package com.music.bitchord.data.settings

/**
 * Clamp a durable settings-page offset to the page's current scroll extent.
 *
 * Kept separate from Compose so restoration can be regression-tested without
 * an Android SDK. A stale offset must never ask a scroll state to move beyond
 * its measured content.
 */
fun settingsScrollRestoreOffset(durableOffset: Int, maxValue: Int): Int =
    durableOffset.coerceIn(0, maxValue.coerceAtLeast(0))
