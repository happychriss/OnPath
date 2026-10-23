# Web planner – handover laptop → cloud (2026-10-23)

Status: **idea + proposal, nothing built.** Owner hands this to the cloud session (start 2026-10-24).
Owner's wish: a web version for planning a route online, built **with as much of one codebase as possible**.
Not decided yet: the purpose (§2) – ask the owner first, one question at a time.

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
