/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.wear

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A failed play says so where the tap happened (v33): a pull failure used to render
 * only inside the player page — a swipe away from the playlist the user tapped, which
 * itself showed nothing at all, as if the tap had been ignored. The playlist page now
 * carries the same failure line as the player page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WatchPlaylistErrorTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun aPullFailureShowsOnThePlaylistPage() {
        rule.setContent {
            WatchTheme {
                WatchPlaylist(
                    list = WatchList("scadify:local:lp1", "Road Mix", ""),
                    tracks = Result.success(emptyList()),
                    onBack = {}, onPlayList = {}, onPlayTrack = {}, onRetry = {},
                    error = "Can't reach your phone.",
                )
            }
        }
        rule.onNodeWithText("That did not work: Can't reach your phone.", substring = true).assertIsDisplayed()
    }

    @Test fun noFailureNoLine() {
        rule.setContent {
            WatchTheme {
                WatchPlaylist(
                    list = WatchList("scadify:local:lp1", "Road Mix", ""),
                    tracks = Result.success(emptyList()),
                    onBack = {}, onPlayList = {}, onPlayTrack = {}, onRetry = {},
                )
            }
        }
        rule.onNodeWithText("That did not work", substring = true).assertDoesNotExist()
    }
}
