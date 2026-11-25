package com.music.spotui.data.api

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
        assertEquals(PlaylistLink.Amazon, PlaylistLinks.parse("https://music.amazon.com/playlists/B0CPYZ1234"))
        assertEquals(PlaylistLink.Amazon, PlaylistLinks.parse("https://music.amazon.co.uk/playlists/B0CPYZ1234?marketplaceId=A1F83G8C2ARO7P"))
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
        }}}
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
