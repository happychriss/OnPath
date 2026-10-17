#!/usr/bin/env python3
"""Extract outdoor POIs from an OSM .pbf into a compact GeoJSON point file (F-MAP-09).

Output: OUT_DIR/pois.geojson, one Point per POI (nodes as is; ways and multipolygons as centroid).
Properties (only when present): id ("n123"/"w123"/"r123"), kind, name, ele, opening_hours, phone,
website, wikipedia, cuisine.

Usage: python3 extract_pois.py input.osm.pbf OUT_DIR minlon minlat maxlon maxlat
"""
import json
import sys
from collections import Counter

import osmium
from shapely.geometry import LineString, Polygon

pbf, out_dir = sys.argv[1], sys.argv[2]
minlon, minlat, maxlon, maxlat = map(float, sys.argv[3:7])

YES = ("yes", "true", "1")


def classify(t):
    """Return the short kind key for an OSM tag set, or None."""
    g = t.get
    am, tour, nat, hist = g("amenity"), g("tourism"), g("natural"), g("historic")
    if tour == "viewpoint":
        return "viewpoint"
    if tour == "alpine_hut":
        return "hut"
    if tour == "wilderness_hut":
        return "bivouac"
    if am == "restaurant":
        return "restaurant"
    if am in ("cafe", "ice_cream"):
        return "cafe"
    if am in ("bar", "pub", "biergarten"):
        return "bar"
    if am == "fast_food":
        return "fast_food"
    if am in ("drinking_water", "water_point") or (g("man_made") == "water_tap" and g("drinking_water") in YES) \
            or (am == "fountain" and g("drinking_water") in YES):
        return "water"
    if nat == "spring":
        return "spring"
    if am == "shelter":
        return "shelter"
    if tour == "picnic_site":
        return "picnic"
    if am == "toilets":
        return "toilets"
    if am == "parking" and g("access") not in ("private", "no", "customers") and g("parking") != "underground":
        return "parking"
    if g("highway") == "bus_stop":
        return "bus"
    if g("railway") in ("station", "halt") and g("station") not in ("subway", "funicular") and g("usage") != "tourism":
        return "train"
    if g("aerialway") == "station":
        return "lift"
    if g("waterway") == "waterfall" or nat == "waterfall":
        return "waterfall"
    if hist == "castle":
        return "castle"
    if hist == "ruins":
        return "ruins"
    if am == "place_of_worship":
        return "chapel" if (g("building") == "chapel" or g("place_of_worship") == "chapel") else "church"
    if nat == "cave_entrance":
        return "cave"
    if tour == "camp_site":
        return "camping"
    if tour == "museum":
        return "museum"
    if tour == "information" and g("information") == "office":
        return "info"
    if am == "bicycle_repair_station" or g("shop") == "bicycle":
        return "bike_shop"
    if am == "charging_station" and g("bicycle") in YES:
        return "charging"
    if g("shop") in ("supermarket", "convenience", "bakery"):
        return "shop"
    return None


def clean(v, maxlen=200):
    v = (v or "").strip()
    return v[:maxlen] if v else None


def ele(v):
    try:
        e = float(str(v).replace(",", ".").replace("m", "").strip())
        return int(round(e)) if -500 < e < 9000 else None
    except ValueError:
        return None


def props(prefix, oid, kind, t):
    p = {"id": f"{prefix}{oid}", "kind": kind}
    name = clean(t.get("name:de")) or clean(t.get("name"))
    if name:
        p["name"] = name
    e = ele(t.get("ele")) if t.get("ele") else None
    if e is not None:
        p["ele"] = e
    for key, srcs in (("opening_hours", ("opening_hours",)), ("phone", ("phone", "contact:phone", "contact:mobile")),
                      ("website", ("website", "contact:website", "url")), ("wikipedia", ("wikipedia",)),
                      ("cuisine", ("cuisine",))):
        for s in srcs:
            v = clean(t.get(s))
            if v:
                p[key] = v
                break
    return p


def inside(lon, lat):
    return minlon <= lon <= maxlon and minlat <= lat <= maxlat


class Handler(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.feats = []
        self.seen = set()

    def add(self, prefix, oid, kind, t, lon, lat):
        if not inside(lon, lat):
            return
        key = (prefix, oid)
        if key in self.seen:
            return
        self.seen.add(key)
        self.feats.append({"type": "Feature",
                           "geometry": {"type": "Point", "coordinates": [round(lon, 5), round(lat, 5)]},
                           "properties": props(prefix, oid, kind, t)})

    def node(self, n):
        kind = classify(n.tags)
        if kind and n.location.valid():
            self.add("n", n.id, kind, n.tags, n.location.lon, n.location.lat)

    def way(self, w):
        kind = classify(w.tags)
        if not kind:
            return
        try:
            coords = [(n.lon, n.lat) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        if not coords:
            return
        if len(coords) >= 4 and coords[0] == coords[-1]:
            pt = Polygon(coords).representative_point()
        elif len(coords) >= 2:
            pt = LineString(coords).interpolate(0.5, normalized=True)
        else:
            pt = None
        if pt is not None:
            self.add("w", w.id, kind, w.tags, pt.x, pt.y)
        else:
            self.add("w", w.id, kind, w.tags, *coords[0])

    def area(self, a):
        if a.from_way():
            return  # closed ways handled in way()
        kind = classify(a.tags)
        if not kind:
            return
        try:
            wkb = osmium.geom.WKBFactory().create_multipolygon(a)
        except Exception:
            return
        from shapely import wkb as swkb
        pt = swkb.loads(wkb, hex=True).representative_point()
        self.add("r", a.orig_id(), kind, a.tags, pt.x, pt.y)


def main():
    h = Handler()
    h.apply_file(pbf, locations=True)
    # stable order: important kinds first (helps symbol placement priority ties)
    h.feats.sort(key=lambda f: f["properties"]["id"])
    out = f"{out_dir}/pois.geojson"
    with open(out, "w") as f:
        json.dump({"type": "FeatureCollection", "features": h.feats}, f, separators=(",", ":"), ensure_ascii=False)
    c = Counter(f["properties"]["kind"] for f in h.feats)
    print("pois", len(h.feats), dict(sorted(c.items(), key=lambda kv: -kv[1])))


main()
