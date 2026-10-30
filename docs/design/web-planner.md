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
encrypted through a small mailbox (Worker + D1/R2). No login. Built 2026-10-27 – see §12.

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
- `workers_dev` and preview URLs off: the only address is the custom domain, which **Cloudflare Access** guards
  (e-mail one-time code, free up to 50 users). Tests: `web/host/test` (CI); `wrangler deploy --dry-run` bundles 4 KiB.

**Owner setup, once (in this order):**
1. onpath.earth active in Cloudflare (nameservers at all-inkl → Cloudflare).
2. Zero Trust → Access → Applications → Add → *Self-hosted*: domain `plan.onpath.earth`; policy *Allow*, include the
   testers' e-mail addresses; login method *One-time PIN*. (First visit: choose a team name and the Free plan.)
3. API token (My Profile → API Tokens → Create → Custom): Account · Workers Scripts · Edit; Zone onpath.earth ·
   Workers Routes · Edit and DNS · Edit. In GitHub: secret `CLOUDFLARE_PLANNER_TOKEN`, variable
   `PLANNER_HOST_TEST` = `plan.onpath.earth` (`CLOUDFLARE_ACCOUNT_ID` exists).
4. Actions → Web spike → Run workflow (branch web-spike) → tick *deploy*.
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

**CI** (`.github/workflows/web-spike.yml`, owner 2026-10-30: faster, no test dropped, every test a gate before the
deploy). Jobs: `build` (parity check, TeaVM engine + overview style, texts check, the page with the overview files →
artifact `web-build` = `web/app/dist` + the engine files) ∥ `jvm` (the app's tests of the shared files +
`WebImportTest`, JVM routes, the three Wasm = JVM Node checks, host tests, browser compare) ∥ `smokes` (a matrix of 5
machines – `cells`, `sheet-sync`, `areas-parity`, `lang-gpx`, `short` – balanced by the smokes' times, each smoke its
own step with its own log). `jvm` and `smokes` have no `needs`: they set up (Gradle tests, Chromium, routing data)
while `build` runs, then wait for it through the API (`actions: read`) before downloading `web-build` → `deploy` (`needs` all of them, same condition as before: `inputs.deploy` or a push to web-spike) and
`results` (merges every job's `results-*` into the one artifact `web-spike-results`). A new smoke goes into the
shortest group. **Engine cache:** the TeaVM step is skipped when its key matches – `hashFiles` of `web/engine/**`,
`app/src/main/java/**`, `app/src/test/java/com/onpath/app/**`, `app/libs/**`, `app/src/main/assets/**`,
`tools/routing/jvm-test/**`, `strings/**`, `web/strings/**`, the Gradle wrapper / root Gradle files and the workflow
itself (deliberately wider than `appFiles`; a miss only costs ~2 min). The Gradle tests and JVM routes are not cached:
they run in `jvm` on every run.
**Agents:** locally run only the smokes your change touches; CI runs all.

Open: the home / help pages (drafts live in WordPress on 44-2.de, marketing session) – `onpath.earth` can redirect
there first. Requesting new cells from the web: §17 (the host holds the key, the browser does not).

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
| Plan sheet: activity chip, time + distance, ⋯, climb + max, difficulty badge (raised by technique), profile, Points (≡ drag, ✕, tap → map, Reverse, Round trip), one primary button | `ui/PlanPanel`, `PointsSection`, `StepSheet` | same; no big primary button (owner 2026-10-30): Share = icon + text next to ⋯; ⋯ = Export GPX, Clear route; Save in the header (row 22) | texts from the app's `Format`, `RouteSummary.rating`, `PlanPoints`, `PlanEdits.move` |
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
  My routes · the phone entry (always there, row 25: "Pair phone" / "Handy verbinden", or phone + green dot + "Paired" / "Verbunden") · Import GPX · Help & credits · The app (row 29: the phone entry opens the Phone window, its dot green / amber; row 28: no Save – ⋯ and My routes have it; one list, `NAV` in `main.ts` – new entries go there), right
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
  - *small* – the head: activity chip · Share icon · ⋯ · chevron in one line, then time + distance (never wrapped),
    then ↑/↓ + difficulty badge; the route's red / amber / grey lines stay visible here, as in the app (they sit above
    its MINI step end), so a new error line never needs a step change. About 150 px tall.
  - *profile* – + the elevation profile and "Saved as …".
  - *points* – + the Points list (the full sheet; on a phone it takes most of the height).
  - Controls: the grab handle on top (the app's handle tap: one step bigger, from Points back to profile), the chevron
    next to ⋯ ("Mehr anzeigen" until Points, then "Weniger anzeigen" → small; ⌄ / ⌃ point to where the sheet grows:
    down for the side panel, up for the bottom sheet), a click on the summary (= handle), Arrow up / down on handle or
    chevron. Remembered in `localStorage` `onpath.sheetStep` (try / catch); nothing remembered → side panel *points*
    (as before), bottom sheet *profile* (the app's default), following the width until a step is chosen. A Points row
    tapped on a phone → *profile* (the app's `showPointFromList`).
  - Unchanged in every step: the place card (at its point / instead of the bottom sheet), the GPX track panel (takes
    the sheet's place completely; Close track brings the step back), the long-route question (a dialog), the status
    line's waits and errors (plain info only from *profile*; the developer timing "Planned in … ms" only with `?debug`).
  - Map fit (`fitPadding`): phone → bottom = sheet (or place card) height; wide → the room beside the panel, or below
    it when that is larger (a short *small* panel).
- **No big main button** (owner 2026-10-30): Share is a small icon + text button next to ⋯ (always there, greyed until two points – row 20 in §13); it opens the
  dialog (QR code with "Scan with the OnPath app" above it, Copy link, system share, the one app line below).
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

## 12. Phase 2 – plan sync phone ⇄ browser (cloud, 2026-10-27) – built, not deployed

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
| 27 | My routes: "zu viele 'Trennen'" – Unpair three times, the question below Close | The question takes the place of the "Sync now · Unpair" row (since row 29 in the Phone window, above Close; `askInPlace` in plans.ts): the app's text without a repeated "Unpair?" ("Routes stop syncing. They stay in this browser and on the phone." / "Die Routen werden nicht mehr abgeglichen. Sie bleiben in diesem Browser und auf dem Handy.", app `sync_unpair_text`), **Cancel** (app `common_cancel`) + one red **Unpair**; Escape / Cancel bring the row back with focus on Unpair. The pairing dialog has no Unpair of its own any more (row 29), and a route's delete question now sits in place of its line (Keep · Delete) – never under Close. Close alone at the bottom right. Tests: sync-smoke (in place, above Close, one "Unpair", Escape, Cancel), ui-shots (`desktop-phone-unpair`). |
| 28 | Save out of the header; My routes says what syncs | Header: My routes · phone entry ("Pair phone" / "Paired") · Import GPX · Help – 4 entries (desktop and ☰). Save two ways, as in the app: the sheet's ⋯ → **Save route** (first item, active with a route – the app's `SheetMenuItems`) and My routes → **Save current route** (greyed on an empty route; ✓ **Saved as …** while the shown route is saved and unchanged – `renderSaved`, a click still asks Save / Save as new, the app's `saveMode`). The sheet's "Saved as … / changed, not saved" line stays from the profile step on, hidden in the small step like the app's `SavedLine` (below the MINI step). My routes' list has the heading **Your planned routes** / **Deine geplanten Routen** with the app's planned-route icon (`Icons.Filled.Route` in `OnPathColors.Route`): only planned routes sync, no tracks. Tests: parity-smoke (no header entry, ⋯ Save, My routes save states: active, saved, changed, back), sheet-smoke (4 header entries, paired too), search-smoke (⋯ Save). |
| 29 | The phone in its own window, three states (owner 2026-10-30) | **Phone window** (`phoneDialog` in plans.ts), opened from the header's phone entry (☰ on phones) only – My routes has no pairing section any more. On top the state with its dot and one sentence (`phoneState`): **not paired** – grey phone, "Pair phone" / "Handy verbinden", "Your planned routes appear on the phone and here."; **paired and current** – green dot, "Paired · synced 2 min. ago" / "Verbunden · abgeglichen vor 2 Min." (the phone reached the mailbox within **24 h**: the app syncs at start, on return and every minute while open but never in the background, so a phone in use syncs many times a day; the app's own 6 h warning would turn amber every night); **paired, the phone not syncing** – amber dot, "Paired · phone last 3 days ago" / "Verbunden · Handy zuletzt vor 3 Tagen", "Open OnPath on the phone, then the routes sync. Changes from here wait until then." Offline in the browser stays the sync line `web_sync_offline` (the app's `sync_offline`). The phone's time comes from the host (`appSeen`, web-sync.md §8 – no app change); without it (older host, two browsers) green with when this browser synced. Then "How it works" (3 lines: appear on both, sync while OnPath is open, newer wins / older stays as a copy), privacy in one line + **More** → the privacy page, the actions (not paired: **Pair phone** → QR code, steps "Open OnPath on the phone → ☰ → Pair with browser → scan this code", check digits; paired: **Sync now**, **Show code again** – the same code for another device –, **Unpair** with the question in place), **Close**; scrolls at phone width. Header: the entry's dot green / amber, none when not paired, tooltip = state + sentence; at phone width the **☰ button carries the same dot**. `web_sync_already_paired` / Help point to the entry ("Paired → Unpair", "Pair phone, at the top"). Tests: sync-smoke (state 1 window, the three states with the phone's time faked through the host, amber / green on the entry and on ☰, How it works / privacy / buttons, Show code again = same digits, unpair in the window), host `sync.test.js` (appSeen), ui-shots (`*-header-{none,fresh,stale}`, `desktop-phone-{none,fresh,stale,unpair}`, `phone-phone-stale`). |
| 30 | A toolbar button that shows the whole route | `#fitRoute` next to locate, Material `fit_screen` icon, "Show whole route" / "Ganze Route zeigen" (`web_fit_route`, label + tooltip): fits the planned line, every point and a shown GPX track into the free map room (`fitPadding` – clear of the sheet in any step, the top bar, the place card, the controls; the same fit as a shared plan's). A padding left by an older `easeTo` is cleared first (fitBounds would add it). Not keepFollow – it ends position following ("Map camera"). Greyed below two points, tooltip "Set a start and destination first". Test: sheet-smoke (greyed on an empty plan; after a jump far away and zoomed in, click / Enter brings every point and the line into the free room – desktop 1440×900 small + Points step + left-over padding, phone 390×844 small + Points step, with a GPX track on both). |

 Help is the credits dialog with four how-to lines (a real help
page could link onpath.earth/help).

## 14. App logic on the web – inventory and keeping it in sync (cloud, 2026-10-29)

Owner items 5 + 13: "cross-check the planning logic and keep the web in sync automatically – also with the memory
work in the app". Since 2026-10-29 web-spike carries main's app code again (the engine generates the app's string
resources, web-sync.md §0); every file below "shared" is compiled from `app/` as it is.

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
| Plan link / QR | `routing/PlanCode` | same | shared |
| GPX import, Komoot sport → activity + T level, track → plan | `gpx/GpxParser`, `gpx/SportType`, `routing/TrackToPlan`, `time/TimeEstimate`, `AppViewModel.importGpx / planFromShown` | same (§15) | shared; flow + panel mirrored (`gpximport.ts`, `WebImport.kt`) |
| GPX export with sport type | `gpx/GpxWriter`, `SportType` | same | shared |
| Search: index, ranking, Photon, add preview; route name proposal | `search/*`, `search/RouteNamer` | same | shared; Photon URL + debounce mirrored (`search.ts`) |
| POI kinds and filters | `poi/PoiKinds`, `poi/PoiFilter` | same | shared; visibility rules mirrored (`mapcontent.ts`, `LayerSettings`) |
| Routing data per corridor (1° parts) | `routing/Rd5Parts` | same | shared; download loop in `worker.ts` (app: `RoutingDataLoader`, `Rd5PartStore`) |
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

1. **Shared code updates itself:** web-spike merges main (`web/parity/merge-main.sh`: fetch, merge, build the engine,
   all JVM / Wasm / browser tests, the parity check; exit codes say why it stopped). Scheduled GitHub workflows only
   run from the default branch, so the daily run is a **scheduled Claude task** (prompt in the task report): it runs
   the script, pushes when green (CI = the gate, a green push deploys), and when mirrors or plan behaviour changed it
   writes a proposal of web adjustments instead of guessing.
2. **Mirrors cannot drift silently:** `web/parity.json` stores, per mirrored app file, the git blob hash it was last
   checked at. `web/parity/check.mjs` (first step of the web CI) **fails** when such a file changed – look at the
   change, adjust the web, then `node web/parity/check.mjs --update <app file>`.
3. **Shared files and performance / memory:** every compiled app file (`appFiles` + `appTests`), the string files,
   BRouter's jars and profiles and the style are listed with their hash and their perf / memory-relevant lines
   (caches, limits, timeouts, budgets, buffer sizes). A change is a CI warning; a change in those lines is shown
   line by line ("PERF/MEMORY-relevant change …") – then check the web side too (Wasm heap, worker, prefetch sizes,
   `RouteTimeLimit` vs browser speed). The run's summary page carries the report.
4. **Watch list:** app files with plan behaviour the web neither compiles nor mirrors (PlanPanel, MapScreen, Notices …):
   a change is a warning, so the daily task can propose the web side.

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
| Map tiles still loading 400 ms after the map stopped | map strip, spinner, until idle | Loading the map for your area (`maps_load_loading`) |
| Routing data for a plan | sheet status line, spinner | Loading the map for your area · about 10 MB (`maps_about_mb`; the worker sends the bytes still missing before it downloads – the app's map-cells.md §19.8 wording, no "routing data") |
| Routing | sheet subline + status line, spinner | Calculating the route… (`plan_calculating`) |
| "Go here" waiting for the position | status line, spinner | Finding your position… |
| Spot without detailed map, zoom ≥ 10 | map strip, grey | Overview only – the detailed map for this area is asked for when the map rests here. |
| Map area being built | map strip, spinner | the app's strip: "Preparing map for this area – about 5 min", rows "Your position · waiting – about 5 min" / "being prepared – 40 %, about 2 min", "2 more areas · waiting", × = hide (keeps loading) |
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
  "not available" (+ Worker log). The site stays behind Access (only `/sync/*` bypasses it).
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

Owner decision 2026-10-30: the planner goes public (no login), with a quiet path to the app. Done on `web-spike`;
Cloudflare Access stays on until the owner switches it off (steps below).

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
| Sync mailbox | 120 requests / min per IP; 32 KB per plan, 1000 items and 4 MB per mailbox; idle 12 months → deleted | `sync.js` `LIMITS`, `SYNC_RATE` (existing) |

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

### 18.5 Owner steps to go public (in this order)
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
