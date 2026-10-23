#!/usr/bin/env python3
"""Extract outdoor POIs from an OSM .pbf into a compact GeoJSON point file (F-MAP-09).

Output: OUT_DIR/pois.geojson, one Point per POI (nodes as is; ways and multipolygons as centroid).
Properties (only when present): id ("n123"/"w123"/"r123"), kind, name, ele, opening_hours, phone,
website, wikipedia, cuisine; always "pr" (placement priority on the map, see priority()).

Usage: python3 extract_pois.py input.osm.pbf OUT_DIR minlon minlat maxlon maxlat
Tests: cd tools && python3 -m unittest -v test_extract_pois
"""
import json
import math
import sys
import zlib
from collections import Counter, defaultdict

import osmium
from shapely.geometry import LineString, Polygon

pbf = out_dir = None
minlon = minlat = maxlon = maxlat = 0.0  # set by main() from the command line

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


PR_LEVELS = 50  # importance levels; pr = level * PR_TIE + tie, so 0 <= pr < 50000
PR_TIE = 1000


def importance(t):
    """0..PR_LEVELS-1: how notable a POI is by its OSM tags (a well-mapped place with a Wikipedia article and opening
    hours before a bare node). Orders POIs of the same kind only – the kind rank comes first in the style."""
    g = t.get
    s = 0
    if g("wikipedia") or g("wikidata"):
        s += 12
    if g("website") or g("contact:website") or g("url"):
        s += 4
    if g("opening_hours"):
        s += 4
    if g("phone") or g("contact:phone") or g("contact:mobile"):
        s += 2
    if g("cuisine"):
        s += 1
    s += min(sum(1 for _ in t), 40) // 4  # more tags = better mapped (0..10)
    return min(s, PR_LEVELS - 1)


def priority(imp, lon, lat):
    """Placement priority "pr" (higher = placed first where symbols collide; make_style.pois() sort key). Fixed per
    POI, so the same POI wins at every zoom and in every tile (field report 0.10.1, Bad Waldsee: restaurants swapped
    while zooming – all of a kind had one sort key and the feature order of each tile decided). Equal importance is
    broken by a hash of the rounded position: deterministic and, unlike the id, the same in a cell build (renumbered
    extract) and a region build."""
    tie = zlib.crc32(f"{lon:.5f},{lat:.5f}".encode()) % PR_TIE
    return int(imp) * PR_TIE + tie


def add_priorities(feats):
    """props() stores the importance level in "pr"; the position tie-break is added once positions are final (after
    the bus-stop merge)."""
    for f in feats:
        lon, lat = f["geometry"]["coordinates"]
        f["properties"]["pr"] = priority(f["properties"].get("pr", 0), lon, lat)
    return feats


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
    p["pr"] = importance(t)  # level only; add_priorities() makes it the final "pr"
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


MERGE_KINDS = {"bus": 80.0}  # kind -> merge radius in metres (same-name stops on both sides of a road)


def merge_duplicates(feats):
    """Same-kind, same-name POIs closer than MERGE_KINDS[kind] metres (the two bus stops of a pair) get one
    extra feature at their midpoint with "merged": 1 (drawn zoomed out, one symbol per stop name), while the
    originals stay with "detail": 1 (drawn zoomed in, so the user can pick the right side). The merged feature
    carries the properties of the member with the most tags. Returns (features, number of merged members)."""
    groups = defaultdict(list)
    rest = []
    for f in feats:
        p = f["properties"]
        if p["kind"] in MERGE_KINDS and p.get("name"):
            groups[(p["kind"], p["name"].casefold())].append(f)
        else:
            rest.append(f)
    merged = 0
    for (kind, _), fs in groups.items():
        radius = MERGE_KINDS[kind]
        clusters = []  # [features]
        for f in fs:
            lon, lat = f["geometry"]["coordinates"]
            for cl in clusters:
                clon, clat = cl[0]["geometry"]["coordinates"]
                dx = (lon - clon) * 111320 * math.cos(math.radians(lat))
                dy = (lat - clat) * 110540
                if math.hypot(dx, dy) <= radius:
                    cl.append(f)
                    break
            else:
                clusters.append([f])
        for cl in clusters:
            if len(cl) == 1:
                rest.append(cl[0])
                continue
            merged += len(cl) - 1
            keep = max(cl, key=lambda f: (len(f["properties"]), f["properties"]["id"]))
            lon = sum(f["geometry"]["coordinates"][0] for f in cl) / len(cl)
            lat = sum(f["geometry"]["coordinates"][1] for f in cl) / len(cl)
            rest.append({**keep, "geometry": {"type": "Point", "coordinates": [round(lon, 5), round(lat, 5)]},
                         "properties": {**keep["properties"], "merged": 1}})
            for f in cl:
                rest.append({**f, "properties": {**f["properties"], "detail": 1}})
    return rest, merged


def main():
    global pbf, out_dir, minlon, minlat, maxlon, maxlat
    pbf, out_dir = sys.argv[1], sys.argv[2]
    minlon, minlat, maxlon, maxlat = map(float, sys.argv[3:7])
    h = Handler()
    h.apply_file(pbf, locations=True)
    h.feats, merged = merge_duplicates(h.feats)
    add_priorities(h.feats)
    print("merged duplicates", merged)
    # stable order: important kinds first (helps symbol placement priority ties)
    h.feats.sort(key=lambda f: f["properties"]["id"])
    out = f"{out_dir}/pois.geojson"
    with open(out, "w") as f:
        json.dump({"type": "FeatureCollection", "features": h.feats}, f, separators=(",", ":"), ensure_ascii=False)
    c = Counter(f["properties"]["kind"] for f in h.feats)
    print("pois", len(h.feats), dict(sorted(c.items(), key=lambda kv: -kv[1])))


if __name__ == "__main__":
    main()
