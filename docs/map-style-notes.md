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
| track grade3 | fine dotted edges (`track.edge.dash`) | always |
| track grade4–5 (soft / grass) | sparse dotted edges (`track.soft.dash`) | always |
| track without tracktype | as before: solid if OpenMapTiles says paved, else fine dots | always |
| trail with `trail_visibility` bad / horrible / no | same rhythm, sparser and lighter dots (`trail.faint.*`); T1/untagged faint paths get the T2 dot rhythm | always |
| SAC label "T1"…"T6" | small black text along the way (`trails-sac-label`) | **hiking only** |
| MTB label "S0"…"S5" | same look (`trails-mtb-label`) | **MTB / MTB e-bike only** |
| loose surface (gravel, stony, dirt, ground, grass, sand, …) or smoothness bad or worse | thin warm-brown edge on both sides (`surface.loose.*`, `line-gap-width`) | **bike activities only** |
Compacted and fine gravel do not count as loose (fine on any bike). The app switches the activity-dependent layers
in `MapController.setOverlay`. Tracktype edges come from `ways.geojson`, drawn over the base map's track with a
light cover (`track.cover.width`) that hides the base map's own edges.

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
