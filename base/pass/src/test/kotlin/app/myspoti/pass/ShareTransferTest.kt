package app.myspoti.pass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShareTransferTest {

    // ------------------------------------------------------------- the invitation

    private val invite = ShareInvite("192.168.1.20", 41234, PassCrypto.keyPair().public.encoded, PassCrypto.randomBytes(16), "Laroi unreleased")

    private fun same(a: ShareInvite, b: ShareInvite?) {
        assertNotNull(b); b!!
        assertEquals(a.host, b.host); assertEquals(a.port, b.port); assertEquals(a.name, b.name)
        assertTrue(a.key.contentEquals(b.key)); assertTrue(a.token.contentEquals(b.token))
    }

    @Test fun `the share invitation survives its QR text`() {
        val text = invite.toText()
        assertTrue(text, text.startsWith("orpheon://share?v=1&a=192.168.1.20:41234&k="))
        same(invite, ShareInvite.parse(text))
        // As a camera may hand it on: other case for the scheme and host, a slash, spaces.
        same(invite, ShareInvite.parse("  ORPHEON://Share/?" + text.substringAfter('?') + "\n"))
    }

    @Test fun `a share invitation refuses a public address and the pass's codes`() {
        assertNull(ShareInvite.parse(invite.copy(host = "8.8.8.8").toText()))
        assertNull(ShareInvite.parse(invite.copy(host = "127.0.0.1").toText()))
        assertNull(ShareInvite.parse(invite.copy(host = "example.com").toText()))
        // The pass's own code is not a share code, and the other way around.
        val pass = PassInvite("192.168.1.20", 41234, invite.key, invite.token, "TV")
        assertNull(ShareInvite.parse(pass.toText()))
        assertNull(PassInvite.parse(invite.toText()))
        val q = invite.toText().substringAfter('?')
        listOf(
            "orpheon://other?$q", "orpheon://share.example.com?$q", "orpheon://share/x?$q", "orpheon://share",
            "orpheon://share?", "https://share?$q", "orpheon://share?${q.replace("v=1", "v=2")}",
            "orpheon://share?${q.replace(Regex("&t=[^&]*"), "")}",
            "orpheon://share?${q.replace(Regex("&k=[^&]*"), "&k=AAAA")}",
            "orpheon://share?${q.replace(Regex("&t=[^&]*"), "&t=AAAA")}",
            "orpheon://share?${q.replace("192.168.1.20:41234", "192.168.1.20:99999")}",
            "", "orpheon", "Laroi unreleased",
        ).forEach { assertNull(it, ShareInvite.parse(it)) }
    }

    @Test fun `the playlist name on the confirmation loses its control and direction characters`() {
        val back = ShareInvite.parse(invite.copy(name = "Mix\u202E\ntape\u0000").toText())
        assertEquals("Mixtape", back!!.name)
    }

    // ------------------------------------------------------------- the transfer

    private val manifestJson = """{"version":1,"kind":"scadify-playlist","name":"Mix","tracks":[]}"""
    private val songA = ByteArray(700_001) { (it % 251).toByte() } // crosses the chunk boundary
    private val songB = ByteArray(123) { (255 - it % 256).toByte() }

    private fun server(onDone: (Boolean) -> Unit = {}): ShareServer = ShareServer(
        manifestJson = manifestJson,
        entries = mapOf(
            "files/000.mp3" to ShareServer.Entry(songA.size.toLong()) { ByteArrayInputStream(songA) },
            "files/001.mp3" to ShareServer.Entry(songB.size.toLong()) { ByteArrayInputStream(songB) },
            "cover.jpg" to ShareServer.Entry(3) { ByteArrayInputStream(byteArrayOf(9, 8, 7)) },
        ),
        playlistName = "Mix",
        onDone = onDone,
        host = "127.0.0.1",
    ).start()

    @Test fun `a playlist crosses over the wire, manifest and songs byte for byte`() {
        val done = CountDownLatch(1)
        var completed: Boolean? = null
        val s = server { ok -> completed = ok; done.countDown() }
        val client = ShareClient(s.invite, "Receiver phone")
        assertEquals(manifestJson, client.connect())
        val a = ByteArrayOutputStream()
        assertEquals(songA.size.toLong(), client.fetch("files/000.mp3", a))
        assertTrue(songA.contentEquals(a.toByteArray()))
        val b = ByteArrayOutputStream()
        assertEquals(songB.size.toLong(), client.fetch("files/001.mp3", b))
        assertTrue(songB.contentEquals(b.toByteArray()))
        // A song the sender does not have is an answer, not a hang.
        try { client.fetch("files/099.mp3", ByteArrayOutputStream()); fail("a missing song was served") }
        catch (e: IOException) { assertTrue(e.message!!.contains("not on this phone")) }
        client.done()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(true, completed)
    }

    @Test fun `a code with another token cannot take the playlist`() {
        val s = server()
        val thief = ShareClient(s.invite.copy(token = PassCrypto.randomBytes(16)), "Thief")
        try { thief.connect(); fail("a wrong token was answered") } catch (_: IOException) {}
        // The real receiver is still welcome afterwards: a refused knock does not end the wait.
        val client = ShareClient(s.invite, "Receiver phone")
        assertEquals(manifestJson, client.connect())
        client.done()
    }

    @Test fun `one receiver only — after it finishes, the code is spent`() {
        val done = CountDownLatch(1)
        val s = server { done.countDown() }
        val client = ShareClient(s.invite, "Receiver phone")
        client.connect()
        client.done()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val late = ShareClient(s.invite, "Too late")
        try { late.connect(); fail("a second receiver was served") } catch (_: IOException) {}
    }
}
