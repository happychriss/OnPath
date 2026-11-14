"""Level 1 – what a way is. Facts per piece of way, the same everywhere: nothing here knows the place or the person.

The one place where routing-data tags and the map-area grids (land.py, named.py) become facts:
  ok       allowed for this activity and level (BRouter's profile says so)
  ground   0 paved · 1 firm (compacted, gravel, a good track) · 2 natural · 3 rough (roots, rock, a demanding trail)
  traffic  0..1 – on a road with cars (by its class, calmer at 30 km/h) or on a separate way right beside one
  built    inside settlement land
  green    0..1 nature right around the way (100 m) · depth 0..1 how far inside nature it is (300 m and 900 m)
  water    1 at big water (lake, wide river) · 0.5 at small water (pond, brook with a green strip) · 0
  signed   0..1 rank of the best signed route on it (international / national 1 … local 0.6)
  rail     a rail trail (former railway line, car-free, flat) · ferry · noise 0..1 (routing data)
  area     the named protected area it lies in · named = the known routes / lakes it lies on (named.py)
"""
import land
from net import dist

ROADS = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service"}
PAVED = {"asphalt", "paved", "concrete", "concrete:plates", "concrete:lanes", "paving_stones"}
FIRM = {"compacted", "fine_gravel", "gravel", "pebblestone", "sett", "cobblestone", "unhewn_cobblestone", "metal", "wood", "grass_paver"}
ROUGH = {"rock", "roots", "stone", "scree", "mud"}


class RoadGrid:
    """Where roads with real car traffic run (squares of ~12 m), to find a separate way *beside* one by geometry."""
    CX, CY = 0.00018, 0.00011

    def __init__(self):
        self.cells = {}

    def add(self, pts, w):
        for a, b in zip(pts, pts[1:]):
            n = max(1, int(dist(a, b) / 10.0))
            for k in range(n + 1):
                key = (int((a[0] + (b[0] - a[0]) * k / n) / self.CX), int((a[1] + (b[1] - a[1]) * k / n) / self.CY))
                if self.cells.get(key, 0.0) < w:
                    self.cells[key] = w

    def beside(self, pts):
        """Weight of the road a way runs beside: found at two of its points at 1/4, 1/2, 3/4 (a crossing has one)."""
        total = sum(dist(a, b) for a, b in zip(pts, pts[1:]))
        hits = []
        for f in (0.25, 0.5, 0.75):
            want, acc = f * total, 0.0
            for a, b in zip(pts, pts[1:]):
                d = dist(a, b)
                if acc + d >= want and d > 0:
                    u = (want - acc) / d
                    cx, cy = int((a[0] + (b[0] - a[0]) * u) / self.CX), int((a[1] + (b[1] - a[1]) * u) / self.CY)
                    hits.append(max(self.cells.get((cx + i, cy + j), 0.0) for i in (-1, 0, 1) for j in (-1, 0, 1)))
                    break
                acc += d
        return sorted(hits)[-2] if len(hits) >= 2 else 0.0


def of_tags(t, K, bike):
    """Tags of a way -> (road weight, ground, signed rank, rail, ferry, noise, river class)."""
    hw = t.get("highway", "").replace("_link", "")
    road = K["road"].get(hw, 0.0) if hw in ROADS else 0.0
    if road and t.get("maxspeed", "") in ("10", "20", "30", "walk"):
        road *= K["calm"]
    surf, tt = t.get("surface", ""), t.get("tracktype", "")
    demanding = t.get("sac_scale", "") not in ("", "hiking", "mountain_hiking") or t.get("mtb:scale", "0")[:1] in "23456"
    if surf in ROUGH or demanding or t.get("smoothness") in ("horrible", "very_horrible", "impassable"):
        ground = 3
    elif surf:
        ground = 0 if surf in PAVED else 1 if surf in FIRM else 2
    elif tt:
        ground = 0 if tt == "grade1" else 1 if tt == "grade2" else 2
    else:
        ground = 0 if hw in ROADS or hw in ("cycleway", "pedestrian", "steps") else 1 if hw == "track" else 2
    signed = max([K["rank"].get(k.rsplit("_", 1)[1][:1], K["rank"]["l"]) for k, v in t.items() if v == "yes"
                  and k.startswith("route_bicycle_" if bike else ("route_hiking_", "route_foot_"))] or [0.0])
    rail = t.get("railway") == "abandoned" and hw in ("path", "cycleway", "track") and t.get("bicycle", "yes") != "no"
    num = lambda k: int(t.get(k, "0") or 0)
    return road, ground, signed, rail, t.get("route") == "ferry", num("estimated_noise_class") / 6.0, num("estimated_river_class")


class Facts:
    """Struct of arrays over the network's edges (see the module text)."""

    def __init__(self, net, index, K, bike):
        n = len(net.edges)
        per_tag = [of_tags(t, K, bike) for t in net.tags]
        roads = RoadGrid()
        for ei, e in enumerate(net.edges):
            if per_tag[e[5]][0] >= K["beside_from"] and net.tags[e[5]].get("tunnel") != "yes":
                roads.add(net.geom(ei), per_tag[e[5]][0])
        self.ln = [e[2] for e in net.edges]
        self.ok = [min(e[3], e[4]) < 9999 for e in net.edges]
        self.costfactor = [min(e[3], e[4], 5.0) for e in net.edges]
        self.ground, self.traffic, self.built, self.green, self.depth = [0] * n, [0.0] * n, [False] * n, [0.0] * n, [0.0] * n
        self.water, self.signed, self.rail, self.ferry, self.noise = [0.0] * n, [0.0] * n, [False] * n, [False] * n, [0.0] * n
        self.area, self.named, self.areas = [0] * n, [()] * n, [""]
        things = self.things = index.things
        for ei, e in enumerate(net.edges):
            if not self.ok[ei]:
                continue
            road, self.ground[ei], signed, self.rail[ei], self.ferry[ei], self.noise[ei], river = per_tag[e[5]]
            mid = net.mid(ei)
            if not road and e[2] >= 25.0 and roads.cells:
                road = K["beside"] * roads.beside(net.geom(ei))
            self.traffic[ei] = road
            self.built[ei] = land.built(mid, 45.0) >= 0.5
            self.green[ei] = g = land.depth(mid, 100.0)
            self.depth[ei] = 0.5 * (land.depth(mid, 300.0) + land.depth(mid, 900.0))
            bank = land.water(mid, 90.0) > 0.04
            hits = tuple(index.at(mid, 1))
            for i in hits:
                th = things[i]
                if th["kind"] == "route" and (road < K["beside_from"] or signed):
                    signed = max(signed, th["rank"])  # on a busy road only when the road itself carries the route
                bank = bank or th["kind"] == "lake"
            big = bank and land.depth(mid, 300.0, "sat_water") >= K["big_water"]
            self.water[ei] = 1.0 if big else 0.5 if bank or (river >= 4 and g >= 0.2) else 0.0
            self.signed[ei], self.named[ei] = signed, hits
            nm = land.name_at(mid)
            if nm:
                if nm not in self.areas:
                    self.areas.append(nm)
                self.area[ei] = self.areas.index(nm)
