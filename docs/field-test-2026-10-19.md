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
| 6 | Arrival time jumps (12:45 → 13:15 → 12:45) after editing the route during the tour | **Open** – cause found (new Navigator per line change resets progress; TripPace limiter reset; exact list compare). Proposal in backlog, owner: think first |
| 7 | Elevation profile: finger on the profile moves a dot on the route | **Open** (todo on request) |
| 8 | Wants to test today → trail safety | **Done 0.6.8** (see "found by Claude") – **verify on the trail** |
| 9 | Opening the phone again in Navigate: jump to own position | **Open** (todo on request) |
| 10 | Position dot sometimes under the lines | **Open** – candidates: search-mark layers added above the dot; style reload re-adds location layers below. Need a screenshot |
| 11 | Green routes and black paths too pale at some zooms; one style instead of normal + high contrast | **In progress**: 3 proposals (rejected: no band edges, no white), colour matrix; v2 with ground symbols being built |
| 12 | Ground types: symbols for wetland, farmland, rock/scree, greyish residential (z14–16); no white in path drawing | **In progress** (matrix v2) |
| 13 | Clear the route from the map after it is saved | **Done 0.6.9**: saved plan leaves the map when the tour ends (Undo brings it back); Clear never asks. Optional direct button: open |
| 14 | Compass: turned by hand → tap north → tap walking direction | **Proposal** in backlog, waiting for go |
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
| J | At z12 the black path covers the green route line (route looks black) | In the style work (#11) |
| K | Route band (routes.geojson) and base-map path are not exactly on top of each other at z16 | Open – check overlay geometry vs base tiles |
| L | "Waiting for GPS · last fix …" + moving time may be cut off in the narrow card | Open, minor – check on the phone |
| M | `trails-low` had an invalid style expression (layer dropped) | **Done 0.6.4** |

## Still open from before (not hike-specific)
Owner map todos (contours always, shrub symbols, trail numbers abroad); safety audit batches B (warning line in the
plan sheet) and D (pipeline: via ferrata, visibility, cliffs, fords, bus stops); unverified paths; attribution
question; EN + DE strings; robustness leftovers (BRouter terminate, CI on every push, backup rules, background
location permission); Hetzner server not yet run on a real account; owner may delete branches `wip-0.6`,
`wip-no-silent-errors`.
