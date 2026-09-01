package com.yunjelee.securemsg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release key rotation, checked against the updater's own verdict.
 *
 * Digests are the real ones from securemsg-release.apk and the debug key the
 * installed base carries; if this ever fails, every existing install refuses
 * the update and the only way forward is an uninstall — which destroys the
 * Keystore-held device key and all local history.
 */
class SigningRotationTest {
    private val debugCert = "a258a11699886e3ba703ea0e6b6de3ca60cd53c46d13cd84f8cb3b9f1c2acbad"
    private val releaseCert = "fafb9e26dfeb192a5f27755d7e3986ab28b823b2aea5c5a923988aff1c2cb6e6"

    @Test
    fun `a phone signed with the debug key accepts the rotated release`() {
        assertTrue(
            UpdateValidation.signingCertificatesMatch(
                installedCurrentDigests = setOf(debugCert),
                installedHasMultipleSigners = false,
                archiveCurrentDigests = setOf(releaseCert),
                archiveHasMultipleSigners = false,
                archiveHistoryDigests = listOf(debugCert, releaseCert),
            ),
        )
    }

    @Test
    fun `an already-rotated phone accepts the next release`() {
        assertTrue(
            UpdateValidation.signingCertificatesMatch(
                installedCurrentDigests = setOf(releaseCert),
                installedHasMultipleSigners = false,
                archiveCurrentDigests = setOf(releaseCert),
                archiveHasMultipleSigners = false,
                archiveHistoryDigests = listOf(debugCert, releaseCert),
            ),
        )
    }

    @Test
    fun `a rotated phone refuses an APK signed with the retired debug key alone`() {
        // The point of rotating: the public debug key must stop being able to
        // push an update once a phone has moved to the release key.
        assertFalse(
            UpdateValidation.signingCertificatesMatch(
                installedCurrentDigests = setOf(releaseCert),
                installedHasMultipleSigners = false,
                archiveCurrentDigests = setOf(debugCert),
                archiveHasMultipleSigners = false,
                archiveHistoryDigests = listOf(debugCert),
            ),
        )
    }
}
