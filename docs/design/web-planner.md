# Web planner – handover laptop → cloud (2026-10-23)

Status: **idea + proposal, nothing built.** Owner hands this to the cloud session (start 2026-10-24).
Owner's wish: a web version for planning a route online, built **with as much of one codebase as possible**.
Purpose: asked 2026-10-23, owner had no preference → **A** (plan at home, send to phone), built so B can grow.
**Architecture rethought 2026-10-23 (owner: "rethink – consistency, low risk, low complexity, elegance, modern") → §6.**

## 1. Why one codebase is realistic

The app already separates "what to do" from "how the phone does it":
- **Pure Kotlin logic**, tested on the JVM: planning (`routing/Planner`, `WaypointInsertion`, `PlanEdits`), route
  summary / warnings (`RouteSummary`, `ui/RouteWarnings`, `ui/PlanGaps`), time estimate (`time/…`), GPX
  (`gpx/…`), route names (`search/RouteNamer`), settings summaries.
- **Routing behind an interface** (`routing/RoutingContract.kt`, `RoutingEngine`).
- **The map is a style + PMTiles files.** maplibre-gl JS reads the same style JSON and the same area files (we
  already render the style with maplibre-gl JS in headless Chrome for checks). Keep one style valid for both
  (lesson 2026-10-22: Native rejected zoom-interpolated hillshade colours – `StyleFormatTest` guards Native).

So: **Kotlin Multiplatform** – the same Kotlin compiled for Android and for the browser (Kotlin/Wasm).

| Part | Android | Web | Shared |
|---|---|---|---|
| Planning logic, warnings, time, GPX, names | Kotlin | same Kotlin → Wasm | yes |
| UI (plan sheet, points, profile, settings) | Jetpack Compose | Compose Multiplatform for Web (wasmJs) | largely; desktop layout differs |
| Map | MapLibre Native | maplibre-gl JS | same style + files, small adapter each |
| Map data | areas from R2 via Worker | same (needs a web path: CORS, ideally z/x/y tiles served from the area files) | yes |
| **Routing** | BRouter (Java) on the phone | **BRouter compiled to Wasm with TeaVM, running in the browser**, rd5 1° parts via HTTP range (as the phone does: `routing/Rd5Parts.kt`) | same engine + same profiles = identical routes |
| Storage | SQLite | IndexedDB / OPFS | adapter |
| GPS, recording, vibration | phone | – (web = planning) | phone only |

**Key idea:** routing in the browser means **no routing server** – no compute cost, nothing to abuse, the route is
computed on the user's computer, the server only sees which areas are needed. Same privacy story as the app
("everything on your device"); the web can even keep areas in OPFS and work offline.

**Browser → phone without an account:** the plan's points in a link / QR code; scanning or tapping it opens the plan
in OnPath. Nothing stored on a server.

## 2. Purpose – to decide with the owner first

- **A. Plan at home on a big screen, send to the phone** (laptop recommendation; what Komoot users know). Map,
  points, route, profile, warnings, activity + T level, link/QR to the phone.
- **B. Full planner for people without Android** (iPhone / desktop): plus saving routes in the browser, GPX
  import/export. Build A so B can grow from it.
- C. Navigation/recording in the browser: not sensible (weak GPS in browsers, stops in background, no vibration).

## 3. Risks

1. **BRouter → Wasm (TeaVM, Apache-2.0):** BRouter reads files via `RandomAccessFile` → adapter to an HTTP range
   reader. Biggest unknown. (Alternative: a BRouter server on Cloud Run – proven, but costs compute and needs abuse
   protection; BRouter-Web at brouter.de works this way.)
2. **Compose for Web** is younger than Android Compose: text, accessibility, first download (several MB). The map
   must sit underneath as a DOM element with Compose on top. If it feels wrong: TypeScript UI on top of shared
   Kotlin logic + Wasm BRouter (still most of the value).
3. **Big screen ≠ phone:** side panel instead of the sheet – design work.
4. **Style differences Native vs JS:** one style for both, guarded by tests.
5. **Map server access:** a browser app exposes the app key completely → see `docs/design/map-access.md`
   (anonymous quota, supporter code). Decide together.
6. Side effect: shared logic/UI would also carry an iOS app later (routing on iOS stays the hard part; no Java).

## 4. Proposed steps

0. Ask the owner the purpose (§2), one question at a time.
1. **Two spikes in parallel, 1–2 days each, on a separate branch (nothing in main, app unchanged):**
   - **Spike 1 – BRouter in the browser:** TeaVM → Wasm, range-reader adapter for rd5 1° parts, run 10–20 real
     routes from `tools/routing/jvm-test` in the browser and compare with the JVM/phone result (same line, same
     length); measure Wasm size, load time, route time.
   - **Spike 2 – shared UI on the web:** Compose Multiplatform wasmJs mini page with maplibre-gl JS underneath:
     set points (click), call the shared planning logic, draw route + elevation profile. Judge feel, size, speed.
2. Short report to the owner with numbers and a recommendation (full Compose web vs TypeScript UI + shared logic).
3. If yes: move the pure logic into a KMP `shared` module first (worth it anyway; no user-visible change), then a
   minimal web planner for purpose A.

## 5. Owner context to keep

- Owner principles: robust first, offline by design, everything on the device, simple; no account, no tracking.
- Public texts factual (see laptop memory "public text tone": like telling a friend, no marketing).
- Ask the owner one question at a time (AskUserQuestion), with a recommendation.
- Laptop load / builds: cloud session builds in its own environment.

## 6. Rethink (cloud, 2026-10-23) – "same bytecode" instead of KMP + Compose web

Owner asked, before anything is built: is there a way with more code consistency, less risk, less complexity?

**Facts found:** the app's Android-free Kotlin is large already – routing 16 of 18 files, time 9/9, gpx 3/3,
navigation 9/9 have no Android import (~7,000 lines). BRouter touches files in only 3 classes
(`OsmFile`, `PhysicalFile`, `NodesCache` – `RandomAccessFile`), threads in 2, reflection in 2. The phone already
builds its own rd5 from 1° parts (`Rd5Parts`).

**What must be identical between phone and web:** routes (engine + profiles + data), distance / ascent / time /
warnings, the plan link format. **What need not be identical:** the UI – a desktop side panel is a different layout
from the phone sheet anyway.

| | 1. Handover plan: KMP + Compose web + TeaVM BRouter | **2. Same bytecode (new proposal)** | 3. BRouter server |
|---|---|---|---|
| How | Move logic + UI into a KMP module (Kotlin/Wasm), BRouter via TeaVM | **TeaVM compiles the existing JVM classes – BRouter jars + the app's Android-free Kotlin, unchanged – into one Wasm module in a Web Worker.** Small TypeScript page + maplibre-gl JS on top (same style, same PMTiles). | Routing on Cloud Run, page asks it |
| Consistency | high (same source) | **highest: the very same compiled classes from the same commit; CI compares golden routes JVM ⇄ browser** | routes yes, logic duplicated in JS |
| Change to the app | large (KMP refactor of main, Compose UI split) | **none** (web build reads `app/src/main/java/...` files; app untouched) | none |
| Toolchains | Kotlin/Wasm + Compose web + TeaVM (two Wasm compilers) | **one: TeaVM** (+ plain TS / Vite) | server + JS |
| Risk | Compose web young (canvas UI: text, accessibility, several MB), KMP move | TeaVM support of the JDK / Kotlin-stdlib calls used → found in a 1-day spike; gaps get small shims | cost, abuse protection, no offline, not "on your device" |
| Modern | yes | yes: Wasm GC, Web Worker, OPFS cache, PWA offline | – |

**Recommendation: 2.** One spike instead of two: (a) TeaVM → Wasm for BRouter + `Planner` / `RouteSummary` /
time / GPX / plan link, a range-reader that fills an in-memory rd5 like `Rd5Parts`; (b) 15–20 routes of
`tools/routing/jvm-test` in headless Chromium vs the JVM (same line, same length); (c) measure Wasm size, load time,
route time; (d) a tiny TS + maplibre-gl page (click points → route + profile). Fallback if TeaVM cannot take the
Kotlin parts: BRouter via TeaVM only, logic via KMP later (option 1's logic half). Option 1's KMP module stays
possible later (e.g. for iOS) – nothing in option 2 blocks it. GraalVM Web Image (JVM → Wasm) is the newer
alternative to TeaVM but still experimental – watch, not use.

## 7. Spike result (cloud, 2026-10-23) – owner approved option 2, built on `web-spike`

**What exists** (`web/`, nothing in `app/` changed):
- `web/engine/` – separate Gradle build. Compiles, straight from `app/src/main/java` (no copy): `BRouterEngine`,
  `Rd5Parts`, `Planner`, `WaypointInsertion`, `PlanEdits`, `RouteSummary`, `RouteTimes`, `PaceModel`, `Geo`,
  turn/junction helpers + the BRouter jars of `app/libs`. TeaVM 0.16 → one Wasm GC module. `WebApi.kt` is the
  only API (init, putLayout/putPart for rd5 1° parts, route, plan, insertIndex). Web-only stand-ins: `Problems`,
  `UserText` (routing part), and three tiny class replacements: single-thread `ReentrantLock`,
  `java.lang.Package` manifest getters (BRouter's GPX header version), no-op BRouter debug `StackSampler`.
- `web/app/` – Vite + TypeScript + maplibre-gl 6 + pmtiles. Engine in a Web Worker; fetches profiles and the rd5
  parts the plan needs (part choice = the phone's `Rd5Parts.forCorridor`) by HTTP range (falls back to a whole file
  on hosts without ranges). Planner page: click → point (WaypointInsertion), drag / click to move / delete,
  activity + T level, route (failed legs dashed), km / ↑ / ↓ / time / difficulty, profile coloured by
  `RouteSummary.steepness`. Map = the app's format-2 style, fonts and sprite unchanged.
- Tests: `web/test/cases.json` (20 cases: Bozen–Meran × 6 activities, Algund, Meran 2000 T2/T3/T5, via Terlan,
  Ultental loop, Lana–Ulten across 11°E, ferry, 3 error cases). `./gradlew -p web/engine runJvm` (same Engine on
  the JVM, full rd5) vs `web/app/test/browser-compare.mjs` (headless Chromium, partial rd5 by range).
  `web/app/test/ui-smoke.mjs` clicks two points. `.github/workflows/web-spike.yml` runs all on the branch.

**Numbers** (cloud container; CI green):

| | |
|---|---|
| Same result browser ⇄ JVM | **20/20** – same line (max deviation 0.00 m, same point count), length, ascent, descent, time, error texts |
| Engine size | Wasm 1.52 MB, **~0.48 MB gzip**; TeaVM runtime JS 17 KB |
| Page (maplibre + UI) | 1.08 MB JS, 294 KB gzip |
| Engine start | ~130–160 ms (compile + profiles) |
| Route time | like the JVM: 30 km bike 0.2–0.9 s, hikes 30–50 ms |
| Routing data | per 1° part 5–13 MB, once per area (Meran part 9.7 MB, ~0.15 s locally) |

**Findings / open points**
1. brouter.de sends no CORS header → the web needs our own copy of the rd5 files (R2 via the Worker, with CORS +
   Range) – fits map-hosting; access without the app key: map-access.md (decide with the owner before public).
2. `BRouterEngine.matchedPoints` reads BRouter's protected field by reflection – not available in TeaVM, so the web
   gets "no snapped points" (route itself identical). Small app change later: read it in `OnPathHints.java`
   (package `btools.router`) like the turn hints – no reflection on the phone either.
3. Small pure helpers live in UI files (e.g. `ui/ElevationProfile.profileData`) – the web mirrors 8 lines. When
   the web planner is built for real, move such helpers to pure files so both use them.
4. Not done in the spike: plan link / QR to the phone (needs a link format the app opens), search, POIs, saving.
5. Compose for web was not tried (option 1 dropped by the owner's choice) – the TS page is ~250 lines.

**Preview (private claude.ai artifact, 2026-10-23):** https://claude.ai/artifact/QTG6zEiwfTpA5BRpP4beKm – Meran cell
10/543/361 (format 2, built in the cloud container with `tools/cells/build_cell.sh`) + the E11_N46 rd5 part only
(Meran, Algund, Schenna, Lana; Bozen is outside the map cell but routable). The preview host serves only common web
types, so binaries are published as base64 text and decoded in the browser (`VITE_PACKED=1` build, `src/packed.ts`);
the normal build reads the files directly. Not tested inside the claude.ai frame by the cloud (only headless).

**Status 2026-10-24: paused by the owner after the spike report.** Restarted 2026-10-26 with phase 1 – see §8.

**Owner TODO (2026-10-24):** delete the GitHub branch `marketing` (GitHub → Branches → trash icon); it holds
nothing any more – marketing stays in the chat, not on GitHub.

**Next (if the owner says yes):** move the pure helpers out of UI files, the reflection-free matched points
(finding 2), a plan link / QR the app opens, rd5 + areas for the web on R2 with CORS (needs the map-access decision),
then a minimal planner for purpose A.

## 8. Phase 1 – plan per QR code and link (owner decision 2026-10-26)

The owner restarted the web planner with two phases (details and why: private notes, not in this repo):
**Phase 1** – plan in the browser, bring the plan to the phone by QR code, share it as a link.
**Phase 2** – pair phone and browser once (QR, like WhatsApp Web); then plans sync both ways by themselves, end-to-end
encrypted through a small mailbox (Worker + D1/R2). No login. Not started.

Split agreed with the owner: **cloud** builds the plan format, the web part and the test page; **the desktop session
(donald, phone on USB)** builds the app part – many short tries with the camera are seconds with adb, ~20 min per
round through the update channel.

### Plan code (done, branch `feat/plan-code`, merged into `web-spike`)
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

### Web (done on `web-spike`)
- "Send to phone · Share" (from 2 points): dialog with QR code of the link (`qrcode-generator`, MIT), Copy link,
  system Share where the browser has it.
- The address bar always holds the current plan (`#p=…`, `history.replaceState`): reload / bookmark keep it.
- Opening a link loads points, activity, T level, names and pinned points (pinned → passed exactly, the app's
  `Planner(pinned)`); a broken link says so in plain words.
- Test: `web/app/test/share-smoke.mjs` (CI) – opens the frozen code, checks the browser writes the identical code back,
  the QR dialog, edit + reload, broken link. The QR image was decoded once with jsQR: it holds the link.

### App part (to do – desktop session with the phone)
1. Open plan links: intent filter for the planner's https links (`#p=`) → `PlanCode.parse` → load as the current
   plan, "Save first?" like opening a saved route when the plan has unsaved changes. Until App Links are verified
   (`/.well-known/assetlinks.json` on the planner host with the signing key's SHA-256), the web page on a phone can
   offer "Open in OnPath" with an `intent://…;package=com.onpath.app;end` link.
2. Scanning: the phone's own camera already reads the QR code and opens the link (1). An in-app scanner (☰ → Scan
   plan) only if the owner wants it – then ZXing (Apache-2.0) without Google Play Services, and the CAMERA permission
   asked only on that tap.
3. "Share plan" in the app writes the same link (`PlanCode.link`) – the web opens it.
4. Core-feature style tests: link → plan (incl. round trip, pinned, names), broken link → plain message.

### Test page (to do, needs the owner once)
Cloudflare Pages for the page; rd5 parts and map areas from the existing R2 bucket through the Worker with CORS
(brouter.de has no CORS); access during the test by Cloudflare Access (e-mail code, free up to 50) – no app key in the
browser (map-access.md). The planner host name is also the App Link host above.

## 9. Test page host (cloud, 2026-10-26) – built, not deployed

Owner 2026-10-26: own domain **onpath.earth** (bought at all-inkl, DNS at Cloudflare; .earth is not sold by
Cloudflare). Proposed: `onpath.earth` home + help, `planer.onpath.earth` the planner, later `maps.onpath.earth`.
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
- everything else → the built page.
- `workers_dev` and preview URLs off: the only address is the custom domain, which **Cloudflare Access** guards
  (e-mail one-time code, free up to 50 users). Tests: `web/host/test` (CI); `wrangler deploy --dry-run` bundles 4 KiB.

**Owner setup, once (in this order):**
1. onpath.earth active in Cloudflare (nameservers at all-inkl → Cloudflare).
2. Zero Trust → Access → Applications → Add → *Self-hosted*: domain `planer.onpath.earth`; policy *Allow*, include the
   testers' e-mail addresses; login method *One-time PIN*. (First visit: choose a team name and the Free plan.)
3. API token (My Profile → API Tokens → Create → Custom): Account · Workers Scripts · Edit; Zone onpath.earth ·
   Workers Routes · Edit and DNS · Edit. In GitHub: secret `CLOUDFLARE_PLANNER_TOKEN`, variable
   `PLANNER_HOST_TEST` = `planer.onpath.earth` (`CLOUDFLARE_ACCOUNT_ID` exists).
4. Actions → Web spike → Run workflow (branch web-spike) → tick *deploy*.

Open: the home / help pages (drafts live in WordPress on 44-2.de, marketing session) – `onpath.earth` can redirect
there first; requesting new cells from the web (needs the doorman without app key – map-access.md).

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
| Search pill + round ☰ | `ui/PlanTopBar` | same; ☰ = Send to phone · Share, About & credits | page (My routes, recording, settings, updates are app-only) |
| Search, place card (Add to route / Remove, As start, As destination, Google) | `ui/search/*`, `search/AddPreview` | same | app code (§10) + `PoiLinks` |
| Map controls group: compass, eye, undo, redo, locate + GPS dot | `ui/MapFooter.MapControls` | same order and rules (eye only with a route, undo / redo only when possible) | page; undo / redo = app's `PlanHistory` |
| "Page orientation" = compass: shows the map's bearing, tap → north up | `CompassControl`, `AppViewModel.onCompass` | same; the map turns with right mouse / two fingers, no tilt | page; heading-up skipped (§11.4) |
| Eye: hold → route + markers hidden | `EyeControl` | same (pointer / Space held) | page |
| Hold rules: first hold "New route: Start here · Go here"; hold map / line → point (smart insertion); POI → "Add to route · Open in Google Maps" (empty plan: Start here · Go here); point → point menu | `ui/MapGestures`, `ui/MapOverlays` | a **click** (or right-click / long press) is the hold; same menus | app's `MapGestures.decide` / `pointMenu` / `fromMeBlocked` in the engine; menu texts mirrored |
| Go here (from my location) | GPS | browser location (asks on first use) | page |
| Point menu: Start here ("Removes N points before it"), Start & finish here, Open in Google Maps (named), Delete | `PointMenu` | same | app rule, texts mirrored |
| Google Maps link-out | `poi/GoogleMapsLink.PoiLinks` (app: geo: intent, else web URL) | web URL in a new tab, nothing drawn or fetched | app code |
| Plan sheet: activity chip, time + distance, ⋯, climb + max, difficulty badge (raised by technique), profile, Points (≡ drag, ✕, tap → map, Reverse, Round trip), one primary button | `ui/PlanPanel`, `PointsSection`, `StepSheet` | same; primary = Send to phone · Share; ⋯ = Export GPX, Clear route | texts from the app's `Format`, `RouteSummary.rating`, `PlanPoints`, `PlanEdits.move` |
| Route warnings: amber ⚠ line → "On this route" → tap shows the spot | `ui/RouteWarnings` | same | app code |
| Activity picker (six activities, line under each, T1–T5 with SAC text, Done) | `ActivityPicker`, `Screens.SacScale` | same | names from the app's `Activity`; SAC texts mirrored |
| Export GPX | `GpxWriter`, `SportType` | download | app code |
| Map credits: short "© OpenStreetMap", tap → full credits | `MapFooter`, `MAP_CREDITS` | same | page |
| Bottom sheet steps small / profile / full | `StepSheet` | phone width: two steps (summary + profile, + Points); wide screen: side panel, all shown | page |
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

### 11.3 Page structure

- **Wide screen (> 700 px)**: search pill + ☰ top left, the plan sheet below it as a side panel (384 px), map controls
  bottom right, scale + "© OpenStreetMap" bottom left beside the panel. Everything of the sheet is shown.
- **Phone width**: search pill + ☰ across the top, the plan sheet as a bottom sheet (handle: summary + profile +
  button ↔ + Points), map controls and footer above the sheet (`--sheet-h`).
- Menus: point / POI / first-point menus are map popups at the point; ☰ and ⋯ are drop-down menus; activity picker,
  share and credits are dialogs. Colours and type follow `OnPathColors` (green primary, route blue, amber warnings,
  red errors); icons are the app's Material icons.

### 11.4 Still different from the phone, and why

- **A click is the hold.** A browser has no hold-to-arm ring or vibration; a quick click adding a point is what web map
  users expect. Right-click / long press do the same. Markers can also be dragged (desktop habit); the phone is
  hold-only by owner decision.
- **No heading-up compass mode**: there is no walking direction while planning at a desk, and the browser compass needs
  a permission prompt on iOS. North up / turned only.
- Two sheet steps instead of three on phones; no profile scrubbing dot; no Route details (way types / surfaces) screen
  yet – the data is in the plan answer (`wayTypes`, `surfaces`), the screen is Compose.
- No saved routes / My routes, recording, Navigate, offline maps, settings – the web planner hands the plan to the
  phone (QR / link).
- Offline house numbers, recents and categories in search (§10).

### 11.5 Open decisions for the owner

1. Click = hold (current) or require a long press / right-click to add points, like the phone?
2. Keep marker dragging on the web (current) or hold-only as on the phone?
3. Primary button: "Send to phone · Share" (current) – or also offer "Open in the app" on phones (needs the app's link
   handling, §8 app part)?
4. Prefetch: the 1° part under the map centre from zoom 9 (2–10 MB per area) – fine for a public page, or only after
   the first click?
