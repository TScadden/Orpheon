package com.music.spotui.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.LocalLikes

/**
 * Saved state of a library item (track in Liked Songs, saved album, followed artist)
 * as Spotify reports it. Every screen reads the same [SongPlayer.library], so a like
 * made anywhere (here or on another device) shows everywhere.
 */
@Composable
fun rememberSaved(uri: String, fallback: Boolean = false): Boolean {
    LaunchedEffect(uri) { SongPlayer.requestSaved(uri) }
    return SongPlayer.library[uri] ?: fallback
}

@Composable
fun rememberTrackSaved(trackId: String): Boolean =
    if (trackId.isBlank()) false else rememberSaved("spotify:track:$trackId")

/**
 * Whether a track is liked, for a row of a list: what Spotify last reported ([SongPlayer.library], which a like
 * made anywhere moves at once), else the list's own batch check [known] (the uris it asked about in one go).
 */
fun trackSaved(trackId: String, known: Set<String>): Boolean {
    val uri = "spotify:track:$trackId"
    return SongPlayer.library[uri] ?: (uri in known)
}

/**
 * Whether a row of the Liked Songs page is still liked, wherever its like lives —
 * the one judgement both looks' Liked Songs lists are built with, so the list, the
 * page's own count and the play queue (which plays the page's rows as they stand)
 * agree by construction.
 *
 * A catalog row is judged by the account library's saved map, as before: an unlike
 * anywhere writes its entry false and the row leaves at once. A phone file's row
 * (its play address is the file's own `local:` address) is judged by [LocalLikes],
 * where its like actually lives: the saved map holds no entry for a file — worse,
 * the bare "spotify:track:" key a blank id builds there could be written false by
 * a batch check that asked about nothing, and judging files by it dropped every
 * liked file from the list while the count and the queue still held them (v27).
 * A row with no Spotify id and no file address is on the page because it is liked;
 * it stays.
 */
fun likedRowShown(song: SongsModel): Boolean =
    likedRowShown(song, SongPlayer.library) { uri -> LocalLikes.isLikedUri(uri) }

/** [likedRowShown] against a given saved map and file-like check: the testable seam. */
internal fun likedRowShown(
    song: SongsModel,
    saved: Map<String, Boolean>,
    isLocalLiked: (String) -> Boolean,
): Boolean {
    if (song.url.startsWith("local:")) return isLocalLiked(song.url.removePrefix("local:"))
    if (song.spotifyTrackId.isBlank()) return true
    return saved["spotify:track:${song.spotifyTrackId}"] != false
}
