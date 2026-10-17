#!/usr/bin/env python3
"""Extract trail and route-network overlays from an OSM .pbf into small GeoJSON files.

Output (in OUT_DIR):
  routes.geojson  - official hiking / bicycle / mtb route relations (line geometries)
  trails.geojson  - paths with SAC scale (hiking difficulty) and/or mtb:scale

Usage: python3 extract_overlays.py input.osm.pbf OUT_DIR minlon minlat maxlon maxlat
"""
import json
import sys

import osmium
from shapely.geometry import LineString, box, mapping

pbf, out_dir = sys.argv[1], sys.argv[2]
minlon, minlat, maxlon, maxlat = map(float, sys.argv[3:7])
BBOX = box(minlon, minlat, maxlon, maxlat)
SIMPLIFY = 0.00003  # about 3 m

SAC = {"hiking": 1, "mountain_hiking": 2, "demanding_mountain_hiking": 3,
       "alpine_hiking": 4, "demanding_alpine_hiking": 5, "difficult_alpine_hiking": 6}
ROUTE_KIND = {"hiking": "hike", "foot": "hike", "bicycle": "bike", "mtb": "mtb"}


class RelationPass(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.way_routes = {}  # way id -> list of (kind, ref, name)

    def relation(self, r):
        if r.tags.get("type") != "route":
            return
        kind = ROUTE_KIND.get(r.tags.get("route", ""))
        if not kind or r.tags.get("state") == "proposed":
            return
        ref = r.tags.get("ref", "")
        name = r.tags.get("name", "")
        for m in r.members:
            if m.type == "w":
                self.way_routes.setdefault(m.ref, []).append((kind, ref, name))


class WayPass(osmium.SimpleHandler):
    def __init__(self, way_routes):
        super().__init__()
        self.way_routes = way_routes
        self.routes = []
        self.trails = []

    def geom(self, w):
        try:
            coords = [(n.lon, n.lat) for n in w.nodes]
        except osmium.InvalidLocationError:
            return None
        if len(coords) < 2:
            return None
        line = LineString(coords)
        if not line.intersects(BBOX):
            return None
        return line.simplify(SIMPLIFY)

    def way(self, w):
        routes = self.way_routes.get(w.id)
        sac = SAC.get(w.tags.get("sac_scale", ""), 0)
        mtb = w.tags.get("mtb:scale", "")
        if not routes and not sac and not mtb:
            return
        line = self.geom(w)
        if line is None:
            return
        g = rounded(mapping(line))
        if routes:
            seen = set()
            for kind, ref, name in routes:
                if kind in seen:
                    continue
                seen.add(kind)
                self.routes.append({"type": "Feature", "geometry": g,
                                    "properties": {"kind": kind, "ref": ref, "name": name}})
        if sac or mtb:
            props = {"sac": sac}
            if mtb:
                props["mtb"] = mtb[:1]
            self.trails.append({"type": "Feature", "geometry": g, "properties": props})


def rounded(geom):
    geom = dict(geom)
    geom["coordinates"] = [[round(x, 5), round(y, 5)] for x, y in geom["coordinates"]]
    return geom


def main():
    rp = RelationPass()
    rp.apply_file(pbf)
    wp = WayPass(rp.way_routes)
    wp.apply_file(pbf, locations=True)
    for name, feats in (("routes", wp.routes), ("trails", wp.trails)):
        with open(f"{out_dir}/{name}.geojson", "w") as f:
            json.dump({"type": "FeatureCollection", "features": feats}, f, separators=(",", ":"))
        print(name, len(feats))


main()
