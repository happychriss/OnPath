"""The way network around a start – BRouter's own nodes and links – and the few graph routines everything uses.

Edge I/O: `load` asks the route service (service/RouteService.java, POST /graph). Everything else is pure.
An edge is (a, b, metres, cost factor a→b, cost factor b→a, tag id, geometry text); a factor >= 9999 = not allowed.
"""
import heapq
import math
import urllib.request

INF = float("inf")
DIRS = ["Norden", "Nordosten", "Osten", "Südosten", "Süden", "Südwesten", "Westen", "Nordwesten"]


def dist(a, b):
    """Metres between two [lon, lat] points (flat-earth, fine for a few kilometres)."""
    kx = math.cos(math.radians((a[1] + b[1]) / 2.0)) * 111320.0
    return math.hypot((a[0] - b[0]) * kx, (a[1] - b[1]) * 111320.0)


def bearing(a, b):
    kx = math.cos(math.radians((a[1] + b[1]) / 2.0))
    return math.degrees(math.atan2((b[0] - a[0]) * kx, b[1] - a[1])) % 360.0


def direction(a, b):
    return DIRS[int((bearing(a, b) + 22.5) // 45) % 8]


class Net:
    """nodes[i] = (lon, lat, ele) · edges[i] = see module text · tags[tid] = {key: value} · adj[node] = [(node, edge)]."""

    def __init__(self, text):
        self.nodes, self.edges, self.tags, self.start = [], [], [], 0
        for ln in text.split("\n"):
            k = ln[:1]
            if k == "N":
                f = ln.split(" ")
                self.nodes.append((float(f[2]), float(f[3]), float(f[4])))
            elif k == "E":
                f = ln.split(" ", 7)
                self.edges.append((int(f[1]), int(f[2]), float(f[3]), float(f[4]), float(f[5]), int(f[6]),
                                   f[7] if len(f) > 7 else ""))
            elif k == "T":
                f = ln.split(" ", 2)
                self.tags.append(dict(x.split("=", 1) for x in (f[2] if len(f) > 2 else "").split(" ") if "=" in x))
            elif k == "S":
                self.start = int(ln.split(" ")[1])
        self.adj = [[] for _ in self.nodes]
        for i, e in enumerate(self.edges):
            self.adj[e[0]].append((e[1], i))
            self.adj[e[1]].append((e[0], i))

    def geom(self, ei, frm=None):
        """The points of an edge, from node `frm` (default: its first node)."""
        a, b, raw = self.edges[ei][0], self.edges[ei][1], self.edges[ei][6]
        pts = [list(self.nodes[a][:2])] + ([[float(x) for x in p.split(",")] for p in raw.split(";")] if raw else []) \
            + [list(self.nodes[b][:2])]
        return pts[::-1] if frm == b and a != b else pts

    def mid(self, ei):
        raw = self.edges[ei][6]
        if raw:
            ps = raw.split(";")
            return [float(x) for x in ps[len(ps) // 2].split(",")]
        a, b = self.nodes[self.edges[ei][0]], self.nodes[self.edges[ei][1]]
        return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]

    def other(self, ei, node):
        e = self.edges[ei]
        return e[1] if e[0] == node else e[0]


def load(service, profile, kv, start, radius, coarse=None):
    """coarse = {maxcf, skip, keep}: leave out ways the profile dislikes / minor ways (the bike network is large)."""
    lines = ["profile=" + profile, "at=%.6f,%.6f" % (start[0], start[1]), "radius=%d" % radius]
    lines += ["kv=%s=%s" % kv_ for kv_ in kv.items()] + ["%s=%s" % c for c in (coarse or {}).items()]
    text = urllib.request.urlopen(urllib.request.Request(service + "/graph", data="\n".join(lines).encode()),
                                  timeout=120).read().decode()
    if text.startswith("ERR"):
        raise RuntimeError(text[:300])
    return Net(text)


def seat_start(net, ok):
    """The start must sit on the connected network: a point snapped onto a dead piece (steps to a private path, a way
    the coarse bike network leaves out) reaches nothing. Then it moves to the nearest node of the big piece.
    -> metres moved (0 = it was fine)."""
    comp, sizes = [-1] * len(net.nodes), []
    for v0 in range(len(net.nodes)):
        if comp[v0] >= 0:
            continue
        comp[v0], stack, size = len(sizes), [v0], 0
        while stack:
            u = stack.pop()
            size += 1
            for v, ei in net.adj[u]:
                if comp[v] < 0 and ok[ei]:
                    comp[v] = comp[u]
                    stack.append(v)
        sizes.append(size)
    big = max(range(len(sizes)), key=sizes.__getitem__)
    if comp[net.start] == big or sizes[comp[net.start]] >= 0.2 * sizes[big]:
        return 0
    p = net.nodes[net.start]
    net.start = min((v for v in range(len(net.nodes)) if comp[v] == big), key=lambda v: dist(p, net.nodes[v]))
    return int(dist(p, net.nodes[net.start]))


def dijkstra(net, src, cost, pen=None, stop=None, max_len=None):
    """Cheapest ways from src. cost[edge] < 0 = closed; pen = {edge: factor} for this search only.
    -> (cost so far, previous node, previous edge, metres so far) per node."""
    n = len(net.nodes)
    d, ln, prev, pe = [INF] * n, [0.0] * n, [-1] * n, [-1] * n
    d[src] = 0.0
    heap, adj, edges = [(0.0, src)], net.adj, net.edges
    get = pen.get if pen else None
    while heap:
        du, u = heapq.heappop(heap)
        if du > d[u]:
            continue
        if u == stop:
            break
        if max_len is not None and ln[u] > max_len:
            continue
        for v, ei in adj[u]:
            c = cost[ei]
            if c < 0:
                continue
            if get:
                c *= get(ei, 1.0)
            if du + c < d[v]:
                d[v], prev[v], pe[v], ln[v] = du + c, u, ei, ln[u] + edges[ei][2]
                heapq.heappush(heap, (du + c, v))
    return d, prev, pe, ln


def path(prev, pe, t):
    """The edges from the search's source to t, in walking order."""
    out = []
    while prev[t] >= 0:
        out.append(pe[t])
        t = prev[t]
    return out[::-1]
