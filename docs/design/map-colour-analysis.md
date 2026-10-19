# Map colours: how the OnPath route band and ground colours were derived

Status: analysis done 2026-10-19 (owner + Claude, cloud session), chosen values not yet in the app.
Reproduce the numbers with `python3 tools/style_analysis/route_green.py`. The interactive matrix is
[`route-green-matrix.html`](route-green-matrix.html) (open in a browser).

## Why this document exists

OnPath's map style is our own. Some outdoor apps, Komoot among them, are well-known references for the general
*kind* of map hikers expect: black paths, dotted mountain trails, a coloured band along marked routes. Those are
long-standing topographic and Alpine-club map conventions, not something one app owns.

The concrete colours, opacities, widths and symbols in OnPath were **derived from our own analysis**. The analysis
used field-test feedback from the owner and a measurable contrast criterion, as recorded below. No colour values,
images, icons or data were taken from Komoot or any other app. The ground colours come from our own `classic`
theme (2026-10-17 onward). The route colour was chosen by an optimisation over our own ground colours.

## Starting point (field test, 2026-10-19, Ultental / Algund)

The owner tested 0.6.x on a hike and found the map too pale:

- **Route band hard to see on forest.** The route band was a light green `#c9f29b`, opaque from zoom 14, on forest `#a9cd8b`.
- **Route turned black at zoom 12.** There the route is a thin green line under the black path, so the path covered it.
- **Two styles were one too many.** "Normal" and "high contrast" should become one map that is fine *and* clear.

## Iterations and owner decisions

| Step | What was shown | Owner decision |
|---|---|---|
| 1 | Three full style proposals A/B/C (lighter forest, dark band edge, maximum contrast); renders z11–16 in three areas | **No edges on the band, ever.** Too many lines; only a slightly transparent band whose colour contrasts with the ground |
| 2 | Colour matrix: every ground colour × 10 greens, band without edge, path on top, colour difference per cell | Look at grounds and symbols too |
| 3 | Matrix v2: ground symbols (z14–16), no white anywhere in the path drawing | **No white casings or underlays** ("too nervous"). No symbols for normal forest and meadow. Symbol variant 1. Meadow in shadow should not look grey. Prefer a dark green at **lower opacity** |
| 4 | Matrix v3 with a computed optimum (below) | **Band #168403 at 40 %**; real map renders requested |
| 5 | Real renders (maplibre-gl, same tiles as the app) | **Residential: no hatch** (too busy when cycling through a town), a lighter neutral grey instead |

## Method

For every candidate green `G` and opacity `α`, the band seen on a ground colour `B` is the alpha composite
`C = (1−α)·B + α·G`. Two numbers are computed for every ground:

1. **Band visibility.** The CIEDE2000 colour difference ΔE00(C, B), which measures how differently a person sees
   the band and the bare ground. As a rule of thumb, ≥ 12 is clear at a glance and < 7 blurs on a phone.
2. **Path legibility.** The WCAG 2 contrast ratio between the path ink `#33251a` and the composited band `C`.
   ≥ 4.5 is very good (the WCAG text level); < 3 the black path starts to disappear in the band.

A hiker needs the band on *every* ground, so the score of a colour is its **weakest ground** (the minimum over
all grounds), not the average. The grounds are wood, grass, leisure, wetland, farmland, open land, residential,
rock, sand and ice. Wood and grass also appear "in hill shadow", i.e. mixed with the hillshade shadow colour.

The optimiser searches CIE LCh space: hue 120–150° (green), lightness 20–90 and chroma 20–84, about 5 000
colours. It keeps the colour with the largest weakest-ground ΔE00 among those whose path contrast stays above a
floor (3.5 or 4.5).

## Results (from `tools/style_analysis/route_green.py`)

Weakest ground at 40 % opacity:

| Green | Ground today, blue-grey shadow | Lighter ground, greenish shadow |
|---|---|---|
| light `#c9f29b` (until 0.6.9) | band 3.7 · path 7.5 | — |
| dark `#2e7d32` | band 8.3 · path 4.1 | band 10.4 · path 5.3 |
| **chosen `#168403`** | band 11.4 · path 4.2 | **band 12.4 · path 5.3** |
| very dark `#014e08` | band 14.3 · path 3.2 | band 16.0 · path 4.2 |
| optimum, path ≥ 4.5 | `#1aa200` band 10.3 · path 4.7 | `#06630f` band 14.2 · path 4.6 |
| optimum, path ≥ 3.5 | `#06630f` band 12.4 · path 3.6 | `#003a01` band 17.9 · path 3.8 |

Findings:

- **A dark green at low opacity is the right kind of band.** It shows because it darkens the ground a little and
  makes it greener. A light green barely differs from light forest: today's light green scored 3.7.
- **There is a trade-off.** A darker band is more visible, but the black path on it reads worse. No single colour
  maximises both.
- **The weakest ground is always forest,** usually forest in hill shadow. Making the forest lighter and the hill
  shadow greenish raises every candidate by about 1–4 points, more than most choices of green.
- **`#168403` at 40 % is a balanced choice.** On the lighter ground it is clearly visible (12.4) and keeps the
  path very legible (5.3). It is close to the computed optimum and stays saturated, so it reads as "green route"
  rather than "dark shadow".

Limits: these are screen colours (sRGB, ideal display). Sunlight and glare on the glass reduce all contrasts, so
the real map renders and a field test have the last word. Symbols and relief texture are not part of the numbers.

## Chosen style (proposal `tools/themes-proposals/d-final.json`)

- **Route band:** `#168403`, 40 % opacity at every zoom, no edge. Route pieces are merged into continuous lines
  in the pipeline, so the transparent band does not darken where pieces meet. Only real junctions and tight
  hairpins overlap slightly.
- **Ground:** wood `#c3dcab`, grass `#dfeacb`, leisure `#d9e8c4`, farmland `#f1f0de`, land `#f6f4ec`,
  residential `#e6e5e1` (light neutral grey, no pattern; band 23.6 · path 7.0 on it).
- **Hill shadow:** greenish `rgba(45,68,45,0.40)` instead of blue-grey, so shaded meadow does not look grey.
- **Paths:** black lines only, no white casing, no light underlay. From z13.5 paths are drawn from our own exact
  path data (`paths.geojson`, pipeline change). A dotted mountain trail then no longer needs to hide a solid
  base-map line underneath.
- **Tracks (variant A):** two thin brown dashed edges (`line-gap-width`), no light fill. The ground and the band
  show through between them, and the double edge reads as a wider way than a path.
- **Ground symbols** from z14, as small `fill-pattern` tiles, very light:
  - wetland: marsh tufts
  - farmland: furrow dashes
  - rock: rock outlines
  - sand: dots

  None for forest and meadow (owner decision).
- **Cable-car ropes:** wider with zoom (1 → 3.2 px), like the railway. Already in the app style since 2026-10-19.

## Before / after (maplibre-gl render of the same map data)

| Before (0.6.9) | After (proposal) |
|---|---|
| ![](img/before-algund-12.jpg) | ![](img/after-algund-12.jpg) |
| ![](img/before-algund-14.jpg) | ![](img/after-algund-14.jpg) |
| ![](img/before-ulten-15.jpg) | ![](img/after-ulten-15.jpg) |
| ![](img/before-meran2000-14.jpg) | ![](img/after-meran2000-14.jpg) |

## Still to do before it ships

1. **Pipeline:** `extract_overlays.py` writes `paths.geojson` (every path and track, exact geometry, `k`, `sac`,
   `vis`, steps flag) and merges route pieces per kind into continuous lines.
2. **Style:**
   - Move the post-processing in `tools/themes-proposals/d-final-post.py` into `make_style.py`:
     - own path layer
     - track variant A
     - symbol layers
     - 40 % route opacity
   - Make the chosen theme the only one and drop "high contrast".
3. **App:**
   - Add the symbol tiles at style load (`style.addImage`).
   - Remove the theme switch in Settings.
   - Add `paths.geojson` to `OptionalSources`.
4. **Field test** in sunlight.

## Road types: street, track, path (2026-10-19, proposal)

For hiking and cycling the key question is the surface. Is it a paved street, an unpaved track (forest or farm
road, `highway=track`, theoretically drivable) or a path? The owner rejected white street fills with a dark casing
as too busy. Four variants without any white were rendered
(https://claude.ai/artifact/JfUteXEasokSfqgJeCn8hq, `tools/themes-proposals/roads-post.py`):

| Variant | Street (minor, service) | Track |
|---|---|---|
| 1 | solid grey line | grey dashed line |
| 2 | solid grey line | brown dashed line |
| 3 | two thin grey edges (open, no fill) | two grey dashed edges |
| **4 (recommended)** | two thin grey edges | brown dashed line |

The rule behind variant 4 is that every type gets its own **shape**, not only a colour, because colour differences
fade in sunlight:

| Type | Shape |
|---|---|
| street | double line, reads as "wide, paved" |
| track | dashed, reads as "not paved"; brown |
| path | thin solid black |
| mountain trail | black dots |

A solid grey street (1, 2) reads like a thick path. Grey double-dashed tracks (3) are too faint, and tracks matter
most for cyclists. Track quality from `tracktype` sets the dash length:

| `tracktype` | Dash |
|---|---|
| grade1–2 (compact) | long dashes |
| grade3 | medium dashes |
| grade4–5 (soft, grass) | short dashes |
| paved track | solid |

Decision pending (owner).

**Owner variant 5 (2026-10-19):**
- Street: two separate solid grey lines.
- Farm road: two dotted brown lines (round dots).
- Neither has any white. Both show through under the 40 % green band.

Renders show that it works well from z15. At z14 the two dot rows of a farm road move close together. There, a
single brown dashed line is suggested so that it cannot be mixed up with the single black dot row of a T2 trail.
