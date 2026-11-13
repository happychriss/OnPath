"""Tour-suggestions POC – variant B: loop search in the way network (instead of many single routings).

The way network around the start comes once from the route service (/graph: BRouter's own nodes and links, the
profile's cost factor per direction, the full way tags). Every piece of way gets an *unpleasantness per metre* for
this wish (the same ingredients as the score sheet: near forest, quiet, at water / on a lake shore, on a signed or
known named route, natural ground; street and main road cost extra). Then:

  1. one search from the start (cheapest = most pleasant ways) – it also says how far every node is on foot;
  2. anchors: per direction sector and distance band the node reached most pleasantly, plus nodes on the
     destinations in reach (lake shores, known routes);
  3. loops start → A → back (lollipop) and start → A → B → start for anchors in different directions; every later
     part pays `used_penalty` on ways already walked, so the way back avoids the way out;
  4. ready-made rings: a lake shore or a signed circular route that closes in the network is walked as it is
     (way there + ring + way back);
  5. all loops whose time fits are scored with the same sheet as variant A (suggest.numbers).

Everything stays inside one graph in memory – thousands of loops could be tried; the limit here is only Python.
Knobs: weights.json "netsearch".
"""
import heapq
import math
import time
import urllib.request

INF = float("inf")


class Graph:
    pass


def load_graph(start, radius, kv, W, service):
    lines = ["profile=" + W["routing"]["profile"], "at=%.6f,%.6f" % (start[0], start[1]), "radius=%d" % radius]
    lines += ["kv=%s=%s" % (k, v) for k, v in kv.items()]
    text = urllib.request.urlopen(urllib.request.Request(service + "/graph", data="\n".join(lines).encode()),
                                  timeout=120).read().decode()
    if text.startswith("ERR"):
        raise RuntimeError(text[:300])
    G = Graph()
    G.nodes, G.tags, G.edges, G.adj = [], [], [], []
    G.start = 0
    for ln in text.split("\n"):
        if not ln:
            continue
        k = ln[0]
        if k == "N":
            f = ln.split(" ")
            G.nodes.append((float(f[2]), float(f[3]), float(f[4])))
            G.adj.append([])
        elif k == "E":
            f = ln.split(" ", 7)
            a, b = int(f[1]), int(f[2])
            geom = [[float(x) for x in p.split(",")] for p in f[7].split(";")] if len(f) > 7 and f[7] else []
            G.edges.append((a, b, float(f[3]), float(f[4]), float(f[5]), int(f[6]), geom))
        elif k == "T":
            f = ln.split(" ", 2)
            d = {}
            for x in (f[2] if len(f) > 2 else "").split(" "):
                if "=" in x:
                    kk, vv = x.split("=", 1)
                    d[kk] = vv
            d.pop("reversedirection", None)
            G.tags.append(d)
        elif k == "S":
            G.start = int(ln.split(" ")[1])
    for i, e in enumerate(G.edges):
        G.adj[e[0]].append((e[1], i))
        G.adj[e[1]].append((e[0], i))
    return G


def dijkstra(G, src, cost, pen=None, stop=None, factor=1.0):
    n = len(G.nodes)
    dist = [INF] * n
    ln = [0.0] * n
    prev = [-1] * n
    pe = [-1] * n
    dist[src] = 0.0
    heap = [(0.0, src)]
    adj, edges = G.adj, G.edges
    while heap:
        d, u = heapq.heappop(heap)
        if d > dist[u]:
            continue
        if u == stop:
            break
        for v, ei in adj[u]:
            c = cost[ei]
            if c < 0:
                continue
            if pen is not None and ei in pen:
                c *= factor
            nd = d + c
            if nd < dist[v]:
                dist[v] = nd
                prev[v] = u
                pe[v] = ei
                ln[v] = ln[u] + edges[ei][2]
                heapq.heappush(heap, (nd, v))
    return dist, prev, pe, ln


def path_to(prev, pe, t):
    nodes, edges = [t], []
    while prev[t] >= 0:
        edges.append(pe[t])
        t = prev[t]
        nodes.append(t)
    nodes.reverse()
    edges.reverse()
    return nodes, edges


def edge_values(G, wish, W, index, S):
    """Per edge: cost (unpleasant metres, -1 = not allowed), class, attraction hits."""
    C, SC, N = W["classes"], W["score"]["weights"], W["netsearch"]
    ranks = W["attractions"]["rank"]
    tw = {k: SC[k] * wish[k] for k in ("forest", "quiet", "water", "signed", "natural")}
    wsum = sum(tw.values()) or 1.0
    per_tag = {}
    cost, hits_all = [], []
    for (a, b, ln, cf, cb, tid, geom) in G.edges:
        if min(cf, cb) >= 9999:
            cost.append(-1.0)
            hits_all.append(())
            continue
        if tid not in per_tag:
            t = G.tags[tid]
            k = S.edge_class(t, C)
            fv = C["forest_value"].get(t.get("estimated_forest_class", ""), 0.0)
            if k in "SG":
                fv *= C["forest_on_street_factor"]
            hw, surf = t.get("highway", "?"), t.get("surface", "")
            paved = surf in C["paved_surfaces"] or (not surf and hw in C["street_highways"])
            per_tag[tid] = (k, fv, 1 - C["noise_value"].get(t.get("estimated_noise_class", ""), 0.0),
                            C["river_value"].get(t.get("estimated_river_class", ""), 0.0), S.signed_rank(t, C, ranks),
                            0.0 if paved else 1.0)
        k, fv, quiet, river, sr, natural = per_tag[tid]
        hits = ()
        if index is not None:
            na, nb = G.nodes[a], G.nodes[b]
            mid = geom[len(geom) // 2] if geom else [(na[0] + nb[0]) / 2, (na[1] + nb[1]) / 2]
            near = index.at(mid, 1)
            hits = tuple(i for i in index.at(mid, 2) if index.attr[i]["kind"] != "route" or i in near)
            for i in hits:
                a_ = index.attr[i]
                if a_["kind"] == "lake" and k not in "SG":
                    river = 1.0
                elif a_["kind"] == "route":
                    sr = max(sr, a_["rank"])
        p = (tw["forest"] * fv + tw["quiet"] * quiet + tw["water"] * river + tw["signed"] * min(1.0, sr)
             + tw["natural"] * natural) / wsum
        c = 1.0 + N["k_taste"] * (1.0 - p) + (N["pen_main"] if k == "G" else N["pen_street"] if k == "S" else 0.0)
        c *= 1.0 + N["cf_weight"] * (min(min(cf, cb), 5.0) - 1.0)
        cost.append(ln * c)
        hits_all.append(hits)
    return cost, hits_all


def to_candidate(G, nodes, edges, wish, W, S, extra):
    """Node / edge path -> a candidate in variant A's form (edges = [a, b, metres, way tags, node tags])."""
    # cut exact there-and-backs (… x y x …)
    n2, e2 = [nodes[0]], []
    for nd, ei in zip(nodes[1:], edges):
        if e2 and len(n2) >= 2 and n2[-2] == nd and e2[-1] == ei:
            n2.pop()
            e2.pop()
        else:
            n2.append(nd)
            e2.append(ei)
    nodes, edges = n2, e2
    if len(edges) < 3:
        return None
    out = []
    up, ref = 0.0, G.nodes[nodes[0]][2]
    hyst = W["netsearch"]["ascent_hysteresis_m"]
    for u, v, ei in zip(nodes, nodes[1:], edges):
        a, b, ln, cf, cb, tid, geom = G.edges[ei]
        pts = [list(G.nodes[a][:2])] + geom + [list(G.nodes[b][:2])]
        if u != a:
            pts = pts[::-1]
        t = G.tags[tid]
        nt = {"highway": "crossing"} if t.get("footway") == "crossing" else {}
        for i in range(len(pts) - 1):
            out.append([pts[i], pts[i + 1], S.dist(pts[i], pts[i + 1]), t, nt if i == 0 else {}])
        ele = G.nodes[v][2]
        if ele > ref + hyst:
            up += ele - ref
            ref = ele
        elif ele < ref - hyst:
            ref = ele
    km = sum(e[2] for e in out) / 1000.0
    hours = km / wish["pace"] + up / W["time"]["ascent_m_per_h"]
    c = {"edges": out, "km": km, "hours": hours, "ascent": up, "fit": abs(hours - wish["hours"]) / wish["hours"],
         "route_ms": 0, "legs_routed": 0, "seed": 0, "cw": True, "node_path": nodes}
    c.update(extra)
    return c


def search(wish, W, kv, attr, index, rnd, S, service):
    N = W["netsearch"]
    t0 = time.time()
    target_m = wish["hours"] * wish["pace"] * 1000.0
    radius = min(N["max_radius_m"], N["radius_factor"] * target_m)
    G = load_graph(wish["start"], radius, kv, W, service)
    t_graph = time.time() - t0
    cost, hits = edge_values(G, wish, W, index, S)
    s = G.start
    s_xy = G.nodes[s]
    d0, prev0, pe0, len0 = dijkstra(G, s, cost)
    info = {"graph_nodes": len(G.nodes), "graph_edges": len(G.edges), "graph_s": round(t_graph, 2),
            "radius_m": int(radius), "searches": 1}
    built = []
    max_off = N["keep_length_off"]

    def add(nodes, edges, extra):
        c = to_candidate(G, nodes, edges, wish, W, S, extra)
        if c and c["fit"] <= max_off:
            built.append(c)

    # ---- anchors: per sector and distance band the most pleasantly reached node
    sectors = N["sectors"]
    anchors = {}
    for v in range(len(G.nodes)):
        if d0[v] == INF or len0[v] <= 0:
            continue
        frac = len0[v] / target_m
        band = None
        for bi, b in enumerate(N["bands"]):
            if abs(frac - b) <= N["band_width"]:
                band = bi
                break
        if band is None:
            continue
        p = G.nodes[v]
        if S.dist(s_xy, p) < 0.5 * len0[v]:
            continue  # reached by a winding way: a poor turning point
        sec = int(S.bearing(s_xy, p) / (360.0 / sectors)) % sectors
        val = d0[v] / len0[v] + rnd.uniform(0, N["anchor_jitter"])
        k = (sec, band)
        if k not in anchors or val < anchors[k][0]:
            anchors[k] = (val, v)
    alist = [(k[0], k[1], v, None) for k, (val, v) in sorted(anchors.items())]
    # ---- anchors on destinations (the most relevant first), and their rings
    rel = {"lake": 0.3 + wish["water"], "route": 0.3 + wish["signed"], "view": 0.0}
    tops = sorted([i for i, a in enumerate(index.attr)] if index else [],
                  key=lambda i: -(index.attr[i]["rank"] * rel[index.attr[i]["kind"]]))[:N["max_targets"]]
    on = {i: set() for i in tops}
    for ei, hs in enumerate(hits):
        if cost[ei] < 0:
            continue
        for i in hs:
            if i in on:
                on[i].add(ei)
    for i in tops:
        a = index.attr[i]
        if not on[i] or rel[a["kind"]] <= 0.3:
            continue
        nodes_on = set()
        for ei in on[i]:
            nodes_on.add(G.edges[ei][0])
            nodes_on.add(G.edges[ei][1])
        reach = [v for v in nodes_on if d0[v] < INF and len0[v] <= N["target_reach"] * target_m]
        if not reach:
            continue
        entry = min(reach, key=lambda v: d0[v])
        far = max(reach, key=lambda v: len0[v])
        sec = int(S.bearing(s_xy, G.nodes[entry]) / (360.0 / sectors)) % sectors
        alist.append((sec, 9, entry, a["name"]))
        if far != entry:
            alist.append((int(S.bearing(s_xy, G.nodes[far]) / (360.0 / sectors)) % sectors, 9, far, a["name"]))
        # a ring in the network: leave the entry by one edge of the attraction, come back by the others
        sub = on[i]
        best = None
        for v2, ei in G.adj[entry]:
            if ei not in sub:
                continue
            ring_cost = [c if (j in sub and j != ei) else -1.0 for j, c in enumerate(cost)] if len(sub) < 4000 else None
            if ring_cost is None:
                break
            dr, pr, per, lr = dijkstra(G, v2, ring_cost, stop=entry)
            info["searches"] += 1
            if dr[entry] == INF:
                continue
            rn, re_ = path_to(pr, per, entry)
            ring_len = lr[entry] + G.edges[ei][2]
            if best is None or ring_len > best[0]:
                best = (ring_len, [entry] + rn, [ei] + re_)
            break
        if best and best[0] >= N["ring_min_m"]:
            n1, e1 = path_to(prev0, pe0, entry)
            used = set(e1) | set(best[2])
            d3, p3, pe3, l3 = dijkstra(G, entry, cost, pen=used, stop=s, factor=N["used_penalty"])
            info["searches"] += 1
            if d3[s] < INF:
                n3, e3 = path_to(p3, pe3, s)
                add(n1 + best[1][1:] + n3[1:], e1 + best[2] + e3,
                    {"bearing": round(S.bearing(s_xy, G.nodes[entry])), "shape": "runde", "mode": "ring",
                     "target_name": a["name"], "vias": [list(G.nodes[entry][:2])]})

    # ---- one search per anchor (ways of start→A cost extra): lollipops, and the middle part of the pairs
    per_anchor = []
    for sec, band, v, tname in alist:
        n1, e1 = path_to(prev0, pe0, v)
        used = set(e1)
        dA, pA, peA, lA = dijkstra(G, v, cost, pen=used, factor=N["used_penalty"])
        info["searches"] += 1
        per_anchor.append((sec, band, v, tname, n1, e1, dA, pA, peA, lA))
        if dA[s] < INF:
            n2, e2 = path_to(pA, peA, s)
            add(n1 + n2[1:], e1 + e2, {"bearing": round(S.bearing(s_xy, G.nodes[v])), "shape": "keule",
                                       "target_name": tname, "mode": "pull", "vias": [list(G.nodes[v][:2])]})
    # ---- pairs A, B in different directions whose estimated length fits
    pairs = []
    for ia, A in enumerate(per_anchor):
        for ib, B in enumerate(per_anchor):
            if ia == ib:
                continue
            turn = (B[0] - A[0]) % sectors
            if not (N["pair_min_sectors"] <= turn <= N["pair_max_sectors"]):
                continue  # B lies clockwise of A by 60–150°: each pair once, in one sense of rotation
            vb = B[2]
            if A[6][vb] == INF:
                continue
            est = len0[A[2]] + A[9][vb] + len0[vb]
            off = abs(est - target_m) / target_m
            if off > N["pair_length_off"]:
                continue
            estc = (d0[A[2]] + A[6][vb] + d0[vb]) / est
            pairs.append((estc + off, ia, ib))
    pairs.sort()
    per_a = {}
    done = 0
    for _, ia, ib in pairs:
        if done >= N["pairs_max"]:
            break
        if per_a.get(ia, 0) >= N["pairs_per_anchor"]:
            continue
        per_a[ia] = per_a.get(ia, 0) + 1
        A, B = per_anchor[ia], per_anchor[ib]
        n2, e2 = path_to(A[7], A[8], B[2])
        used = set(A[5]) | set(e2)
        d3, p3, pe3, l3 = dijkstra(G, B[2], cost, pen=used, stop=s, factor=N["used_penalty"])
        info["searches"] += 1
        done += 1
        if d3[s] == INF:
            continue
        n3, e3 = path_to(p3, pe3, s)
        mid = [(G.nodes[A[2]][0] + G.nodes[B[2]][0]) / 2, (G.nodes[A[2]][1] + G.nodes[B[2]][1]) / 2]
        add(A[4] + n2[1:] + n3[1:], A[5] + e2 + e3,
            {"bearing": round(S.bearing(s_xy, mid)), "shape": "schleife", "target_name": A[3] or B[3], "mode": "pull",
             "vias": [list(G.nodes[A[2]][:2]), list(G.nodes[B[2]][:2])]})
    info["anchors"] = len(alist)
    info["pairs_tried"] = done
    info["loops_kept"] = len(built)
    info["search_s"] = round(time.time() - t0 - t_graph, 2)
    return built, info, G


def router_check(c, wish, W, kv, S):
    """A chosen loop once through the real router (via points every ~via_every_m on the loop): how much of the loop
    does BRouter confirm, and its ascent."""
    step = W["netsearch"]["check_via_every_m"]
    edges = c["edges"]
    vias, acc = [], 0.0
    for e in edges[:-1]:
        acc += e[2]
        if acc >= step:
            vias.append(e[1])
            acc = 0.0
    r = S.route([wish["start"]] + vias + [wish["start"]], kv, [], W)
    if "error" in r:
        return {"router_error": str(r["error"])[:80]}
    mine = {S.ekey(e[0], e[1]) for e in edges}
    total = sum(e[2] for e in edges) or 1.0
    co = r["coords"]
    same = sum(S.dist(a, b) for a, b in zip(co, co[1:]) if S.ekey(a, b) in mine)
    return {"router_match_pct": round(100 * same / total), "router_km": round(r["dist"] / 1000.0, 2),
            "router_ascent": int(r["ascend"])}
