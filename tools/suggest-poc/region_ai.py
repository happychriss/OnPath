#!/usr/bin/env python3
"""Tour-suggestions POC – "region knowledge" from an AI, once per map area (experiment, owner 2026-11-13). Version 2.

    python3 tools/suggest-poc/region_ai.py --around Hilden --km 20   # which map areas in reach lack an AI file, cost
    python3 tools/suggest-poc/region_ai.py --around Hilden --km 20 --create   # … and create them (one call each)
    python3 tools/suggest-poc/region_ai.py Hilden            # the map area that holds the place
    python3 tools/suggest-poc/region_ai.py 10-543-361        # a map area by id (Meran)
    python3 tools/suggest-poc/region_ai.py 10-531-341 --list # only show the closed list, no AI call
    … --model sonnet|opus|haiku (default sonnet) · --recheck = the stored answer through the checks again, no AI call

How it works – no name matching:
 1. `catalog(x, y)`: every named thing of ONE map area that can be a destination, from our own map files (known
    routes, named trails, lakes, rivers, protected areas, peaks, POIs worth a stop), each with a stable id and its
    geometry. That is the closed list.
 2. ONE question to the AI: the numbered list + the area's box and towns; it PICKS BY NUMBER what a local would
    recommend (rang 1–3, wandern / rad / beides, one sentence why). A pick is one of our objects by construction.
 3. The AI may add up to 8 "extras" it misses in the list, with a rough position. An extra is only reported
    ("known, not in our map") – unless one of our named objects lies within 1.5 km AND shares the name's stem
    (Tappeinerweg ↔ Tappeiner-Promenade): then it is an alias and joins the picks.
Only the area's box, its town names and our own map's names go out; nothing about a user.
Result: ~/onpath-suggest-poc/region-ai/10-x-y.json (version 2; an old version-1 file is kept as *.v1.json).
`suggest.tips` reads these files at request time (wish field `tips`) – a request never calls the AI.

The AI call here is `claude -p` (the Claude Code command line on this computer, run in an empty folder so it
knows nothing of this project). The real thing would be one API call on the map server when it builds an area.
"""
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import named as AT  # noqa: E402  (read-only use: the per-area extract of routes / trails / lakes)
import cells as CE  # noqa: E402
import pmt  # noqa: E402
import suggest as S  # noqa: E402

OUT = os.path.join(CE.DATA, "region-ai")
VERSION = 2
MAX_LIST = 600
POI_KINDS = {"viewpoint": "Aussicht", "castle": "Schloss/Burg", "ruins": "Ruine", "museum": "Museum",
             "waterfall": "Wasserfall", "cave": "Höhle", "train": "Bahnhof"}
NET = {4: "international", 3: "national", 2: "regional", 1: "lokal", 0: "lokal"}
# a trail label is kept when its name says "a walk", or when it is long; "Selikumer Weg" (a street-like way) is not
TRAILISH = re.compile(r"rund|pfad|steig|promenade|waal|panorama|höhenweg|hoehenweg|uferweg|wanderweg|route|trail|"
                      r"allee|deich|damm|ufer|schlucht|klamm|tal\b|heide|joch|alm", re.I)
GENERIC = set("weg wege pfad steig promenade see seen wald park schloss burg kloster bahnhof rundweg rundwanderweg "
              "naturschutzgebiet nsg naturpark tal berg garten ufer fahre faehre seilbahn bahn radweg wanderweg "
              "der die das am an im von zu und bei san st sankt".split())


def norm(s):
    s = unicodedata.normalize("NFKD", (s or "").lower().replace("ß", "ss")).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def dist_m(a, b):
    k = math.cos(math.radians((a[1] + b[1]) / 2))
    return math.hypot((a[0] - b[0]) * k, a[1] - b[1]) * 111320.0


def length_km(lines):
    return sum(dist_m(l[i], l[i + 1]) for l in lines for i in range(len(l) - 1)) / 1000.0


def _in(p, box):
    return box[0] <= p[0] < box[2] and box[1] < p[1] <= box[3]


def _clip(part, box):
    """The pieces of a line that lie inside the box (point-wise – good enough for lengths and nearness)."""
    out, cur = [], []
    for p in part:
        if _in(p, box):
            cur.append([round(p[0], 6), round(p[1], 6)])
        else:
            if len(cur) > 1:
                out.append(cur)
            cur = []
    if len(cur) > 1:
        out.append(cur)
    return out


def _thin(line, step_m=25.0):
    out = [line[0]]
    for p in line[1:-1]:
        if dist_m(out[-1], p) >= step_m:
            out.append(p)
    if len(line) > 1:
        out.append(line[-1])
    return out


def merged_label(label):
    """One name per route: 'EV15 · EuroVelo 15 - Rheinradweg - Teilstrecke Köln - Moers' and 'Euroga Radwege (West)'."""
    lab = re.sub(r"\s+-\s+(Teilstrecke|Verbindung|Abschnitt|Etappe|Teil|Variante)\b.*$", "", label)
    return re.sub(r"\s*\((West|Ost|Süd|Nord|Mitte)\)\s*$", "", lab).strip()


def _area_km2(ring):
    if len(ring) < 3:
        return 0.0
    k = math.cos(math.radians(ring[0][1])) * 111.32
    s = 0.0
    for i in range(len(ring)):
        a, b = ring[i], ring[(i + 1) % len(ring)]
        s += (a[0] * k) * (b[1] * 111.32) - (b[0] * k) * (a[1] * 111.32)
    return abs(s) / 2


def catalog(x, y, W=None):
    """The closed list of one map area: [{id, kind, name, rank, detail, geom}] – every entry is an object of our map.
    geom: {type: line|shore|area|point, lines | rings | p}."""
    A = {"trail_min_m": 1000, "lake_skip": AT.NOT_A_LAKE.pattern, "lake_min_shore_m": 600}
    box = CE.cell_bbox(x, y)
    d = CE.folder(x, y)
    cell_km2 = _area_km2([[box[0], box[1]], [box[2], box[1]], [box[2], box[3]], [box[0], box[3]]])
    ent = {}

    def add(kind, name, rank, geom, size, extra=""):
        name = re.sub(r"\s+", " ", name).strip()
        key = kind.split(":")[0] + ":" + norm(name).replace(" ", "-")
        if not norm(name) or key in ent:
            return
        ent[key] = {"id": key, "kind": kind, "name": name, "rank": rank, "geom": geom, "size": round(size, 2), "extra": extra}

    # known routes and named trails (the POC's per-area extract; pieces of one route merged under one name)
    groups = {}
    for r in AT.read_cell(x, y)["routes"]:
        g = groups.setdefault((r["kind"], bool(r.get("trail")), merged_label(r["label"])), {"net": 0, "lines": []})
        g["net"] = max(g["net"], int(r.get("net") or 0))
        for l in r["lines"]:
            g["lines"] += _clip(l, box)
    for (kind, trail, label), g in groups.items():
        km = length_km(g["lines"])
        if not g["lines"] or km < 0.3:
            continue
        lines = [_thin(l) for l in g["lines"]]
        if trail:
            if km < A.get("trail_min_m", 1000) / 1000.0 or not (TRAILISH.search(label) or km >= 3.0):
                continue
            add("trail", label, 1, {"type": "line", "lines": lines}, km)
        else:
            add("route:" + kind, label, g["net"], {"type": "line", "lines": lines}, km)
    # lakes with a real shore
    skip = re.compile(A.get("lake_skip", "^$"), re.I)
    for l in AT.read_cell(x, y)["lakes"]:
        km = length_km(l["lines"])
        if skip.search(l["name"]) or km * 1000 < A.get("lake_min_shore_m", 600):
            continue
        add("lake", l["name"], 0, {"type": "shore", "lines": [_thin(q) for q in l["lines"]]}, km)
    # rivers, protected areas, peaks from the base map
    ba = pmt.PMTiles(os.path.join(d, "base.pmtiles"))
    rivers, parks, peaks, places = {}, {}, {}, {}
    for z, tx, ty in pmt.tiles_of_cell(x, y, 12):
        raw = ba.tile(z, tx, ty)
        if not raw:
            continue
        L = pmt.layers(raw, {"waterway", "park", "mountain_peak", "place"}, z, tx, ty)
        for f in L.get("waterway", []):
            n = f["tags"].get("name")
            if n and f["tags"].get("class") == "river":
                for part in f["parts"]:
                    rivers.setdefault(n, []).extend(_clip(part, box))
        for f in L.get("park", []):
            n = f["tags"].get("name")
            if n and f["type"] == 3:
                p = parks.setdefault(n, {"cls": f["tags"].get("class") or "", "rings": []})
                p["rings"] += [[[round(q[0], 6), round(q[1], 6)] for q in r] for r in f["parts"] if len(r) > 3]
        for f in L.get("mountain_peak", []):
            t = f["tags"]
            if t.get("name") and t.get("class") == "peak" and f["parts"] and _in(f["parts"][0][0], box):
                peaks.setdefault(t["name"], (int(t.get("rank") or 9), int(t.get("ele") or 0), f["parts"][0][0]))
        for f in L.get("place", []):
            t = f["tags"]
            if t.get("name") and t.get("class") in ("city", "town", "village") and f["parts"] and _in(f["parts"][0][0], box):
                cur = places.get(t["name"])
                rk = ({"city": 0, "town": 100, "village": 200}[t["class"]] + int(t.get("rank") or 50))
                if cur is None or rk < cur[0]:
                    places[t["name"]] = (rk, f["parts"][0][0])
    for n, lines in rivers.items():
        km = length_km(lines)
        if km >= 3.0:
            add("river", n, 0, {"type": "shore", "lines": [_thin(l, 40) for l in lines]}, km)
    for n, p in parks.items():
        rings = [r for r in p["rings"] if any(_in(q, box) for q in r)]
        km2 = sum(_area_km2(r) for r in rings)  # tile-cut pieces add up to the area inside the cell (roughly)
        if not rings or km2 < 0.05:
            continue
        g = {"type": "area", "rings": [_thin(r, 40) for r in rings]}
        if km2 > 0.4 * cell_km2:
            g["big"] = True  # covers most of the map area (a Naturpark): a pick says "the region", not a place
        add("area", n, 0, g, km2, p["cls"])
    for n, (rk, ele, pt) in peaks.items():
        add("peak", n, 0, {"type": "point", "p": [round(pt[0], 6), round(pt[1], 6)]}, -rk * 10000 + ele, "%d m" % ele if ele else "")
    # POIs worth a stop (and stations as ways in)
    pj = os.path.join(d, "pois.geojson")
    if os.path.isfile(pj):
        with open(pj, encoding="utf-8") as f:
            for ft in json.load(f)["features"]:
                pr = ft["properties"]
                pt = ft["geometry"]["coordinates"][:2]
                if pr.get("kind") in POI_KINDS and pr.get("name") and _in(pt, box) and norm(pr["name"]) != "aussichtspunkt":
                    add("poi:" + pr["kind"], pr["name"], 0, {"type": "point", "p": [round(pt[0], 6), round(pt[1], 6)]}, 0)
    items = list(ent.values())
    # cap: routes first, then by what makes a destination big
    order = {"route": 0, "lake": 1, "area": 2, "trail": 3, "poi": 4, "river": 5, "peak": 6}
    items.sort(key=lambda e: (order[e["kind"].split(":")[0]], -(e["rank"] or 0), -e["size"], e["name"]))
    quota = {"peak": 60, "trail": 220, "area": 140}
    seen, kept = {}, []
    for e in items:
        k = e["kind"].split(":")[0]
        seen[k] = seen.get(k, 0) + 1
        if seen[k] <= quota.get(k, MAX_LIST):
            kept.append(e)
    kept = kept[:MAX_LIST]
    for i, e in enumerate(kept, 1):
        e["nr"] = i
    return kept, towns_line(places, box), sorted(places)


def towns_line(places, box, n=14):
    """The area's towns for the question: the biggest place of each ninth of the box first, then the biggest overall."""
    best = {}
    for name, (rk, p) in places.items():
        gx = min(2, int((p[0] - box[0]) / (box[2] - box[0]) * 3))
        gy = min(2, int((p[1] - box[1]) / (box[3] - box[1]) * 3))
        if (gx, gy) not in best or rk < best[(gx, gy)][0]:
            best[(gx, gy)] = (rk, name)
    out = [nm for _, nm in sorted(best.values())]
    for name, (rk, _) in sorted(places.items(), key=lambda kv: kv[1][0]):
        if len(out) >= n:
            break
        if name not in out:
            out.append(name)
    return out


def list_line(e):
    k = e["kind"]
    if k.startswith("route:"):
        return "%d | %s, %s | %s | %.0f km im Gebiet" % (e["nr"], "Radweg" if k.endswith("bike") else "Mountainbike-Route" if k.endswith("mtb") else "Wanderweg",
                                                     NET.get(e["rank"], "lokal"), e["name"], e["size"])
    if k == "trail":
        return "%d | benannter Weg | %s | %.1f km" % (e["nr"], e["name"], e["size"])
    if k == "lake":
        return "%d | See | %s | Ufer %.1f km" % (e["nr"], e["name"], e["size"])
    if k == "river":
        return "%d | Fluss | %s | %.0f km im Gebiet" % (e["nr"], e["name"], e["size"])
    if k == "area":
        return "%d | Gebiet (%s) | %s | %.1f km²" % (e["nr"], e["extra"] or "Schutzgebiet", e["name"], e["size"])
    if k == "peak":
        return "%d | Gipfel | %s | %s" % (e["nr"], e["name"], e["extra"])
    return "%d | %s | %s |" % (e["nr"], POI_KINDS[k.split(":")[1]], e["name"])


PROMPT = """Du hilfst einer Wander- und Fahrrad-App. Es geht um ein Kartengebiet (Rechteck):
West {w:.3f}, Süd {s:.3f}, Ost {e:.3f}, Nord {n:.3f} (WGS84). Orte darin: {towns}.

Unten steht eine nummerierte Liste von allem, was unsere Karte in diesem Rechteck mit Namen kennt
(Nr | Art | Name | Größe). Wähle daraus die Ziele, die ein Ortskundiger für Wandern und Radfahren
wirklich empfehlen würde – höchstens 30, die besten zuerst. Wähle nur, was du sicher kennst oder
dem Namen und der Art nach sicher einordnen kannst; lieber weniger. Verschiedene Namen für dieselbe
Sache nur einmal.

Zusätzlich: höchstens 8 lohnende Ziele in diesem Rechteck, die in der Liste FEHLEN (z. B. Fähren,
Klöster, Aussichtspunkte, bekannte Wege unter anderem Namen) – mit ungefährer Lage.

Antworte NUR mit JSON, ohne Text davor oder danach:
{{"picks": [{{"nr": 12, "rang": 1-3, "fuer": "wandern|rad|beides", "warum": "ein kurzer Satz"}}],
  "extras": [{{"name": "...", "art": "gebiet|weg|see|aussicht|sehenswert|einstieg", "lat": 0.0, "lon": 0.0,
              "fuer": "wandern|rad|beides", "warum": "ein kurzer Satz"}}]}}
(rang 3 = überregional bekannt, 2 = in der Region bekannt, 1 = lokaler Tipp)

Liste:
{lines}"""


def ask(prompt, model):
    """One AI call. -> (text, seconds, the tool's own result record with cost and token usage)."""
    t = time.time()
    with tempfile.TemporaryDirectory() as empty:
        r = subprocess.run(["claude", "-p", "--model", model, "--output-format", "json", "--max-turns", "1"],
                           input=prompt, cwd=empty, capture_output=True, text=True, timeout=400)
    if r.returncode != 0:
        raise RuntimeError("claude -p failed: " + (r.stderr or r.stdout)[:400])
    o = json.loads(r.stdout)
    return o.get("result", ""), time.time() - t, o


def stems(name):
    """The name's telling words: 'Tappeinerweg' -> {tappeiner}, 'Tappeiner-Promenade' -> {tappeiner}."""
    out = set()
    for w in norm(name).split():
        for suf in ("promenade", "rundweg", "weg", "pfad", "steig", "see", "wald", "park", "bahn"):
            if w.endswith(suf) and len(w) - len(suf) >= 5:
                w = w[:-len(suf)]
                break
        if w not in GENERIC and len(w) >= 5:
            out.add(w)
    return out


def geom_points(g):
    if g["type"] == "point":
        return [g["p"]]
    return [p for l in g.get("lines", g.get("rings", [])) for p in l]


# which of our kinds an extra of this "art" may be an alias of (a promenade is never a museum, a park never a river)
COMPAT = {"weg": ("route", "trail"), "see": ("lake", "river"), "gebiet": ("area",), "aussicht": ("peak", "poi:viewpoint"),
          "sehenswert": ("poi:castle", "poi:ruins", "poi:museum", "poi:waterfall", "poi:cave"), "einstieg": ("poi:train",)}


def alias_of(extra, cat, place_names=(), within_m=1500.0):
    """Our object this extra really is: of a fitting kind, near the given position AND sharing a telling word that
    is not just a town's name ('… Meran' proves nothing in Meran). (None, None) otherwise."""
    try:
        p = [float(extra["lon"]), float(extra["lat"])]
    except (KeyError, TypeError, ValueError):
        return None, None
    towns = set()
    for n in place_names:
        towns |= set(norm(n).split())
    st = stems(extra.get("name", "")) - towns
    kinds = COMPAT.get(extra.get("art"), ())
    best = (None, None, 0)
    for e in cat:
        if not any(e["kind"] == k or e["kind"].startswith(k + ":") for k in kinds):
            continue
        common = st & (stems(e["name"]) - towns)
        if not common:
            continue
        dm = min(dist_m(p, q) for q in geom_points(e["geom"]))
        if dm <= within_m and (len(common), -dm) > (best[2], -(best[1] if best[1] is not None else 1e9)):
            best = (e, dm, len(common))
    return best[0], best[1]


def usage_of(meta):
    u = meta.get("usage") or {}
    return {"input": u.get("input_tokens"), "cache_write": u.get("cache_creation_input_tokens"),
            "cache_read": u.get("cache_read_input_tokens"), "output": u.get("output_tokens")}


def run(x, y, model="sonnet", only_list=False, recheck=False, log=print):
    CE.get(x, y)
    box = CE.cell_bbox(x, y)
    cat, towns, place_names = catalog(x, y)
    kinds = {}
    for e in cat:
        kinds[e["kind"].split(":")[0]] = kinds.get(e["kind"].split(":")[0], 0) + 1
    log("Kartengebiet 10-%d-%d · Orte: %s" % (x, y, ", ".join(towns)))
    log("geschlossene Liste: %d Einträge %s" % (len(cat), kinds))
    lines = "\n".join(list_line(e) for e in cat)
    prompt = PROMPT.format(w=box[0], s=box[1], e=box[2], n=box[3], towns=", ".join(towns) or "(keine Angabe)", lines=lines)
    if only_list:
        log(lines)
        log("Frage: %d Zeichen" % len(prompt))
        return None
    out = os.path.join(OUT, "10-%d-%d.json" % (x, y))
    if recheck:  # no AI call: the stored answer again through the checks (after a change to the alias rule)
        with open(out, encoding="utf-8") as f:
            old = json.load(f)
        ans = old.get("answer") or {"picks": [{"nr": by["nr"], "rang": p["rang"], "fuer": p["fuer"], "warum": p["warum"]}
                                              for p in old["picks"] if not p.get("alias")
                                              for by in cat if by["id"] == p["id"]],
                                    "extras": [{k: v for k, v in e.items() if k not in ("alias_von", "alias_m", "im_gebiet")} for e in old["extras"]]}
        secs, meta, model = old["seconds"], {"total_cost_usd": old["cost_usd"], "usage": {}, "modelUsage": dict.fromkeys(old.get("models_used", []))}, old["model"]
        tokens = old["tokens"]
    else:
        text, secs, meta = ask(prompt, model)
        js = re.search(r"\{.*\}", text, re.S)
        ans = json.loads(js.group(0)) if js else {}
        tokens = usage_of(meta)
    by_nr = {e["nr"]: e for e in cat}
    picks, bad, seen = [], [], set()
    for p in ans.get("picks", []):
        e = by_nr.get(p.get("nr"))
        if e is None:
            bad.append(p)
            continue
        if e["id"] in seen:
            continue
        seen.add(e["id"])
        picks.append({"id": e["id"], "kind": e["kind"], "name": e["name"], "rang": max(1, min(3, int(p.get("rang") or 1))),
                      "fuer": p.get("fuer") or "beides", "warum": p.get("warum", ""), "geom": e["geom"], "size": e["size"]})
    extras = []
    for ex in ans.get("extras", []):
        e, dm = alias_of(ex, cat, place_names)
        try:
            ex["im_gebiet"] = _in([float(ex["lon"]), float(ex["lat"])], box)
        except (KeyError, TypeError, ValueError):
            ex["im_gebiet"] = None
        if e is not None:
            ex["alias_von"], ex["alias_m"] = e["name"], round(dm)
            if e["id"] not in seen:
                seen.add(e["id"])
                picks.append({"id": e["id"], "kind": e["kind"], "name": e["name"], "rang": 2, "fuer": ex.get("fuer") or "beides",
                              "warum": ex.get("warum", ""), "geom": e["geom"], "size": e["size"], "alias": ex.get("name")})
        extras.append(ex)
    os.makedirs(OUT, exist_ok=True)
    if os.path.isfile(out):
        with open(out, encoding="utf-8") as f:
            old = json.load(f)
        if old.get("version") != VERSION:
            os.replace(out, out[:-5] + ".v1.json")
    res = {"version": VERSION, "cell": [x, y], "bbox": list(box), "towns": towns, "model": model,
           "models_used": list((meta.get("modelUsage") or {}).keys()), "seconds": round(secs, 1),
           "cost_usd": meta.get("total_cost_usd"), "tokens": tokens, "prompt_chars": len(prompt),
           "list_size": len(cat), "list_kinds": kinds, "picks": picks, "extras": extras, "bad_numbers": bad, "answer": ans,
           "at": time.strftime("%Y-%m-%dT%H:%M:%S")}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False)
    with open(out[:-5] + ".prompt.txt", "w", encoding="utf-8") as f:
        f.write(prompt)
    log("KI (%s): %d gewählt, %d Extras (davon %d als anderer Name erkannt), %d ungültige Nummern · %.0f s · %s $ · Frage %d Zeichen · Token %s" % (
        model, len(picks), len(extras), sum(1 for e in extras if e.get("alias_von")), len(bad), secs,
        meta.get("total_cost_usd"), len(prompt), tokens))
    for p in picks:
        log(" r%d %-7s %-14s %-46s %s%s" % (p["rang"], p["fuer"], p["kind"], p["name"][:46], p["warum"],
                                           (" [genannt als: %s]" % p["alias"]) if p.get("alias") else ""))
    for ex in extras:
        log(" + %-34s %-10s %s%s" % (ex.get("name", "")[:34], ex.get("art"), ex.get("warum", ""),
                                     (" → ist bei uns: %s (%d m)" % (ex["alias_von"], ex["alias_m"])) if ex.get("alias_von")
                                     else " → fehlt in unserer Karte" + ("" if ex.get("im_gebiet") else " (Lage außerhalb / unklar)")))
    log("gespeichert: " + out)
    return res


def around(place, reach_km, create, model):
    """Which map areas within reach of a place have no AI file yet, and what creating them would cost. Creates them
    only with --create (one AI call per missing area)."""
    pl = S.find_place(place, S.load_settings())
    if not pl:
        sys.exit("Ort nicht gefunden: " + place)
    cells = CE.cells_for(pl[1][0], pl[1][1], reach_km * 1000.0)
    missing = []
    for x, y in cells:
        f = os.path.join(OUT, "10-%d-%d.json" % (x, y))
        ok = False
        if os.path.isfile(f):
            with open(f, encoding="utf-8") as fh:
                d = json.load(fh)
            ok = d.get("version") == VERSION
            print(" 10-%d-%d  KI-Datei da (%s, %d Tipps, %s)" % (x, y, d.get("model"), len(d.get("picks", [])), d.get("at", "?")))
        if not ok:
            missing.append((x, y))
            print(" 10-%d-%d  fehlt%s" % (x, y, "" if CE.have(x, y) else " (Kartengebiet noch nicht geladen)"))
    print("%s, %d km Umkreis: %d Kartengebiete, %d ohne KI-Datei – anlegen kostet etwa %d × 10 Cent = %.2f $ (mittleres Modell)"
          % (pl[0], reach_km, len(cells), len(missing), len(missing), 0.10 * len(missing)))
    if create:
        for x, y in missing:
            run(x, y, model=model)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--around" in sys.argv:
        i = sys.argv.index("--around")
        place = sys.argv[i + 1]
        km = float(sys.argv[sys.argv.index("--km") + 1]) if "--km" in sys.argv else 6.0
        model = sys.argv[sys.argv.index("--model") + 1] if "--model" in sys.argv else "sonnet"
        return around(place, km, "--create" in sys.argv, model)
    model = "sonnet"
    if "--model" in sys.argv:
        model = sys.argv[sys.argv.index("--model") + 1]
        args = [a for a in args if a != model]
    arg = args[0] if args else "Hilden"
    m = re.fullmatch(r"10-(\d+)-(\d+)", arg)
    if m:
        x, y = int(m.group(1)), int(m.group(2))
    else:
        pl = S.find_place(arg, S.load_settings())
        if not pl:
            sys.exit("Ort nicht gefunden: " + arg)
        x, y = CE.cell_of(*pl[1])
    run(x, y, model=model, only_list="--list" in sys.argv, recheck="--recheck" in sys.argv)


if __name__ == "__main__":
    main()
