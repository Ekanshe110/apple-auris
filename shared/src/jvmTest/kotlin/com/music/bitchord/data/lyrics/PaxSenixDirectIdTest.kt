package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaxSenixDirectIdTest {

    @Test
    fun `direct Apple id route encodes the id and requests TTML`() = runBlocking {
        var requestedUrl: String? = null
        val lines = PaxSenix.fetchLyricsById("123/abc &more") { url ->
            requestedUrl = url
            """{"content":"<tt><body><div><p begin=\"1.0\" end=\"2.0\"><span begin=\"1.0\" end=\"2.0\">hello</span></p></div></body></tt>"}"""
        }

        assertEquals(
            "https://lyrics.paxsenix.org/apple-music/lyrics?id=123%2Fabc%20%26more&ttml=true",
            requestedUrl,
        )
        assertEquals("hello", lines?.single()?.text)
    }

    @Test
    fun `blank direct Apple id makes no request`() = runBlocking {
        var requested = false
        val lines = PaxSenix.fetchLyricsById("  ") {
            requested = true
            null
        }

        assertNull(lines)
        assertEquals(false, requested)
    }

    @Test
    fun `repository uses direct Apple id without catalogue fallback`() = runBlocking {
        var directId: String? = null
        var fallbackCalled = false
        val expected = listOf(LyricLine(timeMs = 1_000L, text = "direct"))

        val lines = LyricsRepository.fetchPaxSenix(
            appleSongId = "12345",
            title = "Song",
            artist = "Artist",
            durationMs = 2_000L,
            album = null,
            byId = { id ->
                directId = id
                expected
            },
            byName = { _, _, _, _ ->
                fallbackCalled = true
                null
            },
        )

        assertEquals("12345", directId)
        assertEquals(false, fallbackCalled)
        assertEquals(expected, lines)
    }

    @Test
    fun `strict Apple row without catalogue id makes no lookup`() = runBlocking {
        val result = LyricsRepository.lyrics(
            videoId = "src:apple::stream-token",
            title = "Song",
            artist = "Artist",
            durationMs = 2_000L,
            appleSongId = null,
            strictApple = true,
        )

        assertNull(result)
    }

    @Test
    fun `repository keeps name route when Apple id is absent`() = runBlocking {
        var fallbackCalled = false
        val expected = listOf(LyricLine(timeMs = 1_000L, text = "search"))

        val lines = LyricsRepository.fetchPaxSenix(
            appleSongId = null,
            title = "Song",
            artist = "Artist",
            durationMs = 2_000L,
            album = null,
            byId = { error("direct route must not run") },
            byName = { title, artist, durationMs, _ ->
                fallbackCalled = title == "Song" && artist == "Artist" && durationMs == 2_000L
                expected
            },
        )

        assertEquals(true, fallbackCalled)
        assertEquals(expected, lines)
    }
}
