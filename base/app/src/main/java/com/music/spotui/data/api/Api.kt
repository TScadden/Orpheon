package com.music.spotui.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.util.Log
import app.myspoti.api.Album
import app.myspoti.api.Artist
import app.myspoti.api.Image
import app.myspoti.api.LibraryFilter
import app.myspoti.api.LocalFile
import app.myspoti.api.MediaCard
import app.myspoti.api.MediaKind
import app.myspoti.api.MyspotiApi
import app.myspoti.api.Playlist
import app.myspoti.api.Track
import app.myspoti.api.best
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.ArtistTrackUi
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.ShowPage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.ImportedPlaylist
import com.music.spotui.data.local.ImportedPlaylists
import com.music.spotui.data.local.ImportedSong
import com.music.spotui.data.local.ListenHistory
import com.music.spotui.data.local.LocalFileMatch
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The UI's data source, over [MyspotiApi]. Keeps the `Flow<Response<...>>` contract the
 * ViewModels use; every call maps the API's models to the UI's ones.
 */
class Api constructor(
    private val context: android.content.Context = Graph.app,
) {
    private val api: MyspotiApi get() = MyspotiRuntime.api

    private fun stableId(key: String): Int = key.hashCode() and 0x7fffffff

    /** Imported playlists whose stored songs' repair has been attempted this run. */
    private val importRepairAttempted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val importRepairScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Process-level caches: screens show the last result at once, then refresh. */
    companion object HomeCache {
        /** Releases loaded for an artist page, at most. */
        const val DISCOGRAPHY_MAX = 300
        /** Sentinel id for the pinned "Liked Songs" library entry. */
        const val LIKED_SONGS_ID = "liked-songs"
        /** The Liked Songs artwork, as the library and the home shortcuts draw it. */
        const val LIKED_COVER = "android.resource://${app.myspoti.engine.BuildConfig.APPLICATION_ID}/drawable/liked_cover"
        /** The "Local Files" folder pinned under Liked Songs in the library (not a Spotify item). */
        const val LOCAL_FILES_ID = "local-files"
        /** "Downloaded" (songs kept for offline listening), pinned under Local Files. */
        const val DOWNLOADS_ID = "downloads"
        /** "Your Episodes" (saved podcast episodes). */
        const val EPISODES_ID = "your-episodes"
        /** Prefix of the library id of a followed show in the Podcasts chip (a plain show id otherwise). */
        const val SHOW_PREFIX = "show:"
        /** "New episodes" (unplayed episodes of the followed shows), first of the Podcasts chip's shows. */
        const val NEW_EPISODES_ID = "new-episodes"
        /** Episodes asked for each followed show by the new episodes list, and the most it keeps. */
        private const val NEW_PER_SHOW = 3
        private const val NEW_EPISODES_MAX = 60
        /** The new episodes list is asked for again only after this long (the shows are many queries). */
        private const val NEW_EPISODES_TTL_MS = 30 * 60_000L
        @Volatile var newEpisodes: Pair<Long, List<SongsModel>>? = null
        /** Pages of 50 asked for a show's episodes / chapters, at most (500 rows). */
        const val MAX_EPISODE_PAGES = 10
        /** The page's collection set behind its "Mark as finished" menu. */
        @Volatile var albums: List<AlbumsModel>? = null
        @Volatile var artists: List<ArtistsModel>? = null
        @Volatile var home: HomeFeedModel? = null
        val facets = java.util.concurrent.ConcurrentHashMap<String, HomeFeedModel>()
        @Volatile var library: List<LibraryEntry>? = null
        /** Library as shown by the Library screen (root level, folders closed). */
        @Volatile var libraryRoot: List<LibraryEntry>? = null
        /** Contents of the library folders opened so far, by folder uri. */
        val folders = java.util.concurrent.ConcurrentHashMap<String, List<LibraryEntry>>()

        /** A playlist's songs were changed from the app: the next page of it reads them again. */
        fun invalidatePlaylist(playlistId: String) = PlaylistFresh.drop("spotify:playlist:$playlistId")

        /** Drop all cached feeds (e.g. on logout / account switch). */
        fun clear() {
            PlaylistFresh.drop(null)
            albums = null; artists = null; home = null; library = null
            libraryRoot = null; folders.clear(); newEpisodes = null
            facets.clear()
            LikedCache.tracks = null
            ArtistCache.map.clear()
            ScreenCache.clear()
        }
    }

    object LikedCache {
        @Volatile var tracks: List<SongsModel>? = null
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        @Volatile private var loading: kotlinx.coroutines.Deferred<List<SongsModel>?>? = null

        /**
         * All liked songs, loaded once for the whole app (not again by every screen that needs
         * them, nor started over when that screen closes before the end).
         */
        @Synchronized
        fun loadOnce(): kotlinx.coroutines.Deferred<List<SongsModel>?> {
            tracks?.let { return kotlinx.coroutines.CompletableDeferred(it) }
            loading?.takeIf { it.isActive }?.let { return it }
            return scope.async {
                runCatching {
                    Graph.api.getLikedPage().first { it is Response.Success && it.data.complete || it is Response.Error }
                        .let { (it as? Response.Success)?.data?.tracks }
                }.getOrNull()?.also { if (tracks == null) tracks = it }
            }.also { loading = it }
        }

        /**
         * [head] (the newest liked songs, from Spotify) then the rest of [known] after them.
         * A song unliked inside the head's span is gone from it, so it goes from the list too.
         */
        /** True when [head] ends inside [known] (or is the whole list), so [merge] loses nothing. */
        fun reaches(head: List<SongsModel>, known: List<SongsModel>): Boolean {
            if (head.size < 50) return true
            val last = head.last().spotifyTrackId
            return known.any { it.spotifyTrackId == last }
        }

        fun merge(head: List<SongsModel>, known: List<SongsModel>): List<SongsModel> {
            val ids = head.mapTo(HashSet()) { it.spotifyTrackId }
            val lastHead = head.lastOrNull()?.spotifyTrackId
            // What came after the head's last song in the old list is older: kept as it was.
            val tailStart = known.indexOfFirst { it.spotifyTrackId == lastHead }.let { if (it < 0) known.size else it + 1 }
            val tail = if (head.size < 50) emptyList() else known.drop(tailStart).filterNot { it.spotifyTrackId in ids }
            return head + tail
        }
    }

    /** True once the Web Player is signed in and ready (waits up to 30 s at startup). */
    private suspend fun ready(): Boolean =
        withTimeoutOrNull(30_000) { api.ready.first { it } } != null

    private suspend fun readyToRead(): Boolean = api.ready.value || ready()

    private suspend fun diskHome(): HomeFeedModel? =
        withContext(Dispatchers.IO) { ScreenCache.home.read() }?.also { HomeCache.home = it }

    private fun notSignedIn() = context.getString(R.string.not_signed_in)

    // ── Mapping ──

    private fun cover(images: List<Image>) = images.best(300)?.url.orEmpty()
    private fun big(images: List<Image>) = images.best(640)?.url.orEmpty()

    /**
     * A page as it shows while explicit music is off: the known-explicit songs are out
     * of it, and how many went is kept on the page (its hidden note, [ContextPageModel.hiddenExplicit]).
     * The page as built — and anything kept on storage from it — keeps every song; only
     * what a screen is given is cut, so turning the setting back on brings them all back.
     */
    private fun ContextPageModel.explicitFiltered(): ContextPageModel {
        val allowed = com.music.spotui.data.preferences.ExplicitPref.allowed
        if (allowed) return this
        val kept = com.music.spotui.data.preferences.ExplicitFilter.visible(tracks, allowed)
        if (kept.size == tracks.size) return this
        return copy(tracks = kept, hiddenExplicit = tracks.size - kept.size)
    }

    fun songOf(t: Track): SongsModel {
        val id = t.uri.substringAfterLast(':')
        val singer = t.artists.joinToString(", ") { it.name }
        if (t.uri.startsWith("spotify:episode:")) return SongsModel(
            id = stableId("episode:$id"),
            title = t.name.take(128),
            album = t.album?.name.orEmpty(),
            singer = singer,
            coverUri = cover(t.album?.images.orEmpty()),
            url = "episode:$id",
            explicit = t.explicit,
            explicitStatus = t.explicitness,
            durationMs = t.durationMs.toInt(),
            addedAt = t.addedAt.orEmpty(),
            playable = t.playable,
            rowUid = t.uid.orEmpty(),
        )
        // A local file of the user's computer: the uri holds its tags, nothing else. Greyed out
        // until [LocalFileMatch] finds the same song on this device.
        if (LocalFile.isLocal(t.uri)) return SongsModel(
            id = stableId("local:${t.uri}"),
            title = t.name.take(128),
            album = t.album?.name.orEmpty(),
            singer = singer,
            coverUri = "",
            url = t.uri,
            durationMs = t.durationMs.toInt(),
            addedAt = t.addedAt.orEmpty(),
            playable = false,
            rowUid = t.uid.orEmpty(),
        )
        // A song of a catalog provider (a search's, an album's of that provider): it goes by its catalog key
        // (CatalogRuntime.keyOf), and plays from the audio-source extensions (SongPlayer.playSong).
        if (com.music.spotui.data.catalog.CatalogRuntime.isCatalogUri(t.uri)) return SongsModel(
            id = stableId("catalog:${t.uri}"),
            title = t.name.take(128),
            album = t.album?.name.orEmpty(),
            singer = singer,
            coverUri = cover(t.album?.images.orEmpty()),
            url = com.music.spotui.data.catalog.CatalogRuntime.PLAY_PREFIX + t.uri,
            spotifyTrackId = com.music.spotui.data.catalog.CatalogRuntime.keyOf(t.uri).orEmpty(),
            explicit = t.explicit,
            explicitStatus = t.explicitness,
            durationMs = t.durationMs.toInt(),
            albumId = t.album?.uri.orEmpty(),
            artistIds = t.artists.map { it.uri },
        )
        return SongsModel(
            id = stableId("track:$id"),
            title = t.name.take(128),
            album = t.album?.name.orEmpty(),
            singer = singer,
            coverUri = cover(t.album?.images.orEmpty()),
            url = SongPlayer.buildSpotifyPlayQuery(id, t.name, singer),
            spotifyTrackId = id,
            explicit = t.explicit,
            explicitStatus = t.explicitness,
            durationMs = t.durationMs.toInt(),
            albumId = t.album?.uri?.substringAfterLast(':').orEmpty(),
            addedAt = t.addedAt.orEmpty(),
            playable = t.playable,
            artistIds = t.artists.map { it.uri.substringAfterLast(':') },
            rowUid = t.uid.orEmpty(),
        )
    }

    /** [songs] with the playlist's local files played from this device's own copy, when there is one. */
    private suspend fun withDeviceFiles(songs: List<SongsModel>): List<SongsModel> {
        if (songs.none { it.url.startsWith(LocalFileMatch.SPOTIFY_PREFIX) }) return songs
        return LocalFileMatch.resolve(songs, LocalMusic.tracks(context))
    }

    private fun episodeSong(e: app.myspoti.api.Episode): SongsModel {
        val id = e.uri.substringAfterLast(':')
        val show = e.show?.name ?: "Podcast"
        return SongsModel(
            id = stableId("episode:$id"),
            title = e.name,
            album = show,
            singer = show,
            coverUri = cover(e.images).ifBlank { cover(e.show?.images.orEmpty()) },
            url = "episode:$id",
            durationMs = e.durationMs.toInt(),
            // No spotifyTrackId on purpose: an episode is keyed by its "episode:<id>" url
            // everywhere (download, playback). "playable" is what the downloader refuses on.
            playable = e.playable,
            description = e.description.orEmpty(),
            releaseDate = e.releaseDate.orEmpty(),
            played = e.played,
            playedPositionMs = e.playedPositionMs,
            showId = e.show?.uri?.substringAfterLast(':').orEmpty(),
        )
    }

    /** The id the UI keys an item by: Spotify's bare id, or the whole uri of an item of another provider. */
    private fun idOf(uri: String) = if (uri.startsWith("spotify:")) uri.substringAfterLast(':') else uri

    private fun albumOf(a: Album) = AlbumsModel(
        spotifyId = idOf(a.uri),
        id = stableId("album:${a.uri}"),
        artists = a.artists.joinToString(", ") { it.name },
        coverUri = cover(a.images),
        name = a.name,
        time = a.releaseDate?.take(10).orEmpty(),
        type = a.type,
    )

    private fun artistOf(a: Artist) = ArtistsModel(name = a.name, coverUri = cover(a.images), id = idOf(a.uri))

    private fun playlistEntry(p: Playlist) = LibraryEntry(
        spotifyId = p.uri.substringAfterLast(':'),
        name = p.name,
        subtitle = context.getString(R.string.lbl_playlist) + (p.owner?.let { " • $it" } ?: ""),
        coverUri = cover(p.images),
        isPlaylist = true,
    )

    private fun homeItem(c: MediaCard): HomeItem? {
        val id = c.uri.substringAfterLast(':')
        val image = cover(c.images)
        return when (c.kind) {
            MediaKind.ALBUM -> HomeItem.Album(id = id, name = c.title, imageUrl = image, subtitle = c.subtitle ?: context.getString(R.string.lbl_type_album), artists = c.subtitle.orEmpty())
            MediaKind.ARTIST -> HomeItem.Artist(name = c.title, imageUrl = image, id = id)
            MediaKind.PLAYLIST -> HomeItem.Playlist(name = c.title, imageUrl = image, subtitle = c.subtitle?.let { context.getString(R.string.lbl_playlist) + " • $it" } ?: context.getString(R.string.lbl_playlist), id = id)
            MediaKind.SHOW, MediaKind.AUDIOBOOK -> HomeItem.Show(id = id, name = c.title, imageUrl = image, subtitle = c.subtitle.orEmpty(), isEpisode = false)
            MediaKind.EPISODE, MediaKind.CHAPTER -> HomeItem.Show(id = id, name = c.title, imageUrl = image, subtitle = c.subtitle.orEmpty(), isEpisode = true)
            MediaKind.COLLECTION -> HomeItem.Playlist(name = c.title, imageUrl = image.ifBlank { LIKED_COVER }, subtitle = context.getString(R.string.lbl_playlist), id = LIKED_SONGS_ID)
            else -> null
        }
    }

    private fun likedEntry() = LibraryEntry(
        spotifyId = LIKED_SONGS_ID,
        name = context.getString(R.string.lbl_liked_songs),
        subtitle = context.getString(R.string.lbl_playlist),
        coverUri = LIKED_COVER,
        isPlaylist = true,
    )

    // ── Home ──

    /**
     * New releases for the "New releases" screen: albums from artists the user follows
     * (home shelves), then Spotify's new-release editorial playlists' albums.
     */
    suspend fun newReleases(): List<AlbumsModel> {
        val feed = api.home.feed(app.myspoti.api.HomeFilter.MUSIC, itemsPerShelf = 30)
        val albums = feed.shelves.flatMap { it.items }.filter { it.kind == MediaKind.ALBUM }.distinctBy { it.uri }
        return albums.map { c ->
            AlbumsModel(spotifyId = c.uri.substringAfterLast(':'), id = stableId("album:${c.uri}"), artists = c.subtitle.orEmpty(),
                coverUri = cover(c.images), name = c.title, time = "", type = "ALBUM")
        }
    }

    /** Artists the user follows (library "Artists" filter). */
    suspend fun getFollowedArtists(): List<ArtistsModel> {
        // Artists followed on this phone (LocalFollows): the platform's library
        // keeps nothing, so its list can never carry them; they are merged in
        // here, on top (they are the newest follows of this phone).
        val local = com.music.spotui.data.local.LocalFollows.items.map {
            ArtistsModel(it.name, it.image, idOf(it.uri), "")
        }
        if (!ready()) return local
        val out = ArrayList<ArtistsModel>()
        var offset = 0
        while (true) {
            val page = runCatching { api.library.items(filter = LibraryFilter.ARTISTS, offset = offset, limit = 50) }.getOrNull() ?: break
            page.items.filter { it.kind == MediaKind.ARTIST }.forEach { out += ArtistsModel(it.name, cover(it.images), it.uri.substringAfterLast(':'), it.addedAt.orEmpty()) }
            offset = page.nextOffset ?: break
        }
        if (local.isEmpty()) return out
        val localIds = local.mapTo(HashSet()) { it.id }
        return local + out.filter { it.id !in localIds }
    }

    /**
     * Every show the user follows (library "Podcasts" filter), as library rows. The list is read
     * page after page to its end: the Home podcasts shelf holds only the first 20. Empty when it
     * could not be read; [followedShows] tells that apart from no show followed.
     */
    suspend fun getFollowedShows(): List<LibraryEntry> = followedShows().getOrDefault(emptyList())

    /**
     * [getFollowedShows], failing when Spotify did not answer its first page (no session, an error):
     * the Podcasts chip said "nothing followed" for an error and logged nothing (#136). A later
     * page that fails ends the list with the shows already read.
     *
     * The "Podcasts" filter id was never captured (only Playlists, Artists and Albums were), and an
     * id Spotify does not know answers an empty list, which the chip showed as "nothing followed"
     * for accounts that follow shows (#136). So when the filter finds no show, the whole library
     * is read and its shows kept: the unfiltered list carries them whatever the filters are called.
     */
    suspend fun followedShows(): Result<List<LibraryEntry>> {
        if (!ready()) return Result.failure(IllegalStateException(notSignedIn()))
        val filtered = readShows(LibraryFilter.PODCASTS)
        val shows = if (filtered.getOrNull().isNullOrEmpty()) readShows(LibraryFilter.ALL).takeIf { it.isSuccess || filtered.isFailure } ?: filtered else filtered
        val out = shows.getOrElse { return Result.failure(it) }.map {
            LibraryEntry(SHOW_PREFIX + it.uri.substringAfterLast(':'), it.name,
                context.getString(R.string.lbl_podcast) + (it.subtitle?.let { s -> " • $s" } ?: ""), cover(it.images), isPlaylist = false)
        }
        if (out.isEmpty()) return Result.success(out)
        val news = LibraryEntry(NEW_EPISODES_ID, context.getString(R.string.episodes_new_title), context.getString(R.string.new_episodes_subtitle),
            out.first().coverUri, isPlaylist = true)
        return Result.success(listOf(news) + out)
    }

    /**
     * The shows of the library list read with [filter], page after page, and of the folders in it
     * (a show filed in a folder is only listed inside it), three levels down at most; failing on
     * a first page that fails.
     */
    private suspend fun readShows(filter: LibraryFilter, folderUri: String? = null, depth: Int = 0): Result<List<app.myspoti.api.LibraryItem>> {
        val out = ArrayList<app.myspoti.api.LibraryItem>()
        val folders = ArrayList<String>()
        var offset = 0
        while (true) {
            val page = runCatching { api.library.items(filter = filter, folderUri = folderUri, offset = offset, limit = 50) }.getOrElse {
                if (it is CancellationException) throw it
                Log.e("Api", "getFollowedShows ($filter${folderUri?.let { f -> " in $f" }.orEmpty()}) failed at $offset", it)
                if (offset == 0) return Result.failure(it)
                null
            } ?: break
            out += page.items.filter { it.kind == MediaKind.SHOW }
            folders += page.items.filter { it.kind == MediaKind.FOLDER }.map { it.uri }
            offset = page.nextOffset ?: break
        }
        if (depth < 3) folders.distinct().forEach { f -> readShows(filter, f, depth + 1).getOrNull()?.let { out += it } }
        return Result.success(out.distinctBy { it.uri })
    }

    /**
     * The episodes not played yet of the shows the user follows, newest first: the show page's own
     * episodes query, asked for the latest few of each show, four shows at a time. Kept for half
     * an hour, so opening the screen again asks nothing.
     */
    suspend fun getNewEpisodes(): List<SongsModel> {
        if (!ready()) return emptyList()
        newEpisodes?.let { (at, list) -> if (System.currentTimeMillis() - at < NEW_EPISODES_TTL_MS) return list }
        val shows = getFollowedShows().filter { it.spotifyId.startsWith(SHOW_PREFIX) }.map { it.spotifyId.removePrefix(SHOW_PREFIX) }
        val gate = kotlinx.coroutines.sync.Semaphore(PLAYLIST_PARALLEL)
        val found = coroutineScope {
            shows.map { id ->
                async(Dispatchers.IO) { gate.withPermit { runCatching { api.podcasts.episodes("spotify:show:$id", 0, NEW_PER_SHOW).items }.getOrDefault(emptyList()) } }
            }.map { it.await() }.flatten()
        }
        val list = found.map(::episodeSong).filter { !it.played && it.releaseDate.isNotBlank() }
            .sortedByDescending { it.releaseDate }.take(NEW_EPISODES_MAX)
        if (list.isNotEmpty()) newEpisodes = System.currentTimeMillis() to list
        return list
    }

    /** [facet]: "" (all), "music-chip", "podcasts-chip", "audiobooks-chip", or a sub-chip Spotify sent (HomeSubChips). */
    suspend fun getHomeFeed(facet: String = ""): Flow<Response<HomeFeedModel>> = flow {
        val cached = if (facet.isEmpty()) HomeCache.home ?: diskHome() else HomeCache.facets[facet]
        cached?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (cached == null) emit(Response.Error(notSignedIn())); return@flow }
        // Any facet Spotify offers: a top chip, or one opened beside it (Music > Following).
        runCatching { api.home.feed(facet, itemsPerShelf = 20) }.fold(
            onSuccess = { feed ->
                com.music.spotui.data.entity.HomeSubChips.learn(feed.chips)
                val sections = feed.shelves.mapNotNull { shelf ->
                    val items = shelf.items.distinctBy { it.uri }.mapNotNull(::homeItem)
                        .distinctBy { it::class.simpleName + "|" + it.name.lowercase() }
                    if (items.isEmpty()) null else HomeSection(title = shelf.title.orEmpty(), items = items)
                }.distinctBy { it.title.lowercase().ifBlank { it.hashCode().toString() } }
                // Scadify's own shelves under the account's (the main feed only): with no
                // account the account feed is empty, and Home is these plus the extensions'.
                val builtIns = if (facet.isEmpty()) builtInHomeSections(sections.mapTo(HashSet()) { it.title.lowercase() }) else emptyList()
                val model = HomeFeedModel(greeting = feed.greeting, sections = sections + builtIns)
                if (facet.isEmpty()) { HomeCache.home = model; ScreenCache.home.write(model) } else HomeCache.facets[facet] = model
                emit(Response.Success(model))
            },
            onFailure = { Log.e("Api", "getHomeFeed failed", it); if (cached == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** Deezer's public catalogue for the built-in chart shelf, asked directly (its own parsers, no key). */
    private val chartDeezer by lazy {
        app.myspoti.catalog.Deezer(app.myspoti.catalog.HttpFetch(MyspotiRuntime.apiHttp, com.music.spotui.data.catalog.CatalogRuntime.USER_AGENT, 0))
    }

    /**
     * The shelves that are Scadify's own, under the account feed's: this phone's listening
     * ("Continue listening", "Your frequent plays", from [ListenHistory], only what there is)
     * and the public chart ("Popular this week", Deezer's, played through the catalog like a
     * search's songs). A title the feed already has is not repeated.
     */
    private suspend fun builtInHomeSections(have: Set<String>): List<HomeSection> {
        val out = ArrayList<HomeSection>()
        fun add(title: String, items: List<HomeItem>) {
            if (items.isNotEmpty() && title.lowercase() !in have && out.none { it.title.equals(title, ignoreCase = true) }) out += HomeSection(title, items)
        }
        val listens = withContext(Dispatchers.IO) { ListenHistory.all() }
        add(context.getString(R.string.home_continue_listening),
            listens.sortedByDescending { it.atMs }.distinctBy { it.key }.take(12).mapNotNull(::historyTrack))
        add(context.getString(R.string.home_frequent_plays),
            withContext(Dispatchers.IO) { ListenHistory.recap(0) }.topTracks.mapNotNull { r ->
                listens.firstOrNull { it.title == r.name && it.artist == r.subtitle }?.let(::historyTrack)
            })
        add(context.getString(R.string.home_popular_week), runCatching {
            chartDeezer.chartTracks(20)
                // The chart states each song's explicitness (Deezer): while explicit
                // music is off, the known-explicit ones stay off the shelf.
                .filter { com.music.spotui.data.preferences.ExplicitPref.allowed || it.track.explicitness != app.myspoti.api.Explicitness.EXPLICIT }
                .map { hit ->
                val t = hit.track
                HomeItem.Track(
                    name = t.name,
                    imageUrl = cover(t.album?.images.orEmpty()),
                    subtitle = t.artists.joinToString(", ") { it.name },
                    playUrl = com.music.spotui.data.catalog.CatalogRuntime.PLAY_PREFIX + t.uri,
                    album = t.album?.name.orEmpty(),
                )
            }
        }.getOrDefault(emptyList()))
        return out
    }

    /** A listen of this phone as a home card: what plays it again, from the key it was heard under. */
    private fun historyTrack(l: ListenHistory.Listen): HomeItem.Track? = when {
        l.key.startsWith("spotify:track:") -> {
            val id = l.key.substringAfterLast(':')
            HomeItem.Track(id = id, name = l.title, imageUrl = l.cover, subtitle = l.artist,
                playUrl = SongPlayer.buildSpotifyPlayQuery(id, l.title, l.artist), album = l.album)
        }
        com.music.spotui.data.catalog.CatalogRuntime.isCatalogUri(l.key) ->
            HomeItem.Track(name = l.title, imageUrl = l.cover, subtitle = l.artist,
                playUrl = com.music.spotui.data.catalog.CatalogRuntime.PLAY_PREFIX + l.key, album = l.album)
        // A phone file or an episode: no card (the file's own page is Local Files).
        l.key.startsWith("local:") || l.key.startsWith("episode:") -> null
        else -> HomeItem.Track(name = l.title, imageUrl = l.cover, subtitle = l.artist,
            playUrl = SongPlayer.buildSpotifyPlayQuery("", l.title, l.artist), album = l.album)
    }

    // ── Search ──

    suspend fun searchTracks(query: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (query.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        runCatching { api.search.tracks(query, limit = 30).items.map(::songOf) }.fold(
            onSuccess = { emit(Response.Success(com.music.spotui.data.preferences.ExplicitFilter.visible(it, com.music.spotui.data.preferences.ExplicitPref.allowed))) },
            onFailure = { Log.e("Api", "searchTracks failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** Tracks, albums, artists and playlists in parallel, then podcasts and episodes. */
    suspend fun searchEverything(query: String): Flow<Response<SearchResults>> = flow {
        emit(Response.Loading())
        if (query.isBlank()) { emit(Response.Success(SearchResults())); return@flow }
        if (!readyToRead()) { emit(Response.Error(notSignedIn())); return@flow }
        val music = runCatching { api.search.all(query, limit = 20) }.getOrElse {
            Log.e("Api", "searchEverything failed", it); emit(Response.Error(it.message ?: "error")); return@flow
        }
        val base = SearchResults(
            songs = com.music.spotui.data.preferences.ExplicitFilter.visible(music.tracks.items.map(::songOf), com.music.spotui.data.preferences.ExplicitPref.allowed),
            albums = music.albums.items.map(::albumOf),
            artists = music.artists.items.map(::artistOf),
            playlists = music.playlists.items.map(::playlistEntry),
        )
        emit(Response.Success(base))
        val (shows, episodes) = coroutineScope {
            val s = async { runCatching { api.search.shows(query, limit = 12).items }.getOrDefault(emptyList()) }
            val e = async { runCatching { api.search.episodes(query, limit = 12).items }.getOrDefault(emptyList()) }
            s.await() to e.await()
        }
        if (shows.isNotEmpty() || episodes.isNotEmpty()) emit(Response.Success(base.copy(
            shows = shows.map { PodcastModel(it.uri.substringAfterLast(':'), it.title, it.subtitle.orEmpty(), cover(it.images)) },
            episodes = episodes.map(::episodeSong),
        )))
    }

    // ── Podcasts ──

    /** A podcast's episodes, newest first, as playable songs (url = "episode:<id>"). */
    suspend fun getShowEpisodes(showId: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val uri = "spotify:show:$showId"
        val episodes = runCatching {
            val out = ArrayList<SongsModel>()
            var offset = 0
            repeat(10) {
                val page = api.podcasts.episodes(uri, offset, 50)
                out += page.items.map(::episodeSong)
                emit(Response.Success(out.toList()))
                offset = page.nextOffset ?: return@runCatching out
            }
            out
        }
        if (episodes.exceptionOrNull() is CancellationException) throw episodes.exceptionOrNull()!!
        episodes.fold(
            onSuccess = { songs -> SongPlayer.registerContext(songs.map { it.url }, uri); emit(Response.Success(songs)) },
            onFailure = { Log.e("Api", "getShowEpisodes failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /**
     * The show page: header and rows in one flow, so the screen has a single loading state.
     *
     * A podcast answers [app.myspoti.api.Podcasts.episodes]; a book answers nothing there and
     * only has chapters, which is why its page used to show a cover and a title and nothing
     * else (B13).
     */
    suspend fun getShowPage(showId: String): Flow<Response<ShowPage>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val uri = "spotify:show:$showId"
        val head = runCatching { api.podcasts.show(uri) }.getOrNull()
        val rows = ArrayList<SongsModel>()
        var failure: Throwable? = null
        var audiobook = false
        var book: app.myspoti.api.Audiobook? = null

        try {
            var offset = 0
            loop@ for (page in 0 until MAX_EPISODE_PAGES) {
                val p = api.podcasts.episodes(uri, offset, 50)
                if (p.items.isEmpty()) break@loop
                rows += p.items.map(::episodeSong)
                emit(Response.Success(pageOf(showId, head, book, rows, audiobook = false)))
                offset = p.nextOffset ?: break@loop
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        }

        if (rows.isEmpty()) {
            // A book: its chapters come from queryBookChapters, not from the episodes query.
            audiobook = true
            book = runCatching { api.podcasts.audiobook(uri) }.getOrNull()
            try {
                var offset = 0
                loop@ for (page in 0 until MAX_EPISODE_PAGES) {
                    val p = api.podcasts.chapters(uri, offset, 50)
                    if (p.items.isEmpty()) break@loop
                    rows += p.items.map(::episodeSong)
                    emit(Response.Success(pageOf(showId, head, book, rows, audiobook = true)))
                    offset = p.nextOffset ?: break@loop
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (rows.isEmpty()) failure = e
            }
        }

        if (rows.isEmpty() && failure != null) {
            Log.e("Api", "getShowPage failed", failure)
            emit(Response.Error(failure.message ?: "error"))
            return@flow
        }
        SongPlayer.registerContext(rows.map { it.url }, uri)
        emit(Response.Success(pageOf(showId, head, book, rows, audiobook)))
    }

    private fun pageOf(
        showId: String,
        head: app.myspoti.api.Show?,
        book: app.myspoti.api.Audiobook?,
        rows: List<SongsModel>,
        audiobook: Boolean,
    ): ShowPage {
        val cover = head?.let { big(it.images) }.orEmpty()
            .ifBlank { book?.images?.let(::big).orEmpty() }
            .ifBlank { rows.firstOrNull()?.coverUri.orEmpty() }
        val model = PodcastModel(
            id = showId,
            name = head?.name ?: book?.name ?: rows.firstOrNull()?.album.orEmpty(),
            publisher = head?.publisher?.takeIf { it.isNotBlank() }
                ?: book?.authors?.joinToString(", ").orEmpty(),
            coverUri = cover,
            description = head?.description ?: book?.description?.let { androidx.core.text.HtmlCompat.fromHtml(it, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }.orEmpty(),
            rating = head?.rating ?: 0.0,
            saved = head?.saved == true,
            authors = book?.authors.orEmpty(),
            narrators = book?.narrators.orEmpty(),
            audiobook = audiobook,
        )
        return ShowPage(model, rows, audiobook, cover)
    }

    /** One episode (or chapter) by raw Spotify id, for the episode page. */
    suspend fun getEpisode(episodeId: String): SongsModel? {
        if (!ready()) return null
        return runCatching { episodeSong(api.podcasts.episode("spotify:episode:$episodeId")) }.getOrNull()
    }

    /** Episodes marked finished: kept by no service now. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun setEpisodePlayed(episodeUri: String, played: Boolean) {}

    /** Uris of the episodes marked finished: none. */
    suspend fun finishedEpisodes(): Set<String> = emptySet()

    /** "Your Episodes": saved podcast episodes. */
    suspend fun getSavedEpisodes(): List<SongsModel> {
        if (!ready()) return emptyList()
        return runCatching { api.podcasts.savedEpisodes(limit = 100).items.map(::episodeSong) }.getOrDefault(emptyList())
    }

    /** Release date of a show's newest episode (one episode asked for), "" when unknown. */
    suspend fun latestEpisodeDate(showId: String): String {
        if (!ready()) return ""
        return runCatching { api.podcasts.episodes("spotify:show:$showId", 0, 1).items.firstOrNull()?.releaseDate.orEmpty() }.getOrDefault("")
    }

    // ── Radio and categories ──

    /** The song radio after [seedTrackIds]' last track: Spotify's own radio playlist. */
    suspend fun getRecommendations(seedTrackIds: List<String>): List<SongsModel> {
        val seed = seedTrackIds.lastOrNull { it.isNotBlank() } ?: return emptyList()
        if (!ready()) return emptyList()
        val radio = runCatching { api.extras.radio("spotify:track:$seed") }.getOrNull() ?: return emptyList()
        return runCatching { api.playlists.tracks(radio, 0, 50).items.map(::songOf).filter { it.spotifyTrackId != seed } }.getOrDefault(emptyList())
    }

    /** The radio playlist id for a track or artist uri (for "Go to song radio"). */
    suspend fun radioPlaylistId(seedUri: String): String? =
        if (!ready()) null else runCatching { api.extras.radio(seedUri) }.getOrNull()?.substringAfterLast(':')

    /**
     * A browse category: Spotify's own category page when [genre] is a page/genre uri or id,
     * else playlists matching the name.
     */
    suspend fun getCategoryPlaylists(genre: String): Flow<Response<List<LibraryEntry>>> = flow {
        emit(Response.Loading())
        if (genre.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val pageUri = when {
            genre.startsWith("spotify:page:") || genre.startsWith("spotify:genre:") -> genre
            else -> runCatching { api.home.categories() }.getOrNull()?.firstOrNull { it.title.equals(genre, ignoreCase = true) }?.uri
        }
        val entries = runCatching {
            if (pageUri != null) {
                // Shows, episodes and books too: the Podcasts and Documentaries pages hold little else
                // and showed empty (reported). Each opens by the kind its uri says (categoryEntryRoute).
                api.home.category(pageUri).shelves.flatMap { it.items }.distinctBy { it.uri }.mapNotNull { c ->
                    if (c.kind !in CATEGORY_KINDS) return@mapNotNull null
                    LibraryEntry(c.uri.substringAfterLast(':'), c.title, c.subtitle.orEmpty(), cover(c.images), isPlaylist = c.kind == MediaKind.PLAYLIST, uri = c.uri)
                }
            } else api.search.playlists(genre, limit = 24).items.map(::playlistEntry)
        }
        entries.fold(
            onSuccess = { emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getCategoryPlaylists failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** What a browse category page lists: the cards a tap can open. */
    private val CATEGORY_KINDS = setOf(MediaKind.PLAYLIST, MediaKind.ALBUM, MediaKind.SHOW, MediaKind.EPISODE, MediaKind.AUDIOBOOK, MediaKind.CHAPTER)

    // ── Artists ──

    private suspend fun findArtist(name: String): Artist? {
        val hits = api.search.artists(name, limit = 5).items
        return hits.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: hits.firstOrNull()
    }

    /** The full artist page; [knownArtistId] avoids the fuzzy name lookup. */
    suspend fun getArtistOverview(artistName: String, knownArtistId: String = ""): Flow<Response<ArtistOverviewModel>> = flow {
        emit(Response.Loading())
        if (artistName.isBlank() && knownArtistId.isBlank()) { emit(Response.Success(ArtistOverviewModel(name = artistName))); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val uri = when {
            // An artist of a catalog provider: its own page.
            knownArtistId.contains(':') -> knownArtistId
            knownArtistId.isNotBlank() -> "spotify:artist:$knownArtistId"
            else -> runCatching { findArtist(artistName) }.getOrNull()?.uri
        }
        if (uri == null) { emit(Response.Error(context.getString(R.string.artist_not_found))); return@flow }
        // Shown again within a few minutes (back, the releases page, another tap): from memory,
        // without asking Spotify for the page and the whole discography once more.
        ArtistCache.map[uri]?.let { (at, cached) ->
            emit(Response.Success(cached))
            if (System.currentTimeMillis() - at < ArtistCache.FRESH_MS) return@flow
        }
        val firstReleases = kotlinx.coroutines.coroutineScope {
            // The discography's first page goes out with the page itself, not after it.
            val releases = async { runCatching { api.catalog.discography(uri, 0, 50) }.getOrNull() }
            val page = runCatching { api.catalog.artistPage(uri) }.getOrElse {
                releases.cancel()
                Log.e("Api", "getArtistOverview failed", it)
                return@coroutineScope null
            }
            page to releases
        } ?: run {
            if (ArtistCache.map[uri] == null) emit(Response.Error(context.getString(R.string.page_load_failed)))
            return@flow
        }
        val page = firstReleases.first
        val avatar = big(page.artist.images)
        val top = page.topTracks.map { t -> songOf(t.track).let { if (it.coverUri.isBlank()) it.copy(coverUri = avatar) else it } to t.playcount }
            // The artist's top tracks are a list like any other for the explicit filter.
            .filter { !com.music.spotui.data.preferences.ExplicitFilter.hides(it.first, com.music.spotui.data.preferences.ExplicitPref.allowed) }
        SongPlayer.registerContext(top.map { it.first.url }, uri)
        val model = ArtistOverviewModel(
            id = idOf(uri),
            name = page.artist.name.ifBlank { artistName },
            verified = page.artist.verified,
            monthlyListeners = page.artist.monthlyListeners,
            biography = page.biography,
            headerImage = big(page.headerImages).ifBlank { avatar },
            avatarImage = avatar,
            topTracks = top.map { (song, plays) -> ArtistTrackUi(song, plays) },
            // Newest first, by year at this point (a popular release may carry its year alone).
            popularReleases = app.myspoti.api.Releases.newestFirst(page.popularReleases, emptyList()).map(::albumOf),
            appearsOn = page.appearsOn.map(::albumOf),
            relatedArtists = page.related.map(::artistOf),
        )
        emit(Response.Success(model))
        ArtistCache.map[uri] = System.currentTimeMillis() to model
        // The overview carries only part of the discography: the whole list follows.
        // Every page of it (big artists have hundreds of singles), up to a sane cap.
        val all = runCatching {
            val out = ArrayList<app.myspoti.api.Album>()
            val first = firstReleases.second.await()
            var offset: Int? = if (first != null) { out += first.items; first.nextOffset.takeIf { first.items.isNotEmpty() } } else 0
            while (offset != null && out.size < DISCOGRAPHY_MAX) {
                val next = api.catalog.discography(uri, offset, 50)
                if (next.items.isEmpty()) break
                out += next.items
                offset = next.nextOffset
            }
            out
        }.getOrNull().orEmpty()
        // Shown again when the discography adds releases or puts them in their real order (its dates are full ones).
        val merged = app.myspoti.api.Releases.newestFirst(page.popularReleases, all).map(::albumOf)
        if (merged != model.popularReleases) {
            val full = model.copy(popularReleases = merged)
            ArtistCache.map[uri] = System.currentTimeMillis() to full
            emit(Response.Success(full))
        }
    }

    /**
     * The songs of playlists read in full lately, by playlist uri (a few, small ones: a long list
     * stays on the network). Dropped by an edit made from the app, see [invalidatePlaylist].
     */
    private object PlaylistFresh {
        const val FRESH_MS = 5 * 60_000L
        const val MAX_TRACKS = 1_000
        private const val KEEP = 6
        private val map = object : LinkedHashMap<String, Pair<Long, List<SongsModel>>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<SongsModel>>>) = size > KEEP
        }

        @Synchronized fun get(uri: String): List<SongsModel>? =
            map[uri]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.second

        @Synchronized fun put(uri: String, songs: List<SongsModel>) {
            if (songs.isEmpty() || songs.size > MAX_TRACKS) map.remove(uri) else map[uri] = System.currentTimeMillis() to songs
        }

        @Synchronized fun drop(uri: String?) { if (uri == null) map.clear() else map.remove(uri) }
    }

    /** Artist pages shown lately, by artist uri, with when they were loaded. */
    private object ArtistCache {
        const val FRESH_MS = 5 * 60_000L
        val map = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ArtistOverviewModel>>()
    }

    // ── Albums and playlists ──

    private fun dateLabel(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            val d = java.time.LocalDate.parse(iso.take(10))
            d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault()))
        }.getOrDefault(iso.take(4))
    }

    private fun durationLabel(totalMs: Long): String {
        val min = totalMs / 60000
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    /** Album id for a name (+ artist) when the caller only knows the name. */
    suspend fun resolveAlbumId(albumName: String, artist: String = ""): String? {
        if (albumName.isBlank() || !ready()) return null
        val hits = runCatching { api.search.albums(if (artist.isBlank()) albumName else "$albumName $artist", limit = 10).items }.getOrNull().orEmpty()
        return pickAlbum(hits, albumName, artist)?.uri?.let(::idOf)
    }

    /**
     * The user's liked songs by one artist, as their own page (Spotify's "Liked Songs" row on an
     * artist page). They play inside Liked Songs, in this list's order, so only they play.
     */
    suspend fun getLikedByArtistPage(artistId: String, artistName: String): Flow<Response<ContextPageModel>> = flow {
        emit(Response.Loading())
        val all = LikedCache.tracks ?: LikedCache.loadOnce().await()
            ?: run { emit(Response.Error(context.getString(R.string.page_load_failed))); return@flow }
        val songs = all.filter { s ->
            if (s.artistIds.isNotEmpty()) artistId in s.artistIds
            else artistName.isNotBlank() && s.singer.split(", ").any { it.equals(artistName, ignoreCase = true) }
        }
        emit(Response.Success(ContextPageModel(
            uri = "spotify:collection:tracks",
            type = "liked-artist",
            name = context.getString(R.string.artist_liked_songs),
            coverUri = "",
            ownerName = ProfileCache.name.orEmpty(),
            ownerAvatar = ProfileCache.imageUrl.orEmpty(),
            metaLine = context.resources.getQuantityString(R.plurals.artist_liked_songs_count, songs.size, songs.size, artistName),
            tracks = songs,
            complete = true,
        ).explicitFiltered()))
    }

    /**
     * The phone files liked on this phone (LocalLikes), as Liked Songs rows. A device file
     * has no account id, so the account's liked list can never carry it; the rows are added
     * to the page here instead, on top (they are the newest likes of this phone). Their play
     * key is the file's own (`local:<content uri>`), so the page plays them through the
     * local player like the rows of a local playlist. The account list itself (LikedCache,
     * the copy on storage) stays account-only: the merge of its newest page works by
     * account ids and must not see these.
     */
    private fun localLikedSongs(): List<SongsModel> =
        com.music.spotui.data.local.LocalLikes.items.map { item ->
            SongsModel(
                id = stableId("localfile:" + item.uri),
                title = item.title, album = item.album, singer = item.artist,
                coverUri = com.music.spotui.data.local.LocalCovers.effectiveArt(item), url = "local:" + item.uri,
                durationMs = item.durationMs.toInt(),
            )
        }

    /**
     * The catalog songs liked on this phone (CatalogLikes), as Liked Songs rows. The
     * platform's library keeps nothing, so the account's list can never carry them; the
     * rows are added to the page here instead. Their play key is the song's own url (a
     * catalog play address, or a Spotify play query rebuilt from the snapshot), so the
     * page plays them through the normal catalog pipeline, as anywhere else.
     */
    private fun catalogLikedSongs(): List<SongsModel> =
        com.music.spotui.data.local.CatalogLikes.items.map { like ->
            SongsModel(
                id = stableId("track:" + like.trackId),
                title = like.title, album = like.album, singer = like.artist,
                coverUri = like.coverUri,
                url = like.url.ifBlank { SongPlayer.buildSpotifyPlayQuery(like.trackId, like.title, like.artist) },
                spotifyTrackId = like.trackId,
                durationMs = like.durationMs.toInt(),
            )
        }

    /** [tracks] (the account's liked songs) with this phone's likes on top: catalog songs first, then files. */
    private fun withLocalLiked(tracks: List<SongsModel>): List<SongsModel> {
        val catalog = catalogLikedSongs()
        val locals = localLikedSongs()
        if (catalog.isEmpty() && locals.isEmpty()) return tracks
        val ids = tracks.mapTo(HashSet()) { it.spotifyTrackId }
        val urls = tracks.mapTo(HashSet()) { it.url }
        // A song the account list already carries is not listed twice (by its track id,
        // or by the address that plays it).
        val extra = catalog.filter { it.spotifyTrackId !in ids && it.url !in urls } +
            locals.filter { it.url !in urls }
        return extra + tracks
    }

    /**
     * Liked Songs as a page: cached list shown instantly, then refreshed. A list read in full less
     * than [LIKED_FULL_MS] ago only has its newest page read again (the hearts of this account
     * and a like or unlike anywhere else land there); after that, with no list on storage, or
     * with [full] (a pull-to-refresh), every page is read.
     */
    suspend fun getLikedPage(full: Boolean = false): Flow<Response<ContextPageModel>> = flow {
        fun page(tracks: List<SongsModel>, complete: Boolean): ContextPageModel {
            // The cache keeps every song; the page as shown is cut by the explicit
            // filter, its count the visible one and the difference on the page.
            val all = withLocalLiked(tracks)
            val allowed = com.music.spotui.data.preferences.ExplicitPref.allowed
            val shown = com.music.spotui.data.preferences.ExplicitFilter.visible(all, allowed)
            return ContextPageModel(
                uri = "spotify:collection:tracks",
                type = "liked",
                name = context.getString(R.string.lbl_liked_songs),
                coverUri = "",
                ownerName = ProfileCache.name.orEmpty(),
                ownerAvatar = ProfileCache.imageUrl.orEmpty(),
                metaLine = context.resources.getQuantityString(R.plurals.lbl_n_songs, shown.size, shown.size),
                hiddenExplicit = all.size - shown.size,
                tracks = shown,
                complete = complete,
            )
        }
        if (LikedCache.tracks == null) LikedCache.tracks = withContext(Dispatchers.IO) { ScreenCache.liked.read() }
        LikedCache.tracks?.let { emit(Response.Success(page(it, true))) } ?: emit(Response.Loading())
        if (!ready()) {
            // No account answer: this phone's likes are still a Liked Songs page.
            if (LikedCache.tracks == null) {
                if (localLikedSongs().isNotEmpty() || catalogLikedSongs().isNotEmpty()) emit(Response.Success(page(emptyList(), true)))
                else emit(Response.Error(notSignedIn()))
            }
            return@flow
        }
        val known = LikedCache.tracks
        if (!full && known != null) {
            val at = withContext(Dispatchers.IO) { ScreenCache.likedFullAt() }
            if (at != null && System.currentTimeMillis() - at < LIKED_FULL_MS) {
                val head = runCatching { likedHead() }.getOrElse { if (it is CancellationException) throw it; null }
                // The list shown stays when Spotify cannot be reached.
                if (head == null) return@flow
                // A head that does not reach the list known (more than a page of new likes) cannot be merged.
                if (LikedCache.reaches(head, known)) {
                    val merged = LikedCache.merge(head, known)
                    LikedCache.tracks = merged
                    ScreenCache.liked.write(merged)
                    SongPlayer.registerLikedSongs(merged.map { it.url })
                    emit(Response.Success(page(merged, true)))
                    return@flow
                }
            }
        }
        val first = LikedCache.tracks == null
        // Cancellation must escape: emit() throws AbortFlowException (a CancellationException)
        // when the collector stops early (first { … }); catching it and emitting again
        // violates flow exception transparency and breaks Android Auto's firstResult.
        val tracks = runCatching { allLiked { if (first) emit(Response.Success(page(it, false))) } }.getOrElse {
            if (it is CancellationException) throw it
            Log.e("Api", "getLikedPage failed", it)
            if (LikedCache.tracks == null) emit(Response.Error(context.getString(R.string.couldnt_load, context.getString(R.string.lbl_liked_songs))))
            return@flow
        }
        LikedCache.tracks = tracks
        ScreenCache.liked.write(tracks)
        ScreenCache.markLikedFull()
        SongPlayer.registerLikedSongs(tracks.map { it.url })
        emit(Response.Success(page(tracks, true)))
    }

    /**
     * An imported song as a row: built exactly like [songOf] builds a Spotify track, with
     * the row's own key kept. The model goes by the row's play id ([ImportedSong.playId]):
     * its Spotify id, or — for an Amazon or file import — the catalog key its completion
     * attached, exactly the key [songOf] puts on a catalog song's model. That key is what
     * makes the row play: the tap path asks the audio-source extensions by the model's
     * spotifyTrackId alone (ExtensionAudio.playList refuses a blank one before any
     * extension is asked, and the refusal surfaces as "No source plays"), so a row
     * modelled with a blank id died on tap however clean its title read — the v27
     * completion filled ISRCs and covers but attached no identity, and its rows still
     * could not play. A song with neither id keys its model id on its own row key
     * instead — every such row would otherwise share the one id of "track:" — and its
     * play query is the plain title-and-artist text [SongPlayer.buildSpotifyPlayQuery]
     * makes of a blank id, until a completion pass gives it a key.
     */
    private fun importedSongOf(s: ImportedSong): SongsModel = SongsModel(
        id = stableId("track:" + s.playId.ifBlank { "row:${s.key}" }),
        title = s.title.take(128),
        album = s.album,
        singer = s.artist,
        coverUri = s.coverUrl,
        url = SongPlayer.buildSpotifyPlayQuery(s.playId, s.title, s.artist),
        spotifyTrackId = s.playId,
        durationMs = s.durationMs.toInt(),
        rowUid = s.key,
        isrc = s.isrc,
        explicit = s.explicit == app.myspoti.api.Explicitness.EXPLICIT,
        explicitStatus = s.explicit,
    )

    /**
     * The row of the imported playlist [playlistId] that plays under [identity] (a MixEdits
     * track identity), as the very track the player gets for it — the Mix editor resolves its
     * pair through this, so the identity it edits under is the identity playback plans under.
     */
    fun importedPlayableTrack(playlistId: String, identity: String): com.music.spotui.di.LocalTrack? {
        val p = ImportedPlaylists.get(playlistId) ?: return null
        return p.items.asSequence().map(::importedSongOf)
            .map { com.music.spotui.extensions.ExtensionAudio.localTrack(it) }
            .firstOrNull { com.music.spotui.data.local.MixEdits.trackIdentity(it.uri) == identity }
    }

    /**
     * An imported playlist as a page: read from this phone alone (its store), whole at once.
     * Its type is "imported": the context screens let its rows be removed and the list be
     * renamed, which the account's editable check would never allow with no account.
     */
    fun getImportedPlaylistPage(id: String): Flow<Response<ContextPageModel>> = flow {
        val p = ImportedPlaylists.get(id)
        if (p == null) { emit(Response.Error(context.getString(R.string.imported_playlist_gone))); return@flow }
        maybeRepairImported(p)
        maybeCompleteImported(p)
        emit(Response.Success(ContextPageModel(
            uri = "scadify:imported:$id",
            type = "imported",
            name = p.name,
            coverUri = if (p.cover.isNotBlank()) java.io.File(p.cover).toURI().toString()
                else p.items.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
            ownerName = context.getString(importedOriginLabelRes(importedOrigin(p))),
            metaLine = context.resources.getQuantityString(R.plurals.lbl_n_songs, p.items.size, p.items.size),
            tracks = p.items.map(::importedSongOf),
            complete = true,
        ).explicitFiltered()))
    }

    /**
     * A playlist imported before imports kept the full reading has thin rows stored: the
     * embed page's songs carry no ISRC, no cover and no album name. Without an ISRC its
     * songs then match sources the slow way — a live lookup that cannot answer for a bare
     * Spotify id in the accountless chain, so they can fail to play or download at all —
     * and without covers and album names the list reads as broken. The store kept the
     * playlist's source link, so when such a playlist is opened it is re-read once, in the
     * background, the same merged reading an import makes, and every field the stored
     * songs lack is filled from it (ImportedPlaylists.repair). A song the repair completes
     * also sheds a failed download from before the repair: that failure belonged to the
     * broken row's era, not to the sources, so its badge clears for a fresh attempt. Once per playlist
     * per run, off the page's own path; a failed re-read leaves everything exactly as it
     * is, and a later run may try again.
     */
    private fun maybeRepairImported(p: ImportedPlaylist) {
        if (p.items.none { it.spotifyTrackId.isNotBlank() && (it.isrc.isBlank() || it.coverUrl.isBlank() || it.album.isBlank() || it.explicit == app.myspoti.api.Explicitness.UNKNOWN) }) return
        val link = PlaylistLinks.parse(p.sourceUrl) as? PlaylistLink.Spotify ?: return
        if (!importRepairAttempted.add(p.id)) return
        importRepairScope.launch {
            runCatching {
                val fresh = PlaylistImport.fetchSpotify(link.id)
                // Spotify's API read is refused intermittently (a 429 quota answer was
                // seen from a phone whose repair then had nothing to fill: the merged
                // read came back embed-only). Whatever the fresh reading still lacks is
                // completed from the app's own catalog first (CatalogCompletion), so the
                // repair below consumes the fullest reading either source could give.
                val completed = CatalogCompletion.completeFromCatalog(fresh.songs)
                val byId = completed.filter { it.spotifyTrackId.isNotBlank() }.associateBy { it.spotifyTrackId }
                if (byId.isNotEmpty()) {
                    val repaired = ImportedPlaylists.repair(p.id, byId)
                    repaired.forEach { com.music.spotui.data.offline.OfflineDownloader.forgetFailed(it) }
                }
            }.onFailure { if (it !is CancellationException) Log.w("Api", "Imported playlist repair for ${p.name}: $it") }
        }
    }

    /**
     * The completion counterpart of [maybeRepairImported] for the playlists that
     * repair cannot reach: an Amazon or file import's rows carry no Spotify track
     * id, so no re-read of a source link can fill them — but the catalog completion
     * can, matched by row key ([ImportedPlaylists.complete]), and the catalog key
     * it attaches is the identity those rows play under. A playlist stored before
     * completion attached keys (or whose rows a pass left thin) is completed once
     * per run when it is opened, in the background, off the page's own path; a
     * failed pass leaves everything exactly as it is.
     */
    private fun maybeCompleteImported(p: ImportedPlaylist) {
        if (p.items.none { CatalogCompletion.thin(it) }) return
        if (!importRepairAttempted.add(p.id)) return
        importRepairScope.launch {
            runCatching {
                val completed = CatalogCompletion.completeFromCatalog(p.items)
                val byKey = completed.associateBy { it.key }
                if (byKey.isNotEmpty()) ImportedPlaylists.complete(p.id, byKey)
            }.onFailure { if (it !is CancellationException) Log.w("Api", "Imported playlist completion for ${p.name}: $it") }
        }
    }

    /**
     * After a like / unlike: the newest Liked Songs (one page) put on top of what is
     * known, so the list is right at once without loading every page again.
     */
    suspend fun mergeLikedHead(): List<SongsModel>? = runCatching {
        if (!ready()) return null
        val head = likedHead()
        val merged = LikedCache.merge(head, LikedCache.tracks.orEmpty())
        LikedCache.tracks = merged
        withContext(Dispatchers.IO) { ScreenCache.liked.write(merged) }
        SongPlayer.registerLikedSongs(merged.map { it.url })
        withLocalLiked(merged)
    }.getOrNull()

    private val LIKED_PAGE = 50

    /** The newest Liked Songs: one page of 50. */
    private suspend fun likedHead(): List<SongsModel> =
        api.library.likedTracks(0, 50).items.map { songOf(it.track).copy(addedAt = it.addedAt.orEmpty()) }

    private suspend fun allLiked(onPage: suspend (List<SongsModel>) -> Unit): List<SongsModel> {
        fun songs(p: app.myspoti.api.Page<app.myspoti.api.SavedTrack>) = p.items.map { songOf(it.track).copy(addedAt = it.addedAt.orEmpty()) }
        val first = api.library.likedTracks(0, LIKED_PAGE)
        return PagedFetch.inOrder(songs(first), first.total, LIKED_PAGE, fetch = { o -> songs(api.library.likedTracks(o, LIKED_PAGE)) }, onBatch = onPage)
    }

    /**
     * An album as a page: the copy on storage (opened before) at once, then the album and its
     * songs from Spotify, and the artist's avatar a moment after in a second emit. Without
     * [stored] the copy on storage is left out (a sync that must see the album as it is now).
     */
    /** The release date in full, then the label when no copyright line names it, then the copyright lines. */
    private fun albumFooter(album: Album): List<String> {
        val date = album.releaseDate?.takeIf { it.length >= 10 }?.let { iso ->
            runCatching {
                java.time.LocalDate.parse(iso.take(10)).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG))
            }.getOrNull()
        }
        val label = album.label?.trim()?.takeIf { l -> l.isNotEmpty() && album.copyrights.none { it.contains(l, ignoreCase = true) } }
        return listOfNotNull(date, label?.let { context.getString(R.string.album_label, it) }) + album.copyrights
    }

    suspend fun getAlbumPage(asked: String, stored: Boolean = true): Flow<Response<ContextPageModel>> = flow {
        emit(Response.Loading())
        // An album of a catalog provider (a whole uri): its own page, read from that provider and not kept on storage.
        val other = asked.contains(':')
        if (other && !ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val albumId = asked
        val onSpotify = !albumId.contains(':')
        val uri = if (onSpotify) "spotify:album:$albumId" else albumId
        val saved = if (stored && onSpotify) withContext(Dispatchers.IO) { ScreenCache.albumPage(albumId).read() } else null
        if (saved != null) {
            SongPlayer.registerContext(saved.tracks.map { it.url }, uri)
            emit(Response.Success(saved.explicitFiltered()))
        }
        if (!ready()) { if (saved == null) emit(Response.Error(notSignedIn())); return@flow }
        val (album, tracks) = runCatching {
            coroutineScope {
                val a = async { api.catalog.album(uri) }
                val t = async {
                    val out = ArrayList<Track>()
                    var offset = 0
                    while (true) {
                        val p = api.catalog.albumTracks(uri, offset, 50)
                        out += p.items
                        offset = p.nextOffset ?: break
                    }
                    out
                }
                a.await() to t.await()
            }
        }.getOrElse { if (saved == null) emit(Response.Error(context.getString(R.string.album_not_found))); return@flow }
        val songs = tracks.map { songOf(it).copy(album = album.name, coverUri = cover(album.images), albumId = albumId) }
        SongPlayer.registerContext(songs.map { it.url }, uri)
        val type = context.getString(when (album.type) {
            "SINGLE" -> R.string.lbl_type_single; "EP" -> R.string.lbl_type_ep
            "COMPILATION" -> R.string.lbl_type_compilation; else -> R.string.lbl_type_album
        })
        val firstArtist = album.artists.firstOrNull()
        val page = ContextPageModel(
            uri = uri,
            type = "album",
            name = album.name,
            coverUri = big(album.images),
            ownerName = album.artists.joinToString(", ") { it.name },
            // The avatar of the copy on storage (same artist) until the new one is known.
            ownerAvatar = saved?.takeIf { it.ownerUri == firstArtist?.uri.orEmpty() }?.ownerAvatar.orEmpty(),
            ownerUri = firstArtist?.uri.orEmpty(),
            metaLine = listOf(type, dateLabel(album.releaseDate)).filter { it.isNotBlank() }.joinToString(" • "),
            explicit = songs.any { it.explicit },
            tracks = songs,
            footer = albumFooter(album),
        )
        // Written before the first emit: a collector that only wants the first page stops the flow there.
        if (onSpotify) {
            ScreenCache.albumPage(albumId).write(page)
            ScreenCache.pruneAlbumPages(ALBUM_PAGES_KEPT)
        }
        emit(Response.Success(page.explicitFiltered()))
        val avatar = firstArtist?.let { runCatching { api.catalog.artist(it.uri) }.getOrNull() }?.images?.let(::cover).orEmpty()
        if (avatar.isNotBlank() && avatar != page.ownerAvatar) {
            val withAvatar = page.copy(ownerAvatar = avatar)
            if (onSpotify) ScreenCache.albumPage(albumId).write(withAvatar)
            emit(Response.Success(withAvatar.explicitFiltered()))
        }
    }

    /**
     * A playlist as a page. Its details are always asked for; its songs are kept in memory for
     * [PlaylistFresh.FRESH_MS] after a full read and reused when the playlist still has as many
     * songs, so reopening it does not read every page again. An edit made here drops them
     * ([invalidatePlaylist]); [force] (a reload) reads everything.
     */
    suspend fun getPlaylistPage(playlistId: String, force: Boolean = false): Flow<Response<ContextPageModel>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val uri = "spotify:playlist:$playlistId"
        val fresh = if (force) null else PlaylistFresh.get(uri)
        // The first songs are asked for together with the playlist's details, not after them.
        val flowScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
        fun startFirstPage() = flowScope.async(Dispatchers.IO) {
            runCatching { api.playlists.tracks(uri, 0, 100) }
        }
        var firstPage = if (fresh == null) startFirstPage() else null
        val pl = runCatching { api.playlists.playlist(uri) }.getOrElse {
            firstPage?.cancel()
            emit(Response.Error(context.getString(R.string.playlist_not_found))); return@flow
        }
        if (fresh != null && fresh.size != pl.trackCount) firstPage = startFirstPage()
        val saves = pl.followers?.takeIf { it > 0 }?.let {
            context.resources.getQuantityString(R.plurals.lbl_n_saves, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it)
        }.orEmpty()
        val header = ContextPageModel(
            uri = uri,
            type = "playlist",
            name = pl.name,
            coverUri = big(pl.images),
            description = stripHtml(pl.description),
            ownerName = pl.owner.orEmpty(),
            ownerAvatar = cover(pl.ownerImages),
            ownerUri = pl.ownerUri.orEmpty(),
            metaLine = saves,
            isBlend = pl.isBlend,
            canLeave = pl.canLeave,
        )
        // The first page gives the total: the rest is asked for PLAYLIST_PARALLEL pages at a time
        // and shown in order as it lands, at most every PLAYLIST_EMIT_MS. One page after the
        // other, each followed by the whole list shown and registered again, a 6000-song
        // playlist took about two minutes (tester report).
        val songs = ArrayList<SongsModel>()
        var failed = false
        fun publish(done: Boolean): Response<ContextPageModel> {
            SongPlayer.registerContext(songs.map { it.url }, uri)
            // Kept before the emit: a collector that stops at the complete page ends the flow there.
            if (done && !failed) PlaylistFresh.put(uri, songs.toList())
            val total = songs.sumOf { it.durationMs.toLong() }
            // The song count rides along with the duration ("1 h 2 min / 12 songs") so the two
            // numbers that grow together as pages arrive stay next to each other.
            val durationAndCount = if (songs.isNotEmpty()) {
                val count = context.resources.getQuantityString(R.plurals.lbl_n_songs, songs.size, songs.size)
                if (total > 0) "${durationLabel(total)} / $count" else count
            } else ""
            val meta = listOf(saves, durationAndCount).filter { it.isNotBlank() }.joinToString(" • ")
            // A playlist without a cover of its own shows the mosaic of its songs' albums, as in the official app.
            val cover = header.coverUri.ifBlank { com.music.spotui.ui.components.mosaicCoverFor(songs.take(200).map { it.coverUri }) }
            // The page as shown is cut by the explicit filter; [songs] itself (and the
            // fresh copy kept from it above) keeps every song.
            return Response.Success(header.copy(coverUri = cover, metaLine = meta, tracks = songs.toList(), complete = done).explicitFiltered())
        }
        if (firstPage == null) {
            songs += fresh!!
            emit(publish(done = true))
            return@flow
        }
        val first = firstPage.await().getOrElse { failed = true; null }
        if (first != null) {
            songs += withDeviceFiles(first.items.map(::songOf))
            val offsets = generateSequence(first.nextOffset) { (it + PLAYLIST_PAGE).takeIf { n -> n < first.total } }.toList()
            emit(publish(done = offsets.isEmpty()))
            if (offsets.isNotEmpty()) {
                val gate = kotlinx.coroutines.sync.Semaphore(PLAYLIST_PARALLEL)
                coroutineScope {
                    val jobs = offsets.map { o -> async(Dispatchers.IO) { gate.withPermit { runCatching { api.playlists.tracks(uri, o, PLAYLIST_PAGE) }.getOrNull() } } }
                    var lastEmit = System.currentTimeMillis()
                    for ((i, job) in jobs.withIndex()) {
                        val page = job.await()
                        if (page == null) { failed = true; jobs.drop(i + 1).forEach { it.cancel() }; break }
                        songs += withDeviceFiles(page.items.map(::songOf))
                        val last = i == jobs.lastIndex
                        if (last || System.currentTimeMillis() - lastEmit >= PLAYLIST_EMIT_MS) {
                            emit(publish(done = last)); lastEmit = System.currentTimeMillis()
                        }
                    }
                }
                // A page that failed: what came before it stays, marked complete so nothing waits.
                if (failed && songs.isNotEmpty()) emit(publish(done = true))
            }
        }
        if (failed && songs.isEmpty()) emit(Response.Error(context.getString(R.string.couldnt_load, pl.name)))
    }

    // ── Library ──

    private fun entryOf(item: app.myspoti.api.LibraryItem): LibraryEntry? = when (item.kind) {
        MediaKind.FOLDER -> LibraryEntry(
            spotifyId = item.uri, name = item.name,
            subtitle = context.getString(R.string.lbl_folder) + " • " +
                context.resources.getQuantityString(R.plurals.lbl_n_playlists, item.count ?: 0, (item.count ?: 0).toString()),
            coverUri = "", isPlaylist = false, folderUri = item.uri, childCount = item.count ?: 0,
            uri = item.uri, pinned = item.pinned, addedAt = item.addedAt.orEmpty(),
        )
        MediaKind.PLAYLIST -> LibraryEntry(item.uri.substringAfterLast(':'), item.name,
            context.getString(R.string.lbl_playlist) + (item.subtitle?.let { " • $it" } ?: ""), cover(item.images), isPlaylist = true,
            uri = item.uri, pinned = item.pinned, addedAt = item.addedAt.orEmpty(), ownerUri = item.ownerUri.orEmpty())
        MediaKind.ALBUM -> LibraryEntry(item.uri.substringAfterLast(':'), item.name,
            context.getString(R.string.lbl_type_album) + (item.subtitle?.let { " • $it" } ?: ""), cover(item.images), isPlaylist = false, artists = item.subtitle.orEmpty(),
            uri = item.uri, pinned = item.pinned, addedAt = item.addedAt.orEmpty())
        MediaKind.COLLECTION -> if (item.uri == "spotify:collection:tracks") null
            else LibraryEntry(EPISODES_ID, item.name, "Podcast", cover(item.images), isPlaylist = true)
        else -> null
    }

    private suspend fun libraryLevel(folderUri: String?, filter: LibraryFilter = LibraryFilter.ALL): List<app.myspoti.api.LibraryItem> {
        val first = api.library.items(filter = filter, folderUri = folderUri, offset = 0, limit = 50)
        // 40 pages at most, as before (2,000 items); the rest four at a time instead of one by one.
        return PagedFetch.inOrder(first.items, minOf(first.total, 40 * 50), 50,
            fetch = { o -> api.library.items(filter = filter, folderUri = folderUri, offset = o, limit = 50).items })
    }

    /** Flat library (Android Auto, pickers): Liked Songs, playlists, then albums. */
    suspend fun getLibrary(): Flow<Response<List<LibraryEntry>>> = flow {
        HomeCache.library?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (HomeCache.library == null) emit(Response.Error(notSignedIn())); return@flow }
        runCatching {
            val playlists = api.playlists.mine().map(::playlistEntry)
            val albums = libraryLevel(null, LibraryFilter.ALBUMS).mapNotNull(::entryOf).filter { !it.isPlaylist }
            listOf(likedEntry()) + playlists + albums
        }.fold(
            onSuccess = { HomeCache.library = it; emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getLibrary failed", it); if (HomeCache.library == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** "Your Library" as Spotify shows it: Liked Songs, then folders, playlists and albums in the user's order. */
    suspend fun getLibraryRoot(): Flow<Response<List<LibraryEntry>>> = flow {
        if (HomeCache.libraryRoot == null) HomeCache.libraryRoot = withContext(Dispatchers.IO) { ScreenCache.libraryRoot.read() }
        HomeCache.libraryRoot?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (HomeCache.libraryRoot == null) emit(Response.Error(notSignedIn())); return@flow }
        runCatching { listOf(likedEntry()) + libraryLevel(null).mapNotNull(::entryOf) }.fold(
            onSuccess = { HomeCache.libraryRoot = it; ScreenCache.libraryRoot.write(it); PagePrefetch.schedule(it); emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getLibraryRoot failed", it); if (HomeCache.libraryRoot == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** The folders and playlists inside one library folder. */
    suspend fun getLibraryFolder(folderUri: String): Flow<Response<List<LibraryEntry>>> = flow {
        HomeCache.folders[folderUri]?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (HomeCache.folders[folderUri] == null) emit(Response.Error(notSignedIn())); return@flow }
        runCatching { libraryLevel(folderUri).mapNotNull(::entryOf) }.fold(
            onSuccess = { HomeCache.folders[folderUri] = it; emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getLibraryFolder failed", it); if (HomeCache.folders[folderUri] == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** The looping Canvas video for a track, cached per track (also when there is none). */
    suspend fun getCanvasUrl(trackId: String): String? {
        if (trackId.isBlank()) return null
        CanvasCache.map[trackId]?.let { return it.value }
        if (!ready()) return null
        val url = runCatching { api.extras.canvas("spotify:track:$trackId")?.videoUrl }.getOrNull()
        CanvasCache.map[trackId] = CanvasCache.Entry(url)
        return url
    }

    /** The Canvas already looked up for [trackId]: null when never asked, else its url (or none). */
    fun knownCanvas(trackId: String): Result<String?>? = CanvasCache.map[trackId]?.let { Result.success(it.value) }

    private object CanvasCache {
        class Entry(val value: String?)
        val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    }

    /** The signed-in account (name, avatar, plan). Spotify does not give the e-mail to the Web Player. */
    suspend fun getAccount(): Flow<Response<com.music.spotui.data.entity.AccountModel>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        runCatching { api.users.me() }.fold(
            onSuccess = { u ->
                emit(Response.Success(com.music.spotui.data.entity.AccountModel(
                    name = u.name, email = "", imageUrl = cover(u.images), plan = if (u.premium) "Premium" else "Free",
                )))
            },
            onFailure = { Log.e("Api", "getAccount failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** Playlist descriptions come as HTML: keep the text and link labels. */
    private fun stripHtml(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return androidx.core.text.HtmlCompat.fromHtml(raw, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    /** Among same-named albums, prefer the exact name by the requested artist. */
    private fun pickAlbum(candidates: List<Album>, albumName: String, artist: String): Album? {
        if (candidates.isEmpty()) return null
        if (artist.isBlank()) return candidates.firstOrNull { it.name.equals(albumName, ignoreCase = true) } ?: candidates.first()
        val want = artist.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        fun artistMatches(a: Album) = a.artists.joinToString(" ") { it.name }.lowercase().let { names -> want.any { names.contains(it) } }
        val nameMatches = candidates.filter { it.name.equals(albumName, ignoreCase = true) }
        return nameMatches.firstOrNull(::artistMatches) ?: candidates.firstOrNull(::artistMatches) ?: nameMatches.firstOrNull() ?: candidates.first()
    }
}

/** Songs per playlist page, pages asked for at once, and the most often a growing list is shown again. */
private const val PLAYLIST_PAGE = 100
private const val PLAYLIST_PARALLEL = 4
private const val PLAYLIST_EMIT_MS = 350L
/** A Liked Songs list read in full is trusted this long: only its newest page is read again. */
private const val LIKED_FULL_MS = 24 * 3_600_000L
/** Album pages kept on storage. */
private const val ALBUM_PAGES_KEPT = 30
