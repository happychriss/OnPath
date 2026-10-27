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

## Test style on the phone (2026-10-26, owner: "the test cycle is too slow")
A style tweak used to need a release and an install. Now it takes about a minute:
- `tools/dev/push_style.sh` builds the classic theme of the checkout (`make_style.py --format 2 --out`) and pushes it
  with adb to `<data dir>/dev/style.json`. Then it force-stops and starts OnPath.
- `tools/dev/push_style.sh variant.json` takes a theme file outside `tools/themes`, for example
  `{"extends": "classic", "street.town.edge.color.z17": "#7d766d"}`. A ready style (a JSON with `layers`, e.g. from a
  render test) is pushed as it is.
- `tools/dev/push_style.sh --remove` deletes the test style.
- The restart would stop a running tour: the script refuses (exit 3) while OnPath's `RecordingService` runs, and also
  when adb gives no answer (unknown = refuse). `--check` only asks; `--force` (first argument) skips the check – only
  when you know nobody records (2026-10-26: a push interrupted a tour on the owner's phone).
- The app reads `dev/style.json` only while the developer tools are shown: debug builds, or after 7 taps on the version
  (`StyleLoader.devStyleFile`). Normal users never get it.
- Settings → Diagnostics → Developer tools → "Map style" shows "Test style active (dev/style.json, time)" with
  **Remove**, and a **Reload map style** button.
- A file that is not JSON or is written for format 1 is skipped with a warning in the problem list. A style that
  MapLibre rejects is a warning too: the normal style loads, and the file stays skipped until a new one is pushed.
- Map cells are not part of this. Another session owns the speed-up for cells (building one cell locally and pushing it).

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
  - Overlay `streets` layers (z13+) read `u`: white lane, a little wider, edges light at z13 and mid-grey close in
    (`street.town.edge.color` / `.width`, `town_street_paint()`). Built-up land gets lighter from z14 to z16
    (`residential.color.z14/z16`).
  - The base-map `-low` layers (z11–12) get their own `street.low.*` paint.
  - Private driveways / parking aisles: opacity 0 below z17 (`street_opacity()`).
- **Pedestrian:** `pedestrian-town` is a lane in `lanes()`, before the street lanes; `pedestrian-town-casing` (same
  filter `PEDESTRIAN_TOWN`) is its edge, after the road casings. `pedestrian-line` and the green ways skip squares (`ar`).
- **Street endings** (2026-10-26, owner on 0.11.2): `town_below_streets()` runs last in `build()`. Each layer in
  `TOWN_BELOW_STREETS` (`paths-own`, `paths-link`, `cycleway`, `cycleway-unpaved`, `path-steps`, `greenway-bike`,
  `greenway-touring`, `greenway-mtb`) gets a copy `<id>-town` (filter + `u = 1` and no `br`), moved just below the
  first road casing (above the bike route band). The original keeps the rest. `MapController.setOverlay` switches
  `<id>-town` with `<id>`. Town passages (`tn`) are left out of `paths-own` and drawn by `paths-tunnel-town`, dashed
  from z16. `br` (path bridge) is not in the cells yet – until then a footbridge in town is drawn below the street it
  crosses. Guard: `StyleFormatTest.format2TownWaysEndAtTheStreet`.
- **Green ways:**
  - `greenway-foot` skips `u = 1`.
  - The bike green ways fade in at z14 in towns and use `greenway.bike.width`, a little wider than the hiking line.
  - The soft bike glow of 0.11.2 (`*-glow` layers) is gone (owner, later the same day): for bikes, as for hiking, the
    green line marks a good way and the band marks a signed route.
- **Cycle routes:** `bike_route_lines()`.
  - `routes-bike` is a mint band (`route.bike.*`) moved just below `FIRST_LANE`, so it sits above every casing / track
    edge and below every lane. A street on a route gets a mint border, also a wide main road (below the casing it hardly
    showed there). On a cycleway the path line runs inside it. Light zoomed out. The town path copies sit above it.
  - Long distance (net ≥ 3, only on the `majoronly` pieces) looks exactly like a hiking major route: `routes-bike-over`
    is the green band (bikes have no T-level pieces, so the `-over` slot is free), then `routes-bike-major-low` (thin
    pink line below z13.5) and `routes-bike-major` (pink dashes).
  - `routes-bike-major-label`: every named piece. Schema 7 cells add label lines (`lb`) for named local routes; the
    pipeline leaves out names a major piece already carries. It is the last layer of the style, so it is placed first:
    lower down, the street and POI labels took every place along a street.
  - Bicycle streets (schema 7 streets `bs`): `bike-street-bikeview`, the green bike line along the street.
  - Footbridges (schema 7 paths `br`): `town_below_streets()` leaves them in the upper layer, above the streets.
  - Guard: `StyleFormatTest.bikeRoutesAsBands`.
- **Bike view / hike view** (owner 2026-10-26, "the brown ways look very dominant"):
  - Layer ids ending in `-bikeview` show only for bike activities, ids ending in `-hikeview` only for hiking.
    `MapController.setOverlay` switches them by suffix. The style ships the hiking look, so an older app shows that.
  - Tracks for bikes: `track-bike-edges-bikeview` (thin `track.bike.edge.*`) and `track-bike-lane-bikeview` (lane colour
    by tracktype, `track.bike.lane.*`), from z13.
  - Hiking keeps `track-own-single/compact/medium/loose/soft/lane-hikeview`.
  - Loose surface (`surface-loose-*`, bike only): `surface.loose.mode` `edge` is a thin dotted edge from
    `surface.loose.minzoom` 15; `line` draws the path brown and the bike track's edges brown.
  - Closed paths / tracks for bikes: `forbidden.bike.*`, quieter than the hiking dash.
  - Guard: `StyleFormatTest.bikeAndHikeViews`.
- **Forbidden:**
  - `access-no-tint-*` removed. `setOverlay` detects the per-profile style by `access-no-dash-touring`.
  - Path / track dash from z13, town paths from z15.
  - Service dash: thin, muted, from z14.
  - Signs: from z15 (paths, spacing 250–400) / z17 (service, 600–800).
  - `StyleFormatTest.forbiddenNeverLouderThanAStreet` checks the widths at z14–18.

## Overview when zoomed out (2026-10-26, owner; every activity)
- Theme `lowzoom.*`: from = 13.5, to = 14.5. Up to `from` the map is an overview; from `from` to `to` everything fades
  and widens into the close-up look; from `to` on it is the close-up map.
- One helper set in `make_style.py`, used for hiking and bikes:
  - `low_fade(close_opacity, low)`: opacity `low` up to `from`, the layer's own opacity from `to`.
  - `low_to_close(low_key, close)`: widths that grow from the thin overview value into the close-up curve.
  - `route_band_paint()`: the hiking band and a long-distance cycle route's band.
- Routes in the overview: a thin solid line (`route.low.width`, opacity `route.low.opacity`), no band.
  - Hiking band: a single layer that changes with zoom.
  - Bikes: the mint band sits below the lanes, where a street lane would hide a thin line. So `routes-bike-low` (dark
    green `route.bike.low.color`, above the lanes) fades out while `routes-bike` fades in.
  - `MapController` switches the `-low` suffix together with the route layers.
- Paths / tracks / trails / closed-way dashes (`low_zoom_view()`, lists `LOW_SHARED` / `LOW_HIKE` / `LOW_BIKE`) use
  `lowzoom.path.opacity.hike` (0.35, faint – the trail network stays readable in the mountains) and
  `lowzoom.path.opacity.bike` (0, hidden).
  - When the two differ, each shared layer gets a `<id>-bikeview` twin. `MapController.setOverlay` shows the twin
    instead of its base layer for bike activities.
  - The green good-way lines use `lowzoom.goodway.opacity`.
- Guard: `StyleFormatTest.overviewZoomedOut`.
- Main roads on a route (bike5b): `road-<tertiary|secondary|primary|motorway>-edge` is a thin line in the casing colour
  (`road.edge.width`). `road_edges_on_top()` moves it just below `FIRST_LANE`, above the bike route band, so an orange
  road keeps its edge inside the mint band.
- Slim band close in and bike lanes at the road edge (bike5c): `route.bike.width` 20 / 18 / 22 px at z15 / 16 / 18 (was
  28 / 44 at z16 / 18). The cycle-lane offset in `bike_map()` puts the stripe just inside the road's edge. Before, it
  sat on the sidewalk.

## One-way arrows, forbidden ways, street space, underpasses (2026-10-26, owner; bike5d / bike5e)
- **Arrows** (`bike_map()`, bike activities; sprites `tools/make_sprite.py` – new sprites need a new APK):
  - dark `oneway-arrow`: one-way for everyone, bikes too;
  - green `bike-both-ways` (two-headed): one-way for cars, bikes both ways;
  - green `bike-oneway-arrow`: one-way only for bikes, or a one-way cycleway.
  - Streets read the router class: `cr` = the bike profiles' one-way (absent = bikes both ways). Without classes the
    tags are used (`oneway`, `ow` / `owb`).
  - Layers: `bike-oneway`, `bike-oneway-streets`, `bike-oneway-paths` (dark); `…-green-bikeview` (green single);
    `bike-both-ways`, `bike-both-ways-streets-bikeview` (green ⟷, not on access-no streets).
  - All arrows have a white outline and `icon-allow-overlap` (P icons hid them).
- **Forbidden ways:** dash opacity × `forbidden.fade.bike` (0.65, softer red `forbidden.bike.color`) /
  `forbidden.fade.hike` (0.8). Signs: `forbidden.sign.size` 0.7, `forbidden.sign.opacity.bike|hike` (0.7 / 0.85),
  spacing 600–900 px (service 900–1200), paths from `forbidden.sign.minzoom` 16, service roads from z17.
- **Street space** (`street_space()`, between `buildings()` and `roads()`, format 2, from z16, every activity):
  - overlay `areas` (schema 7, `a` = foot / square / island / platform / bridge): fill `street.area.color`, outline
    `street.area.outline.*`;
  - hidden sidewalk / crossing / traffic-island ways (paths `k = "x"`, `fw` = s / c / t): thin lines
    `street.space.line.*`.
  - The street space between the blocks is the plain background (`land.color`), already lighter and warmer than
    built-up land close in, so it needs no colour of its own (a colour there would tint open country too).
- **Underpasses** (`paths-underpass`, `paths-underpass-edge`, from z15, every activity): paths with `tn = "t"` (pipeline:
  tunnel = yes / layer < 0 under a railway or road) are a clear path line with thin dashed tunnel edges, above the
  railway. `paths-tunnel-town` keeps the faint dashed look for covered passages (`tn = "p"`) and for the old flag
  `tn = 1`.
- Guard: `StyleFormatTest.arrowsForbiddenStreetSpaceUnderpass`.

## Routes by zoom – region view (2026-10-26, owner; 0.11.4)
- One rule for hiking and bikes (`route_tiers()`, theme `route.tier.*`):
  - below `route.tier.regional` (11) only long-distance routes (net ≥ 3);
  - from z11 also regional routes (net 2);
  - from `route.tier.local` (12.5) every signed route.
  - Close in nothing changes.
- The merged band lines carry no network level, so they are the local tier (minzoom 12.5).
- Regional routes before that come from their net pieces: `routes-bike-regional` and `routes-hike-regional`, z11–12.5,
  each the thin green line its local tier starts with (bikes `route.bike.low.color`, hiking `route.band.color`).
  `MapController.setOverlay` switches the `-regional` suffix with the route layers.
- `routes-<kind>-major-low` is the long-distance tier (net ≥ 3). Region / overview data below z10 may extend it;
  `route_tiers()` changes nothing below z10.
- Guard: `StyleFormatTest.routeTiersByZoom`.

## Pink = long distance; greener region view (2026-10-26, owner on 0.11.4 – variants r1 … r3b)
- **Pink only for long-distance routes** (net ≥ 3), hiking and bikes alike: pink zoomed out = exactly the routes that get
  the pink-green band close in. The hiking major band (`routes-hike-major`) was net ≥ 2 – now net ≥ 3 like the bikes';
  regional hiking routes get the normal green band and keep their name label (`routes-hike-major-label`, net ≥ 2,
  coloured by net: pink-red for long distance, `route.bike.label.color` for regional).
- **Overview line** (`route_low_paint()`, theme `route.low.major.*`): pink-violet `#c02fb0`, 2.2 px at z7 → 3.2 px at z11
  → 3.6 px at z13 (was the raspberry `#c43a7d`, 1.2–2.6 px: "red, very similar to streets"). Below it a white halo
  (`route.low.halo.*`, 1 px each side, 90 %) as its own layer `routes-<kind>-major-low-halo-hikeview` / `-bikeview`:
  the route layers' switch has fixed suffixes, the view-group switch shows the halo with its activity group.
- **Roads below z10** in their own colour `<col>.low` (motorway / primary / secondary; was the casing colour) and a
  little thinner at z7–11 (`road.<class>.width.z10`): calmer, so the routes stand out.
- **Land veil** `land-tint` (theme `land.tint.*`): a background layer right above `landcover-farmland` – over the
  background, the region packages' relief (opaque tiles, the background colour is baked in, so a background colour by
  zoom would not show at z7–9) and farmland; below meadows, forest, towns and water. `#c8e0a6` at 60 % up to z9, 25 % at
  z12, 0 from z13 (layer maxzoom 13). It is no first layer and has no source: `Overview.merge` takes the cover colour
  from the first background (still `land.color`) and leaves the veil above the cover (guard checks this too).
- **Greens**: forest `#a9d789` / meadow `#c4e7a5` at z8, today's light greens from z13 – all a step greener than before
  (hue towards green, ~20 % more saturation, same lightness), also close in (meadow z15 `#e6f2d7`, forest z15
  `#d4edbf`, leisure, wetland, farmland a touch).
- **Built-up land** about 4 % darker (residential `#d9d7d2` → `#e1dfda` z14 → z16, industrial `#d3cfcd`), 90 % opaque
  at z9 so towns read on the greener land.
- Renders (private): `external-docs/map-quiet/region/index.html`.
- Guards: `StyleFormatTest.landTintZoomedOut`, `StyleFormatTest.pinkOnlyForLongDistance`.
