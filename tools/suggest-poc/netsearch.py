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
    N = W["netsearch"]
    for k in ("maxcf", "skip", "keep"):  # a coarser network (bikes): ways the profile dislikes / minor ways left out
        if N.get(k):
            lines.append("%s=%s" % (k, N[k]))
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
            # the geometry stays text until a loop needs it (most ways never do)
            G.edges.append((a, b, float(f[3]), float(f[4]), float(f[5]), int(f[6]), f[7] if len(f) > 7 else ""))
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


def geom_of(raw):
    return [[float(x) for x in p.split(",")] for p in raw.split(";")] if raw else []


def mid_of(G, e):
    raw = e[6]
    if raw:
        ps = raw.split(";")
        return [float(x) for x in ps[len(ps) // 2].split(",")]
    na, nb = G.nodes[e[0]], G.nodes[e[1]]
    return [(na[0] + nb[0]) / 2, (na[1] + nb[1]) / 2]


def dijkstra(G, src, cost, pen=None, stop=None, factor=1.0, npen=None, ncost=0.0):
    """pen / factor: ways already walked cost x factor. npen / ncost: stepping onto a node of the way already walked
    costs ncost extra – the way home keeps off the way out instead of weaving across it (owner: at most one
    crossing); where there is no other way (a ferry, a bridge) it is still taken."""
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
            elif npen is not None and v in npen:
                c += ncost
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
    tw = {k: SC[k] * wish[k] for k in ("forest", "quiet", "water", "signed")}
    import nature as NA
    ND = W["nature"]
    tw_deep = SC["deep"] * wish["forest"] if ND["enabled"] else 0.0
    wsum = (sum(tw.values()) + tw_deep) or 1.0
    # along a road and through houses cost more the more nature was asked for
    pen_main = N["pen_main"] * (1.0 + N.get("pen_main_natur", 0.0) * wish["forest"])
    pen_town = N.get("pen_town", 0.0) * wish["forest"]
    no_disc = N.get("roadside_no_discount", 1.5)
    sv = S.surface_values(wish, W)
    k_surf, avoid = N.get("k_surface", 1.5), sv.get("avoid", "")
    # known signed routes are favoured strongly: their ways cost (1 - discount x rank) of the others
    disc = min(N.get("known_discount_max", 0.75), N.get("known_discount_per_signed", 0.8) * wish["signed"])
    per_tag = {}
    cost, hits_all, mws = [], [], []
    for (a, b, ln, cf, cb, tid, geom) in G.edges:
        if min(cf, cb) >= 9999:
            cost.append(-1.0)
            hits_all.append(())
            mws.append(0.0)
            continue
        if tid not in per_tag:
            t = G.tags[tid]
            k = S.edge_class(t, C)
            fv = C["forest_value"].get(t.get("estimated_forest_class", ""), 0.0)
            if k in "SG":
                fv *= C["forest_on_street_factor"]
            hw, surf = t.get("highway", "?"), t.get("surface", "")
            per_tag[tid] = (k, fv, 1 - C["noise_value"].get(t.get("estimated_noise_class", ""), 0.0),
                            C["river_value"].get(t.get("estimated_river_class", ""), 0.0), S.signed_rank(t, C, ranks),
                            S.surface_class(t, C), S.main_weight(t, C), S.builtup(t, k, C))
        k, fv, quiet, river, sr, sclass, mw, built = per_tag[tid]
        hits = ()
        mid = mid_of(G, (a, b, ln, cf, cb, tid, geom))
        deep = NA.value(NA.depth(mid, ND["search_radius_m"]), ND) if tw_deep else 0.0
        if index is not None:
            near = index.at(mid, 1)
            # a known route counts on a way that lies on it – and on a main road only when the routing data says the
            # road itself carries a signed route (else the cycle path beside it is the route, not the carriageway)
            # … and a river's bank only on a way off the road (not a street, not a path beside a road near the river)
            hits = tuple(i for i in index.at(mid, 2)
                         if (index.attr[i]["kind"] == "lake" or index.attr[i]["kind"] == "view")
                         or (index.attr[i]["kind"] == "river" and k not in "SG" and mw <= 0)
                         or (index.attr[i]["kind"] == "route" and i in near and (mw <= 0 or sr > 0)))
            for i in hits:
                a_ = index.attr[i]
                if a_["kind"] == "lake" and k not in "SG":
                    river = 1.0
                elif a_["kind"] == "river" and k not in "SG" and mw <= 0:
                    river = max(river, a_["rank"])  # at the bank of a large river: first-class water
                elif a_["kind"] == "route":
                    sr = max(sr, a_["rank"])
        # on or beside a busy road a signed route is worth nothing: being signed must not make a roadside way cheap
        sr_eff = min(1.0, sr) * max(0.0, 1.0 - no_disc * mw)
        p = (tw["forest"] * fv + tw["quiet"] * quiet + tw["water"] * river + tw["signed"] * sr_eff + tw_deep * deep) / wsum
        # along a road costs by its weight (the carriageway, a path beside it by its noise; a calm road less)
        c = 1.0 + N["k_taste"] * (1.0 - p) + pen_main * mw + (N["pen_street"] if k == "S" else 0.0) \
            + (pen_town if built else 0.0)
        c *= 1.0 + N["cf_weight"] * (min(min(cf, cb), 5.0) - 1.0)
        c *= 1.0 + k_surf * (1.0 - sv[sclass]) + (N.get("surface_avoid_factor", 3.0) if sclass in avoid else 0.0)
        c *= 1.0 - disc * sr_eff
        cost.append(ln * c)
        hits_all.append(hits)
        mws.append(mw)
    return cost, hits_all, mws


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
        pts = [list(G.nodes[a][:2])] + geom_of(geom) + [list(G.nodes[b][:2])]
        if u != a:
            pts = pts[::-1]
        t = G.tags[tid]
        nt = {"highway": "crossing"} if t.get("footway") == "crossing" else {}
        for i in range(len(pts) - 1):
            out.append([pts[i], pts[i + 1], S.dist(pts[i], pts[i + 1]), t, nt if i == 0 else {}])
        ele = G.nodes[v][2]
        if ele < -1000:  # BRouter's "no elevation here" (bridges, tunnels, gaps in the height data)
            continue
        if ref < -1000:
            ref = ele
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


def search(wish, W, kv, attr, index, rnd, S, service, picks=None):
    N = W["netsearch"]
    t0 = time.time()
    target_m = wish["hours"] * wish["pace"] * 1000.0
    radius = min(N["max_radius_m"], N["radius_factor"] * target_m)
    G = load_graph(wish["start"], radius, kv, W, service)
    t_graph = time.time() - t0
    cost, hits, mws = edge_values(G, wish, W, index, S)
    s = G.start
    s_xy = G.nodes[s]
    d0, prev0, pe0, len0 = dijkstra(G, s, cost)
    info = {"graph_nodes": len(G.nodes), "graph_edges": len(G.edges), "graph_s": round(t_graph, 2),
            "radius_m": int(radius), "searches": 1}
    built = []
    max_off = N["keep_length_off"]

    max_cross = W["select"]["max_self_cross"]
    free_stem = W["score"]["free_stem_m"]
    keep_km = S.length_limit_km(wish, W, max_off)
    dropped = {"cross": 0, "length": 0}

    def add(nodes, edges, extra):
        c = to_candidate(G, nodes, edges, wish, W, S, extra)
        if not c:
            return
        if S.length_off_km(c, wish) > keep_km:
            dropped["length"] += 1
        elif S.self_crossings(c["edges"], None, free_stem, W["score"]["selfcross_merge_m"]) > max_cross:  # an eight twice over, a tangle: not a tour, not kept
            dropped["cross"] += 1
        else:
            built.append(c)

    # a short way out and back to where the loop begins is free (score.free_stem_m): the way home may use the first
    # metres of the way out again without the "already walked" penalty
    free_stem = W["score"]["free_stem_m"]

    def walked(e1, *more):
        used, acc = set(), 0.0
        for ei in e1:
            acc += G.edges[ei][2]
            if acc > free_stem:
                used.add(ei)
        for m in more:
            used |= set(m)
        return used

    # crossing-aware building: the nodes of the way already walked (beyond the free stem) cost extra to step onto, so
    # the next part goes round the way out instead of across it; `ncost` in unpleasant metres (netsearch.cross_cost_m)
    ncost = N.get("cross_cost_m", 0.0)

    def touched(used, keep=()):
        if not ncost:
            return None
        ns = set()
        for ei in used:
            ns.add(G.edges[ei][0])
            ns.add(G.edges[ei][1])
        ns.discard(s)
        for v in keep:
            ns.discard(v)
        return ns

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
    # ---- along a known route: start (short stem) → onto the route → follow it for a good part of the budget → back
    #      another way; each direction of the route is its own candidate
    if index:
        follow_routes(G, wish, W, S, index, hits, cost, mws, d0, prev0, pe0, len0, target_m, s, add, info)
        lake_loops(G, wish, W, S, index, hits, cost, target_m, s, add, info)
    # ---- anchors on destinations (the most relevant first), and their rings
    rel = {"lake": 0.3 + wish["water"], "route": 0.3 + wish["signed"], "view": 0.0, "river": 0.3 + wish["water"]}
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
        if a["kind"] == "river":
            continue  # a bank has no ring; "Am <Fluss> entlang" is built in follow_routes
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
            used = walked(e1, best[2])
            d3, p3, pe3, l3 = dijkstra(G, entry, cost, pen=used, stop=s, factor=N["used_penalty"],
                                       npen=touched(used, (entry,)), ncost=ncost)
            info["searches"] += 1
            if d3[s] < INF:
                n3, e3 = path_to(p3, pe3, s)
                add(n1 + best[1][1:] + n3[1:], e1 + best[2] + e3,
                    {"bearing": round(S.bearing(s_xy, G.nodes[entry])), "shape": "runde", "mode": "ring",
                     "target_name": a["name"], "vias": [list(G.nodes[entry][:2])]})

    # ---- "mit KI-Regionswissen": the AI's picks that the map's own destinations do not have (areas, sights, river
    #      banks – signed routes and lakes are destinations already) become anchors: loops are built towards them
    if picks:
        import regionbonus as RB
        A = W["ai"]
        gy = 150.0 / 111320.0
        gx = gy / math.cos(math.radians(s_xy[1]))
        ngrid = {}
        for v, nd in enumerate(G.nodes):
            if d0[v] < INF:
                ngrid.setdefault((int(nd[0] / gx), int(nd[1] / gy)), []).append(v)
        want = [p for p in picks if not p["kind"].startswith("route") and p["kind"] != "lake"]
        want.sort(key=lambda p: (-p["rang"], min(S.dist(s_xy, q) for q in RB.sample(p, 12))))
        made = 0
        for pk in want:
            if made >= A["targets"]:
                break
            near = set()
            for q in RB.sample(pk, 40):
                cx, cy = int(q[0] / gx), int(q[1] / gy)
                best = None
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        for v in ngrid.get((cx + dx, cy + dy), ()):
                            dd = S.dist(G.nodes[v], q)
                            if dd <= A["target_near_m"] and (best is None or dd < best[0]):
                                best = (dd, v)
                if best:
                    near.add(best[1])
            reach = [v for v in near if A["target_min_frac"] * target_m <= len0[v] <= N["target_reach"] * target_m]
            if not reach:
                continue
            entry = min(reach, key=lambda v: d0[v])
            far = max(reach, key=lambda v: len0[v])
            label = pk["name"] + " (KI-Tipp)"
            alist.append((int(S.bearing(s_xy, G.nodes[entry]) / (360.0 / sectors)) % sectors, 9, entry, label))
            if far != entry and len0[far] - len0[entry] > 0.05 * target_m:
                alist.append((int(S.bearing(s_xy, G.nodes[far]) / (360.0 / sectors)) % sectors, 9, far, label))
            made += 1
        info["ai_targets"] = made

    # ---- one search per anchor (ways of start→A cost extra): lollipops, and the middle part of the pairs
    per_anchor = []
    for sec, band, v, tname in alist:
        n1, e1 = path_to(prev0, pe0, v)
        used = walked(e1)
        dA, pA, peA, lA = dijkstra(G, v, cost, pen=used, factor=N["used_penalty"], npen=touched(used, (v,)), ncost=ncost)
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
        used = walked(A[5], e2)
        d3, p3, pe3, l3 = dijkstra(G, B[2], cost, pen=used, stop=s, factor=N["used_penalty"],
                                   npen=touched(used, (B[2],)), ncost=ncost)
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
    info["dropped_crossing"] = dropped["cross"]
    info["dropped_length"] = dropped["length"]
    info["search_s"] = round(time.time() - t0 - t_graph, 2)
    return built, info, G


def used_edges(G, W, e1, *more):
    """Ways already walked, for the way home's penalty: the first score.free_stem_m of the way out are free (a short
    way out and back to where the loop begins costs nothing – owner 2026-11-13)."""
    free, used, acc = W["score"]["free_stem_m"], set(), 0.0
    for ei in e1:
        acc += G.edges[ei][2]
        if acc > free:
            used.add(ei)
    for m in more:
        used |= set(m)
    return used


def used_nodes(G, W, used, s, keep=()):
    """The nodes of the ways already walked (see dijkstra npen); None when cross_cost_m is 0."""
    if not W["netsearch"].get("cross_cost_m", 0.0):
        return None
    ns = set()
    for ei in used:
        ns.add(G.edges[ei][0])
        ns.add(G.edges[ei][1])
    ns.discard(s)
    for v in keep:
        ns.discard(v)
    return ns


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


def follow_routes(G, wish, W, S, index, hits, cost, mws, d0, prev0, pe0, len0, target_m, s, add, info):
    N = W["netsearch"]
    F = N.get("follow", {})
    if not F.get("enabled", True):
        return
    # known routes from "Bekannte Wege" >= from_signed; the bank of a large river from "am Wasser entlang"
    # (>= river_from_water): "Am Rhein entlang" is built like "Entlang <Weg>" – along the bank ways (no street, not
    # beside a road), home another way (the other bank where a ferry or bridge leads there)
    kinds = set()
    if wish["signed"] >= F.get("from_signed", 0.3):
        kinds.add("route")
    if wish["water"] >= F.get("river_from_water", 0.5):
        kinds.add("river")
    if not kinds:
        return
    on = {}
    for ei, hs in enumerate(hits):
        if cost[ei] < 0:
            continue
        for i in hs:
            kd = index.attr[i]["kind"]
            if kd in kinds and (kd == "route" or mws[ei] <= 0):
                on.setdefault(i, set()).add(ei)
    stem_max = F.get("stem_max", 0.12) * target_m
    cands = []
    for i, sub in on.items():
        nodes_on = set()
        for ei in sub:
            nodes_on.add(G.edges[ei][0])
            nodes_on.add(G.edges[ei][1])
        reach = [v for v in nodes_on if d0[v] < INF and len0[v] <= stem_max]
        if not reach:
            continue
        on_m = sum(G.edges[ei][2] for ei in sub)
        if on_m < F.get("min_on", 0.12) * target_m:
            continue
        entry = min(reach, key=lambda v: d0[v])
        boost = F.get("river_boost", 1.5) if index.attr[i]["kind"] == "river" else 1.0
        cands.append((boost * index.attr[i]["rank"] * min(1.0, on_m / (0.5 * target_m)), i, entry, nodes_on))
    cands.sort(key=lambda x: -x[0])
    s_xy = G.nodes[s]
    n_made = 0
    log = info.setdefault("follow", [])
    for _, i, entry, nodes_on in cands[:F.get("max_routes", 4)]:
        sub = on[i]
        a = index.attr[i]
        on_cost = F.get("on_cost", 0.08)
        # on the route a way is cheap – but where the route runs on or beside a busy road it is not: there a quiet way
        # nearby wins (owner: "auch wenn das als offizieller Radweg ausgezeichnet ist")
        rc = F.get("road_cost", 2.5) * (1.0 + N.get("pen_main_natur", 0.0) * wish["forest"])
        cf = [(G.edges[j][2] * (on_cost + rc * mws[j]) if j in sub else c) for j, c in enumerate(cost)]
        dF, pF, peF, lF = dijkstra(G, entry, cf)
        info["searches"] += 1
        # nodes of the route reached (almost) only along the route
        # … a river's bank ways are in pieces (a harbour, a village between): there a wider share off the bank is fine
        opm = F.get("river_on_path_max", 2.0) if a["kind"] == "river" else F.get("on_path_max", 0.5)
        ok = [v for v in nodes_on if dF[v] < INF and lF[v] > 0 and dF[v] <= lF[v] * opm]
        if not ok:
            log.append("%s: vom Einstieg aus kein Stück am Weg entlang erreichbar" % a["name"])
            continue
        e_xy = G.nodes[entry]
        n1, e1 = path_to(prev0, pe0, entry)
        turned = []
        # the turning point: where stem + way along the route + way home (estimated from the start search) fills the
        # wished length; only points the route reaches fairly directly (a route's own loops and side branches are
        # no "along")
        direct = F.get("direct_max", 1.9)
        base = [v for v in ok if lF[v] >= F.get("min_follow", 0.15) * target_m
                and lF[v] <= direct * max(1.0, S.dist(e_xy, G.nodes[v]))]
        for side in (0, 1):
            pool = base if side == 0 or not turned else [
                v for v in base if abs((S.bearing(e_xy, G.nodes[v]) - turned[0] + 180) % 360 - 180) > 100]
            if not pool:
                log.append("%s: %s Richtung zu kurz (längstes Stück %.1f km)" % (
                    a["name"], "zweite" if side else "erste", max(lF[v] for v in ok) / 1000.0))
                continue
            back = F.get("back_factor", 1.15)
            best, T = None, None
            for _try in range(F.get("fit_tries", 3)):
                T2 = min(pool, key=lambda v: abs(len0[entry] + lF[v] + back * len0[v] - target_m))
                if T2 == T:
                    break
                T = T2
                n2, e2 = path_to(pF, peF, T)
                used = used_edges(G, W, e1, e2)
                d3, p3, pe3, l3 = dijkstra(G, T, cost, pen=used, stop=s, factor=N["used_penalty"],
                                           npen=used_nodes(G, W, used, s, (T,)), ncost=N.get("cross_cost_m", 0.0))
                info["searches"] += 1
                if d3[s] == INF:
                    break
                n3, e3 = path_to(p3, pe3, s)
                total = len0[entry] + lF[T] + l3[s]
                if best is None or abs(total - target_m) < best[0]:
                    best = (abs(total - target_m), n1 + n2[1:] + n3[1:], e1 + e2 + e3, T, total)
                if abs(total - target_m) <= 0.08 * target_m:
                    break
                back = l3[s] / max(1.0, len0[T])  # the way home was longer / shorter than estimated: turn earlier / later
                b0 = S.bearing(e_xy, G.nodes[T])
                pool = [v for v in pool if abs((S.bearing(e_xy, G.nodes[v]) - b0 + 180) % 360 - 180) <= 100] or pool
            if best is None:
                log.append("%s: kein Rückweg gefunden" % a["name"])
                continue
            log.append("%s: %.1f km am Weg, Runde %.1f km" % (a["name"], lF[best[3]] / 1000.0, best[4] / 1000.0))
            if not turned:
                turned.append(S.bearing(e_xy, G.nodes[best[3]]))
            add(best[1], best[2], {"bearing": round(S.bearing(s_xy, G.nodes[best[3]])), "shape": "entlang",
                                   "mode": "shore" if a["kind"] == "river" else "follow",
                                   "theme": "river" if a["kind"] == "river" else None, "target_name": a["name"],
                                   "vias": [list(G.nodes[entry][:2]), list(G.nodes[best[3]][:2])]})
            n_made += 1
    info["follow_built"] = n_made


def lake_loops(G, wish, W, S, index, hits, cost, target_m, s, add, info):
    """"Am Wasser entlang": loops made for a named lake in reach – the way there is a fairly direct one (through town
    if need be: the most pleasant way often never arrives), then round the lake if the wished length allows it,
    else along the shore as far as it does, and home another way. Knobs: netsearch.lake."""
    N = W["netsearch"]
    L = N.get("lake", {})
    if not L.get("enabled", True) or wish["water"] < L.get("from_water", 0.5):
        return
    log = info.setdefault("lake", [])
    # a direct search: the same dislikes, but compressed (a street costs ~2x a forest path, not 4–5x)
    direct = [c if c < 0 else e[2] * (c / e[2]) ** L.get("direct_power", 0.5) if e[2] > 0 else 0.0
              for c, e in zip(cost, G.edges)]
    dS, pS, peS, lS = dijkstra(G, s, direct)
    info["searches"] += 1
    on = {}
    for ei, hs in enumerate(hits):
        if cost[ei] < 0:
            continue
        for i in hs:
            if index.attr[i]["kind"] == "lake":
                on.setdefault(i, set()).add(ei)
    cands = []
    for i, sub in on.items():
        nodes_on = set()
        for ei in sub:
            nodes_on.add(G.edges[ei][0])
            nodes_on.add(G.edges[ei][1])
        reach = [v for v in nodes_on if dS[v] < INF and lS[v] <= L.get("stem_max", 0.4) * target_m]
        on_m = sum(G.edges[ei][2] for ei in sub)
        if not reach or on_m < L.get("min_on_m", 800):
            continue
        cands.append((on_m, i, min(reach, key=lambda v: dS[v]), nodes_on))
    cands.sort(key=lambda x: -x[0])
    made = 0
    for on_m, i, entry, nodes_on in cands[:L.get("max_lakes", 3)]:
        a, sub = index.attr[i], on[i]
        n1, e1 = path_to(pS, peS, entry)
        stem = lS[entry]
        on_cost = L.get("on_cost", 0.15)
        cf = [(G.edges[j][2] * on_cost * (1.0 + 0.3 * (c / max(1.0, G.edges[j][2]))) if j in sub else c)
              for j, c in enumerate(cost)]
        cx = sum(p[0] for p in a["points"]) / len(a["points"])
        cy = sum(p[1] for p in a["points"]) / len(a["points"])
        ang = {v: S.bearing([cx, cy], G.nodes[v]) for v in nodes_on}
        a0 = ang[entry]
        diff = lambda v, want: abs((ang[v] - a0 - want + 180) % 360 - 180)

        def home(from_v, used):
            d3, p3, pe3, l3 = dijkstra(G, from_v, cost, pen=used, stop=s, factor=N["used_penalty"],
                                       npen=used_nodes(G, W, used, s, (from_v,)), ncost=N.get("cross_cost_m", 0.0))
            info["searches"] += 1
            return path_to(p3, pe3, s) + (l3[s],) if d3[s] < INF else None

        # --- round the lake: entry → a third round → two thirds → entry, on the shore ways
        P1, P2 = min(nodes_on, key=lambda v: diff(v, 120)), min(nodes_on, key=lambda v: diff(v, 240))
        ring = None
        if diff(P1, 120) < 40 and diff(P2, 240) < 40:
            dA, pA, peA, lA = dijkstra(G, entry, cf, stop=P1)
            nA, eA = path_to(pA, peA, P1)
            dB, pB, peB, lB = dijkstra(G, P1, cf, pen=set(eA), stop=P2, factor=N["used_penalty"])
            nB, eB = path_to(pB, peB, P2)
            dC, pC, peC, lC = dijkstra(G, P2, cf, pen=set(eA) | set(eB), stop=entry, factor=N["used_penalty"])
            info["searches"] += 3
            if dA[P1] < INF and dB[P2] < INF and dC[entry] < INF:
                nC, eC = path_to(pC, peC, entry)
                r_len = lA[P1] + lB[P2] + lC[entry]
                r_edges = eA + eB + eC
                twice = sum(G.edges[j][2] for j in set(eA) & set(eB) | set(eB) & set(eC) | set(eA) & set(eC))
                r_nodes = nA + nB[1:] + nC[1:]
                # a round only if it really goes round the water: the lake's middle lies inside the walked ring
                inside, poly = False, [G.nodes[v] for v in r_nodes]
                for k in range(len(poly)):
                    (x1, y1), (x2, y2) = poly[k][:2], poly[(k + 1) % len(poly)][:2]
                    if (y1 > cy) != (y2 > cy) and cx < x1 + (cy - y1) * (x2 - x1) / (y2 - y1):
                        inside = not inside
                if twice <= 0.15 * r_len and inside:
                    ring = (r_len, r_nodes, r_edges)
        if ring and stem + ring[0] + stem <= (1 + N["keep_length_off"]) * target_m:
            h = home(entry, used_edges(G, W, e1, ring[2]))
            if h:
                add(n1 + ring[1][1:] + h[0][1:], e1 + ring[2] + h[1],
                    {"bearing": round(S.bearing(G.nodes[s], [cx, cy])), "shape": "runde", "mode": "ring", "theme": "lake",
                     "target_name": a["name"], "vias": [list(G.nodes[entry][:2])]})
                made += 1
                log.append("%s: Runde %.1f km, hin %.1f km" % (a["name"], ring[0] / 1000.0, stem / 1000.0))
                continue
        # --- too far round (or no way round): along the shore as far as the wished length allows, each direction
        dF, pF, peF, lF = dijkstra(G, entry, cf)
        info["searches"] += 1
        ok = [v for v in nodes_on if dF[v] < INF and lF[v] >= L.get("min_follow_m", 700)
              and dF[v] <= lF[v] * on_cost * 3.0]
        done = []
        for side in (0, 1):
            pool = [v for v in ok if ((ang[v] - a0 + 360) % 360 < 180) == (side == 0)]
            if not pool:
                continue
            T = min(pool, key=lambda v: abs(stem + lF[v] + 1.1 * lS[v] - target_m))
            n2, e2 = path_to(pF, peF, T)
            h = home(T, used_edges(G, W, e1, e2))
            if not h:
                continue
            add(n1 + n2[1:] + h[0][1:], e1 + e2 + h[1],
                {"bearing": round(S.bearing(G.nodes[s], G.nodes[T])), "shape": "ufer", "mode": "shore", "theme": "lake",
                 "target_name": a["name"], "vias": [list(G.nodes[entry][:2]), list(G.nodes[T][:2])]})
            made += 1
            done.append("%.1f km am Ufer" % (lF[T] / 1000.0))
        log.append("%s: %s" % (a["name"], " / ".join(done) if done else
                               ("Runde zu lang (%.1f km + 2 x %.1f km)" % (ring[0] / 1000.0, stem / 1000.0) if ring
                                else "kein Weg rund um den See, kein Uferstück")))
    info["lake_built"] = made
