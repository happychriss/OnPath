# Google Play build (A-003)

The Play build is build type `play` in `app/build.gradle.kts`: the same app as the GitHub APK (test map server, same
`com.onpath.app`, same version), but **no own updater** (`BuildConfig.SELF_UPDATE = false`, no Settings → Updates, no
GitHub row in Settings → Privacy, `src/play/AndroidManifest.xml` drops `REQUEST_INSTALL_PACKAGES`), **not
debuggable**, **unminified** (R8 could strip what MapLibre / BRouter reach by reflection; shrinking is a later step
with its own test), signed with the **upload key**. All debug task names (`assembleDebug`, `installDebug`, …) are
unchanged. `PlayBuildTest` guards it. Built on the owner's machine and uploaded by hand – not in `release.yml`.

## Make a bundle
```bash
tools/dev/play_bundle.sh               # bundlePlay through a Gradle slot, checks, → dist/onpath-<version>.aab
SKIP_BUILD=1 tools/dev/play_bundle.sh  # check + copy the bundle already built
```
The version is the default `appVersion` in `app/build.gradle.kts` (versionCode = major*10000 + minor*100 + patch):
Play refuses a versionCode it has seen on any track, so a new upload needs a new release version.
The script checks the built files, not the source: signed with the upload key (its SHA-256 fingerprint is shown – Play
shows the same under *App integrity → Upload key certificate*), not debuggable, versionCode / versionName, targetSdk ≥ 36
(Play minimum since 2026-08-31), no `REQUEST_INSTALL_PACKAGES`, arm64 native libraries, the assets (style, BRouter,
fonts, sprites, licences, overview map – `onpath.overviewAssets`) and a map key (`onpath.mapKey.test`).

## The upload key
`.secrets/upload-keystore.jks` + `.secrets/upload-keystore.properties` (`storeFile` relative to `.secrets/`,
`storePassword`, `keyAlias`, `keyPassword`) – only on the owner's machine, gitignored, never printed.
`ONPATH_SECRETS=<dir>` points elsewhere (a worktree uses the main checkout's folder; tests use a throwaway key).
Without it `bundlePlay` builds **unsigned** (Gradle warns) and the script stops. It is the *upload* key: Play App
Signing holds the key the app ships under and re-signs every upload. A lost upload key can be reset through Play
support (slow) – back up the two files somewhere that is not this disk.

## Upload (by hand, Play Console)
1. First upload only: *Testing → Internal testing → Create new release*, accept **Play App Signing** with a
   Google-generated key, upload `dist/onpath-<version>.aab`, release notes, save, roll out.
2. *Internal testing → Testers*: an e-mail list (up to 100), copy the **opt-in link** for the testers.
3. *App content* declarations before the first rollout: privacy policy https://onpath.earth/privacy/, data safety,
   app access (pairing with the browser needs no login), ads: none, content rating questionnaire, target audience.
   *Monetize → App pricing*: free.

**A phone with a GitHub / debug build cannot update to the Play build** (same `com.onpath.app`, other signature):
uninstall first – that deletes the phone's tours and maps, so back up (Settings → Backup) before. Later Play builds
update in place.
