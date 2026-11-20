# ABYSS AntiCheat: release signing, installing and updating

## One-time: create the release key
Do this once and keep the file and passwords somewhere safe. If the key is lost, installed copies can never be updated
(Android only installs an update if it is signed with the same key).

```
keytool -genkeypair -v -keystore abyss-release.jks -alias abyss -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 abyss-release.jks > abyss-release.jks.b64     # macOS: base64 -i abyss-release.jks
```

Add four repository secrets (GitHub: Settings > Secrets and variables > Actions):

| Secret | Value |
|---|---|
| `ABYSS_KEYSTORE_BASE64` | contents of `abyss-release.jks.b64` |
| `ABYSS_KEYSTORE_PASSWORD` | the keystore password |
| `ABYSS_KEY_ALIAS` | `abyss` |
| `ABYSS_KEY_PASSWORD` | the key password |

Never commit the keystore. With the secrets set, every push to `main` builds a release-signed APK and publishes it,
unzipped, at `https://github.com/Vanshu160/ABBYS-Anti-Cheat/releases/download/latest/ABYSS-AntiCheat.apk`.
Without the secrets, CI publishes the debug-signed build and the release notes say so.

Switching from the debug-signed build to the release-signed one changes the signing key, so Android requires
uninstalling the old copy once. After that, updates install in place as long as the package name
(`com.example.mobilescanner`) stays the same and `versionCode` goes up (CI uses the run number).

## What CI checks on every build
`gradle testDebugUnitTest` (session rules), server session tests, `assembleDebug`, `assembleRelease` + `bundleRelease`,
`apksigner verify`, and that the package name is unchanged. Pull requests sign with a throwaway key just to exercise the pipeline.

## Play Protect and Google Play
- A sideloaded APK from an unknown publisher can show "unrecognized app" or a scan prompt. A consistent release signature,
  a small permission list and Play distribution reduce this; nothing here bypasses or hides it, and a warning-free install is not guaranteed.
- The app needs sensitive permissions because of what it does, and Google Play restricts them:
  `QUERY_ALL_PACKAGES` (list installed apps), `MANAGE_EXTERNAL_STORAGE` (All Files Access), `PACKAGE_USAGE_STATS`.
  Play requires a declaration form for each and may reject apps whose core purpose does not fit. Expect review questions.
- For internal testing, upload `app-release.aab` (CI artifact `ABYSS-release-*-signed`) to a Play Console internal test track
  and enrol in Play App Signing.
- Dependencies are pinned. The Android Gradle Plugin / Kotlin / Compose versions have not been upgraded in this change.

## Not verified yet
Nothing in this repository has been installed or run on a physical Android device by the build pipeline: launcher support
(Zalith, Mojo, Pojav), the icon on real launchers, the install/update flow and the 15-minute session on a device must be checked by hand.
