# Backlog (shared by laptop and cloud Claude)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**.

## In progress
<!-- - [laptop|cloud] short description – since YYYY-MM-DD -->
- [laptop] Plan gestures v2: snap unroutable points to nearest way; POI tap = Google offer, POI hold = add; tap a stop = delete overlay (owner spec) – since 2026-10-18

## Next
- **Gradle upgrade (owner decision):** Android Studio on the laptop wants AGP 9.4.1 / Gradle 9.6 / Kotlin 2.2.10.
  Not applied – it is stashed on the laptop; `main` stays on AGP 8.9.1 / Gradle 8.14.3 until both sessions can
  build it. If wanted, do it on a `feat/gradle-9` branch and check the cloud build first.
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
