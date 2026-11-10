# Apple Auris

Apple Auris is a personal GPL-3.0 fork of [Auris](https://github.com/OreoMuncher45/Auris), based on the `feature/automix-core-v2` branch.

The upstream app and its credits remain intact. This fork exists to make the Apple Music addon path faster and to carry the Dolby/Atmos playback work, with signed APKs built entirely by GitHub Actions.

## Download

Signed dev APK (sideload; package `com.dev.bitchord`):

- [Apple Auris 3.0.22-apple.1 release](https://github.com/Ekanshe110/apple-auris/releases/tag/apple-auris-2)
- [arm64-v8a APK](https://github.com/Ekanshe110/apple-auris/releases/download/apple-auris-2/app-dev-arm64-v8a-release.apk) — recommended for most phones
- [universal APK](https://github.com/Ekanshe110/apple-auris/releases/download/apple-auris-2/app-dev-universal-release.apk)

Verified: `1240` Android unit tests, `0` failures, `1` live-network test skipped; APK package/version/signature checked in CI.

## What is different here

- **Stereo-only Atmos fast path** — when an addon search row explicitly declares a stereo mix, the player skips the wasted Atmos `/stream` rung and asks for lossless directly. Rows that declare Atmos, or say nothing, still ask for Atmos first.
- **Apple Auris dev APK** — package `com.dev.bitchord`, label `Apple Auris`, version `3.0.22-apple.1` (`versionCode 122`).
- **GitHub Actions build** — `.github/workflows/apple-auris-apk.yml` provisions JDK 17, the Android SDK and Gradle on GitHub's runner, signs with `ANDROID_KEYSTORE_B64`, verifies the APK, uploads it as an artifact and (on manual dispatch) drafts a prerelease.
- **Dolby/Atmos path retained** — the upstream multichannel USB, E-AC-3 JOC, bundled FFmpeg fallback, Atmos quality rung and addon `atmos=auto` handling are kept.

## Build the APK

### GitHub Actions (recommended, no VPS)

1. Open **Actions → Apple Auris APK → Run workflow**.
2. The signed APKs appear under the run's **Artifacts → apple-auris-apk**.
3. A manual run also creates a draft prerelease named `Apple Auris APK build <run>`.

Required repository secret:

```text
ANDROID_KEYSTORE_B64   base64 of the fork's PKCS#12 keystore
```

Optional Last.fm secrets:

```text
LASTFM_API_KEY
LASTFM_SECRET
```

Without Last.fm keys the app still builds and runs; Last.fm scrobbling is not configured.

### Local build

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # or another SDK with platform 37 + build-tools
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
./gradlew :app:assembleDevRelease
```

Output:

```text
app/build/outputs/apk/dev/release/
```

## Signing

The release keystore is **not** committed. GitHub Actions reads it from the `ANDROID_KEYSTORE_B64` secret and writes the same `keystore.properties` contract upstream uses:

```properties
storeFile=bitchord-fork.jks
storePassword=android
keyAlias=forkdebug
keyPassword=android
```

The package is the dev flavor, so it installs beside `com.music.bitchord` rather than replacing it. A build signed with a different key cannot upgrade an existing installation signed with another key.

## Upstream sync

```bash
git remote add upstream https://github.com/OreoMuncher45/Auris.git
git fetch upstream
git merge upstream/feature/automix-core-v2
```

## License

GPL-3.0, inherited from Auris. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
