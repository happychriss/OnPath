# Field test 2026-10-19 (Ultental / Zoggler Stausee) – complete status

Owner hiked with the app; the cloud session fixed and released during the day (0.6.2 → 0.6.9).
Open items also live in `docs/backlog.md`.

## Reported by the owner

| # | Issue | Status |
|---|---|---|
| 1 | No POIs at the lake | **Done 0.6.2**: package rebuilt with POIs, bbox extended to Ultental |
| 2 | Food at bus stops: show from lower zoom; merge stop pairs zoomed out, separate when zoomed in | **Done 0.6.2/0.6.3**: bus + food from z14, pairs merged until z16; restaurants/cafés from z12 like lifts |
| 3 | Gap west of the lake / Mutegg; path to Kugelwiesen missing (Komoot has it) | **Done 0.6.6**: high-contrast theme lacked the ▲ glyph range → MapLibre dropped whole base tiles. Glyphs added + FontGlyphsTest. Before: restart after map import + package check (0.6.4). **Owner to confirm on the phone.** |
| 4 | Debug/export interface that can be switched off | **Done 0.6.5** (Settings → Diagnostics), Infinity bug fixed 0.6.6 |
| 5 | Errors must never go unseen (12–14 h runs) | **Done 0.6.7**: central problem log, MapLibre warnings + crashes captured, ~120 catch sites reviewed, list in Diagnostics |
| 6 | Arrival time jumps (12:45 → 13:15 → 12:45) after editing the route during the tour | **Laptop session** (arrival/time-to-go robustness, in progress) |
| 7 | Elevation profile: finger on the profile moves a dot on the route | **Done 0.7.1** (Navigate: dot + map at the spot, back after 4 s) |
| 8 | Wants to test today → trail safety | **Done 0.6.8** (see "found by Claude") – **verify on the trail** |
| 9 | Opening the phone again in Navigate: jump to own position | **Done 0.7.1** |
| 10 | Position dot sometimes under the lines | **Done 0.7.1** for the known cause (search ring now added below the position layers); if it still happens, owner sends a screenshot |
| 11 | Green routes and black paths too pale at some zooms; one style instead of normal + high contrast | **Done 0.7.0** (`docs/design/map-colour-analysis.md`) + new map package |
| 12 | Ground types: symbols for wetland, farmland, rock/scree, greyish residential (z14–16); no white in path drawing | **Done 0.7.0** (wetland, farmland, rock, sand symbols; residential light grey without hatch; scree not separated) |
| 13 | Clear the route from the map after it is saved | **Done 0.6.9** (saved plan leaves the map after the tour) + **0.7.1** ("Clear route from map" in Tour saved for an unsaved plan) |
| 14 | Compass: turned by hand → tap north → tap walking direction | **Done 0.7.1** (both modes) |
| 15 | 3D view | Not wanted – dropped |

## Found by Claude during the day

| # | Finding | Status |
|---|---|---|
| A | Hiking routes used via ferratas (820 m Heini-Holzer on a T2 hike) and paths above the T level were only "expensive" | **Done 0.6.8**: forbidden; also abandoned, invisible trails; fords/informal penalised |
| B | Bike profiles used T2/T3 mountain paths (8 km on Vigiljoch → St. Pankraz) | **Done 0.6.8**: T3+/S3+ forbidden, T2 +30 |
| C | MTB T4+ at cost 9000 made BRouter time out (> 15 s) | **Done 0.6.8**: forbidden instead |
| D | Route silently ends short (87–383 m) when the destination is not reachable | **Done 0.6.8**: plan message "The route ends … away from point …" |
| E | A T5 setting made routes seek T5 trails (preferred = limit) | **Done 0.6.8**: preferred capped at T3 |
| F | Via ferratas mapped as `highway=path` are only caught by their sac_scale (no via_ferrata_scale key in lookups) | Open / known limit |
| G | Network (cell/Wi-Fi) positions could make the dot jump km in the mountains | **Done 0.6.8**: ignored after the first GPS fix; "last fix N min ago" |
| H | Open off-route question did not remind again; "U-turn" wording; arrow banner looked like a path | **Done 0.6.8**: re-vibrate, "Hairpin", "straight line" |
| I | No way to read own coordinates for an emergency call | **Done 0.6.8**: position line in the opened status card, tap = copy |
| J | At z12 the black path covers the green route line (route looks black) | **Done 0.7.0** (wider band at low zoom) |
| K | Route band (routes.geojson) and base-map path are not exactly on top of each other at z16 | Open – routes use 3 m simplification (`SIMPLIFY`), paths now 0.5 m; try `FINE_SIMPLIFY` for routes (size +) |
| L | "Waiting for GPS · last fix …" + moving time may be cut off in the narrow card | Open, minor – check on the phone |
| M | `trails-low` had an invalid style expression (layer dropped) | **Done 0.6.4** |

Also done later that day: road types (streets two grey lines, tracks brown with the surface in the dot spacing),
wider cable-car ropes; map data +18 MB on the phone (`paths.geojson`; PMTiles follow-up in backlog).

## Still open from before (not hike-specific)
Owner map todos (contours always, shrub symbols, trail numbers abroad); safety audit batches B (warning line in the
plan sheet) and D (pipeline: via ferrata, visibility, cliffs, fords, bus stops); unverified paths; attribution
question; EN + DE strings; robustness leftovers (BRouter terminate, CI on every push, backup rules, background
location permission); Hetzner server not yet run on a real account; owner may delete branches `wip-0.6`,
`wip-no-silent-errors`.
