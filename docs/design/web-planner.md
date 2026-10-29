# Web planner

Status: **phase 1 is built** – plan links / QR codes open in the app (core feature C15, `PlanCode`). The browser
planner's code and its full design (spike result, phase 1 plan links, test page host, plan edits) live on branch
`web-spike` (`web/`, this file's §6–§10 there); this copy on `main` keeps the background.
Owner's wish: a web version for planning a route online, built **with as much of one codebase as possible**.

## 1. Why one codebase is realistic

The app already separates "what to do" from "how the phone does it":
- **Pure Kotlin logic**, tested on the JVM: planning (`routing/Planner`, `WaypointInsertion`, `PlanEdits`), route
  summary / warnings (`RouteSummary`, `ui/RouteWarnings`, `ui/PlanGaps`), time estimate (`time/…`), GPX
  (`gpx/…`), route names (`search/RouteNamer`), settings summaries.
- **Routing behind an interface** (`routing/RoutingContract.kt`, `RoutingEngine`).
- **The map is a style + PMTiles files.** maplibre-gl JS reads the same style JSON and the same area files (we
  already render the style with maplibre-gl JS in headless Chrome for checks). Keep one style valid for both
  (Native rejects zoom-interpolated hillshade colours – `StyleFormatTest` guards Native).

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

## 2. Purpose

- **A. Plan at home on a big screen, send to the phone** (what Komoot users know). Map,
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

## 4. Owner context

- Owner principles: robust first, offline by design, everything on the device, simple; no account, no tracking.
- Public texts factual (`docs/owner-preferences.md`: like telling a friend, no marketing).
