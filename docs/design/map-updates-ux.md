# Map updates – when and how (owner decisions 2026-10-21, built in 0.8.3)

## Decided (owner, 2026-10-21) – this replaces the proposal below
- **Plan on the latest map:** after every route computed in Plan, the installed map areas along it (10 km corridor)
  are checked for a newer version (`CellTriggers.freshenAlong`, reason `refresh`, each area at most hourly). Nothing is
  updated while the app is idle (no monthly / start-up refresh any more).
- **Already built on the server → fast:** download, the map is veiled ("Loading the newer map…") for those seconds,
  then switches (`MapUpdateLayer`). **Must be built first** (new map style, or OSM older than the server's 35 days)
  → small hint "Newer map for this area being prepared · about N min"; planning goes on with the current map.
  The modal overlay is **only** for areas without any detailed map.
- **Mobile data by default** for everything (no Wi-Fi/mobile question); Settings → "Maps only on Wi-Fi" turns it off.
- **No switch during a tour:** `MapCells.holdUpdates` while in Navigate; new areas at the tour's edge (T5) still load.
- **Routing data** (BRouter tile, ~200 MB, brouter.de weekly) is what decides the way: after a route, at most every
  12 h, a tile older than 35 days is downloaded again when brouter.de has a newer one – on mobile data too (owner:
  "routing is most important"; "Maps only on Wi-Fi" also holds it). Then the plan is computed again; "Route updated –
  newer way data" only when the way changed (`RoutingDataCheck.differs`). Not during a tour.


Trigger: owner tapped Settings → "Update maps now" on 5G; the app said "Checking 4 map areas … downloads on Wi-Fi"
and nothing visible happened.

## Today (0.8.2)
| Case | When | Mobile data | What the user sees |
| --- | --- | --- | --- |
| New area needed (T1/T3/T4/T5) | point set, new place, corridor, tour edge | own point yes; neighbours ask | overlay (Cancel, Wi-Fi/mobile question) |
| Outdated content (schema, e.g. small streets) | every app start | yes, silently | overlay "Updating the map for the new map style" |
| Newer OSM data (T6) | monthly | no – waits for Wi-Fi | nothing |
| "Update maps now" (Settings) | tap | no – waits for Wi-Fi | one toast, then nothing |

## Proposal
Three kinds of update, each with one clear rule:

1. **Must-have** (the app needs data the area lacks – new content schema): automatic at start, as now.
   On mobile data ask once with the size ("Update 4 map areas for the new map style (≈ 40 MB)? Wi-Fi / mobile data"),
   on Wi-Fi no question. Overlay while it runs (Cancel = keep the old map, ask again next start).
2. **Freshness** (newer OSM, nothing broken): quietly in the background, monthly, Wi-Fi only, never asked.
   One small note afterwards: "Map updated (4 areas, OSM 21 Oct)".
3. **"Update maps now"** (user's explicit wish): shows the overlay right away with progress per area. On mobile data
   it asks Wi-Fi / mobile with the size; "Wait for Wi-Fi" closes the overlay and Settings shows "4 areas wait for Wi-Fi".

Rules for all three:
- **Never swap the map during a tour** (Navigate / recording): a new map version is installed, but switched on only
  in Plan mode or at the next start – a style reload while following can stop tracking (CLAUDE.md "Map camera").
- The old map always stays usable until the new one is complete (already true: generations in CellStore).
- Settings → Offline maps shows per area: "OSM 21 Oct · up to date" / "new map style – update waits for Wi-Fi".

## Questions for the owner
1. Must-have updates on mobile data: ask with the size (proposal), or just do it silently (today)?
2. Freshness: monthly on Wi-Fi OK? Show the small "Map updated" note or stay silent?
3. "Update maps now" on mobile: ask (proposal) or always download?
4. During a tour: hold the switch to the new map until Plan mode / next start (proposal)?
