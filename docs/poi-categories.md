# POI categories (build 2) – for the settings page

Source of truth for kinds: `tools/extract_pois.py` (property `kind` in `pois.geojson`).
Group colours: `poi/PoiKinds.kt` (`PoiGroup`) = `tools/make_sprite.py` (`GROUPS`).
Zoom tiers / priority: `tools/make_style.py` (`POI_TIERS`).

## Kinds

"Icon from" = the zoom where the icon first appears; "Label from" = the zoom its name appeared until 2026-11-17 –
since then every POI name shows from z17 (owner, "Style layers" below).
Within a tier, the order in the table is the placement priority (collision is on, so higher rows win).
Named POIs win over unnamed ones of the same kind. Place and peak labels always win over POIs.

| Kind | Group | EN / DE | Icon from | Label from | Default |
| --- | --- | --- | --- | --- | --- |
| `hut` | hut | Mountain hut / Schutzhütte | z12 (dot), z13 badge | z14 | on |
| `bivouac` | hut | Bivouac / Biwak | z12 (dot), z13 badge | z14 | on |
| `viewpoint` | sight | Viewpoint / Aussichtspunkt | z12 (dot), z13 badge | z14 | on |
| `spring` | water | Spring / Quelle | z12 (dot), z13 badge | z14 | on |
| `waterfall` | water | Waterfall / Wasserfall | z12 (dot), z13 badge | z14 | on |
| `lift` | transport | Cable car / lift station | z12 (dot), z13 badge | z14 | on |
| `train` | transport | Train station / Bahnhof | z12 (dot), z13 badge | z14 | on |
| `castle` | sight | Castle / Burg | z12 (dot), z13 badge | z14 | on |
| `water` | water | Drinking water / Trinkwasser | z14 | z15 | on |
| `restaurant` | food | Restaurant / Gasthaus | z14 | z15 | on |
| `cafe` | food | Café | z14 | z15 | on |
| `shelter` | hut | Shelter / Unterstand | z14 | z15 | on |
| `camping` | hut | Campsite / Campingplatz | z14 | z15 | on |
| `hotel` | hut | Hotel (OSM tourism=hotel, motel) | z14 | z15 | off |
| `guest_house` | hut | Guest house / Pension (tourism=guest_house) | z14 | z15 | off |
| `hostel` | hut | Hostel (tourism=hostel) | z14 | z15 | off |
| `church` | sight | Church / Kirche | z14 | z15 | on |
| `ruins` | sight | Ruins / Ruine | z14 | z15 | on |
| `cave` | sight | Cave / Höhle | z14 | z15 | on |
| `museum` | sight | Museum | z14 | z15 | on |
| `chapel` | sight | Chapel / Kapelle | z14 | z15 | on |
| `picnic` | facility | Picnic site / Rastplatz | z15 | z16 | on |
| `toilets` | facility | Toilets / Toiletten | z15 | z16 | on |
| `info` | facility | Tourist information | z15 | z16 | on |
| `bar` | food | Bar / Kneipe | z15 | z16 | on |
| `fast_food` | food | Snack bar / Imbiss | z15 | z16 | on |
| `bike_shop` | facility | Bike shop / repair | z15 | z16 | on |
| `charging` | facility | E-bike charging | z15 | z16 | **off** |
| `shop` | food | Food shop / Lebensmittel | z15 | z16 | **off** |
| `parking` | transport | Parking / Parkplatz | z15 | z16 | on |
| `bus` | transport | Bus stop / Bushaltestelle | z15 | z16 | on |

The "Default" column is the map agent's suggestion (everything on except `shop` and `charging`; the
zoom tiers and collision already keep the map calm). The app currently uses a stricter set in
`PoiKinds.defaultHidden` (bar, fast_food, shop, bus, parking, toilets, picnic, info, bike_shop, charging,
church, chapel); that is the source of truth, both work with the style. `charging` could be switched on
automatically for the e-bike activities. A setting UI grouped by `PoiGroup` (Food & drink, Huts &
camping, Water, Sights, Transport, Facilities) with one switch per kind works well.

Counts in the test region (bbox 10.85,46.35,11.60,46.85; 11,390 POIs): parking 2471, bus 2128
(after merging), restaurant 1394, water 1143, church 537, viewpoint 481, shop 478, cafe 459, bar 371,
toilets 249, shelter 219, lift 210, picnic 196, ruins 164, castle 125, fast_food 114, chapel 108,
spring 86, waterfall 77, museum 68, bike_shop 61, info 60, train 47, hut 43, camping 35, charging 30,
bivouac 21, cave 15.

## Style layers (all symbol layers, source `pois`)
- `poi-tier3`, `poi-tier2`, `poi-tier1` (bottom to top; minzoom 15 / 14 / 12). There are no
  circle or glyph layers. **POI names: normally hidden, from z17 written out (owner 2026-11-17).** Each POI layer
  is one zoom band in two layers that never show at the same zoom: `poi-<x>` below z17 (icon only + an invisible key
  text) and `poi-<x>-named` from z17 (the icon WITH its name in one symbol: icon required, text optional – the icon
  stays when its name has no room, a name shows only beside its own placed icon). Same filter / sort key / icon.
  Same for `poi-station` / `poi-bus-detail`; `poi-bus` (merged stop pairs) ends at z16, so it has no names.
  Never a `-name` id: the tap lookup takes a `-name` layer for text only (`PoiHit.symbolLayer`).
  Why: until 2026-11-17 the names were separate `-name` layers after all icons – a name could not know whether its
  icon was placed (MapLibre checks only boxes placed before; Native 13.7 has no `*-overlap: cooperative`) and it
  collided with its own placed icon (radial offset 1.05 em), so nearly every name shown was a bare one (Hilden
  2026-11-15: a bus stop's name read like a street name). Render check 2026-11-17, main → now: below z17 the same
  icons everywhere, 0 names (main showed only bare ones); at z17+ names beside their icons (Hilden St.-Konrad-Allee
  z17.2: 4 names, 0 icons lost), and where it is crowded a name takes the room of a smaller icon – Soho z17 −8 of 52
  icons, Meran z17 −3 of 32, Soho z18 none (owner: accepted). Bare names: 0. Machine check: `PoiSymbolStyleTest`
  (every kind × zoom × style file: a name only in its icon's symbol, symbols first below z17, no names below z17,
  names from z17, symbol in the sprite, Settings filter hides the name too).
- Filters are expressions on `["get","kind"]`, so the app filter
  `["all", <original filter>, ["in", ["get","kind"], ["literal", [enabled kinds]]]]` works.
  Keep the original filter of each layer (read it with `layer.filter` before wrapping, or remember it once
  after the style loads), otherwise a kind may appear at the wrong zoom.
- The tap / hold lookup (`ShownPois`, read from all `poi-*` symbol layers while the map is idle) hits a POI only
  through its drawn symbol (icon ~21 dp + the 24 dp finger box); a name – beside its icon or bare – is never a target.

## Sprite
- Style: `"sprite": "asset://sprites/onpath"`. MapLibre Native appends `.json`/`.png` and, on screens with
  pixel ratio > 1, `@2x.json`/`@2x.png`. Files: `app/src/main/assets/sprites/onpath{,@2x}.{json,png}`.
  No app-side code is needed; `asset://` works like the glyph URL (`asset://fonts/...`).
- `StyleLoader` only replaces `{DATA_DIR}`; it must not rewrite `asset://` URLs. The fallback style (no data)
  does not need the sprite.
- Images: `poi-<kind>` (badge, 24×24 px at 1x incl. shadow) and `poi-<kind>-small` (12×12 dot), plus
  `poi-default`/`poi-default-small`. The app may reuse them, e.g. in the settings list, by reading the PNG
  regions from the JSON – or draw its own coloured circles with the `PoiGroup` colour.
- Regenerate: `pip install cairosvg pillow && python3 tools/make_sprite.py` (then `python3 tools/make_style.py`).
- Icons: Maki + Temaki (CC0); see `assets/sprites/LICENSE-icons.txt`.

## Data change
`tools/extract_pois.py` now merges bus stops with the same name within 40 m (both directions of a road)
into one point at their midpoint (820 merged in the test region). Re-push `pois.geojson` to the phone.

## Places to stay – „Übernachten“ (owner 2026-11-26)

`PoiKinds.LODGING` (core) is the one definition: hut, bivouac, camping, hotel, guest_house, hostel (not shelter). It is the
search chip „Übernachten“ / „Lodging“ (`SearchKinds.Category.SLEEP`, app and planner – huts are in Essen too) and
`PoiKinds.isLodging` for the multi-day tour (a lodging place set as a night becomes its hotel). Hotels, guest houses and
hostels are off on the map by default (calm map; `DEFAULTS_VERSION` 3). Holiday flats / chalets stay out. A Gasthof
tagged amenity=restaurant stays a restaurant. Map areas built before 2026-11-27 have no hotels (rebuild needed).
