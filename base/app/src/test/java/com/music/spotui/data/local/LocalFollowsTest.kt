package com.music.spotui.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFollowsTest {

    @Test fun followsSurviveARestartAsText() {
        val follows = listOf(
            FollowedArtist("deezer:artist:123", "The Kid LAROI", "https://img/1", 2000L),
            FollowedArtist("spotify:artist:abc", "Other Artist", "", 1000L),
        )
        assertEquals(follows, LocalFollows.decode(LocalFollows.encode(follows)))
        // Another version's file, or no items at all: no follows, no crash.
        assertEquals(emptyList<FollowedArtist>(), LocalFollows.decode("""{"version":99,"items":[]}"""))
        assertEquals(emptyList<FollowedArtist>(), LocalFollows.decode("""{"version":1}"""))
    }

    @Test fun followAndUnfollowByUri() {
        LocalFollows.items.clear()
        val artist = FollowedArtist("deezer:artist:123", "The Kid LAROI", "https://img/1", 1L)
        LocalFollows.set(artist, true)
        assertTrue(LocalFollows.isFollowed("deezer:artist:123"))
        // A second follow of the same artist keeps one entry, in place.
        LocalFollows.set(artist.copy(name = "Renamed"), true)
        assertEquals(1, LocalFollows.items.size)
        LocalFollows.set(artist, false)
        assertTrue(LocalFollows.items.isEmpty())
        // Unfollowing an artist never followed is a no-op.
        LocalFollows.remove("deezer:artist:999")
        assertTrue(LocalFollows.items.isEmpty())
    }

    @Test fun aNewerFollowListsFirst() {
        LocalFollows.items.clear()
        LocalFollows.set(FollowedArtist("deezer:artist:1", "First", "", 1L), true)
        LocalFollows.set(FollowedArtist("deezer:artist:2", "Second", "", 2L), true)
        assertEquals(listOf("deezer:artist:2", "deezer:artist:1"), LocalFollows.items.map { it.uri })
        LocalFollows.items.clear()
    }
}
