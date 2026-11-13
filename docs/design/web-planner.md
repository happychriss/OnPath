# Web planner (plan.onpath.earth)

Status: **live** on plan.onpath.earth (public, no login; `noindex` until the launch, §18.4). Code: `web/` on `main`
(one branch since 2026-11-06; `web-spike` is frozen). CI and deploy: "Web planner" (`.github/workflows/web.yml`, §9);
it goes online with an app release (release.yml job "planner") or by hand (Actions → Web planner → Run workflow →
deploy). Owner: `docs/roles/cloud-plan.md` (the laptop). Plan sync phone ⇄ browser: `docs/design/web-sync.md`.
Sections 1–5 (the first KMP proposal of 2026-10-23) were dropped; the section numbers stay because code cites them.

## 6. Architecture – the same bytecode in the browser (decided 2026-10-23)

**Key idea:** routing runs in the browser – no routing server, no compute cost, nothing to abuse; the server only sees
which map areas are needed. Same privacy story as the app ("everything on your device"). Browser → phone without an
account: the plan's points in a link / QR code (§8), later pairing (§12).

**What must be identical in phone and web:** routes (engine + profiles + data), distance / ascent / time / warnings,
the plan link format. **What need not be identical:** the UI – a desktop side panel is a different layout.

**Chosen (owner, option 2 of three):** TeaVM compiles the existing JVM classes – the BRouter jars + the app's
Android-free Kotlin, unchanged, read straight from `app/src/main/java` – into one Wasm GC module in a Web Worker. A small
TypeScript page + maplibre-gl JS on top (same style, same PMTiles). One toolchain (TeaVM + Vite), no change to the app.
Dropped: KMP + Compose for Web (large refactor, young UI stack) and a BRouter server (cost, abuse, not offline).
GraalVM Web Image is the newer JVM → Wasm route – watch, not use. A KMP module (e.g. for iOS) stays possible later.
Not in the browser on purpose: navigation and recording (weak GPS, stops in the background, no vibration).

## 7. What exists (built from the spike of 2026-10-23)

- `web/engine/` – separate Gradle build. Compiles, straight from `app/src/main/java` (no copy): `BRouterEngine`,
  `Rd5Parts`, `Planner`, `WaypointInsertion`, `PlanEdits`, `RouteSummary`, `RouteTimes`, `PaceModel`, `Geo`,
  turn/junction helpers + the BRouter jars of `app/libs`. TeaVM 0.16 → one Wasm GC module. `WebApi.kt` is the
  only API (init, putLayout/putPart for rd5 1° parts, route, plan, insertIndex). Web-only stand-ins: `Problems`,
  `UserText` (routing part), and three tiny class replacements: single-thread `ReentrantLock`,
  `java.lang.Package` manifest getters (BRouter's GPX header version), no-op BRouter debug `StackSampler`.
- `web/app/` – Vite + TypeScript + maplibre-gl 6 + pmtiles. Engine in a Web Worker; fetches profiles and the rd5
  parts the plan needs (part choice = the phone's `Rd5Parts.forCorridor`) by HTTP range (falls back to a whole file
  on hosts without ranges). Planner page: click → point (WaypointInsertion), drag / click to move / delete,
  activity + T level, route (failed legs dashed), km / ↑ / ↓ / time / difficulty, profile drawn like the app's (route colour fading, line coloured by
  `RouteSummary.steepness` – variant A, owner 2026-11-12). Map = the app's format-2 style, fonts and sprite unchanged.
- Tests: `web/test/cases.json` (22 cases: Bozen–Meran × 6 activities, Algund, Meran 2000 T2/T3/T5, via Terlan,
  Ultental loop, Lana–Ulten across 11°E, ferry, 3 error cases, TU Dortmund by Bike – BRouter alone and as the plan
  sheet plans it). A case with `"plan": true` runs the app's Planner (`Engine.plan`) instead of one BRouter request;
  `"visits": "lat,lon"` = the line must pass that stop within 15 m (Bug 01, core feature C3: a stop on a way is always
  visited – TU Dortmund 2599 m without the stop pass, 2691 m with it, the line 4 m from stop 3; needs E5_N50.rd5).
  `./gradlew -p web/engine runJvm` (same Engine on the JVM, full rd5) vs `web/app/test/browser-compare.mjs`
  (headless Chromium, partial rd5 by range).
  An island point (Köln Dom → Neumarkt, `tools/routing/jvm-test/IslandTest.kt`) routes like in the app since main's
  BRouter jars `1.7.11-beta-onpath1` (`islandNodePairs` package-private, `OnPathHints.islandRetries` / `skipOnSnap`,
  no reflection – "Lessons" below).
- **Plan parity app ⇄ web** (owner 2026-10-31: routes and plans identical, shared Kotlin, parity tests – no checks by
  hand): `web/test/plans.json` – each case planned by the app's own path on the JVM (`./gradlew -p web/engine
  parityJvm`: `BRouterEngine` on the full rd5, `PlanPoints` for edits, `PlanResult.plan` with the app's default pace =
  `AppViewModel.recalc`) and by the page's calls in the Wasm engine (`web/app/test/plan-parity.mjs`: `edit` + `plan`, rd5
  in parts like the browser). Same line and plan points (1 m), distance (1 m), time (1 s), climb, gaps, warnings,
  difficulty, profile – a gate in the jvm job; a case with `"knownDiff"` is listed, not gated (Köln island point).
  2026-10-31 evening: all 12 the same – Köln Dom 1334 m (island retry without reflection), Hilden short trip 1099 m
  (the Planner's midpoint retry, "Lessons" below); no knownDiff left.
  Since `Engine.plan` calls `routing/PlanResult` (one plan computation for both) the page also takes the snapped plan
  points (`snap-smoke.mjs`), exports the app's plan GPX (`PlanGpx`, `<wpt>` stops, re-import exact) and deletes / drags
  with `PlanPoints.deleteAt` / `move` (a round trip stays closed).
  `web/app/test/ui-smoke.mjs` clicks two points. `.github/workflows/web.yml` runs all (§9).

**Numbers** (spike, cloud container, 2026-10-23):

| | |
|---|---|
| Same result browser ⇄ JVM | **20/20** – same line (max deviation 0.00 m, same point count), length, ascent, descent, time, error texts |
| Engine size | Wasm 1.52 MB, **~0.48 MB gzip**; TeaVM runtime JS 17 KB |
| Page (maplibre + UI) | 1.08 MB JS, 294 KB gzip |
| Engine start | ~130–160 ms (compile + profiles) |
| Route time | like the JVM: 30 km bike 0.2–0.9 s, hikes 30–50 ms |
| Routing data | per 1° part 5–13 MB, once per area (Meran part 9.7 MB, ~0.15 s locally) |

**Findings that still hold**
1. brouter.de sends no CORS header → the host passes `/segments/` through (§9).
2. Small pure helpers that live in Android / Compose files are mirrored in TS and guarded by `web/parity.json` (§14.2);
   move them to pure files when touched.

## 8. Phase 1 – plan per QR code and link (owner decision 2026-10-26)

**Phase 1** – plan in the browser, bring the plan to the phone by QR code, share it as a link (built; app part =
core feature C15). **Phase 2** – pair phone and browser once (QR, like WhatsApp Web); then plans sync both ways,
end-to-end encrypted through a small mailbox, no login (§12, `docs/design/web-sync.md`).

### Plan code
`app/src/main/java/com/onpath/app/routing/PlanCode.kt` – `SharedPlan` (points, activity, SAC, pinned points, names,
title) ⇄ compact URL-safe text. Pure Kotlin (SharedCoreRuleTest), no platform Base64; the web engine compiles the same
file to Wasm, so app and browser can never disagree. Tests: `PlanCodeTest` (14, incl. a frozen version-1 code that the
browser test also checks). The app's Gradle build needs an Android SDK; `./gradlew -p web/engine test` runs the same
test on the plain JVM (web CI does).

- **Link:** `<planner page>#p=<code>` – the plan sits after "#", so it never reaches a server. `PlanCode.parse`
  accepts the whole link, `p=<code>` or the bare code; anything else → null (never throws).
- **Format v1:** version, activity (fixed table, not the enum order), SAC; varint point count; lat/lon in 1e-5° as
  zigzag varint deltas; pinned bitset; optional tagged fields (1 title, 2 point name) – unknown tags are skipped, so
  newer codes still open in older apps. Limits 500 points, 100 chars per name. 20 points ≈ 110 characters.

### Web
- "Send to phone · Share" (from 2 points): dialog with QR code of the link (`qrcode-generator`, MIT), Copy link,
  system Share where the browser has it.
- The address bar always holds the current plan (`#p=…`, `history.replaceState`): reload / bookmark keep it.
- Opening a link loads points, activity, T level, names and pinned points (pinned → passed exactly, the app's
  `Planner(pinned)`); a broken link says so in plain words.
- Test: `web/app/test/share-smoke.mjs` (CI) – opens the frozen code, checks the browser writes the identical code back,
  the QR dialog, edit + reload, broken link. The QR image was decoded once with jsQR: it holds the link.

### App part
Plan links (`#p=`) open as the plan in the app (core feature C15, App Links via `/.well-known/assetlinks.json` on the
planner host, §9); the phone's own camera reads the QR code; "Share plan" in the app writes the same link
(`PlanCode.link`).

## 9. Host and CI (cloud, 2026-10-26)

Owner 2026-10-26: own domain **onpath.earth** (bought at all-inkl, DNS at Cloudflare; .earth is not sold by
Cloudflare). Proposed: `onpath.earth` home + help (own repo happychriss/onpath.earth, marketing), `plan.onpath.earth` the planner (owner 2026-10-27: working assumption, to be confirmed), later `maps.onpath.earth`.
The planner host name is also the App Link host (§8, A-005).

**Map from cells** (`web/app/src/cells.ts`): the page reads the map server's format-2 cells in place – a cell is one
z10 tile holding only its own z ≥ 10 tiles, so a maplibre protocol `cell://<layer>/{z}/{x}/{y}` sends every tile to the
archive of its z10 parent (`cells/f2/10/<x>/<y>/current.json` → version folder). Nothing is merged (the phone joins
cells into one file per layer, map-cells.md §19). Missing tiles: empty vector tile, flat Terrarium tile for terrain.
POIs: the cells in view are merged into the style's one geojson source. Below z10 the page shows nothing yet (the app
has its bundled overview map there). `?map=folder` keeps the old single-folder mode (packed preview).

**Host** (`web/host/`, a Cloudflare Worker with static assets): one hostname for everything, so no CORS and no app key
in the browser.
- `/data/<key>` → the maps R2 bucket, read only, cell files only – the doorman's own `serveFile` (Range, ETag, cache
  rules), imported, not copied.
- `/segments/<rd5>` → brouter.de passed through with Range (brouter.de has no CORS header; the app also downloads from
  there). Only rd5 file names go upstream.
- `/.well-known/assetlinks.json` → Android App Links for `com.onpath.app` (A-016): 200, `application/json`, no
  redirect, no login, so plan links (`#p=`) and pairing links (`#pair=`) open OnPath. A Play Store signing key later
  becomes a second fingerprint in the same list (`ASSET_LINKS` in `web/host/src/index.js`).
- everything else → the built page.
- `workers_dev` and preview URLs off: the only address is the custom domain (Cloudflare Access guarded it during the
  test; off since the planner went public, 18.5). Tests: `web/host/test` (CI); `wrangler deploy --dry-run` bundles 4 KiB.

**Owner setup, once (done; kept for a rebuild):**
1. onpath.earth active in Cloudflare (nameservers at all-inkl → Cloudflare).
2. Zero Trust → Access → Applications → Add → *Self-hosted*: domain `plan.onpath.earth`; policy *Allow*, include the
   testers' e-mail addresses; login method *One-time PIN*. (First visit: choose a team name and the Free plan.)
3. API token (My Profile → API Tokens → Create → Custom): Account · Workers Scripts · Edit; Zone onpath.earth ·
   Workers Routes · Edit and DNS · Edit. In GitHub: secret `CLOUDFLARE_PLANNER_TOKEN`, variable
   `PLANNER_HOST_TEST` = `plan.onpath.earth` (`CLOUDFLARE_ACCOUNT_ID` exists).
4. Actions → Web planner → Run workflow (branch main) → tick *deploy* (also done by every app release, release.yml job "planner").
5. **Map areas on demand (§17), once:** GitHub → Settings → Secrets and variables → Actions → *New repository secret*
   `ONPATH_MAP_KEY_TEST` = the newest entry of the environment "test" secret `ONPATH_APP_KEYS` (the part after the last
   comma; the same key the test APKs carry – `onpath.mapKey.test` in `~/.gradle/gradle.properties`). The web deploy has
   no environment, so it cannot read the environment secret itself. The next green push puts it on the host
   (`wrangler secret put MAP_KEY`, never printed). Until then the page says "The detailed map for this area is not
   available on the web yet – the overview map is shown." and asks nothing. Rotation: update this secret together with
   `ONPATH_APP_KEYS` (the proxy answers "not available" while the doorman refuses an old key – logged in the Worker).
6. **Live check after each deploy (optional, proposal):** Zero Trust → Access → Service credentials → *Create service
   token* (name `github-planner-smoke`, duration 1 year; copy Client ID + Secret – shown once). Access → Applications →
   the `plan.onpath.earth` app → Policies → *Add a policy*: action **Service Auth**, include *Service Token* =
   `github-planner-smoke`. GitHub: repository secrets `CF_ACCESS_CLIENT_ID` and `CF_ACCESS_CLIENT_SECRET`. From then on
   the step "Live smoke test of the deployed planner" (`web/host/live-smoke.mjs`) reads the page, the engine, the
   overview, the style, a Meran cell, rd5 bytes by Range and the map-area proxy's status on the live site after every
   deploy (read-only; red when one fails). Without the secrets the step only notes that it was skipped. The token only
   opens this Access app; delete it in Zero Trust to revoke.

**CI** (`.github/workflows/web.yml` – "Web planner", on `main` since 2026-11-06, owner 2026-10-30: faster, no test dropped, every test a gate before the
deploy). Jobs: `web-build` (parity check, TeaVM engine + overview style, texts check, the page with the overview files →
artifact `web-build` = `web/app/dist` + the engine files) ∥ `jvm` (the app's tests of the shared files +
`WebImportTest`, JVM routes, the three Wasm = JVM Node checks, host tests, browser compare) ∥ `smokes` (a matrix of 5
machines – `cells`, `sheet-sync`, `areas-parity`, `lang-gpx`, `short` – balanced by the smokes' times, each smoke its
own step with its own log). `jvm` and `smokes` have no `needs`: they set up (Gradle tests, Chromium, routing data)
while `web-build` runs, then wait for it through the API (`actions: read`) before downloading `web-build` → `deploy` (`needs` all of them, only on `inputs.deploy` – a release or a dispatch, never a plain push) and
`results` (merges every job's `results-*` into the one artifact `web-planner-results`). A new smoke goes into the
shortest group. **Engine cache:** the TeaVM step is skipped when its key matches – `hashFiles` of `web/engine/**`,
`app/src/main/java/**`, `app/src/test/java/com/onpath/app/**`, `app/libs/**`, `app/src/main/assets/**`,
`tools/routing/jvm-test/**`, `strings/**`, `web/strings/**`, the Gradle wrapper / root Gradle files and the workflow
itself (deliberately wider than `appFiles`; a miss only costs ~2 min). The Gradle tests and JVM routes are not cached:
they run in `jvm` on every run.
**Agents:** locally run only the smokes your change touches; CI runs all.

Requesting new cells from the web: §17 (the host holds the key, the browser does not).

## 10. Plan edits and search with the app's code (cloud, 2026-10-26)

Owner: "start here, reverse, round trip – can we benefit from the same code?" and "add search, same as on mobile".

**Plan edits:** Reverse, Round trip and the point menu (Start here / round trip "Start & finish here" / Delete) run
the app's `PlanEdits` in the engine; the Points list is the app's `PlanPoints.rows` (names, leg distances along the
route, a round trip once with ↺). Only the menu rule itself (`ui/MapGestures.pointMenu`, 6 lines) is mirrored in TS –
it sits in a file that imports the GPS tracker; move it to a pure file when the module split comes (finding 3 again).
Test: `web/app/test/edit-smoke.mjs` (CI), incl. "switching Hike → Bike plans again".

**Search:** the app's `OfflineIndex` (POIs from pois.geojson, trail names from overlays.pmtiles, peaks and places from
base.pmtiles via `BaseMapPlaces`), `SearchRanking`, `PhotonParser`, `OnlineRanking`, `SearchMerge` and `AddPreview`
compiled into the engine (`WebSearch.kt`); the page indexes the cells in view (≤ 4, nearest first) the first time
someone types. Online: Photon after 350 ms for 3+ letters, the same URL as the app (`PhotonProvider.url`: position
rounded to ~1 km), mixed in below the rows already shown. Place card: "Add to route" (where it goes, said before the
click), "As start", "As destination"; a found place becomes a pinned, named stop (and travels in the plan link).
Not yet: offline house numbers (the app's `addresses.sqlite` would need SQLite in the browser), recents, categories.
Photon is komoot's public instance (fair use) – a public planner with many users should count requests or host its own.

What TeaVM lacked, and the web stand-ins (only in the engine build, the app is unchanged):
- `java.text.Normalizer` → NFD table for the accented Latin letters (generated from the Unicode data).
- TeaVM's regex lists the digit category as "N" instead of "IsN", so `\p{N}` failed → its `TAbstractCharClass`
  shadowed with that one line fixed (worth reporting upstream).
- Gson's `Streams` (its tree reader pulls in classes the browser lacks) → an equivalent small reader;
  `AtomicIntegerArray`; MapLibre `Feature`/`Point` and Android SQLite as empty shells for code the web never calls.

Test: `web/app/test/search-smoke.mjs` (local – needs a map cell; `ONLINE=1` also checks Photon).

## 11. Phone parity and speed (cloud, 2026-10-27)

Owner: "feature parity between mobile and browser – the eye, page orientation, do and undo; add to route / as start,
open Google Maps; maybe the page structure is similar too. It seems to take some time to route – or is this the Claude
environment?"

### 11.1 Inventory – phone Plan screen vs web

| Feature | Phone (source) | Web now | How |
| --- | --- | --- | --- |
| Search pill + round ☰ | `ui/PlanTopBar` | same; header / ☰ = My routes (+ pairing), Import GPX, Help & credits; Share = the sheet's icon | page (My routes, recording, settings, updates are app-only) |
| Search, place card (Add to route / Remove, As start, As destination, Google) | `ui/search/*`, `search/AddPreview` | same | app code (§10) + `PoiLinks` |
| Map controls group: compass, eye, undo, redo, locate + GPS dot | `ui/MapFooter.MapControls` | same order and rules (eye only with a route, undo / redo only when possible) | page; undo / redo = app's `PlanHistory` |
| "Page orientation" = compass: shows the map's bearing, tap → north up | `CompassControl`, `AppViewModel.onCompass` | same; the map turns with right mouse / two fingers, no tilt | page; heading-up skipped (§11.4) |
| Eye: hold → route + markers hidden | `EyeControl` | same (pointer / Space held) | page |
| Hold rules: first hold "New route: Start here · Go here"; hold map / line → point (smart insertion); POI → "Add to route · Open in Google Maps" (empty plan: Start here · Go here); point → point menu | `ui/MapGestures`, `ui/MapOverlays` | a **click** (or right-click / long press) is the hold; same menus | app's `MapGestures.decide` / `pointMenu` / `fromMeBlocked` in the engine; menu texts mirrored |
| Go here (from my location) | GPS | browser location (asks on first use) | page |
| Point menu: Start here ("Removes N points before it"), Start & finish here, Open in Google Maps (named), Delete | `PointMenu` | same | app rule, texts mirrored |
| Google Maps link-out | `poi/GoogleMapsLink.PoiLinks` (app: geo: intent, else web URL) | web URL in a new tab, nothing drawn or fetched | app code |
| Plan sheet: activity chip, time + distance, ⋯, climb + max, difficulty badge (raised by technique), profile, Points (≡ drag, ✕, tap → map, Reverse, Round trip), one primary button | `ui/PlanPanel`, `PointsSection`, `StepSheet` | same; no big primary button (owner 2026-10-30): Share = icon + text next to the chevron, its dialog also has Export GPX; no ⋯ (row 35): ✕ Close · ⇅ Reverse · ↻ Round trip under the summary; Save in the header (row 22) | texts from the app's `Format`, `RouteSummary.rating`, `PlanPoints`, `PlanEdits.move` |
| Route warnings: amber ⚠ line → "On this route" → tap shows the spot | `ui/RouteWarnings` | same | app code |
| Activity picker (six activities, line under each, T1–T5 with SAC text, Done) | `ActivityPicker`, `Screens.SacScale` | same | names from the app's `Activity`; SAC texts mirrored |
| Export GPX | `GpxWriter`, `SportType` | download | app code |
| Map credits: short "© OpenStreetMap", tap → full credits | `MapFooter`, `MAP_CREDITS` | same | page |
| Sheet steps small / profile / full | `PlanPanel.PlanSize`, `StepSheet` | the same three steps on the side panel and the bottom sheet (§11.3; handle, chevron, summary click, arrow keys; remembered) | page (`sheetstep.ts`) |
| Undo keys | – | Ctrl+Z, Ctrl+Shift+Z / Ctrl+Y | web extra |
| Drag a marker | no (hold-only, owner) | yes (mouse habit) – becomes an unpinned point, one undo step | web extra, see 11.5 |
| Navigate, recording, saved routes, offline maps, settings, haptics, Route details screen, time details | app | – | not a planner's job / later |

New engine exports: `historyRecord/Step/State`, `gesture`, `pointMenu`, `fromMeBlocked`, `googleMaps`, `poiMenu`, `gpx`,
`activities`; the plan answer carries `texts`, `because`, `warning`, `spots`. Compiled in place: `ui/PlanState.kt`,
`ui/MapGestures.kt`, `ui/RouteWarnings.kt`, `poi/GoogleMapsLink.kt`, `gpx/GpxModel.kt`, `gpx/GpxWriter.kt`,
`gpx/SportType.kt`, `data/LastActivity.kt`. Shells (web build only): `recording/GpsStatus` (the enum lives in the
Android file `LocationTracker.kt`), `android.content.Intent`/`ActivityNotFoundException`, `android.net.Uri`,
`android.app.Activity`, and a Context with `startActivity` (replaces the JVM test stub's in this build). The app's
`PlanStateTest`, `MapGesturesTest`, `RouteWarningsTest` now run in the web CI. Mirrored in TS (Android files):
`MapOverlays.stopLabel` and menu texts, `Screens.SacScale`, `PlanStatus.noRouteLine` ("Hold" → "Click").
Test: `web/app/test/parity-smoke.mjs` (CI, no map data needed).

### 11.2 Speed – where the time goes

`web/app/test/perf.mjs` (headless Chromium, swiftshader; Meran rd5 for the normal build, the Hilden preview folder for
the packed build; `NET=slow` = 50 Mbit/s, 40 ms). Times in ms; "download" includes waiting for a part another request
is already fetching.

| Step | normal, localhost | normal, 50 Mbit/s | packed (preview), localhost | packed, 50 Mbit/s |
| --- | --- | --- | --- | --- |
| page → engine ready (2.2 MB Wasm + profiles) | 1000–1350 | 1450–1750 | 680 (3600 before) | 3000 |
| first plan, 2 points – before the fixes | 700 (download 113, store 47, route 529) | 2260 (download 1920) | 5925 (download 4880, store 404) | 15 400 (download 14 700) |
| first plan – after the fixes, plan at once | 1100 (prefetch running) | 2370 | 2390 | 12 800 |
| first plan – after the fixes, user looks at the map first | – | **290** (6 s look) | – | **540** (15 s look) |
| add a via point | 150–230 | 120–170 | 260 | 180 |
| same plan again (cache) | 3–9 | 3–7 | 3–5 | 5 |
| switch Hike → Bike | 235–310 | 250–310 | 240–490 | 200–500 |
| a small call (Points rows) through the worker | 0.3–1.1 | 0.3–0.7 | 0.4–1.2 | 0.4–1.2 |

Routing itself is as fast as on the JVM (browser-compare, 20 routes: e.g. Bozen → Meran 33 km 380–1000 ms in the
browser vs 400–1500 ms JVM, short hikes 35–55 ms vs 22–43 ms). The data is the cost: the first plan in an area needs its
1° rd5 part (Meran 9.7 MB; 2 s at 50 Mbit/s – the phone downloads the same part once and keeps it). **The preview host
adds most of the wait**: it serves no ranges and only text, so the whole 25 MB rd5 comes as 33 MB of base64 (plus
~50 MB of base64 map files the page loads at the same time), and is decoded in the browser. A real host (R2 / the
planner Worker, ranges) fetches only the parts – 9.7 MB here.

Fixed (cheap wins):
- **Engine loaded twice** – two early calls each loaded their own Wasm engine; on a slow line parts stored in one were
  missing in the other ("part does not fit its index", planning failed). The worker keeps the loading promise.
- **Prefetch**: from zoom 9 the page fetches the routing data under the map centre 0.8 s after the map stops, so the
  first plan usually finds it in place (290 / 540 ms above).
- **One rd5 write per plan** instead of one per part, and the engine (with its route cache) is kept: BRouter opens the
  segment files for every route anyway, and a cached leg had all the parts its corridor needs. Store time 400 → 25–110 ms.
- Each part downloads once, also when a prefetch and a plan ask together.
- Preview: base64 pieces fetched 4 at a time; `Uint8Array.fromBase64` where the browser has it.

Still real: the first plan in a new area waits for its part (2–10 MB); BRouter needs 0.1–1 s for long bike routes
(same on the phone). Next, if needed: prefetch parts along the line while points are placed; keep parts in the
browser's cache storage (IndexedDB) across visits.

### 11.3 Page structure (web layout since 2026-10-28, §13)

- **Header line** (56 px; onpath.earth's look: logo, Manrope, site colours): "OnPath Planner" / "OnPath Planer" (one product name, row 21), then the entries
  My routes · the phone entry (always there, row 25: "Pair phone" / "Handy verbinden", or phone + green dot + "Paired" / "Verbunden") · Import GPX · Help & credits · The app (row 29: the phone entry opens the Phone window, its dot green / amber; Save route (row 28, A-017) also in ⋯ and My routes; one list, `NAV` in `main.ts` – new entries go there), right
  "No account · routes stay in this browser". Phone width: 48 px, the entries behind ☰. No Send to phone entry (owner 2026-10-30): sharing is the sheet's Share icon.
- **Top bar = one unit** (shared background and shadow): the search field (flexible width), then the map tools
  separated by thin dividers – undo, redo | eye, compass, locate + GPS dot | Map content ▾ (always there, greyed when
  not possible). Phone width: the search across the top and the tools as a row directly below it in the same bar.
- **Type**: Manrope (OFL), bundled from this host (`web/app/src/fonts/`, woff2, latin, 400/500/600/700, licence text
  beside the files, named in Help & credits) – no font-service requests.
- **Wide screen (> 700 px)**: the top bar top left (up to 860 px), the plan sheet below it as a side
  panel (384 px; three steps, see below; a long Points list scrolls inside), zoom ±
  bottom right, scale + "© OpenStreetMap" bottom left beside the panel. The place card is a callout at the picked point (row 19 in §13).
- **Phone width**: the top bar across the top (search, tools below), the plan sheet as a bottom sheet (the same three steps); the place card is a bottom card that stands in for the
  sheet while it is open.
- **Plan sheet steps** (owner 2026-10-30, option A – the app's `PlanSize` MINI / COMPACT / FULL, one step model for
  both widths, `sheetstep.ts`):
  - *small* – the head: activity chip · Share icon · chevron in one line, then time + distance (never wrapped),
    then ↑/↓ + difficulty badge, then the action row ✕ Close · ⇅ Reverse · ↻ Round trip (the app's `PlanActions`); the route's red / amber / grey lines stay visible here, as in the app (they sit above
    its MINI step end), so a new error line never needs a step change. About 150 px tall.
  - *profile* – + the elevation profile and "Saved as …".
  - *points* – + the Points list (the full sheet; on a phone it takes most of the height).
  - Controls: the grab handle on top (the app's handle tap: one step bigger, from Points back to profile), the chevron
    next to Share ("Mehr anzeigen" until Points, then "Weniger anzeigen" → small; ⌄ / ⌃ point to where the sheet grows:
    down for the side panel, up for the bottom sheet), a click on the summary (= handle), Arrow up / down on handle or
    chevron. Remembered in `localStorage` `onpath.sheetStep` (try / catch); nothing remembered → side panel *points*
    (as before), bottom sheet *profile* (the app's default), following the width until a step is chosen. A Points row
    tapped on a phone → *profile* (the app's `showPointFromList`).
  - Unchanged in every step: the place card (at its point / instead of the bottom sheet), the GPX track panel (takes
    the sheet's place completely; Close track brings the step back), the long-route question (a dialog), the status
    line's waits and errors (plain info only from *profile*; the developer timing "Planned in … ms" only with `?debug`).
  - Map fit (`fitPadding`): phone → bottom = sheet (or place card) height; wide → the room beside the panel, or below
    it when that is larger (a short *small* panel).
- **No big main button** (owner 2026-10-30): Share is a small icon + text button next to the chevron (always there, greyed until two points – row 20 in §13); it opens the
  dialog (QR code with "Scan with the OnPath app" above it, Copy link, system share, Export GPX of the shown plan – row 35 –, the one app line below).
- **Pairing lives in the Phone window** (§13 row 29; not in My routes): the header's phone entry (☰ on phones) opens it –
  the state with its dot and one sentence, "How it works", privacy, Pair phone or Sync now · Show code again · Unpair,
  Close. `#pair=` links open the join dialog as before.
- Menus: point / POI / first-point menus are map popups at the point; ⋯ is a drop-down; the activity picker is a
  drop-down at the chip; Map content is a large panel; share, save and credits are dialogs. Colours and type follow
  `OnPathColors` (green primary, route blue, amber warnings, red errors); icons are the app's Material icons.

### 11.4 Still different from the phone, and why

- **A click is the hold.** A browser has no hold-to-arm ring or vibration; a quick click adding a point is what web map
  users expect. Right-click / long press do the same. Markers can also be dragged (desktop habit); the phone is
  hold-only by owner decision.
- **No heading-up compass mode**: there is no walking direction while planning at a desk, and the browser compass needs
  a permission prompt on iOS. North up / turned only.
- No profile scrubbing dot; no Route details (way types / surfaces) screen
  yet – the data is in the plan answer (`wayTypes`, `surfaces`), the screen is Compose.
- No saved routes / My routes, recording, Navigate, offline maps, settings – the web planner hands the plan to the
  phone (QR / link).
- Offline house numbers, recents and categories in search (§10).

### 11.5 Open decisions for the owner

1. Click = hold (current) or require a long press / right-click to add points, like the phone?
2. Keep marker dragging on the web (current) or hold-only as on the phone?
3. Share (icon next to ⋯, owner 2026-10-30) – or also offer "Open in the app" on phones (needs the app's link
   handling, §8 app part)?
4. Prefetch: the 1° part under the map centre from zoom 9 (2–10 MB per area) – fine for a public page, or only after
   the first click?

## 12. Phase 2 – plan sync phone ⇄ browser (cloud, 2026-10-27)

Pair once (the browser shows a QR code, the app scans it), then saved plans sync both ways by themselves, end-to-end
encrypted (AES-256-GCM; key from the pairing secret, which never reaches a server) through a small mailbox in this
host (`/sync/v1/…`, D1). Newer wins, the older stays as a copy; unpair on both sides. The shared Kotlin
(`app/.../sync/`, branch `feat/plan-sync`) runs in the app and – in the engine – in the browser; the page has
My routes (planned routes only) and the Phone window from the header's phone entry (§13 rows 28–29). Everything – threat model, formats, API, limits, the owner's Cloudflare steps
(D1, Access bypass for `/sync/*`), the app-side task list and the privacy text – is in **`docs/design/web-sync.md`**.

## 13. Web layout (cloud, 2026-10-28) – owner feedback on the live test page

| # | Owner | Now |
| --- | --- | --- |
| 1 | Place card stayed over the sheet after "As start" | Card beside the sheet (phone: bottom card in place of it); every action and every new search closes it. Cause of the "stays": a late online search answer re-opened the results list – a pick now cancels it. |
| 2 | Map buttons bottom right hard to find | Toolbar right of the search pill (komoot-like), Map content ▾ in it; zoom ± bottom right. |
| 3 | Activity picker = modal with Done | Drop-down at the chip; a click picks and closes; Hike's T1–T5 row (with the SAC line) in the same list. |
| 6 | Header like komoot's, onpath.earth's design | Header line, §11.3. |
| 7 | Save made duplicates, dialog again and again, "planer.1" | The app's flow (AppViewModel.requestSave, RouteRules.saveMode): a plan shown from My plans asks **Save** (new version, name kept) / **Save as new…**; else the name dialog with the app's proposal (`RouteNamer`, e.g. "Vellau → Ifinger Hütte"); saving closes it, the sheet says "Saved as …" (+ "changed since"). My plans keeps the list + "Save the current plan…". |
| 8 | Direction arrows as on the phone | `planned-route-arrows`: the app's chevron and spacing / size (MapController.addDirectionArrows). |
| 9 | Drag & drop in Points | Rows move live (≡ lifts the row, the others make room – the app's PlanPoints.dropIndex / shift), touch and mouse, auto-scroll at the edges, Arrow up / down on ≡; long lists scroll inside the sheet (the app's sheet scrolls too – no "show all" folding there). |
| 11 | POI choice using the screen | Toolbar → Map content: large panel, groups with tri-state boxes, Show all / Hide all / Reset, a filter box, contour lines + hill shading; live on the `poi-*` layers with the app's `PoiKinds` defaults and `PoiFilter` (engine), names from the app's strings; kept in localStorage. |

Engine calls added (`WebUi.kt`): `routeName`, `poiKinds`, `poiFilter`. Tests: parity-smoke (toolbar place, arrows layer,
live drag + undo, activity drop-down, Map content filter + storage, Save / Save again without duplicate, ☰ on phones),
search-smoke (card beside the sheet, closes, results stay closed, name proposal), sync-smoke (save via My plans,
"Saved as"); `test/ui-shots.mjs` takes desktop + phone screenshots for a look (nothing checked).

Follow-up (owner, same day): Manrope bundled (§11.3), search field and tools merged into one top bar. Colours from
onpath.earth: page background `#fafaf6`, text `#243c31`, line `#dce1d6`, soft background `#eef0e8`, eyebrow / icon
green `#526d57`, button green `#245f46`, logo green `#1F6E43` + path yellow `#FFC94D` – used for the header; the
map, sheet and controls keep the app's `OnPathColors` (green `#1F6E43`, route blue, amber, red). Since 2026-10-30 the
page has one green, the app's (row 13b below).

Owner decisions 2026-10-29 (cloud, 2026-10-30):

| # | Owner | Now |
| --- | --- | --- |
| 13b | One green: the app's `#1F6E43` | Every green of the page is the app's: header eyebrow and header icons (were onpath.earth's `#526d57`; its button green `#245f46` was never used on the page), buttons, chip and menu icons, the Easy badge. Fills use `--green` (`#1F6E43`) in light and dark; green *text / icons* use `--green-fg` = the same green in light, in dark mode its light tone `#8ED6A8` (the app's `inversePrimary`) – `#1F6E43` on the dark background is only 2.7:1. Contrast (WCAG AA): white on the green button 6.2:1; green text on the header 6.0, on white 6.2, Easy badge 5.2; dark 6.7–10. The website's neutrals (`--site-bg/fg/line/soft`) stay. Test: ui-smoke (the app's green in light; ≥ 4.5:1 text, ≥ 3:1 icons in light and dark). |
| 14 | Pace choice like the app's Settings → Pace | The activity drop-down ends with **Your pace**: the five levels of the current activity's group (Hiking, or "Cycling · <bike>" – the app keeps one level for hiking and one for cycling) with the app's labels and "about 4 km/h · 300 m up per hour" line and intro. A click sets it (the list stays open); the sheet's time is recomputed by the engine (`planTime`: the app's `TimeEstimate.planSeconds` with `planFactors` of the chosen level, no new routing); new plans and an imported track's model time use it too (`WebPace.kt`). Remembered in localStorage under the app's Prefs key names (`onpath.hikePaceLevel` / `onpath.bikePaceLevel`). The long-route question keeps Average, as in the app (`LongRoute`). Tests: browser-compare (every level, Hike / bike / e-bike, Wasm = JVM `JvmRun.paceRun` exactly), parity-smoke (section, time follows, stays open, remembered after reload). |
| 15 | Region packages for zoom 7–9 (map-cells.md §20) | Read like cells from the maps bucket through `/data/` (same R2 layout: `cells/f2/7/<x>/<y>/current.json` + `<version>/{base,overlays,hillshade}.pmtiles`; the host's key pattern `cells/…` already covers it – host test added). `cells.ts`: a tile z7–9 comes from the package of its z7 parent (base, overlays = long-distance routes, hillshade = the style source `region-shade`), z10+ from the cells as before; a missing / broken package file is left out (warned once). Style: the app merges per installed packages (`Overview.merge(packages = boxes)`, StyleLoader reloads); its structure does not depend on *which* packages, only the `within` geometry of the overview's labels from z7 does – so the build also writes `onpath-overview-packages.json` (the app's merge with a placeholder box in the South Pacific) and the page sets the boxes of the packages it finds into that filter (`overview.ts followPackages`, mirror of `Overview.multiPolygon`). Without any package it draws like the plain overview style (the detailed layers from z7 find no tiles). The page never asks for a package to be built (the app does, `MapCells.ensurePackages`) – it shows the ones that exist. Test: overview-smoke with a fake package (`test/fake-package.mjs`: a solid-magenta hillshade.pmtiles for z7 tile 68/44, Munich): its relief covers the overview at z8, the labels' filter holds its box, no overview label inside it; Meran (no package) keeps the overview and its labels. |

Owner decisions 2026-10-30 (cloud, same day):

| # | Owner | Now |
| --- | --- | --- |
| 16 | Plan sheet collapsible on wide screens too, three steps like the app (option A) | §11.3 "Plan sheet steps": small / profile / points on both widths, grab handle + chevron + summary click + arrow keys, remembered (`onpath.sheetStep`), map fit follows the panel. Test: `test/sheet-smoke.mjs` (CI) – three steps on desktop (1440×900) and phone (390×844), every control, reload, fit, place card, GPX track panel. |
| 17 | "Send to phone" and "Pair phone" side by side confuse | Header: My routes · Import GPX · Help & credits · The app. Share = icon next to ⋯ (no big green button; greyed on an empty plan since row 20), dialog "Scan with the OnPath app" above the QR code. Pairing = section "Pair with phone" in My routes; a phone with a green dot beside My routes while paired. Tests: sheet-smoke, sync-smoke (pair / unpair through My routes, header status), share-smoke, lang-smoke. |
| 18 | "Route planen" wrapped beside the chip; "Planned in … ms · routing data …" is a developer line | The head puts chip and icons on one line and time / title on its own line (never wrapped); the timing shows only with `?debug`. Test: sheet-smoke. |
| 19 | Place card "at the point where I add it, like the app" (after an online pick it floated beside the sheet, the ring far away, the result not centred) | Wide: the card is a callout at the picked point with a small tail (above; below / right / left when there is no room – never over the sheet, top bar, area strip, credits or zoom ±), moving with the map (`cardanchor.ts`, laid out on every `move`). Opening flies so point + card sit in the middle of the free room (`fitPadding`), not keepFollow (ends position following). Phone: the bottom card as before, the point centred in the map above it. ✕, Escape and a map click beside it close it (the click adds nothing). **Why it was not centred (reproduced, the likely cause of the screenshot):** maplibre keeps the `padding` of an `easeTo` for every later move – after a route-warning spot or a plan-gap "Show" (both `easeTo({ padding })`) the next search `flyTo({ center })` put the result in the middle of that old padding (right of / below the middle, ~1100 px across on a 1866 px window). Those moves now pass an `offset` with padding 0 (`roomFor`), the pick resets it too. The map menus (first point, point, POI) were already popups at their point; they now show their tail too. Tests: sheet-smoke (tail ≤ 3 px across and ≤ 16 px from the point, inside the window, clear of sheet and top bar, follows a pan, flips under the top bar, map click / Escape close, a far result after a left-over padding lands in the free room; phone: point above the card), follow-smoke (a real pick ends following). |
| 20 | Share icon missing on an empty route; Share with its word | A small icon + "Share" / "Teilen" button next to ⋯, always shown; greyed with "Set a start and destination first" / "Erst Start und Ziel setzen" (tooltip + label) until two points. Test: sheet-smoke. |
| 21 | Logo "OnPath Planer" as one product name, not "OnPath" big + "PLANER" small caps | `.wordmark`: same Manrope size as "OnPath", "Planner" / "Planer" in the app's green, weight 600 (OnPath 700); shown on every width. Test: sheet-smoke (text, one size, no capitals). |
| 22 | "The floating window is planning, the header is managing": Save in the header | *Superseded by row 28 (owner 2026-10-30): Save is out of the header again.* |
| 23 | Every header icon with its text | The paired status: phone + green dot + "Paired" / "Verbunden" (tooltip "Paired with your phone · last synced …"). Narrower than 1180 px (and 701–860 px as before) only the icons stay, each with its title; phones: the ☰ list with icon + text. Test: sheet-smoke (labels at 1440 px, icons only at 1000 px). |
| 24 | Pointing hand over clickable map things | `mousemove` → one `queryRenderedFeatures` per frame on the `poi-*` layers and the route line (a click there opens the POI menu / puts a point into that leg); markers have their own pointer; plain map keeps maplibre's grab cursor. Test: parity-smoke (a station POI, the route line, the plain map). |
| 25 | "The header manages, the floating sheet plans": the phone pairing entry always in the header | `#syncBadge` beside My routes, always shown: not paired → phone (no dot) + "Pair phone" / "Handy verbinden" (`web_pair_phone`), a click opens the pairing dialog (QR code + check digits) directly; paired → phone + green dot + "Paired" / "Verbunden", tooltip "Paired with your phone · last synced …", a click opens My routes at "Pair with phone" (Sync now, Unpair). Follows `sync.onChange` – no reload after pairing / unpairing. Below 1180 px icon only with its title, phones: the ☰ list with text. My routes keeps its section. Tests: sync-smoke (Pair phone → the pairing dialog directly, "Paired" after pairing, back to "Pair phone" after unpairing on both sides, ☰), sheet-smoke (labels incl. the entry, both states), lang-smoke ("Handy verbinden"), ui-shots (`desktop-header-unpaired/-paired.png`). |
| 26 | Always light, like the app | The app has no dark mode and the map is light; the page's dark scheme (dark header and sheet over a light map) is gone – `color-scheme: light`, a dark system setting changes nothing (row 13b's dark tones are retired). Test: ui-smoke (dark system setting → same colours, white page). |
| 27 | My routes: "zu viele 'Trennen'" – Unpair three times, the question below Close | *Unpair asks nothing since row 32 (A-017); the in-place question stays for deleting a route.* The question takes the place of the "Sync now · Unpair" row (since row 29 in the Phone window, above Close; `askInPlace` in plans.ts): the app's text without a repeated "Unpair?" ("Routes stop syncing. They stay in this browser and on the phone." / "Die Routen werden nicht mehr abgeglichen. Sie bleiben in diesem Browser und auf dem Handy.", app `sync_unpair_text`), **Cancel** (app `common_cancel`) + one red **Unpair**; Escape / Cancel bring the row back with focus on Unpair. The pairing dialog has no Unpair of its own any more (row 29), and a route's delete question now sits in place of its line (Keep · Delete) – never under Close. Close alone at the bottom right. Tests: sync-smoke (in place, above Close, one "Unpair", Escape, Cancel), ui-shots (`desktop-phone-unpair`). |
| 28 | My routes says what syncs; Save in three places | *First "Save out of the header" – reversed the same day by owner order A-017 (Save sends to the phone): Save stays in the header.* Header: My routes · phone entry · **Save route** · Import GPX · Help – 5 entries (desktop and ☰); greyed on an empty route, ✓ **Saved** while saved and unchanged (`saveEntry`). Also the sheet's ⋯ → **Save route** (the app's `SheetMenuItems`) and My routes → **Save current route** (greyed on an empty route; ✓ **Saved as …** while saved and unchanged; a click still asks Save / Save as new, the app's `saveMode`). The sheet's "Saved as … / changed, not saved" line stays from the profile step on, hidden in the small step like the app's `SavedLine`. My routes' list has the heading **Your planned routes** / **Deine geplanten Routen** with the app's planned-route icon (`Icons.Filled.Route` in `OnPathColors.Route`): only planned routes sync, no tracks. Tests: parity-smoke (header Save, ⋯ Save, My routes save states: active, saved, changed, back), sheet-smoke (5 header entries, paired too), search-smoke (⋯ Save). |
| 29 | The phone in its own window, three states (owner 2026-10-30) | **Phone window** (`phoneDialog` in plans.ts), opened from the header's phone entry (☰ on phones) only – My routes has no pairing section any more. On top the state with its dot and one sentence (`phoneState`): **not paired** – grey phone, "Pair phone" / "Handy verbinden", "Your planned routes appear on the phone and here."; **paired and current** – green dot, "Paired · synced 2 min. ago" / "Verbunden · abgeglichen vor 2 Min." (the phone reached the mailbox within **24 h**: the app syncs at start, on return and every minute while open but never in the background, so a phone in use syncs many times a day; the app's own 6 h warning would turn amber every night); **paired, the phone not syncing** – amber dot, "Paired · phone last 3 days ago" / "Verbunden · Handy zuletzt vor 3 Tagen", "Open OnPath on the phone, then the routes sync. Changes from here wait until then." Offline in the browser stays the sync line `web_sync_offline` (the app's `sync_offline`). The phone's time comes from the host (`appSeen`, web-sync.md §8 – no app change); without it (older host, two browsers) green with when this browser synced. Then "How it works" (3 lines: appear on both, sync while OnPath is open, newer wins / older stays as a copy), privacy in one line + **More** → the privacy page, the actions (not paired: **Pair phone** → QR code, steps "Open OnPath on the phone → ☰ → Pair with browser → scan this code", check digits; paired: **Sync now**, **Unpair** with the question in place; no "Show code again" – A-017: a second browser pairs itself), **Close**; scrolls at phone width. Header: the entry's dot green / amber, none when not paired, tooltip = state + sentence; at phone width the **☰ button carries the same dot**. `web_sync_already_paired` / Help point to the entry ("Paired → Unpair", "Pair phone, at the top"). Tests: sync-smoke (state 1 window, the three states with the phone's time faked through the host, amber / green on the entry and on ☰, How it works / privacy / buttons, unpair in the window), host `sync.test.js` (appSeen), ui-shots (`*-header-{none,fresh,stale}`, `desktop-phone-{none,fresh,stale,unpair}`, `phone-phone-stale`). |
| 30 | A toolbar button that shows the whole route | `#fitRoute` next to locate, Material `fit_screen` icon, "Show whole route" / "Ganze Route zeigen" (`web_fit_route`, label + tooltip): fits the planned line, every point and a shown GPX track into the free map room (`fitPadding` – clear of the sheet in any step, the top bar, the place card, the controls; the same fit as a shared plan's). A padding left by an older `easeTo` is cleared first (fitBounds would add it). Not keepFollow – it ends position following ("Map camera"). Greyed below two points, tooltip "Set a start and destination first". Test: sheet-smoke (greyed on an empty plan; after a jump far away and zoomed in, click / Enter brings every point and the line into the free room – desktop 1440×900 small + Points step + left-over padding, phone 390×844 small + Points step, with a GPX track on both). |
| 31 | "War in Meran, jump to location (Hilden), suche nach Hilden, gebe als Start" → "This route is very long – about 35 hours by bike", only point 1 on screen – read as a bug | **Cause (reproduced, the owner's address `#p=…` after the error):** the plan still held one pinned point "Algund" from earlier (restored from the address); "As start" put Hilden before it → a 2-point plan Hilden → Algund, 35 h by bike, and the app's long-route question fired correctly – but nothing on screen said there was an old point. An empty plan + a far result as start never asks (fewer than two points). Now: (1) "Far from your route" when the one new point of an edit alone makes the plan long (§15); (2) the sheet names a single point also in the small step ("1 point · Algund"); (3) "Your route is outside the map · Show" when no point / route piece is in view and the nearest point is > 25 km away (Show = the toolbar's "Show whole route", row 30, also for one point); (4) Delete (Clear route) also in the Points head next to Reverse / Round trip (owner: "Route löschen" not found in ⋯; no question, ↶ brings it back; ⋯ keeps it like the app). Test: import-smoke. |
| 32 | Owner order A-017: one folder per phone, "Save" sends to the phone ("So wenige Fragen wie möglich") | web-sync.md §14. Header **Save route** bold / filled (`.unsaved`) while the open phone folder has anything not on the phone, quiet "Not saved on the phone yet" / "Noch nicht auf dem Handy gespeichert" beside it (`#saveHint`, below 1180 px only the tooltip), amber "Mailbox full – …" after a 507; Without phone: saves here, no hint. My routes: "Your planned routes · Pixel 8" / "· Without phone", imported tracks listed (purple route icon, "imported track"), the phone's new / changed routes bold + "New from the phone" / "Changed on the phone" until opened, "Without phone (n)" ⇄ "Back to Pixel 8", "Copy to Pixel 8" beside "Saved as …" for a route from Without phone, quiet "Remove routes of ‹phone› from this browser" for closed folders. Phone window: Sync now · **Pair another phone** · **Unpair without a question** (row 27's question gone – A-017 rules 3 / 7); "Unpaired. The routes of Pixel 8 stay in this browser."; 410 → "The connection was ended on the phone." Pair phone shows a new code every time. DE words from the app ("verbinden", not "koppeln": texts-check). Tests: sync-smoke (67 checks, web-sync.md §14.4), WebSyncJoinTest (JVM). |
| 33 | "Überladen" – the A-017 dialogs show the machinery (owner 2026-10-30, final) | web-sync.md **§14.6** replaces the UI parts of rows 22, 23, 25, 28, 29, 32 (the machinery of §14.1–14.3 stays). Header right: **Save** (`#menuSavePlan`, not paired "💾 Save", paired "📱 Save to phone"; dark / light ✓ "Saved" · "On the phone" / greyed / amber 507; no hint beside it) and the **Phone** pill (`#syncBadge`: outlined "Pair phone", paired name + dot + "synced 2 min. ago" / amber "last 3 days ago"), 36 px, side by side; the other entries stay left and lose their texts first (< 1180 px); at phone width Save and Phone stay as symbols, the rest behind ☰. Save "as in Word" (rule 5): a name field under the button. **My routes** one plain list: kind circle, name, "Planned · 30 Oct 2026" / "GPX · …", 🖥 / 📱, ⋮ Rename · Export GPX · Delete, bold = waiting, the open route light green; no heading, Save box, marks, ✎ / 🗑, folder lines. **Phone window** small, "How does it work?" in place, "Pair another phone" / "Remove routes of …" small at the bottom. Tests: sync-smoke, parity-smoke, sheet-smoke, lang-smoke, ui-shots. |
| 34 | No WebGL2 → a plain message | maplibre needs WebGL2; without it (old browser, hardware acceleration off, some headless browsers) the page was blank without a word. Now one calm full-page message (`web_no_webgl_title/_text`, EN + DE: use a current Chrome, Firefox or Edge and switch on hardware acceleration), no map started. Test: nowebgl-smoke (CI group short). |
| 35 | App 0.13.5 parity (owner 2026-10-31, captain's order): Save asks for the name, plan sheet without ⋯, candidate marker, fit after a place | **Save** (header) always opens the app's name dialog (`plans.ts nameDialog`, `#saveTitle`, Cancel · Save; the name selected): the open saved route is overwritten and renamed when the name changed, a new route gets the proposal (link title, the place proposal once it arrives – 5 s –, else the date name); "Saved as …" is a plain line (no rename in place, no message). **My routes ⋮**: Rename · Save as copy ("Name (2)", `copyName` = `RouteRules.copyName`) · Export GPX · Share plan · Delete (copy / share for planned routes, the app's `TourItemMenu`). **Plan sheet without ⋯**: the action row ✕ Close · ⇅ Reverse · ↻ Round trip under the summary (Points head only the title); ✕ Close asks "Discard changes?" (Cancel · Discard) only with unsaved changes; **Export GPX** of the shown plan moved into the Share dialog (owner 2026-11-01, "In den Teilen-Dialog"). **Candidate marker** (`search.ts candidateMarker`) instead of the orange ring: dashed route-blue ring with "+" and the name below, a thin dashed line (`candidate-join`) to `WaypointInsertion.joinPoint` (engine `placePreview` → `join`). **Fit after a place** from the card (`WholeRoute.fitAfterPlace`; a POI tap on the map never moves it). Not mirrored: the app's rolled-up one-row step (COLLAPSED – its compact green Start has no browser counterpart). Tests: parity-smoke 6 / 6d / 7, sync-smoke, import-smoke, sheet-smoke, search-smoke, ui-shots. |
| 36 | App 0.14.0 parity (merge of main f154d62, 2026-11-03): the found place is a **pin** | `search.ts candidateMarker` = the app's `CandidateMarker` pin (route-blue teardrop, white outline + dot, tip on the place, soft shadow) instead of the dashed ring with "+"; its name is a **chip** under it (`.pinchip`, "Name ›", > 20 characters end in "…" = `PinChipRules.label`, aria "Name: show options" = `plan_pin_chip_desc`). Closing the place card keeps the pin (the app's `closeMapOverlays`); the chip (or the pin) opens the card again where the map is; the card ends with **Remove pin** (`plan_remove_pin`); the pin goes on an add, a new pick, Remove pin and Neu planen (`removePin`). The dashed preview line stays the card's. Not mirrored (phone only): the tour's action tiles and plan puck, "Änderungen verwerfen", the end-tour route box, delete without a second question. Engine tests: the app's BRouter profiles are copied to `web/engine/src/main/assets/brouter` (task `appProfiles`, git-ignored) for `RouteWarningsTest`. Tests: sheet-smoke, follow-smoke. |

 Help is the credits dialog with four how-to lines (a real help
page could link onpath.earth/help).

## 14. App logic on the web – inventory and keeping it in sync (cloud, 2026-10-29)

Owner items 5 + 13: "cross-check the planning logic and keep the web in sync automatically – also with the memory
work in the app". The engine generates the app's string resources (web-sync.md "Status"); every file below "shared" is
compiled from `app/` as it is.

### 14.1 Inventory (app source → web)

| Planning logic / validation | App source | Web | How |
| --- | --- | --- | --- |
| Routing, profiles, SAC limit, "more than 1000 m from any path", time limits | `routing/BRouterEngine`, `RouteTimeLimit`, `RoutingContract` (error kinds), `WaypointSnap` | same | shared (Wasm) |
| Leg-by-leg plan, failed leg = straight dashed + plain message, pinned POI points | `routing/Planner` | same | shared |
| Where a clicked point goes; reverse, round trip, move, start here, delete | `WaypointInsertion`, `PlanEdits`, `PlanPoints` | same (drag arithmetic also mirrored, see below) | shared |
| Undo / redo, hold rules and menus, "Go here" blocked reasons | `ui/PlanState` (`PlanHistory`), `ui/MapGestures` | same | shared |
| Difficulty badge, steepness, way types / surfaces | `routing/RouteSummary`, `BRouterWayStats` | same | shared |
| Route warnings (ferry, pushing, steps, above T, ford, steep; MTB rules) | `ui/RouteWarnings` | same | shared |
| Plan time (one time model, pace setting, e-bike flat cap, rounding) | `time/PaceModel`, `RouteTimes`, `TimeEstimate.planSeconds` / `planFactors` | same since 2026-10-29 | shared; the pace choice (Settings → Pace) in the activity drop-down (§13 #14, `WebPace.kt`; `PaceSettings.PaceSection`, `Theme.navName`, Prefs keys mirrored) |
| Very long plan asked first (> 24 h estimate) and what Cancel does | `routing/LongRoute`, `ui/PlanStatus`, `AppViewModel.recalc / answerLongRoute / cancelRun` | same (§15) | shared rule + texts; dialog flow mirrored (`longroute.ts`, `main.ts`) |
| Point the route does not reach (red line + Show, grey dashed piece) | `ui/PlanGaps`, `MapController.setPlanGaps`, `PlanPanel` | same since 2026-10-29 | shared rule + text; drawing mirrored (`plangaps.ts`); hollow grey point not drawn yet |
| What reaches it („② erst ab T2 erreichbar“, Show = the reaching way amber + fitted) | `routing/PlanReach`, `PlanGaps.reachText`, `AppViewModel.findReach` / `showPlanGap` | same since 2026-11-07 | engine `plan` runs `PlanReach.find` after the route (only the missing piece), `gapsJson` gives the line and `way`; `plangaps.ts` draws `plan-reach` in the app's SPOT_COLOR on Show (`WebPlanReachTest`, import-smoke 5) |
| Plan link / QR | `routing/PlanCode` | same | shared |
| GPX import, Komoot sport → activity + T level, track → plan | `gpx/GpxParser`, `gpx/SportType`, `routing/TrackToPlan`, `time/TimeEstimate`, `AppViewModel.importGpx / planFromShown` | same (§15) | shared; flow + panel mirrored (`gpximport.ts`, `WebImport.kt`) |
| GPX export with sport type | `gpx/GpxWriter`, `SportType` | same | shared |
| Search: index, ranking, Photon, add preview; route name proposal | `search/*`, `search/RouteNamer` | same | shared; Photon URL + debounce mirrored (`search.ts`) |
| POI kinds and filters | `poi/PoiKinds`, `poi/PoiFilter` | same | shared; visibility rules mirrored (`mapcontent.ts`, `LayerSettings`) |
| Routing data per corridor (1° parts) | `routing/Rd5Parts`, `routing/OfflineCorridor` | same | shared; the plan's activity picks the corridor (10 km on foot, 20 km by bike, 2026-11-07; `partsNeeded(points, activity)`, a prefetch without a plan the largest); download loop in `worker.ts` (app: `RoutingDataLoader`, `Rd5PartStore`) |
| A held place's add entries („Als Zwischenstopp“ / „Als neues Ziel“, „⋯ Mehr“) | `ui/PlaceAddMenu` | same | shared; engine `placeMenu` (no tour: the route ahead = the plan), `poiMenu` in `main.ts` |
| Locate off the map: the distance under the icon, a tap frames plan + me | `ui/LocateDistance` | same | shared; engine `locateLabel` on `moveend` / a new fix (`updateLocateLabel` in `main.ts`) |
| Plan sync | `sync/*` | same | shared |
| World overview below z10 | `map/Overview.merge` | same (§16) | shared, run at build time; cover + URLs in `overview.ts` |
| Optional map sources stripped | `map/OptionalSources` | mirrored | `cells.ts` |
| Texts | `strings/…/values/*.xml`, `Txt` | same (English) | generated (`generateStrings`), `Txt` stand-in |
| Mirrored UI rules (Android / Compose files) | `Screens.SacScale`, `MapOverlays.stopLabel` + menus, `PlanStatus.noRouteLine` texts, `RouteRules.saveMode`, `MapFooter.gpsColor`, `MapController` arrows + layer order, `MAP_CREDITS`, `ElevationProfile` colours, `TourBar.longRouteText`, `Panels.TrackPanel`, `PointsSection`, `PlanPoints.dropIndex/shift` (per pointer move) | mirrored | listed with the app file's hash in `web/parity.json` |
| Engine stand-ins for Android files | `debug/Problems`, `debug/UserText`, `data/DataFiles`, `recording/GpsStatus`, `Txt` / `XmlStrings` | mirrored | `web/engine/src/main/kotlin/com/onpath/app/…` |
| Not on the web (on purpose) | navigation, recording, re-routing, activity check, rides, cells download + joining (the web reads cells in place; build requests and the strip: §17), backups, Google share (`SharedPlace`), plan links into the app (`PlanLinks`), haptics, Points delete guard, Notices | – | phone only; `watch` in `web/parity.json` lists the plan-related ones |
| Region view z7–9 (region packages) | `map/Overview.merge(packages)`, `cells/CellStore` (a package owns z7–9) | same (§13 #15) | merge shared at build time (`OverviewStyle.kt`, placeholder box); package lookup + `within` boxes mirrored (`cells.ts`, `overview.ts`) |
| Not on the web yet | Route details screen, hollow grey unreachable point | – | open items (§14.3) |

### 14.2 How the web stays in sync

1. **Shared code updates itself:** since the one-branch merge (owner 2026-11-06, onpath.private#32) app and planner
   live on `main`; the engine compiles the app's files in place, so a shared change reaches the web with the commit
   itself. `.github/workflows/web.yml` ("Web planner") runs on every push / pull request that touches `web/` or the
   app code the engine compiles; locally `web/parity/check-web.sh` runs the same checks. The planner goes online only
   on purpose: with an app release (`release.yml`, job "planner", after its own green checks) or Actions → Web planner
   → Run workflow → deploy – phone and browser always come from one commit. **The app leads** (owner 2026-10-30): when a
   mirrored app file changed (texts, buttons, menus, colours, plan behaviour), the laptop (or a scheduled task) adopts
   the change in the web itself, tests, `--update`s and pushes – it does not wait for the owner. Only a change with no
   web counterpart yet (a new screen, phone-only hardware) goes to the owner as a proposal.
2. **Mirrors cannot drift silently:** `web/parity.json` stores, per mirrored app file, the git blob hash it was last
   checked at. `web/parity/check.mjs` (first step of the web CI) **fails** when such a file changed – look at the
   change, adjust the web, then `node web/parity/check.mjs --update <app file>`.
3. **Shared files and performance / memory:** every compiled app file (`appFiles` + `appTests`), the string files,
   BRouter's jars and profiles and the style are listed with their hash and their perf / memory-relevant lines
   (caches, limits, timeouts, budgets, buffer sizes). A change is a CI warning; a change in those lines is shown
   line by line ("PERF/MEMORY-relevant change …") – then check the web side too (Wasm heap, worker, prefetch sizes,
   `RouteTimeLimit` vs browser speed). The run's summary page carries the report.
4. **Watch list:** app files with plan behaviour the web neither compiles nor mirrors (PlanPanel, MapScreen, Notices …):
   a change is a warning, so the laptop can propose the web side.

5. **Deliberate differences (owner's word – a mirror check must not bring them back):** the plan row has no
   „✕ Neu planen“ (owner 2026-11-06: „+ Neu“ in the plan bar is the one way; the app's PlanActions keeps ✕).
   Labels of the plan bar use the app's strings for the same thing (owner 2026-11-06: „genauso wie auf dem Handy“):
   the list is „Meine Touren“ (`routes_title`), not „Öffnen“.

### 14.3 Open items

- `PlanStatus.noRouteLine` texts are still mirrored in `main.ts`; the engine now compiles `PlanStatus` – the page could
  ask it (UI agent's file).

## 15. Long-route question and GPX import (cloud, 2026-10-29)

**Long route (owner item 4).** The app does not refuse a far plan; it **asks first** (`routing/LongRoute`: straight
lines × 1.3, flat, timed by the pace model; more than 24 h → "This route is very long" / "About 8 days on foot.
Calculating and loading its maps takes long." / Cancel · Plan anyway). Nothing is routed or downloaded while it is
open. Cancel takes back only the change that made it long (`PlanStatus.onCancel`: a clicked point is undone, an
activity change is put back, a loaded plan stays unrouted with "Route not calculated – very long plan." + "Plan
anyway"). The web asks the engine (`longRoute`, `planCancel` – the app's code and texts) before every plan; "Plan
anyway" is remembered for those points like the app's `longRouteOk`.

**Far from your route (web, owner 2026-10-30, §13 row 27).** When a point edit adds exactly one point and that point
alone makes the plan long – the plan without it is not long (or is a single point) and even its straight line to the
nearest earlier point is (the app's `LongRoute` rule on those two points) – the question says so instead:
"⚠ Far from your route" / "Your point Algund is about 35 hours by bike from here." (a single earlier point, by its
name) or "Your route near <first named point> is about 8 days on foot from here." – buttons, stacked, the primary
first: **Start a new route here** (only the new point stays; one undo step with the edit, so ↶ brings the old plan back –
nothing more is said), **Add to the route** (= Plan anyway), **Cancel** (as the app's Cancel). A plan long for other
reasons (many points spread out, a loaded link, undo / redo, an activity change) keeps the app's question
(`longroute.ts farPoint`; texts `web_far_*`).

**GPX import (owner item 10).** Header / ☰ → **Import GPX**, or drop a `.gpx` on the map. The engine runs the app's
`GpxParser` unchanged: TeaVM has no XML parser, so the Wasm build maps `javax.xml.parsers` / `org.xml.sax` to a small
SAX stand-in (`web/engine/src/main/java/onpathweb/…`, `XmlSubstitution`), and java.time to ThreeTen backport (TeaVM's
own mapping); `WebImportTest` checks the stand-in reports the JDK's events on the fixtures and `gpx-engine.mjs` that
Wasm gives the JVM's JSON byte for byte. Then like the app: Komoot's `<trk><type>` sets activity and T level
(`SportType.importChoice`, "Activity set to Hike T2 from the file"), time = the file's own, else the time model, the
track panel (name · Close track, distance, time, climb, grey lines, profile) takes the sheet's place, and the app's two
ways on: keep it shown, or **Plan a route from it** (`TrackToPlan`: few route points, our router, one undo step).
Following the track (Navigate) is phone-only. Export ⇄ import keeps sport and line. Tests: `import-smoke.mjs`
(fixtures in `web/app/test/fixtures`: a Komoot hike, a GPX 1.0 route with prefixes / CDATA / references, a broken file).

## 16. World overview below the cells (cloud, 2026-10-29)

Owner item 12: below zoom 10 the web map was empty (cells hold z10+). Now the page serves the APK's overview files
(`base.pmtiles` Natural Earth z0–5, `hillshade.pmtiles` z0–7 – the GitHub release `overview-2026-10-22` the app's
release workflow downloads, SHA256-checked in the web CI, ~14 MB static assets) and loads the app's style merged by
the app's own `map/Overview.merge` (run on the JVM at build time: `./gradlew -p web/engine overviewStyle` →
`style/onpath-overview.json`). The page only points the `asset://overview/` URLs at its `overview/` folder and keeps
the cover (`detail-cover`) on the cells it found – the app covers its installed cells. Without the files (a local build
without `ONPATH_OVERVIEW_DIR`) the plain style is used as before. Region packages (z7–9): §13 #15 (since 2026-10-30). Test: `overview-smoke.mjs` (zoom 5 and 8 draw the overview; with
map data also zoom 12.5 with the cover).

## 17. Map areas on demand, never a blank map, every wait said (cloud, 2026-10-30)

Owner report (live test): Locate jumped to the owner's home (no cells there) and showed an **empty beige map** with
the blue dot and no word why. "When there are waits, the user must know why … it must work much like on the phone."

**Never a blank map.** The overview (§16) already continues above zoom 10 under the detailed layers (the app's
`~under` copies), but it is Natural Earth – at zoom 15 in a flat town that is beige. Changed:
- the cover (`detail-cover`) follows the cells that **draw** (a first base tile came back), not the cells whose
  `current.json` exists – so a cell that is still loading, or a cell without readable files, never hides the overview
  (`cells.ts` `found`; the app covers its installed cells, which always draw);
- a region package's relief (z7–9, §13 #15) goes on magnified above zoom 9 where no cell draws (`region-relief`
  without maxzoom, under the cover) – the app stops it at 10 because its cells are installed there. Its vector data is
  not overzoomed (would need copies of the detailed layers from a second source – open decision below);
- **a cell whose map fails** (`cells.ts` `failed`, owner report 2026-10-30: Hilden showed POIs on beige, no streets):
  pmtiles keeps a failed header for good and maplibre keeps a failed tile as empty, so one 429 used to cost the cell's
  streets for the whole visit. Now a busy answer (429 / 5xx / network, also an expired login) drops the archive and tries
  again after 5, 15, 30, 60, 120, 300 s (a 429 at least 60 s – the host's Retry-After), then reloads that layer's tiles;
  after 6 tries, or when the file is gone (404, wrong bytes) and `current.json` read once more still names the same
  version, the file is left out until the page is reloaded. A new version in `current.json` (the cell rebuilt while the
  page was open) → the page switches to it. Many tiles failing at once count as one failure; a busy `current.json` is
  not asked again before its backoff (no request per tile). While a cell's base does not draw it leaves `found` (the
  overview shows there) and the map strip says it: grey `maps_err_unreachable` ("Map server not reachable – trying
  again.") while it is tried again, red `web_maps_cell_failed` ("… could not be loaded – the overview map is shown.
  Reload the page to try again.") when left out. Test: `cells-smoke.mjs` (404, 503, 429, new generation, busy
  current.json, slow answers, diagnostics).
- **Diagnostics** (`diag.ts`, English): every recovered failure goes to the page's problem log once (deduped, counted),
  also uncaught errors, maplibre errors and main-thread blocks over 1 s (Chrome's long-task timing – a frozen page leaves
  a trace). To send it: open the browser console (F12) and type `__diag()` – the report is copied to the clipboard; or
  add `?debug` to the address for a "Copy diagnostics" button on the map. It holds the page address (no plan), the
  browser, the rounded map view, the cells' state and the problems.

**Every wait said** (CLAUDE.md "Messages": a spinner where the user started it, INFO grey, ERROR red with a way out):

| Wait | Where | Text |
| --- | --- | --- |
| Engine start | sheet status line, spinner | Loading the routing engine… |
| Locate | map strip, spinner | Finding your position… (after 20 s: Still finding your position – …); denied: red "Location is not allowed for this page. To allow it, click the icon left of the address, turn on Location, then click Locate again." (the next click asks again); off: red "Your position is not available – …" |
| Map tiles still loading 400 ms after the map stopped | **load overlay (§27)**, percent, until loaded | Loading the map for your area (`maps_load_loading`) |
| Routing data for a plan | **load overlay (§27)**, percent (bytes received of the bytes needed) | Loading the map for your area (`maps_load_loading`; until 2026-11-13 „· about 10 MB“, `maps_about_mb`, in the wait box) |
| Routing | sheet subline + status line, spinner | Calculating the route… (`plan_calculating`) |
| "Go here" waiting for the position | status line, spinner | Finding your position… |
| Spot without detailed map, zoom ≥ 10 | map strip, grey | Overview only – the detailed map for this area is asked for when the map rests here. |
| Map area being built | **load overlay (§27)**, the doorman's percent | the app's strip: "Preparing map for this area – about 5 min", rows "Your position · waiting – about 5 min" / "being prepared – 40 %, about 2 min", "2 more areas · waiting", × = hide (keeps loading) |
| Map server down / offline | map strip, grey | "Map server not reachable – trying again." / "No detailed map – loads when online" |
| Build failed | map strip, red | the doorman's reason, or "Map for this area failed" |
| No key on the host | map strip, grey | The detailed map for this area is not available on the web yet – the overview map is shown. |

Audited: search ("Searching online…" in the list), GPX import ("Importing …"), saving / sync (fast, local), long-route
question (a dialog), name proposal (local). App texts come from the engine (`mapTexts`, `WebMaps.kt`: the generated
`Res.string` / `Res.plurals` of strings_maps / strings_plan), filled with XmlStrings' `%1$d` rule in `areas.ts`; texts
without an app counterpart (locate on the web, not available on the web) are the page's own (English, like the rest).

**Map areas on demand** (`areas.ts`, mirrors `CellCards.build` / `name` and `CellTriggers` – `web/parity.json`):
- Triggers: a point set (T1; more than 3 new at once = a loaded plan → T4 only), the position after Locate (T3,
  "Your position"), a search result shown (T2), the computed route (T4: the cells the line crosses, at most 12, reason
  `corridor`), and the web's own: the map **resting** at zoom ≥ 10 for 4 s on a spot whose centre has no cell (the
  centre cell). Scrolling alone never asks. A cell that exists (its `current.json`) is never asked for; each cell at
  most once per page (dedupe). Several triggers join one strip (the app's job); it closes by itself when every area is
  there.
- Flow: `POST /maps/v1/cells/request {"cells":[…],"reason":"point","formats":[2]}` → states → `GET
  /maps/v1/cells/status?ids=…` every `poll_s` (5–60 s, the doorman's answer) while the page is visible (hidden → no
  polling; visible again → at once) → `ready`: the page forgets the cell's "not there", reads its `current.json` and
  gives the cell sources new tile URLs (`?g=<generation>`), so maplibre loads the new tiles; the files are read in
  place from R2 (`/data/`), nothing is downloaded or joined. 429 / 5xx / offline → "trying again" with backoff 30, 60,
  120, 300 s (or Retry-After). 503 / 404 from the proxy (no key, or a static host without it) → "not available on the
  web yet", nothing more is asked.
- **Host proxy** (`web/host/src/maps.js`): only `POST /maps/v1/cells/request` and `GET /maps/v1/cells/status`; bodies
  rebuilt (zoom-10 ids only, at most 12, the doorman's reasons, format 2); `X-OnPath-Key` added from the Worker secret
  `MAP_KEY`, never answered back; upstream = the doorman Worker through the service binding `DOORMAN`
  (`onpath-doorman-test`, same account – the doorman also sees the browser's IP for its own per-IP limit), else
  `https://$MAPS_HOST/v1/…`. Limits: `MAPS_RATE` 20 per minute per IP, `MAPS_RATE_ALL` 120 per minute for all browsers
  (Workers rate-limit bindings), on top of the doorman's 30 per minute and its build caps. A key the doorman refuses →
  "not available" (+ Worker log).
- Owner step: §9 step 5 (repository secret `ONPATH_MAP_KEY_TEST`).

Tests: `web/host/test/maps.test.js` (key added and not answered back, bodies rebuilt, only two calls, limits, no key,
refused key, upstream down); `web/app/test/areas-smoke.mjs` (the owner's report reproduced – Locate to a spot without
cells: overview at the Bodensee at z11, a fake package's relief at z15, no cover; "Overview only", "not available";
the request flow against a fake doorman behind the real proxy: one request, the app's strip texts, polling, ready →
the cell's new version read, strip closed, no second request; location denied; engine / routing data / routing waits);
`web/host/live-smoke.mjs` (live, optional, §9 step 6).

Open decisions:
1. View trigger: the map resting 4 s at zoom ≥ 10 builds the centre cell (the owner asked for it; the app asks only for
   points, position, search, route). Costs a build per new area looked at – keep, lengthen, or ask first like the
   app's T2b ("Load the map for this area?")?
2. Region packages are shown, never requested from the web (the app's `ensurePackages`); and their vector data is not
   magnified above zoom 9 (copies of the detailed layers from a package source, below the cover, would show roads and
   towns there – more layers to draw).
3. Every browser shares the planner's test key; when the doorman counts per app id (map-access.md) the web needs its own.


**Changed 2026-10-30 (A-011, §18):** the map resting orders nothing any more – on a public page idle panning must not
build map areas. Open decision 1 above is settled that way (assumption, owner may revert): only points, the route, a
search result and the position ask; resting at zoom ≥ 10 without detailed map says "Overview only – set a point here
to load the detailed map".

## 18. Public planner – German first, the quiet path to the app, cost protection (cloud, 2026-10-30, A-011)

Owner decision 2026-10-30: the planner goes public (no login), with a quiet path to the app. Cloudflare Access is off
(owner, 2026-10-31); the site stays `noindex` until the launch (18.4).

### 18.1 Languages
- **One engine, every language.** `generateStrings` (web/engine) reads `strings/…/values/` (English) and every
  `values-<lang>/` folder; each `Res.string` carries all of them, `Txt.setLanguage(lang)` picks one at run time (the
  page sends it with the engine's config) and sets the locale, so `data/Format` writes "4,2 km" in German as in the
  app (TeaVM keeps only `en_US, de_DE` number data – `java.util.Locale.available` in the build). Everything the engine
  writes (plan sheet, difficulty, warnings, menus, activity names, map-area strip) follows.
- **The page's texts** come from `src/gen/texts.ts`, generated at build time (`npm run strings`,
  `scripts/gen-strings.mjs`): the app's own strings for every key the TypeScript names (`t('plan_reverse')` etc.) – so
  the page says what the app says – plus the web's own texts in **`web/strings/values[-de]/strings_web.xml`** (same
  Compose resource format, keys `web_…`; a new language = one folder there and in `strings/`). Placeholders `%1$s` /
  `%1$d` as in the app. The build fails on a key that exists nowhere. Why at build time and not through the engine:
  the header and sheet render before the Wasm engine has loaded (and still render if it cannot load).
- **The web uses the app's words; the app leads** (owner 2026-10-30). Where the app has a text for the same thing the
  page uses the app's key (`routes_title` "My routes" / "Meine Routen", `plan_save_route`, `plan_route_default_name`,
  `msg_name_share` …); its own `web_…` texts use the app's terms: a saved or shown thing is a **route** / **Route**
  (never "plan" / "Pläne" as a noun – only the app's "plan link" / "Plan-Link" and the verb), the phone is **paired** /
  **verbunden** and **unpaired** / **getrennt** (the app's `sync_*`, not "koppeln"). `texts-check.mjs` fails on the old
  words. A new app word changes the web text, not the other way round.
- **Language:** `?lang=de` (not remembered) → the header's switch (DE | EN, remembered as `onpath.lang`; the page
  reloads, the plan stays in the address) → the browser's language (`de*` → German) → English. `<html lang>` follows.
- **Checks:** `test/texts-check.mjs` (every web key in every language with the same placeholders; no user text left in
  the TypeScript – markup, title / aria-label / placeholder, label / status / say values; logs and internal errors stay
  English like the app's problem log), `test/lang-engine.mjs` (engine in German and back, decimal comma),
  `test/lang-smoke.mjs` (German browser end to end, links, the app line, the switch).
- Not translated on purpose: map labels (German / local names from OpenStreetMap, as the app), credits' proper names.

### 18.2 Website links and the one moment for the app
- Header: "Die App" / "The app" → `https://onpath.earth/de/` / `https://onpath.earth/` (a plain nav entry; the logo
  links there too). Footer (beside "© OpenStreetMap") and Help & credits: Datenschutz / Privacy →
  `/de/datenschutz/` / `/privacy/`, Impressum / Legal notice → `/de/impressum/` / `/legal/`. Checked on the live
  site 2026-10-30 (`/en/…` paths do not exist).
- **The app line** (`apphint.ts`): „Mit der App: offline unterwegs, Navigation, neue Route ohne Netz.“ + „App holen“
  (EN "With the app: offline on the way, navigation, new route without signal." + "Get the app") →
  `/de/ausprobieren/` / `/try/` – never a store link. Shown in the Share dialog and in the plan sheet
  when a shared plan link is opened (the sheet is beside / under the fitted route, so it never covers it); once per
  browser session (`sessionStorage`), ✕ closes it, never full screen, nothing counted.

### 18.3 Cost protection (host)
| What | Limit | Where |
| --- | --- | --- |
| Map files `/data/` | 2400 requests / min per IP (a map view ≈ 100 tile ranges; 600 is the likely cause of the missing streets in the owner report 2026-10-30) | `DATA_RATE` (Workers rate limit) |
| Routing data `/segments/` | 30 requests / min per IP (a plan needs a few 2–10 MB parts) | `SEGMENTS_RATE` |
| Map-area calls `/maps/` | 20 / min per IP, 120 / min all (existing) | `MAPS_RATE`, `MAPS_RATE_ALL` |
| **New map areas** | 15 per browser per day, 60 per month; 40 per IP per day; 200 per day for everyone | `maps.js` quotas, D1 table `map_quota` in the sync database (migration 0002) |
| Sync mailbox | 300 requests / min per IP (clients pace to ≤ ~33, web-sync.md §8); 32 KB per plan, 1000 items and 4 MB per mailbox; idle 12 months → deleted | `sync.js` `LIMITS`, `SYNC_RATE` (existing) |

- **New map areas:** the page sends a random browser id (`onpath.browserId`, 16 random bytes) as `X-OnPath-Browser`
  with each build request. The host counts the cells the doorman answers as *queued / building* (new work; cells that
  are already there cost nothing) per browser (day + month), per IP (day; the IP only as a SHA-256 of host secret +
  day + IP, so it cannot be read back and changes daily) and for everyone (day). Stored: key, period, count – no cell
  id, no position. Over a limit → 429 `{"quota": "browser" | "ip" | "global"}`, the strip says „Für heute genug neue
  Kartengebiete in diesem Browser. Die OnPath-App lädt jedes Gebiet – oder versuch es morgen wieder.“ (global: "The
  planner has prepared many new map areas today …"). Counters older than two days / the last month are deleted by the
  daily job. A request may overshoot by one call (at most 12 cells): the check is before, the count after the
  doorman's answer. Numbers in `wrangler.toml [vars]` (`MAPS_QUOTA_*`).
- **Sync limits checked (report):** per-IP rate, item size, items and bytes per mailbox and the idle purge exist. Not
  there: a cap on *new* mailboxes per IP (each costs one D1 row; mailbox ids are random) – fine for now, a per-IP
  daily cap like the map quota is the follow-up if the table grows.
- **Owner step – spending alert (Cloudflare, no token needed):** dash.cloudflare.com → the account → *Notifications*
  (left menu, "Manage account" → Notifications) → *Add* → **Usage Based Billing** (alerts when a product's billable
  usage passes a threshold you set – choose *Workers*, *R2* and *D1*, e.g. Workers 5 M requests / day) → email
  neuhaus.info@gmail.com → Save. Add a second one: *Add* → **Workers – Weekly Summary** (optional, for the trend). Also
  check *Billing → Billable usage* once a week for the first month after launch. (Cloud Run builds are billed in
  Google Cloud: Billing → Budgets & alerts → a monthly budget for the maps project with alerts at 50 / 90 / 100 %.)

### 18.4 Search engines
Until launch every answer of the host carries `X-Robots-Tag: noindex, nofollow` and the page has
`<meta name="robots" content="noindex, nofollow">`. **The one switch:** `INDEXING = "on"` in
`web/host/wrangler.toml` `[env.test.vars]` – the header goes and the host removes the meta tag (HTMLRewriter).

### 18.5 Owner steps to go public (step 3 done – Access is off; check the others before the launch)
1. Spending alert (18.3) – before anything else.
2. Optional: `ONPATH_MAP_KEY_TEST` (§9 step 5) so map areas can be built from the web; without it the page shows the
   overview where no detailed map exists.
3. Switch off Cloudflare Access for the planner: Zero Trust → Access → Applications → the planner app
   (`plan.onpath.earth`) → *Delete* (or Policies → change *Allow* to *Bypass – Everyone*). The `/sync/*` bypass app can
   stay. Then open `https://plan.onpath.earth/` in a private window: the page opens without the e-mail code.
4. The live smoke test's service token (§9 step 6) is no longer needed; delete it in Zero Trust → Access → Service
   credentials when Access is gone.
5. At the real launch: `INDEXING = "on"` (18.4) and push.

### 18.6 What the planner does with data – input for the privacy policy (not legal text)
**Stored in the browser (only there, never sent unless said below):**
- `localStorage`: `onpath.activity`, `onpath.sac` (last activity and T level), `onpath.hikePaceLevel`,
  `onpath.bikePaceLevel` (pace setting), `onpath.mapContent` (POI kinds hidden, contour lines / relief on), `onpath.lang`
  (language chosen with the switch), `onpath.sheetStep` (plan sheet step: small / profile / points), `onpath.browserId` (random id for the map-area quota, see below).
- `sessionStorage`: `onpath.appHintShown` (the app line was shown in this session), `onpath.viewOnce` (map view kept over the language switch reload, removed at once).
- IndexedDB `onpath` (store `kv`): saved plans (My plans) and their sync ledger, a random device id, and – once paired –
  the pairing secret (the key for the end-to-end encryption; never leaves the browser except inside the pairing QR
  code / link the user shows to their phone).
- The current plan is in the page address after `#p=` (the fragment is never sent to a server); a shared link carries
  it the same way.
- No cookies, no analytics, no tracking, no ads, no third-party scripts (fonts and libraries are served by our host).

**Requests and where they go:**
- Page, scripts, fonts, map style, sprites, world overview files: our host `plan.onpath.earth` (Cloudflare Workers).
- Map tiles of the detailed map: our host `/data/…` → our Cloudflare R2 bucket (same files the app uses).
- Routing data: our host `/segments/…` → passed through from `brouter.de` (the request to brouter.de comes from
  Cloudflare, with our user agent – brouter.de does not see the user's IP); cached by Cloudflare for a day.
- Routing itself runs in the browser (no route, point or position is sent anywhere for planning).
- Place search: typed text + the map centre rounded to 2 decimals (≈ 1 km) + `lang=de` go directly from the browser to
  **Photon by komoot** (`photon.komoot.io`, OpenStreetMap data) after 3 letters; the offline index runs in the browser.
- Google Maps: only a link the user clicks (opens google.com in a new tab with the place's name / coordinates); nothing
  from Google is loaded or drawn.
- Map areas on demand: when the user sets a point, searches, locates or plans where no detailed map exists, the page
  asks our host `/maps/v1/…` to build that area: the zoom-10 cell ids (≈ 40 × 40 km squares) + the random browser id.
  The host forwards the cell ids (with the user's IP for its per-IP limit) to our map server (the doorman Worker,
  same Cloudflare account), which queues the build. The host stores only counts (browser id / hashed IP / "all",
  day or month, number) – no cell id, no position; counters older than 2 days (day) / 1 month (month) are deleted daily.
- Position: only when the user clicks Locate or "Go here"; it stays in the browser (the map area around it may be
  requested as above).
- Sync mailbox (only after pairing a phone): our host `/sync/v1/…` → Cloudflare D1 (EU). The server sees: a random
  mailbox id, a token hash, encrypted blobs (AES-256-GCM, key only on the devices), their sizes, revision numbers and
  times of use, and the IP for the per-IP rate limit (not stored). It cannot read plans, names or device names.
  Retention: until unpaired (then deleted at once), or 12 months without use (deleted by the daily job).
- Cloudflare: processes every request (IP, user agent, URL) to deliver and protect the site (rate limits, DDoS);
  aggregate analytics in the dashboard. The planner host has no Workers Logs / Logpush enabled (only live tail while
  someone watches). **Note:** the map server (doorman, `tools/cloud/worker`) has Workers Logs on (`[observability]`)
  and keeps a per-minute rate-limit row per plain IP in its D1 (`ratelimit`, overwritten each minute, not purged) –
  for the privacy text, or hash / purge it there (follow-up for the maps session).

## 19. Terrain view – the profile linked to the map, 3D on demand (laptop, 2026-11-05, owner: "wir bauen A und C")
Options looked at (perspective views of other planners, built in the browser without a server): A big profile linked to
the map, B tilt only, C real terrain, D flyover, E route "wall", F own side view, G own 3D – the owner chose **A and C**;
D (flyover along the route) would sit on top of C. Web only for now (the app does not tilt).
Code: `web/app/src/terrainview.ts` (`RouteProfile`, `Terrain3D`), `profile.ts` (drawing, cursor, distance → point).
- **A – profile and map together.** Both profiles (the sheet's and the big one) have the app's cursor
  (`ui/ElevationProfile`): pointer / finger over the profile → a line, "375 m · 2.7 km · 14 %" (height, distance, gradient
  over ~100 m like `RouteSummary.steepness`) and the app's white dot with the route ring on the map (`profile-cursor` =
  the app's `highlight-dot`). The other way round: the pointer on the route line moves the cursor in the profiles. The ⤢
  button (in the row above the drawing, beside "300 – 578 m"; the x-axis reads "0 … 4,6 km" – owner review 2026-11-05)
  opens the **big profile** below the map (legend < 5 % · 5–10 % · 10–15 % · > 15 %, ✕); while it is open the route is
  coloured by steepness (`profile-steep` over a white edge). **One set of colours everywhere** – the app's
  `STEEP_COLOURS` (green, yellow, orange, red) in both profiles, the legend and on the map; never the route's blue
  (blue = edit). The camera fit keeps the route above the big profile (`fitPadding`). Phone: the big profile takes the sheet's place.
  Distance along the line: haversine over the legs' points (the app's `Geo.cumulative` rule behind `ProfileData`).
- **Big profile easier to find (owner 2026-11-06):** the ⤢ alone was not seen – now a labelled outlined button
  „⤢ Großes Profil“ (plan blue, 40 px, top right of the sheet's profile; while open „Profil schließen“), and a click
  anywhere on the small profile opens the big one too (cursor zoom-in, tooltip „Großes Höhenprofil öffnen“).
- **A marked stretch (owner 2026-11-06):** in the big profile press and drag (touch: hold ~0.5 s, then drag) → the stretch
  is shaded, highlighted on the map (`profile-range`, a yellow marker under the route) and its numbers stand in the
  profile's head: „1,6 km · 30 min · ↑ 59 m ↓ 0 m“. They come from the engine (`rangeStats` → `WebPace.rangeStats`):
  the time from the plan's own `RouteTimes` with the pace factors and `TimeEstimate.round` (the same model as the plan's
  total), the climb with the plan's hysteresis (`Planner.climb` / `Geo.routeClimb`, 10 m). A click (no drag) or Escape
  clears it; a new plan or closing the big profile too.
- **C – 3D.** Toolbar button **3D** ("Gelände in 3D" / "Zurück zur flachen Karte"): `map.setTerrain` on the style's
  raster-dem source `terrain` – the cells' `terrain.pmtiles` (Terrarium, z10–13), the same files as the relief: no new
  data, no server. Exaggeration 1.5, a sky, on entering tilt 62° (max 70°), bearing −20° and framed on the route like
  the fit button (`cameraForBounds` with `fitPadding`, 0.4 zoom further out – owner review: "barely tilted"), zoom ≥ 11 while on (heights exist from z10;
  further out a tile is flat 0 m and the mountains would stand on walls). **View only:** a click plans nothing, points
  cannot be dragged; one quiet line says so ("3D-Ansicht – nur zum Anschauen. Zum Planen 3D ausschalten."). Off: no
  terrain, tilt 0, `maxPitch` 0 again (turn yes, tilt no), min zoom 0. A failure goes to the log, the flat map comes back.
  While on, the terrain source's level of detail stays near the camera's zoom (`setSourceTileLodParams(1.5, 6)`), so the
  far tiles do not fall below z10.
- **Cells built before z13** (format 1 terrain ends at z12, e.g. the 2026-10-19 Meran cells): `cells.ts` used to answer a
  z13 terrain tile the file does not have with the flat 0 m tile – in 3D every near tile stood 0 m deep (walls), and the
  z13 relief was flat. Now such a tile is the parent's quarter magnified (nearest pixel, so Terrarium heights stay
  heights) – what the app's MapLibre does by the file's maxzoom.
- **Known limit:** at the edge of the loaded map area (no cell beside it) the ground drops to 0 m – a wall in 3D. Only
  more cells (or an outside height source) remove it.
- Tests: ui-smoke (cursor from the sheet's profile + dot, gone again; the labelled button, a click on the small profile opens
  the big one; a dragged stretch → numbers, shade, map line, a click clears; big profile, steepness line, pointer on the route →
  cursor; close; 3D on: terrain + tilt + note, a click adds nothing; off: flat, max tilt 0). Pictures:
  `web/app/test/terrain-shots.mjs <segments> <map store> <out> [de|en]` (Waalweg Meran → Vellau).
- **D – flyover (owner 2026-11-06).** In 3D „▶ Abfliegen“ right beside the toolbar's **3D** (greyed without a route);
  while flying ❚❚ Pause / ▶ Weiter, ■ Stopp, 1× / 2× in the same place (owner 2026-11-07: the controls where the fly
  button is – they were on the right before; below 961 px Pause / Stop show their sign only, the title keeps the word;
  at phone width the group floats just under the top bar) (`web/app/src/flyover.ts`). Time-based along the route's
  distance (~50 s per 10 km at 1×, 25 s – 3 min 20 s). **Calm** (owner 2026-11-07: „ruhiger, entspannter“): the centre
  follows a smoothed path (the route averaged over ±half the look-ahead, triangle weights, then eased ~0.7 s – hairpins
  become one soft curve), the direction runs along that path from a bit behind to 600–1600 m ahead, eased ~2.2 s and never
  faster than 20°/s; height changes eased ~2.5 s. Measured on Meran → Schenna (real map, `test/fly-calm.mjs`): camera turn
  max 105 → 30 °/s, 95 % of frames 34 → 12 °/s, mean 10 → 5 °/s; the centre's course swing max 179 → 31 °/s. Pitch 55°,
  zoom 14.2, climbing (zoom out, ≥ 11.5) when the ground behind the centre comes within 250 m of the camera. The profiles' cursor moves along. Any other camera move (drag, wheel, compass, fit) pauses;
  leaving 3D or a new route stops; Stopp frames the route in 3D again; at the end the camera stays. ui-smoke checks it.

- **Speed (owner 2026-11-06: "3D + großes Höhenprofil – langsam?").** Measured with real terrain (Meran cells, Meran →
  Schenna, the laptop's GPU, `test/perf-terrain.mjs`): every pointer move over the profile re-set the cursor's GeoJSON
  source, and in 3D each such update redraws the whole terrain; a pointer move over the 3D map also ran a hit test that
  samples the terrain per feature (nothing is clickable in 3D). Now the cursor dot is a DOM marker (`.profdot`, same look,
  no map redraw), the hit test is skipped in 3D, a dragged range is applied at most every 40 ms. Frames per second
  before → after, normal CPU / CPU slowed 4×: 3D + big profile sweep 31 → 60 / 18 → 60; flyover 27 → 59 / 10 → 18;
  3D pointer over the map 59 → 60 / 41 → 60 (main thread 770 → 118 ms/s). Flat: unchanged ~60. The flyover's rest is
  MapLibre's own 3D drawing.

## 20. My routes with length and climb, a visible New plan (laptop, 2026-11-06, owner)

Owner 2026-11-06: "Im Tour-Menü … nicht nur den Tournamen, sondern auch die Länge der Tour und hoch und runter – da ist
genug Platz" and "Wie plane ich eine neue Tour? Fehlt da ein Button?".

- **My routes rows** show `12,5 km · ↑ 842 m ↓ 310 m` (the app's Format.distance / Format.meters, as its My routes card)
  right of the name in a wide window (the dialog is 600 px wide from 701 px on), a muted line under the name on a phone.
  The sync record holds only the plan code, so the numbers come from `planstats.ts`: kept in this browser
  (localStorage `onpath.planStats`, the newest 400) whenever the page has them anyway – a plan saved or opened and routed
  here (key = a hash of its plan code, so a copy or the phone's same version has them too; only a real route: no straight
  pieces, no gaps, routing data there), a GPX track imported or opened (key = its id). The list never routes anything:
  a route this browser has not routed yet shows its name only, until it is opened once.
- **New plan** (now the plan bar's "+ Neu", §21): an outlined header button before Save, greyed on an empty map, the same
  as the plan row's former ✕ Neu planen (`newPlan()` in main.ts – unsaved changes ask first). Label from 1361 px, icon only
  below; at phone width the header has no room, so it is ☰'s first entry (`menuNewPlan`). To make room the header hint
  shows from 1761 px only, "Android-App testen" keeps its label from 1501 px, the nav labels from 1261 px, Save's from
  961 px; 701–760 px slightly tighter gaps – no header overflows from 390 to 1920 px (de and en, paired).
- Tests: parity-smoke (row numbers = the sheet's distance, right of the name; New plan active / asks / greyed; phone:
  ☰ starts with New plan), import-smoke (an imported track keeps its numbers), sheet-smoke (header entries shown),
  ui-shots (`*-myroutes.png` with numbers).

## 21. The plan bar – everything done with the open plan in one place (laptop, 2026-11-06, owner A)

Owner 2026-11-06: "alles, was ich mit einer Tour mache – speichern, neu, abbrechen, aufs Handy schicken – gehört
zusammen"; chosen layout A. The header is now: brand · **plan bar** · (hint ≥ 1501 px) · Help · the app (icon) · language.

- **Plan bar** (`#mainMenu.planbar`, one `ACTS` table in main.ts): `+ Neu` (`#newPlan`, greyed on an empty map, asks
  like ✕) · `📂 Meine Touren` (`#menuPlans`, the app's `routes_title` – owner 2026-11-06, was „Öffnen“) · `💾 Speichern ▾` (`#menuSavePlan`, always "Speichern" – ✓
  "Gespeichert" when all is saved, dark / amber as web-sync.md §14.6; paired it also sends; ▾ `#saveMore` → **Speichern
  unter …** (`saveRouteAs`: a NEW saved plan under a name – "<open name> (2)" or the proposal –, open from then on) ·
  **GPX exportieren** of the shown plan) · `📱 Aufs Handy ▾` (`#syncBadge`: not paired → the pairing window directly, and
  once paired and closed the plan follows (`pairDialog(…, then)` → Save); ▾ `#phoneMore` → the Phone window: state, Sync
  now, pair another, Unpair). **Paired** (owner 2026-11-06: everything saved goes to the phone anyway): Save carries a small
  📱 (`.savephone`, tooltip „Speichern – auch aufs Handy (‹phone›)“, `web_save_also_phone`) and Aufs Handy becomes the
  phone's status, big enough to see (owner 2026-11-07: „wichtiges Symbol, soll zeigen, wann zuletzt synchronisiert“):
  „📱 Pixel 8 🟢“ with „abgeglichen vor 2 Min.“ below (🟡 „zuletzt vor 3 Tagen“), a click opens the Phone window, no ▾;
  narrower the second line goes (< 1181 px), then the name (< 861 px) – icon and dot stay; at phone width a 📱 with the
  dot stays in the header beside Plan ▾ (`#phoneMini`, same title, opens the Phone window)).
- **Meine Touren** (was „Öffnen“) = My routes with the app's **search** (`routes_search_hint`, ✕ `search_clear_query`, none → `routes_search_none`;
  `searchPlans` = `RoutesTab.search`: every word in the name, case and accents ignored, ß = ss; the field shows once
  there is anything to search) and **GPX importieren** at its bottom left.
- **Narrow:** labels of New / Meine Touren go below 1001 px, Help's word and the phone line below 1181 px, the To phone label below
  861 px; **phone width (≤ 700 px)** the bar is one **Plan ▾** button, its drop-down lists New · Meine Touren · Import GPX · Save
  (a dot while unsaved) · Save as · Export GPX · To phone · Phone · Help · the app (paired: the phone's name + dot instead of
  To phone · Phone); "Planner" in the brand goes below 401 px.
  No header overflow from 320 to 1920 px (sheet-smoke measures 320, 390, 720, 860, 1000, 1180, 1440).
- The plan sheet keeps ⇅ Umkehren · ↻ Rundtour and Teilen; ✕ Neu planen left it (owner 2026-11-06, §14.2 point 5).
- Plans without a phone live only in this browser's storage (IndexedDB / localStorage, no cookie) – a private window or
  cleared site data loses them; pairing moves them to the phone (web-sync.md §14.6 rule 2).
- Tests: sync-smoke 16 (the owner's two scenarios: first visit → Save → reload → Open lists it → To phone pairs and the
  plan arrives; second visit → Open → search → open → change → Save sends; Save ▾ → Save as), sheet-smoke (bar order,
  labels, widths, Plan ▾), parity-smoke (Plan ▾ entries), import-smoke (Open → Import GPX), lang-smoke (German bar),
  ui-shots (`*-myroutes-search.png`, `desktop-savemenu.png`, `phone-menu.png`).

## 22. Lessons (app ⇄ web, TeaVM / Wasm)

Problems found while making the browser plan exactly like the phone, and the special solutions. They replace the old
`docs/handoff-web-*.md` notes (git history has the full texts).

- **Shared code must be pure.** A file the engine compiles (`app/web-shared-files.txt`) uses no Android, no reflection,
  no `java.security` – `SharedCoreRuleTest.webShared` / `SharedFilesRuleTest` enforce it. Rules that sat inside
  Android / Compose files were moved into their own pure files and the app only draws them (e.g.
  `data/RoutingDataNotice.kt` out of `RoutingDataLoader`, `routing/BreakdownRules.kt` out of `ui/RouteBreakdown.kt`,
  `routing/PlanResult.kt` = one plan computation for sheet and browser, `gpx/PlanGpx`, `map/ActivityLayers`). Tests
  proved "no behaviour change" against a frozen copy of the old code. A test that reads `app/` assets by path
  (`RoutingFingerprintTest`) cannot run in the web engine – the app's CI runs it.
- **Island retry without reflection (Köln Dom).** A point snapped onto an "island" way is snapped again by
  `BRouterEngine.search`; that needs BRouter's `private` field `RoutingEngine.islandNodePairs`. The app read it by
  reflection; **TeaVM has no reflection**, so the browser drew a straight line (959 m vs the app's 1334 m). Fix: one
  byte in the jar – `tools/routing/patch_brouter_jar.py` clears the field's `private` flag in brouter-core's
  `RoutingEngine.class` (access_flags 0x0002 → 0x0000, nothing else changes; `build_brouter_jars.sh` runs it and
  `--check`s), the jars are named `brouter-*-1.7.11-beta-onpath1.jar` (a modified BRouter says so; MIT), and
  `btools/router/OnPathHints.java` (BRouter's package) gives `islandRetries` / `skipOnSnap`. App routes were diffed
  unchanged; `btools/router/OnPathHintsTest.islandListWithoutReflection` guards it.
- **Bug-01 splice only around the cut stop (owner "Option 1", 2026-10-31; PLANNER_VERSION 3).** Adding a point to the
  owner's 70.6 km hike took ~5 s in the browser: when BRouter's via correction cut a stop, the Bug-01 pass routed the
  whole chunk again. The fix routed only A → cut stop(s) → B (A / B up to 1 km before / after the stop on the first
  line) and spliced it in, with the old pass as fallback: JVM 2.7 → 1.3 s, browser 5 s → 2.5 s (live 2.2 s), length
  +1 m. **Superseded 2026-11-05** by leg-by-leg planning with a leg cache (`Planner`, PLANNER_VERSION 4): every leg is
  its own request, spurs at leg joins are trimmed the way BRouter's via correction did, a point on a spur stays visited.
  The lesson stays: route only what changed, and same points still give the same route (`SpurSpliceJvmTest.kt`,
  `SpurStopJvmTest.kt`).
- **Short-trip detour retry (Hilden, owner option B).** A leg under 2 km straight that BRouter rode more than twice as
  long is retried once via the midpoint of the straight line and taken when ≥ 30 % shorter (`Planner.retryShortDetour`)
  – no profile change, so no router-class hash change. Hilden 2054 → 1099 m. `PlannerShortDetourTest`, `ShortDetourTest.kt`.
- **Fingerprint bumps are expected.** Every `PLANNER_VERSION` / `BROUTER_VERSION` change changes all `r1-…` fingerprints
  once, so plans saved before may show the quiet "planned with other routing" line once (`docs/design/routing-fingerprint.md`).
- **TeaVM gaps and their web-only stand-ins** (the app is unchanged): `java.text.Normalizer` (NFD table), the `\p{N}`
  regex class (TeaVM's `TAbstractCharClass` shadowed), Gson `Streams`, `AtomicIntegerArray`, no XML parser
  (`javax.xml` / SAX mapped to a small stand-in, `WebImportTest`), java.time via ThreeTen backport, only `en_US` /
  `de_DE` number data, single-thread `ReentrantLock`, `java.lang.Package` getters, no-op BRouter `StackSampler` (§7, §10,
  §15, §18.1).
- **Node never exits after loading the TeaVM Wasm engine** – run engine tests in a child process.
- **Engine loaded twice** on early calls lost rd5 parts stored in the other copy – the worker keeps one loading promise
  (§11.2).
- **pmtiles / maplibre keep a failure for good** – one 429 cost a cell's streets for the whole visit; the page drops the
  archive and retries with backoff (§17).
- **Flaky web smokes were test-side camera races** (the whole-route fit moved the map between the test's projection and
  its clicks): drag the map first like a person, jump without padding, log the camera when a click goes astray.

## 23. The app's colour rule, paper and „Zuletzt“ (laptop, 2026-11-07 – parity with main's 2026-11-06 app)
- **Colours** (`style.css` `:root`, the app's `Theme.kt` / `ActionButtons`, onpath.private#36): walnut `#4A3F37` for every
  button without a meaning of its own (the old `--green*` tokens point to it), plan blue `#2E6A9E` for plan actions (Save,
  the place card's Add / As start / As destination, Plan anyway, the wait for a plan, „⤢ Großes Profil“, the plan row's
  switches, spinners while a route is calculated – `Message.tone: 'edit'` = the app's `ButtonTone.EDIT`), the navigation
  green only for the brand's word (the web never navigates). Every surface is paper `#FAFAF6`, also what floats over the
  map (`--card`: search pill, results, place / pin card, message boxes, the map strip, the fly buttons – the shadow lifts
  them off the map; app 2026-11-06 evening); white only for fields one types into. A lifted point row is `#EAE9E1`.
- A GPX track without a line (< 2 points or < 20 m, the app's `TrackLine.has`): one quiet line „plan_no_track_recorded“
  instead of the profile, no „Plan aus dem Track“.
- **„Zuletzt“** (`recent.ts`, the app's drafts – `RouteRules.kt Drafts`, `AppViewModel.keepAsDraft`): no „Plan speichern?“
  any more. A plan with ≥ 2 points that leaves the map not saved as it is (+ Neu, another plan / link / GPX opened, „Plan
  aus dem Track“) is kept under Meine Touren → ZULETZT, named like the Save proposal; same points twice = one entry; the
  newest 25; this browser only (the app's drafts are not synced either). ZULETZT shows the newest 3 + „Alle N anzeigen ›“,
  a pale route sign, Speichern (blue) · Löschen (red, no question) on the card; GESPEICHERT below. A plan opened from
  Zuletzt still belongs to its entry; saved, it leaves Zuletzt and shows „✓ Gerade gespeichert“.
- **Delete** in Meine Touren: in a phone's folder at once (the phone's recycle bin is the net – the app asks nothing);
  without a phone it still asks, since this browser has no recycle bin.
- Phone-only in the same app round (nothing to do here): plan auto-saved at „Los geht's“ (`PlanAutoSave`), the end
  sheet without the plan switch, „Weiter“ wording, the tour's T1–T5 sheet, „Ohne Plan aufzeichnen“, Plan in front after
  the app start (the web fits a restored plan anyway), the map column always 6 high (the web's toolbar was already
  greyed-not-hidden); the second app round of the evening: a plan / tour tapped during a tour is followed without a
  question, the hold-time slider instead of the lock switch, the end sheet's tap guard, the overview pill with „Detailkarte
  laden“ (the web loads map areas by itself, §17; its strip keeps its own overview line).
- **Second app round (2026-11-07, parity of 17 mirrors):** ink blue `#335A85` (app 2026-11-07; first `#2B4C6F`) for plan actions (variant C: no light blue
  surfaces; tonal `#DDE5EE` / ink `#1E3550` also for tags), warm walnut shadows (`--shadow-low` / `--shadow-high`), one corner
  family (cards 20 px, small 12 px, pills round), the compass ink blue / walnut grey (no red), the goal as a white flag on the
  map, in the points list and the point menu, locate without the GPS dot and greyed without a position (the app's PlanGps),
  the pin card's „Mehr“ on PaperDim. Meine Touren's „In Bearbeitung“ row, the route previews and RouteReveal: §25 (no large overview, owner 2026-11-13). Not taken: the
  profile's fill (ProfileFill) – the planner keeps its steepness bars (owner 2026-11-05). Paper tone B `#F5F3EB` for
  surfaces, `#FAFAF6` for cards in lists (CardPaper). Phone-only: Navigate's ⚙ settings, lock line,
  tour controller / RoutesState refactors, accuracy circle only in Navigate, Prefs.


## 24. Plan sheet A – points first, the profile along the bottom (laptop, 2026-11-07, owner: A + B's profile)
Proposals page https://claude.ai/artifact/F1rCK7xXXmUhWcQRj3o1mR (today's sheet, three proposals, „Aus der App übernehmen“;
section „Gebaut“ with the shots). The owner chose A („Punkte zuerst“, like Google Maps' route panel) with B's profile.
- **Wide (> 700 px): no steps.** The side panel always shows, in the phone's order (owner after the first look, 2026-11-07:
  „das Wichtigste zuerst“): activity chip · Teilen; the facts in one row (time · km · ↑ ↓ · max · difficulty, tabular
  digits); the plan row ⇅ Umkehren · ↻ Rundtour; the route's lines (warnings, notices); then the points with ⋮⋮ handles
  (hover = plan tonal), the goal as a flag, and **„+ Ziel hinzufügen“** (puts the cursor in the search – the place card's
  „Hinzufügen“ adds it); Wegtypen & Untergrund (folded). No handle, no chevron (`sheetstep.ts`).
- **The elevation profile lies along the map's bottom edge for good** (`RouteProfile.pinned`): cursor ⇄ dot, steepness
  colours, the marked range („1,6 km · 30 min · ↑ 59 m“) – no ✕, no „⤢ Großes Profil“. The route on the map stays plan blue
  while pinned (the steepness is read in the profile). The camera fit keeps the route above it (`fitPadding`).
- **Phone: two steps.** small = head + facts + lines, the chevron says „2 Punkte ⌃“; open = everything, the small profile
  with a full-width „⤢ Höhenprofil groß“ (the big one as before). The step is remembered (`onpath.sheetStep`, an old
  'profile' = open). A click on a point row on the phone folds the sheet (map and point visible).
- **A shared link on a phone (owner 2026-11-13 field report):** the person who gets `#p=…` sees the route first. The
  sheet opens *small* (the peek: the link's name or length · ↑ ↓, the activity, Share, „2 Punkte ⌃“; not remembered,
  the handle pulls it up); the app hint waits in the open sheet. While the first route loads the one wait is the load
  overlay at the top of the map with its percent (§27, owner 2026-11-13 – until then the peek's line); no wait box over
  the map, no second line; then the route is fitted above the peek. Wide screens and a plain visit unchanged. Test:
  `test/phonelink-smoke.mjs` (check-web.sh; not yet in web.yml – a workflow change needs a pull request).
- **Owner's second look (2026-11-07 noon):** „Routenlänge und Höhen ↑/↓ als oberstes“, „großes Profil prominent“, „beim Start
  Punkte und Profil zeigen, man kann ihn zuklappen“. Now, at every width: the first line of the sheet is **length · ↑ · ↓**
  (24 px), below it the time, max and the difficulty – above the activity chip. The sheet is **open on start** (points and
  profile), the chevron (wide) / handle + chevron (phone) collapse it to the facts; remembered in `onpath.sheetStep`,
  nothing stored = open. Wide: the bottom profile has a **✕** that hides it (remembered, `onpath.profileHidden`); then the
  panel shows the small profile with an outlined „⤢ Großes Profil“ (≥ 44 px) that brings it back. Phone: the full-width
  „⤢ Höhenprofil groß“ row (44 px) under the facts in the open sheet.
- Tests: sheet-smoke (wide order points → + Ziel → facts, pinned profile, blue route, + Ziel → search; phone two steps,
  label „2 points“, keys), ui-smoke (the bottom profile's cursor and range), edit / import / pair / ui shots without steps
  on wide. Shots: `test/sheet-a-shots.mjs <segments> <cellStore> <outDir>`.

## 25. Meine Touren like the app (laptop, 2026-11-07, owner: „ja“ – app 01b38ffa / cb791053 / ce27c111 / e570e467 / ddc8d59b / 4cf68bd7)
- **Rows** (`plans.ts` `myPlansDialog`, `rowActions` = the app's `RouteRowActions`): cards with a 52 px **preview tile**
  (`routeshape.ts` = the app's `RoutePreview` / `RouteShape`: the line on PaperDim, start dot, goal in dark red, no ⤢
  badge; without a known line the kind's sign; a tap on it opens the plan like the row), the name, „Plan · 7. Nov. 2026“ / „Importierte GPX ·
  …“ (the app's words), the facts. ZULETZT: the plan on the map first (`routes_live_name` / its own name, tag
  `routes_tag_live` „In Bearbeitung“, Speichern on the card when it can be saved, a tap goes back to it, no ⋮; a Zuletzt
  entry with its points is not shown twice), then the drafts with **Planen · Speichern · Löschen** on the card and ⋮
  Umbenennen · GPX exportieren. GESPEICHERT: the clean row – tile, name, facts, 🖥 / 📱, ⋮, › – ⋮ = Planen · Umbenennen ·
  Als Kopie speichern · GPX exportieren · Plan teilen · Löschen (a track: Als Plan übernehmen instead of Planen / Kopie /
  Teilen); the saved route that is the plan on the map carries „In Bearbeitung“ instead of › and its tap goes back to it.
  „Los geht's“ is the phone's (no tour here). A route added on top scrolls into view.
- **The line** is kept in this browser like the numbers (planstats.ts): `keepShape(planKey(code) | trackKey(id))` when a
  plan is routed here (saved, opened, kept under Zuletzt) or a track is read – at most 200 points, 12 + 12 bits each in
  the line's box, the newest 300 (localStorage `onpath.planShapes`). The list never routes anything.
- **No large overview** (owner 2026-11-13: „das brauchen wir im Browser nicht, weil wir direkt in die Route springen“):
  the app's `RouteOverview` is not mirrored. A row or its tile opens the plan at once; the browser has the room to show
  it on the map itself.
- **The route draws itself** (`routereveal.ts` = the app's `RouteReveal`): a new route for the plan runs from start to goal
  in 0.5 s (decelerating), not the first route after the page loaded, not the same route again, not over 4 000 points, not
  with „reduce motion“; automated browsers only with `window.__reveal` (import-smoke checks it).
- Tests: import-smoke (live row, draft row + menu, the tile opens the plan, the reveal, the saved row's ⋮), sync-smoke
  (the app's words, „In Bearbeitung“, a row tap opens at once), `test/tours-shots.mjs` (shots wide + phone, before / after).


## 26. A dropped long plan never holds the planner (laptop, 2026-11-07)
BRouter runs in one engine worker and cannot be interrupted mid-call; the worker answers in order. A long plan the page
has dropped (a newer edit, „+ Neu“, a loaded plan, Abbrechen – the app's cancelRun) used to keep routing for minutes
(Meran → Hilden with NRW data) and every later call – Neu, rows, the next plan – waited behind it.
Now (`engine/client.ts` `dropPlans` / `restart`): a dropped plan still waiting for routing data is skipped by the worker
(`drop` message); one that has been routing ≥ 1 s (`DROP_GRACE_MS`, at once if it already ran that long) is stopped by
terminating the worker and starting a fresh one. The fresh worker gets the config, the pace levels and the undo / redo
history first (`historyExport` → `historyImport`, the app's `PlanHistory.snapshot` / `restore`; every history call's
reply carries the history), then every other open request again in its order; the dropped plan answers `{cancelled}`,
which the page ignores like any superseded plan. Routing data is read again from the browser's cache; the leg cache
fills again with the next plan. Measured on the laptop (Chrome, Meran → Hilden routing, then „+ Neu“): map empty
after ~120 ms, fresh worker ready in ~70 ms, a short plan right after in ~0.7 s. `test/stop-smoke.mjs` (needs E10_N45 +
E5_N50; skips otherwise – in `web/parity/check-web.sh`, not in GitHub's smokes: no NRW data there).
Also (owner 2026-11-07, app f37793d0 `RouteWarnings.saysChanged`): the user's own activity / T change in Plan says no
„New route for … – check it“ – in the planner the change is always the user's own, so the line never shows.

## 27. The load overlay – the map of a new place loading, in percent (laptop, 2026-11-13)
Owner 2026-11-13: „Das wird der Normalfall sein: man ist im Urlaub und plant für einen neuen Ort, sucht ihn – oder bekommt
einen Link von einem Freund und war da noch nie. Die Meldung, dass die Karte noch geladen wird, sollte als Overlay
erscheinen, mit einer prozentualen Fortschrittsangabe.“

**What it is** (`loadoverlay.ts`, drawn by `mapnotice.ts` in the strip's place – slot `load`): one card at the top of
the map (phone: under the search bar, full width; desktop: beside the side panel) – the line, a bar and the percent
(„Karte für deine Gegend wird geladen ███░░ 43 %“), Cancel while a plan waits in it. It comes 400 ms after a wait began
(a short wait shows nothing), when the map tiles of the view, a plan's or a link's routing data or a map area the server
prepares are still loading; the engine starting and the route calculation only *join* a card that is up (the
calculation alone keeps the wait box, as before). The route calculation is its last phase („Route wird berechnet…“,
the engine's „Leg 2 of 5…“). It goes by itself: the full bar for a moment, then away. A failure shows the existing plain
lines (plan failed, build failed – the strip's red line), no new error. **One wait at a time** (owner 2026-11-13: overlapping waits are no detail – „das irritiert den
Benutzer“): one arbiter (`onewait.ts`) decides which wait shows, desktop and phone. Each of the five only says whether
it *wants* to show – the card (`loadoverlay.ts`), the route's wait box (`msgcard.ts` `onShown`), a busy strip
(`mapnotice.ts`: position, „Preparing map …“), the sheet's wait line (while a plan runs), the engine's status line –
and the arbiter writes the one that shows to `body[data-wait]`, by priority card > box > strip > sheet > status line;
`style.css` hides the others in the same task (no frame with two), and of several busy strips only the first shows.
While the card's 400 ms delay runs and two other waits want to show, the card comes at once (it carries them: the
area's rows are its lines, the calculation its last phase); with one other wait that one shows until the card takes
over. The paths found 2026-11-13 (the field run's „box + strip“): a point's map area asked for → the strip's
„Preparing map … Your point · waiting“ at once, while the box (or, on a phone with a link, the sheet's line) already
said „Calculating the route…“ and the card was still in its 400 ms delay – or never came (a plain recalculation without
download, routing data prefetched); a host without map service (the strip until its late 404); Cancel and a failed
download while an area is prepared (the box and the strip side by side until the card). On a phone a shared link
keeps today's peek (§24): the sheet stays collapsed, the card is over the map.

**What is measured** (one percent, weighted; it never goes back and is below 100 % until all is there):

| Part | How | Weight |
| --- | --- | --- |
| Routing data under a plan | bytes received / bytes needed – the sizes come from the rd5 files' own 200-byte index (exact), the bytes are counted as the range answers stream in (`worker.ts` `ensureParts` → progress `{download, bytes, done}`); a part the prefetch is fetching counts too | bytes |
| Map tiles of the view | tiles seen loading → loaded (or failed, or no longer in view); maplibre's tile managers (`_inViewTiles`, no public API; without it: loading / loaded). A pmtiles tile's size is not known before it comes | 40 kB per tile |
| Map area being prepared (§17) | the doorman's own `progress` per area (0 while queued, 1 ready / failed) | 20 MB per area – it takes minutes, it outweighs the downloads |
| Engine starting, a link before its routing data size is known, the route calculation | not measurable (TeaVM loads the Wasm itself; the router says no fraction) – a fixed share, done in one step | 3 MB, 4 MB (replaced by the real bytes once known), 1 MB |

When work is added while the card is up (a new part, the route's map areas, tiles after the route is fitted) the bar
does not jump back: the part still to go is shared by all open work from that moment (`LoadProgress` rebase). Nothing
sits at 99 % for ever: every part ends – downloaded, failed (counted as done; the plain error line says it) or dropped
(Cancel, a new plan). Percent in the page's language (`Intl.NumberFormat`: „43 %“ / „43%“). No new strings: the line is
`maps_load_loading`, `plan_calculating`, the area strip's `maps_card_preparing*`; „· about 10 MB“ is no longer shown
(the percent says it better).

Tests: `test/phonelink-smoke.mjs` (held-back then slow routing data, phone + desktop: the card appears, percent rises in
steps to 100 and never back, the card goes, never more than one wait visible – sampled every 50 ms; part 6: a fake
doorman holds the area „preparing“ (or the host answers a late 404) while the route waits – a link at start and a link
into an open page, the whole load, Cancel, a failed download, phone and desktop, sampled every 16 ms: red before the
arbiter in every run, green 5× after – the sheet stays
collapsed on the phone, Cancel in the card stops the plan; red before, green after; `phonelink-smoke.mjs <seg> - <dir>`
writes `phone-link-loading.png` / `desktop-link-loading.png`); `areas-smoke` D (the card's percent while the routing data
comes), `robust-smoke` 7 (the card's Cancel). §17's table: „Map tiles still loading“, „Routing data for a plan“ and
„Map area being built“ are this card now.

## 28. Zuletzt = the app's one-plan rule, a Papierkorb for this browser's drafts (laptop, 2026-11-13 – app 2923a3e7 / 6f4b47d3)
Replaces §23's „newest 25 / newest 3“ and §25's „In Bearbeitung on the saved row“.
- **Exactly one plan under ZULETZT – the one worked on last, saved or not** (`recent.ts`; the app's `Drafts.MAX = 1`,
  `RoutesState.keepDraft / takePlace / afterSaved / restore / trimDrafts`, `LivePlanRows.of` = `recentRow`). The plan on
  the map is always that row („In Bearbeitung“; a saved route as it is also „Gespeichert“, no Speichern; changed or new:
  Speichern); its saved route stays under GESPEICHERT as a plain row (›, ⋮ with Planen). An empty map: what holds the place
  (`onpath.zuletzt`, survives a reload = the app's `prefs.zuletztId`) – a draft (Planen · Speichern · Löschen), or a saved
  route left as it is (its clean row with the „Gespeichert“ tag, also under Gespeichert; deleted → no row) – but a draft
  outside the bin always wins over a saved route, and the newest draft over an older pointed one (a draft never hides).
- **Taking the place sends every other draft to the Papierkorb** – never gone for good. Saved from Zuletzt (or the plan
  from it saved): the draft and any draft with its points go (the saved route holds it), the saved route takes the place.
  A saved route left as it is but deleted meanwhile → kept as a new draft. Löschen on the draft → Papierkorb, no question.
  Once per browser (`onpath.zuletztTrimmed`) the older planner's drafts beyond the newest go to the bin (one INFO line).
- **Papierkorb** (the app's `RecycleBinScreen`, its words: `common_recycle_bin`, `backup_bin_keep`, `backup_bin_deleted`
  + `backup_bin_days_left` / `backup_bin_removed_next`, `common_restore`, `common_back`): a button beside „GPX
  importieren“ while it holds a plan (the app: My routes ⋮ → Papierkorb). The list replaces Meine Touren's in the same
  window: the keep line, per plan its name, „Plan · date · km“, „Gelöscht am … · noch N Tage“, Wiederherstellen (it takes
  the Zuletzt place, the draft there goes in). 30 days, then a page start removes it. Only this browser's drafts – saved
  routes deleted without a phone still ask first (§23); no „Jetzt löschen“ (the app's question speaks of the phone and its
  backup folder). Stored in `onpath.recent` (`deletedAt`), at most 400 entries (the oldest bin entries beyond, reported).
- Tests: `test/zuletzt-smoke.mjs` (recent.ts in Node + the page: trim, bin, restore, + Neu, Löschen, saved place over a
  reload, a draft never hides, nothing lost, EN + DE words; check-web.sh), sync-smoke / parity-smoke (the open saved route
  = the Recent row „In progress“ + „Saved“).
- **Phone sheet (same day, field: a two-point link plan listed only „1 Start“ + „+ Ziel hinzufügen“):** at the points step
  the sheet taller than the screen squeezed `#pointsSection` to its 96 px minimum – one row, the goal hidden inside the
  list. On a phone the open sheet now scrolls as one and the list keeps every row (`style.css`, end). Test:
  phonelink-smoke 2b (412 × 760 / 640, DE) and 1 (every row inside the list and the sheet).
