#!/usr/bin/env python3
"""Extract a compact offline address index (streets, house numbers, towns) into SQLite (optional data file).

Output: OUT_FILE (normally addresses.sqlite), read by the app's offline search (search/AddressSearch.kt).
Missing file = no offline addresses; the app works as before.

Usage: python3 extract_addresses.py input.osm.pbf OUT_FILE minlon minlat maxlon maxlat [--owner]
  --owner  map cell build (docs/design/map-cells.md §2): the bbox is a cell, the input its buffered cut; streets and
           places are kept by the half-open owner rule (west <= lon < east, south < lat <= north), so a point on a
           cell border lands in exactly one cell. Without it the bbox edges are inclusive.

What goes in (only what lies in the bbox; one file per region or per z10 map cell):
  town    municipalities (admin_level 8, bilingual names, country) and places (city … hamlet, suburb, quarter)
  street  one row per named street per municipality and area (ways of one name within 1.5 km are one street);
          house numbers without a mapped street (addr:place, unknown addr:street) become a "street" row too
  house   house number → street, point (address nodes and building centroids)
  word    normalised word → street (prefix search with a B-tree range, no FTS needed)

Names: South Tyrol tags names bilingually ("Romstraße - Via Roma", "Bolzano - Bozen"). The display name is
name:de, else the German part of the bilingual name; all variants are searchable.

Joining cells: every cell is a self-contained file (own ids); the app opens addresses.sqlite and
addresses/*.sqlite and merges the results, so cells are joined by copying files. A street that crosses a
cell edge has one row in each cell (its point inside that cell).

Words are normalised exactly like SearchText.normalize in the app (lower case, ß → ss, accents stripped,
punctuation → space). German compound street names also store stem and suffix ("laurinstrasse" →
"laurin", "strasse") so "Laurin Str. 5" finds Laurinstraße 5.
"""
import math
import os
import re
import sqlite3
import sys
import time
import unicodedata
from collections import Counter, defaultdict

import osmium
import shapely
from shapely import wkb as shapely_wkb
from shapely.geometry import Point

FORMAT = 1
STREET_HW = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
             "living_street", "pedestrian", "service", "track", "road", "footway", "path", "cycleway", "steps",
             "bridleway", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"}
PLACE_KINDS = {"city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood"}
SAME_STREET_M = 1500.0
SUFFIXES = ("strasse", "gasse", "weg", "platz", "allee", "steig", "promenade", "ring", "damm", "ufer", "stieg")
IT_PREFIX = re.compile(r"^(via|viale|vicolo|piazza|piazzetta|largo|corso|strada|localita|passeggiata|lungo|salita|"
                       r"galleria|ponte|rione|borgo|contrada)\b")

# ------------------------------------------------------------------------------------------ text helpers
_MARKS = re.compile(r"[̀-ͯ᪰-᫿᷀-᷿⃐-⃿︠-︯]+")
_APOS = re.compile(r"['’`´]")


def normalize(s):
    """Same result as SearchText.normalize (Kotlin)."""
    if not s or not s.strip():
        return ""
    s = s.lower().replace("ß", "ss").replace("æ", "ae").replace("ø", "o").replace("ł", "l")
    s = "".join(c for c in unicodedata.normalize("NFD", s) if not unicodedata.category(c).startswith("M"))
    s = _APOS.sub("", s)
    s = "".join(c if c.isalnum() else " " for c in s)
    return " ".join(s.split())


def tokens(s):
    return [t for t in normalize(s).split(" ") if t]


def street_words(names):
    out = set()
    for n in names:
        for t in tokens(n):
            out.add(t)
            for suf in SUFFIXES:
                if t.endswith(suf) and len(t) > len(suf) + 2:
                    out.add(t[: -len(suf)])
                    out.add(suf)
                    break
    return out


def is_italian(s):
    return bool(IT_PREFIX.match(normalize(s)))


def names_of(tags, key="name"):
    """(display, [all variants]) from name / name:de / name:it / bilingual 'A - B'."""
    full = (tags.get(key) or "").strip()
    de = (tags.get(key + ":de") or "").strip()
    it = (tags.get(key + ":it") or "").strip()
    parts = [p.strip() for p in full.split(" - ")] if " - " in full else ([full] if full else [])
    variants = [v for v in [de, it, full] + parts if v]
    if de:
        disp = de
    elif len(parts) >= 2:
        non_it = [p for p in parts if not is_italian(p)]
        disp = non_it[0] if non_it else parts[0]
    else:
        disp = full or it
    seen, uniq = set(), []
    for v in variants:
        k = normalize(v)
        if k and k not in seen:
            seen.add(k)
            uniq.append(v)
    return disp, uniq


def dist_m(lat1, lon1, lat2, lon2):
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    y = math.radians(lat2 - lat1)
    return 6371000.0 * math.hypot(x, y)


def country_of(tags):
    if "ref:ISTAT" in tags or "ref:catasto" in tags:
        return "IT"
    if "ref:at:gkz" in tags:
        return "AT"
    if "swisstopo:BFS_NUMMER" in tags or "ref:bfs_Gemeindenummer" in tags:
        return "CH"
    if "de:amtlicher_gemeindeschluessel" in tags or "de:regionalschluessel" in tags:
        return "DE"
    iso = tags.get("ISO3166-2") or ""
    return iso[:2] if len(iso) >= 2 else ""


# ------------------------------------------------------------------------------------------ read OSM
def read(pbf, bbox):
    minlon, minlat, maxlon, maxlat = bbox
    pad = 0.05  # streets / municipalities reaching into the bbox

    def inside(lat, lon, p=0.0):
        return minlat - p <= lat <= maxlat + p and minlon - p <= lon <= maxlon + p

    munis = []      # (shape, display, variants, cc)
    places = []     # (kind, display, variants, lat, lon)
    ways = []       # (display, variants, coords[(lat, lon)])
    houses = []     # (num, street_or_None, place_or_None, city, postcode, lat, lon)
    wkbf = osmium.geom.WKBFactory()

    fp = (osmium.FileProcessor(pbf)
          .with_locations()
          .with_areas(osmium.filter.TagFilter(("boundary", "administrative"))))
    for o in fp:
        t = o.tags
        if o.is_area():
            if t.get("admin_level") != "8" or "name" not in t:
                continue
            try:
                g = shapely_wkb.loads(wkbf.create_multipolygon(o), hex=True)
            except Exception as e:  # broken / incomplete boundary at the extract edge
                print(f"  skip boundary {t.get('name')}: {e}", file=sys.stderr)
                continue
            b = g.bounds
            if b[0] > maxlon + pad or b[2] < minlon - pad or b[1] > maxlat + pad or b[3] < minlat - pad:
                continue
            disp, var = names_of(t)
            munis.append((g, disp, var, country_of(t)))
        elif o.is_node():
            loc = o.location
            if not loc.valid():
                continue
            lat, lon = loc.lat, loc.lon
            if t.get("place") in PLACE_KINDS and "name" in t and inside(lat, lon, pad):
                disp, var = names_of(t)
                places.append((t["place"], disp, var, lat, lon))
            if "addr:housenumber" in t and inside(lat, lon):
                houses.append(house_of(t, lat, lon))
        elif o.is_way():
            hw = t.get("highway")
            has_addr = "addr:housenumber" in t
            if not ((hw in STREET_HW and "name" in t) or has_addr):
                continue
            try:
                coords = [(n.lat, n.lon) for n in o.nodes if n.location.valid()]
            except Exception:  # expected: missing node locations at the extract edge
                continue
            if not coords:
                continue
            if hw in STREET_HW and "name" in t and any(inside(la, lo) for la, lo in coords):
                disp, var = names_of(t)
                ways.append((disp, var, coords))
            if has_addr:
                lat = sum(c[0] for c in coords) / len(coords)
                lon = sum(c[1] for c in coords) / len(coords)
                if inside(lat, lon):
                    houses.append(house_of(t, lat, lon))
    return munis, places, ways, houses


def house_of(t, lat, lon):
    num = re.sub(r"\s+", "", t.get("addr:housenumber", ""))
    street = t.get("addr:street") or None
    street_vars = None
    if street:
        _, street_vars = names_of({"name": street, "name:de": t.get("addr:street:de", ""),
                                   "name:it": t.get("addr:street:it", "")})
    place = t.get("addr:place") or None
    return (num, street, street_vars, place, t.get("addr:city"), t.get("addr:postcode"), lat, lon)


# ------------------------------------------------------------------------------------------ build
def build(pbf, out, bbox, owner=False):
    t0 = time.time()
    minlon, minlat, maxlon, maxlat = bbox

    def own(lat, lon):
        if owner:
            return minlon <= lon < maxlon and minlat < lat <= maxlat
        return minlat <= lat <= maxlat and minlon <= lon <= maxlon
    munis, places, ways, houses = read(pbf, bbox)
    print(f"  read: {len(munis)} municipalities, {len(places)} places, {len(ways)} street ways, "
          f"{len(houses)} addresses ({time.time() - t0:.0f}s)")

    tree = shapely.STRtree([m[0] for m in munis]) if munis else None

    def muni_at(lat, lon):
        if tree is None:
            return None
        hits = tree.query(Point(lon, lat), predicate="within")
        return int(hits[0]) if len(hits) else None

    def muni_many(pts):
        res = [None] * len(pts)
        if tree is None or not pts:
            return res
        geoms = shapely.points([(lo, la) for la, lo in pts])
        pi, ti = tree.query(geoms, predicate="within")
        for a, b in zip(pi, ti):
            if res[a] is None:
                res[a] = int(b)
        return res

    # towns: municipalities (kind 'm') + places; ids 1..n
    towns = []  # [kind, display, variants, cc, lat, lon]
    muni_tid = {}
    for i, (g, disp, var, cc) in enumerate(munis):
        c = g.representative_point()
        muni_tid[i] = len(towns) + 1
        towns.append(["m", disp, var, cc, c.y, c.x])
    place_muni = muni_many([(p[3], p[4]) for p in places])
    for (kind, disp, var, lat, lon), mi in zip(places, place_muni):
        cc = munis[mi][3] if mi is not None else ""
        towns.append([kind, disp, var, cc, lat, lon])
    cc_default = Counter(t[3] for t in towns if t[3]).most_common(1)
    cc_default = cc_default[0][0] if cc_default else ""
    small_places = [(i + 1, t) for i, t in enumerate(towns) if t[0] != "m"]

    def locality(lat, lon, muni_name):
        best, bd = None, 2000.0
        for tid, t in small_places:
            if abs(t[4] - lat) > 0.02 or abs(t[5] - lon) > 0.03:
                continue
            d = dist_m(lat, lon, t[4], t[5])
            if d < bd:
                best, bd = tid, d
        # the nearest place is the municipality's own centre: no extra locality
        if best is not None and normalize(towns[best - 1][1]) == normalize(muni_name or ""):
            return None
        return best

    # ---- streets: group ways by (name, municipality), cluster by distance
    mids = [c[len(c) // 2] for _, _, c in ways]
    way_muni = muni_many(mids)
    groups = defaultdict(list)
    for (disp, var, coords), mi in zip(ways, way_muni):
        groups[(normalize(disp), mi)].append((disp, var, coords))
    streets = []  # dict(disp, vars, muni, lat, lon, coords)
    for (_, mi), ws in groups.items():
        clusters = []
        for w in ws:
            joined = None
            for cl in clusters:
                if any(dist_m(a[0], a[1], b[0], b[1]) < SAME_STREET_M
                       for a in w[2][:: max(1, len(w[2]) // 6)] for b in cl["pts"]):
                    joined = cl
                    break
            if joined is None:
                joined = {"disp": w[0], "vars": list(w[1]), "coords": [], "pts": [], "muni": mi}
                clusters.append(joined)
            else:
                for v in w[1]:
                    if v not in joined["vars"]:
                        joined["vars"].append(v)
            joined["coords"].extend(w[2])
            joined["pts"].extend(w[2][:: max(1, len(w[2]) // 6)] + [w[2][-1]])
        streets.extend(clusters)

    def rep_point(coords):
        ins = [c for c in coords if minlat <= c[0] <= maxlat and minlon <= c[1] <= maxlon] or coords
        clat = sum(c[0] for c in ins) / len(ins)
        clon = sum(c[1] for c in ins) / len(ins)
        return min(ins, key=lambda c: dist_m(c[0], c[1], clat, clon))

    # name variant → streets
    by_name = defaultdict(list)
    for si, s in enumerate(streets):
        s["lat"], s["lon"] = rep_point(s["coords"])
        for v in s["vars"]:
            by_name[normalize(v)].append(si)

    # ---- houses → streets
    house_muni = muni_many([(h[6], h[7]) for h in houses])
    pseudo = {}  # (norm name, muni) → street index
    house_rows = []
    for h, mi in zip(houses, house_muni):
        num, street, street_vars, place, city, pc, lat, lon = h
        if not num:
            continue
        si = None
        if street:
            cands = set()
            for v in street_vars:
                cands.update(by_name.get(normalize(v), ()))
            best = None
            for c in cands:
                s = streets[c]
                d = min(dist_m(lat, lon, p[0], p[1]) for p in s["pts"])
                if s["muni"] != mi:
                    d += 1000.0
                if d < 2500.0 and (best is None or d < best[0]):
                    best = (d, c)
            if best:
                si = best[1]
        if si is None:
            label = street or place
            if not label:
                continue
            key = (normalize(label), mi)
            si = pseudo.get(key)
            if si is None:
                disp, var = (names_of({"name": label}) if not street else
                             (names_of({"name": street})[0], street_vars))
                si = len(streets)
                streets.append({"disp": disp, "vars": list(var), "coords": [], "pts": [], "muni": mi,
                                "lat": lat, "lon": lon, "pseudo": 1 if place and not street else 2, "hs": []})
                pseudo[key] = si
            streets[si]["hs"].append((lat, lon))
        streets[si].setdefault("pcs", Counter())
        if pc:
            streets[si]["pcs"][pc] += 1
        house_rows.append((si, num, lat, lon))
    # pseudo streets: point = house nearest to their centre
    for s in streets:
        if s.get("hs"):
            clat = sum(p[0] for p in s["hs"]) / len(s["hs"])
            clon = sum(p[1] for p in s["hs"]) / len(s["hs"])
            s["lat"], s["lon"] = min(s["hs"], key=lambda p: dist_m(p[0], p[1], clat, clon))

    # ---- write
    tmp = out + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript("""
        PRAGMA page_size = 4096;
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID;
        -- kind: m = municipality, else the OSM place value; cc: ISO country code or ''
        CREATE TABLE town(id INTEGER PRIMARY KEY, kind TEXT, name TEXT, alt TEXT, cc TEXT, lat INTEGER, lon INTEGER);
        -- town: municipality (town.id) or NULL; loc: nearest smaller place within 2 km or NULL
        -- p: 0 = mapped street, 1 = addr:place (no street), 2 = addr:street without a mapped way; lat/lon: 1e-5 deg
        CREATE TABLE street(id INTEGER PRIMARY KEY, name TEXT, alt TEXT, town INTEGER, loc INTEGER, pc TEXT,
                            p INTEGER, lat INTEGER, lon INTEGER);
        CREATE TABLE house(street INTEGER, num TEXT, lat INTEGER, lon INTEGER, PRIMARY KEY(street, num)) WITHOUT ROWID;
        CREATE TABLE word(w TEXT, street INTEGER, PRIMARY KEY(w, street)) WITHOUT ROWID;
    """)
    E5 = 100000.0
    used_towns = set()
    keep = []
    for si, s in enumerate(streets):
        if not own(s["lat"], s["lon"]):
            continue
        keep.append(si)
    keep_set = set(keep)
    new_id = {si: i + 1 for i, si in enumerate(keep)}
    srows, wrows = [], set()
    for si in keep:
        s = streets[si]
        tid = muni_tid.get(s["muni"]) if s["muni"] is not None else None
        muni_name = munis[s["muni"]][1] if s["muni"] is not None else None
        loc = locality(s["lat"], s["lon"], muni_name)
        for x in (tid, loc):
            if x:
                used_towns.add(x)
        pc = s.get("pcs", Counter()).most_common(1)
        alt = [v for v in s["vars"] if v != s["disp"]]
        srows.append((new_id[si], s["disp"], "|".join(alt) or None, tid, loc, pc[0][0] if pc else None,
                      s.get("pseudo", 0), round(s["lat"] * E5), round(s["lon"] * E5)))
        for w in street_words([s["disp"]] + s["vars"]):
            wrows.add((w, new_id[si]))
    db.executemany("INSERT INTO street VALUES (?,?,?,?,?,?,?,?,?)", srows)
    db.executemany("INSERT INTO word VALUES (?,?)", sorted(wrows))
    hrows = {}
    for si, num, lat, lon in house_rows:
        key = (new_id[si], num.lower()) if si in keep_set else None
        if key and key not in hrows:
            hrows[key] = (num, round(lat * E5), round(lon * E5))
    db.executemany("INSERT INTO house VALUES (?,?,?,?)", sorted((k[0],) + v for k, v in hrows.items()))
    trows = []
    for i, (kind, disp, var, cc, lat, lon) in enumerate(towns):
        tid = i + 1
        in_box = own(lat, lon)
        if tid in used_towns or (in_box and kind != "m"):
            alt = [v for v in var if v != disp]
            trows.append((tid, kind, disp, "|".join(alt) or None, cc or None, round(lat * E5), round(lon * E5)))
    db.executemany("INSERT INTO town VALUES (?,?,?,?,?,?,?)", trows)
    meta = {"format": str(FORMAT), "bbox": f"{minlon},{minlat},{maxlon},{maxlat}", "cc": cc_default,
            "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    db.executemany("INSERT INTO meta VALUES (?,?)", meta.items())
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.replace(tmp, out)
    print(f"  wrote {out}: {len(trows)} towns, {len(srows)} streets, {len(hrows)} house numbers, "
          f"{len(wrows)} words, {os.path.getsize(out) / 1e6:.1f} MB ({time.time() - t0:.0f}s)")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--owner"]
    if len(args) != 6:
        print(__doc__)
        sys.exit(2)
    build(args[0], args[1], tuple(map(float, args[2:6])), owner="--owner" in sys.argv[1:])
