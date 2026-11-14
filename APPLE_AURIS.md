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

Apple library access depends on the configured addon exposing an authenticated Apple Music library endpoint. The client expects playlist, album, and song collection responses carrying stable Apple IDs and artwork. A missing endpoint is reported as unavailable; the app does not fabricate playlists or scrape a private web session.

See [docs/APPLE_LIBRARY_ENDPOINT.md](docs/APPLE_LIBRARY_ENDPOINT.md) for the required contract and current limitations.

## Building

Use JDK 17 and Android SDK 37:

```bash
./gradlew :app:testDevDebugUnitTest
./gradlew :app:assembleDevRelease
```

GitHub Actions builds and verifies signed development APKs with `apple-auris-apk.yml`.
