package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transfer-service playlist files: three dialects, one reader, refused whole when malformed. */
class PlaylistCsvTest {

    @Test fun soundiizCsv() {
        val parsed = PlaylistCsv.parse("""
            title,artist,album,isrc,platform,trackId,duration,addedDate,addedBy,url
            Midnight Run,The Wanderers,Long Road Home,US-S1Z-25-00001,Amazon Music,B0TRACK001,204,2026-01-01,,https://music.amazon.com/albums/B0ALBUM001?trackAsin=B0TRACK001
            Slow Parade,Ana Beltrán,Harbor Lights,,Amazon Music,B0TRACK003,177,,,
        """.trimIndent())!!
        assertEquals(2, parsed.songs.size)
        val first = parsed.songs[0]
        assertEquals("Midnight Run", first.title)
        assertEquals("The Wanderers", first.artist)
        assertEquals("Long Road Home", first.album)
        // The file's ISRC is the recording's own: kept, normalized as everywhere.
        assertEquals("USS1Z2500001", first.isrc)
        // Soundiiz's trackId for an Amazon playlist is the song's ASIN.
        assertEquals("B0TRACK001", first.asin)
        // Soundiiz durations are seconds.
        assertEquals(204_000L, first.durationMs)
        assertEquals("", parsed.songs[1].isrc)
        assertEquals("B0TRACK003", parsed.songs[1].asin)
        assertEquals("", first.spotifyTrackId)
    }

    @Test fun tuneMyMusicCsvWithItsOwnHeaderWords() {
        val parsed = PlaylistCsv.parse("""
            Track name,Artist name,Album,Playlist name,Type,ISRC
            "Paper, Gold",June Cartel,Static Bloom,Evening Drive Mix,Track,USS1Z2500002
        """.trimIndent())!!
        assertEquals("Evening Drive Mix", parsed.name)
        val song = parsed.songs.single()
        // A quoted field keeps its comma.
        assertEquals("Paper, Gold", song.title)
        assertEquals("June Cartel", song.artist)
        assertEquals("Static Bloom", song.album)
        assertEquals("USS1Z2500002", song.isrc)
    }

    @Test fun freeYourMusicCsvCarriesTheAmazonIdAndLink() {
        val parsed = PlaylistCsv.parse("""
            Playlist,Position,Title,Artists,Album,Length,ISRC,Amazon Track ID,Amazon Track Link
            Evening Drive Mix,1,Midnight Run,The Wanderers,Long Road Home,3:24,USS1Z2500001,B0TRACK001,https://music.amazon.com/albums/B0ALBUM001?trackAsin=B0TRACK001
        """.trimIndent())!!
        assertEquals("Evening Drive Mix", parsed.name)
        val song = parsed.songs.single()
        assertEquals("B0TRACK001", song.asin)
        assertEquals(204_000L, song.durationMs)
        assertEquals("USS1Z2500001", song.isrc)
    }

    @Test fun columnsMayMoveAndTheIsrcColumnMayBeMissing() {
        val parsed = PlaylistCsv.parse("""
            Album;Artist;Title;Duration
            Long Road Home;The Wanderers;Midnight Run;204
        """.trimIndent())!!
        val song = parsed.songs.single()
        assertEquals("Midnight Run", song.title)
        assertEquals("The Wanderers", song.artist)
        assertEquals("", song.isrc)
        assertEquals(204_000L, song.durationMs)
    }

    @Test fun aTabSeparatedFileReads() {
        val parsed = PlaylistCsv.parse("Title\tArtist\tAlbum\nMidnight Run\tThe Wanderers\tLong Road Home\n")!!
        assertEquals("Midnight Run", parsed.songs.single().title)
    }

    @Test fun aHeaderlessTextFileReadsArtistDashTitle() {
        val parsed = PlaylistCsv.parse("""
            The Wanderers - Midnight Run
            June Cartel - Paper & Gold
        """.trimIndent(), fallbackName = "Evening Drive Mix")!!
        assertEquals("Evening Drive Mix", parsed.name)
        assertEquals(listOf("Midnight Run", "Paper & Gold"), parsed.songs.map { it.title })
        assertEquals("The Wanderers", parsed.songs[0].artist)
    }

    @Test fun malformedFilesAreRefusedWhole() {
        assertNull(PlaylistCsv.parse(""))
        assertNull(PlaylistCsv.parse("   \n  "))
        assertNull(PlaylistCsv.parse("just some prose\nwith no playlist in it"))
        // A header with no title column is not a playlist export.
        assertNull(PlaylistCsv.parse("foo,bar,baz\n1,2,3"))
        // A known header with no songs under it imports nothing.
        assertNull(PlaylistCsv.parse("title,artist,album,isrc\n"))
        // Random CSV from another life: columns that name no song fields.
        assertNull(PlaylistCsv.parse("date,amount,note\n2026-01-01,10,Lunch"))
    }

    @Test fun durationsInTheirShapes() {
        assertEquals(204_000L, PlaylistCsv.parseDuration("3:24"))
        assertEquals(3_845_000L, PlaylistCsv.parseDuration("1:04:05"))
        assertEquals(204_000L, PlaylistCsv.parseDuration("204"))
        assertEquals(204_000L, PlaylistCsv.parseDuration("204.0"))
        assertEquals(204_000L, PlaylistCsv.parseDuration("204000"))
        assertEquals(0L, PlaylistCsv.parseDuration(""))
        assertEquals(0L, PlaylistCsv.parseDuration("unknown"))
    }

    @Test fun quotedFieldsKeepCommasQuotesAndLineBreaks() {
        val records = PlaylistCsv.tokenize("\"a,\"\"b\"\"\",c\n\"x\ny\",z", ',')
        assertEquals(listOf(listOf("a,\"b\"", "c"), listOf("x\ny", "z")), records)
    }

    @Test fun aForeignTrackIdIsNotKeptAsAnAsin() {
        // A Spotify-sourced Soundiiz file: its trackId is a Spotify id, not an ASIN,
        // and its ISRC still carries the row.
        val parsed = PlaylistCsv.parse("""
            title,artist,album,isrc,platform,trackId,duration,addedDate,addedBy,url
            Midnight Run,The Wanderers,Long Road Home,USS1Z2500001,Spotify,4uLU6hMCjMI75M1A2tKUQC,204,,,
        """.trimIndent())!!
        val song = parsed.songs.single()
        assertEquals("", song.asin)
        assertEquals("USS1Z2500001", song.isrc)
        assertTrue(song.spotifyTrackId.isBlank())
    }
}
