# Field test 2026-10-22 (Tasers / Schenna) – analysis of the phone data

Owner's Pixel 8, app 0.9.0. Fixes made from this analysis: see the end of this file.

All times are local CEST (UTC+2), with UTC (Z) where it helps. Everything was read from the phone without changing it.
Raw files (GPX, `onpath.db` + WAL, `problems.json`, tour/plan times) stay off the repo (owner's data).

## 0. What was pulled

| Source | Content |
| --- | --- |
| `Documents/OnPath/*.gpx` (5) + `Deleted/` (1) | 4 recorded tours + 1 **planned** route (Taser Alm → Greitererhof is a plan, not a recording) + the deleted 3-point "Round trip from Gasthaus Egger" |
| `databases/onpath.db` (+ `-wal`) | tables `tours`, `points(tour_id, seq, seg, lat, lon, ele, time)`. **No accuracy per point is stored** (the GPX has none either) |
| `files/problems.json` | 21 problem entries + only **50** trace events (the ring buffer, `MAX_EVENTS = 50`), covering 14:35 → 19:18 only |

Recorded tours (DB = GPX, same point and segment counts):

| id | Tour | Time (CEST) | Points | Segments | Distance (app) |
| --- | --- | --- | --- | --- | --- |
| 2 | Gasthof Taser Alm → Gasthaus Egger | 11:38–12:20 | 503 | **2** | 1922 m |
| 3 | Round trip from Gasthaus Egger (deleted) | 13:20–13:21 | 3 | 1 | 2 m |
| 4 | Gasthaus Egger → Schnugger | 13:22–14:00 | 350 | **2** | 1300 m |
| 5 | Schnugger → Gasthof Taser Alm | 14:35–15:36 | 381 | 1 | 1443 m |
| 6 | Verdins → Schenna | 15:45–16:10 | 335 | **2** | 2385 m |

No tour was paused: the stored duration matches wall-clock time (2: 2510 s vs 2506 s; 4: 2317 vs 2296; 6: 1478 vs 1486),
so every segment break is a GPS gap (the 0.9.0 rule "> 30 s without a fix ≤ 50 m → new segment"), not Pause/Resume.

## 1. Track gaps (§4.1)

### How to read the 15–31 s gaps
Most stored-point gaps > 15 s are **not** GPS loss: while standing still `PointFilter` drops fixes that moved less than
3–25 m within 30 s, so a break stores one point every ~30 s (e.g. 13:52–14:00 at Schnugger, 14:35–15:11 at Schnugger,
15:49–15:57 at the Taser lift). All such gaps are ≤ 31 s with 0–16 m moved. **The only gaps > 31 s are the three
segment breaks below** – there is no gap > 31 s inside a segment in any tour.

### The real gaps (all segment breaks)

| Tour | Gap (CEST) | UTC | Duration | Straight distance | Speed | Height | Where | 0.9.2 `bridgesGap`? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2 Taser Alm → Egger | 11:45:03 → 11:46:39 | 09:45:03–09:46:39Z | 96 s | 120 m | 1.25 m/s | 1494 → 1526 m | ~220 m north of **Gasthof Greitererhof**, below Hotelparkplatz Taseralm | **yes** (connected, +120 m counted) |
| 4 Egger → Schnugger | 13:39:22 → 13:41:56 | 11:39:22–11:41:56Z | 154 s | 143 m | 0.93 m/s | 1483 → 1457 m | 150–190 m W of and ~80–100 m **below Gasthaus Egger** | **yes** (connected, +143 m counted) |
| 6 Verdins → Schenna | 15:57:53 → 16:03:49 | 13:57:53–14:03:49Z | **356 s** | 1645 m | 4.6 m/s | 1500 → 907 m | Taser Seilbahn **Bergstation → near Talstation (Schenna)** – the gondola ride | **no** (> 5 min → still a new piece, not counted) |

Owner's spots:
- **Below Gasthaus Egger, ~13:30–13:45** → the 13:39–13:42 gap. Confirmed independently by the problem entry
  "Arrival estimate paused: no GPS fix for 2 min while recording" at **13:41:28** (= 2 min after the last fix at 13:39:22).
  That pause is driven only by fixes that pass the 50 m accuracy check, so it proves "no usable fix", but not whether the
  phone delivered nothing or delivered fixes worse than 50 m.
- **Near Greitererhof** → the 11:45 gap (morning tour, not 13:30).
- **Schenna** → the gondola: no fix inside the cabin for 6 min (metal cabin), then the track resumes just above the valley
  station.

### Accuracy > 50 m vs real silence
Cannot be decided from the data: accuracy is not stored in the DB/GPX, and 0.9.0 has no per-gap trace. Indirect evidence:
the walker moved at a normal slow-hiking pace across both walking gaps (0.9–1.25 m/s, descending/ascending on a trail),
so the phone was not paused or killed – the service kept running and the first fix after the gap was good. Most likely
forest/slope shading with fixes degraded beyond 50 m (or none) for 1.5–2.5 min. The gondola gap is a Faraday-cage loss.

### Verdict on 0.9.2 `bridgesGap` (≤ 5 min, ≤ 15 m/s)
- **Covers both walking breaks** the owner saw (96 s and 154 s, ~1 m/s) – the track would stay connected and +263 m would
  be counted.
- **Does not cover** the gondola gap (356 s). That is the right outcome here (it's a ride, not walking), but only because
  of the time limit: the speed (4.6 m/s) is well under 15 m/s. **A shorter cable car (< 5 min) would be bridged and its
  distance counted as hiking.** That belongs to vehicle detection (`docs/design/vehicle-detection.md`), not to PointFilter.
- Related: tour 6 after the gondola (16:06–16:10) is a **car/bus ride** at 9–11 m/s from the valley station down to
  Schenna ("Abzweigung Verdins"), ~2.1 km counted as hike distance – again vehicle detection.

## 2. Empty plan during tour 5 (§4.2, screenshot 14:52)

**Cause: (1) – the plan points were deleted by hand in the Points list** (three ✕ taps within 5 s). Not a restart, not
End tour, not a bug in plan restore. The tour kept following its copy of the old route, as designed.

Evidence:
- At 14:52 tour 5 (Schnugger → Taser Alm, started 14:35:27) was running; its stored points add up to **39 m** at 14:52
  (screenshot: 41 m) – the owner was still at Schnugger.
- Trace (0.9.0 traces only some edits; undo/redo/clear and "Go here" from a POI are not traced, but every plan change
  shows as a "Route #n for N points" line):

```
14:35:09–14:35:21 map   Press … (drawn 2, plan 2)                  ← plan = 2 points (Schnugger → Taser Alm), tour started 14:35:27
14:35:37 (problem) Following paused: finger on the elevation profile
14:48:30 map   Hold: marker 0, … nearest point 2 at 333 dp, 2 points
14:48:34 plan  POI menu closed: closed
14:48:34 routing Route #147 for 3 points ready                     ← point added from the POI menu (2 → 3)
14:49:11 map   Hold: … 3 points
14:49:12 plan  POI menu closed: closed
14:49:12 routing Route #148 for 4 points ready                     ← 3 → 4
14:49:17 routing Route #149 for 3 points ready                     ← 4 → 3, untraced (Undo)
14:49:18 routing Route #150 for 2 points ready                     ← 3 → 2, untraced (Undo)
14:51:51 routing Route #152 for 4 points ready                     ← 2 → 4, untraced (Redo ×2; #151 not shown)
14:51:59 routing Route #153 for 3 points ready                     ← 4 → 3, untraced (Undo)
14:52:06–07 map Press 156/140/52 dp from point 1/2/3 (drawn 3, plan 3)
14:52:09 plan  Points list: point 2 deleted (plan 3 → 2)
14:52:10 routing Route #154 for 2 points ready
14:52:10 plan  Points list: point 2 deleted (plan 2 → 1)
14:52:14 plan  Points list: point 1 deleted (plan 1 → 0)           ← "No points yet" (screenshot 14:52)
15:11:18–15:11:34 routing Route #158…#165, plan 2 ↔ 3 points       ← plan brought back (untraced: Undo/Redo), ends at 2 points
```
- "Points list: … deleted" is written only by `AppViewModel.deletePointFromList`, whose only caller is the ✕ in the plan
  sheet's Points list (`MapScreen` → `onDeletePoint`) – i.e. a finger on the ✕.
- (3) ruled out: the route counter runs on without reset from #147 (14:48) to #172 (18:32); a process restart resets it
  to #1 (seen only at 19:18:39, long after the tours).
- (2) ruled out: 0.9.0 does not clear the plan on End tour (that came in 0.9.1), and tour 5 ran until 15:36.
- Note the two ✕ taps 1 s apart on "point 2": after the first delete the rows shift up, so a second tap at the same
  spot deletes the next row. The owner may have meant to delete one point and the list moved under the finger.
- Also seen: between 15:40:59 (plan 2) and 15:45:28 (plan 0) the plan was emptied without a trace → a manual
  Clear route (untraced in 0.9.0), then a hold → POI menu → 2-point plan (Route #167) → tour 6 started 15:45:29.

## 3. Other findings in problems.json

| Level | Area | Message | Count | Note |
| --- | --- | --- | --- | --- |
| **ERROR** | cells | Map store generation **g000013.tmp** unreadable; skipped (older one used) | 1 (19:19:01) | **Bug.** `CellStore.generations()` says "complete generations" but its filter uses `GEN = ^g(\d{6})(\.tmp\|\.bad)?$`, which also matches in-progress `.tmp` (and `.bad`) folders. While `apply()` was writing g000013.tmp (routing data install at 19:19:01), a reader hit the half-written folder → a false ERROR announced to the owner. Same code in 0.9.2. |
| WARN | routing | Parts list of E10_N45.rd5 does not match the file; parts are checked again | 12 (since install 21.10.) | Recurs on (almost) every start – the stored parts list goes stale each time. Worth a look. |
| WARN | time | Arrival estimate paused: moving for 2 min but progress along the route did not change | 1 (15:49:39) | Owner was standing at the Taser lift top station (points 0.03–0.1 m/s from 15:48:54 to 15:57:53). "Moving" seems to be judged true from GPS drift; minor. |
| INFO | time | Arrival estimate paused: no GPS fix for 2 min while recording | 1 (13:41:28) | = the gap below Gasthaus Egger. Not repeated during the 6-min gondola gap (the 2-point plan of tour 6 was probably already reached). |
| INFO | map | Following stopped: map gesture (pan / fling / turn) | **390** | Very noisy; makes the list hard to read. |
| INFO | map | Camera mode changed by the map to 8 (wanted 24) | 55 | MapLibre dropping TRACKING_COMPASS; relates to the camera rule in CLAUDE.md. |
| INFO | map | LocationComponent#paddingWhileTracking ignored / only with camera mode ≠ NONE | 6 + 1 | harmless |
| INFO | routing | Routing data missing / installed / download paused offline | 15, 4, 2, 1, 1 | Evening plan into Germany (E5_N50); "Route #1 … after 71916 ms" and "#172 … after 34235 ms" = slow long-distance routing with downloads (0.9.1's long-route question covers this). |
| INFO | cells | Map cell 10-531-341 download stopped / server not reachable | 1 + 1 | offline at 18:29, retried |
| INFO | search | Online search failed (offline) | 1 | |

No crashes, no recording/DB write errors, no "GPS refused", no service restarts recorded.

## 4. What to fix (proposals at analysis time)

1. **Nothing more for the walking gaps** – 0.9.2's `bridgesGap` covers both real cases. Keep the 5-min limit; it is what
   stops the gondola being counted.
2. **Make the next field test decisive** – extend the 0.9.2 gap trace ("No usable GPS fix for N s …") with *why*: number
   of fixes dropped for accuracy during the gap and the best accuracy seen (vs. "no fix at all"). Optionally store
   accuracy per point (new nullable column) – cheap and settles this kind of question for good.
3. **Trace buffer too short** – 50 events covered only 14:35–19:18; by evening the morning's events (and any 0.9.2 gap
   traces) are gone. Raise `MAX_EVENTS` (e.g. 500) or keep plan/gps/tour events separately.
4. **Trace the missing plan edits** – Undo, Redo, Clear route, POI-menu "Go here"/add, plan restore at app start, tour
   start/end. In this data four plan changes and one clear could only be inferred from route counts. (Still missing in
   current `main`.)
5. **§4.2 UX** – by design, but easy to hit: the Points list ✕ deletes instantly and rows shift under the finger
   (two deletes 1 s apart). Options: ignore a second ✕ on the same row position for ~0.5 s, and/or, in Plan mode during a
   tour with an empty plan, offer "Show the followed route's points" (handover idea).
6. **CellStore bug** – `generations()` must list only complete `gNNNNNN` folders (exclude `.tmp`/`.bad`; keep the wider
   regex for numbering). Removes a false ERROR at every concurrent map/routing install.
7. **Vehicle detection** (already next in the backlog) – this day gives two real test cases: gondola 15:58–16:04
   (4.6 m/s, −600 m, no fix in the cabin) and car 16:06–16:10 (9–11 m/s). Both should end up as "ride", not hike distance.
8. Minor: "Parts list of E10_N45.rd5 does not match" on every start (12×); noisy INFO "Following stopped: map gesture"
   (390×).

## 5. Done (after the analysis, 2026-10-22)

- Gap trace says why: fixes dropped for accuracy and the best accuracy seen in the gap; accuracy is stored per point (DB).
- Trace buffer 50 → 500 events.
- Plan traces: Undo, Redo, Clear route, add from the POI menu, plan restore at start, tour start / end.
- Points list ✕: the list ignores taps for a short time after a delete (rows don't shift under the finger).
- Owner rule: what Plan shows = what Navigate follows. Deleting all points during a tour ends the route guidance (the
  tour keeps recording); Undo brings the points and the guidance back.
- CellStore lists only complete generations (no false ERROR on a half-written `.tmp`).
- rd5 parts list: see the commit "rd5 parts list" for the cause and fix.
- "moving but no progress" needs real movement (not GPS jitter while standing).
- "Following stopped: map gesture" / "Camera mode changed by the map" go to the trace only, not Problems.
