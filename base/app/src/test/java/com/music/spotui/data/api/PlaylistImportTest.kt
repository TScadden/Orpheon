package com.music.spotui.data.api

import com.music.spotui.data.local.ImportedSong
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportTest {

    private val id = "37i9dQZF1DXcBWIGoYBM5M"

    @Test fun spotifyLinksInTheirShapes() {
        assertEquals(PlaylistLink.Spotify(id), PlaylistLinks.parse("https://open.spotify.com/playlist/$id?si=abc123"))
        assertEquals(PlaylistLink.Spotify(id), PlaylistLinks.parse("https://open.spotify.com/intl-de/playlist/$id"))
        assertEquals(PlaylistLink.Spotify(id), PlaylistLinks.parse("https://open.spotify.com/embed/playlist/$id?utm_source=generator"))
        assertEquals(PlaylistLink.Spotify(id), PlaylistLinks.parse("spotify:playlist:$id"))
        assertEquals(PlaylistLink.Spotify(id), PlaylistLinks.parse("  https://open.spotify.com/playlist/$id  "))
    }

    @Test fun amazonAndGarbageLinks() {
        // Editorial and user playlists, on the .com host and a regional one: the link
        // is kept whole, because its host and territory suffix are part of the address
        // the reader must open.
        assertEquals(
            PlaylistLink.Amazon("https://music.amazon.com/playlists/B0CPYZ1234"),
            PlaylistLinks.parse("https://music.amazon.com/playlists/B0CPYZ1234"),
        )
        assertEquals(
            PlaylistLink.Amazon("https://music.amazon.co.uk/playlists/B0CPYZ1234?marketplaceId=A1F83G8C2ARO7P"),
            PlaylistLinks.parse("https://music.amazon.co.uk/playlists/B0CPYZ1234?marketplaceId=A1F83G8C2ARO7P"),
        )
        assertEquals(
            PlaylistLink.Amazon("https://music.amazon.com/user-playlists/13490f326c65481596accb1ab639b504sune?ref=dm_sh_x"),
            PlaylistLinks.parse("https://music.amazon.com/user-playlists/13490f326c65481596accb1ab639b504sune?ref=dm_sh_x"),
        )
        assertEquals(
            PlaylistLink.Amazon("https://music.amazon.de/user-playlists/abcdef0123456789abcdef0123456789jajp"),
            PlaylistLinks.parse("  https://music.amazon.de/user-playlists/abcdef0123456789abcdef0123456789jajp  "),
        )
        // An Amazon link that names no playlist is not a playlist link.
        assertEquals(PlaylistLink.Unknown, PlaylistLinks.parse("https://music.amazon.com/albums/B0CPYZ1234"))
        assertEquals(PlaylistLink.Unknown, PlaylistLinks.parse("https://music.amazon.com/artists/B0CPYZ1234"))
        assertEquals(PlaylistLink.Unknown, PlaylistLinks.parse("https://example.com/playlist/$id"))
        assertEquals(PlaylistLink.Unknown, PlaylistLinks.parse("https://open.spotify.com/album/$id"))
        assertEquals(PlaylistLink.Unknown, PlaylistLinks.parse("not a link"))
        assertEquals(PlaylistLink.Unknown, PlaylistLinks.parse(""))
    }

    private val nextData = """
        {"props":{"pageProps":{"state":{
          "data":{"entity":{"name":"Evening Mix","trackCount":3,"trackList":[
            {"uri":"spotify:track:AAA","title":"One","subtitle":"Artist A","duration":201000},
            {"uri":"spotify:track:BBB","title":"Two","subtitle":"Artist B","duration":182000},
            {"title":"Broken row","subtitle":"Nobody","duration":1000}
          ]}},
          "settings":{"session":{"accessToken":"tok123"}}
        }}}}
    """.trimIndent()

    @Test fun embedPageParsesAndCountsTheBrokenRow() {
        val parsed = SpotifyEmbed.parseData(JSONObject(nextData))!!
        assertEquals("Evening Mix", parsed.name)
        // Two songs read, three declared: the import can say "2 of 3", nothing silently dropped.
        assertEquals(2, parsed.tracks.size)
        assertEquals(3, parsed.declaredTotal)
        assertEquals("tok123", parsed.accessToken)
        assertEquals("AAA", parsed.tracks[0].spotifyTrackId)
        assertEquals("One", parsed.tracks[0].title)
        assertEquals("Artist A", parsed.tracks[0].artist)
        assertEquals(201_000L, parsed.tracks[0].durationMs)
    }

    @Test fun embedHtmlExtraction() {
        val html = "<html><head><script id=\"__NEXT_DATA__\" type=\"application/json\">$nextData</script></head></html>"
        assertEquals("Evening Mix", SpotifyEmbed.parse(html)?.name)
        assertNull(SpotifyEmbed.parse("<html><body>no data here</body></html>"))
        assertNull(SpotifyEmbed.parseData(JSONObject("""{"props":{"pageProps":{}}}""")))
    }

    @Test fun apiPageCarriesTheIsrcNormalized() {
        val page = SpotifyEmbed.parseApiPage(JSONObject("""
            {"total":2,"items":[
              {"track":{"id":"AAA","name":"One","duration_ms":201000,
                 "artists":[{"name":"Artist A"}],
                 "album":{"name":"Alb","images":[]},
                 "external_ids":{"isrc":"us-s1z-25-00001"}}},
              {"track":{"id":"BBB","name":"Two","duration_ms":182000,"artists":[{"name":"Artist B"}],"album":{"name":"Other","images":[]}}}
            ]}
        """.trimIndent()))
        // The ISRC the API states, normalized as everywhere else in the app; a track
        // whose record carries none reads as "".
        assertEquals("USS1Z2500001", page.tracks[0].isrc)
        assertEquals("", page.tracks[1].isrc)
    }

    private fun song(id: String, title: String, artist: String, album: String = "", cover: String = "", isrc: String = "") =
        ImportedSong("", id, title, artist, album, 100_000L, cover, isrc)

    @Test fun aPartialApiReadCompletesOnlyTheSongsItReturned() {
        // The reported failure: the API's reading came back shorter than the embed's and
        // the old either/or threw its covers and album names away with the rest.
        val embed = listOf(
            song("AAA", "One", "Artist A"),
            song("BBB", "Two", "Artist B"),
            song("CCC", "Three", "Artist C"),
        )
        val api = listOf(song("BBB", "Two", "Artist B", album = "Alb", cover = "https://img/2", isrc = "USS1Z2500001"))
        val merged = SpotifyEmbed.mergeReads(embed, api)
        assertEquals(listOf("AAA", "BBB", "CCC"), merged.map { it.spotifyTrackId })
        assertEquals("", merged[0].album)
        assertEquals("", merged[0].coverUrl)
        assertEquals("Alb", merged[1].album)
        assertEquals("https://img/2", merged[1].coverUrl)
        assertEquals("USS1Z2500001", merged[1].isrc)
        assertEquals("", merged[2].album)
        assertEquals("", merged[2].isrc)
    }

    @Test fun anEmbedOnlyReadIsUnchanged() {
        val embed = listOf(song("AAA", "One", "Artist A"), song("BBB", "Two", "Artist B"))
        assertEquals(embed, SpotifyEmbed.mergeReads(embed, emptyList()))
    }

    @Test fun anApiOnlyReadIsUnchanged() {
        val api = listOf(song("AAA", "One", "Artist A", album = "Alb", cover = "https://img/1"))
        assertEquals(api, SpotifyEmbed.mergeReads(emptyList(), api))
    }

    @Test fun songsOnlyTheApiReachedAreAppendedInApiOrder() {
        val embed = listOf(song("AAA", "One", "Artist A"), song("BBB", "Two", "Artist B"))
        val api = listOf(
            song("CCC", "Three", "Artist C", album = "Third"),
            song("BBB", "Two", "Artist B", album = "Alb"),
            song("DDD", "Four", "Artist D", album = "Fourth"),
        )
        val merged = SpotifyEmbed.mergeReads(embed, api)
        assertEquals(listOf("AAA", "BBB", "CCC", "DDD"), merged.map { it.spotifyTrackId })
        assertEquals("Alb", merged[1].album)
    }

    @Test fun aDuplicateInTheApiReadCompletesOnceAndAddsNoRow() {
        val embed = listOf(song("AAA", "One", "Artist A"), song("BBB", "Two", "Artist B"))
        val api = listOf(
            song("BBB", "Two", "Artist B", cover = "first"),
            song("BBB", "Two", "Artist B", cover = "second"),
        )
        val merged = SpotifyEmbed.mergeReads(embed, api)
        assertEquals(2, merged.size)
        assertEquals("first", merged[1].coverUrl)
    }

    @Test fun aSongIsMatchedByNameWhenItsIdIsMissing() {
        val embed = listOf(song("", "Two", "Artist B"))
        val api = listOf(song("BBB", "two", "artist b", album = "Alb"))
        val merged = SpotifyEmbed.mergeReads(embed, api)
        assertEquals(1, merged.size)
        assertEquals("BBB", merged[0].spotifyTrackId)
        assertEquals("Alb", merged[0].album)
    }

    // The explicit mark, from both Spotify reads (v23): the embed trackList's
    // isExplicit and the API track's explicit. Stated true is EXPLICIT, stated
    // false is CLEAN, and a read that says nothing leaves the song UNKNOWN —
    // never badged, never hidden.
    @Test fun embedTracksCarryTheirExplicitMark() {
        val parsed = SpotifyEmbed.parseData(JSONObject("""
            {"props":{"pageProps":{"state":{
              "data":{"entity":{"name":"Mix","trackCount":3,"trackList":[
                {"uri":"spotify:track:AAA","title":"One","subtitle":"Artist A","duration":201000,"isExplicit":true},
                {"uri":"spotify:track:BBB","title":"Two","subtitle":"Artist B","duration":182000,"isExplicit":false},
                {"uri":"spotify:track:CCC","title":"Three","subtitle":"Artist C","duration":182000}
              ]}},
              "settings":{"session":{"accessToken":"tok123"}}
            }}}}
        """.trimIndent()))!!
        assertEquals(app.myspoti.api.Explicitness.EXPLICIT, parsed.tracks[0].explicit)
        assertEquals(app.myspoti.api.Explicitness.CLEAN, parsed.tracks[1].explicit)
        assertEquals(app.myspoti.api.Explicitness.UNKNOWN, parsed.tracks[2].explicit)
    }

    @Test fun apiTracksCarryTheirExplicitMark() {
        val page = SpotifyEmbed.parseApiPage(JSONObject("""
            {"total":3,"items":[
              {"track":{"id":"AAA","name":"One","duration_ms":201000,"explicit":true,
                 "artists":[{"name":"Artist A"}],"album":{"name":"Alb","images":[]}}},
              {"track":{"id":"BBB","name":"Two","duration_ms":182000,"explicit":false,
                 "artists":[{"name":"Artist B"}],"album":{"name":"Other","images":[]}}},
              {"track":{"id":"CCC","name":"Three","duration_ms":182000,
                 "artists":[{"name":"Artist C"}],"album":{"name":"Other","images":[]}}}
            ]}
        """.trimIndent()))
        assertEquals(app.myspoti.api.Explicitness.EXPLICIT, page.tracks[0].explicit)
        assertEquals(app.myspoti.api.Explicitness.CLEAN, page.tracks[1].explicit)
        assertEquals(app.myspoti.api.Explicitness.UNKNOWN, page.tracks[2].explicit)
    }

    @Test fun theApiExplicitMarkWinsWhenItStatesOne() {
        val embed = listOf(
            song("AAA", "One", "Artist A").copy(explicit = app.myspoti.api.Explicitness.CLEAN),
            song("BBB", "Two", "Artist B").copy(explicit = app.myspoti.api.Explicitness.EXPLICIT),
            song("CCC", "Three", "Artist C"),
        )
        val api = listOf(
            song("AAA", "One", "Artist A").copy(explicit = app.myspoti.api.Explicitness.EXPLICIT),
            song("BBB", "Two", "Artist B"),
            song("CCC", "Three", "Artist C").copy(explicit = app.myspoti.api.Explicitness.CLEAN),
        )
        val merged = SpotifyEmbed.mergeReads(embed, api)
        // The API stated one: it wins over the embed's.
        assertEquals(app.myspoti.api.Explicitness.EXPLICIT, merged[0].explicit)
        // The API said nothing: the embed's judgement stands.
        assertEquals(app.myspoti.api.Explicitness.EXPLICIT, merged[1].explicit)
        assertEquals(app.myspoti.api.Explicitness.CLEAN, merged[2].explicit)
    }

    @Test fun apiPageParsesSkipsRemovedAndKeepsTotal() {
        val page = SpotifyEmbed.parseApiPage(JSONObject("""
            {"total":3,"next":"https://api.spotify.com/v1/playlists/x/tracks?offset=100","items":[
              {"track":{"id":"AAA","name":"One","duration_ms":201000,
                 "artists":[{"name":"Artist A"},{"name":"Guest"}],
                 "album":{"name":"Alb","images":[{"url":"big","width":640},{"url":"mid","width":300}]}}},
              {"track":null},
              {"track":{"id":"BBB","name":"Two","duration_ms":182000,"artists":[{"name":"Artist B"}],"album":{"name":"Other","images":[]}}}
            ]}
        """.trimIndent()))
        assertEquals(3, page.total)
        assertTrue(page.hasNext)
        assertEquals(2, page.tracks.size)
        assertEquals("Artist A, Guest", page.tracks[0].artist)
        assertEquals("mid", page.tracks[0].coverUrl)
        assertEquals("", page.tracks[1].coverUrl)
    }
}
