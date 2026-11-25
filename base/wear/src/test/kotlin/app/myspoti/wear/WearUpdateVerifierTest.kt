package app.myspoti.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The certificate half of the watch's pre-install verification ([trustedWearUpdate]). */
class WearUpdateVerifierTest {
    private val keyA = setOf("cert-a")
    private val keyB = setOf("cert-b")

    @Test fun theSameCertificateMatches() {
        assertTrue(certificatesMatch(keyA, keyA, keyA))
        assertTrue(certificatesMatch(setOf("a", "b"), setOf("b", "a"), emptySet()))
    }

    @Test fun aForeignCertificateIsRejected() {
        assertFalse(certificatesMatch(keyA, keyB, keyB))
        assertFalse(certificatesMatch(keyA, keyB, emptySet()))
        assertFalse(certificatesMatch(keyA, emptySet(), emptySet()))
        assertFalse(certificatesMatch(setOf("a", "b"), setOf("a", "c"), setOf("a", "b", "c")))
    }

    @Test fun aRotatedSingleKeyMatchesThroughItsHistory() {
        // One signer before and after, and the new APK's history holds the old key.
        assertTrue(certificatesMatch(keyA, keyB, setOf("cert-b", "cert-a")))
        // A multi-signer install gets no history shortcut.
        assertFalse(certificatesMatch(setOf("a", "b"), keyB, setOf("a", "b")))
    }

    @Test fun anInstalledAppWithoutCertificatesNeverMatches() {
        assertFalse(certificatesMatch(emptySet(), keyA, keyA))
    }
}
