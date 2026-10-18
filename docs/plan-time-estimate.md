# Plan: smart time estimate (planning + remaining time while navigating)


**Owner decisions (2026-10-18): all six recommendations accepted** – new model everywhere (Q1 yes), "to go" =
moving time + arrival clock with 10 % break allowance (Q2), three pace chips Relaxed/Normal/Fit (Q3), learning across
tours automatic with Reset (Q4), no range for now (Q5), owner's GPX tours as trimmed test files in the repo (Q6).

Status: **P0–P3 implemented** on branch `feat/time-estimate` (2026-10-18, see "Implementation status" below); **P4
way factors implemented** on `feat/time-p4` (SAC / surface / steps / MTB scale per span, see "P4" below); tuning with
the owner's tours still open. Original proposal text follows unchanged. Owner request: "Estimation of remaining walking/hiking time –
how to do it smart? Settings, learn from the current trip (consider up and down) – be cool and make sure we're not too
far off." Experiment: `tools/routing/jvm-test/TimeTest.kt` (real BRouter routes from the Bozen / Meran data +
synthetic replays), run with
`KTTEST=$PWD/tools/routing/jvm-test/TimeTest.kt tools/routing/jvm-test/run.sh /home/development/Projects/onpath-build/out/segments`.

**Short version**

- **Today the app shows two different times for the same route.** The plan sheet shows BRouter's time (Tobler's
  function, 5 km/h on the flat and **500 m/h up steep hills**); Navigate "to go" shows our DIN 33466 formula. On
  mountain hikes "to go" at the start is **+52 % to +94 %** of what the plan sheet just said (Hirzer hut: plan 1:31,
  to go 2:56). That jump comes first. Fix it before any learning.
- **One model for both**, worked out **per 50 m segment with its gradient** (option B, a small table: 4 km/h on the flat,
  ~300–340 m/h up, ~500–550 m/h down, slower again on very steep descents). It gives the same time in Plan and
  Navigate, and up and down are handled where they really are, not as route totals.
- **Settings:** one "Pace" choice per hike / bike: *Relaxed · Normal · Fit* (×1.25 / ×1.0 / ×0.8), as F-ROUTE-03 says.
- **Learning on the trip:** while you follow a route, OnPath compares your real **moving time** on each passed piece of
  the route with what the model said, **separately for down / flat / up**. The rest of the route is then scaled
  with your own factors. It learns slowly at first (it acts as if 20 min of "normal" walking were already done), so the
  first minutes don't swing the number. Elevation comes from the route (terrain model), not from GPS altitude, so GPS
  noise doesn't matter.
- **Breaks** don't count as moving time (standing still ≥ 2 min). "To go" = walking time. The **arrival clock**
  ("arrive ~16:40") adds a break allowance (default 10 %, later learned from your tours).
- **Calm display:** updated every 100 m / 30 s, and the arrival clock moves at most 1 min + 2 % of the remaining time
  per update. No jumping.
- **Replay result** (synthetic hikers on 9 real routes, 900 runs): median error of the remaining time at half way
  **20 % today → 8 % with learning**, p90 **53 % → 21 %**. Biggest arrival-clock jump per 100 m: 29 min → **8 min**
  with the calm display.
- **Across tours (optional):** after each tour your factors are stored per activity, so the next plan already says
  "your" time. Shown in Settings ("Your pace: 12 % faster than standard, from 4 tours", Reset).

## Implementation status (2026-10-18, branch `feat/time-estimate`)

| Phase | What is in the code | Where |
| --- | --- | --- |
| P0 | One model: table B (hiking), power model (bikes: rider power, e-assist to 25 km/h, rolling resistance and speed cap by surface, pushing = walking × 0.85 as the floor), per ~50 m DEM segment, cumulative per down/flat/up. Plan sheet, route list (planned routes re-timed on load), Navigate "to go", re-route scoring and the activity-check sheet all use it. BRouter's `durationS` stays in `RouteResult` but is not shown. | `time/PaceModel`, `time/RouteTimes`, `time/TimeEstimate`; `AppViewModel.withModelTime`, `Rerouter`, `ActivityCheck.timeS` |
| P1 | Settings → "Your pace" (Relaxed / Normal / Fit for hiking and cycling). Navigate card: "2 h 50 min · 7.4 km", "arrive ≈ 16:40" (10 % break allowance) on the right, recording line "moving 1:12 · breaks 0:11". "Tour saved" shows moving time, breaks and "On the route: planned 3:10 – you needed 2:52". | `ui/PaceSettings.kt`, `NavigateScreen` (`recordingLine`, arrival column), `FinishedDialog(times)`, `time/TourTimesStore` (`tour-times.json`) |
| P2 | `TripPace` in the recording service (persisted in `trip-pace.json`): learning per group with τ = 20 min, τg = 15 min, ρ = 0.5, clamp 0.5…2; pieces off route / jumps > 400 m / ratio outside 0.33…3 are not learned. Calm display. The opened Navigate card shows "Your pace on this trip: up 0.9× · down 1.1×" after 20 min of comparison. Replay harness for GPX pairs. | `time/TripPace`, `time/BreakDetector`, `RecordingService` (`timeLine`, `tripTime`), tests `TripReplay` / `TrackReplayTest` |
| P4 | Way attributes per span of the routed line (`GeoPoint.way: WayAttrs`, from BRouter's track messages): hiking × SAC / rough ground / steps / faint-trail factor, bikes on the real surface with smoothness / MTB-scale caps and pushing. Used per 50 m piece; Time details has a second table "By way type / difficulty". Kept through leg fallback, chunks, re-route splice and the navigation session file; saved routes store their group times in `plan-times.json`. | `routing/RoutingContract` (`WayAttrs`), `BRouterWayStats.perNode`, `BRouterEngine.toResult`, `time/PaceModel` (`hikeWayFactor`, `ride`), `time/RouteTimes`, `time/TimeBreakdown`, `time/PlanTimesStore`, `NavSession` |
| P3 | `PaceStore` per activity (6 activities, not 3 groups): after a tour with ≥ 30 min moving time on a followed route, weighted by compared time per group, half-life 5 tours; break ratio stored (not yet used). Settings lists it with Reset (asks first). Plan = model × pace × stored factors. | `time/PaceStore`, `Prefs.paceStore`, `RecordingService.learnAcrossTours`, `AppViewModel.resetLearnedPace` |

**Decisions taken while building** (open for the owner):
- *Breaks:* "within 25 m for 2 min" alone flagged slow steep walking as breaks (replay: 2:28 h of "breaks" instead of
  55 min). A stop now starts only when every fix of the last 2 min lies within **15 m** of the current one and, on a
  followed line, progress in those 2 min was < 10 m; it ends > 25 m away. A GPS gap ≥ 2 min with < 0.15 m/s average
  (hut, indoors) is a break too. Pauses are never breaks.
- *Calm display:* a new estimate is shown every **100 m** of progress (not more often than every 10 s); standing
  still, "to go" stays and the arrival clock only moves later as the time passes (checked every 30 s). Arrival
  rounded to 5 min, "to go" to 1 min under 20 min, else 5 min (the plan sheet shows the same rounded value).
- *Stored factors* are relative to the pace setting at the time; changing the chip later applies on top.
- *No "Learn from my tours" switch* (owner Q4: automatic + Reset); *no break-allowance setting* (fixed 10 %, Q2).
- *Bikes:* since P4 each span uses its real surface; spans without attributes (straight legs, imported tracks) use the
  activity's usual surface (touring asphalt, trekking / e-bike paved, MTB / e-MTB unpaved).

**Old vs. new times on the reference routes** (`ReferenceRoutesTest`, h:mm, Normal pace; new = plan sheet = Navigate at
the start, shown rounded to 5 min):

| Route | Plan sheet before (BRouter) | Navigate start before (DIN) | **New (both)** |
| --- | --- | --- | --- |
| H1 Meran → Lana | 1:36 | 2:03 | 2:02 |
| H3 Algund → Leiteralm (up) | 1:55 | 3:28 | 2:50 |
| H5 Leiteralm round trip | 3:24 | 6:04 | 4:47 |
| H8 Saltaus → Hirzer hut (up) | 1:30 | 2:55 | 2:20 |
| H8r Hirzer hut → Saltaus (down) | 1:04 | 1:55 | 1:24 |
| H9 Meran 2000 → Ifinger (T4) | 2:05 | 3:36 | 2:57 |
| B1 Bozen → Meran, trekking | 1:38 | 2:12 | 1:41 |
| B3 Lana → Vigiljoch, MTB | 1:16 | 1:32 | 1:19 |

**Replay (synthetic walkers on H5 / H7 / H9, `TrackReplayTest`):** |error| of "to go" at 25 / 50 / 75 %: strong climber
with bad knees 24 / 24 / 0 %, relaxed ×1.3 11 / 11 / 10 %, fit ×0.8 11 / 13 / 5 % (plain model 15–31 %); mean 12 % vs.
25 % without learning; breaks found within ±1 min; arrival jump per 100 m p90 2.4–3.9 min, max 3.3–12.3 min (the
1 min + 2 % rule allows ~8 min per update on a 6 h day). Real tracks: add folders to `app/src/test/resources/tracks/`
(plan.gpx + track.gpx + info.txt, see `TripReplay`).

### P4: way type and difficulty (2026-10-18, branch `feat/time-p4`)

Owner: "Is the estimator considering up and down and way type?" – up / down yes (per 50 m piece), now also the way.

**How:** BRouter reports way tags per run of equal tags (`processUnusedTags=1`); `BRouterWayStats.perNode` matches the
runs to the track nodes by position and puts a `WayAttrs` (way type, surface key, SAC 1–6, mtb:scale, smoothness,
trail_visibility, rock / scree) on every routed point (`GeoPoint.way`, ignored by `equals`, so nothing else changes).
`RouteTimes` times each 50 m piece span by span with the piece's slope and each span's way. No attributes (straight
legs, GPX tracks, old saved lines) = exactly the P0–P3 times. Not in the BRouter lookup table, so not used: `scree`
(only rock / stone), `incline` on steps.

**Hiking factors** (× table B, which already has the slope):

| Way | Factor | Why |
| --- | --- | --- |
| no SAC, T1, T2 | × 1.0 | Table B is anchored on DAV / AVS / Swiss signpost times, which use one formula for every hiking and mountain path (Swiss Wanderwege: "nimmt keine Rücksicht … auf die Schwierigkeit des Weges"). |
| T3 (demanding mountain hiking) | × 1.15 | Rocky, sure-footed walking. Wood et al. 2023 (GPS, 88 000 km): heavy ground cover / obstruction costs ≈ 13 % on the flat (4.85 → 4.23 km/h). |
| T4 (alpine hiking) | × 1.35 | Hands needed at times, pathless sections; between T3 and the off-path value. |
| T5 (demanding alpine) | × 1.6 | Often pathless, scrambling. Tobler 1993: off-path speed = 3/5 of on-path (× 1.67) – the upper end; Wood 2023 found Tobler's 0.6 too strong for easy off-path ground, so T5 stays a bit below it. |
| T6 (difficult alpine) | × 1.67 | Tobler's off-path factor. |
| rock / stone surface, steps | ≥ × 1.15 | as T3 (steps: slope is in table B, the extra is the stepping rhythm, mainly down). |
| trail_visibility bad / horrible / no | × 1.1 / 1.2 / 1.25 on top | way-finding; capped at × 1.8 overall. |

**Bikes** (power model unchanged; per span): rolling resistance and downhill cap of the **real surface** (track grade via
the surface mapping: grade1 paved … grade4/5 ground). Smoothness (OSM: defined by the vehicle that still gets through):
intermediate cap 25 km/h; bad cap 15 (MTB 20), Crr ≥ 0.025; very_bad cap 10 (MTB 15), Crr ≥ 0.035; horrible and worse:
pushed (MTB: cap 10). MTB scale (singletrail-skala.de; without it a footpath's SAC stands in: T2 ≈ S1, T3 ≈ S2,
T4+ ≈ S4): MTB / e-MTB S1 cap 15, Crr ≥ 0.03; S2 cap 10, Crr ≥ 0.045; S3 cap 6, Crr ≥ 0.06; S4+ pushed. Other bikes:
S1 cap 12, S2+ (or T3+) pushed. Pushed = walking (table B) × 0.85; steps = carried, half of that. Rolling resistance
order of magnitude: asphalt 0.004–0.008, gravel ≈ 0.01–0.02, rough ground ≥ 0.03 (Wilson, *Bicycling Science*). The
caps are judgement – first in line for tuning with real rides.

**Old vs. new** (`tools/routing/jvm-test/TimeP4Test.kt`, real BRouter routes, Normal pace, h:mm unrounded):

| Route | Way mix (km) | Before (P0–P3) | **P4** |
| --- | --- | --- | --- |
| H1 Meran → Lana (valley) | road 4.0, cycleway 3.8 | 2:02 | 2:02 (±0) |
| H2 Algund → Schenna (Waalweg) | road 4.1, footway 1.1, track 0.7 | 2:05 | 2:05 (±0) |
| H3 Algund → Leiteralm (up) | T2 2.2, road 1.3, track 0.9, T1 0.8 | 2:50 | 2:50 (±0) |
| H5 Leiteralm round trip | T2 4.7, road 2.4, track 1.8 | 4:47 | 4:47 (±0) |
| H8 Saltaus → Hirzer hut (up) | path 2.0, track 0.9 | 2:20 | 2:20 (±0) |
| H9 Meran 2000 → Ifinger (sac limit T5) | T1 5.2, **T3 1.4**, T2 1.1 | 2:57 | **3:05 (+5 %)** |
| H10 Texel group traverse | **T4 5.1** | 2:17 | **3:05 (+35 %)** |
| H11 Texel group section | **T5 3.2** | 2:53 | **4:37 (+60 %)** |
| B1 Bozen → Meran, trekking | asphalt 32.8 | 1:41 | 1:36 (−5 %: asphalt instead of the assumed "paved") |
| B1 Bozen → Meran, touring | asphalt 29.8 | 1:20 | 1:21 |
| B2 Meran → Schenna → Meran, trekking | asphalt / paved / gravel, intermediate | 0:55 | 0:56 |
| B3 Lana → Vigiljoch, MTB | unknown 2.7, asphalt 2.2 | 1:19 | 1:14 (−6 %: asphalt part) |
| B4 Algund → Vellau, MTB | gravel T1/T2 2.0 | 0:40 | 0:41 |

The Ifinger summit path is tagged T3 in OSM (only the last 1.4 km), so it gets slower only there; real T4 / T5 routes
get +35 % / +60 %. Valley and T1/T2 routes are unchanged.

**Field test (owner):** see the checklist in `docs/project-status.md` (Last session).

---

## 1. Goals and non-goals

**Goals**
- **One honest number** for the same route everywhere: plan sheet, route list, Navigate "to go", re-route questions.
- **Up and down count where they happen** (a route that climbs first and descends later is not one average).
- **Learns from the current trip**, quickly enough to help (after ~20–30 min) but never jumpy.
- **Simple settings:** one pace choice; learning works without any setting.
- **Offline, on the phone, cheap:** O(1) per GPS fix (precomputed sums along the line), no network, no server.
- **All six activities**; hiking first (owner's main use), bikes with the same frame.
- **Measured:** we know how far off we are (metrics + replay tests with real tracks, §6).

**Non-goals**
- No heart rate / sensors, no weather, no daylight warnings (requirements §1, later).
- No machine-learning model or cloud training; a few factors per user are enough.
- No change to how routes are chosen: the pace setting changes times only (F-ROUTE-03), not the route.
- No "race against the plan" or training features.

## 2. Today's behaviour and where it is off

### 2.1 Where times come from (code as of `0f7c8b8`)

| What the user sees | Where | Model |
| --- | --- | --- |
| Plan sheet time ("2 h 40 min"), saved tours, route list | `ui/PlanPanel.kt` ← `PlannedRoute.durationS` ← `routing/Planner.kt` sums `RouteResult.durationS` of each leg ← `routing/BRouterEngine.toResult`: `track.getTotalSeconds()` | **BRouter kinematic model** (below). Straight-line fallback legs: `Geo.estimateSeconds(d, 0, 0)` (flat). |
| Navigate "to go" time | `ui/NavigateScreen.kt` `ToGo(… FollowProgress.timeS(r, vm.activity))` → `data/FollowProgress.timeS` → `data/Geo.estimateSeconds(remaining distance, ascent, descent)` | **DIN 33466 on the whole rest of the route** (hike) / fixed speed + seconds per metre of climb (bikes). Ascent from `Geo.climb` (4 m hysteresis) of the route points' DEM elevations. Recomputed on every fix, no smoothing. |
| Re-route scoring | `navigation/Rerouter.kt` | `Geo.estimateSeconds` (DIN) |
| Imported GPX without times | `AppViewModel.importGpx` | `Geo.estimateSeconds` |
| Recording time | `recording/RecordingService` `elapsedS` | Wall-clock time while not paused (**includes breaks**; no moving time). |

**BRouter's time model** (`btools.router.StdPath.computeKinematic`, BRouter 1.7.11; defaults read from
`RoutingContext`): elevation is smoothed with a decaying buffer (`calcIncline`), then
- **foot profiles** (`hiking-mountain.brf` has `validForFoot 1`, no `maxSpeed` → default 6 km/h):
  **Tobler's hiking function** `v = 6 · exp(−3.5 · |incline + 0.05|)` km/h → 5.0 km/h flat, 6 km/h at −5 %.
- **bike profiles:** power model, solves `S_C_x·v³ + m·g·(C_r + incline)·v = bikerPower` (defaults 100 W, 90 kg,
  `C_r` 0.01 on every surface, `maxSpeed` 45 km/h). `trekking.brf` / `fastbike` set exactly these; `mtb.brf` sets
  nothing (same defaults → no surface effect); E-bike / E-MTB: `BRouterEngine.keyValues` sets 180 W, 25 km/h, 110–115 kg.
- `hiking-mountain.brf` ignores the SAC level and surface for time (only for cost); no breaks.

**Recording** (`RecordingService.onFix`, `TourDb.points`): per point `lat, lon, ele, time` (GPS UTC). `ele` is the raw
`Location.getAltitude()` = height above the **WGS84 ellipsoid** (in South Tyrol about +48 m above sea level, noisy
±5–15 m). Accuracy is filtered (≤ 30 m) but **not stored**. Standing still: points closer than ~3–8 m are skipped unless
30 s passed. Ascent: 5 m hysteresis on GPS altitude. The followed line's points carry **DEM elevation** from BRouter
(`GeoPoint.ele`, ¼ m steps).

### 2.2 How far apart are the numbers? (JVM, real routes, `TimeTest.kt` Part 1)

Hiking, h:mm. "DIN all" = today's to-go formula on the whole route; "DIN seg" = the same rule per 50 m segment;
"table B" = proposed model (§3).

| Route | km | ↑ m | ↓ m | **Plan sheet (BRouter)** | **To go at start (DIN all)** | DIN seg | Tobler | table B | to go vs. plan |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| H1 Meran → Lana (valley) | 8.1 | 13 | 15 | 1:36 | 2:04 | 2:07 | 1:36 | 2:03 | +29 % |
| H2 Algund → Schenna (Waalweg) | 6.8 | 262 | 27 | 1:33 | 2:10 | 2:19 | 1:34 | 2:05 | +40 % |
| H3 Algund → Leiteralm (up) | 6.1 | 780 | 61 | 1:55 | 3:29 | 3:32 | 1:56 | 2:51 | +81 % |
| H4 Leiteralm → Algund (down) | 6.1 | 90 | 777 | 1:29 | 2:37 | 2:39 | 1:31 | 1:57 | +76 % |
| H5 Algund → Leiteralm → Algund | 12.1 | 865 | 838 | 3:24 | 6:05 | 6:11 | 3:27 | 4:48 | +79 % |
| H6 Meran 2000 → Ifinger hut | 2.5 | 49 | 65 | 0:30 | 0:46 | 0:47 | 0:31 | 0:40 | +52 % |
| H7 Lana → Vigiljoch → Lana | 13.3 | 609 | 575 | 3:14 | 4:55 | 5:32 | 3:17 | 4:29 | +52 % |
| H8 Saltaus → Hirzer hut (up, T4) | 3.7 | 738 | 0 | 1:31 | 2:56 | 2:54 | 1:33 | 2:20 | +94 % |
| H8r Hirzer hut → Saltaus (down) | 3.7 | 0 | 729 | 1:04 | 1:56 | 1:57 | 1:06 | 1:25 | +80 % |
| H9 Meran 2000 → Ifinger summit (T4) | 8.1 | 481 | 493 | 2:06 | 3:36 | 3:49 | 2:09 | 2:57 | +72 % |

Speeds the models assume on a steady slope (horizontal km/h | vertical m/h):

| Gradient | DIN per segment | Tobler (= BRouter hike) | table B (proposed) |
| --- | --- | --- | --- |
| −40 % | 1.1 \| 432 | 1.8 \| 705 | 1.3 \| 522 |
| −20 % | 1.9 \| 381 | 3.5 \| 710 | 2.7 \| 545 |
| −10 % | 2.9 \| 286 | 5.0 \| 504 | 4.4 \| 444 |
| −5 % | 3.3 \| 167 | 6.0 \| 300 | 4.4 \| 222 |
| 0 % | 4.0 | 5.0 | 4.0 |
| +10 % | 2.2 \| 218 | 3.5 \| 355 | 2.5 \| 250 |
| +20 % | 1.3 \| 253 | 2.5 \| 500 | 1.6 \| 324 |
| +30 % | 0.9 \| 267 | 1.8 \| 529 | 1.1 \| 333 |

**Findings**
1. **The plan sheet is optimistic for mountain hikes.** BRouter's Tobler climbs at ~500 m/h on 20–30 % slopes;
   alpine clubs and signposts assume 300 m/h. For the Hirzer climb (738 m), BRouter says 1:31, DIN 2:56, table B 2:20.
   A normal hiker planning with the plan sheet would be about an hour late.
2. **Navigate is pessimistic, mostly on descents.** DIN on the whole route charges 500 m/h on *every* descent metre and
   adds half the horizontal time on top. Gentle descents (−5 to −10 %) come out *slower than the flat*, which is wrong
   (Tobler, Langmuir and the GPS study of Wood et al. 2023 all find gentle descents the fastest).
3. **The two disagree by 29–94 %**, so on "Start navigation" the time jumps. Users read that as "the app doesn't know".
4. **Bikes** (Part 1c): BRouter vs. `Geo.estimateSeconds` differ in both directions: trekking Bozen → Meran 1:38 vs 2:13
   (BRouter 20 km/h, Geo 15 km/h); e-bike Meran → Schenna 0:42 vs 0:37; MTB on gravel/trails gets the asphalt
   rolling resistance (`C_r` 0.01) in BRouter, so trail sections are as fast as roads on the flat.
5. **Breaks, SAC level and surface are ignored** everywhere; the recording time includes breaks.

## 3. Model options (planning and the prior for learning)

All options are evaluated **per segment** (~50 m along the route line, DEM gradient smoothed over 150 m) and summed.
Per segment is what makes "up first, down later" and learning per gradient possible.

| | A. DIN 33466 per segment + factors | **B. Gradient table (recommended)** | C. Tobler (what BRouter does) | D. Data-driven GLM (Wood et al. 2023) |
| --- | --- | --- | --- | --- |
| What | 4 km/h, 300 m/h up, 500 m/h down, larger + half of smaller, per segment | Minutes per km by gradient (table below), linear in between, like the Swiss "Wanderzeit" formula (time = distance × polynomial in slope) | `6·exp(−3.5·|s+0.05|)` km/h | `v = exp(a + b·cross-slope + c·s + d·s²)` + path/terrain factors from 88,000 km of GPS tracks |
| Matches signposts (DAV / AVS / SAC) | climbs yes; descents too slow | yes (anchored on them) | no: climbs ~1.7× too fast | not calibrated for alpine |
| Gentle descent faster than flat | **no** (slower) | yes | yes (too much: 6 km/h) | yes |
| Very steep descent slower | partly | yes (beyond −30 %) | yes | yes (critical at −16 to −18°) |
| Simple to explain / tune | yes | yes (one table, owner can tweak) | yes | no (fit coefficients) |
| Learning per gradient class | yes | yes | yes | yes |
| Literature accuracy (no personalisation) | Naismith/DIN-type rules: 13.5–15.5 % error on whole routes, 26 % on 50 m sections | similar | 26 % per section | 23.7 % per section, same 13.5–15.5 % per route |

The literature numbers (Wood et al. 2023, 7,636 public GPS tracks) show the key point: **no formula is much better
than another for an unknown person** (~14 % per route, more in the Alps). Individual pace dominates. So: pick a
sensible, explainable prior (B), make it consistent, and spend the effort on **personalisation**.

**Table B (hiking, Normal pace)** – minutes per horizontal km:

| Gradient | −50 % | −40 | −30 | −20 | −10 | −5 | 0 | +5 | +10 | +15 | +20 | +30 | +40 | +50 % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| min/km | 62 | 46 | 32 | 22 | 13.5 | 13.5 | 15 | 18.5 | 24 | 30 | 37 | 54 | 70 | 88 |

On a mixed mountain tour B lands between BRouter and DIN: H5 Leiteralm round trip 3:24 (BRouter) / **4:48 (B)** /
6:05 (DIN); one way up 2:51 (B) vs 1:55 / 3:29, down 1:57 vs 1:29 / 2:37. Checking B against the yellow AVS
signposts on the owner's routes is part of §6.

**Terrain factors (phase P4, optional):** multiply the segment time by a factor from BRouter's way tags (per-segment
messages already parsed by `BRouterWayStats`): SAC T1–T2 ×1.0, T3 ×1.1, T4 ×1.25, T5+ ×1.4; steps ×1.2; road / asphalt
×0.95. Evidence is weak (Aitken: 5 km/h on paths vs 4 km/h off them; Wood: heavy ground cover −0.5 km/h; Tobler:
off-path ×0.6), and the learned factor absorbs most of it, so this waits for field data.

**Bikes:** keep a **power model** (physics is the right tool for bikes, like BRouter's), but compute it ourselves per
segment so Plan and Navigate agree and learning works: rider power by activity (touring 120 W, trekking 100 W, MTB
110 W; e-bike/e-MTB rider 100 W + assist up to 25 km/h), **rolling resistance by surface** (asphalt 0.006, paved 0.008,
gravel 0.012, unpaved/ground 0.02, trail 0.03), **downhill speed caps** by surface (asphalt 40, gravel 30, trail 15
km/h; MTB trails T-scale later). Pace setting = factor on time (simple; same as hiking). Push sections (> 15 % on
non-e-bikes for long stretches) are a later refinement.

**Recommendation:** **B for hiking, per-segment power model for bikes, one code path for every displayed time.**
BRouter's `durationS` stays in the `RouteResult` contract but is no longer shown.

## 4. Personalisation

### 4.1 Settings (simple)

Settings → "Your pace", two rows (hiking, cycling), three chips each:

| Choice | Factor on time | Hiking flat speed / climb |
| --- | --- | --- |
| Relaxed | ×1.25 | 3.2 km/h, ~260 m/h |
| **Normal** (default) | ×1.0 | 4 km/h, ~320 m/h |
| Fit | ×0.8 | 5 km/h, ~400 m/h |

Komoot has a similar fitness setting (it changes only the time, not the route); Hiiker and Locus let you type speeds.
Own speeds (km/h, m/h up, m/h down) are **not** offered in v1 – the learning does this better and needs no input.

### 4.2 Learning from the current trip (the core)

**What is observed.** Only while a route is followed and you are on it (off < 40 m, progress moving forward –
`FollowProgress` / `NavLine` progress already exist). Each time progress passes another ~50 m route segment, record
`(group of that segment, predicted seconds p, real moving seconds a)`. The gradient comes from the **route's DEM
elevation**, so GPS altitude noise, the ellipsoid offset and barometer quirks don't matter. Off-route stretches and
detours are not learned (not comparable to the plan).

**Moving time vs. breaks.** A break starts when you stay within 25 m for 2 min (or the recording is paused) and ends
at the first fix > 25 m away. Break time is excluded from `a`. Shorter stops (photo, map check, gate) stay in moving
time – they are part of normal walking, as in signpost times. The break total is stored separately.

**Groups.** Three: **down** (< −5 %), **flat** (−5…+5 %), **up** (> +5 %). Five classes were tried; three are enough
and learn faster.

**Blending with the prior (shrinkage).** Each factor is "real time ÷ predicted time", but starts as if some normal
walking had already happened:

```
f_trip  = (τ + Σa) / (τ + Σp)                              τ  = 20 min  (trip factor, starts at 1 = setting)
f_group = (τg · (1 + ρ·(f_trip − 1)) + Σa_g) / (τg + Σp_g)  τg = 15 min, ρ = 0.5
clamp all factors to 0.5 … 2.0
remaining = Σ over remaining segments  p_segment × pace setting × f_group(segment)
```

- After 10 min of walking, the trip factor has moved one third of the way to what you showed; after 40 min, two
  thirds. Early minutes cannot swing it.
- **ρ = 0.5:** being fast uphill tells us *something* about downhill, but not everything (strong climbers with bad
  knees exist). A group you haven't walked yet gets half of your trip factor; once you walk it, its own data wins.
- Fatigue (slower later in the day) shows up because the factor keeps learning; a forgetting half-life of ~2 h moving
  time can be added if field data shows a lag.
- Cost: predicted time per group is precomputed as cumulative sums along the line, so `remaining` is three
  subtractions and three multiplications per fix.

**Calm display.**
- Recompute on every fix, **show** at most every 30 s or 100 m of progress.
- The arrival clock moves at most **1 min + 2 % of the remaining time per update**; a real change (e.g. after a re-route
  or plan edit) resets the limit once.
- Rounding: to-go under 20 min to 1 min, under 1 h to 5 min, above to 5 min; arrival clock to 5 min.

### 4.3 Replay result (`TimeTest.kt` Part 2)

Synthetic hikers walk the 9 real routes > 2.5 km (20 runs each, 900 runs): their true time per segment is table B ×
their personal factors per gradient class × fatigue (0–10 % over the day) × correlated noise (σ ≈ 15 % over ~250 m).
Error = estimated remaining moving time vs. the hiker's true remaining moving time (median / p90).

| Estimator | at start | at 25 % | at 50 % | at 75 % | arrival jump per 100 m, p90 / max |
| --- | --- | --- | --- | --- | --- |
| E0 today (DIN on the rest, no learning) | 16 % / 51 % | 18 / 53 | 20 / 53 | 22 / 55 | 1.4 / 6.6 min |
| E1 table B, no learning | 17 / 30 | 18 / 31 | 19 / 33 | 20 / 35 | 1.2 / 7.3 |
| E2 rest km ÷ average speed so far (Garmin-style) | 37 / 60 | 22 / 50 | 26 / 47 | 27 / 60 | 5.1 / **268** |
| E3 table B × one learned factor | 17 / 30 | 9 / 26 | 8 / 27 | 9 / 31 | 2.0 / 24.8 |
| E4 table B × factor per down/flat/up (ρ = 1) | 17 / 30 | 8 / 23 | 7 / 22 | 7 / 20 | 2.3 / 34.3 |
| E4 … (ρ = 0.5) | 17 / 30 | 9 / 21 | 8 / 21 | 8 / 20 | 2.0 / 29.2 |
| **E5 = E4 (ρ = 0.5) + calm display (recommended)** | 17 / 30 | **9 / 23** | **7 / 21** | **8 / 20** | 2.3 / **8.0** |

Per hiker type at half way (median error): today E0 / one factor E3 / recommended E5

| Hiker | E0 | E3 | E5 |
| --- | --- | --- | --- |
| walks exactly like table B | 20 % | 5 % | 5 % |
| fit (×0.8) | 46 % | 4 % | 5 % |
| relaxed (×1.3, more fatigue) | 14 % | 10 % | 11 % |
| strong climber, bad knees (down ×1.3–1.6, up ×0.8) | 13 % | 18 % | 12 % |
| slow climber, fast down | 19 % | 12 % | 8 % |

**What this shows (and doesn't).**
- Learning roughly **halves the typical error and the bad cases** from a quarter of the way on.
- "Average speed so far" (what many devices do) is the worst option: it ignores what is still ahead (a descent after
  a climb) and jumps by hours early on – matches user complaints about Garmin "time to destination".
- One factor is good for "generally faster/slower" people but **misleads on the way down after a climb** (bad-knees
  hiker: 18 %); per-group factors with ρ = 0.5 fix that without hurting the others.
- The calm display costs no accuracy and caps the worst jump at 8 min (from 29).
- **Not shown:** whether table B is right for real people – the hikers were generated from table B. Absolute accuracy
  needs real tracks (§6). The literature says ~14 % per route for an unknown person with a good formula; learning across
  tours (§4.4) is what closes that gap *before* the start.

### 4.4 Learning across tours (optional, recommended)

- After a tour with ≥ 30 min moving time on a followed route, store per activity group (hike / bike / e-bike):
  `f_down, f_flat, f_up` (the trip's group factors) and the break ratio, as a weighted running average
  (new tour weight = its moving hours, older tours fade with a half-life of ~5 tours).
- The next plan uses `table B × pace setting × stored factors`. The trip learning then starts from these.
- Settings shows it in one line: "Your hiking pace: 12 % faster than standard on climbs, 5 % slower downhill (4 tours)
  · **Reset**". No hidden magic; the user can switch learning off.
- Free recordings (no route) can feed this later: elevation from our terrain DEM (`terrain.pmtiles`, Terrarium z13)
  at the recorded points instead of GPS altitude.

### 4.5 Breaks in the arrival clock (`TimeTest.kt` Part 2b)

Arrival error in minutes (median / p90), hikers with 5–20 % break time taken in blocks:

| Allowance | at 25 % | at 50 % | at 75 % |
| --- | --- | --- | --- |
| none | 18 / 48 | 13 / 36 | 4 / 12 |
| **fixed 10 %** | **5 / 28** | **5 / 23** | 1 / 6 |
| learned on this trip only | 10 / 39 | 7 / 25 | 1 / 5 |

Breaks come in blocks (lunch at the hut), so a ratio learned during the trip is too noisy. **Use a fixed allowance
(10 %, later the across-tours value) for what is ahead; breaks already taken are simply in the past.**

## 5. What the user sees

| Place | Today | Proposed |
| --- | --- | --- |
| Plan sheet | "2 h 40 min" (BRouter) | "**3 h 10 min**" walking time from the new model (with pace + learned factors). Tap/long text in "Elevation & surfaces": "walking time, without breaks". No range (simple). |
| Route list / saved tours | duration from the plan | same new model (recomputed on load, stored time ignored for planned tours) |
| Navigate "to go" card | "7.4 km to go · ↑ 512 m ↓ 300 m · 2 h 54 min" | "7.4 km · ↑ 512 m ↓ 300 m · **2 h 50 min**" and on the right, large: "**arrive ~16:40**" (incl. break allowance). |
| Navigate, learning visible | – | Small grey line under the time after ≥ 20 min: "your pace: up 0.9× · down 1.1×" – only in the expanded card (tap = elevation profile, already there). Hidden when factors ≈ 1. |
| Recording line | "● Recording · 5.3 km · 1:23:45" | "● 5.3 km · moving 1:12 · breaks 0:11" |
| After the tour (F-NAV-06) | distance, time, ascent | + moving time, breaks, "planned 3:10 – you needed 2:52 walking" |
| Settings | – | "Your pace" chips (hike / bike), "Learn from my tours" switch + Reset, break allowance 0 / **10** / 20 % |

Owner UX rules are kept: nothing pops up, nothing disappears by itself, big text, no extra buttons in Navigate.
Confidence/ranges are deliberately left out of v1 (one number, honestly computed, calm); see Q5.

## 6. Accuracy plan – how we know we are "not too far off"

**Metrics** (per tour, followed route, moving time):
- `E_k` = |predicted remaining moving time − real remaining moving time| ÷ real remaining, at k = 0 % (plan),
  25 %, 50 %, 75 % of the route distance.
- Arrival-clock error in minutes at the same points (incl. breaks).
- Calmness: p90 and max change of the shown arrival time per 100 m.

**Targets (owner's tours, hiking)**

| Metric | Target |
| --- | --- |
| Plan time, first tours (no history) | median ≤ 20 %, never Plan vs. Navigate disagreement (0 % by construction) |
| Plan time after ≥ 5 tours learned | median ≤ 10 % |
| Remaining time at 25 / 50 / 75 % | median ≤ 10 %, p90 ≤ 25 % |
| Arrival clock jump per 100 m | p90 ≤ 2 min, max ≤ 10 min (except after a re-route / plan edit) |

**Replay tests with real tracks.** A JVM/unit test feeds recorded GPX (lat, lon, time) as fixes into the same pure
estimator (`TripPace`) along the planned route GPX and prints/asserts the metrics. Today **no recorded tracks exist in
the repo or on this machine** (searched `onpath-build`, test resources, home) – they are on the owner's phone.

**Field data from the owner**
1. Before a tour: save the plan (Manage routes → Export GPX).
2. Record the tour while navigating (as now). After: My tours → Export GPX.
3. Put both GPX files (+ one line: activity, pace setting, anything special such as "lunch 40 min", "rain") into the
   fixture folder (see Q6), e.g. `app/src/test/resources/tracks/2026-10-25-hirzer/{plan,track}.gpx`.
4. The replay test runs in CI; each new tour adds one row to the accuracy table.
5. After ~5 hikes: tune table B (if the owner is consistently off in one gradient group, that is the table, not him),
   the τ / ρ values and the break threshold.

## 7. Architecture

New package `time/` (pure Kotlin, no Android, JVM-testable; like `navigation/`):

| Piece | Role |
| --- | --- |
| `time/PaceModel.kt` | `segmentSeconds(lenM, dz, activity, terrain?)`: table B for hiking, power model for bikes. Pace settings factors. |
| `time/RouteTimes.kt` | For a line: per-point cumulative predicted seconds **per group** (down/flat/up). `total(f)`, `remainingFrom(index, partial, f)` – O(1). Used for plan sheet, to go, re-route scoring. |
| `time/TripPace.kt` | State machine fed by the service: `onFix(timeMs, progressIndex, offM, moving)`, break detection, group sums, factors (formulas §4.2), calm display state, arrival. Serialisable (JSON) so it survives a service restart. |
| `time/PaceStore.kt` | Per-activity stored factors + break ratio (Prefs JSON), update after a tour, reset. |

**Where it plugs in**

| Today | Change |
| --- | --- |
| `Planner` sums `RouteResult.durationS`; straight-line legs `Geo.estimateSeconds` | `PlannedRoute.durationS = RouteTimes(points).total(user factors)` (points carry elevation; straight legs get DEM later or flat). `RoutingContract` unchanged. |
| `FollowProgress.timeS` → `Geo.estimateSeconds` | `RouteTimes.remainingFrom(r.index, …)` with the live factors from `RecState`. `FollowProgress` keeps distance / ascent. |
| `Rerouter` scoring with `Geo.estimateSeconds` | same `PaceModel` (consistency; optional) |
| `RecordingService` (elapsed time only) | owns a `TripPace` next to the `Navigator` (the service runs with the screen off and knows progress); publishes `moving`, `breaks`, factors, remaining, arrival in `RecState`; persists with `nav-session.json`. On tour end → `PaceStore.update`. |
| `NavigateScreen` `ToGo(... timeS)` | shows `RecState` remaining + arrival (no own computation). |
| `Geo.estimateSeconds` | kept for imported GPX without times and as fallback. |

Data: no new DB columns needed for v1 (break/moving can be recomputed from points); optional `accuracy` column later
for better replay.

## 8. Phases

| Phase | What | Effort | Who |
| --- | --- | --- | --- |
| **P0** One model everywhere | `PaceModel` + `RouteTimes` (table B, bike power model), plan sheet + to go + re-route use it; unit tests; `TimeTest.kt` stays as a regression print | ~1 day | cloud (routing-adjacent, no device needed) |
| **P1** Pace setting + arrival clock | Settings chips (hike / bike), arrival "~16:40" with 10 % break allowance, moving / breaks in the recording line; Paparazzi shots | ~1 day | laptop (feel on the phone) |
| **P2** Learning on the trip | `TripPace` in the service (break detection, group factors, calm display, persisted), NavUi shows it; replay test harness for GPX pairs | ~2 days | cloud (pure logic + tests), laptop field test |
| **P3** Learning across tours | `PaceStore`, after-tour summary ("planned vs. needed"), Settings line + Reset + switch | ~1 day | either |
| **P4** Terrain + tuning | SAC / surface factors from BRouter tags, bike surface rolling resistance and downhill caps, tune table and τ/ρ with ≥ 5 real tours | ~1–2 days | cloud, with owner's GPX |

P0 alone fixes the most visible problem (the jump between Plan and Navigate) and is small.

## 9. Open questions for the owner

| # | Question | Recommendation |
| --- | --- | --- |
| Q1 | Plan sheet times for mountain hikes get **~30–50 % longer** than today (BRouter's Tobler is fast uphill). OK to switch to the new model everywhere? | **Yes** – closer to the yellow signposts, and Plan = Navigate. |
| Q2 | "To go" = walking time without breaks, and the arrival clock adds a break allowance (10 %)? | **Yes**; allowance setting 0 / 10 / 20 %, later learned from tours. |
| Q3 | Pace setting: three chips (Relaxed / Normal / Fit) per hiking and cycling, or also own speeds (km/h, m/h)? | **Three chips**, no own speeds – learning does the fine-tuning. |
| Q4 | Learn across tours automatically (stored per activity, visible in Settings with Reset)? | **Yes, on by default**, only from tours with ≥ 30 min on a followed route. |
| Q5 | Show a range ("2:40–3:10") or confidence? | **No** for v1 – one calm number; maybe a "~" until 20 min of learning. |
| Q6 | Your recorded GPX tours as test fixtures: in the public repo (start/end 300 m trimmed for privacy) or in a private folder outside git? | **Repo with trimmed ends** – CI can then guard accuracy. |

## Sources

- DIN 33466 / DAV rule (4 km/h, 300 m/h up, 500 m/h down, larger + half of smaller):
  [planetoutdoor.de](https://planetoutdoor.de/wissen/auf-wanderungen/auf-bergtour-gehzeit-berechnen),
  [bergwelten.com](https://www.bergwelten.com/a/berg-know-how-wie-lange-geht-s-noch),
  [DAV Gehzeitrechner (PDF)](https://WWW.ALPENVEREIN.DE/files/DAV_Gehzeitrechner_acrobat_8_1_19002.pdf),
  [bergwelten: Gehzeit ist nicht gleich Gehzeit](https://www.bergwelten.com/a/gehzeit-ist-nicht-gleich-gehzeit).
- Swiss hiking-time formula (distance × 16-coefficient polynomial in slope, binding since 2006; limits above 40 %):
  [Beobachter](https://www.beobachter.ch/ausfluge-freizeit/wanderwegweiser-die-gelben-zeiten),
  [NZZ reprint (PDF)](https://www.burgenverein-untervaz.ch/_files/ugd/405a1e_8def2d28fe7240799700626cb2eb6ea0.pdf),
  [SRF rule of thumb](https://www.srf.ch/sendungen/kassensturz-espresso/services/espresso-aha/espresso-aha-so-berechnen-sie-die-wanderzeit);
  "Leistungskilometer": [youngstarswiki (J+S)](https://www.youngstarswiki.org/de/wiki/art/marschzeitberechnung-und-marschzeittabelle).
- Naismith, Langmuir (gentle descent −10 min / 300 m, steep +10 min / 300 m), Tranter, Aitken, Scarf:
  [Wikipedia – Naismith's rule](https://en.wikipedia.org/wiki/Naismith%27s_rule).
- Tobler's hiking function: [Wikipedia](https://en.wikipedia.org/wiki/Tobler%27s_hiking_function); Irmischer & Clarke
  2018 variants: [movecost docs](https://www.rdocumentation.org/packages/movecost/versions/3.0.0/topics/mc_cost_functions).
- Wood et al. 2023, "Improved prediction of hiking speeds using a data driven approach" (7,636 GPS tracks; per-route
  error 13.5–15.5 % for all models): [arXiv 2303.16065](https://ar5iv.labs.arxiv.org/html/2303.16065).
- Tobler's off-path factor 0.6 (3/5): W. Tobler 1993, *Three presentations on geographical analysis and modeling*,
  NCGIA TR 93-1; Wood et al. 2023 (above) on obstruction (−0.5 km/h at ≥ 10 cm ground cover; "the factor of 0.6 is a
  larger reduction in walking speed than is observed in practice").
- Swiss signpost times ignore the path difficulty: [Beobachter](https://www.beobachter.ch/ausfluge-freizeit/wanderwegweiser-die-gelben-zeiten),
  [SRF](https://www.srf.ch/sendungen/kassensturz-espresso/services/espresso-aha/espresso-aha-so-berechnen-sie-die-wanderzeit).
- SAC scale: [OSM wiki Key:sac_scale](https://wiki.openstreetmap.org/wiki/Key:sac_scale); MTB scale:
  [OSM wiki Key:mtb:scale](https://wiki.openstreetmap.org/wiki/Key:mtb:scale), [singletrail-skala.de](https://www.singletrail-skala.de/);
  smoothness by vehicle: [OSM wiki Key:smoothness](https://wiki.openstreetmap.org/wiki/Key:smoothness).
- Rolling resistance by surface: D. G. Wilson, *Bicycling Science* (3rd ed., MIT Press 2004), ch. 6.
- BRouter time model: `btools.router.StdPath.computeKinematic` and `RoutingContext` defaults in the bundled BRouter
  1.7.11 jars (`app/libs/`).
- Komoot fitness level changes only the time estimate:
  [komoot Adventure Hub](https://www.komoot.com/adventure-hub/186OZJztsNtyEOgjSnbQkf/du-plus-exigeant-au-plus-fun-comment-planifier-un-itineraire-sur-komoot),
  [PCtipp](https://www.pctipp.ch/praxis/navigation/komoot-durchschnittsgeschwindigkeit-anpassen-2788436.html).
- Outdooractive base speeds 4.0 / 3.5 km/h, formula private:
  [Naturfreunde presentation (PDF)](https://naturfreunde-wilhelmsburg.at/wp-content/uploads/2022/11/Tourenportal-Praesentation-2022-Update-Varianten-Andi-Stubhan.pdf).
- Strava route times from the last 4 weeks' average pace:
  [TrainerRoad forum](https://www.trainerroad.com/forum/t/new-strava-route-builder/36294?page=3),
  [Strava Routes launch](https://endurance.biz/2020/industry-news/strava-releases-routes-for-runners-and-riders/).
- Hiiker (5 km/h, 600 m/h, no descents, user pace setting): [Hiiker support](https://support.hiiker.app/?p=3658).
- Locus ("rough estimate", users ask for DIN-style up/down speeds):
  [Locus 3.32](https://www.locusmap.app/plan-trips-on-time-new-locus/).
- Garmin ETA complaints (average-speed based, jumps):
  [Edge 530](https://forums.garmin.com/sports-fitness/cycling/f/edge-530/308237/time-calculations-on-530-inaccurate),
  [Edge 830 v9.75](https://forums.garmin.com/sports-fitness/cycling/f/edge-830/370120/eta-to-dest-wildly-inaccurate-in-v9-75),
  [Better ETA idea](https://forums.garmin.com/developer/connect-iq/f/app-ideas/351506/datafield-idea-better-eta/1694285).
