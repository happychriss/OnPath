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
