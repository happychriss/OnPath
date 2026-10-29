# Map updates – when and how (owner decisions)

- **Plan on the latest map:** after every route computed in Plan, the installed map areas along it (10 km corridor)
  are checked for a newer version (`CellTriggers.freshenAlong`, reason `refresh`, each area at most hourly). Nothing is
  updated while the app is idle (no monthly / start-up refresh).
- **Already built on the server → fast:** download, the map is veiled ("Loading the newer map…") for those seconds,
  then switches (`MapUpdateLayer`). **Must be built first** (new map style, or OSM older than the server's 35 days)
  → small hint "Newer map for this area being prepared · about N min"; planning goes on with the current map.
  The modal overlay is **only** for areas without any detailed map.
- **Waiting for the map of your area** (owner): first start
  (T3) and a point set where there is no detailed map (T1) show ONE modal (`ui/MapLoad.kt`): "Preparing the map for
  your area" (server builds) / "Loading the map for your area" (download), a bar with %, "About N minutes left" /
  "Less than a minute left", one button **Cancel** (only hides it – the download goes on, the plan sheet's card shows
  it). One overall % (`MapLoadProgress`): queued + building 0–80 % (server progress + ETA, moving on by elapsed time
  between polls, at most 90 % of the way to 80 by itself), download 80–99 % by bytes, added to the map 100 %; never
  backwards; updated every second. No internet: same modal, "No internet connection – the map will load as soon as
  you're online.", bar paused. Failure: plain reason, "Try again" / "Close". It closes by itself only when the map
  of the point is there; no "done" message (the job's "Map ready" card is skipped). Route corridor (T4), on tour (T5)
  and newer versions stay quiet / in the small card.
- **No flash** (owner field test: "a very short message I couldn't read, then another one: downloading"): the
  map-loading overlay / plan-sheet card, the veil, the "being prepared" hint, the "Overview only" hint and the
  "Finding the shared place…" pill go through `ui/NoFlash`: a passing step (preparing, downloading, a hint) shows only
  after it lasted 1 s – fast background steps are never shown; what shows stays ≥ 2.5 s before it goes or turns into
  another step; what waits for the user (question, failure, "Map ready") shows at once; after a tap the next change
  shows at once.
- **Mobile data by default** for everything (no Wi-Fi/mobile question); Settings → "Maps only on Wi-Fi" turns it off.
- **No switch during a tour:** `MapCells.holdUpdates` while in Navigate (a style reload while following can stop
  tracking, CLAUDE.md "Map camera"); new areas at the tour's edge (T5) still load. The old map stays usable until the
  new one is complete (store generations).
- **Routing data** (BRouter, brouter.de weekly) is what decides the way: after a route, at most every
  12 h, a tile older than 35 days is downloaded again when brouter.de has a newer one – on mobile data too (owner:
  "routing is most important"; "Maps only on Wi-Fi" also holds it). Then the plan is computed again; "Route updated –
  newer way data" only when the way changed (`RoutingDataCheck.differs`). Not during a tour.

