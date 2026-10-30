"""Which OSM extracts a cell is cut from – chosen automatically from the Geofabrik index
(https://download.geofabrik.de/index-v1.json; docs/design/map-hosting.md §Source data). Standard library only
(the CI tests run with a plain python3; the polygons are small enough for pure-Python geometry).

Rule for the bbox B = cell + buffer (5 km):
  1. pinned sources (sources.json "sources": hand-made cover rectangles) win when one of their rectangles contains
     the cell – none today (South Tyrol uses the rules below too).
  2. "country leaves" = Geofabrik regions with a .pbf, without sub-regions, that belong to a country (the region or
     a parent has an ISO 3166 code). They do not overlap much, and they are the smallest downloads (italy/nord-est,
     austria, switzerland, germany/bayern/oberbayern, france/rhone-alpes …). Special overlapping extracts (alps,
     dach, britain-and-ireland …) are not leaves of a country and are left out.
     A leaf that holds most of another candidate leaf is a container, not a leaf ("us" next to us/new-york:
     the states' parent in the index is north-america) and is dropped.
     a) one leaf contains B → that leaf (the smallest, if several overlap there).
     b) else the leaves that intersect B (a border cell: austria + italy/nord-est) – at most MAX_EXTRACTS; the
        builder cuts each one and merges the small cuts (memory stays that of one cut, see build_cell.sh).
  3. otherwise the smallest leaf of any kind that contains B (great-britain, canary-islands, alps …);
     else nothing: open sea → the cell is answered "no map data" for good.

MAX_EXTRACTS (z10 cells) = 8: the leaves are cut one after the other and only the small cuts are merged, so a further
leaf costs one more sequential cut (~10 s for a German Regierungsbezirk, ~50 s for austria) – never memory, and the
work disk only holds the small cuts. Falling back to a containing extract (rule 3) is never cheaper: in Europe that is
alps (2.3 GB, cut 60 s+) or dach (over max_extract_mb → the cell could not be built at all). Every z10 cell of
Europe (lat 35–64, Geofabrik index 2026-10) needs at most 5 leaves: Bodensee (10-538-357: tuebingen + freiburg +
schwaben + switzerland + austria), English counties, the NL / BE / LU / FR corners – 8 leaves room to spare.
"""
import json
import math

INDEX_URL = "https://download.geofabrik.de/index-v1.json"
MAX_EXTRACTS = 8  # z10 cells; region packages pass their own (job_build.PACKAGE_MAX_EXTRACTS)


# ------------------------------------------------------------------------------------------------ geometry
def _ring_contains(ring, x, y):
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i][0], ring[i][1]
        xj, yj = ring[j][0], ring[j][1]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def _poly_contains(poly, x, y):
    return _ring_contains(poly[0], x, y) and not any(_ring_contains(h, x, y) for h in poly[1:])


def _segment_hits_box(a, b, box):
    """Liang–Barsky: does segment a-b touch the closed box [w, s, e, n]?"""
    x0, y0 = a[0], a[1]
    dx, dy = b[0] - x0, b[1] - y0
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, x0 - box[0]), (dx, box[2] - x0), (-dy, y0 - box[1]), (dy, box[3] - y0)):
        if p == 0:
            if q < 0:
                return False
        else:
            t = q / p
            if p < 0:
                if t > t1:
                    return False
                t0 = max(t0, t)
            else:
                if t < t0:
                    return False
                t1 = min(t1, t)
    return True


def _rings_hit_box(poly, box):
    for ring in poly:
        for i in range(len(ring) - 1):
            if _segment_hits_box(ring[i], ring[i + 1], box):
                return True
    return False


def _corners(box):
    return ((box[0], box[1]), (box[2], box[1]), (box[2], box[3]), (box[0], box[3]))


def _bbox_of(polys):
    xs = [p[0] for poly in polys for p in poly[0]]
    ys = [p[1] for poly in polys for p in poly[0]]
    return (min(xs), min(ys), max(xs), max(ys))


def _overlaps(a, b):
    return a[0] <= b[2] and b[0] <= a[2] and a[1] <= b[3] and b[1] <= a[3]


def _ring_area(ring):
    """Rough area in km² (equirectangular at the ring's mean latitude) – only used to rank regions."""
    lat = sum(p[1] for p in ring) / len(ring)
    k = 111.32 ** 2 * math.cos(math.radians(lat))
    s = 0.0
    for i in range(len(ring) - 1):
        s += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1]
    return abs(s) / 2 * k


class Region:
    def __init__(self, feature):
        p = feature["properties"]
        self.id = p["id"]
        self.parent = p.get("parent")
        self.name = p.get("name", self.id)
        self.url = (p.get("urls") or {}).get("pbf")
        self.iso = bool(p.get("iso3166-1:alpha2") or p.get("iso3166-2"))
        g = feature.get("geometry") or {}
        coords = g.get("coordinates") or []
        self.polys = [coords] if g.get("type") == "Polygon" else list(coords) if g.get("type") == "MultiPolygon" else []
        self.polys = [poly for poly in self.polys if poly and len(poly[0]) >= 4]
        self.bbox = _bbox_of(self.polys) if self.polys else None
        self.area = sum(_ring_area(poly[0]) - sum(_ring_area(h) for h in poly[1:]) for poly in self.polys)
        self.leaf = True
        self.country = False

    def contains_box(self, box):
        """The whole box inside one polygon of the region (corners inside, no border line crossing it)."""
        if not self.bbox or not (self.bbox[0] <= box[0] and self.bbox[1] <= box[1]
                                 and box[2] <= self.bbox[2] and box[3] <= self.bbox[3]):
            return False
        return any(all(_poly_contains(poly, x, y) for x, y in _corners(box)) and not _rings_hit_box(poly, box)
                   for poly in self.polys)

    def intersects_box(self, box):
        if not self.bbox or not _overlaps(self.bbox, box):
            return False
        cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
        return any(_rings_hit_box(poly, box) or _poly_contains(poly, cx, cy) for poly in self.polys)

    def __repr__(self):
        return f"Region({self.id})"


def load_index(path_or_obj):
    """Regions of index-v1.json (path, file object or parsed dict) with leaf / country flags."""
    if isinstance(path_or_obj, dict):
        data = path_or_obj
    elif hasattr(path_or_obj, "read"):
        data = json.load(path_or_obj)
    else:
        with open(path_or_obj) as f:
            data = json.load(f)
    regions = [Region(f) for f in data.get("features", [])]
    by_id = {r.id: r for r in regions}
    for r in regions:
        if r.parent in by_id:
            by_id[r.parent].leaf = False
    for r in regions:
        seen, cur = set(), r
        while cur is not None and cur.id not in seen:
            seen.add(cur.id)
            if cur.iso:
                r.country = True
                break
            cur = by_id.get(cur.parent)
    return [r for r in regions if r.url and r.polys]


def _is_container(r, q):
    """r is a big extract that also holds q (Geofabrik's "us" next to us/new-york – both look like leaves, the
    states' parent is north-america): most of q's outline inside r and r much larger."""
    if r.area < 3 * q.area or not _overlaps(r.bbox, q.bbox):
        return False
    pts = [p for poly in q.polys for p in poly[0]]
    step = max(1, len(pts) // 200)
    pts = pts[::step]
    hit = sum(1 for p in pts if any(_poly_contains(poly, p[0], p[1]) for poly in r.polys))
    return hit >= 0.8 * len(pts)


def select(regions, box, max_extracts=MAX_EXTRACTS):
    """Regions to cut bbox [w, s, e, n] from (smallest first), [] for open sea. See the module doc."""
    leaves = [r for r in regions if r.leaf and r.country and r.intersects_box(box)]
    leaves = [r for r in leaves if not any(_is_container(r, q) for q in leaves if q is not r)]
    containing = sorted((r for r in leaves if r.contains_box(box)), key=lambda r: r.area)
    if containing:
        return [containing[0]]
    if leaves and len(leaves) <= max_extracts:
        return sorted(leaves, key=lambda r: r.area)
    # only leaves: a continent's outline also holds open sea (australia-oceania around the mid Pacific)
    any_containing = sorted((r for r in regions if r.leaf and r.contains_box(box)), key=lambda r: r.area)
    if any_containing:
        return [any_containing[0]]
    if leaves:  # many small leaves and nothing containing all: all of them – leaving the smallest out (as before
        # 2026-10-30) cut a hole into the map (10-529-349 built without luxembourg, 10-529-339 without limburg)
        return sorted(leaves, key=lambda r: r.area)
    return []


# ------------------------------------------------------------------------------------------------ pinned
def inside(b, r):
    return r[0] <= b[0] and r[1] <= b[1] and b[2] <= r[2] and b[3] <= r[3]


def pick_pinned(sources, box):
    """First pinned source (sources.json) whose cover rectangles contain the box, or None."""
    for s in sources.get("sources", []):
        if any(inside(box, r) for r in s.get("cover", [])):
            return s
    return None
