package com.music.bitchord.data.apple

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.Locale

/**
 * Attaches Apple's exact catalogue id to discovery rows before they enter a
 * queue.
 *
 * Spotify and YouTube remain discovery catalogues. When Apple Rich Mode is on,
 * this asks the configured Apple addon once per distinct track and stores the
 * exact catalogue id on the [Song]. Playback then opens that id directly; it
 * never converts a provider id on ExoPlayer's loader thread and never asks a
 * metadata matcher to guess another recording.
 *
 * Lookups are bounded, cached, and shared between concurrent callers. A failed
 * lookup is remembered briefly as a failure too, so one unavailable Apple
 * account cannot turn every home shelf into the same repeated request.
 */
object AppleEnrichment {

    private const val TAG = "BitChord"
    private const val MAX_CONCURRENT_LOOKUPS = 4
    private const val MAX_CACHE_ENTRIES = 512
    private const val POSITIVE_CACHE_MS = 6 * 60 * 60 * 1000L
    private const val NEGATIVE_CACHE_MS = 5 * 60 * 1000L

    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lookupGate = Semaphore(MAX_CONCURRENT_LOOKUPS)
    private val inFlightLock = Mutex()
    private val inFlight = mutableMapOf<LookupKey, Deferred<Result<String?>>>()

    private data class LookupKey(
        val title: String,
        val artist: String,
        val durationSec: Long?,
    )

    private data class CachedLookup(
        val result: Result<String?>,
        val expiresAtMs: Long,
    )

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<LookupKey, CachedLookup>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<LookupKey, CachedLookup>) =
                size > MAX_CACHE_ENTRIES
        },
    )

    /**
     * Enriches rows which still need an exact Apple id.
     *
     * Existing ids are kept. Local files, already source-backed rows and
     * direct media URLs are outside this path: they are not Spotify or YouTube
     * discovery rows and must not be sent to Apple.
     */
    suspend fun enrich(songs: List<Song>): List<Song> {
        if (songs.isEmpty() || !AppSettings.appleRichModeEnabled) return songs

        val config = SourceRegistry.appleCatalogConfig()
        if (config == null) {
            return songs.map { song ->
                if (song.needsAppleLookup()) {
                    song.copy(appleLookupError = "Apple Music is not configured")
                } else {
                    song
                }
            }
        }
        val source = SourceRegistry.instance(config.id) as? AddonSource
        if (source == null) {
            return songs.map { song ->
                if (song.needsAppleLookup()) {
                    song.copy(appleLookupError = "Apple Music addon is unavailable")
                } else {
                    song
                }
            }
        }

        val targets = songs.filter { it.needsAppleLookup() }
        if (targets.isEmpty()) return songs

        val results = coroutineScope {
            targets.distinctBy { it.appleLookupKey() }.map { song ->
                async {
                    song.appleLookupKey() to lookup(song, source)
                }
            }.awaitAll().toMap()
        }

        return songs.map { song ->
            val result = results[song.appleLookupKey()] ?: return@map song
            result.fold(
                onSuccess = { appleId ->
                    if (appleId.isNullOrBlank()) {
                        song.copy(
                            appleCatalogId = null,
                            appleLookupError = "Apple Music catalogue not found",
                        )
                    } else {
                        song.copy(
                            appleCatalogId = appleId,
                            appleLookupError = null,
                        )
                    }
                },
                onFailure = { failure ->
                    song.copy(
                        appleCatalogId = null,
                        appleLookupError = failure.message ?: "Apple Music lookup failed",
                    )
                },
            )
        }
    }

    /** Whether this row is a discovery row which still needs Apple's catalogue id. */
    private fun Song.needsAppleLookup(): Boolean =
        appleCatalogId == null &&
            appleLookupError == null &&
            localUri == null &&
            !videoId.startsWith("http://") &&
            !videoId.startsWith("https://") &&
            !videoId.startsWith("content://") &&
            !videoId.startsWith("file://") &&
            SourceRegistry.parseTrackKey(videoId) == null

    private fun Song.appleLookupKey(): LookupKey = LookupKey(
        title = normalized(title),
        artist = normalized(artist),
        durationSec = durationMillis().takeIf { it > 0 }?.div(1000),
    )

    private suspend fun lookup(song: Song, source: AddonSource): Result<String?> {
        val key = song.appleLookupKey()
        cached(key)?.let { return it.result }

        val deferred = inFlightLock.withLock {
            inFlight[key] ?: lookupScope.async {
                lookupGate.withPermit { performLookup(song, source) }
            }.also { inFlight[key] = it }
        }
        return try {
            val result = deferred.await()
            cache[key] = CachedLookup(
                result = result,
                expiresAtMs = System.currentTimeMillis() + if (result.getOrNull().isNullOrBlank()) {
                    NEGATIVE_CACHE_MS
                } else {
                    POSITIVE_CACHE_MS
                },
            )
            result
        } finally {
            inFlightLock.withLock {
                if (inFlight[key] === deferred) inFlight.remove(key)
            }
        }
    }

    private fun cached(key: LookupKey): CachedLookup? {
        val entry = cache[key] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            cache.remove(key)
            return null
        }
        return entry
    }

    /** One exact Apple catalogue lookup. No title-only or nearest-result choice. */
    private suspend fun performLookup(song: Song, source: AddonSource): Result<String?> {
        val query = listOf(song.title, song.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (query.isBlank()) return Result.success(null)

        val rows = source.searchResult(query, limit = 10, request = null)
            .getOrElse { failure ->
                TrackLog.w(TAG, "Apple enrichment failed for '${song.title}': ${failure.message}")
                return Result.failure(failure)
            }
        return Result.success(exactAppleId(song, rows))
    }

    /**
     * The one exact Apple id for [song], or null when there is no unique answer.
     *
     * Kept pure so the identity rule can be tested without a network or an
     * addon. A title-only hit, a near artist, or two equally plausible Apple
     * rows are all null: choosing one would risk playing another recording.
     */
    internal fun exactAppleId(song: Song, candidates: List<Song>): String? {
        val exactIdentity = candidates.filter { candidate ->
            candidate.appleCatalogId?.isNotBlank() == true &&
                normalized(candidate.title) == normalized(song.title) &&
                normalized(candidate.artist) == normalized(song.artist)
        }
        if (exactIdentity.isEmpty()) return null

        val wantedSeconds = song.durationMillis().takeIf { it > 0 }?.div(1000)
        val exact = if (wantedSeconds == null) {
            exactIdentity
        } else {
            exactIdentity.filter { candidate ->
                val duration = candidate.durationMillis().takeIf { it > 0 }?.div(1000)
                duration == null || kotlin.math.abs(duration - wantedSeconds) <= 3
            }
        }
        if (exact.size != 1) return null
        return exact.single().appleCatalogId
    }

    /** Letters and numbers only, so punctuation and spacing cannot split identity. */
    internal fun normalized(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
