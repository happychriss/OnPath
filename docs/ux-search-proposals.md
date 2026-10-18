# POI search + adding places to the route – 3 UX proposals (2026-10-18, cloud)

Visual mockups (8 phone screens): https://claude.ai/artifact/KNThqwa1Qj7ZYJK8Ca2gp1 (owner's canvas).
Status: **waiting for the owner's choice.** Nothing implemented yet.

## Shared for all three
- **Offline search** over the region package: POIs, place names, peaks/passes, stations, trail names
  (pois.geojson, base tiles' place/mountain_peak, trailnames.geojson) → small SQLite FTS index built on the phone
  after import. German/Italian names, typo-tolerant ("hutte" = "Hütte" = "rifugio"), ranked by: near the current
  route, then distance to the map centre. Online provider later (F-PLAN-12).
- The same **place card** opens for a search result and for a POI tapped on the map (the laptop's POI card).
- Waypoints get **names** (POI name, else "Point 3"), shown wherever stops are listed.

## A · Search bar first (Google Maps style)
Home: permanent search field "Search huts, peaks, places…" with the activity chip inside, category chips below
(Huts · Peaks · Water · Stations). Result list → place card with three explicit buttons:
**Start here · Add as stop · Route here** (+ Google Maps). Cleanup: GPS status becomes a dot on the locate button,
settings move behind a layers button.
+ familiar, explicit roles. − search bar always takes map space; three buttons = one decision more per place.

## B · Stop list planner (Komoot style)
The planner is a list: **A Start · 1 Stop · B Destination**, each row a field; tap a row → "Choose destination"
sheet (My position, Pick on map, Recent, good destinations nearby, search). "+ Add stop", reverse, round trip.
+ precise, roles are obvious, reordering by drag. − list covers the map while planning; slower for quick
"tap and go" planning; two interaction models (list + map taps).

## C · Map first, one smart button (recommended)
Home stays a clean map with one search pill "Add a place…" (+ activity chip). Results show as numbered pins on
the map plus a half-height list with "+" per row. The place card has **one primary button "Add to route"** that
uses `WaypointInsertion` (start if empty, then destination, else the best-fitting stop) and **says what it will do
before you tap** ("as stop 2 – fits best · +0.9 km, +40 min", with a dashed preview on the map). Secondary:
As start · As destination · Google Maps. Planner sheet shows the stops as named chips "A My position › 1 Leiter Alm
› B Hochganghaus" + stats + Follow & record; edit/reorder/reverse/round trip/save/GPX in "⋯".
+ one consistent model (map tap, POI tap, search result → same card), fewest decisions, builds on smart insertion.
− roles only one tap away (secondary buttons) instead of always visible.

## UX cleanup proposed with it (any option)
- Top: one search pill + activity chip; GPS status as a dot on the locate button; settings behind a layers button.
- Bottom: Plan / Record / My tours stays on the home screen only.
- Planner: stats + named stops + one primary action; secondary actions in "⋯"; breakdown/profile on swipe-up.
- Fewer gesture rules: map hold (0.3 s) adds a point; tap POI/result → card; long press drags.
