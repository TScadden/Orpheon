package app.myspoti.pass

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A playlist crossing the room: the sending phone hosts, the receiving phone scans its
 * [ShareInvite] and asks. Every message is sealed with [PassCrypto] exactly as the pass
 * seals its one transfer — the receiver seals to the key in the code, the sender seals
 * back to the key the receiver's first message carried, the code's one-time token is the
 * salt and associated data throughout — so a device that never saw the code can neither
 * read the songs nor pose as either side.
 *
 * Frames: 4 bytes of length, then one sealed box (2 bytes of key length, the sender's
 * public key, the 12-byte nonce, the ciphertext — a [PassCrypto.Sealed] laid out). A
 * box's plaintext starts with a kind byte: [KIND_TEXT] for the conversation's lines,
 * [KIND_DATA] for a piece of a song. Songs travel as a run of data boxes, streamed off
 * the disk and onto it — never whole in memory, never base64.
 *
 * The conversation:
 *   receiver → sender:  HELLO\n<its name>
 *   sender → receiver:  MANIFEST\n<the bundle's manifest.json, the file share's own>
 *   receiver → sender:  FILE\n<entry>          (once per song it does not already own)
 *   sender → receiver:  DATA\n<size> then data boxes totalling <size>, or ERR\n<why>
 *   receiver → sender:  DONE                   (the sender then stops listening)
 */
private const val KIND_TEXT: Byte = 1
private const val KIND_DATA: Byte = 0
private const val MAX_FRAME = 1_048_576

/** A song piece per data box: big enough to stream well, small enough to seal cheaply. */
private const val CHUNK = 256 * 1024

private fun writeFrame(out: DataOutputStream, sealed: PassCrypto.Sealed) {
    val key = sealed.senderKey
    val payload = ByteArray(2 + key.size + sealed.nonce.size + sealed.box.size)
    payload[0] = (key.size shr 8).toByte(); payload[1] = key.size.toByte()
    key.copyInto(payload, 2)
    sealed.nonce.copyInto(payload, 2 + key.size)
    sealed.box.copyInto(payload, 2 + key.size + sealed.nonce.size)
    require(payload.size <= MAX_FRAME) { "frame of ${payload.size} bytes" }
    out.writeInt(payload.size); out.write(payload); out.flush()
}

private fun readFrame(input: DataInputStream): PassCrypto.Sealed {
    val n = input.readInt()
    require(n in 16..MAX_FRAME) { "frame of $n bytes" }
    val payload = ByteArray(n).also(input::readFully)
    val keyLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    require(keyLen in 1..n - 14) { "frame key of $keyLen bytes" }
    return PassCrypto.Sealed(
        payload.copyOfRange(2, 2 + keyLen),
        payload.copyOfRange(2 + keyLen, 2 + keyLen + 12),
        payload.copyOfRange(2 + keyLen + 12, n),
    )
}

private fun textFrame(text: String) = byteArrayOf(KIND_TEXT) + text.encodeToByteArray()

/**
 * The sending side: listens on the local network for one receiver, for [lifetimeMs] at
 * most — a lifetime that stretches while a transfer is actually moving — and serves it
 * the manifest and the songs it asks for. A connection that does not open with this
 * invitation's key and token is refused and the wait goes on; the first good receiver is
 * the only one, and when it is done (or gone) the listening stops. [onDone] is told
 * once: true when the receiver said it had everything.
 */
class ShareServer(
    private val manifestJson: String,
    private val entries: Map<String, Entry>,
    playlistName: String,
    private val lifetimeMs: Long = 10 * 60_000L,
    private val onConnected: (name: String) -> Unit = {},
    private val onSending: (entry: String, sentInFile: Long, size: Long) -> Unit = { _, _, _ -> },
    private val onFileDone: (entry: String) -> Unit = {},
    private val onDone: (completed: Boolean) -> Unit = {},
    host: String? = PassInvite.localAddress(),
) {
    /** One thing the receiver can ask for: its length, and a fresh stream of it per ask. */
    class Entry(val size: Long, val open: () -> InputStream?)

    private val keys = PassCrypto.keyPair()
    private val token = PassCrypto.randomBytes(16)
    private val address = host ?: throw IllegalStateException("no local network")
    private val server = ServerSocket().apply { reuseAddress = true; soTimeout = 1_000; bind(InetSocketAddress(InetAddress.getByName(address), 0)) }
    private val over = AtomicBoolean(false)
    @Volatile private var lastActivity = System.currentTimeMillis()

    val invite = ShareInvite(address, server.localPort, keys.public.encoded, token, playlistName)

    fun start(): ShareServer {
        Thread({ serve() }, "share-server").apply { isDaemon = true; start() }
        return this
    }

    fun stop() {
        if (over.compareAndSet(false, true)) {
            runCatching { server.close() }
            onDone(false)
        }
    }

    private fun serve() {
        val until = System.currentTimeMillis() + lifetimeMs
        var completed = false
        var sessionHeld = false
        while (!over.get() && !sessionHeld) {
            val now = System.currentTimeMillis()
            if (now >= until && now - lastActivity >= lifetimeMs) break
            val s = runCatching { server.accept() }.getOrNull() ?: continue
            // A knock that is not the receiver (its box does not open, or it does not say
            // hello) is turned away and the wait goes on. The first real receiver holds
            // the one session this code ever serves — finished or failed, it is the last.
            val held = runCatching { session(s) }.getOrNull()
            if (held != null) { sessionHeld = true; completed = held }
        }
        if (over.compareAndSet(false, true)) {
            runCatching { server.close() }
            onDone(completed)
        }
    }

    /**
     * One connection: null when it was not the receiver at all (the wait goes on), or the
     * session's end — true when the receiver said it had everything, false when it went
     * away first. Either way the session was the transfer, and there is no second one.
     */
    private fun session(s: Socket): Boolean? {
        s.use {
            s.soTimeout = 30_000
            val input = DataInputStream(s.getInputStream().buffered())
            val out = DataOutputStream(s.getOutputStream().buffered())
            // The first box must open with this invitation's key and token and say hello:
            // anything else is not the receiver, and the wait for it goes on.
            val first = runCatching { open(input) }.getOrNull() ?: return null
            val lines = first.first.decodeToString().lines()
            if (lines.firstOrNull() != "HELLO") return null
            val peerKey = first.second
            val name = lines.getOrNull(1).orEmpty()
                .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim().take(40)
            lastActivity = System.currentTimeMillis()
            onConnected(name)
            send(out, peerKey, textFrame("MANIFEST\n$manifestJson"))
            while (true) {
                val plain = runCatching { open(input).first }.getOrNull() ?: return false
                lastActivity = System.currentTimeMillis()
                val ask = plain.decodeToString().lines()
                when (ask.firstOrNull()) {
                    "DONE" -> return true
                    "FILE" -> if (!serveFile(out, peerKey, ask.getOrNull(1).orEmpty())) return false
                    else -> return false
                }
            }
        }
    }

    private fun serveFile(out: DataOutputStream, peerKey: ByteArray, entryName: String): Boolean {
        val entry = entries[entryName]
        val stream = entry?.open?.invoke()
        if (entry == null || stream == null) {
            send(out, peerKey, textFrame("ERR\nnot on this phone"))
            return true
        }
        send(out, peerKey, textFrame("DATA\n${entry.size}"))
        stream.use { input ->
            val buf = ByteArray(CHUNK)
            var sent = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val piece = ByteArray(1 + n)
                piece[0] = KIND_DATA
                buf.copyInto(piece, 1, 0, n)
                send(out, peerKey, piece)
                sent += n
                lastActivity = System.currentTimeMillis()
                onSending(entryName, sent, entry.size)
            }
            check(sent == entry.size) { "song changed size while sending" }
        }
        onFileDone(entryName)
        return true
    }

    /** The next box off the wire, opened with this server's key and token: its text, and the key that sealed it. */
    private fun open(input: DataInputStream): Pair<ByteArray, ByteArray> {
        val sealed = readFrame(input)
        val plain = PassCrypto.open(keys, token, sealed)
        if (plain.isEmpty() || plain[0] != KIND_TEXT) throw IOException("not a text frame")
        return plain.copyOfRange(1, plain.size) to sealed.senderKey
    }

    private fun send(out: DataOutputStream, peerKey: ByteArray, plain: ByteArray) {
        writeFrame(out, PassCrypto.sealWith(keys, peerKey, token, plain))
    }
}

/**
 * The receiving side: connects to the [invite]'s sender, takes the manifest, then asks
 * for the songs one by one and streams each onto [fetch]'s output. Throws [IOException]
 * with the reason in plain words (no route to the phone, its code went stale, it refused)
 * — the screens show that reason, never a hang: every wait here is bounded.
 */
class ShareClient(private val invite: ShareInvite, private val deviceName: String) {
    private val keys: KeyPair = PassCrypto.keyPair()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var out: DataOutputStream? = null

    /** Says hello and brings back the bundle's manifest, word for word as the sender wrote it. */
    fun connect(connectTimeoutMs: Int = 8_000, readTimeoutMs: Int = 30_000): String {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(invite.host, invite.port), connectTimeoutMs)
        } catch (e: Exception) {
            throw IOException("could not reach ${invite.host} — same Wi-Fi?", e)
        }
        s.soTimeout = readTimeoutMs
        socket = s
        input = DataInputStream(s.getInputStream().buffered())
        out = DataOutputStream(s.getOutputStream().buffered())
        send(textFrame("HELLO\n$deviceName"))
        val reply = receiveText()
        val lines = reply.lines()
        if (lines.firstOrNull() != "MANIFEST") throw IOException("the other phone answered oddly")
        return reply.substringAfter('\n')
    }

    /**
     * Asks for [entry] and streams it into [out], [onProgress] told of the bytes as they
     * land. The count written is returned; checking it (and the hash) against the manifest
     * is the caller's word, not the wire's.
     */
    fun fetch(entry: String, out: OutputStream, onProgress: (written: Long) -> Unit = {}): Long {
        val inp = input ?: throw IOException("not connected")
        send(textFrame("FILE\n$entry"))
        val header = receiveText().lines()
        when (header.firstOrNull()) {
            "ERR" -> throw IOException(header.getOrNull(1) ?: "the other phone refused that song")
            "DATA" -> Unit
            else -> throw IOException("the other phone answered oddly")
        }
        val size = header.getOrNull(1)?.toLongOrNull() ?: throw IOException("the other phone answered oddly")
        var written = 0L
        while (written < size) {
            val plain = openFrame(inp)
            if (plain.isEmpty() || plain[0] != KIND_DATA) throw IOException("the other phone answered oddly")
            val n = minOf(plain.size - 1, (size - written).toInt())
            out.write(plain, 1, n)
            written += n
            onProgress(written)
        }
        out.flush()
        return written
    }

    /** Tells the sender everything arrived, and hangs up. Quiet on purpose: the work is done either way. */
    fun done() {
        runCatching { send(textFrame("DONE")) }
        close()
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; out = null
    }

    private fun send(plain: ByteArray) {
        val o = out ?: throw IOException("not connected")
        writeFrame(o, PassCrypto.sealWith(keys, invite.key, invite.token, plain))
    }

    private fun openFrame(inp: DataInputStream): ByteArray =
        PassCrypto.open(keys, invite.token, readFrame(inp))

    private fun receiveText(): String {
        val inp = input ?: throw IOException("not connected")
        val plain = openFrame(inp)
        if (plain.isEmpty() || plain[0] != KIND_TEXT) throw IOException("the other phone answered oddly")
        return plain.copyOfRange(1, plain.size).decodeToString()
    }
}
