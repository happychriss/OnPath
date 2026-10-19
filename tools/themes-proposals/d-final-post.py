import json, sys
p = sys.argv[1]; st = json.load(open(p)); L = st['layers']
ids = [l['id'] for l in L]
def get(i): return L[ids.index(i)]
# routes: 40 % everywhere
for l in L:
    if l['id'] in ('routes-hike','routes-bike','routes-mtb'):
        l['paint']['line-opacity'] = 0.4
# no white / light underlays
for i in ('path-casing','trails-casing','track','track-grade-cover','track-grade-fill'):
    if i in ids: get(i).setdefault('layout', {})['visibility'] = 'none'
# base paths only zoomed out; own exact paths from z13.5 (no solid line under dotted trails)
LOW = 13.5
for i in ('path','path-paved'):
    get(i)['maxzoom'] = LOW
st['sources']['paths'] = {"type": "geojson", "data": "file://{DATA_DIR}/paths.geojson", "tolerance": 0.2}
own = {"id": "paths-own", "type": "line", "source": "paths", "minzoom": LOW,
       "filter": ["all", ["==", ["get", "k"], "p"], ["!", ["has", "st"]], ["<", ["get", "sac"], 2], ["<", ["coalesce", ["get", "vis"], 0], 3]],
       "layout": {"line-join": "round", "line-cap": "round"},
       "paint": {"line-color": "#33251a", "line-width": get('path')['paint']['line-width']}}
L.insert(ids.index('path') + 1, own); ids = [l['id'] for l in L]
# track A: two thin dashed brown edges with a gap, no fill
for i in ('track-casing','track-paved','track-grade-solid','track-grade-dotted','track-grade-soft'):
    if i in ids:
        pt = get(i)['paint']
        pt['line-gap-width'] = ["interpolate", ["linear"], ["zoom"], 13, 1.2, 14, 2.4, 16, 3.6, 18, 7]
        pt['line-width'] = ["interpolate", ["linear"], ["zoom"], 13, 0.6, 14, 0.8, 16, 1.0, 18, 1.4]
        pt['line-color'] = "#6b543c"
        if 'line-dasharray' in pt: pt['line-dasharray'] = [3, 2]
# symbols V1 (fill-pattern, images added by the page)
syms = [("sym-wetland", ["==", ["get", "class"], "wetland"]), ("sym-farmland", ["==", ["get", "class"], "farmland"]),
        ("sym-rock", ["==", ["get", "class"], "rock"]), ("sym-sand", ["==", ["get", "class"], "sand"])]
at = ids.index('landcover-ice-outline')
for n, (img, f) in enumerate(syms):
    L.insert(at + n, {"id": img, "type": "fill", "source": "base", "source-layer": "landcover", "minzoom": 14, "filter": f,
                      "paint": {"fill-pattern": img}})
ids = [l['id'] for l in L]
L.insert(ids.index('landuse-residential') + 1, {"id": "sym-residential", "type": "fill", "source": "base", "source-layer": "landuse", "minzoom": 14,
    "filter": ["in", ["get", "class"], ["literal", ["residential", "suburb", "neighbourhood"]]], "paint": {"fill-pattern": "sym-residential"}})
json.dump(st, open(sys.argv[2], 'w'))
print('ok', len(L))
