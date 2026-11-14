package com.music.bitchord.data.sources

import kotlinx.serialization.Serializable

/**
 * The catalogue a source represents when the source's own protocol is
 * provider-neutral.
 *
 * Addon manifests describe capabilities, not vendor identity. A role is
 * therefore explicit user configuration rather than a guess made from an
 * addon name or hostname.
 */
@Serializable
enum class SourceCatalogRole {
    /** An ordinary source with no provider-specific identity. */
    GENERIC,

    /** An addon whose search rows and stream ids are Apple Music song ids. */
    APPLE_MUSIC,
}
