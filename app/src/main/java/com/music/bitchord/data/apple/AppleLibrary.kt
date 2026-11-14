package com.music.bitchord.data.apple

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonAuthenticationException
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import com.music.bitchord.data.sources.addon.AddonUnsupportedException
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** The account-library collections exposed by the Apple addon contract. */
enum class AppleLibraryCollection(val routeName: String, val label: String) {
    PLAYLISTS("playlists", "Playlists"),
    ALBUMS("albums", "Albums"),
    SONGS("songs", "Songs"),
}

/** The state of one Apple library page. These outcomes are deliberately distinct. */
sealed interface AppleLibraryState {
    data object Unconfigured : AppleLibraryState
    data object Loading : AppleLibraryState
    data object SignedOut : AppleLibraryState
    data object Empty : AppleLibraryState
    data class Failure(val message: String, val retryable: Boolean = true) : AppleLibraryState
    data class Ready(
        val items: List<AddonLibraryItem>,
        val hasMore: Boolean = false,
        val nextOffset: Int? = null,
    ) : AppleLibraryState
}

/**
 * The addon-backed Apple Music library.
 *
 * No Apple credentials are read here. The addon owns the Apple account; the
 * app sends only the addon-scoped credential named by its manifest.
 */
class AppleLibraryRepository(private val config: SourceConfig) {

    suspend fun load(
        collection: AppleLibraryCollection,
        offset: Int = 0,
    ): AppleLibraryState {
        val client = client().getOrElse { return it.state() }
        val page = when (collection) {
            AppleLibraryCollection.PLAYLISTS -> client.libraryPlaylists(offset)
            AppleLibraryCollection.ALBUMS -> client.libraryAlbums(offset)
            AppleLibraryCollection.SONGS -> client.librarySongs(offset)
        }.getOrElse { return it.state() }
        return page.state(offset)
    }

    suspend fun tracks(
        collection: AppleLibraryCollection,
        collectionId: String,
        offset: Int = 0,
    ): AppleLibraryState {
        val client = client().getOrElse { return it.state() }
        val page = when (collection) {
            AppleLibraryCollection.PLAYLISTS -> client.libraryPlaylistTracks(collectionId, offset)
            AppleLibraryCollection.ALBUMS -> client.libraryAlbumTracks(collectionId, offset)
            AppleLibraryCollection.SONGS -> client.librarySongTracks(collectionId, offset)
        }.getOrElse { return it.state() }
        return page.state(offset)
    }

    private suspend fun client(): Result<AddonClient> = try {
        val probe = AddonClient(config.baseUrl)
        val manifest = probe.manifest().getOrThrow()
        val capability = manifest.library
            ?: throw LibraryUnconfiguredException()
        if (!capability.isAppleMusic) throw LibraryUnconfiguredException()
        val credential = capability.authentication?.setting
            ?.takeIf { it.isNotBlank() }
            ?.let { config.settings[it] }
        Result.success(AddonClient(config.baseUrl, credential))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private fun Throwable.state(): AppleLibraryState = when (this) {
        is LibraryUnconfiguredException -> AppleLibraryState.Unconfigured
        is AddonAuthenticationException -> when (code) {
            null, "signed_out", "token_expired", "missing_credential" -> AppleLibraryState.SignedOut
            else -> AppleLibraryState.Failure(message ?: "Apple Music sign-in failed", retryable = false)
        }
        is AddonUnsupportedException -> AppleLibraryState.Failure(
            message ?: "This addon does not provide an Apple Music library",
            retryable = false,
        )
        else -> AppleLibraryState.Failure(message ?: "Apple Music library is unavailable")
    }
}

private class LibraryUnconfiguredException : Exception("Apple Music library capability is not configured")

private fun com.music.bitchord.data.sources.addon.AddonLibraryPage.state(
    requestedOffset: Int,
): AppleLibraryState {
    if (items.isEmpty()) return AppleLibraryState.Empty
    val next = nextOffset
    val hasNext = hasMore ?: (
        next != null || total?.let { requestedOffset + items.size < it } == true
    )
    return AppleLibraryState.Ready(
        items = items,
        hasMore = hasNext,
        nextOffset = next ?: (requestedOffset + items.size).takeIf { hasNext },
    )
}

/** The playable row for a library track, preserving its exact addon stream id. */
fun AddonLibraryItem.toSong(configId: String): Song? {
    val streamId = streamId.takeIf { it.isNotBlank() } ?: return null
    return Song(
        videoId = SourceRegistry.trackKey(configId, streamId),
        title = title.ifBlank { "Untitled" },
        artist = artist,
        albumName = album.ifBlank { null },
        thumbnailUrl = artwork,
        durationText = durationSec?.let {
            "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}"
        },
        appleCatalogId = catalogId.trim().takeIf { it.isNotEmpty() },
    )
}
