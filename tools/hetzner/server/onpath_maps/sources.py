"""Which OSM extracts cover a bbox, from the Geofabrik index (https://download.geofabrik.de/index-v1.json).

Rule: use the *leaf* regions (no sub-regions) that belong to a country (the region or one of its parents has an
ISO 3166 code) and intersect the bbox. That gives small downloads that do not overlap much (e.g. italy/nord-est +
austria + switzerland for the Ortler area) and skips special overlapping extracts such as europe/alps or dach.
If nothing matches (open sea), the smallest region that contains the bbox is used.
"""
import json

from shapely.geometry import box, shape


def load_index(path):
    with open(path) as f:
        return json.load(f)["features"]


def _has_iso(p):
    return bool(p.get("iso3166-1:alpha2") or p.get("iso3166-2"))


def select(features, bbox):
    """List of (id, pbf_url) covering bbox = (minlon, minlat, maxlon, maxlat)."""
    want = box(*bbox)
    by_id = {f["properties"]["id"]: f for f in features}
    parents = {f["properties"].get("parent") for f in features}

    def iso_chain(fid):
        seen = set()
        while fid and fid not in seen:
            seen.add(fid)
            f = by_id.get(fid)
            if f is None:
                return False
            if _has_iso(f["properties"]):
                return True
            fid = f["properties"].get("parent")
        return False

    out = []
    for f in features:
        p = f["properties"]
        if p["id"] in parents or not f.get("geometry") or not p.get("urls", {}).get("pbf"):
            continue
        if not iso_chain(p["id"]):
            continue
        g = shape(f["geometry"])
        if g.intersects(want) and g.intersection(want).area > 0:
            out.append((p["id"], p["urls"]["pbf"]))
    if out:
        return sorted(out)
    containing = [f for f in features if f.get("geometry") and p_url(f) and shape(f["geometry"]).contains(want)]
    if containing:
        f = min(containing, key=lambda f: shape(f["geometry"]).area)
        return [(f["properties"]["id"], p_url(f))]
    return []


def p_url(f):
    return f["properties"].get("urls", {}).get("pbf")
