# Map style notes

## Why the map looks the way it does (contrast pass, Oct 2026)
Principles taken from common outdoor-map practice (OpenTopoMap, CyclOSM, swisstopo, Thunderforest, Mapy.cz,
Komoot, onX basemap redesign write-up). We took the principles, not code or palettes; all colours are our own.
1. **Hierarchy by purpose.** For a hiking/biking app the order is: my route/track > trails and paths > roads >
   settlements > land cover > relief. Trails get the darkest ink on the map; roads stay white with a casing.
2. **Quiet ground, strong figures.** Land, farmland and forest are light, low-chroma fills; lines carry the information.
   Forest is a little more saturated than before (#cde3b4 → #bfdca2) so it reads, but never dark.
3. **Settlements read at a glance.** Residential area is a step darker than open land; buildings are neutral grey
   (#ddd5cc → #cbc6c0) with a subtle outline from z15, so towns are grey, the countryside is warm.
4. **Thin ways need width *and* a halo.** Paths are near-black brown short dashes (#5b4636 → #33251a), at least
   ~1.35 px at z14 and 2.2 px at z16, with a thin white casing from z13 so they pop on forest and hillshade.
5. **Tracks (Feldweg, `highway=track`) look like Komoot's**: a narrow light road (cream fill) with brown edges –
   dashed when unpaved, solid when paved; at z12 only a thin dashed brown line. Paths stay dark single dashed lines.
6. **Route bands sit under trails** (pale opaque bands, drawn below roads/paths/trails) so they never wash lines out.
7. **Relief without mud.** Hillshade shadows are a cool blue-grey at moderate opacity instead of a warm purple-grey;
   brown-grey shadows on green forest turn olive/muddy.
8. **Colour-blind safety.** Trail difficulty is coded by colour *and* dash pattern (T1–2 dashes, T3 shorter, T4+ dots).
9. **Sunlight.** Labels are darker with a 1.5 px white halo. Since 0.7.0 there is one style for all light: black
   paths without white casings, lighter forest/meadow, a transparent dark-green route band chosen by a contrast
   analysis (`docs/design/map-colour-analysis.md`); the separate high-contrast theme was removed.

## How to change the look
All colours, widths / zoom stops, opacities, fonts and hillshade settings are design tokens in
`tools/themes/<theme>.json`. `tools/make_style.py` only holds the layer structure and order.

1. Edit tokens in `tools/themes/classic.json` (or a theme that `"extends": "classic"` and overrides a few tokens).
2. Regenerate: `python3 tools/make_style.py` (one style since 0.7.0; why each colour: `docs/design/map-colour-analysis.md`).
   `classic` → `app/src/main/assets/style/onpath.json`; any other theme `X` → `onpath-X.json`.
3. Check: render with the headless MapLibre GL JS harness / validate with the style-spec validator; then commit and push.

Token conventions:
- `<feature>.color`, `.fill`, `.casing`, `.outline` – colours (CSS strings MapLibre Native accepts, incl. `rgba()`).
- `<feature>.width.z<N>` / `.opacity.z<N>` / `.exaggeration.z<N>` – zoom stops, turned into an `interpolate`
  expression; optional `<feature>.width.base` (default 1.2, `1` = linear). A plain `<feature>.width` scalar also works.
- `<feature>.dash` – line dash arrays; `font.regular|medium|italic|minor-label` – font stacks (must exist in `assets/fonts`).
- Main groups: `land/farmland/grass/wood/wetland/rock/ice/sand/residential/industrial/leisure`, `hillshade.main.*`,
  `hillshade.fill.*` (direction, exaggeration, shadow, highlight, accent), `contour.*`, `water/waterline/stream/river`,
  `building.*`, `road.*` (+ `road.<class>.width`, `major.*`, `motorway.*`), `tunnel/bridge/rail/aerialway`,
  `route.<hike|bike|mtb>.color`, `route.width`, `track.*`, `cycleway.*`, `path.*`, `steps.*`,
  `trail.<easy|mountain|alpine>.color|dash`, `trail.width`, `trail.casing.*`, `boundary.*`, `label.*`, `poi.label.*`.
- Lines starting with `"//"` are comments.

Other style modules (e.g. `tools/style_trail_names.py`) read tokens with
`import style_theme; style_theme.token("label.halo.color")` or `style_theme.zoom("path.width")`
(`make_style.token` is the same function). The active theme is set by `make_style.build()` before layers are built.


## Paths and trails (owner, 2026-10-18 – Komoot-like, all black)
| Way | Look |
| --- | --- |
| footpath / path, no grade or T1 (any surface) | solid black |
| T2–T3 mountain hiking path | black round dots on a light underlay |
| T4–T6 alpine path | black dot-dash |
| track (Feldweg) | light lane; solid edges paved/compact, fine dotted edges loose |
Difficulty is shown by rhythm and the T-labels, never by colour.

## Way quality (owner, 2026-10-18)
Data: `trails.geojson` (`vis`), optional `ways.geojson` (`tt`, `surf`, `smooth`) – fields in `docs/map-pipeline.md`.
| Way | Look | Shown |
| --- | --- | --- |
| track grade1–2 (compact) | solid brown edges | always |
| track grade3 | fine dotted edges (`track.dash.loose`) | always |
| track grade4–5 (soft / grass) | sparse dotted edges (`track.dash.soft`) | always |
| track without tracktype | as before: solid if OpenMapTiles says paved, else fine dots | always |
| trail with `trail_visibility` bad / horrible / no | same rhythm, sparser and lighter dots (`trail.faint.*`); T1/untagged faint paths get the T2 dot rhythm | always |
| SAC label "T1"…"T6" | small black text along the way (`trails-sac-label`) | every activity but MTB (bikes since 2026-10-21) |
| MTB label "S0"…"S5", with the T-grade "T3 S2" | same look (`trails-mtb-label`) | **MTB / E-MTB only** |
| loose surface (gravel, stony, dirt, ground, grass, sand, …) or smoothness bad or worse | thin warm-brown edge on both sides (`surface.loose.*`, `line-gap-width`) | **bike activities only** |
Compacted and fine gravel do not count as loose (fine on any bike). The app switches the activity-dependent layers
in `MapController.setOverlay`. Tracktype edges come from `ways.geojson`, drawn over the base map's track with a
light cover that hides the base map's own edges (format 1; format 2 draws tracks from the overlay `paths` with `tt` →
`track.dash.compact / medium / loose / soft`, `tools/make_style.py track_edges`; the old `track.edge` / `track.cover` /
`track.fill` theme tokens were removed 2026-10-26).

## Checking the style without a phone (headless render)

Used for every map decision on 2026-10-19 (renders in `docs/design/`):
1. In a scratch folder: `npm install maplibre-gl@4.7.1 pmtiles@3.2.1 playwright` (Chromium is preinstalled in the
   cloud container: `/opt/pw-browsers/...`; launch with `--use-gl=swiftshader --enable-unsafe-swiftshader`).
2. A small Node HTTP server **with Range support** (PMTiles needs it) serving `/data/` = the map package folder,
   `/assets/` = `app/src/main/assets/`, `/nm/` = node_modules.
3. Page: fetch `assets/style/onpath.json`, replace `pmtiles://file://{DATA_DIR}/` → `pmtiles://<origin>/data/`,
   `file://{DATA_DIR}/` → `<origin>/data/`, `asset://` → `<origin>/assets/` (fonts + sprite incl. ground patterns),
   then **drop layers whose metadata has `onpath:fallback-for` when that source's file exists** – the app's
   `OptionalSources` does this; without it the base-map paths are drawn under the own ones.
4. `new maplibregl.Map({..., canvasContextAttributes:{preserveDrawingBuffer:true}})`, wait for `idle`, screenshot
   with Playwright (viewport 400×400, deviceScaleFactor 2). Colour maths: `tools/style_analysis/route_green.py`.

## Track junctions and ground symbols (2026-10-19, owner field report)
- **Track crossings.** Two-edge tracks (`line-gap-width` dotted edges, round caps) had no fill, so at crossings and
  T-junctions the edges of one way ran across the other way's lane, and every way piece closed with its own ring /
  "c"-hook at each junction. Now `track-own-lane` / `track-own-paved-lane` draw a lane (gap width, round caps,
  `track.lane.color` light earth tone at `track.lane.opacity` 0.9 – not white, the route band still tints it) **after
  all track edge layers** – like road casings below road fills – so junctions merge into one road and end caps
  inside a junction disappear under the neighbour's lane. Below z14.5 (single dashed line) nothing changed.
- **Street junctions (same day).** Small streets (`service`, `minor` = residential / unclassified / living street …,
  and paved tracks) were two grey edges with an open middle, so a side street's edges ran into / across the street it
  joins and every way piece ended in a small hook. Now every way class follows one casing-then-fill order **across all
  classes**: `roads()` (all casings / street edges) → `track_edges()` (track edges, single dashed track) → `lanes()`
  (`track-own-lane` < `track-own-paved-lane` < `road-service-lane` < `road-minor-lane` < `road-tertiary` … `road-motorway`)
  → `railways()` → `paths()` (paths, cycleways, steps stay on top). The street lane is exactly the gap width
  (`street.lane.color` #f9f7f1, opacity 0.92 – warm, lighter than the land, not white), so the 0.7.0 edges keep their
  colour and width. Layer order decides who wins at a junction: the bigger way's lane is drawn later, so a side street
  / track stops at the bigger road's edge. Casings and fills/lanes sort bridges on top (`line-sort-key`, `ON_BRIDGE`);
  `bridge-casing` still redraws bridge outlines above everything. Track edges and the single dashed track now sit
  below road fills (before: on top of yellow roads).
- **Ground symbols** (OpenMapTiles landcover class / subclass): `grass` (meadow, grassland, grass, scrub, heath, park …)
  and `wood` = plain colour, no symbol. `wetland` (natural=wetland incl. marsh, wet_meadow, swamp, bog, reedbed) = marsh
  tufts. `farmland`: only `orchard` (small rings = trees) and `vineyard` (short upright stakes); plain farmland / farm no
  symbol. The former furrow dashes on all farmland looked like the German wet-meadow symbol and covered every apple
  orchard in the valley – that was the "symbols on many meadows" the owner saw. `rock`, `sand` unchanged.
- **Ground symbols only close in (2026-10-20).** The `landcover-*-pattern` layers start at `GROUND_SYMBOLS_ZOOM` = 16
  (was 14). The app's max zoom is 18 (`MapHost` → `setMaxZoomPreference(18.0)`), so the symbols show on the last two
  zoom steps only; below z16 orchard, vineyard, wetland, rock and sand are plain landcover colour.

## Solid track edges at way ends and in bends (2026-10-20, owner field report)
- **Cause.** The dotted track edges (`track-own-compact/medium/loose/soft`, from z14.5) used `line-cap: round` and
  `line-join: round` with dots made of 0.1-long dashes. MapLibre gives every vertex of a round join fan and of a round
  cap the *same* distance along the line, so the dash pattern does not advance over them: the whole arc on the outer
  side of each bend and the half ring at each way end took one dash value. With dots that close together that value is
  nearly always "on" → solid brown arcs in bends (dense OSM vertices = a fan at every vertex) and a solid ring / "c"-hook
  at every way end and every way split (also visible at junctions).
- **Fix.** Edges now use `line-cap: butt` and `line-join: miter`: no cap ring (the edges end flat, the track lane's
  round cap closes the end in light earth colour), no join fan (sharp hairpins fall back to a small bevel). Because the
  dash shape follows `line-cap`, the dots are now short square dashes (theme `track.dash.*`: compact 0.6/0.5, medium
  0.8/1.0, loose 0.8/1.9, soft 0.8/3.3 – same period as the old round dots, so surface spacing reads the same). On the
  inner side of a tight hairpin the dots still crowd (the pattern is measured along the centre line); that is MapLibre's
  dash model and only a few pixels long.

## Green ways (2026-10-20, owner: Komoot-like "good for walking / riding")
`green_ways()` in `tools/make_style.py`: thin green line (`greenway.color` #2e9e3a, `greenway.width.*`) from the base
map's `transportation` layer (class `path` + `subclass` + `foot` / `bicycle`), so it works with existing packages.
`greenway-foot` (hiking): footway / pedestrian unless foot=no, cycleway / path with foot=designated. `greenway-bike`
(bike profiles): cycleway unless bicycle=no, footway / pedestrian / path with bicycle=yes|designated. Switched by
`MapController.setOverlay`. `pedestrian-line` draws pedestrian zones as a plain path for every activity (they were
lost in 0.7.0: `paths.geojson` has no highway=pedestrian, the base path layer is fallback-only). Render:
`docs/design/img/greenways-meran-z15.png` (before · hiking · bike). Not yet: cycle lanes on roads (`cycleway:*`,
not in the base map – pipeline), oneway arrows on the map.

## Map package format 2 (2026-10-20, map rework steps 3 + 4)
`make_style.py` writes two styles per theme: `style/onpath.json` (format 1, GeoJSON overlays – unchanged) and
`style/f2/onpath.json` (format 2, metadata `onpath:format` 2). The app takes format 2 when the data folder has
`overlays.pmtiles` (`StyleLoader`, `DataFiles.formatOf`); an override in `styles/` written for the other format is
skipped. Pipeline and schema: `docs/map-pipeline.md` section 5b.
- **Same layer ids.** Every GeoJSON source (`routes`, `trails`, `ways`, `paths`, `lowways`, `trailnames`) becomes a
  `source-layer` of the one vector source `overlays` (`lowways` → `paths`, which holds the coarse copy at z11–12).
  The only layers missing in format 2 are the base-map fallbacks `track-base` / `path-base` (format 2 always has the
  overlay).
- **No base-map path lines at z13+.** The format-2 base map has no path / track lines at z13–14, so `cycleway`,
  `cycleway-unpaved`, `path-steps`, `pedestrian-line`, `greenway-foot` / `greenway-bike` and the path / track part of
  `access-no-*` read overlay `paths` (`sub` = OSM highway, `tn`, `ac`, `foot`, `bicycle`, `uv`). The service-road part
  of the access marks stays on the base map as `access-no-{tint,sign}-{foot,bike}-service`. `track-low` / `path-low`
  (z12) still read the base map. Side effect: green ways now lie exactly on the black path line (format 1 showed the
  base map's line a little beside the own path at z17) – `docs/design/img/format2-vs-1-meran-z16.jpg`.
- **Smoothed paths** (pipeline `--smooth`): bends rounded within 1.5 m; route bands on the same line –
  `docs/design/img/format2-smoothing-*.jpg` (format 1 · format 2 unsmoothed · format 2 smoothed).
  Since 2026-10-21 (owner, phone at z18 near Plars: streets had sharp corners, paths only "a bit smoother"): bends from
  15° within 3 m, and small streets are smoothed too – see "Rounder ways" below.
- **Via ferrata** (`via-ferrata`, `via-ferrata-ticks`, all activities): dark red (`viaferrata.color` #9e1b1b) dashed
  line with short cross ticks (a wide line with a very short dash = ticks across the way), like the "Klettersteig"
  signature of Alpine maps; red so it never reads as an ordinary trail. Drawn above the trail dots.
  `docs/design/img/format2-via-ferrata-z17.jpg`.
- **Bike map (first draft, bike activities only, switched by `MapController.setOverlay`):** `bike-lane-left/right`
  (cycle lane / track beside the street, solid; `-shared` dashed for shared / bus lanes; `line-offset` by road class),
  `bike-oneway` (grey arrows on one-way streets from the base map's `oneway`, z15+), `bike-both-ways` (green double
  arrow where `oneway:bicycle=no`). Sprite images `oneway-arrow`, `bike-both-ways`. Needs an owner look on the phone.

## Rounder ways (2026-10-21, owner after a look at z18 near Plars / Algund)
- **Round joins** on every street, path, track and route-band line (casings, lanes, fills, cycleways, steps, bridge
  casings). Exception kept on purpose: the dotted track edges `track-own-compact/medium/loose/soft` stay `line-cap:
  butt` + `line-join: miter` ("Solid track edges at way ends and in bends" above) – with smoothed geometry a track
  turns by at most ~15° per vertex, where a miter looks round, and a round join would bring back the solid arcs at
  the corners that stay sharp (junction nodes).
- **Stronger smoothing** (pipeline, format 2): bends ≥ 15° within 3 m (`extract_overlays.py --smooth-angle 15
  --smooth-dev 3`); junction nodes and ends exact; route bands on the same line.
- **Small streets from the overlay** (format 2): residential / living street / unclassified / road / service ways are
  in `overlays.pmtiles` layer `streets` (smoothed, OpenMapTiles attribute names) and the base map has no minor /
  service lines at z13–14 (`trim_base.sh`). `street_layer()` in `make_style.py` draws `road-{minor,service}-{casing,
  lane,tunnel}` from the base map below z13 (ids `…-low`) and from `streets` above – same ids, filters, widths and the
  same casing-then-fill order, so bigger roads' fills still win at junctions and tracks stay under yellow roads.
  `access-no-*-service` read `streets`; new `bridge-casing-streets` and `bike-oneway-streets` (switched with
  `bike-oneway` by `MapController.setOverlay`). Tertiary and bigger roads stay in the base map, unsmoothed; a small
  street's shared node with them is exact, so they meet without gaps. Street names still come from the base map's
  `transportation_name` (unsmoothed, ≤ 3 m off the drawn line – reads fine). Format 1 unchanged (streets from base).
  Renders: before / after at Plars, Meran and St.-Peter-Straße (Algund) at z16–18.

## No blue ways (2026-10-21, owner)
Rule: **green = good for the current activity, everything else the normal black line.** Water stays blue.
- `cycleway` / `cycleway-unpaved`: black path line (`path.color` / `path.width`, unpaved dashed) instead of blue.
- `greenway-foot` (hiking): footways, pedestrian zones and cycleways – each unless `foot=no` (shared foot + cycle use is
  common in Italy / Austria, so a missing foot tag counts as allowed) – plus paths with `foot=designated`.
- `greenway-bike` (bike activities): every cycleway, plus footways / pedestrian zones / paths with
  `bicycle=yes|designated`. Footways without bike access stay black.
- Cycle lanes beside streets (`bike-lane-*`) are thin green offset lines; the both-ways arrow sprite is green.
- Theme tokens `cycleway.color`, `cycleway.width.*` and `bike.lane.color` are gone (`cycleway.dash` stays).

## Map vs router quick fixes (2026-10-21, owner: "start the quick fixes")
From the map-vs-BRouter audit (each activity's BRouter profile evaluated per tag set). Style / app only – no new map
package needed, except the `mtb` attribute below. Activity → profile: Hike = hiking-mountain, Bike touring =
fastbike-verylowtraffic, Bike trekking + E-bike = trekking, MTB + MTB e-bike = mtb. **Since 2026-10-25:** Bike touring
+ E-bike = touring.brf (OnPath's trekking copy for a normal bike), Bike trekking = trekking.
- **Green respects access** (was already on main): `greenway-*` skip foot / bicycle = no|private and `ac` ways
  without foot / bicycle = yes|designated|permissive. Bikes now also skip bicycle = dismount|use_sidepath.
- **Green per profile family** – three bike layers, switched by `MapController.setOverlay`:
  `greenway-bike` (trekking): cycleways + footways / pedestrian / paths with bicycle=yes|designated;
  `greenway-touring` (touring, E-bike): since 2026-10-25 like trekking (touring.brf; before: only bicycle=designated,
  fastbike costs a bicycle=yes path 100); `greenway-mtb` (MTB, E-MTB): like trekking.
- **No green on hard trails** (format 2): trekking / touring / E-bike only up to T1 and mtb:scale 1 (the router
  forbids T3+ and adds +30 on T2 / mtb:scale 2–3); MTB up to T2 and mtb:scale 3 (T3 +15, T4+ forbidden, mtb:scale 4+
  ≈ 9000). `sac` is in every package; **`mtb` on overlay `paths` is new** (`extract_overlays.py tile_attrs`) – older
  packages show no mtb:scale rule until rebuilt.
- **Private minor streets**: residential / unclassified / living street / road with access=no|private get the blocked
  tint + sign like service roads (same layers `access-no-*-service`, filter widened; format 1 via the base map).
- **T-labels in bike modes**: `trails-sac-label` for every activity but MTB; MTB gets one label "T3 S2"
  (`trails-mtb-label`) – two labels on the same anchors would hide each other.
- **One-way paths** (`bike-oneway-paths`, bike modes, z15+): the street arrow sprite (a bit bigger) on paths,
  footways, cycleways, tracks with `ow`; `owb` (oneway:bicycle) wins, `owb=0` keeps the both-ways symbol
  (`bike-both-ways`), `owb` ±1 on a two-way path draws the arrow.
- **No MTB route band**: mtb.brf ignores route=mtb and rewards cycle routes, so MTB / MTB e-bike now show the cycle
  band (`routes-bike`); the `routes-mtb*` layers are gone from the style (route data kind "mtb" stays unused).
- **Steps in bike modes**: blocked tint + no-entry sign (`access-no-*-bike`), unless bicycle=yes|designated (ramp).
  Router: MTB forbidden, touring 1000 (fastbike; touring.brf since 2026-10-26: 40 like trekking), trekking 40 (carry the bike). Hiking unchanged.
Renders (before / after per activity): Pfelders bicycle=yes path, Töll T2 path, St.-Georgen-Straße, Laubengasse,
Stuanbruch-Trail, steps at the Etsch / Meran, Weißlahn, Kassianspitz.
Still open (audit): #4 trails over the user's SAC limit, #5 trail_visibility=no, #6 paved cycleways for MTB (still
green), #11 / #16 bicycle=no / vehicle=no without `ac` (no tint), #12 foot=no (no tint for hiking), #17 barrier nodes;
the full fix is the per-profile class from BRouter's own expression engine (audit "Router decides, map shows it").

## Router classes – "the router decides, the map shows it" (2026-10-22, owner decisions (3) + (4), branch, renders first)
Approach 1 of `docs/design/map-vs-router-audit.md`. The map's green / black / forbidden comes from OnPath's own BRouter
profiles, evaluated per way with BRouter's expression engine – no tag rules of our own where the data has classes.
- **Classifier** `tools/routing/wayclass/WayClass.java` (built by `build.sh` into one jar with the BRouter jars of
  `app/libs`; `--selftest` checks known tag sets). Profile groups = `BRouterEngine.profileFile`: `ch` Hike
  (hiking-mountain), `ct` Bike trekking (trekking), `cf` Bike touring + E-bike (touring; until 2026-10-25
  fastbike-verylowtraffic, and E-bike was in `ct`), `cm` MTB + MTB e-bike (mtb). Each way: costfactor of the better direction → **0 good** (≤ threshold), **1 possible**, **2
  forbidden** (> 9998, BRouter's own limit, `OsmPath`). Thresholds (length-weighted costs of the test region; green is
  drawn only on path-like ways, so a threshold only separates those): hike **1.40** (every path / footway / cycleway the
  profile does not penalise; foot=no +4, bad visibility, informal, fords, steps 3.6 = possible), trekking **1.20**
  (cycleways, bicycle=yes|designated paths 1.15 off a cycle route; residential 1.25; untagged paths 5.15 = pushing;
  1.10 / 1.05 / 5.05 until the off-route add went 0.05 → 0.15 on 2026-10-25), touring **1.20** like trekking
  (wayclass-5, 2026-10-25; fastbike had 2.0), MTB **7.0** (unpaved cycleways, bicycle=yes|designated paths
  6.67, easy trails; plain paths 10, paved cycleways 9.6). Hike is evaluated **without sac_scale / mtb:scale** – the
  user's T level is runtime, the app applies it. `cr` = bikes' one-way (1 along / −1 against the line) where one
  direction is forbidden or costs ≥ 50 more (the profiles add +100; same in all bike profiles, selftest).
  Barrier nodes: node `initialcost` ≥ 1e6 = blocked, unless every way through the node grants access
  (`nodeaccessgranted`, hiking / cycle routes) → overlay layer `barriers`, `b` = mask 1 hike, 2 trekking, 4 touring,
  8 MTB.
- **Pipeline** `extract_overlays.py --wayclass JAR --profiles DIR` (build_region.sh format 2 and build_cell.sh pass
  it): way tags limited to lookups.dat keys + BRouter's route pseudo-tags (`route_hiking_lwn=yes` …, like its map
  creator) → one request per distinct tag set (53 000 in the test region's extract, < 10 s of Java). Classes go on
  `paths` (z13–14) and `streets`; hiking route pieces get `sac` and `hx` 1 (hike forbids the way at any level: via
  ferrata, trail_visibility=no, private). Profile hash (`wayclass.json`, overlay description
  `onpath-wayclass:<hash>`, package.json / cell manifest `wayclass`). Cell content schema **3**.
- **Style** (format 2): `greenway-<mode>` = class 0 on path-like ways (hiking: only ways built for walking – footway,
  pedestrian, cycleway, foot=designated path; the profile costs a plain path like a footway and plain paths keep their
  black / dotted difficulty look). Forbidden = class 2: the blocked tint (z14), **red dashes** (`access-no-dash-*`,
  `forbidden.*` tokens, z13, owner (4)) and the sign (z15), on paths / tracks / via ferratas and minor / service streets
  (not driveways), one layer set per profile group (`foot`, `bike`, `touring`, `mtb`). Everything else black ("avoid"
  looks like possible). One-way arrows from `cr`. `barrier-blocked-<mode>`: the sign on blocking nodes (z15). Each
  filter is `["case", ["has", key], <class rule>, <tag rule>]` – cells without classes keep the tag rules.
- **Hiking band above the T level** (owner (3)): `routes-hike` only up to the level, `routes-hike-over` (grey dashes,
  `route.over.*`) above it or where `hx`; `MapController.setSacLimit` sets both filters from Settings (style ships T3).
- **Consistency** `tools/routing/wayclass/consistency.py PACKAGE SEGMENTS` (JVM routes through `BRouterEngine`):
  random routes per group never run over a way the map shows as forbidden, and routes between points on forbidden
  ways never run along them.
- **Results (test region, OSM 17 Oct, rd5 17 Oct):** 53 352 tag sets, 4 552 barrier nodes of which 372 block an
  activity. `overlays.pmtiles` 16.63 → 16.95 MB with classes (+0.32 MB, +1.9 %); extract ~2 min (was ~1¾). Consistency
  (seed 1: 30 routes + 30 forbidden ways per group; seed 2: 25 + 25): 228 routes, 2 354 km, ~234 000 samples every
  10 m – **0** on a forbidden way, 0 hiking samples above T3; 220 forbidden ways – **0** routed along (14 "no route").
  Samples on base-map roads (tertiary+, no class) are counted apart (touring ~47 %). Renders (before / after per
  activity at the audit's spots): `onpath-build/out-f2d/renders/`.
- **Open:** the app does not yet compare the cells' `wayclass` hash with its own profiles (a profile change needs a
  rebuild of all cells); tertiary and bigger roads have no class (base map); the cloud job image needs BuildKit
  (`--build-context`, the GitHub runner has it).
- **Owner after the renders (2026-10-22): difficulty is not "forbidden".** New class **3 = too hard**: forbidden only
  because of `sac_scale` / `mtb:scale` / `trail_visibility`, or as a via ferrata (WayClass re-evaluates without them,
  via ferrata as a path). Drawn as a plain grey line (`too-hard-<mode>`, `toohard.*` tokens) – no red, no sign; routing
  unchanged. Red dashes + sign stay for real bans (access / foot / bicycle = no, private, locked gates, one-way). Owner later: steps for MTB are "too hard" too (grey), the via
  ferrata keeps its dark-red tick symbol under the grey line. The hiking band above the T level (and over class 2 / 3 for hiking, `hx`) is a plain grey band, no dashes.
  Consistency re-run with classes 2 + 3 as "not routable": still 0 / 0 (both seeds).

## Hill shading "balanced (c)" + terrain z13 (2026-10-22, owner; ships with the s3 cell rebuild)
Values from the hill-shading proposal, variant c: `hillshade-nw` exaggeration 0.8 / 0.8 / 0.75 / 0.75 / 0.8 (z5 / 9 / 11 /
13 / 15), shadow rgba(38,56,42) 0.45 → 0.33 → 0.23 (z11 / 13 / 15), highlight rgba(255,255,245) 0.80 → 0.90 (z11 / 15),
accent 0.06; `hillshade-w` exaggeration 0.4 / 0.4 / 0.35 / 0.35 (z8 / 10 / 13 / 15), shadow 0.12; lighter wood
(#cde3b8 → #d8eac6, z12 / 15) and grass (#e3edd2 → #e9f1dc). Colour tokens may now be zoom stops (`Z()`,
`<token>.z<N>` + `.base 1`) – **except hillshade shadow / highlight** (fix after the 0.10.0 phone test: MapLibre Native 13.x types them array<color> and cannot interpolate that, so 0.10.0 dropped both on `hillshade-nw` with "Expected array<color> but found string" and drew opaque black shadows / white highlights – much harsher than the maplibre-gl JS renders; now one `hillshade-nw-z<N>` layer per whole zoom z11–15 with the colour at the band's middle, guard `StyleFormatTest`). Overview raster (`Overview.kt`): raster-contrast 0.25, raster-brightness-max 0.9.
Format-2 terrain goes to **z13** (was z12; `build_terrain.py --format 2`, cells z10–13, regions z5–13): per cell
terrain 1.0 → 3.1 MB (Meran 10/543/361: 1.02 → 3.13 MB, 542/361 1.01 → 3.10, 544/362 0.95 → 2.93), i.e. a cell
~9.3 → ~11.4 MB (+23 %); test region terrain 8.7 → 21.9 MB. Mean brightness of the map at Partschins (z11 / 13 / 15):
−0.2 / +2.2 / +1.3 % against main. Cells of schema 2 (terrain z12) mixed with new ones show no relief at z13 tiles of
the old cells until they are rebuilt (the s3 bump asks for all of them).

## Quiet towns, cycle-route lines, forbidden ways (2026-10-26, owner field report; schema 6 cells)
Why each colour: `docs/design/map-colour-analysis.md` "Quiet towns". Data: overlay `u` (1 built-up, 2 town green),
`k` = "l" link pieces, `ar` squares (`docs/map-pipeline.md` 5b). Cells without them look as before.
- **Paths** (`path_paint()` in `make_style.py`, used by `paths-own`, `paths-link`, `cycleway`, `cycleway-unpaved`,
  `path-steps`):
  - Colour: `case u = 1 → path.town.color`, else the path ink.
  - Width: `town_or()`, a case inside every zoom stop – MapLibre allows `["zoom"]` only as the input of the top-level
    curve.
  - Opacity: `town_fade(14)`, so town paths fade in from z13 to z14.
  - The same layer ids as before. The app switches no new layers for towns; zoom inside a filter is avoided on purpose,
    because overzoomed tiles evaluate it at the tile zoom.
- **Zoomed out:** `trails-low` leaves out paths with any `u`. Format 2 has no `path-low` / `track-low` any more (the base
  map's copy drew town footways twice).
- **Streets:**
  - Overlay `streets` layers (z13+) read `u`: white lane, light edges, a little wider (`town_street_paint()`).
  - The base-map `-low` layers (z11–12) get their own `street.low.*` paint.
  - Private driveways / parking aisles: opacity 0 below z17 (`street_opacity()`).
- **Pedestrian:** `pedestrian-town` is a lane in `lanes()`, before the street lanes. `pedestrian-line` and the green ways
  skip squares (`ar`).
- **Green ways:**
  - `greenway-foot` skips `u = 1`.
  - The bike green ways fade in at z14 in towns.
  - New for bike activities, hidden by default and switched by `MapController.setOverlay`: `greenway-bike-glow`,
    `greenway-touring-glow`, `greenway-mtb-glow`, `bike-lane-glow`. `bike_glow()` builds them after `green_ways()`
    (it needs the same filters) and moves them below the first road casing.
- **Cycle routes:** `bike_route_lines()` – `routes-bike` thin teal, `routes-bike-major-low` violet for net ≥ 3 (no
  maxzoom), `routes-bike-major-label`. `routes-bike-major` (pink dashes) is gone; `setOverlay` skips a missing layer.
  Hiking keeps its band.
- **Forbidden:**
  - `access-no-tint-*` removed. `setOverlay` detects the per-profile style by `access-no-dash-touring`.
  - Path / track dash from z13, town paths from z15.
  - Service dash: thin, muted, from z14.
  - Signs: from z15 (paths, spacing 250–400) / z17 (service, 600–800).
  - `StyleFormatTest.forbiddenNeverLouderThanAStreet` checks the widths at z14–18.
