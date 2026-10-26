# Map colours: how the OnPath route band and ground colours were derived

Status: analysis done 2026-10-19 (owner + Claude, cloud session); built into the app in **0.7.0** (map package with paths.geojson).
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

## Shipped in 0.7.0 (what was done)

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

**Track quality in the dots (variant 5, from z15).** Where OSM has a `tracktype` (61 % of the tracks in the test
area), the spacing of the dots shows the surface:

| Surface | Drawing |
|---|---|
| paved | two solid brown lines |
| grade1, compact | dense dots (dash 0.1 / 1.0) |
| grade2 or unknown | normal dots (0.1 / 1.7) |
| grade3 | wider dots (0.1 / 2.6) |
| grade4–5, soft or grass | sparse dots (0.1 / 4.0) |

Up to z14.5 a track is one brown dashed line. Tracks then come from our own `paths.geojson` (with `tt` and a paved
flag), like paths, so no base-map track line shows underneath.

## Quiet towns (2026-10-26, owner field report)

**Problem.** In towns the map was hard to read (owner, Düsseldorf and Hilden on the phone, 0.11.0):
- zoomed out (z11–13): hundreds of short near-black dashes – town footways from the coarse path copy and the base map;
- the street grid hardly showed: two dark grey edges around a near-white lane read as one grey line at z12–13;
- built-up land was nearly the colour of fields;
- in bike mode the 40 % cycle-route band and the pink long-distance dashes covered whole districts;
- in hike mode every town footway and sidewalk was a green line;
- path ends stopped a few metres before the street, at a sidewalk or crossing piece the map does not draw.

The hiking look in forests, meadows and mountains was fine and had to stay.

**Rule.** The map now knows where a way is in a town. The overlay attribute `u` is set by `extract_overlays.py`
(`TownIndex`):
- **`u = 1`, built-up:** inside residential / commercial / retail / industrial / railway / education landuse or a school
  or hospital area, grown by 25 m (landuse blocks often stop at the street), or in a dense block of buildings (≥ 8
  within ~120 m) where no landuse is mapped.
- **`u = 2`, town green:** park, garden, playground, pitch, cemetery, allotments.
- **Never set:** forests, meadows, fields and other open land win over both, and graded trails (T2+ or a faint trail)
  never get `u`.

Cells without `u` (built before schema 6) draw exactly as before.

| What | Before | Now | Why |
|---|---|---|---|
| Built-up land | `#e6e5e1`, 55 % at z9 → full at z13 | **`#e2e0db`**, full from z11 | Towns read as towns when zoomed out. ΔE00 to open land rises from 3.7 to 4.5; still light enough that the path ink keeps 11.2 : 1 on it |
| Industrial land | `#e0dadb` | **`#dcd8d6`** | one step below built-up land, as before |
| Town footways / paths / cycleways / steps (`u = 1`) | near-black `#33251a`, from z12 (base map) / z11 (coarse copy) | **mid-grey `#8e8780`**, thinner (0.9 / 1.4 / 2.2 px at z14 / 16 / 18), only from z14 (fade in from z13) | In a town every footway is walkable, so the line only has to say "there is a way". Grey keeps 2.7 : 1 on built-up land and 3.5 : 1 on a white street lane: readable close in, quiet zoomed out. ΔE00 34.5 to the dark path ink, so country and park paths still stand out where hiking starts |
| Town green (`u = 2`) | dark, from z11 | dark as before, but only from z13 | A cemetery or park path grid was a black mat at z11–12; close in, park paths are where people walk |
| Town streets (overlay `streets`, `u = 1`) | warm lane `#f9f7f1` 92 %, edges `#7d766d` | **white lane `#ffffff`**, a little wider (gap 1.9 / 2.6 / 4.4 / 8 px at z13 / 14 / 16 / 18), **light edges `#b8b2aa` → `#a39c93` → `#8f887f`** (z13 / 15 / 17) | The grid reads at a glance: the white lane has ΔE00 7.0 to built-up land (old lane 4.9), and light edges (1.6–2.7 : 1) frame it without drawing a dark line. Country streets keep the 0.7.0 look: pure white glared on hillsides (Meran test renders) |
| Small streets z11–12 (base map, no town flag) | gap 0.6 → 1.1 px, edges `#7d766d` 0.6 px | **gap 0.7 / 1.25 / 1.5 px** (z11 / 12 / 13), **edges `#958e85` 0.5–0.6 px**, lane `#fdfcf9` | A lane wider than its edges reads as a light street rather than a grey line. Kept modest because these zooms also show mountain farm roads |
| Pedestrian streets in town | dark line | **pale warm lane `#f1ebe0`** (2.6 / 6 / 14 px at z14 / 16 / 18); squares (`area=yes`) no outline at all | A walking street looks like a street you may walk on (ΔE00 3.8 to built-up land, 6.7 to a white street). A square's outline drew a thick ring around it |
| Path gap at sidewalks / crossings | gap of a few metres before the street | **link pieces `k = "l"`**: the hidden sidewalk / crossing piece from a loose path end to the nearest drawn way (≤ 30 m), drawn like the path (grey in town) | The path visibly reaches the street. Not drawn for detours (chain > 1.6 × the straight gap), for path pieces shorter than 20 m (spurs at traffic islands made small triangles) or towards another loose end |
| Hiking green line (`greenway-foot`) | on every town footway and sidewalk | not on town footways (`u = 1`) or squares; parks, country and cycleways outside towns as before | In a town every footway is walkable: the green said nothing and doubled the clutter |

**Bike activities** (the cycle-route view, owner 2026-10-26):

| What | Before | Now | Why |
|---|---|---|---|
| Signed cycle routes | 40 % band `#168403` (up to 17 px) + pink long-distance dashes | **thin teal line `#138a7c`** (0.8 / 1.0 / 1.3 / 2.0 / 2.6 / 3.6 px at z9 / 11 / 12 / 13 / 15 / 18), 55 % at z11, 65 % at z12, 90 % from z13 | Cycle-route networks are dense in cycling regions: bands covered whole towns. A thin line shows the network and leaves the streets readable. Lighter while zoomed out, where networks overlap most. 3.2 : 1 on built-up land, 3.1 : 1 on forest |
| National / long-distance routes (net ≥ 3) | pink line / pink dashes | **violet `#7a4bc4`** on top (1.1 → 3.8 px), 95 %, name in `#5a2f9e` | Long-distance routes stay findable inside the network: ΔE00 40 to the teal, 56 to the green ways; 4.4 : 1 on built-up land. Regional routes (net 2) are part of the teal network, with their name in `#0d5f55` |
| Ways good for the bike | thin green line `#2e9e3a` | the green line plus a **soft mint glow `#a9e4c6`** at 55 % (2.5 → 10 px) underneath; **roads with a cycle lane / track** get the glow beside the road (6 → 30 px, under the road casing); in town from z14 (fade in) | Shows at a glance where riding is pleasant, also on roads with a lane. Soft (ΔE00 14 on built-up land), so it never competes with the route lines. Later in towns, where bike infrastructure is everywhere |

**Forbidden ways** (owner 2026-10-26, Hilden industrial area: "forbidden tracks are super visible – normal streets
are smaller"):
- Rule: a forbidden marking is never wider or stronger than a normal street at the same zoom (`StyleFormatTest`
  checks the widths at z14–18).
- **The tint band is gone everywhere.** The old `rgba(120,70,70,0.28)` band was 4–12 px wide, wider than a minor
  street.
- **Paths / tracks**, which matter for hikers: the red dash `#c62828` stays (4.3 : 1 on built-up land), from z13; town
  paths only from z15. The no-entry sign is sparser: spacing 250 → 400 px instead of 90 → 160.
- **Private service roads and yards:** a thin muted dash `#b85c5c` at 85 % (0.7 / 1.1 / 1.4 px at z14 / 16 / 18,
  3.5 : 1 on a white lane), from z14. The sign shows only from z17, about once per way.
- **Private driveways / parking aisles** are not drawn below z17 at all. They had no marking before either, but drew
  as streets nobody may use.

Renders (private, not in the repo): Düsseldorf south z11.6–13, Oberbilk z14/15, Hauptbahnhof z17, Hilden centre z15/16,
Hilden industrial z15/16, Meran/Algund z12–16 (hiking look unchanged except village streets).
