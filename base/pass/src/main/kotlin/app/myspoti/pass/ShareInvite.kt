package app.myspoti.pass

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * What a shared playlist's QR code says: where the sending phone listens on the local
 * network, its public key, a one-time token and the playlist's name, as a link a phone's
 * camera opens in the app straight on the confirmation:
 * `orpheon://share?v=1&a=192.168.1.20:41234&k=…&t=…&n=Laroi+unreleased`. The same shape as
 * [PassInvite] — its validation is reused wholesale, above all the local-address rule: a
 * photographed or forwarded code can never point a receiver at an internet host. A
 * different host segment than the pass's, so the two parsers can never take each other's
 * codes.
 */
data class ShareInvite(val host: String, val port: Int, val key: ByteArray, val token: ByteArray, val name: String) {
    fun toText(): String = "$LINK?v=1&a=$host:$port&k=${b64(key)}&t=${b64(token)}&n=${URLEncoder.encode(name.take(40), "UTF-8")}"

    companion object {
        const val LINK_HOST = "share"
        const val LINK = "${PassInvite.LINK_SCHEME}://$LINK_HOST"
        private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        private fun unb64(s: String) = Base64.getUrlDecoder().decode(s)

        /**
         * The invitation in a scanned text or an opened link, or null when it is not one
         * (another QR code, the pass's host, a missing or malformed part, an address
         * outside the local network).
         */
        fun parse(text: String): ShareInvite? = runCatching {
            val t = text.trim()
            // The scheme and the host as the link gives them, any case; then nothing but the query
            // (orpheon://share/x?… or orpheon://share.evil?… are not this app's).
            if (t.length <= LINK.length || !t.regionMatches(0, LINK, 0, LINK.length, ignoreCase = true)) return null
            val rest = t.substring(LINK.length).removePrefix("/")
            if (!rest.startsWith("?")) return null
            val query = rest.substring(1).substringBefore('#')
            val q = query.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            // A later version of the link that this app does not know: not guessed at.
            if (q["v"] != null && q["v"] != "1") return null
            val a = q["a"]!!
            val host = a.substringBeforeLast(':')
            // Only an address of a local network: a QR code cannot send the receiver elsewhere.
            require(PassInvite.isPrivate(host)) { "not a local address" }
            val port = a.substringAfterLast(':').toInt()
            require(port in 1..65535) { "no port" }
            val key = unb64(q["k"]!!)
            val token = unb64(q["t"]!!)
            // The sender's key must be one (a P-256 public key) and the token whole.
            PassCrypto.publicKey(key)
            require(token.size == 16) { "token of ${token.size} bytes" }
            // The name is shown on the confirmation: no control or direction characters that could
            // make it read as something else.
            val name = q["n"].orEmpty().filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim().take(40)
            ShareInvite(host, port, key, token, name)
        }.getOrNull()
    }
}
