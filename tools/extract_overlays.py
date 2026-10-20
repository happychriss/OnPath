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
per vector-tile layer (paths, trails, ways, routes, trailnames .ndjson, with per-feature "tippecanoe" zoom ranges) for
tools/build_overlays.sh, which turns them into ONE overlays.pmtiles. The tile "paths" layer replaces lowways (coarse
copy at z11-12) and carries what the style needs to drop the base map's path / track lines (schema: see
docs/map-pipeline.md "Overlays as vector tiles"). --no-geojson skips the format-1 files.

Usage: python3 extract_overlays.py input.osm.pbf OUT_DIR minlon minlat maxlon maxlat [--tiles DIR] [--no-geojson]
"""
import argparse
import json
from array import array
import math
import re
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
                 help="round the bends of paths / tracks / trails (and route bands on them); default for format 2")
ARGS = _ap.parse_args()
pbf, out_dir = ARGS.pbf, ARGS.out_dir
minlon, minlat, maxlon, maxlat = ARGS.bbox
TILES = ARGS.tiles
SMOOTH = ARGS.smooth
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

# --- smoothing (--smooth, owner 2026-10-20): OSM paths are polylines with hard corners; rounded bends read like a
# real trail. Every bend of >= SMOOTH_ANGLE becomes a short curve that stays within SMOOTH_MAX_DEV of the OSM line.
# Way ends and junction nodes (shared with any other highway) are kept exact, so the network stays connected and a
# way smooths the same in every cell (it is smoothed whole, before any clipping). Streets are left alone (the base
# map draws them unsmoothed); route bands use the same geometry as the path below them.
SMOOTH_WAYS = LOW_WAYS | {"cycleway", "steps", "via_ferrata", "pedestrian"}
SMOOTH_ANGLE = 20.0    # degrees of turn
SMOOTH_MAX_DEV = 1.5   # metres from the OSM line (at the bend's corner)
SMOOTH_MAX_CUT = 20.0  # metres along each leg at most
JUNCTIONS = set()      # node ids used by more than one highway way (filled by RelationPass when SMOOTH)


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
        self.node_refs = array("q")  # --smooth: node ids of all highway ways (junctions = ids seen twice)

    def way(self, w):
        if SMOOTH and "highway" in w.tags:
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
    def __init__(self, way_routes, way_hikes=None):
        super().__init__()
        self.way_routes = way_routes
        self.way_hikes = way_hikes or {}
        self.routes = []
        self.trails = []
        self.ways = []
        self.low = []
        self.paths = []
        self.tile_paths = []  # format 2: "paths" tile features z13-14 (format-1 props + tile_attrs, plus extra ways)
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
        path-like ways (not streets) get rounded bends (round_corners)."""
        if not SMOOTH or not (hw in SMOOTH_WAYS or "sac_scale" in w.tags):
            return self.geom(w, FINE_SIMPLIFY)
        try:
            coords = [(n.lon, n.lat) for n in w.nodes]
            ids = [n.ref for n in w.nodes]
        except osmium.InvalidLocationError:
            return None
        if len(coords) < 2 or not LineString(coords).intersects(BBOX):
            return None
        return smooth_line(coords, ids)

    def way(self, w):
        self.collect_names(w)
        routes = self.way_routes.get(w.id)
        sac = SAC.get(w.tags.get("sac_scale", ""), 0)
        mtb = w.tags.get("mtb:scale", "")
        vis = VIS.get(w.tags.get("trail_visibility", ""))
        quality = self.quality(w)
        faint = vis is not None and vis >= 3
        hw = w.tags.get("highway", "")
        low = hw in LOW_WAYS and w.tags.get("footway") not in ("sidewalk", "crossing") and w.tags.get("area") != "yes"
        extra = TILES is not None and not (low or sac) and tile_extra(w.tags, hw)
        if not routes and not sac and not mtb and not faint and not quality and not low and not extra:
            return
        line = self.geom(w)
        if line is None:
            return
        g = rounded(mapping(line))
        fine = None
        if SMOOTH:
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
                self.routes.append({"type": "Feature", "geometry": g,
                                    "properties": {"kind": kind, "ref": ref, "name": name, "net": rank}})
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
                             "properties": {"k": "t"} if hw == "track" else {}})
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
                self.tile_paths.append({"type": "Feature", "geometry": geom, "properties": {**pp, **tile_attrs(w.tags, hw)}})
        elif extra:
            self.tile_paths.append({"type": "Feature", "geometry": fine or rounded(mapping(self.fine_geom(w, hw))),
                                    "properties": {"k": "x", **tile_attrs(w.tags, hw)}})

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
        by_kind.setdefault(f["properties"]["kind"], []).append(shape_of(f["geometry"]))
    for kind, lines in by_kind.items():
        m = linemerge(unary_union(lines))
        for g in (m.geoms if hasattr(m, "geoms") else [m]):
            out.append({"type": "Feature", "geometry": rounded(mapping(g)), "properties": {"kind": kind}})
    for f in routes:
        if f["properties"].get("net", 0) >= 2:
            out.append({"type": "Feature", "geometry": f["geometry"], "properties": {**f["properties"], "majoronly": 1}})
    return out


def shape_of(geom):
    return shape(geom)


def compact(props):
    """Tile attributes: drop empty strings (e.g. route ref / name) – the style reads them with coalesce."""
    return {k: v for k, v in props.items() if v != ""}


def write_tiles(tiles_dir, layers, low, tile_paths):
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
            dump(f, {"geometry": feat["geometry"], "properties": {"k": feat["properties"].get("k", "p")}}, LOW_ZOOMS)
        for feat in tile_paths:
            dump(f, feat, HIGH_ZOOMS)
    print("tiles: paths", len(low), "+", len(tile_paths), "->", tiles_dir)


def main():
    rp = RelationPass()
    rp.apply_file(pbf)
    JUNCTIONS.update(rp.junctions())
    rp.node_refs = None
    wp = WayPass(rp.way_routes, rp.way_hikes)
    wp.apply_file(pbf, locations=True)
    layers = [("routes", merged_routes(wp.routes)), ("trails", wp.trails), ("ways", wp.ways),
              ("trailnames", wp.label_features())]
    if not ARGS.no_geojson:
        for name, feats in layers + [("lowways", wp.low), ("paths", wp.paths)]:
            with open(f"{out_dir}/{name}.geojson", "w") as f:
                json.dump({"type": "FeatureCollection", "features": feats}, f, separators=(",", ":"))
            print(name, len(feats))
    if TILES is not None:
        write_tiles(TILES, layers, wp.low, wp.tile_paths)


main()
