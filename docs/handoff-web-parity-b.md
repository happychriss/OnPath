# Handoff: app ⇄ browser parity, part B (plan GPX, loop-aware edits, routing fingerprint)

Owner rule (2026-10-31): routes and plans are always identical in the browser planner and the app – share the code,
don't re-implement it. Main now has three pure Kotlin pieces for the web engine (no Android, no reflection, no
`java.security`; kept so by `SharedCoreRuleTest.webShared`). web-spike is not touched; this is what the web session
does after merging main.

## 1. `appFiles` / `appTests` (`web/engine/build.gradle.kts`)

- Add `"gpx/PlanGpx.kt"` (needs `GpxWriter`, `SportType`, `GpxModel`, `data/Geo`, `routing/RoutingContract` – all in
  already) and `"routing/RoutingFingerprint.kt"` (needs `sync/SyncCrypto.kt` for `Sha256` – in already; uses
  `java.io.ByteArrayOutputStream` and `Math.floorDiv` only).
- `appTests`: `"gpx/PlanGpxTest.kt"` (GpxParser, PlanEdits – in). `routing/PlanPointsTest.kt` and
  `ui/CoreDeletePointTest.kt` may join (PlanState is in). **Not** `RoutingFingerprintTest.kt`: it reads
  `src/main/assets/brouter` and `libs/` relative to `app/` – the app's CI runs it.

## 2. Export GPX = the app's plan export (`gpx/PlanGpx`)

Today `WebApi.gpx(name, activity, sac)` writes the line only, file name without suffix. The app writes the line, the
route points as `<wpt>` with names, the sport, and "<name> - plan.gpx". Shared now:

```kotlin
/** "Export GPX" of the page's plan: the app's PlanGpx (line, named route points, sport, "<name> - plan.gpx"). */
fun gpx(name: String, activity: String, sac: Int, points: String, names: String): String {
    val w = parsePoints(points)
    val line = lastPlan?.takeIf { it.first == w }?.second?.points?.takeIf { it.size >= 2 } ?: return "null"
    val n = name.ifBlank { Txt.s(Res.string.msg_name_share) }
    val nm = parseNames(names)                       // the rows() format: "index<TAB>name" lines
    val f = com.onpath.app.gpx.PlanGpx.export(n, listOf(line.map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon, it.ele) }),
        com.onpath.app.gpx.PlanGpx.stops(w) { nm[it] }, Activity.valueOf(activity), sac)
    return "{\"file\":" + str(f.fileName) + ",\"gpx\":" + str(f.text) + "}"
}
```

- `main.ts` (≈ line 1190) passes `enc(state.points)` and `namesText()`; `worker.ts` signature + `CALLS` unchanged
  otherwise. The line must belong to these points (`lastPlan.first == w`), as `rows()` does.
- **Import**: before `planFromTrack` (TrackToPlan), ask the engine `PlanGpx.stopsOf(line, waypoints)` – non-null = an
  exported OnPath plan: take those points (and their `<wpt>` names as the page's names) as the plan, no fitting. The app
  does this in "Plan from track" (`AppViewModel.planFromShown`). A small `WebApi.planStops(gpxJson)` or doing it inside
  `gpxRead` (a `"stops"` array in its JSON) both work; the rule is `stopsOf`'s, not the page's.
- Parity check: `PlanGpxTest.sameFileAsTheAppWroteBefore` / `reimportRebuildsTheStops`; a web smoke can export a 3-point
  plan with a named stop and look for 3 `<wpt>` and "- plan.gpx".

## 3. Delete and reorder = the Points list's loop-aware edits (`routing/PlanPoints`)

The app's map menu Delete now goes through `PlanPoints.deleteAt` (core feature C1, `CoreDeletePointTest
.roundTripDeleteTheStartFromTheMenuKeepsTheLoopClosed`): a round trip's start (or its closing copy) deleted → the loop
closes at the next point: `[a, b, c, a]` → `[b, c, b]`. The web differs today:

- `main.ts deletePoint(i)` drops index 0 **and** the closing point of a round trip → `[b, c]`, the loop opens. Replace the
  page's own filter by the engine: `WebApi.edit` `"delete" -> PlanPoints.deleteAt(w, index)` and `deletePoint` calls
  `applyEdit('delete', i)` (or `engine.call('edit', 'delete', …)`). Names / pins: the page keeps `state.meta` by
  index – rebuild it by value after the edit (each new point takes the meta of the first old point with the same
  coordinates; the loop's new closing copy takes its start's), as `edit()` already promises ("coordinates unchanged,
  so the page keeps names and pins by value").
- `WebApi.edit` `"move" -> PlanEdits.move(w, index, to)` is used by the Points list drag (`sortable`, row indices). On a
  round trip the closing point is not a row, and `PlanEdits.move` breaks the loop (`[a, b, c, a]`, row 2 → 0 gives
  `[c, a, b, a]`). Use `PlanPoints.move(w, index, to)` (rows; the loop is closed again at the new start:
  `[c, a, b, c]`), as the app's Points list does.
- The marker `dragend` (a point moved to another place; a loop's start moves its end too) is position editing, not
  reordering – it is fine as it is; the app has no marker drag.
- Tests: `PlanPointsTest.deleteAtAPlanIndex`, `deleteInARoundTripKeepsTheLoop`, `reorderInARoundTrip`; the
  `parity-smoke` "Points list ✕ deletes the point" failure seen on web-spike may be this.

## 4. Routing fingerprint (`routing/RoutingFingerprint`, design: `docs/design/routing-fingerprint.md`)

The app shows in Settings → Diagnostics:
`Routing: r1-3fa9c2e81b04 · BRouter 1.7.11-beta · planner 1 · data E10_N45 2026-10-26`. Same fingerprint + same data
dates = the same route.

- Engine call, e.g. `WebApi.routingInfo(): String` =
  `RoutingFingerprint.describe(RoutingFingerprint.of { n -> File(root, "assets/brouter/$n").readBytes() }, rd5)` – the
  profiles the engine already reads (`AssetManager.root`); `rd5` = the tiles the page has loaded, name →
  `Rd5Parts.creationTime(tail)` (the tail bytes are fetched with the index anyway – `PartialRd5`), `null` if unknown.
- Show it where the page keeps technical info (About / a diagnostics line), English like the app.
- Parity: `web/test/compare.mjs` can assert the Wasm value equals the JVM `runJvm` value (same files → same text).
- The sync fields 7 / 8 and the "planned with other routing" line are built on main (owner 2026-10-31) – web steps in
  `docs/handoff-web-sync-fingerprint.md`.
