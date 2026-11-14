# Apple Auris

Apple Auris is an additive fork of [OreoMuncher45/Auris](https://github.com/OreoMuncher45/Auris), based on upstream branch `feature/automix-core-v2`.

## Fork policy

- Upstream owns music discovery, playback, decoder, and player behavior.
- Apple Music catalog and library support is opt-in and never replaces Spotify.
- Fork-specific changes stay small, tested, and separate from upstream commits.
- Keystores and service credentials are never committed.

## Current fork build

- Application label: `Apple Auris`
- Development package: `com.dev.bitchord`
- Version: `3.0.41-apple.1`
- Version code: `149`

The development APK is signed by GitHub Actions using the repository's `ANDROID_KEYSTORE_B64` secret. It is suitable for testing and sideloading, not Play Store distribution.

## Apple Music catalog

Enable **Use Apple Music catalog** in Settings to route catalog searches through the configured Apple Music addon. Apple results retain Apple's song IDs from search through playback and lyrics. The fork does not convert Spotify IDs or silently substitute another song when Apple lookup fails.

With the setting disabled, upstream Spotify recommendations, matching, and fallback behavior remain unchanged.

## Apple Music library

The Android client includes an addon-backed Apple Music library protocol and a dedicated Library page. It parses an optional manifest capability, reads paged playlist/album/song collections and collection tracks, preserves library/catalog/stream identifiers, and exposes machine-readable authentication errors. The page distinguishes loading, empty, signed-out, and failed states. It does not include MusicKit integration, an Apple sign-in flow, or fabricated library data.

Library access requires an external addon that implements the documented endpoint and a real Apple Music account configured with that addon. The app can present the addon's account state, but cannot sign in to Apple directly.

See [docs/APPLE_LIBRARY_ENDPOINT.md](docs/APPLE_LIBRARY_ENDPOINT.md) for the exact contract and current limitations.

## Building

Use JDK 17 and Android SDK 37:

```bash
./gradlew :app:testDevDebugUnitTest
./gradlew :app:assembleDevRelease
```

GitHub Actions builds and verifies signed development APKs with `apple-auris-apk.yml`.
