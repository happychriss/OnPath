"""Level 2 – what the place offers. Measured from the ways in reach of the start, never assumed.

  * how good every way is on four axes (Natur, Ruhe, Weg, Besonderes) for this character – absolute 0..1;
  * the place's own yardstick: per axis the value that is poor here (low quantile) and the one that is as good as it
    gets here (high quantile). In flat farmland a quiet field way is top, in the mountains the bar is higher by itself;
  * what stands out here: the best share of the ground, connected = the areas (a forest, a heath, a park like the
    Hirschpark, a river meadow) and the lines (a known route, a rail trail, a bank) the loops aim at;
  * the unavoidable part: how far it is from this start to the first stand-out ground. That way out and back is the
    same for every loop from here, so it is left out of the scores and of "is this the same loop?".
Pure except for the place names of unnamed areas (land.place_near).
"""
import land
from net import dijkstra, direction, dist, INF

AXES = ("natur", "ruhe", "weg", "besonderes")


def quantile(pairs, q):
    """Length-weighted quantile of (value, metres) pairs."""
    pairs = sorted(pairs[::max(1, len(pairs) // 30000)])  # a sample is enough for a quantile
    want, acc = q * sum(m for _, m in pairs), 0.0
    for v, m in pairs:
        acc += m
        if acc >= want:
            return v
    return pairs[-1][0] if pairs else 0.0


def groups(net, edges, joins=()):
    """Connected groups of `edges` (lists of edge ids); `joins` = extra edges that connect but do not belong."""
    parent = {}

    def find(x):
        while parent.setdefault(x, x) != x:
            parent[x] = x = parent[parent[x]]
        return x
    for ei in list(edges) + list(joins):
        parent[find(net.edges[ei][0])] = find(net.edges[ei][1])
    out = {}
    for ei in edges:
        out.setdefault(find(net.edges[ei][0]), []).append(ei)
    return list(out.values())


class Place:
    def __init__(self, net, F, ch, S, tips=None):
        P, Q = S["place"], S["search"]
        n, things, tips = len(net.edges), F.things, tips or {}
        ok = [ei for ei in range(n) if F.ok[ei]]
        self.net, self.F, self.ch, self.target = net, F, ch, ch.target_m
        # --- absolute values per way
        v = self.v = {a: [0.0] * n for a in AXES}
        w_water, w_other = (1.0, 0.4) if ch.water else (0.6, 1.0)
        for ei in ok:
            v["natur"][ei] = (1 - ch.depth) * F.green[ei] + ch.depth * F.depth[ei]
            v["ruhe"][ei] = 1.0 - max(F.traffic[ei], 0.6 * F.built[ei], 0.5 * F.noise[ei])
            v["weg"][ei] = 1.0 if (F.rail[ei] and ch.bike) else ch.ground[F.ground[ei]]
            tip = max([tips.get(F.areas[F.area[ei]], 0.0)] + [tips.get(things[i]["name"], 0.0) for i in F.named[ei]])
            other = max(F.signed[ei] * ch.signed, 0.9 * (F.rail[ei] and ch.bike), 0.6 * (F.area[ei] > 0), tip)
            v["besonderes"][ei] = min(1.0, max(w_water * F.water[ei], w_other * other)) * (1.0 - F.traffic[ei])
        # --- the place's yardstick
        self.low, self.high = {}, {}
        for a in AXES:
            pairs = [(v[a][ei], F.ln[ei]) for ei in ok]
            self.low[a] = quantile(pairs, P["low"])
            self.high[a] = max(quantile(pairs, P["high"]), self.low[a] + P["min_span"])
        # --- how good a way is *here*, and what the search pays for it
        wsum = sum(ch.axes[a] for a in AXES)
        self.good, self.cost = [0.0] * n, [-1.0] * n
        for ei in ok:
            g = self.good[ei] = sum(ch.axes[a] * self.rel(a, v[a][ei]) for a in AXES) / wsum
            self.cost[ei] = F.ln[ei] * (1 + Q["taste"] * (1 - g)) * (1 + Q["road"] * F.traffic[ei]) \
                * (1 + Q["profile"] * (F.costfactor[ei] - 1))
        self.d0, self.prev0, self.pe0, self.len0 = dijkstra(net, net.start, self.cost)
        reach = [ei for ei in ok if self.d0[net.edges[ei][0]] < INF]
        # --- what stands out here: areas and lines
        bar = quantile([(self.good[ei], F.ln[ei]) for ei in reach], 1.0 - P["standout"])
        calm = lambda ei: F.traffic[ei] < S["facts"]["beside_from"]
        top = [ei for ei in reach if self.good[ei] >= bar and calm(ei)]
        min_area = max(1000.0, P["min_area"] * self.target)
        by_reserve = {}  # a named protected area is an area of its own (the Hirschpark, the Ohligser Heide)
        for ei in top:
            by_reserve.setdefault(F.area[ei], []).append(ei)
        self.areas = sorted((self._thing(es, "area") for part in by_reserve.values() for es in groups(net, part)
                             if sum(F.ln[e] for e in es) >= min_area), key=lambda a: -a["m"] * a["good"])[:14]
        self.lines = self._lines(reach, things, min_area)
        # --- the unavoidable way out
        step, bands = max(250.0, 0.03 * self.target), {}
        for ei in reach:
            b = bands.setdefault(int(self.len0[net.edges[ei][0]] / step), [0.0, 0.0])
            b[0], b[1] = b[0] + self.good[ei] * F.ln[ei], b[1] + F.ln[ei]
        usual = quantile([(self.good[ei], F.ln[ei]) for ei in reach], 0.5)
        first = next((k for k in sorted(bands) if bands[k][0] / bands[k][1] >= usual), 0)  # where the ground gets as good as usual here
        self.access = min(first * step, P["access_max"] * self.target)

    def rel(self, axis, value):
        """An absolute value on this place's yardstick: 0 = poor here, 1 = as good as it gets here."""
        return max(0.0, min(1.0, (value - self.low[axis]) / (self.high[axis] - self.low[axis])))

    def core(self, ei):
        """Beyond the unavoidable way out – only this part of a loop is judged and compared."""
        e = self.net.edges[ei]
        return min(self.len0[e[0]], self.len0[e[1]]) >= self.access

    def _thing(self, es, kind, name="", rank=1.0):
        """An area or a line as the generators and the cards need it."""
        net, F = self.net, self.F
        nodes = {x for e in es for x in net.edges[e][:2] if self.d0[x] < INF}
        m = sum(F.ln[e] for e in es)
        cx, cy = sum(net.nodes[x][0] for x in nodes) / len(nodes), sum(net.nodes[x][1] for x in nodes) / len(nodes)
        near = min(nodes, key=self.len0.__getitem__)
        if not name:
            share = lambda test: sum(F.ln[e] for e in es if test(e)) / m
            by_area = {}
            for e in es:
                if F.area[e]:
                    by_area[F.area[e]] = by_area.get(F.area[e], 0.0) + F.ln[e]
            best = max(by_area, key=by_area.get) if by_area else 0
            if best and by_area[best] >= 0.3 * m:
                name = F.areas[best]
            else:
                what = "Ufer" if share(lambda e: F.water[e] > 0) >= 0.3 else "Wald" if share(lambda e: F.green[e] >= 0.5) >= 0.5 else "Feldflur"
                town = land.place_near([cx, cy])
                name = "%s bei %s" % (what, town) if town else "%s im %s" % (what, direction(net.nodes[net.start], [cx, cy]))
        return {"kind": kind, "name": name, "rank": rank, "edges": set(es), "nodes": nodes, "m": m, "p": [cx, cy],
                "near": near, "d": self.len0[near], "heart": min(nodes, key=lambda x: dist(net.nodes[x], [cx, cy])),
                "good": sum(self.good[e] * F.ln[e] for e in es) / m}

    def _lines(self, reach, things, min_m):
        """Lines worth following: known routes and lake shores (named.py), rail trails, banks of big and small water."""
        F, ch, out, by_thing = self.F, self.ch, [], {}
        for ei in reach:
            for i in F.named[ei]:
                by_thing.setdefault(i, []).append(ei)
        for i, es in by_thing.items():
            th = things[i]
            if th["kind"] != "view" and sum(F.ln[e] for e in es) >= min_m:
                rank = th["rank"] * ch.signed if th["kind"] == "route" else (1.0 if ch.water else 0.6)
                out.append(self._thing(max(groups(self.net, es), key=len), th["kind"], th["name"], rank))
        runs = [("rail", [e for e in reach if F.rail[e]], 0.9 * ch.bike, "Bahntrasse"),
                ("bank", [e for e in reach if F.water[e] == 1.0 and F.traffic[e] < 0.3], 1.0 if ch.water else 0.6, "Ufer"),
                ("brook", [e for e in reach if F.water[e] == 0.5 and F.green[e] >= 0.2 and F.traffic[e] < 0.3], 0.9 if ch.water else 0.4, "Bach")]
        named_edges = {e for ln in out if ln["kind"] == "lake" for e in ln["edges"]}
        for kind, edges, rank, word in runs:
            for es in groups(self.net, edges, [e for e in reach if F.ln[e] <= 80.0]) if rank else []:
                if sum(F.ln[e] for e in es) >= max(min_m, 1500.0) and len(named_edges & set(es)) < 0.5 * len(es):
                    t = self._thing(es, kind, rank=rank)
                    t["name"] = "%s %s" % (word, t["name"].split(" ", 1)[1]) if t["name"].split(" ")[0] in ("Ufer", "Wald", "Feldflur") else t["name"]
                    out.append(t)
        return sorted([ln for ln in out if ln["rank"] > 0], key=lambda ln: -ln["rank"] * min(ln["m"], 0.5 * self.target))

