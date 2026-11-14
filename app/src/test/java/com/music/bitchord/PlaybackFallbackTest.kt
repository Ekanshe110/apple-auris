package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.HomeProvider
import com.music.bitchord.data.sources.SourceCatalogRole
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.playback.PlaybackFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFallbackTest {

    @Test
    fun `failed upgraded YouTube item is an alternative`() {
        assertTrue(
            PlaybackFallback.isAlternative(
                "bitchord://watch?v=5IX-nUxDtJI&n=Bin%20Tere&q=hifi",
                substitutedYouTube = false,
            ),
        )
    }

    @Test
    fun `failed substituted base item is an alternative`() {
        assertTrue(
            PlaybackFallback.isAlternative(
                "bitchord://watch?v=5IX-nUxDtJI&n=Bin%20Tere",
                substitutedYouTube = true,
            ),
        )
    }

    @Test
    fun `direct YouTube fallback never falls back recursively`() {
        assertFalse(
            PlaybackFallback.isAlternative(
                "bitchord://watch?v=5IX-nUxDtJI&direct_youtube=1&q=original",
                substitutedYouTube = true,
            ),
        )
    }

    @Test
    fun `ordinary YouTube audio is not treated as a failed alternative`() {
        assertFalse(
            PlaybackFallback.isAlternative(
                "bitchord://watch?v=5IX-nUxDtJI&n=Bin%20Tere",
                substitutedYouTube = false,
            ),
        )
    }

    @Test
    fun `source already forced to YouTube never falls back recursively`() {
        assertFalse(
            PlaybackFallback.isAlternative(
                "bitchord://source?s=addon-1&t=3919244&n=Bin%20Tere&direct_youtube=1&q=original",
                substitutedYouTube = false,
            ),
        )
    }

    @Test
    fun `Apple source failure never falls back to metadata matched YouTube`() {
        val previous = SourceRegistry.configs.value
        val previousProvider = AppSettings.homeProvider.value
        try {
            AppSettings.homeProvider.value = HomeProvider.APPLE
            SourceRegistry.configs.value = listOf(
                SourceConfig(
                    id = "apple-config",
                    kind = SourceKind.ADDON,
                    baseUrl = "https://apple.invalid",
                    catalogRole = SourceCatalogRole.APPLE_MUSIC,
                ),
            )

            assertFalse(
                PlaybackFallback.isAlternative(
                    "bitchord://source?s=apple-config&t=3919244&n=Bin%20Tere",
                    substitutedYouTube = false,
                ),
            )
        } finally {
            AppSettings.homeProvider.value = previousProvider
            SourceRegistry.configs.value = previous
        }
    }

    @Test
    fun `source fallback removes manifest and upgrade markers and gets its own rendition`() {
        val failed =
            "bitchord://source?s=addon-1&t=3919244&n=Bin%20Tere&a=Vishal%20%26%20Shekhar" +
                "&manifest_reopen=dash&q=hifi#part"

        assertEquals(
            "bitchord://source?s=addon-1&t=3919244&n=Bin%20Tere&a=Vishal%20%26%20Shekhar" +
                "&direct_youtube=1&q=original#part",
            PlaybackFallback.directYouTubeSourceUri(failed),
        )
    }
}
