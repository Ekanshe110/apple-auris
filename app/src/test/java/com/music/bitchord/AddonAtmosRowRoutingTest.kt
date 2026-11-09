package com.music.bitchord

import com.music.bitchord.data.sources.addon.AddonTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addon search row is allowed to answer the Atmos question before the
 * `/stream` call is made.
 *
 * A catalogue that explicitly marks ordinary rows `STEREO` should not cost one
 * wasted Atmos request per track while the listener has Atmos enabled. The
 * inverse matters more: a row that says nothing, or says Atmos, must keep the
 * immersive rung. Only positive stereo metadata skips it.
 */
class AddonAtmosRowRoutingTest {

    @Test
    fun `explicit stereo metadata skips the Atmos rung`() {
        val mode = AddonTrack(id = "1", audioMode = "STEREO")
        val flag = AddonTrack(id = "2", atmos = false)
        val modes = AddonTrack(id = "3", audioModes = listOf("STEREO"))

        for (track in listOf(mode, flag, modes)) {
            assertTrue("${track.id} should be known stereo", track.isStereoOnly)
            assertFalse(track.isDolbyAtmos)
        }
    }

    @Test
    fun `an Atmos row keeps the immersive rung`() {
        val mode = AddonTrack(id = "1", audioMode = "DOLBY_ATMOS")
        val flag = AddonTrack(id = "2", atmos = true)
        val mixed = AddonTrack(
            id = "3",
            audioModes = listOf("STEREO", "DOLBY_ATMOS"),
        )

        for (track in listOf(mode, flag, mixed)) {
            assertTrue("${track.id} should be Atmos", track.isDolbyAtmos)
            assertFalse("${track.id} is not stereo-only", track.isStereoOnly)
        }
    }

    @Test
    fun `unknown metadata stays unknown`() {
        val track = AddonTrack(id = "1", title = "No mode supplied")
        assertFalse(track.isDolbyAtmos)
        assertFalse(track.isStereoOnly)
    }

    @Test
    fun `a lossless label alone does not prove stereo`() {
        val track = AddonTrack(id = "1", audioQuality = "LOSSLESS")
        assertFalse(track.isStereoOnly)
    }
}
