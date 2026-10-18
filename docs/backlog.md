# Backlog (shared by laptop and cloud Claude)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**.

## In progress
<!-- - [laptop|cloud] short description – since YYYY-MM-DD -->
- [laptop] Re-routing P0–P4 per docs/plan-auto-reroute.md (owner asked laptop to build all; incl. routing parts) – since 2026-10-18

## Next
- **Re-routing while navigating:** design in `docs/plan-auto-reroute.md`, **owner decisions taken** (ask every time with
  vibration, grey original line, skip passed stops) – phases P0–P4 there (P0/P1 suggested for cloud as routing owner).
  Owner UX rule: no self-dismissing messages anywhere.
- Google Play internal test track: create app in Play Console, secret release key in GitHub secrets
  (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`,
  `PLAY_SERVICE_ACCOUNT_JSON`), upload from the release workflow. Removes Play Protect warnings. **(owner has a
  developer account)**

## Later
- Hetzner: static hosting of region packages + monthly `tools/build_region.sh` run; in-app region download.
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
