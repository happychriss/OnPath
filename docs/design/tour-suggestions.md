# Tour suggestions (Rundtour-Vorschläge)

Status: **a proof of concept on the computer** (`tools/suggest-poc/`), not in the app, not in the planner. The owner
wants "etwas echt Cooles, das es noch nicht gibt": say where you are, how long, how hard and what you like, and get a
few loops a person who knows the place would walk or ride – not loops other users uploaded. This note is the current
state; the history is in git (`git log -- tools/suggest-poc docs/design/tour-suggestions.md`; the first version with
its 733 settings is the tag `suggest-poc-v1`).

## 1. Principles

**Three levels, as a person would think** (owner 2026-11-14: "deine 3 Stufen sind klug und so, wie ein Mensch das
machen würde"):

1. **What a way is** – facts, the same everywhere: ground, traffic, built-up, green, how deep inside nature, water,
   signed route, rail trail. Nothing here is tuned to a region.
2. **What the place offers** – measured from the ways in reach of the start: its own yardstick (what is poor here,
   what is as good as it gets here), what stands out here (areas and lines), and the unavoidable way out (from a town
   centre every loop starts through houses – that part is the same for all loops and is not held against them).
   In flat farmland a quiet field way is top; in the mountains the bar is higher by itself.
3. **What this person wants** – one character scale *gemütlich … wild* from the activity and the level (T1 relaxed on
   made paths … T5 wilderness; touring smooth and calm, gravel further out, MTB trails). Nature, quiet and good paths are always built in; the one choice is the Ziel (2026-11-17).

**Five axes, each 0–100, every suggestion explains itself in these words:**

| Axis | Means | Measured |
| --- | --- | --- |
| Natur | green beside the way, being deep inside nature (an edge with a view and a shore count too), a varied landscape | relative to the place |
| Ruhe | away from traffic and houses | relative to the place |
| Weg | the ground fits the character; on foot a narrow path over a wide track | relative to the place |
| Besonderes | water, known routes by rank, a rail trail, a named protected area, an edge with a view, castles / ruins / chapels / viewpoints, a signed loop walked round, aims reached (a summit, a lake, a hut in the middle …), a tip | relative to the place |
| Form | time and ascent inside the wished ranges, nothing walked twice, not crossing itself | absolute |

"Relative to the place" = between the value that is poor here (a low quantile of the ways in reach) and what the ways
beyond the way out offer at their best (a high quantile per axis, `place.top`; since 2026-11-16 – before, it was the
best loop of the pool): each loop is judged on the place alone, so its points do not change when another loop is
built, and 100 means "as good as a loop gets here". The absolute
facts (km in a protected area, % through houses, longest stretch at a road) stay on the card, so a good score in a
poor place does not hide what the place is.

**Few hard rules:** a way the activity may not use; a loop that meets its own way more than once; walking more than
300 m along a road with cars outside a town; more ascent than wished; clearly longer than wished.

**Why this shape:** the first version was tuned on the Rheinland and Meran with hundreds of thresholds; it would have
delivered nonsense elsewhere, and nobody could say any more why a loop won (owner: "nicht überkomplizieren … nachher
gar nicht mehr wissen, warum").

## 2. The wish

| Field | Values | Note |
| --- | --- | --- |
| start | [lon, lat] | |
| activity, t_level | hike (T1–T5), bike, gravel, mtb | gives the character |
| hours (or duration) | **[from, to]** hours | a single number is widened by ± 1 km on foot / ± 5 km by bike |
| ascent | **[from, to]** metres up | missing = from flat up to what is usual for the activity and level; one end alone is fine ("bis 350" = 0–350) |
| ziel | none or several of gipfel · wasser · aussicht · einkehr · kultur | what the loop should lead to (§3 "Aims"); none = the finest in reach |
| tips | yes / no | use the AI's region tips where a file exists |

The controls Natur, Ruhe, Bekannte Wege (niedrig / mittel / hoch) and "am Wasser entlang" are gone (owner
2026-11-17): nature, quiet and good paths are always built in, as on a Traumpfad – their middle setting is the fixed
one. „Wasser“ as a Ziel also does what "am Wasser entlang" did (banks and brooks are lines worth following, a loop at
the water counts as leading to it).

Ranges instead of fixed values (owner 2026-11-14: "eine Range bei den Höhenmetern und eine Range bei der Zeit … man
erkennt den Willen des Nutzers besser"): inside both ranges every loop is equally fine, so the other axes decide; a
bit short or a bit flat falls off gently; above the ascent or clearly too long is not offered.

No separate "level" of effort and no pace in the wish (owner 2026-11-14: "den Level brauchen wir nicht mehr … was
Level dann noch macht, ist auch für den User unklar"): the activity as the app has it (Wandern with its T level, Rad,
Gravel, MTB) sets the character, the two ranges say how long and how much climbing; the activity's normal pace turns
time into distance. No `surface` either (owner 2026-11-15: "die Auswahlkriterien beim Weg sind genau die, die ich
generell sonst auswähle"): the way the user wants is said by the same choice as everywhere in the app – Rad, Gravel,
MTB, Wandern T1–T5 (removed from the POC 2026-11-15).

## 3. The method, as the planner should build it

Inputs: the routing data around the start (BRouter's nodes and links with the profile's verdict "allowed" and the way
tags) and the map areas (see 4). All numbers are in `tools/suggest-poc/settings.json` (about 70, each explained there).

1. **Character** from the wish (`character.py`): position on the scale; axis weights, how much of Natur is depth, the
   worth of a signed route, the welcome of each ground kind, the two ranges.
2. **Network**: the ways within 0.42 × the longest wished length around the start (wheels: minor ways left out unless a
   signed cycle route runs on them). A start on a dead piece of way moves to the nearest connected one.
3. **Facts** per way (`facts.py`): allowed; ground paved / firm / natural / rough; traffic (a road by its class, calmer
   at 30 km/h; a separate way right beside a road counts 0.7 of it – found by geometry); built-up (inside settlement
   land); green within 100 m; depth = nature within 300 m and 900 m; water (big water 1, small water or a brook with a
   green strip 0.5); signed rank (routing data flags and named routes of the map); rail trail; ferry; the named
   protected area and the known routes / lakes it lies on; **an edge with a view** 0..1 (from the map area's terrain
   model: the ground 90–300 m away on one side lies on average 20 m (0) … 60 m (1) lower, times how open – not under
   trees – that side is); **a sight** within ~50 m (castle, ruin, chapel, viewpoint, cave, waterfall from
   `pois.geojson`).
4. **Place** (`place.py`): per way the absolute value on Natur, Ruhe, Weg, Besonderes for this character (an open
   edge with a view counts as nature too – leaving the forest for it costs no Natur; so does a way at water outside
   settlements, 0.8 × its water fact, since 2026-11-17; the edge and a sight count 0.8 on
   Besonderes); the place's
   yardstick per axis (25 % and 95 % quantile by length); "how good is this way here" = the weighted mean on that
   yardstick; search cost = metres × (1 + 4 × (1 − good)) × (1 + 3 × traffic). One search from the start. **What
   stands out** = the best 30 % of the ground: connected, and split by named protected area, it forms the *areas*;
   known routes, lake shores, rail trails, banks and brooks form the *lines*. **The way out** = the distance at which
   the ground becomes as good as usual here (at most 20 % of the wished length).
5. **Loops** (`generate.py`): every loop is a list of turning points given to one assembly routine: start → point → …
   → start by cheapest ways; ways already used cost 6 ×, ways touching them 1.6 × (so the way home keeps apart: nothing
   doubled, no crossing); the first 700 m (wheels 2.5 km) from the start are free to reuse (a short way out and
   back); a spur walked out and straight back is cut. Turning points come from:
   - per direction the most pleasantly reached point at the right distance (at the short and the long end of the
     wished range), alone and in pairs a quarter turn apart;
   - when a climb is wished: points high enough and near enough for the time;
   - chains: the stand-out areas in order around the start, each through its heart (a single area: through the heart
     to its far side);
   - lines: join at the nearest point, follow it both ways (its ways cost 0.3 ×), come home another way; built once,
     then the turning point is moved once so the length fits;
   - a signed route that is itself a loop of fitting length: walked round. Where its ways close into a ring, the ring
     is walked exactly. Else pieces are missing (today a way carries only its most important route); seen from its middle a
     loop lies all around (a line or an arc does not): the route's points in 24 directions (at least 18 taken), each
     direction's mean point in order, are the ring – turning points on the route there (7 on foot, 9 on wheels), its
     ways at 0.3 ×, gaps by the best way (measured against official loops, `tools/suggest-poc/README.md` "Official
     loops").
   - **aims** (`aims.py`, 2026-11-17): what a loop can lead to – a named summit (base.pmtiles `mountain_peak`, with
     its height), a lake (its shore), a viewpoint, a hut / restaurant / café (Einkehr), a castle / ruin / chapel /
     museum (Kultur), a waterfall / named spring (Wasser). One mechanism for all kinds: an aim a way leads to (within
     80 m; a summit 120 m and not more than 80 m lower; a lake: the ways along its shore) is a turning point – alone
     when the time fits, else with a turning point a quarter turn away on either side, or two aims in a chain; for a
     summit at the end of a dead end the way up and back stays in the loop (not cut as a spur, not "walked twice"); a
     lake's turning point is the shore node farthest from where one meets it, its shore ways at 0.3 × while the loop
     aims at it – so the loop goes round, or along one side there and the other back. Aims in the town one starts
     from (on the way out) are none. Per wish the 10 finest in reach (wheels 6) by worth and nearness, a wished
     Ziel's aims first (up to 16).
   When fewer than 12 loops (wheels 8) are built – the mountains: few ways, most points too high for the time – the
   turning points are tried again in twice as many directions and at three times across the wished range.
   On foot a loop that would be out for a long stretch along a road is built once more with such stretches 8 × dearer
   (`search.road_repair`): the way to the same turning points mostly exists without the road (Nordkirchen on foot:
   12 of 22 loops were out, 4 of 14 after it). Kept: at most one meeting with the own way, time within −30 % / +10 %
   of the range, ascent not above it – for official loops too. When no loop stays under a wished maximum that is lower
   than usual, the loops are built once more with climbing dearer (`search.climb`; always on it made the comparison
   sets worse), and if still none fits, the flattest are shown, each with "↑ 416 m – mehr als gewünscht (350 m)" and
   a note in the run (Große Dhünntalsperre, bike, ≤ 350 m: the flattest has 416 m).
6. **Aims reached and the quality-trail measures** (2026-11-17): a loop *reaches* an aim when it passes within 80 m
   (summit 120 m) beyond the way out; a lake when it runs along 30 % of its shore (or 1.5 km of a big one) – along one
   side counts half, round (70 %) full. Each reached aim adds its worth (summit, lake 1.0; castle 0.9; hut,
   waterfall 0.8; viewpoint 0.7 …) × 0.06 to the raw Besonderes, at most three, × 2 for a wished Ziel; a place to eat
   counts full in the middle of the loop, nothing at its ends. With a Ziel the Besonderes weight is × 1.5, per chosen
   kind its best loop is shown, and loops that lead to it come first. From the published criteria of German quality
   trails (Deutscher Wanderverband "Qualitätsweg Wanderbares Deutschland", Deutsches Wanderinstitut "Premiumweg"):
   *way format* – on foot Weg is (1 − p) × the ground's welcome + p × the way's width (narrow path 1, track 0.5, rough
   track 0.8, road / wide made way 0.2; p 0.2 gemütlich … 0.4 wild); *variety* – changes of the landscape (forest /
   open land / water / village, the dominant kind per 400 m) per km: Natur × (0.85 + 0.15 × min(1, changes / 0.5 per
   km)) (Wanderverband: one change per 2–3 km).
7. **Judging** (`score.py`): the four place axes as means over the loop beyond the way out, read between "poor here"
   and the best loop of our own pool (an official loop is measured on it, it does not move it); on Besonderes + 0.05
   per sight passed (at most 3) and + 0.15 for a signed loop walked round, ours or an official one (what the place's
   makers laid out) – only while its longest stretch along a road with cars stays within 300 m (owner 2026-11-16:
   on wheels the road rule beats the bonus; on foot such a loop is out anyway); Form from the ranges, the share walked twice and a meeting; points = weighted mean.
8. **Selection** – one selection over all cards: our loops and, where official loops are known (the POC page:
   Rheinland-Pfalz, Nordrhein-Westfalen), the official loops in reach that fit the wish (time as for our loops, ascent
   not above the wished maximum), judged with the same yardstick – as a ride from the wished start (owner 2026-11-17:
   "die Anfahrt einbeziehen"; the POC page – the Kotlin core follows in the batch of FROZEN.md): the cheapest way to the loop's best joining point, once round in its order (either
   direction), home (the same way back allowed, a different one preferred); time, km, ascent and every axis are those
   of the whole ride, the way there treated like any loop's way out. No two
   cards share more than 75 % of the shorter one's way beyond the way out (`select.overlap`; owner 2026-11-16: "60 %
   … vielleicht 75 %"); an official loop gets + 5 points in the selection only, so between two similar loops the
   official one wins unless it is clearly worse (owner: the official loops are generally good). The best first; with
   "am Wasser entlang" the best loop really at the water is always shown and first; then what stands out gets its best
   loop if within 15 points: an official loop inside the wished time, a first-rank line, each area; then further
   different loops within 18 points. At most five. While fewer than three are shown, further different loops may be
   up to 30 points behind (Nordkirchen on foot: the Hirschpark loops are far ahead of everything else, the other
   directions still give walkable loops); fewer than three is fine when the rest is poorer still. The list is sorted
   by points (loops inside the wished time first).
9. **Words**: a title from what the loop leads to (a wished Ziel's aim, else the finest reached: a summit, a lake gone
   along, a castle …, one per kind) and what it really uses (protected areas with ≥ 0.8 km, a lake, the main known
   route; else direction), a reason line with kilometres, honest hints (longer / shorter than wished, at a road, through
   houses, walked twice, crosses itself, at the edge of a range), "stark bei … · schwächer bei …", and **one line
   on what the loop really passes** (the aims first: "Gipfel Eckbauer (1237 m)", "rund um den Ferchensee", "Einkehr
   Gasthaus Ferchensee"; protected areas, a lake, castles / ruins / chapels, km at edges with a view, a
   viewpoint, the main known route, water, a rail trail) – for an official loop too (never its OSM / operator
   description, owner 2026-11-16). Long protected-area names are shortened for titles and the line (without brackets
   and what follows " mit "; a list gives its more telling part).

For the port: `facts`, `place`, `character`, `generate`, `score` are pure (no I/O); the edges are the network source,
the map-area reader and the grids. `tools/suggest-poc/cases.py` holds the owner's comments as checks – they become
the port's tests.

## 4. Data it needs

| Need | Source in the POC | On the phone |
| --- | --- | --- |
| nodes, links, allowed, way tags (highway, surface, tracktype, sac_scale, maxspeed, route_*, railway, estimated_noise_class, estimated_river_class) | BRouter's node cache through a small Java service (`service/RouteService.java`, `/graph`) | the same rd5 files; reading the node cache through `RoutingContract` is new |
| forest / heath / wetland, protected areas with names, settlement land, open water, place names | `base.pmtiles` of the map areas (`landcover`, `park`, `landuse`, `water`, `place`) drawn into a 45 m grid (`land.py`) | the same files |
| known routes with name and rank, trail names | `overlays.pmtiles` (`routes`, `trailnames`) | the same files |
| lakes with names, sights (viewpoint, castle, ruin, chapel, cave, waterfall) | `base.pmtiles` (`water`, `water_name`), `pois.geojson` | the same files |
| heights for "edge with a view" | `terrain.pmtiles` of the map areas (Terrarium WebP, zoom 12) sampled into the 45 m grid (the POC decodes with Pillow) | the same files |

Missing in our map: which pieces form one circular route and in which order (a signed loop cannot be offered "as it
is" – the suggest file below has them); ferries, monasteries, city parks, cable cars as named things. Map areas a run
needs are fetched as the app does;
a run says when an area cannot be had (without the map there is no nature, no settlement, no names).

## 4a. The suggest file of a map cell (stage 1 of the build plan, 2026-11-16)

Everything in rows 2–5 of the table, plus the signed loops, computed **once per map cell by the map pipeline** into one
small file `suggest.bin`; phone, browser and the POC only read it. Built: the format + reader (`tools/suggest_file.py`,
standard library only), the producer (`tools/build_suggest.py`), the POC's reader switch. In the pipeline as cell
layer `suggest`, schema 9 (laptop 2026-11-17, branch `laptop/suggest-layer-2026-11-17`, not on main yet: test cells
first – notes below, `docs/design/map-hosting.md` §7c).

**Format** (exact bytes: the head of `tools/suggest_file.py`): 8 plain bytes – magic `OPSG`, u16 format version (1),
u16 0 – then one zlib stream. Inside: sections of 4 letters + u32 length + payload; a reader skips sections it does not
know. Numbers little-endian, varints, coordinates in 1e-6 degrees, lines as differences. A reader needs an inflater and a
byte buffer, nothing else (Kotlin: `java.util.zip.Inflater`; no Android, no image decoder, no tile reader).

| Section | Content | Hilden 10-531-342 | Traumpfade 10-532-345 | Meran 10-543-361 |
| --- | --- | --- | --- | --- |
| `GRID` | 550 × ~560 squares of ~45 m, one byte: nature · protected · built-up · water · trees | 32 KB | 27 KB | 24 KB |
| `AREA` | per square the number of the protected area + its names | 6 KB | 4 KB | 1 KB |
| `HGHT` | per square the height in metres (int16, as differences to left + above − above-left) | 66 KB | 108 KB | 172 KB |
| `PLAC` | place names with point and class ("Wald bei <Ort>") | 3 KB | 2 KB | 1 KB |
| `ROUT` | signed routes as the map has them: kind, label, rank, pieces (which way is on which route; following a line) | 70 KB | 62 KB | 59 KB |
| `LAKE` | named lakes with their shore | 4 KB | 0.3 KB | 0.3 KB |
| `SGHT` | sights: kind, name, point (viewpoint, castle, ruin, chapel, cave, waterfall) | 2 KB | 4 KB | 3 KB |
| `LOOP` | signed loops in walking order: OSM id, kind hike / bicycle / mtb, T level, metres to the nearest parking place, length, ascent (or unknown), name, ref, network, the closed line from the parking place | 25 KB (26 loops) | 22 KB (20) | not built |
| **file** | | **212 KB** | **233 KB** | **264 KB** |

All 144 cells the POC has on this computer: median 202 KB, 90 % under 278 KB, largest 418 KB; the 28 with loops median
249 KB. These cells are schema 7; with `routelines` (schema 8) `ROUT` grows – not measured yet.

**Decisions and why:**
- *Heights, not a ready "edge with a view":* the edge value is a rule with thresholds (20 m … 60 m lower, 90–300 m
  away, open = not under trees – `land.rim`); stored as a value, every change of a threshold would rebuild all cells.
  The height grid is the raw thing and with the plane guess it is small (flat 66 KB, alpine 172 KB). At 90 m squares it
  would be a quarter of that, but the edge value changes – not done.
- *Raw, not scored:* classes, heights, lines. The file keeps more loops than the method offers today (on foot 1.5–35 km,
  on wheels 5–120 km; the method takes 4–20 / 15–60 km), jogging circuits included; the reader filters (`official.KM`,
  `NOT_A_TOUR`). Parking is the distance in metres, not yes / no.
- *No texts* but names: no description, no operator text (owner 2026-11-16).
- *One loop, one cell:* a loop lies in the file of the cell that holds most of its length; the cut of every cell has
  the route relations whole (`osmium extract -S types=…,route`), so every cell decides the same. A start whose reach
  holds the whole loop has that cell loaded.
- *Loops need the OSM cut:* `routelines` in the tiles is clipped at tile and cell borders and simplified – it gives no
  order across a border. The producer makes its own pass over the cell's cut (pyosmium, filters inside osmium; a
  route whose ways are together longer than any loop we keep is not chained): 3–9 s per cell.
- *Ascent of a loop* comes from the cell's own heights, so it is known only for loops that stay inside the cell
  (82 % of the 1298 in the 28 cells); else "unknown" and the reader takes the way network's heights as today. A rough number for sorting out
  loops before they are laid onto the network, not for the card.

**Producer:** `python3 tools/build_suggest.py OUT/suggest.bin --cell 10/X/Y --from <folder with base.pmtiles,
overlays.pmtiles, pois.geojson, terrain.pmtiles> --osm cell.osm.pbf --ids <osmium renumber index> --osm-date D`.
Needs what the job image has (pyosmium for the loops, Pillow for the WebP terrain); the rest is standard library and
the small tile reader `tools/suggest_tiles.py` (the POC's former `pmt.py`). The code is lifted, not rewritten:
`land.py` / `named.py` / `official.py extract` now call the same functions. Hilden in the job image: 7 s, 335 MB.

**Reader in the POC:** `SUGGEST_FILE=0` never reads the file (tiles and terrain at run time, as before), `1` reads
only files (a cell without one has no data and the run says "Die Karte wird hier gerade aktualisiert" – what phone
and planner will do), default: the file where a cell has one. Files lie in the cell's folder (`cells.py` downloads
`suggest.bin` once the manifest lists it) or under `SUGGEST_FILES/10-x-y/`.

**Acceptance (2026-11-16, files built on donald for all 144 local cells, loops for 28):**
- Data: for all 144 cells grid, area names, places, heights, routes, lakes, sights read from the file are equal to
  what the POC computes from the tiles – byte for byte.
- Loops: 28 cells (Hilden, the six Traumpfade cells, the cells of the two NRW sets) against the region loops files:
  1010 of 1010 loops there, same line, start, parking, T level, km. Three more in the files: loops across the
  NRW / Rheinland-Pfalz border, whole only in a cut from both extracts.
- `cases.py`: 14 wishes, every loop of every pool equal (line, points, axes, numbers); 54 of 54 statements both ways.
- `official.py compare`: Traumpfade 21, NRW hikes 24, NRW wheels 14 loops – every row equal, so the medians too
  (Traumpfade first loop on the official 81 %, 11 of 21; NRW hikes 20 %; NRW wheels 13 %).
  No difference to explain: the file holds the same squares and whole metres the POC computed before.

**Compatibility:**
- Released apps: they download every file a manifest lists and join only the names they know
  (the download loop in `MapCells`, the join's `CellStore.LAYERS` / `POIS` / addresses) – `suggest.bin` is fetched,
  ignored and removed with the download folder. But a new version is downloaded **whole** (new folder
  `download/<cell>/<version>.part`, nothing reused from the installed version): the s9 bump costs a phone its whole
  cell again (9–23 MB) at its next refresh of that cell, not just the 0.2–0.3 MB file (laptop 2026-11-17, read in
  `MapCells.downloadAndInstall`; not tried on a phone).
- The reader (later the Kotlin core): no file for a cell in reach, or format version below the one it needs, or
  `content` below the one it needs → no suggestions from that cell and the line "die Karte wird hier gerade
  aktualisiert" (+ ask for the refresh). A format version above the reader's own → the same line with "App
  aktualisieren". New sections and new fields at the end of `HEAD` need no new version.
- Two numbers, on purpose: the **layer schema** in `cells.py` says "this cell's file is stale, rebuild it" (server
  side); the **format version / content** in the file says "this reader can use it" (reader side).

**Integration (IR's patch, applied by the laptop 2026-11-17 on top of schema 8; the patch file is gone):**
- `cells.py`: layer `suggest` (file `suggest.bin`) with `LAYER_SCHEMA` 9, `CELL_SCHEMA` 9; it needs the cut
  (`OSM_LAYERS`); `LAYER_INPUTS = {"suggest": (base, overlays, pois, terrain)}` – `rebuild_plan` rebuilds it whenever
  one of them is rebuilt.
- `build_cell.sh`: step `suggest` after terrain / contours, from this run's layers or, in a partial rebuild, from
  `SUGGEST_FROM` (the published version's files).
- `job_build.py`: `layer_inputs` fetches those published files (size + sha256 checked) before a partial build; step
  progress.
- Tried on donald in the job image: `LAYERS=suggest` for Hilden = 37 s (cut 11 s, renumber 2 s, suggest 24 s – that
  was before the producer's speed-up, alone it now takes 7 s), version `…-s9`, manifest with 7 files and `layers.suggest.schema 9`.
- Laptop 2026-11-17: `test_cloud.py` with the layer (fetch of the inputs from the store, damaged input → whole
  build), doorman `CELL_SCHEMA` 9 (`wrangler.toml`, `config.js`), docs. Test cells (Hilden 10-531-342, Traumpfade
  10-531…533-345…346) in the laptop's `onpath-data/store-test-routes`, built from the published s8 / s7 versions with
  the Geofabrik extracts of 2026-11-15 (`LAYERS` from `rebuild_plan`: suggest, or overlays + suggest for an s7 cell).
  The bump makes every phone download its cells once more (the whole new version) for a file it does not use –
  **owner 2026-11-17: accepted** („Normale Kartenschicht“: the file goes into every cell as a normal layer; all cells
  are rebuilt with it).
  Test cells: version `2026-11-15-s9`, partial build 13–44 s for suggest only, 59 / 105 s with the overlays;
  `suggest.bin` 268–424 KB (with `routelines`; Hilden 268, Traumpfade 274–424). Acceptance: the POC from the tiles
  (`SUGGEST_FILE=0`) and from the file (`SUGGEST_FILE=1`) give equal grid, names, labels, places, heights, routes,
  lakes, sights in all 7 cells; loops against `build_suggest.loops` over the whole extracts (owned by the cell): 229 of
  229 equal (line, km, T, parking), Hilden has 2 more – loops across the Düsseldorf / Köln border, whole only in the
  merged cut.
  **Rebuilt 2026-11-17 with content 2 (`AIMS`, after IR's section):** version `2026-11-16-s9` (Geofabrik 2026-11-16),
  the 7 test cells plus 12 for the acceptance (Hilden's reach 10-531-341, 10-532-341…342; Nordkirchen's 3 × 3
  10-532…534-338…340), 15–75 s suggest only, 71–110 s with the overlays (s7 cells); `suggest.bin` 179–591 KB.
  Acceptance (POC, own route service, no server): data tiles = file in all 7 cells; `cases.py` Hilden / Nordkirchen
  (10 wishes) – the shown suggestions with `SUGGEST_FILE=0` and `=1` are identical (39 of 40 statements both ways;
  `hilden-rad` „≥ 2 Schutzgebiete“ fails in both); `official.py compare --name Traumpfad` (15 loops) identical. One
  catch: the tiles-way reference needs its loops file from the **merged** Münster + Arnsberg extract – per-Regbez
  files miss loops across the border (Pättkestour 4), which the cell's merged cut has whole.
- Later, with stage 4: the app keeps `suggest.bin` per cell at the join (as `addresses/<cell>.sqlite` today).

## 5. The owner's taste rules

**What ranks first** (owner 2026-11-15: "ich würde immer die Traumpfade und die Logik, die wir daher haben, über meine
persönlichen Bewertungen stellen"): when the comparison with official circular routes (`official.py compare`) and one
of his comments in `cases.py` pull apart, the official routes win; the comment's check is dropped or loosened, with a
note. A change still has to hold in more than one region (Traumpfade + other loops of the same extract at least).

- Known signed hiking and cycling routes are preferred, by rank; they are shown on the map.
- Along a road is always bad – also on an official cycle or hiking route; walking on a country road is out.
- Through houses is to be avoided when green is around; a half-town, half-housing loop is not a tour.
- A big connected nature area counts more than a green strip; an area is used, not touched (the Hirschpark).
- At most one meeting with the own way; eights "bringen es gar nicht".
- A short way out and back is fine (700 m on foot).
- Time and ascent are ranges; rather a clean loop at the edge of the range than a tangled one in the middle.
- A brook with green around it is always nice; with "am Wasser entlang" the loop must really be at the water.
- Rail trails are first-class for bikes.
- Few controls ("hoch, mittel, niedrig – nicht zu Tode konfigurieren"); since 2026-11-17 none for taste, only the Ziel.
- What is good depends on where one is and on the chosen level.

## 6. Where an AI fits

1. **As a judge in development** – looking at pictures and numbers of loops at very different places against "is this
   near the best this place and this character allow?". Costs the user nothing; the main guard against tuning for
   one region.
2. **Region tips per map area** – once per area, not per user: the model picks by number from the list of our own map
   objects (routes, trails, lakes, protected areas …) the ones a local would recommend (`region_ai.py`; no name
   matching, no invented places). Measured with the middle model through the command-line tool: 9–11 cents and
   about 15 s per map area, about 600 areas for Germany. The tips are one more input to Besonderes (`tips`). Their
   "why" sentences can be wrong and are not shown as facts. Whether they help depends on the region (bikes: sometimes;
   hikes: rarely so far).
3. **Optional online pick** among finished suggestions, or a spoken wish → the controls. Not built.

## 7. Into the planner – agreed, parked

The owner decides when (2026-11-14: "wir warten noch, bis wir das auf den Planer deployen").

- **Surface (his own proposal, sketch 2: https://claude.ai/artifact/GE3STB3Dj1zFgYTiAK8gjV):** the first tap into an
  empty plan offers a third row "Rundtour von hier" beside "Hier starten" · "Hierhin"; the wish in the plan sheet
  (phone: a window) with Abbrechen · OK; then the wait card, three loops and cards with "Übernehmen" → a normal plan.
  The wish sheet's "Dauer 1 h · 2 h · 3 h · halber Tag" will need the two ranges (time, ascent).
- **Framework:** branch `wip/donald-2026-11-14-planner-suggest-remote` – a wish / suggestion contract with exchangeable
  mechanisms (`poc` on localhost, `remote` = a password-protected service, `ring` stand-in, a slot for the real
  core), a debug setting "Tourvorschläge (Test)", all switched off by default; AI tips are one field of the wish.
- **Remote test set-up:** `tools/suggest-poc/remote/` (a locked front `/v1/suggest`, a test planner served from this
  computer). It accepts the old single values and the new ranges. Its services are stopped; not needed for now (owner 2026-11-15: back home, the road test went without it).
- **Memory on the phone (measured 2026-11-15, `tools/suggest-poc/bench-phone/`):** Pixel 8 hike < 1 s, bike 3 h ~4 s;
  but reading the network through BRouter's objects peaks at 230 of 256 MB (bike 3 h). **Owner 2026-11-15: option 1** –
  the port reads the rd5 data itself straight into compact arrays (~33 MB), no map change; a precomputed compact network
  per map area stays in reserve.
- **The owner's answers 2026-11-16:** (1) wheels: the road rule beats the bonus for going round a signed loop (hikes
  unchanged); (2) copyright: in the app always our own generated text line, never OSM / operator descriptions; badge
  „Ausgeschildert · <name>“ instead of „Offiziell“; nothing from operators' websites; a check before release; (3) all
  map cells get every signed route per way when the build into planner / app comes, not before.
- **In the app code now (IR 2026-11-16, branch `wip/ir-2026-11-15-suggest-core`; invisible, nothing calls it):**
  `app/src/main/java/com/onpath/app/suggest/` – `NetReader` reads the way network around a start from the rd5 files
  into a `WayNet` of plain arrays (nodes, links, metres, metres up / down, way kind, allowed per direction, shape
  points; `WayKinds` = per distinct way description the cost factors and ~20 way tags as byte codes + the signed-route
  bits). The rd5 format is decoded by BRouter only (`MicroCache2` per 1/32° tile, `OsmNode.parseNodeBody`); a tile's
  nodes are kept as small arrays and BRouter's objects dropped node by node; tiles with queued nodes stay, others go
  beyond 12 MB. The walk is the POC's (`/graph`): same nodes and links (Hilden hike 2 h 19 966 / 26 180, bike 2 h
  167 145 / 194 026, bike 3 h 250 000 / 291 748, Zons bike 3 h 245 958 / 288 338). "Allowed" = BRouter's routing rule
  (way cost factor ≤ 9998 and the end node not barred – a few links stricter than the POC's way-only rule); the profile
  and parameters are the router's (`RouterProfile`, a guarded copy of BRouterEngine's key values). `NetSearch` /
  `LoopAssembly` = net.py's searches (full, with stop, bounded, path, seat_start, climb) and generate.py's assemble +
  cut_spurs, with costs passed in. Tests: `NetSearchParityTest` (same numbers as the Python, fixture by
  `tools/suggest-poc/port_parity.py`), `NetReaderTest` (needs E5_N50.rd5: POC counts; the same graph and verdicts as a
  walk on BRouter's own objects for hike T2 / T5, bike, MTB; BRouter with the app's parameters only routes over allowed
  links and takes sampled allowed links), `RouterProfileTest`. The files are in `app/web-shared-files.txt`; TeaVM
  translates them (checked with a temporary export). Memory and time: `tools/suggest-poc/bench-phone/` (`BENCH=net`) –
  bike 3 h: desktop −Xmx72m is enough (Bench.java needed 160m), live peak ~67 MB, read 1.0 s; Pixel 8 live peak ~55 MB
  (was 230 MB), read 6.3 s (was 3.0 s – the per-kind tag text work is slower on ART; to speed up).
  **Stage 2, the method (IR 2026-11-16 night, branch `wip/ir-2026-11-16-port`, ported from the frozen state
  `suggest-poc-frozen-2026-11-16`; still invisible, nothing calls it):** `TourSuggester.suggest(wish, NetSource,
  SuggestFiles, SuggestSettings) → SuggestResult` (shown cards first, then the rest of the pool). Files:
  `SuggestFile` (reader of §4a, `java.util.zip.Inflater` – TeaVM 0.16's classlib has `TInflater`, `TBigDecimal`,
  regex), `SuggestSettings` (a small JSON reader; **one settings file: `tools/suggest-poc/settings.json`**, the core
  gets its text – the POC reads it as before; when the Python method is deleted it moves to the app's assets and the
  planner fetches it like the BRouter profiles), `Character` (+ `Wish`), `Land` (grids, rim, place names – the POC's
  "last grid" rule included), `Named` (routes, lakes, sights + index), `Facts`, `Place` (with `top`), `Generate`
  (turning points, pairs, climb, chains, closed signed rings, lines, road repair, the flatter re-build; **not ported:
  the guessed order of a gapped signed route** – the files' signed loops replace it), `Official` (the LOOP section →
  laid onto the network → candidates), `Score` (judge, numbers, selection, title / reasons / hints / generated line),
  `PyIntSet` (Python's set order: where the method takes "the first of equals", the port takes the POC's first, so
  parity is exact), `SuggestGeo` (Python's round / % / str). Lengths, heights and cost factors are taken as the POC's
  route service printed them (0.1 m, shortest float text) – the same numbers, not new ones.
  **Parity** (`tools/suggest-poc/port/parity.sh`, plain JVM with Gradle's Kotlin compiler, no Gradle run; the 22
  reference wishes of `reference.py` in `~/onpath-suggest-poc/reference/`, the suggest files of
  `~/onpath-suggest-poc/suggest-file/cells/`): with the POC's own network (`graph`) every loop of every pool is the
  same (line, axes, points 0.0 apart), the choice the same in 18 of 22, `cases.py` 65 of 65; the only differences are
  the not ported gapped "Rundweg" loops (0–11 per wish) and official loops whose cell's suggest file has no LOOP
  section yet (Nordkirchen, Altenkirchen) – the four other choices differ only by those. With the app's `NetReader`
  (`reader`: same nodes and links in another order, BRouter's stricter "allowed", the app's profile files without the
  POC's `mainroad_penalty`) 70–100 % of the loops both build are the same, points within 0–2 (one 4.3), the
  choice the same in 12 of 22, `cases.py` 65 of 65. Time on the desktop JVM (warm, network read included): hike 2 h Hilden
  0.23 s, bike 3 h Zons 1.9 s / Nordkirchen 1.4 s (Python: 1.5 s / 14 s). Unit tests: `SuggestCoreTest` (file reader
  against `suggest_file.py`, Python's set order, the character, the settings; fixtures by
  `tools/suggest-poc/port/fixtures.py`).
  **Stage 3a, the batch since the freeze (IR 2026-11-17, branch `wip/ir-2026-11-17-port-batch`; still invisible):**
  official loops as a ride from the wished start (`Official.near`); the wish without Natur / Ruhe / Bekannte Wege /
  am Wasser, with the **Ziel** (`Wish.ziel`: gipfel · wasser · aussicht · einkehr · kultur; Besonderes × `aims.weight`
  with a Ziel; `Character.path` on foot); **aims** (`Aims.kt`: from the suggest files' AIMS section, content 2 – peaks
  with height, huts, restaurants, cafés, springs, museums – plus named sights and lakes: a turning point each; reached
  = passed within reach (a peak not too far below its top), a lake along `lake_min` of its shore; worth on raw
  Besonderes, more for a chosen Ziel, Einkehr full in the middle only); `Generate`: aims alone / a quarter turn either
  side / two in a chain, the lake shore cheap while aimed at, the way up to a summit kept (`keep` in assemble,
  cut_spurs, measure), the "few" retry (twice the sectors at three times), road repair from `road_repair_m` (wheels
  too); `Facts.width` and Weg from the way's width on foot, Natur with `water_natur` and the landscape **variety**;
  `Score.choose`: one card per chosen Ziel and for the two finest aims; the order's sort slack (10 % of the range end,
  at least 10 min); title, reasons and line name the aims („Gipfel Eckbauer (1237 m)“, „rund um den Lautersee“,
  „Einkehr …“). `TourSuggester` notes „Die Karte wird hier gerade aktualisiert …“ (and `SuggestResult.mapUpdating`)
  when a cell in reach has no suggest file **or one older than content 2**. **The card words are in the strings
  module** (`strings_suggest.xml`, DE = the POC's words, EN new; `SuggestText.kt` reads them through `Txt` as the
  planner's other shared files do, numbers with the language's decimal mark; the parity tool compiles a German
  stand-in, `port/strings_stub.py`). Parity against the renewed reference (POC on the content-2 files alone,
  `SUGGEST_FILE=1`, 30 wishes – FROZEN.md): `graph` every candidate both build identical (0.0), the choice the same in
  27 of 30 (the 3 others: a chosen gapped "Rundweg", not ported), `cases.py` 83 of 83 (Python too); `reader` 77–100 %
  of the loops both build the same (mean 92 %), points within 0–2 (one 5.6), choice the same in 21 of 30, `cases.py`
  83 of 83. Desktop JVM, app's NetReader, warm, smallest −Xmx that runs (`port/measure.sh`): hike 2 h Hilden 0.34 s /
  48 MB; hike 3 h Mittenwald, Ziel Gipfel 0.14 s / 48 MB; bike 3 h Zons 3.8 s / 256 MB (the stage-2 code needed the same
  256 MB and 2.9 s; 160 MB failed in its Facts); bike 3 h Nordkirchen 2.3 s / 160 MB. Bike 3 h needs the phone check
  (memory) before stage 4. **Memory pass (IR 2026-11-18, branch `wip/ir-2026-11-18-core-memory`, results unchanged:
  parity graph and reader identical to before)**: facts with few distinct values as 2-byte codes (`CodedDoubles`,
  `SmallInts`), the axis values per way worked out when asked, the land grid read grid by grid (one grid's summed-area
  tables in memory), primitive maps instead of boxed ones (road grid in blocks, named index, bearings, aims grid), no
  copies of cost or search arrays per loop (calm rebuild scales on the way), a 4-ary search heap, one BRouter text per
  way kind. Smallest −Xmx (measure.sh's 3 runs, G1) before → after: hike 2 h Hilden 40 → 24 MB, Mittenwald Gipfel
  32 → 24 MB, bike 3 h Zons 256 → 128 MB, Nordkirchen 160 → 64 MB (one run with SerialGC ≈ live peak: Zons 224 → 96 MB);
  CPU time warm: Zons 2.8 → 2.0 s, Nordkirchen 1.9 → 1.1 s, Hilden 0.31 → 0.21 s.
- **Review 2026-11-16 (owner asked for a step back) – the build plan.** Kept: facts → place → character → candidates →
  five axes → selection; measuring against official loops; the compact network core. Changed:
  **a small suggest file per map cell, built by the map pipeline** (owner 2026-11-16: „Ja, Vorschlags-Datei“): the ~45 m
  grid (nature, built-up, water, protected-area id, edge with a view), sights, and the signed loops as ordered lines
  with name, family, km, ascent, start / parking – measured for the Hilden cell ~0.3 MB gzip (nature grid 38 KB, heights
  115 KB, names 116 KB). The POC's `land.py` / `named.py` / `official.py extract` become that pipeline step; phone and
  browser only read it. Falls away: rasterising tiles and decoding terrain at run time (the browser problem), and
  the guessed order of a gapped signed route (`generate.pool` "Rundweg" by 24 directions) – official loops are
  candidates from data. To port: only facts, place, character, generate, score (~830 Python lines).
  Done before the freeze (2026-11-16): the yardstick from the place alone, not "best loop of the pool" (a loop's points
  do not change when another candidate is added); see `tools/suggest-poc/FROZEN.md`. Stages, each with its acceptance: **0 freeze** (comment round done, sets +
  cases measured, tag; no method change in Python afterwards) → **1 data** (laptop, test cells; the POC reads the file
  and gives the same suggestions – producer, reader and acceptance done 2026-11-16, §4a; the pipeline step is open) → **2 core** (Kotlin on `suggest/`; compare sets and `cases.py` run against it via a
  JVM entry; same results as the frozen POC; the Python method is deleted, settings stay one file) → **3 planner**
  (sketch 4, behind the switch, owner tests in NRW, deploy on his word) → **4 app** → **5 all cells** get the file
  (before 3 goes public; a cell without it says the map is being updated). Owner 2026-11-16 late: the file is built now, with the map rebuild of these days, as its own layer with its own version and a compatibility check – released apps have no tour planner and ignore it.
- **Owner 2026-11-17 – how we work from here:** Python (the POC page) is the lab again; changes are tried there, the
  owner looks at them on the page, and only accepted changes are ported to the Kotlin core in one batch (parity
  restored then). No building twice. Batch so far: (1) official loops measured from the wished start (the ride there and
  back counts); next (2) **aims** – summit, lake, viewpoint, hut / food, castle / monument as turning points, with a new
  wish control to say what one wants („Ziel: Gipfel · See · Aussicht · Einkehr · Kultur“, few choices), and a little of
  the quality-trail logic (paths over wide tracks, variety, views, water, culture; check our five axes against the
  published criteria of Deutscher Wanderverband / Deutsches Wanderinstitut).
  **Owner 2026-11-17: the controls Natur, Ruhe, Bekannte Wege and „am Wasser entlang“ go** – nature, quiet and paths are
  always built in, as on a Traumpfad; the wish is activity · time from–to · ascent from–to · **Ziel** (optional, several:
  Gipfel · Wasser · Aussicht · Einkehr · Kultur; none = the finest in reach).
- **Owner 2026-11-17 evening: the planner build starts (stage 3).** The suggest file goes into the cells as a normal
  layer (owner: „Normale Kartenschicht“ – one reload wave of all phones accepted). Steps: (a) port the batch since the
  freeze (access from the start, aims + Ziel, quality measures, removed controls, sort slack) to Kotlin with parity;
  (b) wire `TourSuggester` into the planner's engine (worker; measure time in the browser); (c) UI: „Rundtour von hier“,
  the wish sheet (sketch first – the wish changed since sketch 4), the cards per sketch 4, the calm map, behind a switch
  that is off; hint / reason texts into the strings module (DE/EN); (d) the owner tests (NRW, Fränkische Schweiz),
  deploy on his word. The app (stage 4) after his acceptance of the planner.
- **Wish sheet (owner 2026-11-17, sketch 5 https://claude.ai/artifact/6NCeiysxvZFTewVh5nhE6K): variant B** – time and
  ascent each as a range slider with two handles; activity, Ziel chips and Abbrechen · OK as sketched; **no icons or
  gimmicks** on the activity (no shoe for Wandern – plain labels as the planner has them).
- **Map with the cards (owner 2026-11-17, replaces "the others as a breath"):** only the chosen card's loop is on the
  map; no faint other loops – one looks at the cards one after the other, that is faster.
- **The five values on a card (owner 2026-11-17, sketch 6 https://claude.ai/artifact/K1TGQdADQCYNREbbafoYWR variant B,
  refined):** no bars; only the biggest standouts within the shown list as pills – strengths light green, weaknesses
  light yellow, three intensities by how far it stands out; at most a few per card. Exact picture: **sketch 7
  https://claude.ai/artifact/DAWvWBJqNkyhxUhnkmNRoV – accepted by the owner 2026-11-17**: at most 2 green + 2 yellow pills
  per card; the card's hints are yellow pills too, ordered by weight (missed wish – ascent, time – first, then road,
  through a town, crossing, last soft weaknesses); no pill when the list is alike on that value; no separate hint lines.
  The POC page (`tools/suggest-poc/page.html`) shows the cards this way since 2026-11-17 – computed in its JS from the
  shown cards' `axes` and `hints`, the Python method unchanged.
  **Pill words (owner 2026-11-17, `suggest_pill_*` in `strings_suggest.xml`):** grün · ruhig · schöne Wege (Rad: guter
  Belag) · viele Highlights / wenig Grün · lauter · wenige Highlights; no Form pill; a weak Weg names its cause (most
  above the list's mean): viel Asphalt · breite Forstwege · holprig (Rad: holpriger Belag · viel Schotter, `numbers`
  ground / `track_pct`); hints „… als geplant“, „25 % im Ort“, „Stück doppelt“, „450 m an der Straße“, „kreuzt sich“.
- **Stage 3 – the ready brief (IR 2026-11-17; starts on mehrtage's word in the backlog, merged one after the other
  with mehrtage's `WebApi` exports):** inputs on main – core `suggest/TourSuggester` (parity 83/83), strings
  `strings_suggest.xml` (pill words, hints), sketches 4 (cards), 5 B (wish: activity labels without icons, two range
  sliders, Ziel chips, Abbrechen · OK), 7 (pills: ≤ 2 green + ≤ 2 yellow, words as in §7), only the chosen card's loop on
  the map; the framework branch `wip/donald-2026-11-14-planner-suggest-remote` is 300+ commits behind – take its ideas
  (switch, test setting, wait card), not its code. Steps: (1) `WebApi.suggest(wish)` in its own file, run in the
  engine worker, the cell's `suggest.bin` + rd5 fetched like the router's data; answer with cards (loop line, km, h,
  ascent, pills, title, text line) and `mapUpdating`; (2) measure in Chrome / Firefox: hike 2 h, bike 3 h (Zons) –
  time and memory; bike 3 h peaks at 256 MB on the desktop JVM, so measure before the UI; (3) „Rundtour von hier“
  wherever „Hier starten“ appears, the wish sheet, the wait card, the cards in the plan sheet, „Übernehmen“ → a normal
  plan; switch off by default (`?debug` test setting as on the framework branch); (4) tests: the web engine's JVM test
  runs `TourSuggester` on a fixture; a planner smoke for the flow; (5) the owner tests in NRW / Fränkische Schweiz /
  Mittenwald, deploy on his word. Needs the content-2 suggest files in the test cells (laptop) – without them the
  planner says „Karte wird aktualisiert“.
- **Stage 3 built (IR 2026-11-18, branch `wip/ir-2026-11-18-planner-suggest`; behind the switch, off by default):**
  engine `web/engine/…/WebSuggest.kt` (`suggest(op, json)` in WebApi: `need` → the rd5 parts within reach + the cells
  whose `suggest.bin` the method reads, `run` → cards with loop line, km, h, ascent, title, text line, badge and the
  sketch-7 pills, `mapUpdating`); the worker fetches `suggest/settings.json` (copied from `tools/suggest-poc/settings.json`
  at build time), each cell's `cells/f2/10/x/y/<current version>/suggest.bin` and the parts, then runs it; UI
  `web/app/src/suggest.ts` / `suggest.css` (row „Rundtour von hier“ under „Hier starten“ in the first-tap menu and a
  place's menu, wish sheet 5 B, wait card, cards 4 A + 7, only the chosen loop on the map, „Übernehmen“ = TrackToPlan
  of the loop, closed as a round trip); switch: `?debug` → „Tourvorschläge (Test)“ (localStorage `onpath.suggestTest`).
  Tests: `WebSuggestTest` (need / wish / pills / a real Hilden run where the data is), `web/app/test/suggest-smoke.mjs`.
  **Measured in headless Chromium (donald, Wasm, local rd5 + content-2 suggest files):** hike 2 h Hilden 0.59 s
  (warm 0.35 s), 33 loops, 5 cards; bike 3 h Zons 6.7 s (warm 6.2 s: network 2.5, facts 1.8–2.2, place 0.6, loops 1.2,
  judging 0.1), 15 loops, 5 cards; the browser's resident memory grew by ~300 MB (hike, first run, routing data
  included) and ~610 MB (bike 3 h, first run; +300 MB on the second) – the rd5 parts are held twice in the worker
  (the engine's part bytes + the written rd5), as for routing.
- **Stage 4 app – the part without UI (IR 2026-11-18, branch `wip/ir-2026-11-18-app-suggest-core`; nothing calls it
  yet):** shared core `suggest/SuggestCards.kt` (in `app/web-shared-files.txt`): `SuggestAsk` (the wish as app and
  planner ask it: point, `Activity`, T level, hours from–to, ascent from–to, Ziel → `Wish`), `SuggestCards.of(result)` →
  light `SuggestCard`s (id, title, badge, km on the signed loop, km, h, ascent, text line, pills, the line as lon / lat
  pairs; `points()` → GeoPoints) holding nothing of the network or the place, `SuggestPills` (the sketch-7 pills, moved
  from `WebSuggest`; the planner's JSON is unchanged). `TourSuggester.suggest(…, check)`: a check point between the
  phases. App side `suggest/app/`: `SuggestService` (plain JVM: `need(ask)` → rd5 part keys + cells; `suggest(ask,
  check)` → routing parts missing → `routingToLoad`, no run; no way near the start / any failure → `noData` + the "none"
  note, reported; else the cards, `mapUpdating` and `cellsToFetch` = the cells in reach whose suggest file is missing
  or older than content 2; each cell's file read once per run and dropped with it), `SuggestRunner` (`suspend
  suggest(ask)` on `Dispatchers.Default`, one run at a time, a newer wish cancels the running one at its next phase),
  `SuggestFetch` (fetches only a cell's `suggest.bin` from the current manifest – size + sha256 – for cells installed
  without it; `NOT_ON_SERVER` when the server's cell is older than schema 9), `AppSuggest` (Android glue: one runner per
  process; DataFiles' segments, `RoutingDataLoader.missing`, the store's suggest files, `assets/suggest/settings.json`,
  its own copy of the shipped profiles in `filesDir/suggest/profiles`). `CellStore` now keeps a cell's `suggest.bin` as
  `<dataDir>/suggest/<cell>.bin` (installed after the generation, deleted with the cell, like the addresses); a cell
  installed before this (or by a released app) has none → `cellsToFetch`. `assets/suggest/settings.json` is a copy of
  `tools/suggest-poc/settings.json` (a test fails when they differ – copy it). Tests: `SuggestServiceTest` (real Hilden
  run → light cards; a cell without its file → `mapUpdating` + that cell; routing missing → `routingToLoad`; no data →
  no crash; the check stops a run; a newer wish cancels the running one, never two at once; the fetch takes only
  `suggest.bin`), `CellStoreTest.theSuggestFileIsKeptPerCellBesideTheStore`, the planner's `WebSuggestTest`.
- **Stage 4 UI – what is left** (starts when donald writes „app open for phone work“; the words are in
  `strings_suggest.xml` already, `suggest_ui_*`, the planner's `web/app/src/suggest.ts` is the model):
  1. *Switch:* a developer setting „Tourvorschläge (Test)“, off by default (`data/AppSettings` + Diagnostics →
     Developer tools), as the planner's `?debug` switch.
  2. *Entry „Rundtour von hier“* below „Hier starten“ wherever it appears: `ui/MapOverlays.kt` `FirstPointMenu` (the
     first tap into an empty plan, `startHere` row ~l. 437) and the place / POI menu (`PoiMenu` → `PinMenuItems.of` in
     `domain/PinMenuPlace.kt`, row `START_HERE`: add a row `ROUND_TRIP` right after it, only when the switch is on and the
     plan is empty). Tap → `AppViewModel.openSuggestWish(p)` (new).
  3. *Wish sheet, sketch 5 B:* a new `ui/SuggestSheet.kt` – activity labels without icons (the plan's activity), two
     range sliders (time, ascent), Ziel chips (Gipfel · Wasser · Aussicht · Einkehr · Kultur, several or none),
     Abbrechen · OK; OK → `SuggestAsk`.
  4. *Run:* in `AppViewModel` one `Job` per wish: `viewModelScope.launch { val need = AppSuggest.service(ctx).need(ask);
     mapData.routingData.ensure(need.routingParts)` (as `planFromShownNow` does) `; val r = AppSuggest.runner(ctx).suggest(ask) }`
     – a new wish just launches again (the runner cancels the old). `r.routingToLoad` → the wait card „Wegedaten werden
     geladen“ and retry when `AppRouting` says installed; `r.cellsToFetch` → `AppSuggest.fetch(ctx)?.fetch(cells)` on
     `Dispatchers.IO` (Wi-Fi rule: the setting's, the files are ~0.3 MB), then run again once; `NOT_ON_SERVER` stays
     „Die Karte wird hier gerade aktualisiert“ (the note is in `r.notes`).
  5. *Wait card, then the cards (sketches 4 A + 7)* in the plan sheet (`ui/PlanPanel.kt` area): title, badge
     „Ausgeschildert · <badge>“, km · h · ↑ m, „davon <loopKm> km auf dem Rundweg“, the text line (full on the chosen
     card), pills `SuggestPill` (green / yellow by `strength`, three intensities by `level`, at most 2 + 2 – already cut).
  6. *Map:* only the chosen card's loop (`card.points()`) as one source / layer in `map/MapController` (like the
     `gpx-track` line; one line style for all), the camera fits it; nothing faint for the others.
  7. *„Übernehmen“* → a normal plan: as `AppViewModel.planFromShownNow` with `card.points()` as the track
     (`TrackToPlan.fit` on `backgroundRouting`, round trip since first = last; `keepAsDraft`, `change { clear();
     addAll(points) }`), then the suggestion view closes.
  8. *Tests:* a VM test with a fake runner (wish → cards → take → plan points), screenshot tests of the sheet and a card
     (DE / EN / large), an E2E on the emulator with the Hilden test cell.
- **Stage 4 UI built (IR 2026-11-18, branch `wip/ir-2026-11-18-app-suggest-ui`; behind the switch, off by default):**
  slice `ui/suggest/SuggestSlice` (state: the wish, the phase WAIT / LOADING / CARDS / FAILED, light cards, the chosen
  card's loop; one launch per wish – the runner cancels the old one; `routingToLoad` → `RoutingDataLoader.ensure` and a
  wait of at most 3 min, then the run again; `cellsToFetch` → `SuggestFetch` on IO under the maps' Wi-Fi rule, then one
  run again) behind `SuggestHost` (`AppSuggestHost` = AppSuggest + the routing loader; the camera and „Übernehmen“ from
  the view model); `ui/suggest/SuggestSheet.kt` = the wish as a Material 3 dialog (sketch 5 B: activity as a plain label
  with ▾ + T level, two `RangeSlider`s ½–8 h / 0–1500 m, Ziel `FilterChip`s, Abbrechen · OK) and the panel in the plan
  sheet's place (MapScreen's mount, like the place card: head „3 Rundtouren“ · ✕, the wish line + „Wunsch ändern“, the
  notes, cards 4 A with the sketch-7 pills, „Übernehmen“ on the chosen card; at most 58 % of the screen; Back closes);
  map: one line `MapController.SRC_SUGGEST` (`suggest-loop`, #335A85, the planner's) for the chosen loop, the camera fits
  it; menus: `FirstPointMenu` and `PinMenuItems.Row.ROUND_TRIP` right after „Hier starten“; „Übernehmen“ =
  `AppViewModel.planFromLine` (the track sheet's TrackToPlan path, now shared with „Als Plan übernehmen“; routing data
  1 km around the loop), the wish's activity set first; switch: Settings → Diagnostics → Developer tools →
  „Tourvorschläge (Test)“ (`Prefs.suggestTest`). Tests: `SuggestSliceTest` (fake host), `SuggestScreenshotTest` (EN /
  DE / DE 1.3), `PinMenuPlaceTest.roundTripFollowsStartHere`, E2E `RoundTripSuggestE2ETest` (hold → row → sheet → OK →
  cards → second card's line drawn → Übernehmen; the run itself is a stand-in answer – the E2E map is Meran, which has
  no suggest file; with the workflow's E10_N45.rd5 Übernehmen must give a round-trip plan).
- **Owner 2026-11-18 late:** round trips are ON for everyone (the debug setting stays as an off-switch); the cards are a
  horizontal swipe row (sketch 8 https://claude.ai/artifact/DkavTPQGNXErH4ADZVdZS6); „Rundtour von hier“ appears only
  when setting the very first point (empty plan) – first-tap menu and a place / map-symbol menu – never once the plan has
  a point (narrows the 2026-11-15 rule „wherever Hier starten appears“).
- **The card's whole text (owner 2026-11-19 V1 „die Karte wächst“; 2026-11-28 15:36 variant A, F-PLAN-29):** a folded
  card shows title, numbers and text one line each; where any is cut, a blue „⌄ mehr“ ends the text line (the look of
  „⌃ weniger“, icon before the word). On the card in front the whole text block (title, numbers, text line, ≥ 48 dp)
  takes the tap and the card grows in place (title, numbers and text in full, scrolling inside under 60 % of the
  screen at a large font), „⌃ weniger“ under the text folds it; a swipe to another card folds it too. Nothing cut → no
  „mehr“, the text is part of the card; the peeking card is chosen by a tap anywhere; „Übernehmen“ only by its button
  (`SuggestSheet.kt` `CardTaps`, `OpenCard`; tests `CardTapsTest`, `SuggestScreenshotTest.cardClosed`). The planner
  (`web/app/src/suggest.ts`, `suggest.css`) has no open state yet – to mirror.
- **The real build:** this method written once in the shared Kotlin core and compiled for the browser like the router.
  Open: reading the network from BRouter's node cache in the browser, time and memory for bike wishes (200 000 nodes).
- **The result list (owner 2026-11-15, sketch 3 https://claude.ai/artifact/46KFniQG4WU4zQeGBW6tS9, variant A):** one
  list, official loops and ours mixed, sorted by "passt zum Wunsch"; a badge shows the origin ("Ausgeschildert · Traumpfad" /
  "Vorschlag"); the same five Werte on every card; on the map **one line style for all** (no dashed official loops – the card shows the origin) and the loops not chosen
  only as a breath, barely visible (owner 2026-11-15: "nur quasi als Hauch … die Karte muss ruhig sein"). Of an own loop and an official one that share more
  than 75 % only one is shown, the official one unless clearly worse (§3.7) – no "deckt sich mit unserem Vorschlag" note (owner: "was bringt das dem Benutzer?").
  The short text per card (built in the POC page 2026-11-15): one line, full on the chosen card; official loops get
  the same generated line as ours from what the loop passes (2026-11-16: no OSM descriptions any more – copyright). The card
  says how much of the ride is on the signed loop ("davon 24 km auf dem Rundweg"); the line on the map is the whole ride from
  the start (2026-11-17: no start distance, no parking line any more).
- Where the entry appears (owner 2026-11-15: "wenn ‚Von hier starten' kommt, dann kann auch ‚Rundtour von hier' kommen"):
  wherever a menu offers "Hier starten" – the first tap into an empty plan, the menu of a place / map symbol – it
  also offers "Rundtour von hier"; no extra rule of its own.

## 8. The POC

`tools/suggest-poc/README.md` says how to run, judge and change it. In short: `serve.py` → the page on
http://localhost:8790 (form with the two ranges, map, cards with the five axes, one comment field per card);
`run.py` → one wish in the terminal, with `--png` as pictures; `cases.py` → the owner's comments as checks;
`feedback.py` → his comments with their routes.

## 9. Weak spots and open questions

See the README's list (kept next to the code so it stays true).

## 10. Tried and dropped

- A fan of geometric shapes routed leg by leg through the router: random, out-and-backs, slow.
- Free naming of places by the AI, matched to the map by name: false matches; replaced by picking from our own list.
- Destinations from OpenStreetMap online (Overpass): slow, wrong names; replaced by our own map areas.
- A strict "no crossing unless nothing else exists" rule: hid clearly better loops; a meeting costs Form instead.
- Network search and green-patch chain as separate, selectable methods: one pool with one selection instead.
- Hundreds of absolute thresholds per activity (733 settings): replaced by the place's own yardstick.
- A fixed wished length with a tolerance band and an ascent cap by effort: replaced by the two ranges.
- A final check route through the router for every suggestion: the planner routes the adopted points itself.
