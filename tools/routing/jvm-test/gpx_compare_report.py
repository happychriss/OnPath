#!/usr/bin/env python3
"""Report step of GpxCompare.kt: overlap, way-type / surface mixes, deviations (with GPX and PNG), variant tables.

  python3 -I gpx_compare_report.py report <runDir> --md <out.md> [--devdir DIR] [--plots 15]
  python3 -I gpx_compare_report.py table <label>=<runDir> ... --md <out.md>

<runDir> = GpxCompare's CMP_OUT (one sub folder per reference line). Pure standard library, matplotlib only for plots.

Definitions (all from BRouter way tags, per 10 m sample):
- overlap ref = share of the reference within 20 m of our line; overlap ours = share of ours within 20 m of the
  reference. length ratio = our km / reference km.
- bike way = highway=cycleway, or path / footway / bridleway / track with bicycle=designated, or bicycle_road /
  cyclestreet. main road = primary / secondary / trunk (+ _link), not a bike way. on cycle route = route_bicycle_*.
- pushing = OnPath's RouteWarnings PUSH rule (bicycle=dismount; footway / path with bicycle=no; footway / pedestrian
  without bike access and not on a signed cycle route – a pedestrian zone also on one; path of SAC >= T2 or
  mtb:scale >= S2), runs >= 50 m, as for Touring / Trekking / E-bike.
- unpaved = gravel / ground / dirt / sand / grass / unpaved / pebblestone / mud / compacted / fine_gravel surface, or a
  track grade2..5 without a surface tag ("firm" = compacted / fine_gravel / grade2, the rest "loose").
- way types = the app's own classes (app_waytype, mirror of BRouterWayStats.wayType); surfaces = surf_class groups.
- deviation = our line > 30 m from the reference for >= 300 m (gaps < 60 m joined), or a reference stretch we left
  out; paired with the other line between the nearest shared points; classified by comparing the two tag mixes.
"""
import argparse
import glob
import gzip
import json
import math
import os
import re
import sys
from collections import defaultdict

NEAR = 20.0
DEV_FAR = 30.0
DEV_GAP = 60.0
DEV_MIN = 300.0
STEP = 10.0
ROADS = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link",
         "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "road"}
MAIN = {"trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link"}
LOOSE = {"gravel", "ground", "dirt", "sand", "grass", "unpaved", "pebblestone", "mud", "earth", "clay"}
FIRM = {"compacted", "fine_gravel"}
COBBLE = {"sett", "cobblestone"}
BIKE_OK = {"yes", "designated", "permissive", "mtb"}
SAC = {"hiking": 1, "mountain_hiking": 2, "demanding_mountain_hiking": 3, "alpine_hiking": 4,
       "demanding_alpine_hiking": 5, "difficult_alpine_hiking": 6}


def parse_tags(s):
    d = {}
    for kv in (s or "").split(" "):
        i = kv.find("=")
        if i > 0 and i < len(kv) - 1:
            d[kv[:i]] = kv[i + 1:]
    return d


def app_waytype(t):
    """Mirror of BRouterWayStats.wayType."""
    if t.get("route") == "ferry":
        return "ferry"
    hw = t.get("highway")
    if hw is None:
        return "other"
    if hw == "steps":
        return "steps"
    if hw == "cycleway":
        return "cycleway"
    if hw in ("path", "footway", "bridleway", "pedestrian"):
        if t.get("bicycle") == "designated" and hw != "pedestrian":
            return "cycleway"
        return "footway" if hw in ("footway", "pedestrian") else "path"
    if hw == "via_ferrata":
        return "path"
    if hw == "track":
        return "track"
    if hw in ("residential", "service", "unclassified", "living_street", "road"):
        return "quiet_road"
    if hw in ("tertiary", "tertiary_link"):
        tc = t.get("estimated_traffic_class")
        return "quiet_road" if tc and tc.isdigit() and int(tc) <= 3 else "road"
    if hw in ("secondary", "secondary_link"):
        return "road"
    if hw in ("primary", "primary_link", "trunk", "trunk_link", "motorway", "motorway_link"):
        return "busy_road"
    return "other"


def is_push(t):
    """Mirror of RouteWarnings PUSH for Touring / Trekking / E-bike (per span; the 50 m minimum is applied later)."""
    wt = app_waytype(t)
    if wt == "steps":
        return False
    b = t.get("bicycle")
    if b == "dismount":
        return True
    if wt not in ("footway", "path"):
        return False
    if b in BIKE_OK:
        return False
    if b == "no":
        return True
    if wt == "footway":
        # a footway on a signed cycle route is ridden (owner 2026-10-25), a pedestrian zone not (2026-10-26)
        return not cycleroute(t) or t.get("highway") == "pedestrian"
    m = t.get("mtb:scale", "")
    mtb = int(m[0]) if m[:1].isdigit() else -1
    return SAC.get(t.get("sac_scale"), 0) >= 2 or mtb >= 2


def bikeway(t):
    hw = t.get("highway")
    return (hw == "cycleway" or (hw in ("path", "footway", "bridleway", "track") and t.get("bicycle") == "designated")
            or t.get("bicycle_road") == "yes" or t.get("cyclestreet") == "yes")


def road_bike_lane(t):
    for k in ("cycleway", "cycleway:left", "cycleway:right", "cycleway:both"):
        if t.get(k) in ("lane", "track", "shared_lane", "opposite_lane", "opposite_track", "share_busway"):
            return True
    return any(t.get(k) in ("yes", "designated") for k in ("sidewalk:bicycle", "sidewalk:left:bicycle", "sidewalk:right:bicycle"))


def cycleroute(t):
    return any(t.get("route_bicycle_" + n) == "yes" for n in ("icn", "ncn", "rcn", "lcn"))


def wrongway(t):
    rev = t.get("reversedirection") == "yes"
    ow = t.get("oneway")
    bad = (rev and ow in ("yes", "true", "1")) or (not rev and ow == "-1")
    if not bad or t.get("oneway:bicycle") == "no":
        return False
    if any(t.get(k, "").startswith("opposite") for k in ("cycleway", "cycleway:left", "cycleway:right")):
        return False
    if any(t.get(k) in ("no", "-1") for k in ("cycleway:left:oneway", "cycleway:right:oneway")):
        return False
    return True


def surf_class(t):
    """unpaved firm / loose / cobble / paved / unknown (our own surface groups)."""
    s = t.get("surface")
    if s in FIRM:
        return "firm"
    if s in LOOSE:
        return "loose"
    if s in COBBLE:
        return "cobble"
    if s:
        return "paved"
    tt = t.get("tracktype")
    if t.get("highway") == "track" and tt:
        return {"grade1": "paved", "grade2": "firm"}.get(tt, "loose")
    if t.get("highway") in ROADS:
        return "paved"
    return "unknown"


METRICS = ("bikeway", "cycleroute", "mainroad", "mainroad_nolane", "traffic_hi", "push", "steps", "unpaved", "firm",
           "loose", "cobble", "ferry", "wrongway", "untagged")


class TagInfo:
    __slots__ = ("t", "feat", "surf", "app")

    def __init__(self, s):
        t = parse_tags(s)
        self.t = t
        sc = surf_class(t)
        bw = bikeway(t)
        mr = t.get("highway") in MAIN and not bw
        tc = t.get("estimated_traffic_class", "")
        self.feat = {
            "bikeway": bw, "cycleroute": cycleroute(t), "mainroad": mr, "mainroad_nolane": mr and not road_bike_lane(t),
            "traffic_hi": (not bw) and tc.isdigit() and int(tc) >= 5, "push": is_push(t),
            "steps": t.get("highway") == "steps", "unpaved": sc in ("firm", "loose"), "firm": sc == "firm",
            "loose": sc == "loose", "cobble": sc == "cobble", "ferry": t.get("route") == "ferry", "wrongway": wrongway(t),
            "untagged": False,
        }
        self.surf = sc
        self.app = app_waytype(t)


UNTAGGED = None


def load_samples(path, ncols):
    """-> (tags dict id->TagInfo, rows list of tuples)"""
    tags, rows = {}, []
    with gzip.open(path, "rt") as f:
        for line in f:
            c = line.rstrip("\n").split("\t")
            if c[0] == "T":
                tags[int(c[1])] = TagInfo(c[2] if len(c) > 2 else "")
                continue
            rows.append((float(c[0]), float(c[1]), float(c[2]), int(c[3])) + tuple(float(x) for x in c[4:4 + ncols]))
    return tags, rows


def load_refx(path):
    with gzip.open(path, "rt") as f:
        return [tuple(float(x) for x in l.split("\t")) for l in f if l.strip()]


def read_tsv(paths):
    out = []
    for p in sorted(paths):
        with open(p, encoding="utf-8") as f:
            head = f.readline().rstrip("\n").split("\t")
            for l in f:
                if l.strip():
                    out.append(dict(zip(head, l.rstrip("\n").split("\t"))))
    return out


def push_mask(infos):
    """PUSH per sample with the app's 50 m minimum per run."""
    m = [bool(i and i.feat["push"]) for i in infos]
    out = [False] * len(m)
    k = 0
    while k < len(m):
        if not m[k]:
            k += 1
            continue
        j = k
        while j < len(m) and m[j]:
            j += 1
        if (j - k) * STEP >= 50:
            for x in range(k, j):
                out[x] = True
        k = j
    return out


def mix(infos, push=None):
    """metres per metric / app way type / surface group for a list of TagInfo (None = untagged)."""
    r = defaultdict(float)
    for n, i in enumerate(infos):
        if i is None:
            r["m:untagged"] += STEP
            r["a:unknown"] += STEP
            r["s:unknown"] += STEP
            continue
        for k, v in i.feat.items():
            if v and k != "push":
                r["m:" + k] += STEP
        r["a:" + i.app] += STEP
        r["s:" + i.surf] += STEP
    if push is not None:
        r["m:push"] = STEP * sum(push)
    r["len"] = STEP * len(infos)
    return r


def runs_of(mask, minlen_samples):
    out = []
    k = 0
    while k < len(mask):
        if not mask[k]:
            k += 1
            continue
        j = k
        while j < len(mask) and mask[j]:
            j += 1
        out.append([k, j - 1])
        k = j
    merged = []
    for r in out:
        if merged and (r[0] - merged[-1][1] - 1) * STEP < DEV_GAP:
            merged[-1][1] = r[1]
        else:
            merged.append(r)
    return [r for r in merged if (r[1] - r[0] + 1) >= minlen_samples]


def frac(m, k):
    return m.get(k, 0.0) / m["len"] if m.get("len") else 0.0


def short_mix(m):
    if not m.get("len"):
        return "-"
    parts = []
    for k in ("a:cycleway", "a:road", "a:busy_road", "a:quiet_road", "a:track", "a:path", "a:footway", "a:ferry"):
        f = frac(m, k)
        if f >= 0.1:
            parts.append("%s %d%%" % (k[2:], round(100 * f)))
    for k, lab in (("m:cycleroute", "route"), ("m:push", "push"), ("m:unpaved", "unpaved"), ("m:cobble", "cobbles"),
                   ("m:mainroad_nolane", "main w/o lane"), ("m:wrongway", "wrong-way")):
        f = frac(m, k)
        if f >= 0.1:
            parts.append("%s %d%%" % (lab, round(100 * f)))
    return ", ".join(parts) or "mixed"


def classify(o, r, om, rm):
    """Reason class of one deviation: o / r = mixes of our / the reference stretch."""
    fo = lambda k: frac(o, k)
    fr = lambda k: frac(r, k)
    if fr("m:ferry") > 0.05 and fo("m:ferry") < 0.02:
        return "ferry (reference)"
    if fo("m:ferry") > 0.05 and fr("m:ferry") < 0.02:
        return "ferry (ours)"
    if fr("m:untagged") > 0.4:
        return "data gap (reference not traceable)"
    if fr("m:wrongway") > 0.2 and fo("m:wrongway") < 0.05:
        return "oneway (reference rides against it)"
    if fo("m:mainroad") - fr("m:mainroad") > 0.3:
        return "main road instead of cycle path" if fr("m:bikeway") > 0.25 else "main road instead of quiet way"
    if fr("m:push") - fo("m:push") > 0.2:
        return "footway avoided"
    if fo("m:push") - fr("m:push") > 0.2:
        return "footway taken"
    if fr("m:unpaved") - fo("m:unpaved") > 0.3:
        return "unpaved avoided"
    if fo("m:unpaved") - fr("m:unpaved") > 0.3:
        return "unpaved taken"
    if fr("m:cobble") - fo("m:cobble") > 0.3:
        return "cobbles avoided"
    if fo("m:cobble") - fr("m:cobble") > 0.3:
        return "cobbles taken"
    if fr("m:cycleroute") - fo("m:cycleroute") > 0.4:
        return "cycle route not followed"
    if fo("m:cycleroute") - fr("m:cycleroute") > 0.4:
        return "cycle route followed (reference not)"
    road = lambda f: f("a:road") + f("a:busy_road")
    if road(fo) - road(fr) > 0.3:
        return "road instead of bike way / track" if fr("m:bikeway") + fr("a:track") > 0.3 else "road instead of quiet road"
    if road(fr) - road(fo) > 0.3:
        return "bike way / quiet road instead of road (ours)"
    if fr("a:path") > 0.3:
        return "path (reference)"
    if fo("m:bikeway") - fr("m:bikeway") > 0.3:
        return "bike way instead of other (ours)"
    if fr("m:bikeway") - fo("m:bikeway") > 0.3:
        return "bike way not taken"
    if om < 0.8 * rm:
        return "shortcut (ours shorter)"
    if om > 1.25 * rm:
        return "detour (ours longer)"
    return "similar alternative"


class Piece:
    def __init__(self, d, name, k):
        self.name, self.k = name, k
        tags, rows = load_samples(os.path.join(d, name, "samples", "ref_%d.tsv.gz" % k), 1)
        self.rows = rows
        self.info = [tags.get(r[3]) for r in rows]
        self.len = STEP * len(rows)


def analyse_run(d, name, piece, act, via):
    tag = "%d_%s_%s" % (piece.k, act, via)
    p = os.path.join(d, name, "samples", "ours_%s.tsv.gz" % tag)
    if not os.path.isfile(p):
        return None
    tags, rows = load_samples(p, 2)
    refx = load_refx(os.path.join(d, name, "samples", "refx_%s.tsv.gz" % tag))
    oinfo = [tags.get(r[3]) for r in rows]
    olen = STEP * len(rows)
    ov_ref = sum(1 for x in refx if x[0] <= NEAR) * STEP
    ov_our = sum(1 for r in rows if r[4] <= NEAR) * STEP
    om = mix(oinfo, push_mask(oinfo))
    devs = []
    # our stretches away from the reference
    o_off = [r[4] > DEV_FAR for r in rows]
    covered = []
    for a, b in runs_of(o_off, 1):
        ra0 = rows[a - 1][5] if a > 0 else 0.0
        ra1 = rows[b + 1][5] if b + 1 < len(rows) else piece.len
        lo, hi = min(ra0, ra1), max(ra0, ra1)
        ourm = (b - a + 1) * STEP
        refm = hi - lo
        if max(ourm, refm) < DEV_MIN:
            continue
        ri = [i for i, r in enumerate(piece.rows) if lo <= r[0] <= hi]
        covered.append((lo, hi))
        devs.append((a, b, ri, ourm, refm))
    # reference stretches we left out (not inside a stretch above)
    r_off = [x[0] > DEV_FAR for x in refx]
    for a, b in runs_of(r_off, int(DEV_MIN / STEP)):
        lo, hi = piece.rows[a][0], piece.rows[b][0]
        if any(c0 - 50 <= lo and hi <= c1 + 50 for c0, c1 in covered):
            continue
        oa0 = refx[a - 1][1] if a > 0 else 0.0
        oa1 = refx[b + 1][1] if b + 1 < len(refx) else olen
        olo, ohi = min(oa0, oa1), max(oa0, oa1)
        oi = [i for i, r in enumerate(rows) if olo <= r[0] <= ohi]
        aa, bb = (oi[0], oi[-1]) if oi else (0, -1)
        devs.append((aa, bb, list(range(a, b + 1)), (bb - aa + 1) * STEP, (b - a + 1) * STEP))
    out = []
    for a, b, ri, ourm, refm in devs:
        o = mix(oinfo[a:b + 1], push_mask(oinfo[a:b + 1])) if b >= a else {"len": 0}
        rinfo = [piece.info[i] for i in ri]
        r = mix(rinfo, push_mask(rinfo)) if ri else {"len": 0}
        out.append({
            "piece": piece.k, "act": act, "via": via, "name": name,
            "refFromKm": piece.rows[ri[0]][0] / 1000 if ri else 0, "refToKm": piece.rows[ri[-1]][0] / 1000 if ri else 0,
            "ourM": ourm, "refM": refm, "deltaM": ourm - refm, "size": max(ourm, refm),
            "reason": classify(o, r, ourm, refm), "ourMix": short_mix(o), "refMix": short_mix(r),
            "ourPts": [(rows[i][1], rows[i][2]) for i in range(max(0, a - 1), min(len(rows), b + 2))],
            "refPts": [(piece.rows[i][1], piece.rows[i][2]) for i in range(max(0, ri[0] - 1), min(len(piece.rows), ri[-1] + 2))] if ri else [],
            "ctxPts": [(piece.rows[i][1], piece.rows[i][2]) for i in range(max(0, (ri[0] if ri else 0) - 60),
                                                                            min(len(piece.rows), (ri[-1] if ri else 0) + 60))],
        })
    return {"refM": piece.len, "ourM": olen, "ovRef": ov_ref, "ovOur": ov_our, "mix": om, "devs": out}


def add(a, b):
    for k, v in b.items():
        a[k] = a.get(k, 0.0) + v


def analyse(d, names=None):
    """-> dict name -> {(act, via): aggregate}, refmix per name, deviations list, runs rows, pieces rows"""
    res = {}
    for nd in sorted(glob.glob(os.path.join(d, "*", "runs*.tsv"))):
        name = os.path.basename(os.path.dirname(nd))
        if names and name not in names:
            continue
        if name in res:
            continue
        runs = read_tsv(glob.glob(os.path.join(d, name, "runs*.tsv")))
        pieces = read_tsv(glob.glob(os.path.join(d, name, "pieces*.tsv")))
        agg, refmix, devs = {}, {}, []
        for pr in pieces:
            k = int(pr["piece"])
            piece = Piece(d, name, k)
            add(refmix, mix(piece.info, push_mask(piece.info)))
            for rr in [r for r in runs if int(r["piece"]) == k]:
                a = analyse_run(d, name, piece, rr["act"], rr["viaKm"])
                if a is None:
                    continue
                key = (rr["act"], rr["viaKm"])
                g = agg.setdefault(key, {"refM": 0, "ourM": 0, "ovRef": 0, "ovOur": 0, "mix": {}, "errors": 0, "n": 0})
                g["refM"] += a["refM"]; g["ourM"] += a["ourM"]; g["ovRef"] += a["ovRef"]; g["ovOur"] += a["ovOur"]
                g["n"] += 1
                if rr.get("error"):
                    g["errors"] += 1
                add(g["mix"], a["mix"])
                devs += a["devs"]
        fid = sum(float(p["traceFid"]) * float(p["refKm"]) for p in pieces) / max(1e-9, sum(float(p["refKm"]) for p in pieces))
        res[name] = {"agg": agg, "refmix": refmix, "devs": devs, "runs": runs, "pieces": pieces, "fid": fid}
    return res


ACT_LABEL = {"BIKE_TOURING": "Touring", "BIKE_TREKKING": "Trekking", "EBIKE": "E-bike", "MTB": "MTB"}
VIA_LABEL = {"0": "sparse", "5": "medium (5 km)"}
WAYS = ["cycleway", "quiet_road", "road", "busy_road", "track", "path", "footway", "steps", "ferry", "other", "unknown"]
SURFS = ["paved", "cobble", "firm", "loose", "unknown"]


def pct(x):
    return "%.1f %%" % (100 * x)


def summary_rows(res, group):
    """[(label, act, via, g)] combined over the names in group"""
    keys = sorted({k for n in group for k in res[n]["agg"]})
    out = []
    for key in keys:
        g = {"refM": 0, "ourM": 0, "ovRef": 0, "ovOur": 0, "mix": {}, "errors": 0, "n": 0}
        for n in group:
            x = res[n]["agg"].get(key)
            if x:
                for f in ("refM", "ourM", "ovRef", "ovOur", "errors", "n"):
                    g[f] += x[f]
                add(g["mix"], x["mix"])
        out.append((key[0], key[1], g))
    return out


def write_gpx(path, dev):
    def trk(name, pts):
        return ("  <trk><name>%s</name><trkseg>\n" % name + "".join(
            '    <trkpt lat="%.6f" lon="%.6f"/>\n' % p for p in pts) + "  </trkseg></trk>\n")
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="OnPath GpxCompare" '
                'xmlns="http://www.topografix.com/GPX/1/1">\n')
        f.write(trk("OnPath %s" % ACT_LABEL.get(dev["act"], dev["act"]), dev["ourPts"]))
        f.write(trk("Reference", dev["refPts"]))
        f.write("</gpx>\n")


def plot(path, dev, title):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    allp = dev["ctxPts"] + dev["ourPts"] + dev["refPts"]
    lat0 = sum(p[0] for p in allp) / len(allp)
    kx = math.cos(math.radians(lat0))
    fig, ax = plt.subplots(figsize=(7, 7), dpi=110)
    xs = lambda pts: [p[1] * kx for p in pts]
    ys = lambda pts: [p[0] for p in pts]
    ax.plot(xs(dev["ctxPts"]), ys(dev["ctxPts"]), color="#bbbbbb", lw=5, solid_capstyle="round", label="reference (context)")
    ax.plot(xs(dev["refPts"]), ys(dev["refPts"]), color="#2a7de1", lw=4, label="reference")
    ax.plot(xs(dev["ourPts"]), ys(dev["ourPts"]), color="#e8590c", lw=2.2, label="OnPath %s" % ACT_LABEL.get(dev["act"], dev["act"]))
    if dev["refPts"]:
        ax.plot([dev["refPts"][0][1] * kx], [dev["refPts"][0][0]], "o", color="#2a7de1")
    ax.set_aspect("equal")
    ax.set_xticks([]); ax.set_yticks([])
    # scale bar 500 m
    x0, x1 = ax.get_xlim(); y0, y1 = ax.get_ylim()
    sb = 500 / 111320.0
    ax.plot([x0 + 0.05 * (x1 - x0), x0 + 0.05 * (x1 - x0) + sb], [y0 + 0.05 * (y1 - y0)] * 2, color="black", lw=3)
    ax.text(x0 + 0.05 * (x1 - x0), y0 + 0.07 * (y1 - y0), "500 m", fontsize=8)
    ax.legend(loc="upper right", fontsize=8)
    ax.set_title(title, fontsize=9)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def cmd_report(a):
    res = analyse(a.dir)
    L = ["# " + a.title, ""]
    groups = [("plan", [n for n in res if n == "plan"]), ("rides", [n for n in res if n.startswith("ride")])]
    for gname, group in groups:
        if not group:
            continue
        ref_km = sum(sum(float(p["refKm"]) for p in res[n]["pieces"]) for n in group)
        L.append("## %s (%s, %.0f km, trace fidelity %s)" % (gname, ", ".join(group), ref_km,
                 ", ".join("%s %s" % (n, pct(res[n]["fid"])) for n in group)))
        L.append("")
        L.append("| profile | vias | overlap ref | overlap ours | length ratio | bike way | on cycle route | main road | main road w/o lane | traffic class ≥5 | pushing m/100 km | unpaved | loose | cobbles | ferry m | wrong-way m | failed plans |")
        L.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        rm = {}
        for n in group:
            add(rm, res[n]["refmix"])
        def mrow(label, via, m, ovr, ovo, ratio, err):
            km = m["len"] / 1000
            return "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %.0f | %s | %s | %s | %.0f | %.0f | %s |" % (
                label, via, ovr, ovo, ratio, pct(frac(m, "m:bikeway")), pct(frac(m, "m:cycleroute")), pct(frac(m, "m:mainroad")),
                pct(frac(m, "m:mainroad_nolane")), pct(frac(m, "m:traffic_hi")), m.get("m:push", 0) / km * 100,
                pct(frac(m, "m:unpaved")), pct(frac(m, "m:loose")), pct(frac(m, "m:cobble")), m.get("m:ferry", 0),
                m.get("m:wrongway", 0), err)
        L.append(mrow("**reference**", "-", rm, "-", "-", "1.00", "-"))
        for act, via, g in summary_rows(res, group):
            L.append(mrow(ACT_LABEL.get(act, act), VIA_LABEL.get(via, via), g["mix"], pct(g["ovRef"] / g["refM"]),
                          pct(g["ovOur"] / max(1, g["ourM"])), "%.3f" % (g["ourM"] / g["refM"]), "%d / %d" % (g["errors"], g["n"])))
        L.append("")
        # way types (the app's classes) and surface groups
        L.append("Way types and surfaces (km; reference = traced line):")
        L.append("")
        cols = [("reference", rm)] + [("%s %s" % (ACT_LABEL.get(act, act), VIA_LABEL.get(via, via)), g["mix"]) for act, via, g in summary_rows(res, group)]
        L.append("| category | " + " | ".join(c[0] for c in cols) + " |")
        L.append("| --- " * (len(cols) + 1) + "|")
        for prefix, cats in (("a:", WAYS), ("s:", SURFS)):
            for c in cats:
                L.append("| %s | " % c + " | ".join("%.1f" % (m.get(prefix + c, 0) / 1000) for _, m in cols) + " |")
            L.append("| | " + " | ".join("" for _ in range(len(cols))) + " |")
        L.append("")
    # deviations
    devs = [d for n in res for d in res[n]["devs"]]
    L.append("## Deviations (≥ %d m, our line > %d m from the reference)" % (DEV_MIN, DEV_FAR))
    L.append("")
    by = defaultdict(lambda: [0, 0.0, 0.0])
    for d in devs:
        x = by[(d["act"], d["via"], d["reason"])]
        x[0] += 1; x[1] += d["size"]; x[2] += d["deltaM"]
    for act in sorted({d["act"] for d in devs}):
        for via in sorted({d["via"] for d in devs}):
            items = sorted([(k[2], v) for k, v in by.items() if k[0] == act and k[1] == via], key=lambda kv: -kv[1][1])
            if not items:
                continue
            tot = sum(v[1] for _, v in items)
            L.append("**%s, %s vias** – %d deviations, %.0f km in total" % (ACT_LABEL.get(act, act), VIA_LABEL.get(via, via),
                     sum(v[0] for _, v in items), tot / 1000))
            L.append("")
            L.append("| reason | count | km | share | extra km (ours − ref) |")
            L.append("| --- | --- | --- | --- | --- |")
            for r, v in items:
                L.append("| %s | %d | %.1f | %s | %+.1f |" % (r, v[0], v[1] / 1000, pct(v[1] / tot), v[2] / 1000))
            L.append("")
    if a.devdir:
        os.makedirs(a.devdir, exist_ok=True)
        for f in glob.glob(os.path.join(a.devdir, "*")):
            os.remove(f)
        for act in sorted({d["act"] for d in devs}):
            for via in sorted({d["via"] for d in devs}, key=lambda v: -float(v)):
                top = sorted([d for d in devs if d["act"] == act and d["via"] == via], key=lambda d: -d["size"])
                if not top:
                    continue
                al = ACT_LABEL.get(act, act)
                L.append("### Largest deviations – %s, %s vias (GPX: both lines; PNG for the first %d)" % (al, VIA_LABEL.get(via, via), a.plots))
                L.append("")
                L.append("| # | line | piece | at ref km | ours m | ref m | Δ m | reason | ours | reference | file |")
                L.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
                for i, d in enumerate(top[:a.gpx]):
                    fn = "%s_%s_%03d_%s_p%d" % (al, via, i + 1, d["name"], d["piece"])
                    write_gpx(os.path.join(a.devdir, fn + ".gpx"), d)
                    if i < a.plots:
                        plot(os.path.join(a.devdir, fn + ".png"), d, "%s piece %d km %.1f–%.1f · %s %s vias\n%s · ours %.1f km / ref %.1f km" % (
                            d["name"], d["piece"], d["refFromKm"], d["refToKm"], al,
                            VIA_LABEL.get(d["via"], d["via"]), d["reason"], d["ourM"] / 1000, d["refM"] / 1000))
                    if i < a.rows:
                        L.append("| %d | %s | %d | %.1f–%.1f | %.0f | %.0f | %+.0f | %s | %s | %s | %s |" % (
                            i + 1, d["name"], d["piece"], d["refFromKm"], d["refToKm"], d["ourM"], d["refM"], d["deltaM"],
                            d["reason"], d["ourMix"], d["refMix"], fn))
                L.append("")
    with open(a.md, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    if a.json:
        out = {}
        for n in res:
            out[n] = {"%s|%s" % k: {"refM": g["refM"], "ourM": g["ourM"], "ovRef": g["ovRef"], "ovOur": g["ovOur"],
                                    "errors": g["errors"], "n": g["n"], "mix": g["mix"]} for k, g in res[n]["agg"].items()}
            out[n]["_ref"] = res[n]["refmix"]
            out[n]["_dev"] = {"%s|%s|%s" % k: v for k, v in by.items()}
        json.dump(out, open(a.json, "w"), indent=0)
    print("wrote", a.md)


def cmd_table(a):
    rows = []
    for spec in a.variants:
        label, d = spec.split("=", 1)
        res = analyse(d)
        rows.append((label, res))
    L = ["# " + a.title, ""]
    for gname, sel in (("plan", lambda n: n == "plan"), ("rides", lambda n: n.startswith("ride")), ("all", lambda n: True)):
        L.append("## %s" % gname)
        L.append("")
        L.append("| variant | profile | overlap sparse | overlap medium | length ratio sparse | length ratio medium | bike way | main road | main road w/o lane | pushing m/100 km | unpaved | loose | cobbles | ferry m | wrong-way m | failed |")
        L.append("| --- " * 16 + "|")
        for label, res in rows:
            group = [n for n in res if sel(n)]
            if not group:
                continue
            sr = summary_rows(res, group)
            for act in sorted({x[0] for x in sr}):
                g = {via: gg for aa, via, gg in sr if aa == act}
                s, m = g.get("0"), g.get("5")
                mm = (m or s)["mix"]
                km = mm["len"] / 1000
                L.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %.0f | %s | %s | %s | %.0f | %.0f | %s |" % (
                    label, ACT_LABEL.get(act, act), pct(s["ovRef"] / s["refM"]) if s else "-", pct(m["ovRef"] / m["refM"]) if m else "-",
                    "%.3f" % (s["ourM"] / s["refM"]) if s else "-", "%.3f" % (m["ourM"] / m["refM"]) if m else "-",
                    pct(frac(mm, "m:bikeway")), pct(frac(mm, "m:mainroad")), pct(frac(mm, "m:mainroad_nolane")),
                    mm.get("m:push", 0) / km * 100, pct(frac(mm, "m:unpaved")), pct(frac(mm, "m:loose")), pct(frac(mm, "m:cobble")),
                    mm.get("m:ferry", 0), mm.get("m:wrongway", 0),
                    "%d+%d" % (s["errors"] if s else 0, m["errors"] if m else 0)))
        L.append("")
    L.append("Mix columns (bike way … wrong-way) = medium-density plans. Overlap = share of the reference within 20 m of our line.")
    with open(a.md, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    print("wrote", a.md)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    r = sp.add_parser("report"); r.add_argument("dir"); r.add_argument("--md", required=True)
    r.add_argument("--devdir"); r.add_argument("--plots", type=int, default=8); r.add_argument("--gpx", type=int, default=30); r.add_argument("--rows", type=int, default=20)
    r.add_argument("--title", default="GpxCompare report"); r.add_argument("--json")
    t = sp.add_parser("table"); t.add_argument("variants", nargs="+"); t.add_argument("--md", required=True)
    t.add_argument("--title", default="Variants")
    a = ap.parse_args()
    {"report": cmd_report, "table": cmd_table}[a.cmd](a)


if __name__ == "__main__":
    main()
