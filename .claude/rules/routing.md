---
paths:
  - "app/src/main/java/com/onpath/app/routing/**"
  - "app/src/main/java/com/onpath/app/navigation/**"
  - "app/src/main/java/com/onpath/app/time/**"
  - "app/src/main/java/com/onpath/app/rides/**"
  - "app/src/main/java/com/onpath/app/gpx/**"
  - "app/src/main/assets/brouter/**"
  - "app/libs/**"
  - "tools/routing/**"
---
# Routing, navigation, GPX (loads when you touch routing/, navigation/, time/, rides/, gpx/, the profiles or BRouter)

| Path | What |
| --- | --- |
| `  routing/` | `RoutingContract.kt` (shared interface – change with care), `BRouterEngine` (offline BRouter), `EngineProvider` (single place choosing the engine, falls back to `StraightLineEngine`), `Planner` (leg-by-leg fallback, splits at pinned POI points, short-trip detour retry via the midpoint), `PlanResult` (**the one plan computation of app and web**: route + model time, snapped points, gaps, warnings, rating, `ProfileData` – `docs/handoff-web-planresult.md`), `RoutingFingerprint` (hash of BRouter version + profiles + `PLANNER_VERSION`, Settings → Diagnostics with the rd5 dates; `docs/design/routing-fingerprint.md`), `WaypointInsertion` (where a tapped point goes), `RouteProgress` (arrival check), `ArrivalGate` (when to ask "arrived": round trips, poor fixes), `PlanEdits` (reverse, round trip, move; `loopStart` = a round trip with stops on the way before its loop (owner 2026-11-03, the plan stays a plain point list); edits by row that keep a round trip closed: `PlanPoints.delete` / `deleteAt` / `move` – map menu, Points list and web use these), `RouteSummary` (breakdown merge, difficulty label = distance + ascent raised by trail technique T3+ / S2+, a hike's ≥ 2 km at T2, a Bike / E-bike route ≥ 25 % unpaved or ≥ 5 km loose (`rating`), gradient classes) |
| `  navigation/`, `time/`, `rides/` | Off-route + re-routing (`Navigator`, `Rerouter`, `NavSession`); plan edits during a tour: the position splits the plan into passed / ahead (`TourSplit`, app only – never in the code shared with the web, `docs/design/tour-edit-position.md`); junction cues (`JunctionCues`, `CueRule` – vibrate only where one could go wrong; vibration / screen / lock screen per activity: `docs/design/navigation-cues.md`); time estimate (`PaceModel` per-segment model, `RouteTimes`) + pace during a tour (`TripPace`); bus / car / lift in a tour (`RideDetector`, core feature C12) |
| `  gpx/` | Tolerant `GpxParser`, `GpxWriter` (GPX 1.1), model, `PlanGpx` (the one plan export, shared with the web planner: line + named route points + sport; re-import rebuilds the points), `SportType` (Komoot's `<trk><type>` ⇄ activity + T level on import / export) |
| `app/libs/` | BRouter 1.7.11-beta jars (MIT), built by `tools/routing/build_brouter_jars.sh`; named `-onpath1`: brouter-core has `RoutingEngine.islandNodePairs` package-private (`tools/routing/patch_brouter_jar.py`, the only change – island retry without reflection, `docs/handoff-web-island.md`) |
| `app/src/main/assets/brouter/` | BRouter profiles (`touring.brf` – OnPath's own, `trekking.brf`, `mtb.brf`, `hiking-mountain.brf`) + `lookups.dat` |

- **Routing**: always via the `RoutingEngine` interface; blocking calls on a background thread;
  newer requests supersede older ones in the UI.
- **Activities** (exactly six): Bike, Gravelbike, E-bike, MTB, E-MTB, Hike (SAC T1–T5,
  default T2, chosen in the activity picker; the app remembers the last activity and T level). Names + the one picker line
  under each: `Activity.label` / `line`, chip `Activity.chip` ("Gravel") (enum names BIKE_TOURING / BIKE_TREKKING … unchanged and the only
  stored form). Profiles
  (`BRouterEngine.profileFile` – the one source; map layers, route warnings and the activity check ask it): Bike +
  E-bike → `touring.brf`, Gravelbike → `trekking.brf`, MTB + E-MTB → `mtb.brf`, Hike → `hiking-mountain.brf` with the
  T limit (E-bike / E-MTB change only power, speed and mass). In a sentence the activity reads "Hike T3"
  (`LastActivity.name`), on the chip "Hike · T3".

- Routing on the JVM without a phone: `tools/routing/jvm-test/run.sh <segmentsDir>` (header lists the test mains, e.g. `GpxCompare.kt` + `gpx_compare_report.py`: our route vs a reference GPX line – aggregate numbers only in the repo).
- Map ↔ router classes (format 2 overlays): `tools/routing/wayclass/build.sh` (classifier jar, needs a JDK) and `tools/routing/wayclass/consistency.py <package> <segmentsDir>` (routes never cross a way the map shows forbidden).
