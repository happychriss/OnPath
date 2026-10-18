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
5. **Tracks differ from paths by colour and rhythm**: warmer mid-brown (#82592f), longer dashes, slightly wider.
6. **Route bands sit under trails** (pale opaque bands, drawn below roads/paths/trails) so they never wash lines out.
7. **Relief without mud.** Hillshade shadows are a cool blue-grey at moderate opacity instead of a warm purple-grey;
   brown-grey shadows on green forest turn olive/muddy.
8. **Colour-blind safety.** Trail difficulty is coded by colour *and* dash pattern (T1–2 dashes, T3 shorter, T4+ dots).
9. **Sunlight.** Labels are darker with a 1.5 px white halo; the `high-contrast` theme goes further
   (near-black paths, opaque casings, darker road casings, Medium-weight labels, lighter relief).

## How to change the look
All colours, widths / zoom stops, opacities, fonts and hillshade settings are design tokens in
`tools/themes/<theme>.json`. `tools/make_style.py` only holds the layer structure and order.

1. Edit tokens in `tools/themes/classic.json` (or a theme that `"extends": "classic"` and overrides a few tokens).
2. Regenerate: `python3 tools/make_style.py --all` (or `--theme high-contrast`; no argument = classic).
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
