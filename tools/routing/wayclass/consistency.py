#!/usr/bin/env python3
"""Router classes vs. real routes (docs/map-style-notes.md "Router classes"): the map's "forbidden" must never lie
under a BRouter route, and BRouter must never route over a way the map shows as forbidden.

  python3 tools/routing/wayclass/consistency.py PACKAGE_DIR SEGMENTS_DIR [--routes 30] [--forbidden 30] [--seed 1]
          [--work DIR [--reuse]]

PACKAGE_DIR  a format-2 map package built with router classes (overlays.pmtiles with ch / ct / cf / cm, package.json)
SEGMENTS_DIR the rd5 folder for tools/routing/jvm-test/run.sh (E10_N45.rd5 for the test region)

Per activity group (Hike at T3 / Bike trekking / Bike touring / MTB):
  forward  --routes random routes (1.5–6 km, ends on ways the group may use) through the app's BRouterEngine on the
           JVM; every 10 m of each route (not the first / last 25 m) the way under it is looked up in the package's
           z14 tiles: the overlay ways (paths, streets) and the base map's roads within 3.5 m that run along the route
           (within 35°), nearest first (all within 1.5 m of the nearest). A sample is "forbidden" when every such way
           is an overlay way of class 2 for the group; a base-map road under the route counts as "base" (no class:
           tertiary and bigger roads are not in the overlay). Hike also counts samples on trails above T3 (the app's
           runtime rule).
  reverse  --forbidden ways of class 2 (>= 50 m): a route between points at 15 % and 85 % of the way; BRouter must
           not run along it (2 m samples matched as above whose way is that one); "no route" counts as consistent.
Prints the numbers and lists every violation (name, coordinates). Exit 1 when there is one. Needs pmtiles,
mapbox-vector-tile, shapely (the pipeline's venv) and what run.sh needs (JDK, Gradle's Kotlin compiler).
"""
import argparse
import gzip
import json
import math
import os
import random
import subprocess
import sys
import tempfile

import mapbox_vector_tile
from pmtiles.reader import MmapSource, Reader
from shapely import STRtree
from shapely.geometry import LineString, Point

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
Z = 14
GROUPS = [("hike", "ch", "HIKE"), ("trekking", "ct", "BIKE_TREKKING"), ("touring", "cf", "BIKE_TOURING"),
          ("mtb", "cm", "MTB")]
TOL = 3.5        # m: overlay lines are smoothed within 3 m of the OSM line, z14 grid ~0.4 m
STEP = 10.0      # m between samples
END_SKIP = 25.0  # m at each route end (start / end snapping)
LAT0 = 46.6
KX, KY = 111320.0 * math.cos(math.radians(LAT0)), 110540.0


def xy(lon, lat):
    return lon * KX, lat * KY


def lonlat(x, y):
    return x / KX, y / KY


def tile_of(lon, lat, z=Z):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


class Tiles:
    """z14 tiles decoded on demand: line features of overlays.pmtiles "paths" (z13-14 copies) and "streets", and the
    base map's "transportation" lines (layer name "base"; the trimmed format-2 base map has only the bigger roads, rail
    etc. at z14)."""

    def __init__(self, path, base_path=None):
        self.sources = [(Reader(MmapSource(open(path, "rb"))), ("paths", "streets"))]
        if base_path and os.path.exists(base_path):
            self.sources.append((Reader(MmapSource(open(base_path, "rb"))), ("transportation",)))
        self.cache = {}

    def tile(self, tx, ty):
        key = (tx, ty)
        if key in self.cache:
            return self.cache[key]
        feats = []
        n = 2 ** Z
        for reader, names in self.sources:
            data = reader.get(Z, tx, ty)
            if not data:
                continue
            if data[:2] == b"\x1f\x8b":
                data = gzip.decompress(data)
            dec = mapbox_vector_tile.decode(data)
            for lname in names:
                lay = dec.get(lname)
                if not lay:
                    continue
                ext = lay["extent"]
                for f in lay["features"]:
                    g = f["geometry"]
                    parts = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"] \
                        if g["type"] == "MultiLineString" else []
                    for part in parts:
                        pts = []
                        for px, py in part:
                            lon = (tx + px / ext) / n * 360 - 180
                            yy = ty + (1 - py / ext)  # decode() puts the origin bottom-left
                            lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))
                            pts.append(xy(lon, lat))
                        props = dict(f["properties"])
                        if len(pts) < 2 or (lname == "transportation" and props.get("class") in
                                            ("rail", "transit", "aerialway", "ferry", "cable_car")):
                            continue
                        feats.append((LineString(pts), props, "base" if lname == "transportation" else lname))
        tree = STRtree([g for g, _, _ in feats]) if feats else None
        self.cache[key] = (feats, tree)
        return self.cache[key]

    def near(self, lon, lat, tol=TOL):
        """[(distance m, geometry, props, layer)] of the lines within tol."""
        tx, ty = tile_of(lon, lat)
        feats, tree = self.tile(tx, ty)
        if tree is None:
            return []
        p = Point(xy(lon, lat))
        out = []
        for i in tree.query(p.buffer(tol)):
            d = feats[i][0].distance(p)
            if d <= tol:
                out.append((d, *feats[i]))
        return out

    def under(self, lon, lat, heading, tol=TOL):
        """The ways under a route sample: candidates within tol that run along the route (heading = unit vector of the
        route there; within 35°, else all), nearest first – every candidate within 1.5 m of the nearest one."""
        cand = self.near(lon, lat, tol)
        if not cand or heading is None:
            return cand
        p = Point(xy(lon, lat))
        along = []
        for c in cand:
            g = c[1]
            t = g.project(p)
            a = g.interpolate(max(0.0, t - 2.0)); b = g.interpolate(min(g.length, t + 2.0))
            vx, vy = b.x - a.x, b.y - a.y
            ln = math.hypot(vx, vy)
            if ln > 1e-6 and abs(vx * heading[0] + vy * heading[1]) / ln >= math.cos(math.radians(35)):
                along.append(c)
        cand = along or cand
        dmin = min(c[0] for c in cand)
        return [c for c in cand if c[0] <= dmin + 1.5]


def path_like(props, lname):
    if lname == "streets":
        return props.get("class") in ("minor", "service")
    return props.get("k") in ("p", "t") or props.get("sub") in ("footway", "cycleway", "bridleway", "steps",
                                                                  "pedestrian", "via_ferrata")


def sample_line(pts, step=STEP, skip=END_SKIP):
    """(lon, lat, heading) every `step` m along a lat/lon polyline, without the first / last `skip` m; heading = unit
    vector of the route there (metres)."""
    m = [xy(lo, la) for la, lo in pts]
    total = sum(math.dist(a, b) for a, b in zip(m, m[1:]))
    out, d, i, acc = [], skip, 0, 0.0
    while d <= total - skip and i < len(m) - 1:
        seg = math.dist(m[i], m[i + 1])
        if acc + seg >= d:
            t = (d - acc) / seg if seg else 0
            x = m[i][0] + t * (m[i + 1][0] - m[i][0])
            y = m[i][1] + t * (m[i + 1][1] - m[i][1])
            hd = ((m[i + 1][0] - m[i][0]) / seg, (m[i + 1][1] - m[i][1]) / seg) if seg else None
            out.append((*lonlat(x, y), hd))
            d += step
        else:
            acc += seg
            i += 1
    return out, total


def pick_ends(tiles, key, rng, bbox, n):
    """n pairs of points 1.5-6 km apart, each on a way the group may use (class 0 / 1)."""
    pairs = []
    tries = 0
    while len(pairs) < n and tries < n * 200:
        tries += 1
        lon = rng.uniform(bbox[0] + 0.03, bbox[2] - 0.03)
        lat = rng.uniform(bbox[1] + 0.03, bbox[3] - 0.03)
        a = point_on_way(tiles, key, rng, lon, lat)
        if not a:
            continue
        ang, dist = rng.uniform(0, 2 * math.pi), rng.uniform(1500, 6000)
        b = point_on_way(tiles, key, rng, a[0] + math.cos(ang) * dist / KX, a[1] + math.sin(ang) * dist / KY)
        if b:
            pairs.append((a, b))
    return pairs


def point_on_way(tiles, key, rng, lon, lat):
    feats, _ = tiles.tile(*tile_of(lon, lat))
    ok = [g for g, p, ln in feats if ln != "base" and p.get(key) in (0, 1) and path_like(p, ln)]
    if not ok:
        return None
    g = rng.choice(ok)
    x, y = g.interpolate(rng.uniform(0.2, 0.8), normalized=True).coords[0]
    return lonlat(x, y)


def forbidden_ways(tiles, key, rng, bbox, n):
    """n forbidden ways (class 2 for the group, >= 50 m inside one tile) from random tiles."""
    out, seen, tries = [], set(), 0
    while len(out) < n and tries < 4000:
        tries += 1
        tx, ty = tile_of(rng.uniform(bbox[0], bbox[2]), rng.uniform(bbox[1], bbox[3]))
        feats, _ = tiles.tile(tx, ty)
        cand = [(g, p, ln) for g, p, ln in feats
                if ln != "base" and p.get(key) == 2 and g.length >= 50 and path_like(p, ln)]
        if not cand:
            continue
        g, p, ln = rng.choice(cand)
        k = (round(g.coords[0][0]), round(g.coords[0][1]))
        if k in seen:
            continue
        seen.add(k)
        out.append((g, p, ln))
    return out


def run_routes(requests, segments, work, reuse=False):
    req = os.path.join(work, "requests.txt")
    out = os.path.join(work, "routes.txt")
    text = "\n".join(requests) + "\n"
    same = reuse and os.path.exists(out) and os.path.exists(req) and open(req).read() == text
    with open(req, "w") as f:
        f.write(text)
    if not same:
        route_all(req, out, segments, work)
    return read_routes(out)


def route_all(req, out, segments, work):
    env = dict(os.environ, KTTEST=os.path.join(ROOT, "tools/routing/jvm-test/WayClassRoutesTest.kt"), WC_REQ=req,
               WC_OUT=out)
    subprocess.run(["bash", os.path.join(ROOT, "tools/routing/jvm-test/run.sh"), segments, os.path.join(work, "kt")],
                   env=env, check=True)


def read_routes(out):
    routes, errors, cur = {}, {}, None
    for line in open(out):
        if line.startswith("R "):
            cur = line[2:].strip()
            routes[cur] = []
        elif line.startswith("E "):
            name, _, msg = line[2:].strip().partition(" ")
            errors[name] = msg
            cur = None
        elif cur and line.strip():
            la, lo = map(float, line.split()[:2])
            routes[cur].append((la, lo))
    return routes, errors


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("package"); ap.add_argument("segments")
    ap.add_argument("--routes", type=int, default=30); ap.add_argument("--forbidden", type=int, default=30)
    ap.add_argument("--seed", type=int, default=1); ap.add_argument("--work")
    ap.add_argument("--reuse", action="store_true", help="keep WORK/routes.txt when the requests are the same")
    a = ap.parse_args()
    pkg = json.load(open(os.path.join(a.package, "package.json")))
    bbox = pkg["bbox"]
    tiles = Tiles(os.path.join(a.package, "overlays.pmtiles"), os.path.join(a.package, "base.pmtiles"))
    rng = random.Random(a.seed)
    work = a.work or tempfile.mkdtemp(prefix="wayclass-check-")
    os.makedirs(work, exist_ok=True)
    reqs, meta = [], {}
    for gname, key, act in GROUPS:
        for i, (p, q) in enumerate(pick_ends(tiles, key, rng, bbox, a.routes)):
            name = f"fwd-{gname}-{i}"
            reqs.append(f"{name} {act} 3 {p[1]:.6f} {p[0]:.6f} {q[1]:.6f} {q[0]:.6f}")
            meta[name] = ("fwd", gname, key, None)
        for i, (g, props, ln) in enumerate(forbidden_ways(tiles, key, rng, bbox, a.forbidden)):
            name = f"rev-{gname}-{i}"
            p = lonlat(*g.interpolate(0.15, normalized=True).coords[0])
            q = lonlat(*g.interpolate(0.85, normalized=True).coords[0])
            reqs.append(f"{name} {act} 3 {p[1]:.6f} {p[0]:.6f} {q[1]:.6f} {q[0]:.6f}")
            meta[name] = ("rev", gname, key, (g, props, ln))
    routes, errors = run_routes(reqs, a.segments, work, a.reuse)
    stats = {g: {"routes": 0, "no_route": 0, "km": 0.0, "samples": 0, "forbidden": 0, "no_overlay": 0, "over_t3": 0,
                 "rev_ways": 0, "rev_no_route": 0, "rev_over": 0} for g, _, _ in GROUPS}
    bad = []
    for name, (kind, gname, key, extra) in meta.items():
        s = stats[gname]
        if kind == "fwd":
            if name not in routes:
                s["no_route"] += 1
                continue
            s["routes"] += 1
            pts, total = sample_line(routes[name])
            s["km"] += total / 1000
            for lon, lat, hd in pts:
                s["samples"] += 1
                under = [(p, ln) for _, _, p, ln in tiles.under(lon, lat, hd)]
                if not under or any(ln == "base" for _, ln in under):
                    s["no_overlay"] += 1
                    continue
                if all(p.get(key) == 2 for p, _ in under):
                    s["forbidden"] += 1
                    bad.append(f"{name}: route over forbidden {under[0][0]} at {lat:.5f},{lon:.5f}")
                if gname == "hike" and all(p.get("sac", 0) > 3 for p, ln in under if ln == "paths") \
                        and any(ln == "paths" for _, ln in under):
                    s["over_t3"] += 1
        else:
            g, props, ln = extra
            s["rev_ways"] += 1
            if name not in routes:
                s["rev_no_route"] += 1
                continue
            mid = LineString([g.interpolate(t, normalized=True) for t in (0.15, 0.3, 0.5, 0.7, 0.85)])
            pts, _ = sample_line(routes[name], step=2.0, skip=0.0)
            along = 0.0
            for lon, lat, hd in pts:
                if mid.distance(Point(xy(lon, lat))) > TOL:
                    continue
                under = tiles.under(lon, lat, hd)
                if under and all(ln != "base" and p.get(key) == 2 for _, _, p, ln in under) \
                        and any(gg.distance(mid) < 0.5 for _, gg, _, _ in under):
                    along += 2.0
            if along > 15:
                s["rev_over"] += 1
                c = lonlat(*g.interpolate(0.5, normalized=True).coords[0])
                bad.append(f"{name}: routed {along:.0f} m along forbidden {props} at {c[1]:.5f},{c[0]:.5f}")
    print(f"\npackage {a.package} (wayclass {pkg.get('wayclass')}), seed {a.seed}, work {work}")
    print(f"{'group':9} {'routes':>6} {'none':>5} {'km':>7} {'samples':>8} {'forbid':>6} {'base':>6} {'>T3':>4} | "
          f"{'forb.ways':>9} {'no route':>8} {'routed over':>11}")
    for g, s in stats.items():
        print(f"{g:9} {s['routes']:6} {s['no_route']:5} {s['km']:7.1f} {s['samples']:8} {s['forbidden']:6} "
              f"{s['no_overlay']:6} {s['over_t3']:4} | {s['rev_ways']:9} {s['rev_no_route']:8} {s['rev_over']:11}")
    for b in bad:
        print("  VIOLATION", b)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
