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
   made paths … T5 wilderness; touring smooth and calm, gravel further out, MTB trails). The few controls only nudge it.

**Five axes, each 0–100, every suggestion explains itself in these words:**

| Axis | Means | Measured |
| --- | --- | --- |
| Natur | green beside the way, being deep inside nature | relative to the place |
| Ruhe | away from traffic and houses | relative to the place |
| Weg | the ground fits the character | relative to the place |
| Besonderes | water, known routes by rank, a rail trail, a named protected area, a tip | relative to the place |
| Form | time and ascent inside the wished ranges, nothing walked twice, not crossing itself | absolute |

"Relative to the place" = between the value that is poor here (a low quantile of the ways in reach) and the best any
loop from this start reaches: the loops are judged together, 100 means "as good as it gets from here". The absolute
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
| ascent | **[from, to]** metres up | missing = from the character and `effort` (leicht / mittel / sportlich) |
| pace | gemütlich / normal / zügig | km/h per activity |
| natur, ruhe, bekannt | niedrig / mittel / hoch | nudge the axis weights / the worth of signed routes |
| wasser | yes / no | "am Wasser entlang": water is what counts as special, the best loop at the water is shown first |
| surface | befestigt / gemischt / natur (wheels: glatt / gemischt / rau) | a step on the character scale for the ground |
| tips | yes / no | use the AI's region tips where a file exists |

Ranges instead of fixed values (owner 2026-11-14: "eine Range bei den Höhenmetern und eine Range bei der Zeit … man
erkennt den Willen des Nutzers besser"): inside both ranges every loop is equally fine, so the other axes decide; a
bit short or a bit flat falls off gently; above the ascent or clearly too long is not offered.

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
   protected area and the known routes / lakes it lies on.
4. **Place** (`place.py`): per way the absolute value on Natur, Ruhe, Weg, Besonderes for this character; the place's
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
     then the turning point is moved once so the length fits.
   Kept: at most one meeting with the own way, time within −30 % / +10 % of the range, ascent not above it.
6. **Judging** (`score.py`): the four place axes as means over the loop beyond the way out, read between "poor here"
   and the best loop of the pool; Form from the ranges, the share walked twice and a meeting; points = weighted mean.
7. **Selection**: the best first; with "am Wasser entlang" the best loop really at the water is always shown and
   first; then what stands out gets its best loop (a first-rank line, each area) if within 15 points; then further
   loops that share at most 60 % of their way beyond the way out and are within 18 points. At most five; fewer than
   three is fine when the rest is poor.
8. **Words**: a title from what the loop really uses (protected areas with ≥ 0.8 km, a lake, the main known route;
   else direction), a reason line with kilometres, honest hints (longer / shorter than wished, at a road, through
   houses, walked twice, crosses itself, at the edge of a range), and "stark bei … · schwächer bei …".

For the port: `facts`, `place`, `character`, `generate`, `score` are pure (no I/O); the edges are the network source,
the map-area reader and the grids. `tools/suggest-poc/cases.py` holds the owner's comments as checks – they become
the port's tests.

## 4. Data it needs

| Need | Source in the POC | On the phone |
| --- | --- | --- |
| nodes, links, allowed, way tags (highway, surface, tracktype, sac_scale, maxspeed, route_*, railway, estimated_noise_class, estimated_river_class) | BRouter's node cache through a small Java service (`service/RouteService.java`, `/graph`) | the same rd5 files; reading the node cache through `RoutingContract` is new |
| forest / heath / wetland, protected areas with names, settlement land, open water, place names | `base.pmtiles` of the map areas (`landcover`, `park`, `landuse`, `water`, `place`) drawn into a 45 m grid (`land.py`) | the same files |
| known routes with name and rank, trail names | `overlays.pmtiles` (`routes`, `trailnames`) | the same files |
| lakes with names, viewpoints | `base.pmtiles` (`water`, `water_name`), `pois.geojson` | the same files |

Missing in our map: which pieces form one circular route and in which order (a signed loop cannot be offered "as it
is"); ferries, monasteries, city parks, cable cars as named things. Map areas a run needs are fetched as the app does;
a run says when an area cannot be had (without the map there is no nature, no settlement, no names).

## 5. The owner's taste rules

- Known signed hiking and cycling routes are preferred, by rank; they are shown on the map.
- Along a road is always bad – also on an official cycle or hiking route; walking on a country road is out.
- Through houses is to be avoided when green is around; a half-town, half-housing loop is not a tour.
- A big connected nature area counts more than a green strip; an area is used, not touched (the Hirschpark).
- At most one meeting with the own way; eights "bringen es gar nicht".
- A short way out and back is fine (700 m on foot).
- Time and ascent are ranges; rather a clean loop at the edge of the range than a tangled one in the middle.
- A brook with green around it is always nice; with "am Wasser entlang" the loop must really be at the water.
- Rail trails are first-class for bikes.
- Few controls ("hoch, mittel, niedrig – nicht zu Tode konfigurieren").
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
  computer). It accepts the old single values and the new ranges. Its services are stopped; the VPN is the owner's step.
- **The real build:** this method written once in the shared Kotlin core and compiled for the browser like the router.
  Open: reading the network from BRouter's node cache in the browser, time and memory for bike wishes (200 000 nodes).
- Undecided by the owner: the entry only while the plan is empty; the same entry in the menu of a place / map symbol.

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
