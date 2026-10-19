"""Region grid, names and the region state store of the OnPath map server (pure stdlib, unit-tested).

A request is a centre (lat, lon) and a size in km. The centre is snapped to a GRID_DEG grid so nearby requests
share one build; a ready region whose bbox already contains the requested box is reused instead of building.
State lives in one JSON file per region under STATE_DIR (atomic writes), so the API and the worker never need a
database and a crash leaves nothing half-written.
"""
import json
import math
import os
import secrets
import time

GRID_DEG = 0.25
SIZES_KM = (100, 150, 200)
MIN_KM, MAX_KM = 20, 250          # API limits (the web page offers SIZES_KM)
KM_PER_DEG_LAT = 110.574


def bbox_for(lat, lon, km):
    """(minlon, minlat, maxlon, maxlat) of a km × km square around (lat, lon), 4 decimals."""
    dlat = km / 2 / KM_PER_DEG_LAT
    dlon = km / 2 / (111.320 * math.cos(math.radians(lat)))
    return (round(lon - dlon, 4), round(lat - dlat, 4), round(lon + dlon, 4), round(lat + dlat, 4))


def snap(lat, lon):
    return (round(round(lat / GRID_DEG) * GRID_DEG, 2), round(round(lon / GRID_DEG) * GRID_DEG, 2))


def _coord(v, pos, neg, width):
    s = f"{abs(v):0{width}.2f}".replace(".", "p")
    return (pos if v >= 0 else neg) + s


def region_name(lat, lon, km):
    """Lower-case, [a-z0-9-] only (build_region.sh rule): n46p50-e011p00-200km."""
    return f"{_coord(lat, 'n', 's', 5)}-{_coord(lon, 'e', 'w', 6)}-{int(km)}km"


def contains(outer, inner):
    return outer[0] <= inner[0] and outer[1] <= inner[1] and outer[2] >= inner[2] and outer[3] >= inner[3]


def validate(lat, lon, km):
    lat, lon, km = float(lat), float(lon), int(float(km))
    if not (-85 <= lat <= 85 and -180 <= lon <= 180):
        raise ValueError("Position außerhalb der Karte.")
    if not (MIN_KM <= km <= MAX_KM):
        raise ValueError(f"Größe muss zwischen {MIN_KM} und {MAX_KM} km liegen.")
    return lat, lon, km


class Store:
    """One JSON file per region: STATE_DIR/<name>.json. Never throws on a broken file (it is skipped)."""

    def __init__(self, state_dir):
        self.dir = state_dir
        os.makedirs(state_dir, exist_ok=True)

    def path(self, name):
        return os.path.join(self.dir, name + ".json")

    def get(self, name):
        try:
            with open(self.path(name)) as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def put(self, r):
        tmp = self.path(r["name"]) + ".tmp"
        with open(tmp, "w") as f:
            json.dump(r, f, indent=1)
        os.replace(tmp, self.path(r["name"]))

    def update(self, name, **kw):
        r = self.get(name)
        if r is None:
            return None
        r.update(kw)
        self.put(r)
        return r

    def all(self):
        out = []
        for fn in sorted(os.listdir(self.dir)):
            if fn.endswith(".json"):
                r = self.get(fn[:-5])
                if r:
                    out.append(r)
        return out

    def delete(self, name):
        try:
            os.remove(self.path(name))
        except OSError:
            pass

    def request(self, lat, lon, km, now=None):
        """A user asks for a map around (lat, lon). Returns (region, reused): an existing ready region that covers
        the requested square, the queued/building one for the same grid cell, or a new queued region."""
        now = now or time.time()
        lat, lon, km = validate(lat, lon, km)
        want = bbox_for(lat, lon, km)
        regions = self.all()
        ready = [r for r in regions if r.get("status") == "ready" and contains(r["bbox"], want)]
        if ready:
            best = min(ready, key=lambda r: (r["bbox"][2] - r["bbox"][0]) * (r["bbox"][3] - r["bbox"][1]))
            return self.update(best["name"], last_requested=now), True
        slat, slon = snap(lat, lon)
        name = region_name(slat, slon, km)
        r = self.get(name)
        if r and r.get("status") in ("queued", "building"):
            return self.update(name, last_requested=now), True
        bbox = bbox_for(slat, slon, km)
        if not contains(bbox, want):  # snapping moved the box: grow it so the request is inside
            bbox = (min(bbox[0], want[0]), min(bbox[1], want[1]), max(bbox[2], want[2]), max(bbox[3], want[3]))
        r = r or {"name": name, "token": secrets.token_hex(6), "created": now}
        r.update({"lat": slat, "lon": slon, "km": km, "bbox": list(bbox), "status": "queued", "step": "wartet",
                  "queued_at": now, "last_requested": now, "error": None})
        self.put(r)
        return r, False

    def next_queued(self):
        q = [r for r in self.all() if r.get("status") == "queued"]
        return min(q, key=lambda r: r.get("queued_at", 0)) if q else None
