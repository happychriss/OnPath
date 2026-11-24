# The planner's 52 mirrors, sorted (2026-11-20 late night, [umbau]; a Sonnet read of `web/parity.json` → `mirrors` + grep, nothing built)

Numbers = 1-based row in `mirrors`. A = pure, a small move / an engine export; B = a real cut (S/M/L); C = stays a mirror;
C* = the combined wait window (the laptop: stays unless a shared combined rule exists – not built unasked).

- **A (11):** 4 SearchSession DEBOUNCE_MS · 7 GpsStatus names · 10 PinnedPoiKinds · 11 MAP_CREDITS · 14 CellMath z10 cell ·
  32 CellCards build/name/rowState (pure object in `ui/CellCard.kt`) · 33 CellTriggers · 34 Format.number/distance ·
  39 ThumbRules (pure object in `map/RouteThumbs.kt`) · 40 RoundTrip (`app/domain/routes`, no Android) · 44 PoiDensity
- **B (15):** S – 3 PlanStatus.noRouteLine (a verb parameter), 12 OptionalSources.strip, 15 ElevationProfile fill / axis rules,
  18 PlanRunSlice cancel / planAnyway, 20 planFromShown, 30 Overview.multiPolygon, 41 ActivityFilter, 42 Favourites
  (41/42: already listed in `RouteListRules` – the page has to call them), 45 SearchStore encode/decode;
  M – 13 StyleLoader.withOverview, 17 SaveState writes, 19 PlanRecalcState.begin, 21 importGpx, 22 AppViewModel note, 46 empty search;
  L – 31 CellStore packages
- **C (22) + C* (4):** Compose layout / buttons (1, 2, 5, 6, 23, 24, 28, 35, 49, 43), MapLibre style / bitmaps (8, 9, 36, 37, 38),
  platform stand-ins / tooling / storage (25, 26, 27, 29, 52), planner differs on purpose (16 dialog, 3 if kept), C* 47, 48, 50, 51.

**The count:** every A and B done still leaves **26** (22 C + 4 C*) – „under 15“ is not reachable by sharing code alone.
It needs either fewer entries for the same thing (one entry per web file / cluster: 17–22 are one effort, 12/13/30 one
cluster) or C entries dropped as „not behaviour“ (pure Compose look). That is a decision for the owner / captain.

**Order by entries per effort:** (1) 34, 7, 10, 40 · (2) 14, 44, 33 · (3) 4, 11 · (4) 32, 39 · (5) 41, 42, 45 ·
(6) 12+13+30+15 · (7) 18, 20 then 17, 19, 22 (page drives the shared state) · (8) 21, 46 · 31 last.
Who: moves into `:core` / `:state` = [umbau] (40, 32, 39, 4, 11, 15, 45, 46, 21, the style cluster); engine exports of
code already in `:core` + deleting the TS copy = [laptop] (7, 10, 14, 33, 34, 44, 41, 42, 18–20).
