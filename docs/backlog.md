# Backlog (shared by laptop and cloud Claude)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**.

## In progress
<!-- - [laptop|cloud] short description – since YYYY-MM-DD -->
- [laptop] Arrival/time-to-go robustness: plan edits follow automatically, walking backwards, out-and-back matching, no stale times (owner field test 2026-10-19) – since 2026-10-19
  waiting for the owner's field test, then merge – since 2026-10-18

## Next
- **Map packages from the Hetzner server – design talk (owner, 2026-10-19, not decided yet):** owner leans to
  *grid cells built on demand* (~40 × 27 km zoom-10 cells, server only runs while building, ~5–8 min for a new cell,
  cached for everyone after; phone joins cells without seams). Also discussed: monthly big build + phone cuts its own
  area; future payment by region and month (downloaded maps keep working after the month). Agreed UX so far:
  - Overview map shipped in the app → never a blank screen in a new place.
  - **Trigger = setting a point, not looking:** search only moves the map and shows the orange ring (as today);
    scrolling only looks. The detailed map for an area is fetched when the user *sets a point there* (hold on the
    map / hold on the ring → Start here / Go here) – same gestures as today, one rule: "the map comes where you plan".
    The route corridor's cells follow once the route is calculated.
  - Persistent card in the plan sheet (no self-dismissing messages): "Preparing map for this area – about 6 min",
    progress per cell, own cell first; straight-line route until routing data is there, then it recalculates.
  - **Explain it to the user (owner: "we need to explain it later"):** the overview map shows a hint where no
    detailed map exists ("Overview only – set a point and the detailed map follows"); first-run / help page text;
    the card says *why* it is downloading ("because you planned here").
- **Arrival forecast jumps after plan edits during a tour (owner, 2026-10-19 – to think through, then build):**
  owner navigated, arrival crept 12:45 → 13:15 (ok: slower / standing), then in Plan: round trip, Undo, back to
  Navigate → 12:45 again. Code today: every change of the followed line (Continue navigation with an edited plan,
  re-route taken, activity switch) makes the service build a new `Navigator` (`RecordingService.loadFollow`) whose
  detector starts with unknown progress (0), and `TripPace.setLine` resets the calm limit – the arrival is recomputed
  in one step. Also: a plan that only *looks* edited (round trip + Undo re-routes to an equal but not identical line)
  counts as a new route (`planFollowed` compares lists exactly). Proposal: (1) same route = line within ~10 m and
  ±1 % length → keep the session, nothing restarts; (2) on a real new line keep the time already lost (shown arrival −
  model arrival) as an offset and blend it out over ~10 min instead of resetting; (3) apply the new line's time only
  after the detector has matched the position (never "whole route from 0"); (4) when it still moves by > 5 min, say
  why once ("New route: arrival 12:45, was 13:15"). Touches `time/TripPace`, `RecordingService.timeLine/loadFollow`,
  `AppViewModel.planFollowed` (laptop built the time model – agree who does it).
- **Overlays as PMTiles (follow-up of 0.7.0):** paths.geojson (19 MB), routes, trails, ways, lowways, trailnames are
  GeoJSON read fully into memory (~50 MB together). Pack them into one vector PMTiles (tippecanoe or Planetiler
  custom profile) – several times smaller, faster start. Then lowways can go (paths covers low zooms).
- **(done 0.7.0) One map style, fine and high-contrast (owner, 2026-10-19 – waiting for the owner's pick):** green routes and black
  paths must be clear at every zoom; one style instead of classic + high contrast. Variants A/B/C in
  `tools/themes-proposals/` (README has the adoption steps), comparison page https://claude.ai/artifact/S46QZFW8BpfDYCVjAxpa9a.
  `make_style.py` already takes the new tokens (`route.band.width`, `route.band.edge.*`, `lowways.*.width`).
  Owner feedback: **no edges on the route band, ever** (too many lines) – only a slightly transparent band whose green
  contrasts with the ground. Colour matrix (every ground × 10 greens, ΔE per cell): https://claude.ai/artifact/EZPjuSSjivcLNjfdBASb8W
  – today's light green #c9f29b is ΔE 12 on forest (weakest); yellow-green #d6f25a / fresh #8fd94a ≈ 30–38.
  **Owner pick:** band #168403 at 40 %, lighter ground, greenish hill shadow, no white, track variant A, symbols V1
  (none for forest/meadow). Full analysis + steps to ship: `docs/design/map-colour-analysis.md`; renders
  https://claude.ai/artifact/Ya7yRACjBEY6TtWePbqmJD
- **Map todo (owner, 2026-10-19 – start only when the owner says so):** (1) always show a little contour detail
  (every zoom, also with hillshade); (2) more contrast between the green land types + Komoot-like shrub
  ("Sträucher") symbols; (3) trail numbers like the AVS numbers in South Tyrol must also appear in other
  countries / regions (route-relation `ref`, `osmc:symbol`, network-specific refs) – `tools/make_style.py`,
  `tools/themes/*.json`, `tools/extract_overlays.py`.
- **Safety audit (cloud, 2026-10-18):** https://claude.ai/artifact/3KJ4LidPD9uxi1QngAmPNr – four batches A–D
  (profiles say no; one amber warning line in the plan sheet; Navigate tweaks; pipeline: via ferrata, trail
  visibility, cliffs, fords, bus stops, manifest date/bbox). Owner picks the batch.
- **Unverified paths (owner: keep as todo):** OSM ways with no tags (or only fixme/note) that connect two paths/tracks
  at both ends, ≤ 300 m, drawn as a grey dashed "unverified path" + point-menu hint; never used for routing (rd5 comes
  from brouter.de). Example: way 330547188 near Algund (46.6913, 11.1015), `fixme=tags?` between two dirt paths.
- **Attribution (owner question pending):** short "© OpenStreetMap" on the map, full credits on tap / in About.
- **EN + DE strings** for all UI texts (owner approved, start when no big UI work runs in parallel).
- **EN + DE strings** (owner approved): move all UI texts to strings.xml (EN + DE) in one go – start after the time
  estimate (feat/time-estimate) is merged, it touches most screens.
- **Re-routing while navigating:** design in `docs/plan-auto-reroute.md`, **owner decisions taken** (ask every time with
  vibration, grey original line, skip passed stops) – phases P0–P4 there (P0/P1 suggested for cloud as routing owner).
  Owner UX rule: no self-dismissing messages anywhere.
- Google Play internal test track: create app in Play Console, secret release key in GitHub secrets
  (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`,
  `PLAY_SERVICE_ACCOUNT_JSON`), upload from the release workflow. Removes Play Protect warnings. **(owner has a
  developer account)**

## Later
- In-app region download from the map server (`tools/hetzner/`, done as a web page): app asks the server for the
  map around the planned route / current view, shows build progress, imports the zip and the routing tile itself.
- Worldwide use outside prepared regions: stream OpenFreeMap + AWS terrain, auto-download BRouter tiles.
- Reroute back to the route after an off-route warning (offline).
- Keep pinned POI stops when a saved tour is reopened.
- German UI strings (`strings.xml`, EN + DE).
- Tune difficulty thresholds (Easy / Moderate / Difficult) with real tours.
- Route warnings list (steep, push bike, steps, terrain above T level).
- Reorder route points in a list.
- Verify data package checksums on import (manifest.json).
- Delete the temporary `downloads` branch on GitHub **(owner – sessions cannot delete branches)**.

## Done (recent)
- No silent errors (cloud, 0.6.7, owner): `debug/Problems` central log (dedupe, persisted, crash capture,
  MapLibre native logger), ≈120 catch sites in all packages reviewed and reported (or marked `// expected:`),
  Settings → Diagnostics problem list + Clear, new ERROR announced once per run, last crash announced at start,
  `problems.json` in the diagnostics export.
- Region package rebuilt (cloud, 2026-10-19): bbox extended west/south to `10.70,46.30,11.60,46.85` (whole Ultental /
  Zoggler Stausee for the field test), OSM 2026-10-19, all new overlay files (`ways`, `lowways`, `vis`), published as
  two zips on the `downloads` branch (GitHub 100 MB limit). POIs (owner): bus stops + all food on by default from z14,
  bus-stop pairs merged zoomed out and separate from z16 (`extract_pois.py` merged/detail, `make_style.py`
  poi-bus layers) – app 0.6.2.
- Way quality (laptop agents): track grades, faint trails, MTB S-labels (MTB only; T-labels hike only), loose-surface
  edge in bike modes, trail geometry ~0.5 m (no double lines). Time P4: SAC / surface / smoothness / MTB factors.
- Komoot-like overview (laptop): routes thin lines below z13.5 then bands, all paths/tracks from z11 (new optional
  `lowways.geojson`), yellow road scale. **Cloud: rebuild the region package with ways / lowways / trails / routes.**
- Track safety 0.6.1 (cloud, from the 0.6.0 robustness review at
  https://claude.ai/artifact/DRLGzEaWQBYKW4KVSFJeDd): point writes throw instead of returning -1, every DB write in
  the service guarded with a "Recording cannot be saved" notification + `RecState.saveFailed`, `failLoud()` instead of
  silent `stopSelf()` (precise location, GPS, foreground), Navigate only once the service reports, "Navigate anyway?"
  for straight legs, `ArrivalGate` (round trips, poor fixes), `MapController` no-ops while a style reloads,
  `PointFilter` (50 m accuracy, jitter by accuracy), clock-based elapsed time, wake-lock renewal, corrupt DB moved
  aside, importer keeps the old file. **Still open from the review (next, in this order):** raw exception texts
  shown to users; exact GPS position sent to Photon (needs an Online-search switch + coarse map centre); routing-tile
  download accepts any HTTP 200 body; BRouter `terminate()` + total planning budget; CI on every push
  (`verifyPaparazziDebug` + lint); backup rules (`allowBackup` + map data > 25 MB quota); drop
  `ACCESS_BACKGROUND_LOCATION`; compass accuracy gating; DEM-based ascent.
- Time estimate P0–P3 merged (laptop agent): one model everywhere (`time/`), pace setting, arrival clock, moving
  time / breaks, learning on the trip and across tours. Field test pending (checklist in project-status). P4 open.
- Owner-approved batch (laptop): Clear without question (Undo restores), Pause / Resume in the tour bar (two-line
  status), Round trip greyed when already a loop, no "Searching GPS…" / "Still missing" messages, "Export GPX"
  everywhere, blocked-way tint grey-red, Feldwege with finer lighter dots (Komoot-like).
- Activity in Navigate (laptop agent): icon in the to-go card (own 48 dp target), "Start navigation · Hike", tap =
  check-and-switch sheet (routes the rest for each activity, fits / problems, switching asks Take new route / Keep).
- UX audit (laptop agent): outdated labels, dead Load-route screen, save choice without Cancel, no OK after import /
  copy, settings rows; proposals decided by the owner (see next entry).
- Route bands (laptop, owner): one green band for every activity; major routes (OSM network regional / national /
  international, `net` ≥ 2 – Via Claudia Augusta, E5, EuroVelo 7, Etschradroute, Meraner Höhenweg …) get a Komoot-like
  semi-transparent green/pink dashed band + name label. `routes.geojson` now has `net` (0–4) → **cloud: rebuild the region package /
  downloads zip** (`tools/extract_overlays.py`), older data just shows no highlight.
- Layout (laptop, owner): top = search pill (search only) + round ☰ app menu (My routes, Start recording, Settings,
  Check for updates). Route sheet = everything about this route: activity button (icon ▾) at the start of the first
  line, ⋯ = Save route, Export GPX, Reverse, Round trip, Clear. Activity chip removed from the map; "Load route" and
  "Manage routes" merged into My routes (opening a planned route loads it, asks "Replace current plan?").
- Point menu concept (owner): "Start here" drops the points before, "End here" drops the points after (menu says how
  many; Undo restores); round trip: "Start & finish here" rotates the loop (same points, same order). Start and
  destination of a normal route get neither (⋯ Reverse). `PlanEdits.startAt/endAt/rotateLoop/isRoundTrip`.
- Navigate (laptop, owner): "to go" card shows ↑ and ↓; tap it for the elevation profile of the rest of the route
  (stays open until tapped again).
- Google Maps link (laptop agent): named places open `geo:lat,lon?z=16&q=<name>` (name search biased to our point),
  unnamed ones an exact labelled pin `geo:0,0?q=lat,lon(label)`; browser fallbacks. Needs owner test on the phone.
- Re-routing P0–P4 merged (laptop agent): ask every time, grey original, rejoin ahead, NavSession in the service,
  notification with answers. Field-test checklist in `docs/plan-auto-reroute.md` / project status.
- One blue line (owner): the followed route is drawn route-blue (no purple); while the plan is edited during a tour it
  is thin grey under the blue plan; only a merely shown GPX track keeps purple (`MapController.setTrackLook`).
- Polish 1 (branch `feat/polish-1`, owner): start at my location, small blue position dot with direction triangle,
  compass always in the map controls, no "Start from my location" button, "Tour saved" → Delete asks first, Save =
  overwrite / Save as new…, Rename in Manage routes (⋮ per item). See `docs/ux-two-modes.md`.
- Settings → Default activity (laptop, owner): the app always starts with it (hike + SAC level); the activity chip
  only changes the current route. Direction arrows (white chevrons) on the planned route and shown/followed track,
  to see out-and-back sections (`MapController.addDirectionArrows`, hidden by the eye).
- Route menus (branch `feat/route-menus`, owner spec): top ⋮ = Load route / Save route / Manage routes / Start
  recording / Settings / Updates, each enabled only when it makes sense (`ui/RouteRules.kt`); plan sheet ⋯ = Reverse,
  Round trip, Clear; no primary button without a route; Manage routes list (import, export, delete with confirmation,
  open / pick to load); "Replace current plan?" (Save first / Replace) when loading over an unsaved plan; sheet shows
  "Saved as …". See `docs/ux-two-modes.md` "Menus". Open: saving always creates a new entry (no overwrite/rename);
  empty-sheet hint now points to ⋮ → Start recording.
- Plan gestures v3 (laptop): hold only, 1×/2× vibration, smart point/POI menus, first-hold "Start here / Go here from my
  location", no dragging, no self-dismissing messages (the shared snackbar now stays until OK).
- Map controls grouped (laptop, owner): one rounded group on the right – compass (Navigate always; Plan only when the
  map is turned, tap = north up), eye (hold to hide route lines/markers, position stays; only when something
  route-like is shown; layers in `MapController.ROUTE_OVERLAY_LAYERS`), locate with GPS dot.
- Style (laptop): hiking route bands from z10 (fully opaque by z11, all bands fade in over one zoom), graded trails
  from z10 – Meran no longer looks empty at town zoom. Untagged paths still from z12 (base tiles).
- Plan gestures v2 (laptop, owner spec): POI quick tap = small "Open in Google Maps" bubble, hold on POI = add as
  pinned stop, tap a route point = Delete / End route here / Cancel overlay (26 dp zoom-independent hit radius,
  marker > POI > line > map), stop chips list and PointMenuDialog removed, "point added" notice only when the app
  guessed (middle insertion, no numbers). Rules in `ui/MapGestures.kt`, overlays in `ui/MapOverlays.kt`.
  Open owner questions: "Make it the start" only via ⋯ Reverse / search "As start"; bubble timeout 8 s.
- Snap free plan points onto the way the route uses (laptop, owner: no off-road guidance): BRouter's matched
  crosspoints (`RouteResult.matched`, read by reflection), pure `routing/WaypointSnap`, no extra undo step, POI-pinned
  and named stops keep their coordinates. JVM test `tools/routing/jvm-test/SnapTest.kt`.
- Toolchain (laptop, owner approved): **AGP 9.4.1, Gradle 9.6.0, Kotlin 2.2.10, Paparazzi 2.0.0-alpha05.1** (1.3.5
  breaks on Gradle 9). AGP-9 compatibility flags in `gradle.properties` (builtInKotlin=false, newDsl=false …) –
  clean up before AGP 10. All snapshots re-recorded (antialiasing only). **Cloud:** first build downloads Gradle 9.6;
  still JDK 21; on snapshot conflicts run `recordPaparazziDebug`.
- Bug fixed (laptop): a hiking via point on a spur was cut off and left unconnected (owner's point 3 on the Algunder
  Waalweg, 100 m off the route) – BRouter's via-point spike correction for HIKE lowered 250 m → 50 m
  (`VIA_CORRECTION_HIKE_M`). **Cloud:** please re-run your `ViaTest.kt` cases (the VIA_CASES file is not in the
  repo – consider committing it) and check bikes (400 m) for the same effect.
- 0.6.0 (cloud): navigation heading-up / north-up + next-turn card and arrow; place / address search (offline index +
  Photon online, place card with "Add to route" preview, Google Maps link-out, named stops); Material 3 UI cleanup
  (search pill + ⋮ menu, activity chip, plan sheet with status text, named stop chips and one primary button, route
  actions in ⋯, small FABs, Navigate status on top). `app/` is free again for the laptop. **Needs a phone test** (see
  `docs/project-status.md`, last session).
- 0.5.0 (cloud): two modes Plan / Navigate (`docs/ux-two-modes.md`): Plan is home (My tours + Settings in the top bar,
  GPS dot on the locate button, no eye button, no Plan/Record/My tours bar), "Start navigation" (route, shown track or
  free recording), Navigate with remaining strip and slide-to-plan (≥ 85 %), map taps change nothing; recording keeps
  running in Plan ("Tour running" bar, Continue navigation re-routes past passed stops, End tour with confirmation);
  arrival dialog within 50 m; off-route warnings only in Navigate. **Needs a feel test on the Pixel 8** (slider with
  gloves, re-route, arrival).
- Plan mode POI card (laptop): tap a POI → card above the plan panel with opening hours, "Add to route" /
  "Remove from route" and "Google Maps"; planning is not interrupted. Replaces the long-press Google dialog.
  Adding a point needs a short hold (0.3 s, ring + vibration) so stray taps add nothing. Blocked ways (access=no) marked on the map.
- LayerSettings crash on style reload fixed (laptop): skips a stale style instead of throwing; tested on the
  Pixel 8 by starting without base.pmtiles and putting it back.
- 0.4.1 (laptop, released via the workflow; in-app update 0.4.0 → 0.4.1 confirmed working on the Pixel 8 by the owner): resizable plan panel
  (full / compact / mini), eye hides the route only while held, POI tap = add/remove route point, POI long press =
  ask to open Google Maps, SAC labels (T1–T6) on trails, Komoot-like Feldweg (light lane, dotted edges),
  solid darker ways below z13, tiny POI dots, darker forest / brighter hiking band.
- Tried and **rejected by the owner:** a thin centre line inside the route bands below z13 (reverted in 7a1abf0) –
  keep the bands as plain green/orange/lilac overlays.
- 0.4.0: in-app updates via GitHub Releases, release workflow.
- 0.3.0: trail names + AVS numbers, contrast pass + high-contrast theme, stronger railways/stations,
  map data import on the phone, Clear with confirmation, map pipeline script + docs.
- Parking POIs: off by default (optional in Settings).
