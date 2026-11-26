package com.music.spotui.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Friends screen is gone (v24, Tyson's call): the friend-activity feed was a Spotify
 * leftover that the accountless backend could only ever answer with an empty list. No
 * route may resolve to it again — this pins the removal where a destination would be
 * declared.
 */
class RoutesTest {

    @Test fun noRouteLeadsToAFriendsDestination() {
        val objects = Routes::class.java.declaredClasses.mapNotNull { c ->
            runCatching { c.getField("INSTANCE").get(null) as Routes }.getOrNull()
        }
        assertTrue("the routes did not load", objects.isNotEmpty())
        assertTrue(objects.none { it.route == "friends" })
        assertTrue(Routes::class.java.declaredClasses.none { it.simpleName == "Friends" })
    }
}
