# Owner list 2026-10-20 (hike day, cloud session) – done, open, questions

For tonight's session on the laptop. Everything below is also in the release notes, backlog or design docs that are
linked here.

## Owner rules decided today (keep)
- **Power:** save power only when nobody looks (background, pocket, screen off). On screen the app runs at full speed,
  smooth and polished – no frame caps (0.7.11 reverted the 0.7.3 caps).
- **Polish:** everything moves smoothly and elegantly, nothing jerky ("a bit like Apple"). Paths should not look
  angular either.
- **Recording:** ending a recording must always be deliberate; Plan mode never stops recording; nothing may end a
  tour by accident.
- **Releases:** UI / app work may be released by the cloud; anything about map **loading** (cells) only after the
  laptop says it is ready.
- **Map loading triggers** (`docs/design/map-cells.md` §9): set a point (T1), open the app in Plan at a new place
  (T3), route corridor (T4), position near the edge of the loaded cells while on tour (T5), monthly refresh (T6).
  **Not** the search ring (T2 rejected).

## Done today (released)
| Version | What | Field test |
|---|---|---|
| 0.7.2 | Route-up: on a followed route the map turns by the route 40 m ahead; bigger, separated map buttons in Navigate; + laptop: arrival fix, address search, Google Maps share, route names, small map touches | ✓ partly (see 0.7.10/11) |
| (main) | First-point menu "Start here · Go here" | in 0.7.3 |
| 0.7.3 | Battery batch 1: screen off in the pocket (proximity), sleep after 60 s covered; no Wi-Fi/cell scans in a tour; quieter notification | **test**: pocket screen-off |
| 0.7.4 | Off-route vibration works with the screen off (alarm vibration); setting "Vibrate when off route" (default on) | **test** |
| 0.7.5 | Pause keeps GPS (every 10 s); walking 150 m while paused → vibration + "Resume?" | **test** |
| 0.7.6 | Free look in Navigate: turn/move the map by hand, back to position + orientation after 25 m | **test** |
| 0.7.7 | Recording safety: no "Stop & save" in the notification; running tour not deletable; update asks during a tour; restart/update → continue or notification | **test** |
| 0.7.8 | "Allow all the time" offered once at the first tour → a tour continues by itself after restart/update; status in Settings | **test** (restart the phone during a short tour) |
| 0.7.9 | Map turns faster (≈1–2 s instead of 3–4) | **test** |
| 0.7.10 | Map never upside down when walking slowly against the route (last 15 m of movement wins) | **test** |
| 0.7.11 | Fix map 180° after standing still on the route (compass took over after 15 s); full frame rate again | **test** |
| 0.7.12 | Fix map stuck north up after Plan → Navigate (no new direction while walking straight); heading re-sent every fix | **test**: Navigate → Plan → Navigate, walk straight |
| 0.7.13 | Smoothness batch from the polish audit (`docs/design/polish-audit.md`): continuous map turning, fixed dot position, gliding cards, no hold-ring flash, profile scrub fixes, dark status icons | **test**: walk a bend, start navigation, profile scrub |
| 0.7.14 | Position via Wi-Fi indoors when GPS is silent 30 s (≤ 150 m accurate; never cell guesses; track stays GPS) | **test**: plan indoors |
| 0.7.15 | Dot like Google Maps: cone shows where you face (view heading), map turns by the route (map heading) – camera turned by the app; Google Maps share checked against the address (> 3 km → address) | **test**: turn 90° on the route (only the cone turns); free look with two fingers; share Gelateria Costantin |
| (main, next release) | ☰ menu without "Check for updates" (Settings has Updates); direction arrow instead of the cone at the dot (still shows where you face) | – |

Also answered: off-route warning = vibration + notification with sound (since 0.6.x).

## Open – waiting for the owner
1. **Path smoothing (angular bends):** proposal = round bends ≥ 20° in the pipeline, max ~1.5 m from the real path,
   junctions exact, route bands smoothed the same way; streets stay as in the base map. **Owner: send examples
   (place + zoom / screenshots)** → before/after renders; then agree with the laptop (map owner) and the cells work.
2. **Polish audit:** done – `docs/design/polish-audit.md`. Quick wins A1–A3, A6, A7 and B1 shipped in 0.7.13. Owner
   picks from the rest (plan sheet "breathing" while routing A4, recomposition per frame A5, slide-to-plan snap A8,
   Plan ⇄ Navigate transition B2, dot after the pocket B3, real draggable sheet B4, ...).
3. **Battery batch 2** (`docs/design/battery-audit.md`): slower GPS when standing / screen off in the pocket (with
   thresholds that scale, so no point is lost), recorded track drawn as frozen part + tail, battery %/h in the tour
   summary, one-time battery hint for Samsung-type phones. Owner: field-test 0.7.3+ first; measure %/h if possible.
4. **Map package on the `downloads` branch is out of date** (no `paths.geojson` / `addresses.sqlite`). Held back
   today ("don't change the map"). Rebuild when the laptop's map work allows – laptop or cloud?

## Questions for tonight
- **Bike map (Komoot comparison, Meran):** oneway arrows (+ "bikes both ways"), green cycleways, cycle lanes beside
  streets – see backlog "Bike map". Which of the three, and lanes always or only for bike activities? Laptop or cloud?
- Pause reminder after **150 m**, free look back after **25 m**, route look-ahead **40 m** – right values after the test?
- Notification without "Stop & save": OK, or an "End tour…" button that opens the app with the question?
- Background location ("Allow all the time"): fine for sideloading; **Google Play** will need a declaration + video
  for it later – keep it?
- Samsung / Xiaomi: show the "battery unrestricted" hint once at the first tour?
- Path smoothing: also smooth the green route bands and the planned (blue) route? (planned route comes from BRouter
  and follows the original points, ≤ 1.5 m off.)

## Still open from before (not today)
- Laptop: grid cells / map loading (phase 1 on zo), map data size proposal (`docs/design/map-data-size.md`).
- Small findings: via ferrata mapped as path; route band ~3 m beside the path at z16+; "Waiting for GPS" text may be
  cut; unused theme tokens; branches `wip-0.6`, `wip-no-silent-errors` can be deleted.
- Map todos on request: contours always visible; trail numbers abroad.
- Overlays as one vector PMTiles (smaller, faster start).
- Safety audit batches B (warning line in the plan sheet) and D (pipeline: via ferrata, visibility, cliffs, fords).
- EN + DE strings; attribution style; unverified paths; Hetzner server not yet run on a real account.
