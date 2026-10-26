#!/usr/bin/env python3
"""Extract trail and route-network overlays from an OSM .pbf into small GeoJSON files.

Output (in OUT_DIR):
  routes.geojson  - official hiking / bicycle / mtb route relations, merged per kind into continuous lines (the
      band is transparent); major routes (net >= 2) again as original pieces with "majoronly": 1, ref, name, net
  trails.geojson  - paths with SAC scale (hiking difficulty), mtb:scale and/or poor trail_visibility.
      Props: sac 0-6 (always), mtb "0".."6" (first char of mtb:scale), vis 0-5 (trail_visibility, if tagged)
  ways.geojson    - OPTIONAL way quality (docs/map-pipeline.md "Way quality"): tracks with tracktype, and
      path/footway/cycleway/bridleway/track ways with a loose surface or bad smoothness.
      Props: k "t" track / "p" path, footway, bridleway / "c" cycleway; tt 1-5 (tracktype gradeN);
      surf 1 paved, 2 compact, 3 gravel/stony, 4 natural soft (only if tagged); smooth 0-7 (only if tagged)
  trailnames.geojson - OPTIONAL label lines (style_trail_names.py):
      t="n": trail names ("Algunder Waalweg"), from the way's name:de / German part of name, or
             the name of a hiking route relation for unnamed member ways; consecutive ways with the
             same name are merged so labels place well. Props: name, tier (1 = Waalweg/Höhenweg/long,
             2 = normal, 3 = short), len (m).
      t="r": hiking route numbers (AVS "25", "24A"; several on one way joined as "24 · 25").

  paths.geojson   - every path / footway / bridleway / track (+ ways with sac_scale), exact geometry (~0.5 m), drawn
      by the map from z13 instead of the base map's lines. Props: k "t"/"p", sac 0-6, vis (if tagged), st 1 (steps),
      tt 1-5 (tracktype), pv 1 (paved track)
Format 2 (docs/design/map-data-size.md §2): with --tiles DIR the same data is also written as line-delimited GeoJSON
per vector-tile layer (paths, streets, trails, ways, routes, trailnames .ndjson, with per-feature "tippecanoe" zoom
ranges; streets = the small streets the base map no longer has at z13-14, see STREETS) for
tools/build_overlays.sh, which turns them into ONE overlays.pmtiles. The tile "paths" layer replaces lowways (coarse
copy at z11-12) and carries what the style needs to drop the base map's path / track lines (schema: see
docs/map-pipeline.md "Overlays as vector tiles"). --no-geojson skips the format-1 files.

Towns and path links (format 2, "quiet towns" 2026-10-26, docs/design/map-colour-analysis.md): "paths" (also the z11-12
copy) and "streets" features get u = 1 in built-up land / 2 in town green (TownIndex: one more read of the file for
landuse / leisure / natural / amenity areas and building centres); path ends that stop at a hidden sidewalk / crossing
get link pieces k = "l" (link_pieces); pedestrian squares carry ar = 1.

Router classes (--wayclass JAR, format 2; docs/map-style-notes.md "Router classes", owner decisions 2026-10-22):
tools/routing/wayclass (BRouter's own expression engine and OnPath's profiles, --profiles DIR) classifies every
"paths" (z13-14) and "streets" feature per activity group: ch hike, ct trekking + e-bike, cf touring, cm MTB +
e-MTB, each 0 good / 1 possible / 2 forbidden / 3 too hard (forbidden only by sac_scale / mtb:scale /
trail_visibility or as a via ferrata: grey, not red); cr 1 / -1 = bikes may ride only along / against the line. Barrier
nodes that block an activity go to the tile layer "barriers" (b = mask 1 hike, 2 trekking, 4 touring, 8 MTB). Hiking
route pieces carry "sac" so the app can grey the band above the user's T level. The profile hash goes into
DIR/wayclass.json (build_overlays.sh, package.json, cell manifest).

Usage: python3 extract_overlays.py input.osm.pbf OUT_DIR minlon minlat maxlon maxlat [--tiles DIR] [--no-geojson]
       [--smooth [--smooth-angle 15] [--smooth-dev 3]] [--wayclass JAR --profiles DIR]
"""
import argparse
import json
from array import array
import math
import os
import re
import subprocess
import sys

import osmium
from shapely.geometry import LineString, MultiLineString, box, mapping, shape
from shapely.ops import linemerge, unary_union

_ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
_ap.add_argument("pbf"); _ap.add_argument("out_dir")
_ap.add_argument("bbox", nargs=4, type=float, metavar="MINLON MINLAT MAXLON MAXLAT")
_ap.add_argument("--tiles", metavar="DIR", help="also write format-2 tile inputs (*.ndjson) for build_overlays.sh")
_ap.add_argument("--no-geojson", action="store_true", help="do not write the format-1 *.geojson files")
_ap.add_argument("--smooth", action="store_true",
                 help="round the bends of paths / tracks / trails / small streets (and route bands on them); "
                      "default for format 2")
_ap.add_argument("--smooth-angle", type=float, default=15.0, metavar="DEG",
                 help="--smooth: round bends that turn by at least this much (default 15)")
_ap.add_argument("--smooth-dev", type=float, default=3.0, metavar="M",
                 help="--smooth: the curve stays within this many metres of the OSM line (default 3)")
_ap.add_argument("--wayclass", metavar="JAR",
                 help="format 2: router classes per way from tools/routing/wayclass (build.sh makes the jar)")
_ap.add_argument("--profiles", metavar="DIR", help="--wayclass: lookups.dat + the .brf profiles the app routes with")
_ap.add_argument("--java", default=os.environ.get("JAVA", "java"), help="--wayclass: java command (default $JAVA or java)")
ARGS = _ap.parse_args()
if ARGS.wayclass and not (ARGS.tiles and ARGS.profiles):
    _ap.error("--wayclass needs --tiles and --profiles")
pbf, out_dir = ARGS.pbf, ARGS.out_dir
minlon, minlat, maxlon, maxlat = ARGS.bbox
TILES = ARGS.tiles
DO_SMOOTH = ARGS.smooth  # (SMOOTH is the smoothness table below)
BBOX = box(minlon, minlat, maxlon, maxlat)
SIMPLIFY = 0.00003  # about 3 m
FINE_SIMPLIFY = 0.000005  # about 0.5 m: trails / ways are drawn over the base map's own lines (no ghost lines)

SAC = {"hiking": 1, "mountain_hiking": 2, "demanding_mountain_hiking": 3,
       "alpine_hiking": 4, "demanding_alpine_hiking": 5, "difficult_alpine_hiking": 6}
VIS = {"excellent": 0, "good": 1, "intermediate": 2, "bad": 3, "horrible": 4, "no": 5}
TRACKTYPE = {f"grade{i}": i for i in range(1, 6)}
SURF = {**dict.fromkeys(("paved", "asphalt", "concrete", "concrete:plates", "concrete:lanes", "paving_stones",
                         "sett", "cobblestone", "unhewn_cobblestone", "metal", "wood", "chipseal"), 1),
        **dict.fromkeys(("compacted", "fine_gravel"), 2),
        **dict.fromkeys(("gravel", "pebblestone", "rock", "stone", "scree", "shells"), 3),
        **dict.fromkeys(("dirt", "ground", "earth", "mud", "grass", "sand", "woodchips", "grass_paver"), 4)}
SMOOTH = {"excellent": 0, "good": 1, "intermediate": 2, "bad": 3, "very_bad": 4, "horrible": 5,
          "very_horrible": 6, "impassable": 7}
WAY_KIND = {"track": "t", "path": "p", "footway": "p", "bridleway": "p", "cycleway": "c"}
ROUTE_KIND = {"hiking": "hike", "foot": "hike", "bicycle": "bike", "mtb": "mtb"}



# Ways drawn zoomed out from our own data (trails-low), also without a difficulty grade.
LOW_SIMPLIFY = 0.00008  # ~8 m: lowways are only drawn below z13
LOW_WAYS = {"path", "footway", "bridleway", "track"}

# --- format 2: extra ways and attributes of the tile layer "paths" (only with --tiles) ----------------------------
# The base map's path / track lines are dropped at z13-14 in format 2, so every way a style layer used to read from
# the base map's transportation layer must be in "paths": cycleways, steps, pedestrian streets / areas, sidewalks and
# crossings (green ways), plus via ferratas and roads with cycle lanes for the planned bike map. They get k="x" (never
# drawn as a path / track line); k "t" / "p" stay exactly as in paths.geojson.
EXTRA_WAYS = {"cycleway", "steps", "pedestrian", "via_ferrata"}
ROADS = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
         "service", "road", "busway", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"}
CYCLE_INFRA = {"lane": "l", "track": "t", "shared_lane": "s", "share_busway": "b",
               "opposite_lane": "o", "opposite_track": "o", "opposite": "o"}
# OpenMapTiles' "unpaved" surfaces (the base map's surface=unpaved, used by the cycleway-unpaved layer)
UNPAVED = {"unpaved", "compacted", "dirt", "earth", "fine_gravel", "grass", "grass_paver", "gravel", "gravel_turf",
           "ground", "ice", "mud", "pebblestone", "salt", "sand", "snow", "woodchips"}
ONEWAY = {"yes": 1, "1": 1, "true": 1, "-1": -1, "reverse": -1}
LOW_ZOOMS, HIGH_ZOOMS = (11, 12), (13, 14)  # tile "paths": coarse copy (former lowways) / exact ways

# --- router classes (--wayclass) --------------------------------------------------------------------------------
WAYCLASS = ARGS.wayclass is not None
BARRIER_ZOOMS = (14, 14)
# BRouter's map creator turns route relations into way pseudo-tags "route_<route>_<network>" = yes | proposed
ROUTER_ROUTES = {"bicycle", "mtb", "hiking", "foot"}

# --- towns (format 2, "quiet towns" 2026-10-26; docs/design/map-colour-analysis.md "Quiet towns") --------------------
# Tile attribute u on "paths" and "streets": 1 = the way lies in built-up land (the style draws footways there thin
# grey and only from z14, streets white), 2 = in a park / cemetery / allotments inside a town (paths stay dark, but
# only from z13). Not set in forests, fields, meadows and open land, and never on graded trails (T2+ or a faint trail):
# the hiking look stays as it is. Built-up = landuse / amenity areas of a town, grown by TOWN_BUFFER_M because landuse
# blocks often stop at the street, or a dense block of buildings where no landuse is mapped.
TOWN_LANDUSE = {"residential", "commercial", "retail", "industrial", "garages", "railway", "education"}
TOWN_AMENITY = {"school", "university", "college", "hospital", "kindergarten"}
TOWN_GREEN = {("leisure", "park"), ("leisure", "garden"), ("leisure", "playground"), ("leisure", "pitch"),
              ("leisure", "stadium"), ("leisure", "dog_park"), ("leisure", "recreation_ground"),
              ("landuse", "cemetery"), ("landuse", "allotments"), ("landuse", "recreation_ground"),
              ("landuse", "village_green"), ("amenity", "grave_yard")}
COUNTRY_GREEN = {("landuse", "forest"), ("landuse", "meadow"), ("landuse", "farmland"), ("landuse", "orchard"),
                 ("landuse", "vineyard"), ("natural", "wood"), ("natural", "scrub"), ("natural", "heath"),
                 ("natural", "grassland"), ("natural", "wetland"), ("leisure", "golf_course")}
TOWN_BUFFER_M = 25.0
BUILDING_GRID_M = 40.0   # building density: centres counted per grid square ...
BUILDING_DENSE = 8       # ... a point is built-up with at least this many buildings in the 3 x 3 squares around it
# Gap fix (2026-10-26): sidewalks / crossings (footway=sidewalk|crossing) are not drawn, so a path that ends on one
# stopped a few metres before the street. The hidden pieces that join such a path end to the nearest drawn way become
# tile features k="l" (drawn like the path, same u), at most LINK_MAX_M along the hidden ways. Left out: links that
# would close a small loop (back onto the same path, or a detour much longer than the straight gap – corner triangles,
# traffic islands).
LINK_MAX_M = 30.0
LINK_MAX_DETOUR = 1.6    # chain length / straight distance start -> end
LINK_MIN_PATH_M = 20.0   # no link from a path piece shorter than this (spurs at traffic islands / corners)
TOWN_WAYS = {"path", "footway", "bridleway", "cycleway", "steps", "pedestrian"}
# ways the map draws (a path end touching one of these is no gap): roads and streets, paths / tracks, cycleways, steps,
# pedestrian streets, via ferratas – not sidewalks / crossings, platforms, corridors, construction
DRAWN_WAYS = ROADS | LOW_WAYS | EXTRA_WAYS | {"bridleway"}


def lookup_keys(profiles_dir):
    """Way and node tag keys BRouter knows (lookups.dat): only these reach the classifier (fewer distinct tag sets)."""
    keys, ctx = {"way": set(), "node": set()}, None
    with open(os.path.join(profiles_dir, "lookups.dat"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("---context:"):
                ctx = line.strip().split(":", 1)[1]
            elif ctx in keys and ";" in line and not line.startswith("-"):
                keys[ctx].add(line.split(";", 1)[0])
    return keys


LOOKUP = lookup_keys(ARGS.profiles) if WAYCLASS else {"way": set(), "node": set()}
NODE_KEYS = LOOKUP["node"] - {"highway"}  # a node only matters for blocking with one of these (barrier, access …)

# --- smoothing (--smooth, owner 2026-10-20 / 21): OSM paths are polylines with hard corners; rounded bends read like
# a real trail. Every bend of >= SMOOTH_ANGLE becomes a short curve that stays within SMOOTH_MAX_DEV of the OSM line.
# Way ends and junction nodes (shared with any other highway) are kept exact, so the network stays connected and a
# way smooths the same in every cell (it is smoothed whole, before any clipping). Small streets (STREETS) are smoothed
# too, but only in format 2 (--tiles), where the map draws them from the overlay "streets" layer; tertiary and bigger
# roads stay in the base map, unsmoothed - their shared nodes with a small street are junctions, so both still meet.
# Route bands use the same geometry as the way below them.
SMOOTH_WAYS = LOW_WAYS | {"cycleway", "steps", "via_ferrata", "pedestrian"}
SMOOTH_ANGLE = ARGS.smooth_angle   # degrees of turn (default 15; was 20 until 2026-10-21)
SMOOTH_MAX_DEV = ARGS.smooth_dev   # metres from the OSM line at the bend's corner (default 3; was 1.5)
SMOOTH_MAX_CUT = 20.0  # metres along each leg at most
JUNCTIONS = set()      # node ids used by more than one highway way (filled by RelationPass with --smooth)

# --- format 2: small streets (tile layer "streets", owner 2026-10-21) ----------------------------------------------
# OpenMapTiles classes "minor" (residential, living_street, unclassified, road) and "service" come from the overlay at
# z13-14 (smoothed with --smooth); tools/trim_base.sh drops them from the base map's "transportation" layer there (the
# street names in "transportation_name" stay in the base map). Attributes use the OpenMapTiles names, so the street
# layers of the style keep their filters: class, brunnel, service, access ("no" = access no / private), oneway, foot,
# bicycle. Driveways / parking aisles only at z14 (busy and tiny at z13).
STREETS = {"residential": "minor", "living_street": "minor", "unclassified": "minor", "road": "minor",
           "service": "service"}
MINOR_SERVICE = {"driveway", "parking_aisle", "drive-through"}
SMOOTH_STREETS = DO_SMOOTH and TILES is not None


def round_corners(coords):
    """Replace each bend >= SMOOTH_ANGLE by a quadratic Bezier between points on both legs. The curve's farthest
    point from the corner is d*cos(a/2)/2 (a = inner angle), so d = 2*SMOOTH_MAX_DEV/cos(a/2) keeps it within
    SMOOTH_MAX_DEV; d is also at most 45 % of each leg, so neighbouring curves never overlap. Ends stay exact."""
    if len(coords) < 3:
        return list(coords)
    kx = 111320.0 * math.cos(math.radians(coords[0][1]))
    ky = 110540.0
    pts = [(x * kx, y * ky) for x, y in coords]
    out = [coords[0]]
    for i in range(1, len(pts) - 1):
        (px, py), (vx, vy), (nx, ny) = pts[i - 1], pts[i], pts[i + 1]
        l1 = math.hypot(px - vx, py - vy)
        l2 = math.hypot(nx - vx, ny - vy)
        if l1 < 1e-6 or l2 < 1e-6:
            continue
        u1 = ((px - vx) / l1, (py - vy) / l1)
        u2 = ((nx - vx) / l2, (ny - vy) / l2)
        inner = math.acos(max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1])))  # 180 deg = straight on
        turn = 180.0 - math.degrees(inner)
        if turn < SMOOTH_ANGLE:
            out.append(coords[i])
            continue
        d = min(2 * SMOOTH_MAX_DEV / max(math.cos(inner / 2), 1e-3), 0.45 * l1, 0.45 * l2, SMOOTH_MAX_CUT)
        ax, ay = vx + u1[0] * d, vy + u1[1] * d
        bx, by = vx + u2[0] * d, vy + u2[1] * d
        n = max(2, int(math.ceil(turn / 15.0)))
        for k in range(n + 1):
            t = k / n
            cx = (1 - t) ** 2 * ax + 2 * t * (1 - t) * vx + t * t * bx
            cy = (1 - t) ** 2 * ay + 2 * t * (1 - t) * vy + t * t * by
            out.append((cx / kx, cy / ky))
    out.append(coords[-1])
    return out


def smooth_line(coords, node_ids):
    """Split at junction nodes, simplify each piece (~0.5 m, ends kept), round its bends, join again."""
    cuts = [0] + [i for i in range(1, len(coords) - 1) if node_ids[i] in JUNCTIONS] + [len(coords) - 1]
    out = []
    for a, b in zip(cuts, cuts[1:]):
        piece = coords[a:b + 1]
        if len(piece) > 2:
            piece = list(LineString(piece).simplify(FINE_SIMPLIFY).coords)
        piece = round_corners(piece)
        out.extend(piece if not out else piece[1:])
    return LineString(out)


def street_attrs(tags, hw):
    """OpenMapTiles-like attributes of a "streets" tile feature (only what the style reads)."""
    a = {"class": STREETS[hw]}
    if tags.get("bridge", "no") != "no":
        a["brunnel"] = "bridge"
    elif tags.get("tunnel", "no") != "no":
        a["brunnel"] = "tunnel"
    if tags.get("service"):
        a["service"] = tags.get("service")
    if tags.get("access") in ("no", "private"):
        a["access"] = "no"
    ow = ONEWAY.get(tags.get("oneway", ""))
    if ow:
        a["oneway"] = ow
    for key in ("foot", "bicycle"):
        if tags.get(key):
            a[key] = tags.get(key)
    return a


def cycle_sides(tags):
    """Cycle infrastructure per side from cycleway / cycleway:both / :left / :right -> (left, right) codes or None."""
    both = CYCLE_INFRA.get(tags.get("cycleway:both") or tags.get("cycleway") or "")
    sides = []
    for side in ("left", "right"):
        raw = tags.get(f"cycleway:{side}")
        sides.append(CYCLE_INFRA.get(raw) if raw else both)
    return sides


def tile_extra(tags, hw):
    """True for a way that is in the tile layer "paths" only in format 2 (not a path / track drawn as a line)."""
    if hw in EXTRA_WAYS or "via_ferrata_scale" in tags:
        return True
    if hw in LOW_WAYS:  # sidewalks / crossings: left out of paths.geojson, but green ways need them
        return tags.get("area") != "yes"
    return hw in ROADS and (any(cycle_sides(tags)) or "oneway:bicycle" in tags)


def tile_attrs(tags, hw):
    """Format-2 attributes added to a "paths" tile feature (short keys, only when they say something)."""
    a = {}
    if hw not in ("path", "track"):
        a["sub"] = hw  # footway, bridleway, cycleway, steps, pedestrian, via_ferrata, or a road class
    if tags.get("tunnel", "no") != "no":
        a["tn"] = 1
    if tags.get("area") == "yes":
        a["ar"] = 1  # outline of a pedestrian square: not drawn as a street-like lane or a green way
    if tags.get("access") in ("no", "private"):
        a["ac"] = 1  # like OpenMapTiles' access=no
    for key in ("foot", "bicycle"):
        if tags.get(key):
            a[key] = tags.get(key)
    if hw == "cycleway" and tags.get("surface", "").split(";")[0] in UNPAVED:
        a["uv"] = 1
    ow = ONEWAY.get(tags.get("oneway", ""))
    if ow:
        a["ow"] = ow
    owb = {"no": 0, "yes": 1, "-1": -1}.get(tags.get("oneway:bicycle", ""))
    if owb is not None:
        a["owb"] = owb
    left, right = cycle_sides(tags)
    if left:
        a["cwl"] = left
    if right:
        a["cwr"] = right
    mtb = tags.get("mtb:scale", "")[:1]
    if mtb.isdigit() and mtb != "0":
        a["mtb"] = int(mtb)  # MTB difficulty: bike green ways skip hard trails (make_style.py green_ways)
    if hw == "via_ferrata" or "via_ferrata_scale" in tags:
        vf = tags.get("via_ferrata_scale", "")[:1]
        a["vf"] = int(vf) if vf.isdigit() else 0
    return a


def net_rank(network):
    """OSM route network -> importance: 4 international (icn/iwn/imtb), 3 national, 2 regional, 1 local, 0 unknown."""
    n = (network or "").strip().lower()
    if not n:
        return 0
    return {"i": 4, "n": 3, "r": 2, "l": 1}.get(n[0], 0)

class RelationPass(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.way_routes = {}  # way id -> list of (kind, ref, name)
        self.way_hikes = {}   # way id -> list of (ref, label, network) of hiking relations
        self.way_rtags = {}   # --wayclass: way id -> {route pseudo-tag: yes | proposed}
        self.node_refs = array("q")  # --smooth: node ids of all highway ways (junctions = ids seen twice)

    def way(self, w):
        if DO_SMOOTH and "highway" in w.tags:
            self.node_refs.extend(n.ref for n in w.nodes)

    def junctions(self):
        if not self.node_refs:
            return set()
        import numpy as np
        ids, counts = np.unique(np.frombuffer(self.node_refs, dtype=np.int64), return_counts=True)
        return set(ids[counts > 1].tolist())

    def relation(self, r):
        if r.tags.get("type") != "route":
            return
        if WAYCLASS and r.tags.get("route") in ROUTER_ROUTES:
            key = f"route_{r.tags.get('route')}_{r.tags.get('network', '')}"
            if key in LOOKUP["way"]:
                val = "proposed" if r.tags.get("state") == "proposed" else "yes"
                for m in r.members:
                    if m.type == "w":
                        cur = self.way_rtags.setdefault(m.ref, {})
                        if cur.get(key) != "yes":
                            cur[key] = val
        kind = ROUTE_KIND.get(r.tags.get("route", ""))
        if not kind or r.tags.get("state") == "proposed":
            return
        ref = r.tags.get("ref", "")
        name = r.tags.get("name", "")
        rank = net_rank(r.tags.get("network", ""))
        hike = None
        if kind == "hike":
            net = r.tags.get("network", "")
            label = route_label(r.tags) if net not in ("iwn", "nwn") else ""
            hike = (clean_ref(ref), label, net)
        for m in r.members:
            if m.type == "w":
                self.way_routes.setdefault(m.ref, []).append((kind, ref, name, rank))
                if hike:
                    self.way_hikes.setdefault(m.ref, []).append(hike)


class TownIndex:
    """Built-up land, town green and open-country green of the extract (format 2 only, see TOWN_LANDUSE): one more read
    of the file with osmium's area assembly for landuse / leisure / natural / amenity areas, plus the centre of every
    building way. Geometry in a local metric plane (equirectangular at the bbox centre) – good to a few metres inside a
    cell."""

    def __init__(self, path):
        from collections import Counter
        from shapely import wkb
        from shapely.strtree import STRtree
        self.kx = 111320.0 * math.cos(math.radians((minlat + maxlat) / 2))
        self.ky = 110540.0
        pad = 0.01
        area_bbox = box(minlon - pad, minlat - pad, maxlon + pad, maxlat + pad)
        built, tgreen, green = [], [], []
        self.buildings = Counter()
        fab = osmium.geom.WKBFactory()
        fp = (osmium.FileProcessor(path).with_locations()
              .with_areas(osmium.filter.KeyFilter("landuse", "leisure", "natural", "amenity")))
        for obj in fp:
            if obj.is_area():
                tags = obj.tags
                key = next(((k, tags.get(k)) for k in ("landuse", "leisure", "natural", "amenity")
                            if (k, tags.get(k)) in TOWN_GREEN or (k, tags.get(k)) in COUNTRY_GREEN), None)
                built_area = tags.get("landuse") in TOWN_LANDUSE or tags.get("amenity") in TOWN_AMENITY
                if key is None and not built_area:
                    continue
                try:
                    g = wkb.loads(fab.create_multipolygon(obj), hex=True)
                except Exception:  # expected: broken multipolygons in OSM (osmium cannot close the ring) – skipped
                    continue
                if not g.intersects(area_bbox):
                    continue
                g = self.project(g)
                if not g.is_valid:
                    g = g.buffer(0)
                if key in COUNTRY_GREEN:
                    green.append(g)
                elif key in TOWN_GREEN:
                    tgreen.append(g)
                else:
                    built.append(g.buffer(TOWN_BUFFER_M, 4))
            elif obj.is_way() and "building" in obj.tags and obj.is_closed():
                try:
                    pts = [(n.lon, n.lat) for n in obj.nodes]
                except osmium.InvalidLocationError:
                    continue
                lon = sum(p[0] for p in pts) / len(pts)
                lat = sum(p[1] for p in pts) / len(pts)
                if minlon - pad < lon < maxlon + pad and minlat - pad < lat < maxlat + pad:
                    self.buildings[self.cell(lon * self.kx, lat * self.ky)] += 1
        self.layers = [(STRtree(g), g) for g in (green, tgreen, built)]
        print("towns:", len(built), "built-up areas,", len(tgreen), "town green,", len(green), "open green,",
              sum(self.buildings.values()), "buildings")

    def project(self, g):
        from shapely import transform
        return transform(g, lambda c: c * [self.kx, self.ky])

    @staticmethod
    def cell(x, y):
        return int(x // BUILDING_GRID_M), int(y // BUILDING_GRID_M)

    def kind_at(self, lon, lat):
        """0 open country, 1 built-up, 2 town green."""
        from shapely.geometry import Point
        x, y = lon * self.kx, lat * self.ky
        pt = Point(x, y)
        (gt, green), (tt, tgreen), (bt, built) = self.layers
        if any(green[i].contains(pt) for i in gt.query(pt)):
            return 0
        if any(tgreen[i].contains(pt) for i in tt.query(pt)):
            return 2
        if any(built[i].contains(pt) for i in bt.query(pt)):
            return 1
        cx, cy = self.cell(x, y)
        dense = sum(self.buildings[(cx + i, cy + j)] for i in (-1, 0, 1) for j in (-1, 0, 1))
        return 1 if dense >= BUILDING_DENSE else 0

    def kind_of(self, line):
        """Kind of a way: at the middle of its line (ways are short in towns; a long trail is judged where most of it
        is)."""
        mid = line.interpolate(0.5, normalized=True)
        return self.kind_at(mid.x, mid.y)


TOWNS = None  # TownIndex, format 2 only (main)


def link_pieces(graph, coords, drawn, first_way, ends):
    """Gap fix: for every drawn path end that touches no other drawn way but a hidden sidewalk / crossing, the shortest
    chain of hidden pieces (Dijkstra, at most LINK_MAX_M) to a node of another drawn way. graph: node -> [(node, m)]
    of the hidden ways; drawn: node -> number of drawn ways through it; first_way: node -> one drawn way id through it;
    ends: [(node, way id, props, way length m)]. Returns tile features k="l" (each hidden edge drawn once)."""
    import heapq
    used, feats = set(), []
    # loose path ends: a link never ends at one (two short pieces linked to each other made small triangles)
    loose = {n for n, _, _, _ in ends if drawn.get(n, 0) == 1}
    for start, way, props, length in ends:
        if drawn.get(start, 0) != 1 or start not in graph or length < LINK_MIN_PATH_M:
            continue
        dist, prev, heap, found = {start: 0.0}, {}, [(0.0, start)], None
        while heap:
            d, v = heapq.heappop(heap)
            if d > dist.get(v, 1e18):
                continue
            if v != start and drawn.get(v, 0) and v not in loose and not (drawn[v] == 1 and first_way.get(v) == way):
                found = v
                break
            for w, dw in graph[v]:
                nd = d + dw
                if nd <= LINK_MAX_M and nd < dist.get(w, 1e18):
                    dist[w], prev[w] = nd, v
                    heapq.heappush(heap, (nd, w))
        if found is None:
            continue
        chain = [found]
        while chain[-1] != start:
            chain.append(prev[chain[-1]])
        chain.reverse()
        (x0, y0), (x1, y1) = coords[start], coords[found]
        straight = math.hypot((x1 - x0) * 111320.0 * math.cos(math.radians(y0)), (y1 - y0) * 110540.0)
        if dist[found] > LINK_MAX_DETOUR * max(straight, 0.5):
            continue  # a corner triangle / around a traffic island: the drawn ways already show the junction
        edges = [(a, b) for a, b in zip(chain, chain[1:]) if (a, b) not in used and (b, a) not in used]
        if not edges:
            continue  # the same hidden pieces already link another path end
        used.update(edges)
        feats.append({"type": "Feature", "geometry": {"type": "LineString",
                      "coordinates": [[round(coords[n][0], 6), round(coords[n][1], 6)] for n in chain]},
                      "properties": {"k": "l", **({"u": props["u"]} if props.get("u") else {})}})
    return feats


# --- trail names / route numbers (trailnames.geojson) ---------------------------------------------
TRAIL_HW = {"path", "footway", "track", "bridleway", "steps", "cycleway", "pedestrian"}
REF_RE = re.compile(r"^\d{1,3}[A-Z]?$")                       # AVS trail numbers: 25, 24A
STREETY = re.compile(r"(stra(ß|ss)e|gasse|platz|passage|\bpark\b|\d+-\d+)", re.I)
GENERIC = {"wanderweg", "verbindungsweg", "waldweg", "weg", "sentiero", "steig", "path", "trail"}
TIER1 = re.compile(r"(waal|höhenweg|promenade|panoramaweg|jakobsweg|höfeweg|steig$)", re.I)
WAYWORD = re.compile(r"(weg|steig|pfad|promenade|waal|trail|runde|route|sentiero|percorso|passeggiata|"
                     r"cammino|via )", re.I)  # else usually a farm/place name on an access track
LABEL_SIMPLIFY = 0.00012  # about 10 m, then smoothed: winding Waalwege otherwise exceed text-max-angle
REF_SIMPLIFY = 0.00008    # numbers are drawn upright at points, the line only positions them


def clean_ref(ref):
    ref = ref.replace(" ", "").upper()
    return ref if REF_RE.match(ref) else ""


def clean_name(tags):
    """German display name: name:de, else the German part of a bilingual "DE - IT" name."""
    n = (tags.get("name:de") or tags.get("name") or "").strip()
    parts = [p.strip() for p in n.split(" - ")]
    parts = [p for p in parts if p and not REF_RE.match(p.replace(" ", "").upper())]
    n = parts[0] if parts else ""
    if "=>" in n or "->" in n:  # "from => to" route descriptions, not names
        return ""
    if not n or n.lower() in GENERIC or all(REF_RE.match(p) for p in re.split(r"[\s,;/+]+", n.upper()) if p):
        return ""
    if n.lower().startswith(("lwn ", "wanderweg ", "percorso ", "weg ")) or STREETY.search(n):
        return ""
    return n[:1].upper() + n[1:]


def route_label(tags):
    return clean_name(tags)


def chaikin(coords, rounds=2):
    """Corner-cutting smoothing (keeps the end points) so curved trails take a line label."""
    for _ in range(rounds):
        out = [coords[0]]
        for (x1, y1), (x2, y2) in zip(coords, coords[1:]):
            out += [(.75 * x1 + .25 * x2, .75 * y1 + .25 * y2), (.25 * x1 + .75 * x2, .25 * y1 + .75 * y2)]
        out.append(coords[-1])
        coords = out
    return coords


def length_m(line):
    c = list(line.coords)
    k = math.cos(math.radians(c[0][1]))
    return sum(math.hypot((x2 - x1) * k, y2 - y1) for (x1, y1), (x2, y2) in zip(c, c[1:])) * 111320


class WayPass(osmium.SimpleHandler):
    def __init__(self, way_routes, way_hikes=None, way_rtags=None):
        super().__init__()
        self.way_routes = way_routes
        self.way_hikes = way_hikes or {}
        self.way_rtags = way_rtags or {}
        self.combos = {}      # --wayclass: router tag set -> combo id (one classifier request per distinct set)
        self.barriers = {}    # --wayclass: node id -> [lon, lat, router tags, set of combo ids of the ways through it]
        self._cid = None
        self.routes = []
        self.trails = []
        self.ways = []
        self.low = []
        self.paths = []
        self.tile_paths = []  # format 2: "paths" tile features z13-14 (format-1 props + tile_attrs, plus extra ways)
        self.streets = []     # format 2: "streets" tile features z13-14 (small streets, street_attrs)
        # format 2, gap fix (link_pieces): hidden sidewalk / crossing graph and the drawn ways' nodes
        self.link_graph = {}  # node -> [(node, metres)] along hidden ways
        self.link_coords = {}  # node -> (lon, lat) of hidden-way nodes
        self.drawn = {}       # node -> number of drawn ways through it
        self.first_way = {}   # node -> one drawn way through it
        self.path_ends = []   # (node, way id, tile props, length m) of drawn path ends (k "p")
        self.by_name = {}  # label -> list of raw coordinate lists
        self.by_ref = {}   # "24 · 25" -> list of raw coordinate lists

    def collect_names(self, w):
        hikes = self.way_hikes.get(w.id, [])
        hw = w.tags.get("highway", "")
        trail = hw in TRAIL_HW and w.tags.get("footway") not in ("sidewalk", "crossing")
        label = clean_name(w.tags) if trail else ""
        if trail and not label:
            # unnamed member way of a named hiking route: show the route's name
            label = next((lab for _, lab, _ in hikes if lab), "")
        refs = sorted({r for r, _, _ in hikes if r}, key=lambda r: (int(re.match(r"\d+", r).group()), r))
        if not label and not refs:
            return
        try:
            coords = [(round(n.lon, 6), round(n.lat, 6)) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(coords) < 2 or not any(minlon - .01 < x < maxlon + .01 and minlat - .01 < y < maxlat + .01
                                      for x, y in coords):
            return
        if label:
            self.by_name.setdefault(label, []).append(coords)
        if refs:
            self.by_ref.setdefault(" · ".join(refs[:3]), []).append(coords)

    def label_features(self):
        feats = []
        for t, groups in (("n", self.by_name), ("r", self.by_ref)):
            for key, lines in groups.items():
                merged = linemerge(MultiLineString(lines)).intersection(BBOX)
                parts = [g for g in getattr(merged, "geoms", [merged]) if g.geom_type == "LineString" and not g.is_empty]
                total = sum(length_m(g) for g in parts)
                for g in parts:
                    g = g.simplify(LABEL_SIMPLIFY if t == "n" else REF_SIMPLIFY)
                    if t == "n" and len(g.coords) > 2:
                        g = LineString(chaikin(list(g.coords), 3)).simplify(0.00003)  # drop dense points: rounding would zigzag
                    ln = length_m(g)
                    if ln < (60 if t == "r" else 80):
                        continue
                    props = {"t": t}
                    if t == "n":
                        if not WAYWORD.search(key):
                            tier = 3
                        elif (TIER1.search(key) and total >= 600) or total >= 2500:
                            tier = 1
                        else:
                            tier = 2 if ln >= 250 else 3
                        props.update(name=key, tier=tier, len=int(ln))
                    else:
                        props.update(ref=key)
                    feats.append({"type": "Feature", "geometry": rounded(mapping(g)), "properties": props})
        return feats

    def geom(self, w, tolerance=SIMPLIFY):
        try:
            coords = [(n.lon, n.lat) for n in w.nodes]
        except osmium.InvalidLocationError:
            return None
        if len(coords) < 2:
            return None
        line = LineString(coords)
        if not line.intersects(BBOX):
            return None
        return line.simplify(tolerance)

    def fine_geom(self, w, hw):
        """Near-exact geometry (~0.5 m) for paths, trails, ways and (with --smooth) route bands; with --smooth,
        path-like ways (and small streets in format 2) get rounded bends (round_corners)."""
        if not DO_SMOOTH or not (hw in SMOOTH_WAYS or "sac_scale" in w.tags or (SMOOTH_STREETS and hw in STREETS)):
            return self.geom(w, FINE_SIMPLIFY)
        try:
            coords = [(n.lon, n.lat) for n in w.nodes]
            ids = [n.ref for n in w.nodes]
        except osmium.InvalidLocationError:
            return None
        if len(coords) < 2 or not LineString(coords).intersects(BBOX):
            return None
        return smooth_line(coords, ids)

    def node(self, n):
        if not WAYCLASS or not any(k in n.tags for k in ("barrier", "ford")):
            return
        tags = tuple(sorted((t.k, t.v) for t in n.tags if t.k in LOOKUP["node"]))
        if not any(k in NODE_KEYS for k, _ in tags):
            return
        loc = n.location
        if not loc.valid() or not (minlon <= loc.lon <= maxlon and minlat <= loc.lat <= maxlat):
            return
        self.barriers[n.id] = [round(loc.lon, 6), round(loc.lat, 6), tags, set()]

    def combo(self, w):
        """Combo id of the way's router tag set (BRouter lookup keys + route pseudo-tags); notes barrier nodes on it."""
        tags = {t.k: t.v for t in w.tags if t.k in LOOKUP["way"]}
        tags.update(self.way_rtags.get(w.id, {}))
        key = tuple(sorted(tags.items()))
        cid = self.combos.setdefault(key, len(self.combos))
        if self.barriers:
            for n in w.nodes:
                b = self.barriers.get(n.ref)
                if b is not None:
                    b[3].add(cid)
        return cid

    def way(self, w):
        self.collect_names(w)
        self._cid = self.combo(w) if WAYCLASS and w.tags.get("highway") else None
        routes = self.way_routes.get(w.id)
        sac = SAC.get(w.tags.get("sac_scale", ""), 0)
        mtb = w.tags.get("mtb:scale", "")
        vis = VIS.get(w.tags.get("trail_visibility", ""))
        quality = self.quality(w)
        faint = vis is not None and vis >= 3
        hw = w.tags.get("highway", "")
        low = hw in LOW_WAYS and w.tags.get("footway") not in ("sidewalk", "crossing") and w.tags.get("area") != "yes"
        extra = TILES is not None and not (low or sac) and tile_extra(w.tags, hw)
        if TILES is not None and hw:
            self.note_links(w, hw)
        if TILES is not None and hw in STREETS and w.tags.get("area") != "yes":
            self.add_street(w, hw)
        if not routes and not sac and not mtb and not faint and not quality and not low and not extra:
            return
        line = self.geom(w)
        if line is None:
            return
        # towns (TownIndex): footways / paths / cycleways / steps in built-up land or town green; never tracks, trails
        town = 0
        if TOWNS is not None and hw in TOWN_WAYS and (low or extra) and sac < 2 and not faint:
            town = TOWNS.kind_of(line)
        g = rounded(mapping(line))
        fine = None
        if DO_SMOOTH:
            # route bands on the exact (smoothed) path line - a 3 m simplified band ran beside the path at z16+
            fine = rounded(mapping(self.fine_geom(w, hw)), 6)
            if routes:
                g = fine
        if routes:
            # One feature per kind; keep the most important route of that kind on this way (network rank,
            # then a named one) so major routes (e.g. Via Claudia Augusta, E5) can be highlighted.
            best = {}
            for kind, ref, name, rank in routes:
                cur = best.get(kind)
                if cur is None or (rank, bool(name)) > (cur[2], bool(cur[1])):
                    best[kind] = (ref, name, rank)
            for kind, (ref, name, rank) in best.items():
                props = {"kind": kind, "ref": ref, "name": name, "net": rank}
                if kind == "hike" and sac:
                    props["sac"] = sac  # the app greys the band above the user's T level (MapController.setSacLimit)
                self.routes.append({"type": "Feature", "geometry": g, "properties": props, "cid": self._cid})
        # Trails and way quality are drawn right over the base map's own path / track lines, so they keep a
        # near-exact geometry (~0.5 m); a 3 m simplification shows as a second line beside the path at z17+.
        if fine is None and (sac or mtb or faint or quality):
            fine = rounded(mapping(self.fine_geom(w, hw)))
        if sac or mtb or faint:
            props = {"sac": sac}
            if mtb:
                props["mtb"] = mtb[:1]
            if vis is not None:
                props["vis"] = vis
            self.trails.append({"type": "Feature", "geometry": fine, "properties": props})
        if low or sac:
            # lowways.geojson: every path / track, coarse (~8 m), only drawn zoomed out (< z13) – own small file
            # so the detailed trails source stays light
            self.low.append({"type": "Feature", "geometry": rounded(mapping(self.geom(w, LOW_SIMPLIFY))),
                             "properties": {"k": "t"} if hw == "track" else ({"u": town} if town else {})})
        if quality:
            self.ways.append({"type": "Feature", "geometry": fine, "properties": quality})
        if low or sac:
            # paths.geojson: every path / track with exact geometry – the map draws these instead of the base map's
            # lines from z13 (no solid line under a dotted trail); tt / pv set the track drawing (surface)
            pp = {"k": "t" if hw == "track" else "p", "sac": sac}
            if vis is not None:
                pp["vis"] = vis
            if hw == "steps":
                pp["st"] = 1
            if hw == "track":
                tt = TRACKTYPE.get(w.tags.get("tracktype", ""))
                if tt:
                    pp["tt"] = tt
                if SURF.get(w.tags.get("surface", "").split(";")[0]) == 1:
                    pp["pv"] = 1
            geom = fine or rounded(mapping(self.fine_geom(w, hw)))
            self.paths.append({"type": "Feature", "geometry": geom, "properties": pp})
            if TILES is not None:
                tp = {**pp, **tile_attrs(w.tags, hw), **({"u": town} if town else {})}
                self.tile_paths.append({"type": "Feature", "geometry": geom, "properties": tp, "cid": self._cid})
                if pp["k"] == "p" and len(w.nodes) > 1:
                    n = length_m(line)
                    self.path_ends += [(w.nodes[0].ref, w.id, tp, n), (w.nodes[-1].ref, w.id, tp, n)]
        elif extra:
            self.tile_paths.append({"type": "Feature", "geometry": fine or rounded(mapping(self.fine_geom(w, hw))),
                                    "properties": {"k": "x", **tile_attrs(w.tags, hw), **({"u": town} if town else {})},
                                    "cid": self._cid})

    def note_links(self, w, hw):
        """Gap fix bookkeeping (link_pieces): nodes of every drawn way; the graph of hidden sidewalks / crossings."""
        if w.tags.get("area") == "yes":
            return
        if hw in LOW_WAYS and w.tags.get("footway") in ("sidewalk", "crossing"):
            try:
                pts = [(n.ref, n.lon, n.lat) for n in w.nodes]
            except osmium.InvalidLocationError:
                return
            if not any(minlon - .01 < x < maxlon + .01 and minlat - .01 < y < maxlat + .01 for _, x, y in pts):
                return
            for (a, xa, ya), (b, xb, yb) in zip(pts, pts[1:]):
                d = math.hypot((xb - xa) * 111320.0 * math.cos(math.radians(ya)), (yb - ya) * 110540.0)
                self.link_graph.setdefault(a, []).append((b, d))
                self.link_graph.setdefault(b, []).append((a, d))
                self.link_coords[a], self.link_coords[b] = (xa, ya), (xb, yb)
        elif hw in DRAWN_WAYS:
            for ref in {n.ref for n in w.nodes}:
                self.drawn[ref] = self.drawn.get(ref, 0) + 1
                self.first_way.setdefault(ref, w.id)

    def add_street(self, w, hw):
        line = self.fine_geom(w, hw)
        if line is None:
            return
        zooms = (14, 14) if hw == "service" and w.tags.get("service") in MINOR_SERVICE else HIGH_ZOOMS
        props = street_attrs(w.tags, hw)
        if TOWNS is not None and TOWNS.kind_of(line) == 1:
            props["u"] = 1  # town street: white lane (make_style.py roads / lanes)
        self.streets.append({"type": "Feature", "geometry": rounded(mapping(line), 6 if SMOOTH_STREETS else 5),
                             "properties": props, "zooms": zooms, "cid": self._cid})

    @staticmethod
    def quality(w):
        """Way-quality props for ways.geojson, or None if nothing the map would show differently."""
        hw = w.tags.get("highway", "")
        k = WAY_KIND.get(hw)
        if not k or w.tags.get("footway") in ("sidewalk", "crossing"):
            return None
        tt = TRACKTYPE.get(w.tags.get("tracktype", "")) if k == "t" else None
        surf = SURF.get(w.tags.get("surface", "").split(";")[0])
        smooth = SMOOTH.get(w.tags.get("smoothness", ""))
        if not tt and not (surf or 0) >= 3 and not (smooth or 0) >= 3:
            return None
        props = {"k": k}
        for key, val in (("tt", tt), ("surf", surf), ("smooth", smooth)):
            if val is not None:
                props[key] = val
        return props


def rounded(geom, digits=5):
    """5 decimals (~1 m); smoothed lines keep 6 (0.1 m), else rounding makes the curves zigzag at z17+."""
    geom = dict(geom)
    geom["coordinates"] = [[round(x, digits), round(y, digits)] for x, y in geom["coordinates"]]
    return geom


def merged_routes(routes):
    """Route bands are drawn transparent: where two pieces meet the band would get darker. So every route kind
    becomes as few continuous lines as possible. The original pieces of major routes (net >= 2) follow as
    "majoronly" copies for the pink highlight and the route name (make_style.py route_bands)."""
    out, by_kind = [], {}
    for f in routes:
        # hiking pieces per SAC grade (where graded): the app greys the band above the user's T level
        # (MapController.setSacLimit); a grade change is a seam (slightly darker where the transparent pieces meet)
        # hx 1: the hiking profile excludes the way at any T level (via ferrata, trail_visibility=no, private …;
        # --wayclass only) – greyed like a trail above the user's T level
        key = (f["properties"]["kind"], f["properties"].get("sac", 0), f["properties"].get("hx", 0))
        by_kind.setdefault(key, []).append(shape_of(f["geometry"]))
    for (kind, sac, hx), lines in by_kind.items():
        u = unary_union(lines)
        m = linemerge(u) if u.geom_type == "MultiLineString" else u  # a single piece: nothing to merge
        for g in (m.geoms if hasattr(m, "geoms") else [m]):
            props = {"kind": kind, **({"sac": sac} if sac else {}), **({"hx": 1} if hx else {})}
            out.append({"type": "Feature", "geometry": rounded(mapping(g)), "properties": props})
    for f in routes:
        if f["properties"].get("net", 0) >= 2:
            out.append({"type": "Feature", "geometry": f["geometry"], "properties": {**f["properties"], "majoronly": 1}})
    return out


def shape_of(geom):
    return shape(geom)


def compact(props):
    """Tile attributes: drop empty strings (e.g. route ref / name) – the style reads them with coalesce."""
    return {k: v for k, v in props.items() if v != ""}


def classify(wp, tiles_dir):
    """Run the router classifier (tools/routing/wayclass) once over every distinct tag set and barrier node; put the
    classes on the "paths" / "streets" features and return the "barriers" features. Fails loudly: a format-2 package
    without classes would silently show the old tag rules."""
    req = []
    for key, cid in wp.combos.items():
        req.append("\t".join(["W", str(cid)] + [f"{k}={v}" for k, v in key]))
    nodes = [(nid, b) for nid, b in wp.barriers.items() if b[3]]  # only nodes on a highway way
    for nid, (lon, lat, tags, cids) in nodes:
        req.append("\t".join(["N", str(nid), ",".join(map(str, sorted(cids)))] + [f"{k}={v}" for k, v in tags]))
    text = "".join(r.replace("\r", " ").replace("\n", " ") + "\n" for r in req)
    res = subprocess.run([ARGS.java, "-Xmx512m", "-jar", ARGS.wayclass, "--profiles", ARGS.profiles],
                         input=text.encode("utf-8"), capture_output=True, check=False)
    if res.returncode != 0:
        sys.exit(f"wayclass failed ({res.returncode}): {res.stderr.decode('utf-8', 'replace')[-2000:]}")
    classes, blocked, phash = {}, {}, None
    for line in res.stdout.decode("utf-8").splitlines():
        c = line.split("\t")
        if c[0] == "H":
            phash = c[1]
        elif c[0] == "W":
            classes[int(c[1])] = c[2:7]
        elif c[0] == "N":
            blocked[int(c[1])] = int(c[2])
    missing = len(wp.combos) - len(classes)
    if phash is None or missing:
        sys.exit(f"wayclass answered incompletely (hash {phash}, {missing} tag sets without a class)")
    for f in wp.routes:
        cid = f.pop("cid", None)
        if cid is not None and f["properties"]["kind"] == "hike" and classes[cid][0] in ("2", "3"):
            f["properties"]["hx"] = 1
    for feats in (wp.tile_paths, wp.streets):
        for f in feats:
            cid = f.pop("cid", None)
            if cid is None:
                continue
            ch, ct, cf, cm, cr = classes[cid]
            f["properties"].update({"ch": int(ch), "ct": int(ct), "cf": int(cf), "cm": int(cm)})
            if cr != "0":
                f["properties"]["cr"] = int(cr)
    barriers = [{"type": "Feature", "geometry": {"type": "Point", "coordinates": [b[0], b[1]]},
                 "properties": {"b": blocked[nid]}} for nid, b in nodes if blocked.get(nid)]
    with open(os.path.join(tiles_dir, "wayclass.json"), "w") as f:
        json.dump({"profiles": phash, "tag_sets": len(classes), "barrier_nodes": len(nodes),
                   "blocking_barriers": len(barriers)}, f)
    print("wayclass: profiles", phash, "|", len(classes), "tag sets,", len(nodes), "barrier nodes,",
          len(barriers), "blocking")
    return barriers


def write_tiles(tiles_dir, layers, low, tile_paths, streets, barriers=None):
    """Format 2: one line-delimited GeoJSON file per tile layer, input of tools/build_overlays.sh. "paths" holds the
    former lowways as a coarse copy for z11-12 and the exact ways for z13-14 (per-feature tippecanoe zoom range)."""
    import os
    os.makedirs(tiles_dir, exist_ok=True)

    def dump(f, feat, zooms=None):
        out = {"type": "Feature", "properties": compact(feat["properties"]), "geometry": feat["geometry"]}
        if zooms:
            out["tippecanoe"] = {"minzoom": zooms[0], "maxzoom": zooms[1]}
        f.write(json.dumps(out, separators=(",", ":"), ensure_ascii=False) + "\n")

    for name, feats in layers:
        with open(f"{tiles_dir}/{name}.ndjson", "w") as f:
            for feat in feats:
                dump(f, feat)
    with open(f"{tiles_dir}/paths.ndjson", "w") as f:
        for feat in low:
            lp = {"k": feat["properties"].get("k", "p")}
            if feat["properties"].get("u"):
                lp["u"] = feat["properties"]["u"]  # town flag: the style leaves town paths out when zoomed out
            dump(f, {"geometry": feat["geometry"], "properties": lp}, LOW_ZOOMS)
        for feat in tile_paths:
            dump(f, feat, HIGH_ZOOMS)
    with open(f"{tiles_dir}/streets.ndjson", "w") as f:
        for feat in streets:
            dump(f, feat, feat["zooms"])
    if barriers is not None:
        with open(f"{tiles_dir}/barriers.ndjson", "w") as f:
            for feat in barriers:
                dump(f, feat, BARRIER_ZOOMS)
    print("tiles: paths", len(low), "+", len(tile_paths), ", streets", len(streets), "->", tiles_dir)


def main():
    rp = RelationPass()
    rp.apply_file(pbf)
    JUNCTIONS.update(rp.junctions())
    rp.node_refs = None
    global TOWNS
    if TILES is not None:
        TOWNS = TownIndex(pbf)
    wp = WayPass(rp.way_routes, rp.way_hikes, rp.way_rtags)
    wp.apply_file(pbf, locations=True)
    barriers = None
    if TILES is not None:
        os.makedirs(TILES, exist_ok=True)
        links = link_pieces(wp.link_graph, wp.link_coords, wp.drawn, wp.first_way, wp.path_ends)
        print("gap fix:", len(links), "link pieces")
        wp.tile_paths.extend(links)
        barriers = classify(wp, TILES) if WAYCLASS else None
    for f in wp.routes:
        f.pop("cid", None)
    layers = [("routes", merged_routes(wp.routes)), ("trails", wp.trails), ("ways", wp.ways),
              ("trailnames", wp.label_features())]
    if not ARGS.no_geojson:
        for name, feats in layers + [("lowways", wp.low), ("paths", wp.paths)]:
            with open(f"{out_dir}/{name}.geojson", "w") as f:
                json.dump({"type": "FeatureCollection", "features": feats}, f, separators=(",", ":"))
            print(name, len(feats))
    if TILES is not None:
        write_tiles(TILES, layers, wp.low, wp.tile_paths, wp.streets, barriers)


main()
