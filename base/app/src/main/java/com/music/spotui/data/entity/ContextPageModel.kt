package com.music.spotui.data.entity

/** Everything the album / playlist page shows, loaded by Spotify id. */
@kotlinx.serialization.Serializable
data class ContextPageModel(
    val uri: String,
    val type: String, // "album" | "playlist"
    val name: String,
    val coverUri: String,
    val description: String = "",
    val ownerName: String = "",
    val ownerAvatar: String = "",
    val ownerUri: String = "",
    val metaLine: String = "",
    val explicit: Boolean = false,
    /** Songs the explicit filter kept off this page (0 while explicit music is allowed): the page's hidden note. */
    val hiddenExplicit: Int = 0,
    val tracks: List<SongsModel> = emptyList(),
    val complete: Boolean = true,
    /** A Spotify Blend: its page shows the members and the taste match. */
    val isBlend: Boolean = false,
    /** A member who is not the owner and may leave (`canCancelMembership`). */
    val canLeave: Boolean = false,
    /** Under an album's songs: its full release date, then its label and copyright lines, as Spotify shows them. */
    val footer: List<String> = emptyList(),
)
