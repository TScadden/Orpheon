package app.myspoti.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A phone file being pulled to the watch (Scadify v21): the writer (the pull) appends
 * the file's bytes as they arrive; the reader (the player) reads from any position,
 * waiting where the pull has not reached yet — so a song starts playing while its
 * file is still crossing, and the same bytes are the cache entry once the pull lands.
 * One writer, any number of sequential reads; all state moves under one lock.
 */
class PullBuffer private constructor(
    val file: File,
    val expectedSize: Long,
    complete: Boolean,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val out: FileOutputStream? = if (complete) null else FileOutputStream(file)
    private val raf = RandomAccessFile(file, "r")

    @Volatile var written: Long = if (complete) file.length() else 0L
        private set
    @Volatile var done: Boolean = complete
        private set
    @Volatile var failure: Throwable? = null
        private set

    /** The pull's end: null on success, the failure on failure. */
    val end = CompletableDeferred<Throwable?>().apply { if (complete) complete(null) }

    /** Appends pulled bytes (the writer's side). */
    fun append(bytes: ByteArray, offset: Int, length: Int) {
        val stream = out ?: throw IllegalStateException("a completed buffer takes no more bytes")
        stream.write(bytes, offset, length)
        stream.flush()
        lock.withLock {
            written += length
            changed.signalAll()
        }
    }

    /** The pull landed whole: readers at the end now read end-of-file. */
    fun complete() {
        runCatching { out?.close() }
        lock.withLock {
            done = true
            changed.signalAll()
        }
        end.complete(null)
    }

    /** The pull failed: waiting readers wake to the failure instead of waiting on. */
    fun fail(error: Throwable) {
        runCatching { out?.close() }
        lock.withLock {
            failure = error
            changed.signalAll()
        }
        end.complete(error)
    }

    /**
     * Reads up to [length] bytes at [position] into [buffer]; waits while the pull has
     * not reached [position] yet. Returns -1 at the end of a completed pull, and throws
     * the pull's failure as an [IOException] so the player treats it as a broken source.
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        lock.withLock {
            while (true) {
                failure?.let { throw IOException("the pull failed", it) }
                val available = written - position
                if (available > 0) {
                    val n = minOf(length.toLong(), available).toInt()
                    raf.seek(position)
                    return raf.read(buffer, offset, n)
                }
                if (done) return -1
                changed.await(250, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** What a data source opening at [position] can promise: the rest of the file, or unknown while it is still crossing. */
    fun openLength(position: Long): Long = when {
        expectedSize > 0 -> maxOf(0L, expectedSize - position)
        done -> maxOf(0L, written - position)
        else -> C.LENGTH_UNSET
    }

    companion object {
        /** A fresh pull into [file] (any file there is replaced), of [expectedSize] bytes where known. */
        fun fresh(file: File, expectedSize: Long): PullBuffer {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            return PullBuffer(file, expectedSize, complete = false)
        }

        /** A buffer over a file already whole on the watch (a cache entry). */
        fun completed(file: File): PullBuffer = PullBuffer(file, file.length(), complete = true)
    }
}

/**
 * The on-watch store of pulled phone files (Scadify v21): one folder, a hard cap of
 * [CAP_BYTES] enforced whenever a pull is kept, the least recently played song evicted
 * first. Keys bind a song to the file the phone described — its address, size and
 * last-modified time — so a file changed on the phone keys differently and is pulled
 * again rather than played stale. Nothing outside this folder is ever touched.
 */
class WatchPullCache(private val dir: File) {
    init { dir.mkdirs() }

    fun key(localUri: String, size: Long, modified: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$localUri|$size|$modified".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun entryFile(key: String): File = File(dir, "$key.song")
    fun tempFile(key: String): File = File(dir, "$key.part")

    /** The kept song for [key], its last-played time refreshed; null when it is not on the watch. */
    fun entry(key: String): File? {
        val f = entryFile(key)
        if (!f.isFile || f.length() == 0L) return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    fun discardTemp(key: String) { tempFile(key).delete() }

    /**
     * Keeps a landed pull: moves its temp file into place and evicts back under the
     * cap. A song that alone is over the cap is not kept (its temp is deleted; the
     * pull that is playing it reads on through its open handle). False when not kept.
     */
    fun commit(key: String): Boolean {
        val temp = tempFile(key)
        if (!temp.isFile) return false
        if (temp.length() > CAP_BYTES) {
            temp.delete()
            return false
        }
        val target = entryFile(key)
        target.delete()
        if (!temp.renameTo(target)) return false
        target.setLastModified(System.currentTimeMillis())
        evict(protect = key)
        return true
    }

    /** Deletes least recently played songs (never [protect]) until the store is within the cap. */
    fun evict(protect: String? = null) {
        var total = usage()
        if (total <= CAP_BYTES) return
        entries()
            .filter { it.name.removeSuffix(".song") != protect }
            .sortedBy { it.lastModified() }
            .forEach { f ->
                if (total <= CAP_BYTES) return
                total -= f.length()
                f.delete()
            }
    }

    fun entries(): List<File> = dir.listFiles { f -> f.name.endsWith(".song") }?.toList().orEmpty()

    fun usage(): Long = entries().sumOf { it.length() }

    /** Every kept song and half-pulled temp goes; nothing outside this folder exists to go. */
    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    /** Half-pulled temps from a previous run of the app: no pull owns them any more. */
    fun cleanTemps() { dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } }

    companion object {
        /** The hard cap Tyson set for pulled songs on the watch: 250 MB. */
        const val CAP_BYTES = 250L * 1024 * 1024
    }
}

/**
 * The pulls themselves (Scadify v21): one per song, shared between the player and the
 * prefetch of the next song, each filling its [PullBuffer] from the phone over the
 * Data Layer ([PhoneLink.pullFile]) and keeping the landed file in the [WatchPullCache].
 * A pull that cannot be had fails its buffer plainly — the phone unreachable, or the
 * file gone — and a guard closes a pull that hangs, so nothing waits forever.
 */
object WatchPulls {
    private const val TAG = "Orpheon"
    private const val PULL_TIMEOUT_MS = 240_000L
    private const val CHUNK_BYTES = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, PullBuffer>()
    @Volatile private var cache: WatchPullCache? = null

    fun init(context: Context) {
        if (cache != null) return
        synchronized(this) {
            if (cache == null) {
                cache = WatchPullCache(File(context.applicationContext.cacheDir, "watch-pulls"))
                    .also { it.cleanTemps() }
            }
        }
    }

    private fun cache(context: Context): WatchPullCache {
        init(context)
        return cache!!
    }

    /** How much of the watch the pulled songs take, for the settings page. */
    fun usageBytes(context: Context): Long = cache(context).usage()

    /** Empties the on-watch songs (the settings page's Clear). */
    fun clear(context: Context) = cache(context).clear()

    /**
     * The buffer to play [track] from: the kept cache entry when the song is already
     * on the watch, else a live pull — started now, filling the cache as it plays.
     * A pull already running for the song (the next-song prefetch) is shared.
     */
    fun open(context: Context, track: WatchTrack): PullBuffer {
        val app = context.applicationContext
        val store = cache(app)
        val key = store.key(track.localUri, track.fileSize, track.fileModified)
        store.entry(key)?.let { return PullBuffer.completed(it) }
        // One pull per song: the player's resolve and the next-song prefetch can ask
        // together, so the start is decided inside the lock, not after it.
        return synchronized(jobs) {
            jobs[key]?.takeIf { it.failure == null } ?: run {
                val fresh = PullBuffer.fresh(store.tempFile(key), track.fileSize)
                jobs[key] = fresh
                scope.launch { pull(app, track, key, fresh) }
                fresh
            }
        }
    }

    private suspend fun pull(app: Context, track: WatchTrack, key: String, buffer: PullBuffer) {
        val store = cache(app)
        val closer = AtomicReference<(() -> Unit)?>(null)
        val guard = scope.launch {
            delay(PULL_TIMEOUT_MS)
            closer.get()?.invoke()
            if (!buffer.done && buffer.failure == null) buffer.fail(PullFailed(PullFailed.Kind.UNREACHABLE))
        }
        try {
            val filePull = PhoneLink(app).pullFile(track.localUri) { closer.set(it) }
            val reported = filePull.size
            filePull.use { p ->
                val chunk = ByteArray(CHUNK_BYTES)
                while (true) {
                    val n = p.input.read(chunk)
                    if (n < 0) break
                    if (n > 0) buffer.append(chunk, 0, n)
                }
            }
            val expected = if (reported >= 0) reported else track.fileSize
            if (expected > 0 && buffer.written != expected) throw PullFailed(PullFailed.Kind.UNREACHABLE)
            buffer.complete()
            if (!store.commit(key)) Log.d(TAG, "pulled song not kept on the watch (over the cap, or could not be filed)")
        } catch (e: PullFailed) {
            if (!buffer.done && buffer.failure == null) buffer.fail(e)
            store.discardTemp(key)
        } catch (e: Exception) {
            if (!buffer.done && buffer.failure == null) buffer.fail(PullFailed(PullFailed.Kind.UNREACHABLE, e))
            store.discardTemp(key)
        } finally {
            guard.cancel()
            jobs.remove(key, buffer)
        }
    }
}

/** The player's end of a [PullBuffer]: ExoPlayer reads the song while its pull is still crossing. */
class PullDataSource(private val buffer: PullBuffer) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var position = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        position = dataSpec.position
        transferInitializing(dataSpec)
        return buffer.openLength(dataSpec.position)
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = buffer.readAt(position, destination, offset, length)
        if (n > 0) {
            position += n
            bytesTransferred(n)
        }
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        transferEnded()
    }

    class Factory(private val buffer: PullBuffer) : DataSource.Factory {
        override fun createDataSource(): DataSource = PullDataSource(buffer)
    }
}
