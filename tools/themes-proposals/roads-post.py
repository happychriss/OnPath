import json, sys, copy
base = json.load(open('styles/d-merged.json'))
Z = lambda *s: ["interpolate", ["exponential", 1.2], ["zoom"], *s]
STREET = Z(11, 0.7, 14, 2.2, 16, 4.0, 18, 7.0)
SERVICE = Z(13, 0.6, 14, 1.6, 16, 3.0, 18, 5.5)
TRACK = Z(13, 1.1, 14, 1.8, 16, 3.2, 18, 5.6)
GAP_ST = Z(11, 0.6, 14, 2.0, 16, 3.6, 18, 7)
GAP_TR = Z(13, 1.0, 14, 1.8, 16, 3.0, 18, 6)
EDGE = ["interpolate", ["linear"], ["zoom"], 12, 0.6, 14, 0.9, 16, 1.2, 18, 1.6]
GREY = "#7d766d"; BROWN = "#8a6440"
DASH = {"track-casing": [2.4, 1.4], "track-grade-dotted": [2.4, 1.4], "track-grade-soft": [1.0, 1.4], "track-grade-solid": [4, 1.0]}
def build(opt):
    st = copy.deepcopy(base); L = {l['id']: l for l in st['layers']}
    hide = lambda i: L[i].setdefault('layout', {}).__setitem__('visibility', 'none')
    for i in ('road-minor-casing', 'road-service-casing'): hide(i)
    for i, w, gap in (('road-minor', STREET, GAP_ST), ('road-service', SERVICE, GAP_ST)):
        p = L[i]['paint']; p['line-color'] = GREY
        if opt == 'R3':
            L[i.replace('road-', 'road-') + '-casing']['layout']['visibility'] = 'visible'
            c = L[i + '-casing']['paint']; c['line-color'] = GREY; c['line-width'] = EDGE; c['line-gap-width'] = gap
            hide(i)
        else:
            p['line-width'] = w
    tc = BROWN if opt == 'R2' else GREY
    for i in ('track-casing', 'track-paved', 'track-grade-solid', 'track-grade-dotted', 'track-grade-soft'):
        p = L[i]['paint']; p['line-color'] = tc
        if opt == 'R3':
            p['line-width'] = EDGE; p['line-gap-width'] = GAP_TR
        else:
            p.pop('line-gap-width', None); p['line-width'] = TRACK
        if i == 'track-paved': p.pop('line-dasharray', None)
        else: p['line-dasharray'] = DASH.get(i, [2.4, 1.4]) if opt != 'R3' else [3, 2]
    L['track-low']['paint']['line-color'] = tc
    json.dump(st, open(f'styles/{opt}.json', 'w'))
for o in ('R1', 'R2', 'R3'): build(o)
print('ok')
