"""Tour-suggestions POC – variant C: the green-patch chain ("Grünstück-Kette").

Plan like a person: "durch den Stadtwald, rüber zur Heide, über den Grünzug zurück" – no compass directions.
(Owner 2026-11-13, about a loop he praised: "wirklich clever – einfach alle Grüngürtel geschickt kombiniert".)

  1. the way network around the start is loaded once (as in variant B) and every piece of way gets its
     unpleasantness (the same values as B: netsearch.edge_values);
  2. PATCHES: a piece of way is *green* when it is no street, not on / beside a busy road, not inside built-up land
     and lies in or at nature (forest, heath, protected area – nature.py – or at a bank, or the routing data's top
     forest classes). Green pieces that hang together form a patch (a short not-green piece between two green ones,
     e.g. a road crossing, joins them). Small patches are dropped. The start is a patch of its own, or lies in one;
  3. CONNECTORS: from every patch one search over the not-green rest: the least unpleasant way to each neighbouring
     patch (the search stops at the first node of another patch, so only neighbours are linked) – with the gate where
     it leaves and the gate where it arrives; up to `alternatives` gates far apart per pair;
  4. THE CHAIN: on this small graph (start + a few dozen patches) every cycle start → patch → patch … → start of
     up to `max_patches_in_chain` patches is enumerated (depth-first, cut off by the length). Its worth is the
     green metres walked inside its patches x their quality minus the unpleasant metres of its connectors. Inside a
     patch the way may be stretched (a detour or a loop inside it), so a chain fits when its shortest form is not too
     long and its stretched form not too short;
  5. REALISE: the best chains (one per set of patches) become real loops: connector ways as found, inside each
     patch the nicest way between its two gates – stretched by a turning point when length is left; ways already
     used cost extra; a loop that meets its own way gets its way home searched again around the way out. Then the
     same score sheet as A and B (suggest.numbers).

Knobs: weights.json "greenchain".
"""
import heapq
import math
import time

import nature as NA
import netsearch as NS

INF = float("inf")


def _find(parent, a):
    while parent[a] != a:
        parent[a] = parent[parent[a]]
        a = parent[a]
    return a


def label_green(G, cost, mws, W, S):
    """Per edge: green value 0..1 (0 = not green), bank flag."""
    C, GC, ND = W["classes"], W["greenchain"], W["nature"]
    green = [0.0] * len(G.edges)
    bank = bytearray(len(G.edges))
    kcache = {}
    for ei, e in enumerate(G.edges):
        if cost[ei] < 0:
            continue
        tid = e[5]
        if tid not in kcache:
            t = G.tags[tid]
            kcache[tid] = (S.edge_class(t, C), int(t.get("estimated_forest_class", "0") or 0),
                           t.get("route") == "ferry", S.rail_trail(t) and GC.get("rail_value", 0.0) > 0)
        k, fc, ferry, rail = kcache[tid]
        if k in "SG" or ferry or mws[ei] >= GC["green_max_main"]:
            continue
        mid = NS.mid_of(G, e)
        # (a rail trail is a green line of its own, also between houses: car-free, flat, away from the streets)
        if not rail and NA.built(mid, ND.get("built_radius_m", 45.0)) >= ND.get("built_from", 0.5):
            continue
        nat = NA.depth(mid, GC["green_radius_m"])
        wat = NA.water(mid, GC["bank_radius_m"]) >= GC["bank_from"]
        v = 0.0
        if nat >= GC["green_from"]:
            v = 0.5 + 0.5 * min(1.0, (nat - GC["green_from"]) / (1.0 - GC["green_from"]))
        if fc >= GC["green_forest_class"]:
            v = max(v, 0.6)
        if wat:
            v = max(v, GC["bank_value"])
            bank[ei] = 1
        if rail:
            v = max(v, GC["rail_value"])
        green[ei] = v
    return green, bank


def find_patches(G, cost, green, bank, W, s):
    """Connected green ways -> patches. Returns npatch (node -> patch id or -1), epatch (edge -> patch id or -1),
    patches [{id, green_m, value_m, bank_m, q, nodes, cx, cy}]."""
    GC = W["greenchain"]
    n = len(G.nodes)
    parent = list(range(n))
    is_green_node = bytearray(n)
    for ei, e in enumerate(G.edges):
        if green[ei] > 0:
            ra, rb = _find(parent, e[0]), _find(parent, e[1])
            if ra != rb:
                parent[ra] = rb
            is_green_node[e[0]] = is_green_node[e[1]] = 1
    joined = set()
    for ei, e in enumerate(G.edges):  # a short not-green piece between two green ones (a road crossing) joins them
        if green[ei] == 0 and cost[ei] >= 0 and e[2] <= GC["join_gap_m"] and is_green_node[e[0]] and is_green_node[e[1]]:
            ra, rb = _find(parent, e[0]), _find(parent, e[1])
            if ra != rb:
                parent[ra] = rb
            joined.add(ei)
    comp = {}
    for ei, e in enumerate(G.edges):
        if green[ei] > 0:
            r = _find(parent, e[0])
            c = comp.setdefault(r, {"green_m": 0.0, "value_m": 0.0, "bank_m": 0.0, "edges": []})
            c["green_m"] += e[2]
            c["value_m"] += e[2] * green[ei]
            c["bank_m"] += e[2] * bank[ei]
            c["edges"].append(ei)
    sxy = G.nodes[s]
    keep = [(r, c) for r, c in comp.items() if c["green_m"] >= GC["min_patch_m"]]
    # the most worthwhile patches in reach: by green metres (bank counts extra), nearer ones first on a tie
    keep.sort(key=lambda rc: -(rc[1]["value_m"] + GC["bank_bonus"] * rc[1]["bank_m"]))
    keep = keep[:GC["max_patches"]]
    root_id = {r: i for i, (r, c) in enumerate(keep)}
    npatch = [-1] * n
    for v in range(n):
        if is_green_node[v]:
            npatch[v] = root_id.get(_find(parent, v), -1)
    epatch = [-1] * len(G.edges)
    patches = []
    for i, (r, c) in enumerate(keep):
        xs = ys = 0.0
        for ei in c["edges"]:
            epatch[ei] = i
            a = G.nodes[G.edges[ei][0]]
            xs += a[0]
            ys += a[1]
        m = len(c["edges"])
        patches.append({"id": i, "green_m": c["green_m"], "value_m": c["value_m"], "bank_m": c["bank_m"],
                        "q": c["value_m"] / c["green_m"] + GC["bank_bonus"] * c["bank_m"] / c["green_m"],
                        "cx": xs / m, "cy": ys / m, "edges": c["edges"], "name": None})
    for ei in joined:
        e = G.edges[ei]
        if npatch[e[0]] >= 0 and npatch[e[0]] == npatch[e[1]]:
            epatch[ei] = npatch[e[0]]
    return npatch, epatch, patches


def connectors(G, cost, npatch, src_nodes, src_id, max_len, S, GC, n_alt):
    """One search from all nodes of a patch (or the start) over the rest: arrivals at other patches.
    -> {patch id: [ {cost, len, gp, gq, nodes, edges} ]} (up to n_alt per patch, gates >= gate_sep_m apart)."""
    dist, ln, prev, pe = {}, {}, {}, {}
    heap = []
    for v in src_nodes:
        dist[v] = 0.0
        ln[v] = 0.0
        heap.append((0.0, v))
    heapq.heapify(heap)
    adj, edges = G.adj, G.edges
    arrive = {}
    while heap:
        d, u = heapq.heappop(heap)
        if d > dist[u]:
            continue
        pu = npatch[u]
        if pu >= 0 and pu != src_id:
            arrive.setdefault(pu, []).append((d, ln[u], u))
            continue  # only neighbours are linked: the search does not go on through another patch
        lu = ln[u]
        if lu > max_len:
            continue
        for v, ei in adj[u]:
            c = cost[ei]
            if c < 0:
                continue
            if npatch[v] == src_id and src_id >= 0 and npatch[u] == src_id:
                continue  # stay out of the own patch (its nodes are sources already)
            nd = d + c
            if nd < dist.get(v, INF):
                dist[v] = nd
                ln[v] = lu + edges[ei][2]
                prev[v] = u
                pe[v] = ei
                heapq.heappush(heap, (nd, v))
    out = {}
    for q, arr in arrive.items():
        arr.sort()
        chosen = []
        for d, l, v in arr:
            if l <= 0:
                continue
            if any(S.dist(G.nodes[v], G.nodes[o[2]]) < GC["gate_sep_m"] for o in chosen):
                continue
            chosen.append((d, l, v))
            if len(chosen) >= n_alt:
                break
        res = []
        for d, l, v in chosen:
            nodes, es = [v], []
            t = v
            while t in prev:
                es.append(pe[t])
                t = prev[t]
                nodes.append(t)
            nodes.reverse()
            es.reverse()
            res.append({"cost": d, "len": l, "gp": nodes[0], "gq": v, "nodes": nodes, "edges": es})
        if res:
            out[q] = res
    return out


# While a loop is being put together: nodes already passed, and what touching one of them again costs (metres).
# A way that would cross or touch the loop so far is searched around it – an eight is no tour.
AVOID = {"nodes": None, "add": 0.0}


def dij_to(G, src, dst, cost, pen, factor, limit):
    """Search in the whole network from src to dst (stops there); ways in `pen` cost x factor, touching AVOID nodes
    costs extra. -> (nodes, edges) or None."""
    av, add = AVOID["nodes"], AVOID["add"]
    dist, prev, pe = {src: 0.0}, {}, {}
    heap = [(0.0, src)]
    adj = G.adj
    while heap:
        d, u = heapq.heappop(heap)
        if d > dist[u]:
            continue
        if u == dst:
            return _path(prev, pe, dst)
        if d > limit:
            return None
        for v, ei in adj[u]:
            c = cost[ei]
            if c < 0:
                continue
            if pen is not None and ei in pen:
                c *= factor
            nd = d + c
            if av is not None and v in av and v != dst:
                nd += add
            if nd < dist.get(v, INF):
                dist[v] = nd
                prev[v] = u
                pe[v] = ei
                heapq.heappush(heap, (nd, v))
    return None


def dij_patch(G, src, cost, epatch, pid, pen=None, factor=1.0):
    """Search inside one patch only (edges of that patch); touching AVOID nodes costs extra."""
    av, add = AVOID["nodes"], AVOID["add"]
    dist, ln, prev, pe = {src: 0.0}, {src: 0.0}, {}, {}
    heap = [(0.0, src)]
    adj, edges = G.adj, G.edges
    while heap:
        d, u = heapq.heappop(heap)
        if d > dist[u]:
            continue
        for v, ei in adj[u]:
            if epatch[ei] != pid:
                continue
            c = cost[ei]
            if c < 0:
                continue
            if pen is not None and ei in pen:
                c *= factor
            nd = d + c
            if av is not None and v in av:
                nd += add
            if nd < dist.get(v, INF):
                dist[v] = nd
                ln[v] = ln[u] + edges[ei][2]
                prev[v] = u
                pe[v] = ei
                heapq.heappush(heap, (nd, v))
    return dist, ln, prev, pe


def _path(prev, pe, t):
    nodes, es = [t], []
    while t in prev:
        es.append(pe[t])
        t = prev[t]
        nodes.append(t)
    nodes.reverse()
    es.reverse()
    return nodes, es


def _bearing(G, a, b):
    pa, pb = G.nodes[a], G.nodes[b]
    return math.degrees(math.atan2((pb[0] - pa[0]) * math.cos(math.radians(pa[1])), pb[1] - pa[1])) % 360.0


def _air(G, a, b):
    pa, pb = G.nodes[a], G.nodes[b]
    return math.hypot((pa[0] - pb[0]) * math.cos(math.radians(pa[1])), pa[1] - pb[1]) * 111320.0


def inner_loop(G, cost, epatch, pid, a, b, want, used, GC, dA, lA, pA, eA, variant):
    """A real loop inside a patch (the patch is the destination): a → w1 → w2 → b with three parts of about a third
    of `want` each; w1 = a pleasantly reached point a third away (variant k = the k-th such point in another
    direction), w2 = a point a third from w1 and a third from b, away from w1."""
    third = want / 3.0
    cands = [(dA[w] / lA[w] + 2.0 * abs(l - third) / third, w) for w, l in lA.items()
             if l > 0 and abs(l - third) <= GC["loop_tol"] * third and _air(G, a, w) >= 0.45 * l]
    if not cands:
        return None
    cands.sort()
    picked = []
    for val, w in cands:
        bw = _bearing(G, a, w)
        if all(min(abs(bw - o), 360 - abs(bw - o)) >= GC["loop_variant_deg"] for o in picked):
            picked.append(bw)
            if len(picked) == variant + 1:
                w1 = w
                break
    else:
        return None
    n1, e1 = _path(pA, eA, w1)
    pen = set(e1) | (used or set())
    d1, l1, p1, pe1 = dij_patch(G, w1, cost, epatch, pid, pen, GC["used_penalty"])
    if a != b:
        dB, lB, _pB, _eB = dij_patch(G, b, cost, epatch, pid, pen, GC["used_penalty"])
    else:
        dB, lB = dA, lA
    opts = []
    for w, l in l1.items():
        lb = lB.get(w)
        if lb is None or w in (a, b, w1) or l <= 0 or lb <= 0:
            continue
        if _air(G, w1, w) < 0.4 * third or _air(G, b, w) < 0.3 * third:
            continue
        val = abs(l - third) / third + abs(lb - third) / third + GC["turn_cost_weight"] * (d1[w] + dB[w]) / (l + lb)
        if val <= 1.2:
            opts.append((val, w))
    if not opts:
        return None
    opts.sort()
    # several second turning points are tried (well apart): the first loop whose three parts do not touch each other
    # wins – a loop inside a patch must not become an eight
    tried, best = [], None
    for val, w2 in opts:
        if any(_air(G, w2, o) < 0.25 * third for o in tried):
            continue
        tried.append(w2)
        n2, e2 = _path(p1, pe1, w2)
        pen2 = pen | set(e2)
        d3, l3, p3, pe3 = dij_patch(G, w2, cost, epatch, pid, pen2, GC["used_penalty"])
        if b not in d3:
            continue
        n3, e3 = _path(p3, pe3, b)
        s1, s2, s3 = set(n1[1:-1]), set(n2[1:-1]), set(n3[1:-1])
        touch = len(s1 & s2) + len(s2 & s3) + (len(s1 & s3) if a != b else 0) + (1 if w2 in s1 else 0)
        es = e1 + e2 + e3
        r = (n1 + n2[1:] + n3[1:], es, sum(G.edges[ei][2] for ei in es))
        if best is None or touch < best[0]:
            best = (touch, r)
        if touch == 0 or len(tried) >= GC["loop_tries"]:
            break
    return best[1] if best else None


def inner_way(G, cost, epatch, pid, a, b, want, used, GC, variant=0):
    """The way inside a patch from gate a to gate b, about `want` metres long (0 = the nicest direct way): by a
    turning point w inside the patch with len(a→w) + len(w→b) ≈ want – or, when much more length is wanted than the
    direct way has, a real loop inside the patch (inner_loop); ways already used cost extra.
    -> (nodes, edges, metres) or None."""
    dA, lA, pA, eA = dij_patch(G, a, cost, epatch, pid, used, GC["used_penalty"])
    if a != b and b not in dA:
        return None
    direct = lA.get(b, 0.0)
    if a != b and want <= direct * GC["stretch_from"]:
        n, e = _path(pA, eA, b)
        return n, e, direct
    if a == b and want < GC["min_inner_loop_m"]:
        return [a], [], 0.0
    if want >= GC["min_inner_loop_m"] * 3 and (a == b or want >= GC["loop_from"] * direct):
        r = inner_loop(G, cost, epatch, pid, a, b, want, used, GC, dA, lA, pA, eA, variant)
        if r is not None and abs(r[2] - want) > 0.12 * want:  # came out too long / short: once more, scaled
            r2 = inner_loop(G, cost, epatch, pid, a, b, want * want / r[2], used, GC, dA, lA, pA, eA, variant)
            if r2 is not None and abs(r2[2] - want) < abs(r[2] - want):
                r = r2
        if r is not None:
            return r
        if variant > 0:
            return None
    if a != b:
        dB, lB, pB, eB = dij_patch(G, b, cost, epatch, pid, used, GC["used_penalty"])
    else:
        dB, lB = dA, lA
    best = None
    for w, la in lA.items():
        lb = lB.get(w)
        if lb is None or w == a or w == b:
            continue
        tot = la + lb
        if tot > want * 1.25:
            continue
        # nearest to the wished length, the more pleasant of two alike; a turning point reached by a winding way is poor
        val = abs(tot - want) / max(want, 1.0) + GC["turn_cost_weight"] * (dA[w] + dB[w]) / max(tot, 1.0)
        if best is None or val < best[0]:
            best = (val, w)
    if best is None:
        if a == b:
            return [a], [], 0.0
        n, e = _path(pA, eA, b)
        return n, e, direct
    w = best[1]
    n1, e1 = _path(pA, eA, w)
    pen = set(e1) | (used or set())
    d2, l2, p2, pe2 = dij_patch(G, w, cost, epatch, pid, pen, GC["used_penalty"])
    if b not in d2:
        return None
    n2, e2 = _path(p2, pe2, b)  # in walking order w → b
    return n1 + n2[1:], e1 + e2, lA[w] + l2[b]


def patch_name(G, P, index, hits):
    """A patch's name from our map: the protected area most of its ways lie in, else the lake at its bank, else the
    nearest named place ("Wald bei X" / "Grün bei X"). Nothing invented."""
    if P["name"] is not None:
        return P["name"]
    res, lakes = {}, {}
    step = max(1, len(P["edges"]) // 250)
    for ei in P["edges"][::step]:
        e = G.edges[ei]
        mid = NS.mid_of(G, e)
        nm = NA.name_at(mid)
        if nm:
            res[nm] = res.get(nm, 0.0) + e[2]
        if index is not None:
            for i in hits[ei]:
                a = index.attr[i]
                if a["kind"] == "lake":
                    lakes[a["name"]] = lakes.get(a["name"], 0.0) + e[2]
    tot = sum(G.edges[ei][2] for ei in P["edges"][::step]) or 1.0
    name = ""
    if res:
        nm, m = max(res.items(), key=lambda x: x[1])
        if m >= 0.12 * tot:
            name = nm
    if not name and lakes:
        nm, m = max(lakes.items(), key=lambda x: x[1])
        if m >= 0.15 * tot:
            name = nm
    if not name:
        mid_e = G.edges[P["edges"][len(P["edges"]) // 2]]
        pl = NA.place_near([P["cx"], P["cy"]]) or NA.place_near(NS.mid_of(G, mid_e), 4000.0)
        if pl:
            forest = NA.depth([P["cx"], P["cy"]], 300) >= 0.5
            name = ("Ufer bei %s" if P["bank_m"] > 0.4 * P["green_m"] else "Wald bei %s" if forest else "Grün bei %s") % pl
    P["name"] = name or "Grünstück"
    return P["name"]


def short(name):
    for pre in ("Naturschutzgebiet ", "NSG "):
        if name.startswith(pre):
            name = name[len(pre):]
    return name.split(",")[0].strip()


def search(wish, W, kv, attr, index, rnd, S, service, picks=None, G=None):
    N = W["netsearch"]
    # a bank counts by the Wasser wish: without the theme a pond between houses is no destination
    GC = dict(W["greenchain"])
    GC["bank_value"] = GC["bank_value_dry"] + (GC["bank_value"] - GC["bank_value_dry"]) * wish["water"]
    GC["bank_bonus"] = GC["bank_bonus"] * wish["water"]
    W = dict(W, greenchain=GC)
    t0 = time.time()
    target_m = wish["hours"] * wish["pace"] * 1000.0
    band = W["score"]["length_flat_km"] * 1000.0
    if G is None:
        G = NS.prepare(wish, W, kv, index, S, service)
        t0 = time.time() - G.t_graph - G.t_values
    radius, t_graph = G.radius, G.t_graph
    cost, hits, mws = G.cost, G.hits, G.mws
    s = G.start
    green, bank = label_green(G, cost, mws, W, S)
    npatch, epatch, patches = find_patches(G, cost, green, bank, W, s)
    t_patch = time.time() - t0 - t_graph
    nP = len(patches)
    home = npatch[s]
    home_real = home >= 0
    if not home_real:
        home = nP  # the start is a patch of its own (one node)
        npatch[s] = home
    free_stem = W["score"]["free_stem_m"]
    max_conn = GC["max_connector_share"] * target_m

    # ---- connectors: one search per patch (and the start)
    conn = {}
    n_conn = 0
    by_patch = {}
    for v, p in enumerate(npatch):
        if p >= 0:
            by_patch.setdefault(p, []).append(v)
    for p in range(nP + (0 if home_real else 1)):
        src = by_patch.get(p, [])
        if not src:
            continue
        out = connectors(G, cost, npatch, src, p, max_conn, S, GC,
                         GC["alternatives_home"] if p == home else GC["alternatives"])
        for q, lst in out.items():
            conn[(p, q)] = lst
            n_conn += len(lst)
    # a connector found from the other side is as good: make the lists symmetric
    for (p, q), lst in list(conn.items()):
        back = conn.setdefault((q, p), [])
        for o in lst:
            if not any(S.dist(G.nodes[o["gq"]], G.nodes[b["gp"]]) < GC["gate_sep_m"] for b in back):
                back.append({"cost": o["cost"], "len": o["len"], "gp": o["gq"], "gq": o["gp"],
                             "nodes": o["nodes"][::-1], "edges": o["edges"][::-1]})
    nbr = {}
    for (p, q) in conn:
        nbr.setdefault(p, []).append(q)
    t_conn = time.time() - t0 - t_graph - t_patch

    def air(a, b):
        return S.dist(G.nodes[a], G.nodes[b])

    def cap(p):
        if p >= nP:
            return 0.0
        return min(patches[p]["green_m"] * GC["stretch_share"], GC["stretch_max_share"] * target_m)

    def q(p):
        return patches[p]["q"] ** GC["q_power"] if p < nP else 0.0

    # ---- chains: depth-first over the patch graph
    lam = GC["connector_weight"] * (1.0 + GC["connector_natur"] * wish["forest"])
    found = {}
    steps = [0]
    max_len = target_m + band + GC["length_slack"] * target_m

    def close(order, legs, L, V):
        caps = sum(cap(p) for p in set(order))
        if L + caps < target_m - band - GC["length_slack"] * target_m:
            return
        need = max(0.0, target_m - L)
        # the stretch goes to the best patches first
        v = V
        rest = need
        for p in sorted(set(order), key=lambda p: -q(p)):
            take = min(rest, cap(p))
            v += q(p) * take
            rest -= take
        score = v / target_m - GC["short_penalty"] * rest / target_m
        key = frozenset(order)
        if key not in found or score > found[key][0]:
            found[key] = (score, list(order), list(legs), L)

    def dfs(c, g, order, legs, L, V, visited):
        steps[0] += 1
        if steps[0] > GC["max_steps"]:
            return
        for nq in nbr.get(c, ()):
            closing = nq == home
            if not closing and (nq in visited or len(order) >= GC["max_patches_in_chain"] + 1):
                continue
            if closing and len(legs) == 0:
                continue
            for o in conn[(c, nq)]:
                inner = 0.0 if c >= nP else GC["inner_factor"] * air(g, o["gp"])
                if c == home and not home_real:
                    inner = 0.0
                if c != home and o["gp"] == g and not (closing and len(order) == 2):
                    continue  # in and out by the same gate: not a walk through the patch
                if closing and len(legs) == 1 and o is not None:
                    first = legs[0]
                    # one patch only: the way home must not be the way out (a stem up to free_stem is fine)
                    if air(first["gq"], o["gp"]) < GC["gate_sep_m"] and first["len"] > free_stem:
                        continue
                    if air(first["gp"], o["gq"]) < 1.0 and air(first["gq"], o["gp"]) < 1.0 and first["len"] > free_stem:
                        continue
                L2 = L + inner + o["len"]
                if L2 > max_len:
                    continue
                V2 = V + q(c) * inner - lam * (o["cost"] - o["len"])
                if closing:
                    L3, V3 = L2, V2
                    if home_real:
                        back = GC["inner_factor"] * air(o["gq"], s)
                        L3 += back
                        V3 += q(home) * back
                    if L3 <= max_len:
                        close(order, legs + [o], L3, V3)
                else:
                    visited.add(nq)
                    dfs(nq, o["gq"], order + [nq], legs + [o], L2, V2, visited)
                    visited.discard(nq)

    dfs(home, s, [home], [], 0.0, 0.0, {home})
    # the home patch alone (the start lies in a patch big enough): a loop inside it
    if home_real and cap(home) + 0.0 >= target_m - band - GC["length_slack"] * target_m:
        found[frozenset([home])] = (q(home), [home], [], 0.0)
    # the chains to realise: the best first, each further one must differ from those taken (at most
    # realise_max_common of its patches in common) – else the dozen best are one loop with small changes
    chains = []
    for ch in sorted(found.values(), key=lambda x: -x[0]):
        ps = set(p for p in ch[1] if p < nP)
        if not ps:
            continue
        if any(len(ps & o) / float(len(ps | o)) > GC.get("realise_max_common", 1.0) for o in (set(p for p in c2[1] if p < nP) for c2 in chains)):
            continue
        chains.append(ch)
        if len(chains) >= GC["realise"]:
            break
    if not chains:
        chains = sorted(found.values(), key=lambda x: -x[0])[:GC["realise"]]
    t_chain = time.time() - t0 - t_graph - t_patch - t_conn

    # ---- realise
    built = []
    max_cross = W["select"]["max_self_cross"]
    keep_km = S.length_limit_km(wish, W, N["keep_length_off"])
    dropped = {"cross": 0, "length": 0, "inner": 0}
    merge = W["score"]["selfcross_merge_m"]

    full_cache = {}  # a connector searched in the whole network: once per pair of gates

    def realise(order, legs, want_extra, variant=0):
        """-> (nodes, edges, metres per patch visit). want_extra = {index in order: extra metres inside that patch}."""
        nodes, edges = [s], []
        used = set()
        touched = set()
        acc = [0.0]
        AVOID["add"] = GC["touch_cost_share"] * target_m

        def push(n, e):
            for v, ei in zip(n[1:], e):
                acc[0] += G.edges[ei][2]
                if acc[0] > free_stem:
                    used.add(ei)
                    touched.add(v)
            nodes.extend(n[1:])
            edges.extend(e)

        def avoid(*free):
            a = set(touched)
            for v in free:
                a.discard(v)
            AVOID["nodes"] = a

        inner_m = {}
        g = s
        for i, p in enumerate(order):
            leg = legs[i] if i < len(legs) else None
            out_gate = leg["gp"] if leg else s
            if p < nP:
                vs = variant if want_extra and i == max(want_extra, key=lambda k: want_extra[k]) else 0
                want = want_extra.get(i, 0.0)
                avoid(g, out_gate)
                r = inner_way(G, cost, epatch, p, g, out_gate, want, used, GC, vs)
                AVOID["nodes"] = None
                if r is None:
                    return None
                push(r[0], r[1])
                inner_m[i] = r[2]
                inner_m.setdefault("_seg", {})[i] = r[0]
            if leg:
                ln_, le_ = leg["nodes"], leg["edges"]
                if GC.get("connector_full_search", True) or (GC["touch_cost_share"] > 0 and any(v in touched for v in ln_[1:])):
                    # the connector again, in the whole network: the stored one was found outside all patches – now a
                    # green way through another patch may be taken when it is not much longer (owner 2026-11-13:
                    # "der Rückweg durch Hassels, warum nicht durch den Hasseler Forst"; "warum durch Werne, es ist
                    # viel Grün drum rum"); ways already walked cost extra
                    ck = (leg["gp"], leg["gq"])
                    if GC["touch_cost_share"] <= 0 and ck in full_cache:
                        r = full_cache[ck]
                        if r is not None:
                            ln_, le_ = r
                        push(ln_, le_)
                        g = leg["gq"]
                        continue
                    if GC["touch_cost_share"] > 0:
                        avoid(leg["gp"])
                    r = dij_to(G, leg["gp"], leg["gq"], cost, used if GC["touch_cost_share"] > 0 else None, GC["used_penalty"],
                               GC["reroute_limit"] * max(leg["cost"], 1.0) + AVOID["add"])
                    AVOID["nodes"] = None
                    if GC["touch_cost_share"] <= 0:
                        full_cache[ck] = r
                    if r is not None:
                        ln_, le_ = r
                push(ln_, le_)
                g = leg["gq"]
        if home_real and len(order) > 1:  # back inside the home patch to the start
            avoid(g, s)
            r = inner_way(G, cost, epatch, home, g, s, want_extra.get("home", 0.0), used, GC,
                          variant if want_extra and max(want_extra, key=lambda k: want_extra[k]) == "home" else 0)
            AVOID["nodes"] = None
            if r is None:
                return None
            push(r[0], r[1])
            inner_m["home"] = r[2]
            inner_m.setdefault("_seg", {})["home"] = r[0]
        return nodes, edges, inner_m

    def total_len(edges):
        return sum(G.edges[ei][2] for ei in edges)

    jobs = []
    log = []

    def note(what):
        log.append("%s v%d est %.2f: %s" % (" > ".join(short(patch_name(G, patches[p], index, hits)) if p < nP else "Start" for p in cur[1]), cur[3], cur[0], what))
    cur = None
    for score, order, legs, L in chains:
        # a chain of one or two patches is mostly a loop inside a patch: offer it in several directions
        nreal = sum(1 for p in set(order) if p < nP)
        for variant in range(GC["loop_variants"] if nreal <= 2 else 1):
            jobs.append((score, order, legs, variant))
    seen_loops = set()
    for score, order, legs, variant in jobs:
        cur = (score, order, legs, variant)
        # first pass: direct ways inside the patches; then give the missing length to the best patches
        want = {}
        res = realise(order, legs, want)
        if res is None:
            dropped["inner"] += 1; note("innen kein Weg")
            continue
        fitted = False
        for _ in range(GC["fit_passes"]):
            missing = target_m - total_len(res[1])
            if abs(missing) <= band * 0.6 or (missing < 0 and not want):
                break
            if missing < 0:  # the stretch came out too long: take it back where most was added
                i = max(want, key=lambda k: want[k])
                want[i] = max(0.0, want[i] + missing)
                r2 = realise(order, legs, want, variant)
                if r2 is None:
                    break
                res = r2
                continue
            slots = [(i, p) for i, p in enumerate(order) if p < nP]
            if home_real and len(order) > 1:
                slots.append(("home", home))
            slots.sort(key=lambda ip: -q(ip[1]))
            rest = missing
            for i, p in slots:
                have = res[2].get(i, 0.0)
                take = min(rest, max(0.0, cap(p) - max(0.0, want.get(i, 0.0) - have)))
                if take <= 0:
                    continue
                want[i] = have + take
                rest -= take
                if rest <= 0:
                    break
            r2 = realise(order, legs, want, variant)
            if r2 is None:
                break
            res = r2
            fitted = True
        if variant > 0 and not fitted:
            continue
        nodes, edges, _ = res
        sig = (len(edges), sum(edges))
        if sig in seen_loops:
            continue
        seen_loops.add(sig)
        # the loop's name: per patch the part really walked decides – the protected area it runs in, else the lake at
        # its bank, else the place nearest to the middle of the walked part ("Wald bei X"); a patch walked only a few
        # steps is not named
        names = []
        segs = res[2].get("_seg", {})
        for i, p in enumerate(order):
            if p >= nP:
                continue
            seg = segs.get(i) or (segs.get("home") if p == home else None)
            nm = ""
            if seg and len(seg) >= 3:
                votes = {}
                for v in seg[::max(1, len(seg) // 40)]:
                    r_ = NA.name_at(G.nodes[v])
                    if r_:
                        votes[r_] = votes.get(r_, 0) + 1
                if votes:
                    best_nm, cnt = max(votes.items(), key=lambda x: x[1])
                    if cnt >= 0.3 * len(seg[::max(1, len(seg) // 40)]):
                        nm = best_nm
                if not nm:
                    P = patches[p]
                    cached = patch_name(G, P, index, hits)
                    if P["bank_m"] >= 0.3 * P["green_m"] or not cached.startswith(("Wald bei", "Grün bei", "Grünstück")):
                        nm = cached
                    else:
                        mid = G.nodes[seg[len(seg) // 2]]
                        pl = NA.place_near(mid, 3000.0)
                        nm = ("Wald bei %s" if NA.depth(mid, 300) >= 0.5 else "Grün bei %s") % pl if pl else cached
            elif not seg:
                nm = patch_name(G, patches[p], index, hits)
            nm = short(nm) if nm else ""
            if nm and nm not in names and "Grünstück" not in nm:
                names.append(nm)
        gates = [list(G.nodes[l["gq"]][:2]) for l in legs]
        cx = sum(G.nodes[v][0] for v in nodes) / len(nodes)
        cy = sum(G.nodes[v][1] for v in nodes) / len(nodes)
        extra = {"bearing": round(S.bearing(G.nodes[s], [cx, cy])), "shape": "kette", "mode": "chain",
                 "target_name": " – ".join(names[:4]) or None, "vias": gates[:8],
                 "chain": {"patches": [p for p in order if p < nP], "est_score": round(score, 3)}}
        c = NS.to_candidate(G, nodes, edges, wish, W, S, extra)
        if not c:
            continue
        cross = S.self_crossings(c["edges"], None, free_stem, merge)
        if cross > 0 and legs:
            # the way home again, around everything walked so far (ways at nodes already passed cost extra)
            if not home_real:
                k = len(nodes) - len(legs[-1]["nodes"])  # the node where the last connector (the way home) begins
                pen = set()
                for v in set(nodes[:k]):
                    if v == s:
                        continue
                    for _w, ei in G.adj[v]:
                        pen.add(ei)
                d3, p3, pe3, l3 = NS.dijkstra(G, nodes[k], cost, pen=pen, stop=s, factor=GC["avoid_penalty"])
                if d3[s] < INF:
                    n3, e3 = NS.path_to(p3, pe3, s)
                    c2 = NS.to_candidate(G, nodes[:k + 1] + n3[1:], edges[:k] + e3, wish, W, S, extra)
                    if c2:
                        cross2 = S.self_crossings(c2["edges"], None, free_stem, merge)
                        if cross2 < cross:
                            c, cross = c2, cross2
        if S.length_off_km(c, wish) > keep_km:
            dropped["length"] += 1; note("Länge %.1f km" % c["km"])
        elif cross > max_cross:
            dropped["cross"] += 1; note("kreuzt %d" % cross)
        else:
            # a chain through a patch that is mostly bank is a "lake" loop: with "am Wasser entlang" one is always shown
            lakey = any(p < nP and patches[p]["bank_m"] >= 1000.0 and patches[p]["bank_m"] >= 0.3 * patches[p]["green_m"] for p in order)
            c["theme"] = "lake" if lakey else "chain"
            built.append(c); note("ok %.1f km" % c["km"])
    info = {"graph_nodes": len(G.nodes), "graph_edges": len(G.edges), "graph_s": round(t_graph, 2),
            "radius_m": int(radius), "patches": nP, "home_in_patch": home_real, "connectors": n_conn,
            "patch_s": round(t_patch, 2), "connector_s": round(t_conn, 2), "chain_steps": steps[0],
            "chains_found": len(found), "chain_s": round(t_chain, 2), "chains_realised": len(chains),
            "loops_kept": len(built), "dropped_crossing": dropped["cross"], "dropped_length": dropped["length"],
            "dropped_inner": dropped["inner"], "searches": nP + 1,
            "green_km": round(sum(p["green_m"] for p in patches) / 1000.0, 1),
            "chain_log": log, "search_s": round(time.time() - t0 - t_graph, 2),
            "top_patches": [{"name": short(patch_name(G, p, index, hits)), "green_km": round(p["green_m"] / 1000.0, 1),
                             "bank_km": round(p["bank_m"] / 1000.0, 1)} for p in patches[:8]]}
    return built, info, G
