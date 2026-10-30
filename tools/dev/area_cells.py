#!/usr/bin/env python3
"""z10 map cells (and their z7 region packages) that cover a country – for pre-building an area with
tools/dev/publish_area.sh (docs/design/map-hosting.md §7h).

    python3 -I tools/dev/area_cells.py <natural_earth_vector.sqlite> DEU [--buffer-km 2] [--packages]

The country is Natural Earth's 10 m admin-0 polygon (land only – the coast, islands included; the file is Planetiler's
natural_earth_vector.sqlite, unzipped from ~/onpath-cells-work/src/planetiler-sources/). A cell is listed when the
polygon, grown by --buffer-km, touches it: border cells are in, open-sea-only cells are out. One id per line
("10-531-342"; with --packages the z7 parents "7-66-42"). Standard library only.
"""
import argparse
import math
import sqlite3
import struct
import sys

KM_PER_DEG_LAT = 110.57


def parse_wkb(b):
    """WKB (multi)polygon -> list of rings [(lon, lat), …] (outer and inner rings alike – only edges matter here)."""
    rings, pos = [], 0

    def geom():
        nonlocal pos
        order = "<" if b[pos] == 1 else ">"
        (t,) = struct.unpack_from(order + "I", b, pos + 1)
        pos += 5
        t %= 1000
        if t == 3:
            (n,) = struct.unpack_from(order + "I", b, pos)
            pos += 4
            for _ in range(n):
                (m,) = struct.unpack_from(order + "I", b, pos)
                pos += 4
                pts = struct.unpack_from(order + "%dd" % (2 * m), b, pos)
                pos += 16 * m
                rings.append(list(zip(pts[0::2], pts[1::2])))
        elif t == 6:
            (n,) = struct.unpack_from(order + "I", b, pos)
            pos += 4
            for _ in range(n):
                geom()
        else:
            raise ValueError(f"WKB type {t} not handled")

    geom()
    return rings


def tile_bounds(z, x, y):
    n = 2 ** z
    lat = lambda yy: math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))  # noqa: E731
    return x / n * 360 - 180, lat(y + 1), (x + 1) / n * 360 - 180, lat(y)  # w, s, e, n


def tile_of(z, lon, lat):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


def inside(rings, px, py):
    """Even-odd rule over all rings (holes count as holes)."""
    c = False
    for r in rings:
        for (x1, y1), (x2, y2) in zip(r, r[1:] + r[:1]):
            if (y1 > py) != (y2 > py) and px < (x2 - x1) * (py - y1) / (y2 - y1) + x1:
                c = not c
    return c


def seg_point_dist(ax, ay, bx, by, px, py):
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx - px, ay + t * dy - py)


def seg_rect_dist(ax, ay, bx, by, w, s, e, n):
    def pin(x, y):
        return w <= x <= e and s <= y <= n
    if pin(ax, ay) or pin(bx, by):
        return 0.0
    corners = [(w, s), (e, s), (e, n), (w, n)]
    d = min(seg_point_dist(ax, ay, bx, by, cx, cy) for cx, cy in corners)
    for (cx, cy), (qx, qy) in zip(corners, corners[1:] + corners[:1]):
        d = min(d, seg_point_dist(cx, cy, qx, qy, ax, ay), seg_point_dist(cx, cy, qx, qy, bx, by))
        if crosses(ax, ay, bx, by, cx, cy, qx, qy):
            return 0.0
    return d


def crosses(ax, ay, bx, by, cx, cy, dx, dy):
    def side(px, py, qx, qy, rx, ry):
        return (qx - px) * (ry - py) - (qy - py) * (rx - px)
    return (side(ax, ay, bx, by, cx, cy) * side(ax, ay, bx, by, dx, dy) < 0
            and side(cx, cy, dx, dy, ax, ay) * side(cx, cy, dx, dy, bx, by) < 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sqlite")
    ap.add_argument("country", help="Natural Earth adm0_a3, e.g. DEU")
    ap.add_argument("--buffer-km", type=float, default=2.0)
    ap.add_argument("--packages", action="store_true", help="list the z7 region packages over those cells instead")
    a = ap.parse_args()
    db = sqlite3.connect(f"file:{a.sqlite}?mode=ro", uri=True)
    row = db.execute("SELECT GEOMETRY FROM ne_10m_admin_0_countries WHERE adm0_a3 = ?", (a.country,)).fetchone()
    if not row:
        sys.exit(f"country {a.country} not found")
    rings = parse_wkb(row[0])
    lats = [p[1] for r in rings for p in r]
    lons = [p[0] for r in rings for p in r]
    kx = KM_PER_DEG_LAT * 1.0019 * math.cos(math.radians((min(lats) + max(lats)) / 2))  # km per degree lon
    ky = KM_PER_DEG_LAT
    # everything in a local km plane (equirectangular – a few % off at the edges, fine for a 2 km buffer)
    km = [[(x * kx, y * ky) for x, y in r] for r in rings]
    segs = [(a_, b_) for r in km for a_, b_ in zip(r, r[1:] + r[:1])]
    buf = a.buffer_km
    pad = buf / ky + 0.01
    x0, y1 = tile_of(10, min(lons) - pad * 2, min(lats) - pad)
    x1, y0 = tile_of(10, max(lons) + pad * 2, max(lats) + pad)
    cells = []
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            w, s, e, n = tile_bounds(10, x, y)
            W, S, E, N = w * kx, s * ky, e * kx, n * ky
            if inside(km, (W + E) / 2, (S + N) / 2):
                cells.append((x, y))
                continue
            near = False
            for (ax, ay), (bx, by) in segs:
                if max(ax, bx) < W - buf or min(ax, bx) > E + buf or max(ay, by) < S - buf or min(ay, by) > N + buf:
                    continue
                if seg_rect_dist(ax, ay, bx, by, W, S, E, N) <= buf:
                    near = True
                    break
            if near:
                cells.append((x, y))
    if a.packages:
        for px, py in sorted({(x >> 3, y >> 3) for x, y in cells}):
            print(f"7-{px}-{py}")
    else:
        for x, y in cells:
            print(f"10-{x}-{y}")


if __name__ == "__main__":
    main()
