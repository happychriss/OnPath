# Pre-release review – architecture & code hygiene (app/src/main/java/com/onpath/app)

Reviewer focus: overall architecture, AppViewModel / MainActivity / OnPathApp, data/, cells/, map/, search/, poi/,
update/, debug/, strings/. Read-only; base = main @ 15de6d99 (web-spike worktree @ f31b9acb).

## Findings (most severe first)

### 1. HIGH – The "release" APK given to users is the debug build (debuggable, test map server, developer tools for everyone)
- **Where:** `.github/workflows/release.yml:129,135` (`assembleDebug`, `app-debug.apk`); `app/build.gradle.kts:62-67`
  (debug → `MAP_SERVER_ENV=test`, `maps-test.…`); `ui/AppViewModel.kt:257` + `DevToolsUnlock.shown(debugBuild || unlocked)`.
- **What / consequence:** every user gets `android:debuggable=true` (anyone with adb can `run-as com.onpath.app` and read
  tours, the sync pairing secret, prefs; ART runs debuggable apps with fewer optimisations → Compose jank and more battery
  drain while navigating); the app talks to the **test** doorman; and because `BuildConfig.DEBUG` is true, **Developer tools
  (Demo location, test style, map data import, routing formulas) are visible to every first user** without the 7-tap unlock.
- **Evidence:** `fun shown(debugBuild: Boolean, unlocked: Boolean) = debugBuild || unlocked`; release.yml builds only debug.
  `docs/design/map-hosting.md:295` says production/`assembleRelease` "comes with Play" – a known decision, but its side
  effects (dev tools, debuggable) look unintended for real users.
- **Fix (cheap, before tomorrow):** decouple dev tools from `BuildConfig.DEBUG` (e.g. a `buildConfigField DEV_TOOLS_DEFAULT`
  false for CI-built releases, or `-Ponpath.publicRelease=true` in release.yml). Medium term: a `release`-type build signed
  with the test key (`signingConfig = signingConfigs.debug` on `release`) so it is not debuggable.
- **Role:** cloud-app (workflow change → PR to owner). **Certainty:** high (dev tools / debuggable), medium (perf impact size).

### 2. HIGH – Real users' APKs are signed with a public key committed to the repo
- **Where:** `keystore/onpath-test.keystore`, `app/build.gradle.kts:49-56` (passwords `android`), `keystore/README.md`
  ("must never be used for Google Play releases").
- **What / consequence:** anyone can build an APK `com.onpath.app` signed with this key; a user who installs it (a "mod",
  a phishing link) gets it as an in-place **update with full access to their tours and sync keys**. And once the first real
  users are on this key, moving them to a private / Play key later requires uninstall (data loss unless backup) – unless key
  rotation (APK Signature Scheme v3 lineage, Android 9+) is prepared. The backlog item (`docs/backlog.md:204`) is still open.
- **Fix:** owner decision *before inviting users*: create the private release key now (GitHub secret, release.yml signs with
  it; the test key keeps signing dev builds) – testers install once fresh. If not now: document a v3 rotation plan
  (`apksigner rotate`) and accept that builds of the public key can replace OnPath.
- **Role:** cloud-app / owner (signing = PR to owner). **Certainty:** high.

### 3. MEDIUM – AppViewModel is a 5 000-line god class (plus a 265-line MainActivity.onCreate)
- **Where:** `ui/AppViewModel.kt` – 4 994 lines, 115 `mutableStateOf` fields, ~318 functions, ~30 feature sections
  (map cells, demo, routing data, downloads, offline maps, planning, hold menus, search, shared places, time estimate,
  route warnings, Plan⇄Navigate, activity check, follow/record, tours, plan sync, plan links, recycle bin, phone setup,
  GPX import, updates, routing freshness …). `MainActivity.kt` 772 lines, `onCreate` lines 114-379.
- **Consequence:** every feature change touches the same file (merge conflicts across the parallel sessions), state is
  coupled implicitly (e.g. `followNextRoute`, `planEditPending`, `adoptPlanBaseline`, `fitNextRoute` flags interacting in one
  `onSuccess`), unit testing needs the whole VM, and the init order matters ("Last in the class: every property it touches is
  initialised by now", line 4903) – a classic source of NPE-at-start bugs.
- **Fix (after release):** extract self-contained controllers that the VM owns, starting with the ones with no plan
  coupling: `UpdateController` (1618-1780), `PlanSyncUi` (3989-4262), `PhoneSetupState` (4536-4751), `CellsUi` (375-1167),
  `SharedPlaceFlow` (2372-2486). Keep the VM as the façade the screens read.
- **Role:** cloud-app. **Certainty:** high.

### 4. MEDIUM – Raw exception text shown to the user when a route fails
- **Where:** `ui/AppViewModel.kt:2712` – `routeError = it.message ?: Txt.s(Res.string.msg_routing_failed)`; shown in
  `PlanPanel.kt:373`.
- **What:** `it` is any `Throwable` from `runCatching { PlanResult.plan(...) }` (2662) – not only `RoutingException` (which
  is localised). An `IllegalStateException`, `IndexOutOfBounds…` or BRouter-internal message would be shown verbatim, in
  English, in a German UI – exactly what `debug/UserText` and the "no technical error texts" rule forbid (UserTextRuleTest
  does not catch this pattern).
- **Fix:** `routeError = UserText.of(it, Txt.s(Res.string.msg_routing_failed))`.
- **Role:** cloud-app. **Certainty:** high.

### 5. MEDIUM – App ⇄ web shared code is a file list on another branch; nothing on main compiles it
- **Where:** `web/engine/build.gradle.kts` `appFiles` (web-spike) vs `app/src/test/.../SharedCoreRuleTest.kt` `files` /
  `webShared` (main); web CI only `on: push: branches: [web-spike]`; web-spike is 12 commits behind origin/main today.
- **What:** 19 files the web compiles are not covered by the app's guard (e.g. `ui/PlanState.kt`, `ui/MapGestures.kt`,
  `ui/PlanStatus.kt`, `search/OnlineSearch.kt` (Gson), `search/OfflineIndex.kt`, `data/AppSettings.kt`, `map/Overview.kt`,
  `poi/PoiFilter.kt`, `data/LastActivity.kt`). An app-session edit adding an Android/`java.security`/reflection use there is
  green on main and breaks the planner only at the next merge into web-spike – the "identical in app and browser" promise
  (PlanResult, ActivityLayers) silently diverges until then.
- **Fix:** one list – move `appFiles` to a file on main (e.g. `web/shared-files.txt`) read by both the web build and
  SharedCoreRuleTest; or add a cheap main-side job that compiles the shared list on the plain JVM with the stubs.
- **Role:** cloud-plan (with cloud-app). **Certainty:** high (lists differ), medium (how often it bites).

### 6. MEDIUM – Four hand-rolled resumable HTTP downloaders with different safety levels; APK not checksum-verified
- **Where:** `update/AppUpdater.kt:117-168` (`downloadOnce`), `cells/CellApi.kt:69-110`, `data/SegmentDownloader.kt:31+`,
  `data/Rd5PartStore.kt:45+` (`HttpRd5Remote`); plus `plansync/HttpMailbox`, `search/OnlineSearch`, `search/SharedPlace`.
- **What:** each re-implements Range resume, redirect handling, timeouts, 200-vs-206 logic. Only CellApi verifies sha256 and
  fsyncs. AppUpdater checks size only although release.yml publishes `SHA256SUMS` (and GitHub's asset JSON carries a
  `digest`), and follows `Location` to any scheme (`URL(url, loc)` – an http downgrade is accepted). SegmentDownloader /
  HttpRd5Remote: no checksum (brouter.de offers none), no `User-Agent`.
- **Consequence:** a corrupted/truncated-then-resumed APK (part file kept across taps) only fails in the system installer
  with "package appears to be invalid" – Android's signature check prevents a foreign APK from installing in place, so this is
  robustness, not a hole. Fixes to resume bugs must be made four times.
- **Fix:** one `HttpDownload.resume(url, part, expectedSize, sha256?)` helper (https-only redirects, UA, fsync); verify the
  APK against the asset `digest` / `SHA256SUMS` before handing it to the installer.
- **Role:** cloud-app. **Certainty:** high.

### 7. MEDIUM – Every user downloads routing data straight from brouter.de (third-party volunteer server)
- **Where:** `data/SegmentDownloader.kt:15` (`BASE_URL = "https://brouter.de/brouter/segments4/"`), `data/Rd5PartStore.kt:45`,
  `data/RoutingDataLoader.kt:204`.
- **What / consequence:** with real users the app depends on a server OnPath does not run (availability, fair use, bans on
  an anonymous default `Dalvik/…` UA, file changes mid-read handled via If-Range). Map cells come from OnPath's own R2;
  routing – "what matters most" – does not.
- **Fix:** at least a `User-Agent: OnPath-Android/<version> (+contact)`; plan to mirror rd5 into the existing R2/doorman.
- **Role:** cloud-app. **Certainty:** medium.

### 8. LOW – Copy-pasted geo math and tile naming
- **Where:** distance: `data/Geo.kt:15` (haversine) vs identical equirectangular `distM` in `routing/WaypointSnap.kt:129` and
  `routing/BRouterEngine.kt:524`, `data/SegmentDownloader.kt:129` (`111_320` m/°); `111_xxx` constants in 9 files
  (WaypointInsertion ×4, RouteNamer, Rd5Parts, NavLine, CellMath, TrackCode, HeadingEngine …). rd5 tile name ×3:
  `Rd5Parts.tileName`, `BRouterEngine.segmentName`, `SegmentDownloader.tileName`. Also `Geo.distance(a,b): Double` and
  `Geo.distance(m): String` (formatting) share a name in one object.
- **Consequence:** small, inconsistent metre values between snapping, insertion and display; a fix to one copy misses the
  others. Low risk now.
- **Fix:** `Geo.fastDistance` (equirectangular) + `Geo.M_PER_DEG_LAT` in the shared `data/Geo.kt`; `Rd5Parts.tileName` as the
  one tile namer; move the text formatters into a `Format` object.
- **Role:** cloud-app (shared files → tell cloud-plan). **Certainty:** high.

### 9. LOW – Silent failures that break the "no silent errors" rule
- **Where:** `ui/AppViewModel.kt:4220` and `:4234` (`runCatching { db.getTour(id) }.getOrNull()` – no Problems report; a DB
  error looks like "route deleted" and the open plan is unlinked: `setSaved(null, -1, null)`).
  Also `runCatching { … }` around coroutine work (2662, 3771) catches `CancellationException` and `OutOfMemoryError`.
- **Fix:** `.onFailure { Problems.warn("db", …, it) }`; in coroutines prefer `try/catch (e: Exception)` and rethrow
  cancellation.
- **Role:** cloud-app. **Certainty:** high (4220 unlinks only when `t == null`, so a DB exception does unlink).

### 10. LOW – Map style is built on the main thread on every reload
- **Where:** `ui/MapHost.kt:122` `styleJson()` → `map/StyleLoader.kt:146` (reads the 148 KB style from assets or the data
  folder, `JSONObject` parse for dev/override styles, `withOverview` merge, `File.isFile` checks per source); called at start
  and on each cell-store generation change (also during navigation). `StyleLoader.lastMapDir` / `lastLoaded` are unsynchronised
  globals.
- **Consequence:** tens of ms of main-thread work + disk IO per reload → visible hitch, StrictMode violations.
- **Fix:** build the JSON in `Dispatchers.IO`, then `setStyle` on main; return `(json, mapDir)` instead of globals.
- **Role:** cloud-app. **Certainty:** medium (not measured).

### 11. LOW – SharedPreferences "onpath" written around `Prefs`
- **Where:** `ui/AppViewModel.kt:1639` (`updateCheck`), `MainActivity.kt:606` (`crashShownUntil`), `IncomingIntent.kt:36`
  (`lastIncomingIntent`), `data/AppLanguage.kt:25`.
- **Consequence:** keys are scattered (no single list of what is backed up via `backup_rules.xml` → `onpath.xml`; e.g. a
  restored phone inherits `crashShownUntil`/`updateCheck`), collisions possible.
- **Fix:** route through `Prefs` (or a `PrefKeys` object). **Role:** cloud-app. **Certainty:** high.

### 12. LOW – Intent filters accept `file://` and any stream; the import opens it with the app's own rights
- **Where:** `AndroidManifest.xml` VIEW filters (`scheme="file"`), `MainActivity.kt:520-528` → `vm.importGpx(uri)` →
  `contentResolver.openInputStream(uri)` (`AppViewModel.kt:4765`).
- **What:** another app can hand OnPath `file:///data/data/com.onpath.app/files/sync/…`; OnPath reads its own private file.
  It is parsed as GPX (fails → error), so nothing realistic leaks, but it is the standard confused-deputy pattern. Any non-GPX
  VIEW URI (e.g. `https://plan.onpath.earth/` without `#p=`) also ends up in `importGpx` → "cannot open" error.
- **Fix:** reject `file:` URIs under `filesDir`/`dataDir` (or drop the `file` scheme – senders targeting API 24+ cannot send
  it anyway); only call `importGpx` for `content:`/`file:` schemes.
- **Role:** cloud-app. **Certainty:** high (behaviour), low (impact).

### 13. LOW – Two JSON libraries, one of them undeclared
- **Where:** Gson in `search/OfflineIndex.kt`, `search/OnlineSearch.kt`, `cells/PoiTiles.kt`, `cells/GeoJsonMerge.kt`
  (comes only transitively through MapLibre / maplibre-java); `org.json` in 23 files.
- **Consequence:** a MapLibre upgrade that drops/changes Gson breaks the build; the web stubs `com/google/**` for it.
- **Fix:** declare `com.google.code.gson:gson` explicitly (with a licence entry) or move those four to org.json.
- **Role:** cloud-app. **Certainty:** high.

### 14. LOW – Small dead code / unused texts
- 6 unused string keys (`common_open`, `common_retry`, `maps_hide_cd`, `maps_import_cant_replace`, `msg_name_share`,
  `plan_route_options`); main-source functions used only by tests (`AppViewModel.sharePlan` – E2E only, though CLAUDE.md
  says Share plan is in My routes ⋮ – check it is still reachable; `reloadMapStyleForTest`, `Problems.flushForTest`,
  `StyleLoader.noteTestStyleLoadedForTest`, `Backups.forgetLikeReinstall`, `TourDb.pointAccuracies`, `DiskIndex.allPlaces`).
- Overall dead code is remarkably low (13 test-only functions in 267 files). **Role:** cloud-app. **Certainty:** high.

## Checked and found sound (no finding)
- `debug/Problems`: bounded (300 entries / 200 events), dedupe, cancellation ignored, synchronous write on crash; the
  empty catches in `Problems.kt:231` and `OptionalSources.kt:41` are the logger's own fallback.
- Silent-catch scan: of 482 `catch` + 62 `runCatching`, only the items in #9 lack a report or `// expected:`.
- `data/AtomicFiles` (rename(2), fsync, never delete-first) used for state files; `TourDb` keeps a corrupt DB aside,
  WAL, transactions for multi-row writes, column-checked upgrades.
- `cells/CellManifest`/`CellApi`: safe-name regex, `..` rejection, sha256 + size verified, fsync before rename.
- `DataImporter.targetPath` (zip-slip safe, only known file types), `GpxParser` with external entities off and size limit.
- Manifest: only `MainActivity` exported; receiver/services/FileProvider not exported; FileProvider exposes only
  `cache/export` and `cache/updates`; backup rules exclude the sync secret. No Play Services; HTTPS everywhere.
- Camera rule: every `moveCamera/easeCamera/animateCamera` is in `MapController`, and the fit/fly callers turn following off
  first (e.g. `fitFirstRoute` sets `followMe = false` before `UiEvent.Fit`).
- strings/: EN and DE have identical key sets (1 213 keys); hand-set `fontSize` only where `FontSizeRuleTest` allows it.
- BRouter use is serialised by one global lock, so the VM's and the service's routing threads do not collide.

## Architecture overall
The layering is better than the size suggests: pure, unit-tested logic (routing/, navigation/, time/, sync/, setup/,
cells' math, the many `*Rule` objects) is cleanly separated from Android, guarded by rule tests, and robustness conventions
(Problems, AtomicFiles, UserText, checksummed downloads) are applied with unusual consistency. The weak point is the
presentation layer: one 5 000-line `AppViewModel` holds the state of ~30 features and coordinates them through boolean flags,
and `MainActivity` wires everything in one long `onCreate` – this is where future regressions and session merge conflicts
will concentrate. Infrastructure code (HTTP downloads, geo helpers, prefs access) has grown by copy rather than by shared
helpers, which is cheap to fix later. The app/web sharing works but is held together by a file list on a long-lived branch
rather than a module boundary. For tomorrow, the decisive items are not in the Kotlin code but in the release pipeline: a
debuggable debug build with developer tools for everyone, signed with a public key.
