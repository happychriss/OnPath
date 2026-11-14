"""Tour suggestions – a wish in, a few loops out, each with its reasons (docs/design/tour-suggestions.md).

  facts.py      level 1  what a way is               (tags + map -> facts, the same everywhere)
  place.py      level 2  what the place offers       (its own yardstick, what stands out, the unavoidable way out)
  character.py  level 3  what this person wants      (gemütlich … wild, from activity and level)
  generate.py   one pool of loops, one assembly routine
  score.py      five axes, a few hard rules, one selection, the words that say why
This file is the edge: settings, place names, the route service, map areas, the saved run. Plain Python, stdlib only.
"""
import json
import os
import subprocess
import time
import urllib.parse
import urllib.request

import cells
import named
from character import Character, wish as make_wish
from facts import Facts
from generate import pool
from net import load, seat_start
from place import Place
from score import choose, explain, score

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE = os.environ.get("SUGGEST_SERVICE", "http://127.0.0.1:8791")
DATA = os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc"))


def load_settings():
    with open(os.environ.get("SUGGEST_SETTINGS") or os.path.join(HERE, "settings.json"), encoding="utf-8") as f:
        return json.load(f)


def find_place(name, S):
    """Quick picks (settings.json places), 'lat, lon', else Nominatim (online). -> (label, [lon, lat]) or None."""
    n = name.strip().lower()
    for test in (str.__eq__, str.startswith):
        for k, v in S["places"].items():
            if test(k.lower(), n):
                return k, v
    try:
        lon, lat = sorted(float(x) for x in name.replace(";", ",").split(","))  # in Europe lat > lon
        return "%.5f, %.5f" % (lat, lon), [lon, lat]
    except ValueError:
        pass
    try:
        url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + urllib.parse.quote(name)
        r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "onpath-suggest-poc"}), timeout=8))
        return (r[0]["display_name"].split(",")[0], [float(r[0]["lon"]), float(r[0]["lat"])]) if r else None
    except Exception:  # expected: no network / no hit – the caller says "Ort nicht gefunden"
        return None


def ensure_service():
    """The BRouter route service (service/RouteService.java) – started once if it is not answering."""
    def up():
        try:
            urllib.request.urlopen(SERVICE + "/ping", timeout=1).read()
            return True
        except Exception:  # expected: not started yet
            return False
    if up():
        return
    os.makedirs(DATA, exist_ok=True)
    log = open(os.path.join(DATA, "service.log"), "a")
    seg = os.environ.get("ONPATH_SEGMENTS", os.path.expanduser("~/onpath-routing/segments"))
    subprocess.Popen([os.path.join(HERE, "service", "start.sh"), seg, SERVICE.rsplit(":", 1)[1].strip("/")],
                     stdout=log, stderr=log, start_new_session=True)
    for _ in range(60):
        time.sleep(0.5)
        if up():
            return
    raise RuntimeError("route service did not start – see " + os.path.join(DATA, "service.log"))


def tips(wish, reach_m):
    """Optional input: the AI's region tips per map area (region_ai.py) -> {name of a route / lake / protected area: 0..1}."""
    out, mine = {}, ("rad", "beides") if wish["activity"] != "hike" else ("wandern", "beides")
    for x, y in cells.cells_for(wish["start"][0], wish["start"][1], reach_m) if wish["tips"] else []:
        try:
            with open(os.path.join(DATA, "region-ai", "10-%d-%d.json" % (x, y)), encoding="utf-8") as f:
                for p in json.load(f).get("picks", []):
                    if p.get("fuer") in mine:
                        out[p["name"]] = max(out.get(p["name"], 0.0), p.get("rang", 1) / 3.0)
        except (OSError, ValueError):  # expected: no tips for this area
            pass
    return out


def suggest(wish, S=None):
    S = S or load_settings()
    t, times = time.time(), {}

    def lap(key):
        nonlocal t
        times[key], t = round(time.time() - t, 2), time.time()
    t0, notes = t, []
    ch = Character(wish, S)
    radius = min(ch.family["reach_max_m"], max(1500.0, S["search"]["radius"] * ch.target_m))
    ensure_service()
    missing = cells.ensure(wish["start"][0], wish["start"][1], radius)[1]
    if missing:
        notes.append("Für %d Kartengebiete hier gibt es noch keine Karte – dort kennt der Vorschlag weder Natur noch Orte." % len(missing))
    net = load(SERVICE, ch.profile, ch.kv, wish["start"], radius, ch.family["coarse"])
    things = named.load(wish["start"], radius + 500.0, ch.bike, S["facts"]["rank"])
    lap("network")
    F = Facts(net, named.Index(things), S["facts"], ch.bike)
    moved = seat_start(net, F.ok)
    if moved:
        notes.append("Der Startpunkt lag auf einem Weg ohne Anschluss – die Runden beginnen %d m weiter am nächsten Weg." % moved)
    lap("facts")
    P = Place(net, F, ch, S, tips(wish, radius))
    if P.access >= 0.1 * ch.target_m:
        notes.append("Von hier sind es etwa %s km bis ins Grüne. Dieser Weg hinaus und zurück ist bei jeder Runde gleich "
                     "und zählt bei der Bewertung nicht mit." % ("%.1f" % (P.access / 1000.0)).replace(".", ","))
    lap("place")
    cands = pool(P, S, wish)
    lap("loops")
    views = [{"name": th["name"], "p": th["dense"][0]} for th in things if th["kind"] == "view"]
    for i, c in enumerate(cands):
        score(P, S, c, wish, views)
        c["id"] = i + 1
    shown = choose(P, S, cands)
    for c in cands:
        c["chosen"] = c in shown
        if c["chosen"]:
            explain(P, c, wish)
        nodes = c.pop("nodes")
        c["coords"], c["cls"] = [[round(v, 6) for v in net.nodes[nodes[0]][:2]]], ""
        for ei, frm in zip(c.pop("edges"), nodes):
            pts = net.geom(ei, frm)[1:]
            c["coords"] += [[round(p[0], 6), round(p[1], 6)] for p in pts]
            c["cls"] += ("G" if F.traffic[ei] >= 0.45 else "S" if F.traffic[ei] >= 0.15 or F.built[ei] else
                         "W" if F.green[ei] >= 0.5 else "P") * len(pts)
    if not shown:
        notes.append("Von hier ließ sich keine passende Runde finden.")
    lap("judging")
    return {"run": time.strftime("%Y%m%d-%H%M%S") + "-%d" % wish["seed"], "wish": wish, "version": S["version"],
            "weights_version": S["version"], "method": "v2", "seconds": round(time.time() - t0, 1), "times": times,
            "notes": notes, "target_km": round(ch.target_m / 1000.0, 1), "character": {"wild": round(ch.wild, 2), "word": ch.word(),
                                                                                    "axes": {k: round(v, 2) for k, v in ch.axes.items()}},
            "place": {"nodes": len(net.nodes), "access_m": int(P.access), "low": {k: round(v, 2) for k, v in P.low.items()},
                      "high": {k: round(v, 2) for k, v in P.high.items()},
                      "areas": [[a["name"], round(a["m"] / 1000.0, 1)] for a in P.areas],
                      "lines": [[ln["name"], ln["kind"], round(ln["m"] / 1000.0, 1)] for ln in P.lines[:12]]},
            "candidates": sorted(cands, key=lambda c: (not c["chosen"], -c["score"]))}


def save_run(result):
    d = os.path.join(DATA, "runs")
    os.makedirs(d, exist_ok=True)
    base, k = result["run"], 0
    while os.path.exists(os.path.join(d, result["run"] + ".json")):
        k += 1
        result["run"] = base + chr(96 + k)
    with open(os.path.join(d, result["run"] + ".json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False)


def table(result, everything=False):
    """The run as text – for the terminal and for judging without a picture."""
    w, pl = result["wish"], result["place"]
    out = ["%s · %s · %g h · Ziel %s km · Charakter %s (%.2f) · %d Wegpunkte · %.1f s" % (
        w["start_name"] or w["start"], w["activity"], w["hours"], result["target_km"], result["character"]["word"],
        result["character"]["wild"], pl["nodes"], result["seconds"])]
    out += ["  Hinweis: " + n for n in result["notes"]]
    out.append("  Was hier heraussticht: " + (", ".join("%s (%s km)" % tuple(a) for a in pl["areas"][:8]) or "–"))
    out.append("  Linien: " + (", ".join("%s (%s km)" % (ln[0], ln[2]) for ln in pl["lines"][:8]) or "–"))
    for c in result["candidates"]:
        if not (c["chosen"] or everything):
            continue
        n, a = c["numbers"], c["axes"]
        out.append("%s %5.1f  N%3d R%3d W%3d B%3d F%3d  %5.1f km %4.2f h ↑%4d  %-12s %s" % (
            "*" if c["chosen"] else " ", c["score"], *(100 * a[k] for k in ("natur", "ruhe", "weg", "besonderes", "form")),
            n["km"], n["hours"], n["ascent"], c["origin"], c.get("name") or c.get("out") or ""))
        if c["chosen"]:
            out.append("        %s" % " · ".join(c["reasons"] + c["hints"]))
            out.append("        %s · am Wald %d %% · tief %d %% · Häuser %d %% · Straße %d %% (längstes Stück %d m)" % (
                c["why"], n["forest_pct"], n["deep_pct"], n["town_pct"], n["street_pct"], n["road_longest_m"]))
    return "\n".join(out)


if __name__ == "__main__":
    import sys
    S = load_settings()
    place = find_place(sys.argv[1] if len(sys.argv) > 1 else "Hilden", S)
    r = suggest(make_wish({"start": place[1], "start_name": place[0]}, S), S)
    print(table(r))
