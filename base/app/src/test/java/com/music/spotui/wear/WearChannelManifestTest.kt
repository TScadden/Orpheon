/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.wear

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The manifest's side of the watch link (v33): the phone's files cross to the watch on
 * the /scadify/file channel, but WearLinkService's CHANNEL_EVENT filters covered only
 * /orpheon — so the opening of a file channel was never delivered, serveFile never ran,
 * and every pull of an uncached local file hung in silence (the watch published
 * "playing" at 0:00 with no byte ever arriving). These resolve the manifest's filters
 * the way the Wearable service does: an intent of the channel event and the channel's
 * address must land on the link service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WearChannelManifestTest {

    private fun services(action: String, uri: String): List<String> {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(action).setData(Uri.parse(uri)).setPackage(app.packageName)
        return app.packageManager.queryIntentServices(intent, 0).map { it.serviceInfo.name }
    }

    @Test fun theFileChannelReachesTheLinkService() {
        assertTrue(
            services("com.google.android.gms.wearable.CHANNEL_EVENT", "wear://watch/scadify/file")
                .contains("com.music.spotui.wear.WearLinkService"),
        )
    }

    @Test fun theExtensionsChannelStillReachesTheLinkService() {
        assertTrue(
            services("com.google.android.gms.wearable.CHANNEL_EVENT", "wear://watch/orpheon/extensions")
                .contains("com.music.spotui.wear.WearLinkService"),
        )
    }

    @Test fun watchModeMessagesStillReachTheLinkService() {
        assertTrue(
            services("com.google.android.gms.wearable.REQUEST_RECEIVED", "wear://watch/scadify/nowplaying")
                .contains("com.music.spotui.wear.WearLinkService"),
        )
    }
}
