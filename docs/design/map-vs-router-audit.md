# Map rules vs. BRouter: audit (origin/main 3511f3c, 2026-10-21)

Read-only audit. Sources: `tools/make_style.py` (format 2), `tools/themes/classic.json`, `tools/extract_overlays.py`
(`tile_attrs`, `street_attrs`), `MapController.setOverlay`, `BRouterEngine.profileFile/keyValues`,
`assets/brouter/*.brf` + `lookups.dat`.

How it was checked:
- **Router costs:** each profile was evaluated per tag set with BRouter's own expression engine
  (`BExpressionContextWay`, no routing; scratch `eval/EvalWay.java`). A costfactor of 9999 or more means forbidden.
  Hike runs with SAC limit 3 and preferred 3 (app default).
- **Counts:** pyosmium over `region.osm.pbf`, limited to the test bbox `10.70,46.30,11.60,46.85` (90 612 highway ways)
  (scratch `count_tags.py` → `counts.json`, which holds the examples).
- **Real routing:** `tools/routing/jvm-test/run.sh` with the scratch `jvm/PlarsTest.kt` test.

## Activity → profile (BRouterEngine)

| Activity | Profile | Overrides |
|---|---|---|
| Hike | hiking-mountain.brf | SAC_scale_limit = user T (1–5), SAC_scale_preferred = min(T,3) |
| Bike touring | fastbike-verylowtraffic.brf | – |
| Bike trekking, E-bike | trekking.brf | E-bike: power/speed/mass only (same costs) |
| MTB, MTB e-bike | mtb.brf | E-MTB: power/speed/mass only |

The map has two activity states, plus MTB for the S-labels: **walking** (greenway-foot, access-*-foot,
T-labels) and **not walking** (greenway-bike, access-*-bike, bike lanes, one-way arrows, loose-surface edges).
All four bike activities see the same map. The router has four very different bike cost models.

## Router costfactor per tag set (evaluated)

| Tags | Hike | Trekking/E-bike | Touring | MTB/E-MTB |
|---|---|---|---|---|
| highway=footway | 1.39 | 5.05 (push) | 106 | 10.4 |
| footway + access=private | **forbidden** | **forbidden** | **forbidden** | **forbidden** |
| footway + access=private + on hiking route | 13.2 (allowed, +12) | forbidden | forbidden | forbidden |
| footway + access=residents (alias → private) | forbidden | forbidden | forbidden | forbidden |
| footway + foot=private (no access tag) | 1.39 (!) | forbidden | forbidden | 10.4 |
| path + foot=no | 6.19 (+4) | 5.05 | 100 | 10.05 |
| path (untagged) | 1.39 | 5.05 | 100 | 10.05 |
| path + bicycle=yes | 1.39 | 1.05 | **100** | 6.67 |
| path + bicycle=designated | 1.39 | 1.05 | 2.0 | 6.67 |
| path + bicycle=no | 1.39 | 9.05 | 106 | 14.05 |
| path + vehicle=no | 1.39 | 9.05 | 106 | 14.05 |
| path sac T2 / T3 / T4 | 1.26 / 1.20 / forbidden | 35 / forbidden / forbidden | 130 / forbidden / forbidden | 10 / 18.8 / forbidden |
| path + trail_visibility=no | forbidden | 5.05 | 100 | 10.05 |
| cycleway / paved cycleway | 1.32 | 1.05 | 1.3 | 6.7 / **9.57** |
| cycleway + oneway=yes (reverse) | 1.32 | 101 | 101 | 107 |
| steps | 3.6 | 40 | 1000 | **forbidden** |
| via_ferrata | forbidden | forbidden | forbidden | forbidden |
| track / track grade3 | 1.39 | 5.05 / 3.05 | 100 / 10 | 7.6 / 7.8 |
| residential + access=private | forbidden | forbidden | forbidden | forbidden |
| residential oneway=yes (reverse) / + oneway:bicycle=no | – | 101 / 1.15 | 101 / 1.2 | 114 / 13.7 |
| pedestrian + bicycle=yes | 1.2 | 2.25 | 10 | 14.7 |

## Real routing check (JVM, BRouterEngine)

- **Plarserhof private footway.** OSM: `highway=footway`, `access=private`. Overlay: `sub=footway, ac=1`. It is a ~30 m
  way at the bus stop, **11.0996, 46.6843**. (11.1355, 46.6800 is about 3 km east. The nearest `ac=1` footway there is
  at 11.1317, 46.6820.) Routing end to end for Hike, Trekking and MTB gives a 69 m route that stays 14 m away from the
  footway, so it runs on the road next to it. **BRouter does not use it.** The map draws it green at z13+ and adds only
  the faint brown tint (rgba(120,70,70,0.28)) from z14 and the sign from z15.
- **Meran, way 15005851** (footway access=private, 11.15925, 46.66751). 67 m beeline. All profiles detour 176–183 m
  and never come closer than 50 m to it.

## Mismatches

Severity:
- **H** – the map suggests the way is good, but the router forbids it or strongly avoids it.
- **M** – the map shows the way as neutral, but the router forbids it, avoids it, or ignores it; or the map draws a
  way as good that the router avoids for one activity.
- **L** – cosmetic, rare, or by design.

| # | OSM tags | Map shows | BRouter does | Sev | Count in test bbox | Example |
|---|---|---|---|---|---|---|
| 1 | footway / pedestrian / cycleway + access=private\|no (no foot=yes\|designated\|permissive) | **Hike: green "good for walking"** (greenway-foot ignores `ac`). From z14 a faint tint, from z15 the sign. At z13 green only. Bikes: black + tint | Hike: forbidden (on a hiking route: allowed at +12). All bikes: forbidden | **H** | 113 ways / 4.4 km | Plarserhof 11.0996,46.6843. Meran w15005851 11.15925,46.66751. Schlehdorfweg w44527363 (access=no, on a hiking route) 11.16035,46.67853 |
| 2 | path / footway + bicycle=yes, unpaved, no tracktype | Bike modes: **green** (greenway-bike accepts yes\|designated) | Touring: cost 100 (only `designated` → 2.0). Trekking 1.05. MTB 6.7 | **H** (Touring) | 396 ways / 141 km | w25589308 11.46255,46.45451 |
| 3 | greenway-bike candidate + sac_scale ≥ T3 or mtb:scale ≥ 3 | Bike modes: **green** | Trekking / E-bike / Touring: forbidden | **H** | 33 ways / 21.6 km | w26402621 (bicycle=yes, mtb:scale=3) 11.05249,46.69063 |
| 3b | same + sac T2 or mtb:scale 2 | **green** | Trekking / Touring +30 (cost 31–130) | **H** | 136 ways / 64 km | w26612704 11.11036,46.6619 |
| 4 | Hiking route band over sac_scale above the user's limit (T4+ at default T3) | Band + T4/T5 label, dot-dash; nothing marks it as "over your limit" | Forbidden (SAC_access_penalty) | **H** | 112 ways / 90 km on routes (+82 ways / 92 km off routes) | w28078092 (alpine_hiking) 11.02148,46.69128 |
| 5 | Hiking route band over highway=via_ferrata or trail_visibility=no | Band (+ red via ferrata look / faint dots) | Forbidden for every profile | **H** (vis=no) / M (via ferrata is red) | 30 ways / 12 km | Patleideregger-Kirchweg w366611136 (vis=no) 10.99169,46.65795. Heini-Holzer-KS w171000850 11.25719,46.69205 |
| 6 | MTB / E-MTB: paved cycleways and cycle lanes | **green** (greenway-bike "every cycleway", bike-lane-*) | mtb.brf path_preference = 20: paved cycleway 9.6, roads 13.7, avoided against trails | **H**/M | 935 paved cycleways / 239 km | Passerpromenade w22749499 11.14983,46.66784 |
| 7 | route=mtb relations | MTB band (routes-mtb) | mtb.brf ignores mtb routes (`route_mtb_*` is in lookups but unused). Only cycle routes (lcn/rcn/…) count | M | 1452 ways / 461 km | Weißlahn w8599592 11.56121,46.4797 |
| 8 | Trails sac T3+ / mtb:scale 3+ in bike modes | Same dotted trail as for hiking. **T-labels hidden in bike modes**. S-labels only for MTB | Trekking / Touring / E-bike: forbidden from T3. MTB: forbidden from T4, T3 +15, mtb:scale 4–6 ≈ 9000 | M | 408 T3 ways / 327 km + 194 T4+ ways / 183 km | w23314171 11.49945,46.70767 |
| 8b | sac T2 in Trekking / Touring | Neutral dotted trail | +30 (cost ≈ 35 / 130) | M | 4266 ways / 2013 km | w8599612 11.53839,46.47621 |
| 9 | residential / unclassified / living_street + access=private\|no | Normal street, **no tint** (access layers: service class only) | Forbidden (all profiles) | M | 106 ways / 22.7 km | St.-Georgen-Straße w22749295 11.1726,46.67187 |
| 10 | trail_visibility=no (not on a route) | Faint dots (same as bad / horrible) | Hike: forbidden (bad / horrible only +1.5) | M | 15 ways / 12 km | w58993529 11.19048,46.66697 |
| 11 | bicycle=no\|private\|dismount\|use_sidepath, no access=no | Bike modes: no tint (access-*-bike needs `ac`) | Push / avoided: Trekking 9 vs 5, Touring 106, MTB 14. Forbidden if foot is also no | M | 1504 ways / 469 km | w8599597 (path bicycle=no) 11.53287,46.47168 |
| 12 | foot=no, no access tag | Hike: not green, but a plain black line, no tint | Hike: +4 (cost ~6, avoided) | M | 117 ways / 32 km | cycleway foot=no w28191356 11.34257,46.48984 |
| 13 | oneway=yes on cycleway / path / pedestrian / track (no oneway:bicycle=no) | No arrow: bike-oneway* skips path / track classes, and overlay `ow` drives only bike-both-ways | Bikes: reverse ×100 | M | 132 ways / 42 km | Laubengasse w23594307 11.16307,46.67154. Stuanbruch-Trail w26612661 11.09705,46.64939 |
| 14 | oneway:bicycle=yes on a two-way way | No arrow | Bikes: one-way | M | 16 ways / 3 km | Lupo-Trail w183216122 10.97029,46.62986 |
| 15 | steps | Dashed steps, same in all modes | MTB forbidden, Touring 1000, Trekking 40 | M | 1329 ways / 22 km | w26384539 11.00131,46.64603 |
| 16 | vehicle=no\|private (no bicycle tag) | Map does not carry `vehicle` | Bikes: push (Trekking 9, Touring 106, MTB 14) | M | 100 ways / 38 km | Zögglerwald track w32875603 10.99356,46.53737 |
| 17 | barrier node + access / foot = no\|private | Nothing (no barrier data) | Node blocked (initialcost 1e6) unless foot / bike is allowed on the node | M | 492 nodes | locked gate n291212258 11.02398,46.62730 |
| 18 | Hiking route over an access=no\|private way | Tint + sign (looks closed) | Hike: allowed at +12 (any_hiking_route overrides access) | M (router side) | 64 ways / 18 km | w42227301 (path access=no) 11.58536,46.805 |
| 19 | pedestrian + bicycle=yes\|designated | green (bike) | Touring 10, MTB 14.7, Trekking 2.25 | L/M | 97 ways / 9 km | Klosterstraße w22749517 11.15609,46.67323 |
| 20 | cycleway + bicycle=no\|dismount | green (bike) | push / forbidden | L (rare) | 2 ways | w34420920 11.3185,46.49236 |
| 21 | private driveway / parking_aisle | No tint (by design) | Forbidden | L | 1207 ways / 59 km | w26546846 11.03796,46.67186 |
| 22 | access=restricted\|residents\|employees | No tint (`ac` only for no / private) | lookups alias → private → forbidden | L | 1 way | w1327493527 11.36992,46.47639 |
| 23 | footway + foot=private (no access) | Hike: green, no tint | Hike: **allowed** (1.39). Profile quirk: footaccess = bikeaccess AND not foot=no. Bikes: forbidden | L (router bug) | 3 ways | w832235990 11.33486,46.49049 |
| 24 | highway=path (untagged) vs footway | path black, footway green | Hike: identical cost (1.39) | L | – | – |
| 25 | informal=yes | Not shown | Hike +1.0 | L | 118 ways / 26 km | w503169578 11.09954,46.79733 |
| 26 | highway=construction | (base map) | Hike allowed ×4, bikes forbidden | L | 30 ways / 11 km | Trienter Straße w29848556 11.34612,46.48847 |
| 27 | path + via_ferrata_scale (not highway=via_ferrata) | Red via ferrata look | Only sac_scale applies (T4+ here, so forbidden at T3) | L | 3 ways | Larix-KS w1433135013 10.78378,46.76484 |
| 28 | ford=yes | Not shown | Hike ×3. MTB large penalty | L | 10 ways | w351214498 11.43974,46.68037 |
| 29 | Bicycle route without icn/ncn/rcn/lcn network | Bike band | Router does not treat it as a cycle route | L | 2 ways | w1085647312 11.25342,46.36408 |
| 30 | Untagged path / track in Trekking (5.05) and Touring (100) | Neutral black / brown line | Strongly avoided | L (informational) | most of the ~50 000 path-like ways | – |

Consistent (checked, no action): access=no / private on path / footway / track / bridleway / service gets the tint and
is forbidden by the router (869 + 621 ways). access=no + foot=yes (hike) and access=no + bicycle=yes (bike) show no
tint and are allowed. access=destination / agricultural / forestry / customers / permissive are allowed everywhere
and not tinted. highway=via_ferrata is red and forbidden. oneway + oneway:bicycle=no shows the both-ways symbol and
costs nothing extra.

**Totals:** 30 mismatches:
- **7 H:** #1, #2, #3, #3b, #4, #5, #6
- **15 M:** #7–#18 and three halves rated M (#5 via ferrata, #6 for E-MTB lanes, #19)
- **8 L:** #20–#30, with #19 counted under M

Counted by row: 7 H rows, 12 M rows, 11 L rows.

## Attributes the router uses that the map data does not carry

- `vehicle` (#16), `motorroad`, and access values other than no / private (`restricted`, `residents`, `employees`
  alias to private in lookups.dat). OpenMapTiles' base map (tertiary and bigger) only has access "no".
- Node tags: `barrier` + access / foot / bicycle / `locked`, and node `ford` (#17).
- `informal`, `ford` (ways), `sidewalk` (hike: makes trunk / primary walkable), `bicycle_road` / `cyclestreet`,
  `cycleway:left/right:oneway` (bike one-way exceptions), `smoothness` / `surface` / `tracktype` on paths and streets
  (only in `ways` when loose / rough), and surface on streets.
- Route membership by network: the router uses `route_hiking_*` / `route_foot_*` (any network) and
  `route_bicycle_{icn,ncn,rcn,lcn}`. The map has `net` rank but not whether it is a "BRouter cycle route".
- The user's SAC limit is runtime state. The style never sees it (#4).
- `estimated_*_class` (traffic / noise / forest / town) live only in the rd5. They matter only for Touring
  (consider_traffic = true on primary / secondary / tertiary roads).

The map uses these, but the router ignores them:
- route=mtb relations (#7) and bicycle routes without a network (#29)
- `via_ferrata_scale` on non-via_ferrata ways (#27)
- `uv` (unpaved cycleway): Trekking and Touring cost all cycleways the same
- `vis` bad / horrible: hike gives only +1.5, which matches the map's faint look
- trail names / AVS numbers

## "Router decides, map shows it": feasibility

**Feasible with the jars in app/libs, without a routing run.** Proven in `eval/EvalWay.java` (≈40 lines, JDK 21,
classpath = the five brouter-*.jar), all 30 tag sets × 4 profiles in well under a second:

```java
BExpressionMetaData meta = new BExpressionMetaData();
BExpressionContextWay ctx = new BExpressionContextWay(meta);   // registers with meta
meta.readMetaData(new File(dir, "lookups.dat"));
ctx.parseFile(new File(dir, "trekking.brf"), "global", keyValues);  // same overrides the app passes
int[] ld = ctx.createNewLookupData();
ctx.addLookupValue("highway", "footway", ld); ctx.addLookupValue("access", "private", ld);
byte[] d = ctx.encode(ld);
ctx.evaluate(false, d); float fwd = ctx.getCostfactor();   // also getAccessType / priorityclassifier
ctx.evaluate(true, d);  float rev = ctx.getCostfactor();   // reverse direction = oneway handling
```

lookups.dat aliases are applied (access=residents → private gave "forbidden"), and route pseudo-tags work
(`route_hiking_lwn=yes` turned the private footway into 13.2). `BExpressionContextNode` works the same way for
barrier nodes.

### Sketch

`extract_overlays.py` already has every way's tags plus relation membership.

1. Write the relevant tags and route pseudo-tags (`route_hiking_<net>`, `route_bicycle_<net>`, computed like
   BRouter's map creator does) of each paths / streets feature to an ndjson file.
2. A small Java CLI (`tools/routing/classify`) evaluates the four profiles forward and reverse and writes back a
   class per profile, e.g. `ch` / `ct` / `cf` / `cm` with values 0 = good (≤1.3), 1 = ok, 2 = poor (push / ≥5),
   3 = avoid (≥30), 4 = forbidden (≥9999), plus a `cr` flag "reverse forbidden or ×100" for one-way arrows.
3. Hike needs SAC handled separately: evaluate at limit 6 and keep `sac` as is. The app then adds the runtime rule
   "sac > user limit → forbidden" as a style filter. MapController already switches layers per activity, so it can
   set `["get", "ch"]` vs `["get", "ct"]` with one expression per activity.
4. The style colours green / neutral / tint+sign only by that class. The tag-based filters stay as fallback for
   packages without the class (package.json gets a "profiles hash", and the app falls back when it does not match
   the shipped .brf).

### Costs and caveats

- Tile size: ~4 small ints per path / street feature, a few % of overlays.pmtiles.
- Any profile change means rebuilding the map cells (or accepting a mismatch until the next build). The profiles are
  OnPath-edited, so tie the class to a profile hash.
- E-bike = Trekking and E-MTB = MTB for costs, so 4 classes cover all 6 activities.
- Class thresholds are a product decision. Trekking makes every untagged path / track "poor" (5.05) and Touring makes
  them "avoid" (100), so a literal mapping would turn most mountain paths grey or red in bike modes.
- The base map's tertiary+ roads are not in the overlay. Either keep them tag-styled or add them to "streets".
- Unknown tag values: check how `addLookupValue` treats values missing from lookups.dat. It should mirror the map
  creator, which maps them to "unknown" / drops them.

### Rough effort

| Task | Effort |
|---|---|
| Java CLI + pipeline wiring (`build_region.sh`, cloud job image needs a JRE) | ~1 day |
| `extract_overlays` tag export / merge | ~0.5 day |
| Style + MapController switch + fallback | ~1 day |
| Tests (JVM eval vs. a few known ways, Paparazzi) | ~0.5 day |
| **Total** | **~3 days** + a map rebuild per region |

### Quick fixes worth doing first (hours, no new data)

| Fix | Rows |
|---|---|
| greenway-foot / greenway-bike: add `!has ac` unless foot / bicycle is yes \| designated \| permissive | #1, #20 |
| greenway-bike: only `bicycle=designated` paths for Touring (needs a third activity state), drop sac ≥ 3 / mtb ≥ 3 | #2, #3 |
| access tint on minor streets too | #9 |
| Show T-labels in bike modes | #8 |
| One-way arrows on overlay paths with `ow` (bike modes) | #13 |
| Hide routes-mtb (or map MTB to bike routes) | #7 |
