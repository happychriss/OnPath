#!/usr/bin/env python3
"""Extract trail and route-network overlays from an OSM .pbf into small GeoJSON files.

Output (in OUT_DIR):
  routes.geojson  - official hiking / bicycle / mtb route relations (line geometries)
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

Usage: python3 extract_overlays.py input.osm.pbf OUT_DIR minlon minlat maxlon maxlat
"""
import json
import math
import re
import sys

import osmium
from shapely.geometry import LineString, MultiLineString, box, mapping
from shapely.ops import linemerge

pbf, out_dir = sys.argv[1], sys.argv[2]
minlon, minlat, maxlon, maxlat = map(float, sys.argv[3:7])
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

    def way(self, w):
        self.collect_names(w)
        routes = self.way_routes.get(w.id)
        sac = SAC.get(w.tags.get("sac_scale", ""), 0)
        mtb = w.tags.get("mtb:scale", "")
        vis = VIS.get(w.tags.get("trail_visibility", ""))
        quality = self.quality(w)
        faint = vis is not None and vis >= 3
        if not routes and not sac and not mtb and not faint and not quality:
            return
        line = self.geom(w)
        if line is None:
            return
        g = rounded(mapping(line))
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
        fine = rounded(mapping(self.geom(w, FINE_SIMPLIFY))) if (sac or mtb or faint or quality) else None
        if sac or mtb or faint:
            props = {"sac": sac}
            if mtb:
                props["mtb"] = mtb[:1]
            if vis is not None:
                props["vis"] = vis
            self.trails.append({"type": "Feature", "geometry": fine, "properties": props})
        if quality:
            self.ways.append({"type": "Feature", "geometry": fine, "properties": quality})

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


def rounded(geom):
    geom = dict(geom)
    geom["coordinates"] = [[round(x, 5), round(y, 5)] for x, y in geom["coordinates"]]
    return geom


def main():
    rp = RelationPass()
    rp.apply_file(pbf)
    wp = WayPass(rp.way_routes, rp.way_hikes)
    wp.apply_file(pbf, locations=True)
    for name, feats in (("routes", wp.routes), ("trails", wp.trails), ("ways", wp.ways), ("trailnames", wp.label_features())):
        with open(f"{out_dir}/{name}.geojson", "w") as f:
            json.dump({"type": "FeatureCollection", "features": feats}, f, separators=(",", ":"))
        print(name, len(feats))


main()
