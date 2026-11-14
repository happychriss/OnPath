"""Building loops – one pool, one assembly routine.

Every loop is a list of turning points handed to `assemble`: start → point → … → start, each part the cheapest way
(place.cost = how good a way is here), ways already in the loop cost more and so do ways touching them – that keeps
the way home apart from the way out, so nothing is walked twice or crossed. The generators differ only in how they
choose the points:
  turning points  the most pleasant point per direction at the right distance, alone or in pairs;
                  when a climb is wished, also points high enough and near enough for the time
  chains          the stand-out areas of the place, in order around the start, each crossed through its heart
  lines           follow a known route, a rail trail or a bank, come home another way
A loop inside one big area needs no generator of its own: there the pleasant turning points lie inside it.
The wished time is a range: turning points are taken across all of it.
"""
from net import bearing, dijkstra, dist, path, INF


def assemble(P, S, points, costs=None):
    """-> the loop's edges in walking order, or None. costs = {part index: cost list} for a part that follows a line."""
    net, F, Q = P.net, P.F, S["search"]
    free = P.ch.family["free_stem_m"]
    pen, edges, cur, walked = {}, [], net.start, 0.0
    for i, w in enumerate(points + [net.start]):
        cost = (costs or {}).get(i, P.cost)
        if i == 0 and cost is P.cost:
            leg = path(P.prev0, P.pe0, w)
        else:
            d, prev, pe, _ = dijkstra(net, cur, cost, pen, stop=w)
            leg = path(prev, pe, w) if d[w] < INF else None
        if leg is None or (not leg and w != cur):
            return None
        node = cur
        for ei in leg:
            walked += F.ln[ei]
            node = net.other(ei, node)
            if walked > free:  # the first stretch from the start stays free: a short way out and back is fine
                pen[ei] = Q["used"]
                for _, e2 in net.adj[node]:
                    pen.setdefault(e2, Q["beside_used"])
        edges += leg
        cur = w
    out = []
    for ei in edges:  # a spur walked out and straight back (to a turning point off the way) is cut
        if out and out[-1] == ei:
            out.pop()
        else:
            out.append(ei)
    return out if len(out) >= 3 else None


def measure(P, edges):
    """The plain measures of a loop: nodes, metres, ascent, hours, metres walked twice beyond the free stem, and how
    often it meets its own way (a crossing, an eight's middle, a shared stretch = one meeting each; the start, the
    stem and a touch within the free stem do not count)."""
    net, F, ch = P.net, P.F, P.ch
    nodes, at = [net.start], [0.0]
    for ei in edges:
        nodes.append(net.other(ei, nodes[-1]))
        at.append(at[-1] + F.ln[ei])
    if nodes[-1] != net.start:
        return None
    up, ref = 0.0, None
    for x in nodes:
        ele = net.nodes[x][2]
        if ele < -1000:  # no height here (bridges, tunnels)
            continue
        if ref is None or ele < ref - 6.0:
            ref = ele
        elif ele > ref + 6.0:
            up, ref = up + ele - ref, ele
    free, total = ch.family["free_stem_m"], at[-1]
    stem = 0
    while stem < len(edges) // 2 and edges[stem] == edges[-1 - stem]:
        stem += 1
    count = {}
    for ei in edges:
        count[ei] = count.get(ei, 0) + 1
    doubled = sum(F.ln[e] * (c - 1) for e, c in count.items() if c > 1)
    seen, cross, in_run, last = {}, 0, False, -1e9
    for i in range(stem, len(nodes) - 1 - stem):
        j = seen.get(nodes[i])
        if j is not None and i - j > 1 and not (at[j] <= free and total - at[i] <= free):
            cross += not in_run and at[i] - last > 150.0
            in_run, last = True, at[i]
        else:
            in_run = False
        seen[nodes[i]] = i
    return {"edges": edges, "nodes": nodes, "m": total, "up": up, "hours": ch.time(total, up), "stem_m": at[stem],
            "doubled_m": max(0.0, doubled - min(at[stem], free)), "cross": int(cross)}


def pool(P, S):
    """All loops worth judging for this wish: [{edges, nodes, m, up, hours, …, origin, aim}]."""
    net, ch, fam, Q = P.net, P.ch, P.ch.family, S["search"]
    lo, hi, T = ch.min_m, ch.max_m, ch.target_m
    start = net.nodes[net.start]
    out, seen = [], set()

    def add(points, origin, aim=(), costs=None):
        """Assemble, measure, keep if it fits. -> its measures (also when it does not fit), None if nothing new came."""
        edges = assemble(P, S, points, costs)
        m = measure(P, edges) if edges and frozenset(edges) not in seen else None
        if m:
            seen.add(frozenset(edges))
            if m["cross"] <= S["form"]["max_cross"] and (1 - S["form"]["short_zero"]) * ch.hours[0] <= m["hours"] \
                    <= (1 + S["form"]["long_zero"]) * ch.hours[1] and m["up"] <= ch.up[1]:
                out.append(dict(m, origin=origin, aim=list(aim)))
        return m

    reached = [v for v in range(len(net.nodes)) if 0 < P.len0[v] < hi and P.d0[v] < INF]
    bear = {v: bearing(start, net.nodes[v]) for v in reached}

    def anchors(fits):
        """Per direction the node that fits and is reached most pleasantly."""
        best = {}
        for v in reached:
            if fits(v):
                k, r = int(bear[v] * fam["sectors"] / 360.0), P.d0[v] / P.len0[v]
                if k not in best or r < best[k][0]:
                    best[k] = (r, v)
        return [v for _, v in best.values()]

    # --- turning points: one per direction (at the short and the long end of the range), then pairs a quarter turn apart
    for out_m in ((0.45 * T,) if hi < 1.25 * lo else (0.45 * lo, 0.45 * hi)):
        for v in anchors(lambda v: 0.9 * out_m <= P.len0[v] <= 1.1 * out_m):
            add([v], "Wendepunkt")
    if ch.up[0] >= 100.0:  # a climb is wished: turning points high enough, and near enough for the time
        gain = lambda v: net.nodes[v][2] - start[2]
        for v in anchors(lambda v: 0.8 * ch.up[0] <= gain(v) <= ch.up[1]
                         and ch.hours[0] <= ch.time(2.1 * P.len0[v], gain(v)) <= ch.hours[1]):
            add([v], "Anstieg")
    A = anchors(lambda v: 0.27 * lo <= P.len0[v] <= 0.36 * hi)
    pairs = []
    for a in A:
        est = lambda b: P.len0[a] + 1.3 * dist(net.nodes[a], net.nodes[b]) + P.len0[b]
        fit = lambda b: max(0.0, lo - est(b), est(b) - hi)
        side = [b for b in A if 40.0 <= (bear[b] - bear[a]) % 360.0 <= 110.0]
        if side:
            pairs.append((fit(min(side, key=fit)), a, min(side, key=fit)))
    for _, a, b in sorted(pairs)[:fam["pairs"]]:
        add([a, b], "Wendepunkte")

    # --- chains through the stand-out areas, in order around the start, each through its heart
    areas = sorted(P.areas, key=lambda a: bearing(start, a["p"]))
    chains = []
    for i in range(len(areas)):
        for k in range(1, min(4, len(areas)) + 1):
            seq = [areas[(i + j) % len(areas)] for j in range(k)]
            if k == 1:
                turn = min(seq[0]["nodes"], key=lambda v: abs(P.len0[v] - 0.45 * T))
                pts = [seq[0]["heart"], turn] if P.len0[seq[0]["heart"]] < P.len0[turn] - 200.0 else [turn]
                est = 2.1 * P.len0[turn]
            else:
                pts = [a["heart"] for a in seq]
                est = P.len0[pts[0]] + P.len0[pts[-1]] + 1.35 * sum(dist(net.nodes[x], net.nodes[y]) for x, y in zip(pts, pts[1:]))
            if 0.7 * lo <= est <= 1.25 * hi:
                chains.append((-sum(a["m"] * a["good"] for a in seq), i, k, pts, [a["name"] for a in seq]))
    for _, _, _, pts, names in sorted(chains)[:fam["chains"]]:
        add(pts, "Kette", names)

    # --- lines: join at the nearest point, follow it (both ways), come home another way
    for ln in P.lines[:fam["lines"]]:
        entry = ln["near"]
        if P.len0[entry] > 0.35 * T:
            continue
        along_cost = list(P.cost)
        for e in ln["edges"]:
            along_cost[e] *= Q["line"]
        d, _, _, along = dijkstra(net, entry, along_cost, max_len=0.75 * hi)
        on = [v for v in ln["nodes"] if d[v] < INF and v != entry and v in bear]
        if not on:
            continue
        turn = lambda u, v: abs((bearing(net.nodes[entry], net.nodes[u]) - bearing(net.nodes[entry], net.nodes[v]) + 180.0) % 360.0 - 180.0)
        half = lambda v: abs(P.len0[entry] + along[v] - 0.5 * T)  # half the loop on the line, as a first guess
        first = min(on, key=half)
        other = [v for v in on if turn(v, first) >= 90.0]
        for v in [first] + ([min(other, key=half)] if other else []):
            for _ in range(2):  # the way home is not known before it is built: build, then move the turning point once
                m = add([entry, v], "Linie", [ln["name"]], {1: along_cost})
                if not m or lo <= m["m"] <= hi:
                    break
                want, same = along[v] + 0.5 * (T - m["m"]), [u for u in on if turn(u, v) < 60.0]
                v = min(same, key=lambda u: abs(along[u] - want))
    return out
