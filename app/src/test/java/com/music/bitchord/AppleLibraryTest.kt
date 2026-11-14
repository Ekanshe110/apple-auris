package com.music.bitchord

import com.music.bitchord.data.apple.toSong
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Apple library identity rules that keep playback pinned to one addon. */
class AppleLibraryTest {

    @Test
    fun `library track keeps its exact stream id`() {
        val song = AddonLibraryItem(
            id = "library-resource",
            catalogId = "apple-catalog-id",
            streamId = "apple-stream-id",
            title = "Song",
            artist = "Artist",
        ).toSong("apple-config")

        assertEquals("src:apple-config::apple-stream-id", song?.videoId)
        assertEquals("apple-catalog-id", song?.appleCatalogId)
        assertEquals("Song", song?.title)
    }

    @Test
    fun `catalog id is never used as a stream id`() {
        val song = AddonLibraryItem(
            id = "library-resource",
            catalogId = "apple-catalog-id",
            streamId = "",
            title = "Song",
        ).toSong("apple-config")

        assertNull(song)
    }
}
