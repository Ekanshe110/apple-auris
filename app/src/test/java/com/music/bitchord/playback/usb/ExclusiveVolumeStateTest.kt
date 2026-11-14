package com.music.bitchord.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusiveVolumeStateTest {

    @Test
    fun `system listening level is curved once and app gain stays linear`() {
        val listening = 0.5f
        val app = 0.5f
        val expected = UacFeatureVolume.perceptualLinearGain(listening) * app

        assertEquals(expected, exclusiveSoftwareGain(listening, app), 1e-6f)
    }

    @Test
    fun `one system slider is not applied twice`() {
        val listening = 0.5f
        val once = UacFeatureVolume.perceptualLinearGain(listening)
        val squared = UacFeatureVolume.perceptualLinearGain(listening * listening)

        assertEquals(once, exclusiveSoftwareGain(listening, 1f), 1e-6f)
        assertTrue("double application makes normal steps feel much larger", once > squared)
    }

    @Test
    fun `remembered DAC level wins over an attach-time maximum`() {
        assertEquals(0.6f, attachMaxPinFallbackGain(1f, 0.6f)!!, 1e-6f)
        assertNull("a genuine maximum without prior evidence stays trustworthy", attachMaxPinFallbackGain(1f, Float.NaN))
    }

    @Test
    fun `Feature Unit needs a changed readback before it owns volume`() {
        assertTrue(featureVolumeReadbackProvesWritable(100, 101))
        assertFalse(featureVolumeReadbackProvesWritable(100, 100))
        assertFalse(featureVolumeReadbackProvesWritable(100, null))
        assertFalse(featureVolumeReadbackProvesWritable(null, 100))
    }
}
