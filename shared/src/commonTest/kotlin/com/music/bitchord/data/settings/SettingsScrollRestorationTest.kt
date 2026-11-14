package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsScrollRestorationTest {
    @Test
    fun `durable offset is clamped to measured content`() {
        assertEquals(120, settingsScrollRestoreOffset(durableOffset = 120, maxValue = 400))
        assertEquals(400, settingsScrollRestoreOffset(durableOffset = 500, maxValue = 400))
        assertEquals(0, settingsScrollRestoreOffset(durableOffset = -5, maxValue = 400))
    }

    @Test
    fun `unmeasured content does not produce a negative or invalid offset`() {
        assertEquals(0, settingsScrollRestoreOffset(durableOffset = 120, maxValue = 0))
        assertEquals(0, settingsScrollRestoreOffset(durableOffset = 120, maxValue = -1))
    }
}
