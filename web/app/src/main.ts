// OnPath web planner – spike page (docs/design/web-planner.md §6). Click the map to set points; the app's own
// planning code (Planner, WaypointInsertion, BRouter, RouteTimes – compiled to Wasm) plans in a Web Worker.
// The map is the app's style + the same PMTiles files, drawn by maplibre-gl JS.
import * as maplibregl from 'maplibre-gl';
import type { StyleSpecification, GeoJSONSource, MapMouseEvent } from 'maplibre-gl';
import type { FeatureCollection } from 'geojson';
import 'maplibre-gl/dist/maplibre-gl.css';
import { Protocol } from 'pmtiles';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { EngineClient } from './engine/client';
import type { Activity, PlanResult, SharedPlan } from './engine/types';
import { shareDialog } from './share';
import { profileSvg } from './profile';
import { PACKED, usePacked, packedGlyphs } from './packed';
import { Cells } from './cells';
import { setupSearch, type PlaceRole } from './search';
import type { Hit } from './engine/types';
import './style.css';

// names as in the app (Activity.label, owner 2026-10-26); the stored form is the enum name
const ACTIVITIES: [Activity, string][] = [
  ['BIKE_TOURING', 'Bike'], ['BIKE_TREKKING', 'Gravel bike'], ['EBIKE', 'E-bike'],
  ['MTB', 'MTB'], ['EMTB', 'E-MTB'], ['HIKE', 'Hike'],
];
const ROUTE = '#1E3FAE';
const base = new URL('./', location.href).href;
const params = new URLSearchParams(location.search);
const startAt = (params.get('at') ?? import.meta.env.VITE_START ?? '11.16,46.67,12').split(',').map(Number);

const state = {
  points: [] as [number, number][],
  /** Per point: pinned to a POI / place (passed exactly) and its name – kept from a received plan. */
  meta: [] as { pinned: boolean; name?: string }[],
  title: null as string | null,
  activity: (localStorageGet('activity') as Activity) ?? 'HIKE',
  sac: Number(localStorageGet('sac') ?? 2),
  plan: null as PlanResult | null,
  latest: 0,
};

document.querySelector<HTMLDivElement>('#app')!.innerHTML = `
  <div id="map"></div>
  <aside id="panel">
    <header><b>OnPath</b> <span class="muted">web planner · test</span></header>
    <div id="searchbox"></div>
    <label>Activity <select id="activity">${ACTIVITIES.map(([k, l]) => `<option value="${k}">${l}</option>`).join('')}</select></label>
    <label id="sacRow">Trail difficulty <select id="sac">${[1, 2, 3, 4, 5].map((t) => `<option value="${t}">T${t}</option>`).join('')}</select></label>
    <div id="summary" class="summary muted">Click the map to set a start point.</div>
    <div id="profile"></div>
    <ol id="points"></ol>
    <div class="row"><button id="share" class="primary" disabled>Send to phone · Share</button></div>
    <div class="row"><button id="reverse">Reverse</button><button id="roundTrip">Round trip</button><button id="clear">Clear</button></div>
    <div id="status" class="status muted"></div>
  </aside>`;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const actSel = $<HTMLSelectElement>('activity');
const sacSel = $<HTMLSelectElement>('sac');
actSel.value = state.activity; sacSel.value = String(state.sac);

// ---- map ----
maplibregl.setWorkerUrl(maplibreWorkerUrl); // the bundle moves maplibre's module worker; tell it where
const pmtiles = new Protocol();
maplibregl.addProtocol('pmtiles', pmtiles.tile);
// Map: the map server's cells (default), or one flat map folder (?map=folder, and the packed preview)
const useCells = params.get('map') !== 'folder' && import.meta.env.VITE_MAP !== 'folder';
const cells = new Cells(params.get('data') ?? base + 'data/');
const mapFolder = params.get('data') ?? base + 'map/'; // folder mode only
if (useCells) maplibregl.addProtocol('cell', cells.protocol);
const map = new maplibregl.Map({
  // start view: ?at=lon,lat,zoom, else the build's VITE_START (preview), else Meran
  container: 'map', style: await loadStyle(), center: [startAt[0], startAt[1]], zoom: startAt[2], attributionControl: false,
});
(window as unknown as { __map: maplibregl.Map }).__map = map; // for the browser tests
map.addControl(new maplibregl.NavigationControl({ visualizePitch: false }), 'top-right');
map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-right');
map.addControl(new maplibregl.AttributionControl({ compact: true, customAttribution: '© OpenStreetMap contributors, © OpenMapTiles' }));
if (useCells) map.on('moveend', () => cells.loadPois(map));
map.on('load', () => {
  if (useCells) cells.loadPois(map);
  map.addSource('planned-route', { type: 'geojson', data: empty() });
  const routed = ['==', ['get', 'straight'], false] as maplibregl.FilterSpecification;
  const straight = ['==', ['get', 'straight'], true] as maplibregl.FilterSpecification;
  map.addLayer({ id: 'planned-route-casing', type: 'line', source: 'planned-route', filter: routed,
    layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#fff', 'line-width': 10 } });
  map.addLayer({ id: 'planned-route', type: 'line', source: 'planned-route', filter: routed,
    layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': ROUTE, 'line-width': 6 } });
  map.addLayer({ id: 'planned-straight', type: 'line', source: 'planned-route', filter: straight,
    paint: { 'line-color': ROUTE, 'line-width': 4, 'line-dasharray': [1.5, 1.5] } });
});

// The style the app ships, pointed at web URLs; sources whose file is not there are left out (no blank errors).
async function loadStyle(): Promise<StyleSpecification> {
  const s: StyleSpecification = await (await fetch(base + 'style/onpath.json')).json();
  if (useCells) {
    s.glyphs = PACKED ? packedGlyphs(base) : base + 'fonts/{fontstack}/{range}.pbf';
    s.sprite = base + 'sprites/onpath';
    return cells.style(s);
  }
  const data = mapFolder;
  const fix = (u: string) => u.replace('pmtiles://file://{DATA_DIR}/', 'pmtiles://' + data).replace('file://{DATA_DIR}/', data);
  s.glyphs = PACKED ? packedGlyphs(base) : base + 'fonts/{fontstack}/{range}.pbf';
  s.sprite = base + 'sprites/onpath';
  const missing = new Set<string>();
  await Promise.all(Object.entries(s.sources).map(async ([id, src]) => {
    const x = src as { url?: string; data?: unknown };
    if (typeof x.url === 'string') x.url = fix(x.url);
    if (typeof x.data === 'string') x.data = fix(x.data);
    const file = (x.url ?? (x.data as string)).replace('pmtiles://', '');
    const ok = PACKED && file.endsWith('.pmtiles') ? await usePacked(pmtiles, file)
      : await fetch(file, { method: 'HEAD' }).then((r) => r.ok).catch(() => false);
    if (!ok) missing.add(id);
  }));
  for (const id of missing) delete s.sources[id];
  s.layers = s.layers.filter((l) => !('source' in l) || !missing.has(l.source as string));
  if (missing.size) status(`Map files not found here: ${[...missing].join(', ')}`);
  return s;
}

// ---- points ----
const markers: maplibregl.Marker[] = [];
map.on('click', async (e: MapMouseEvent) => {
  const { lat, lng } = e.lngLat;
  // ~24 px on screen = "clicked on the line" (the phone uses 24 dp)
  const mPerPx = 40075016 * Math.cos((lat * Math.PI) / 180) / (512 * 2 ** map.getZoom());
  const i = await engine.insertIndex(state.points, lat, lng, 24 * mPerPx);
  state.points.splice(i, 0, [lat, lng]);
  state.meta.splice(i, 0, { pinned: false });
  changed();
});

const key = ([lat, lon]: [number, number]) => `${lat.toFixed(6)},${lon.toFixed(6)}`;
const namesText = () => state.meta.map((m, i) => (m.name ? `${i}\t${m.name.replace(/[\t\n]/g, ' ')}` : '')).filter(Boolean).join('\n');
let roundTrip = false;

/** Markers + the Points list (the app's PlanPoints rows: names, leg distances, a round trip shown once with ↺). */
async function drawPoints() {
  const r = await engine.rows(state.points, namesText());
  roundTrip = r.roundTrip;
  markers.splice(0).forEach((m) => m.remove());
  const last = state.points.length - 1;
  state.points.forEach(([lat, lon], i) => {
    if (roundTrip && i === last) return; // the closing point sits on the start
    const el = document.createElement('div');
    el.className = 'pin ' + (i === 0 ? 'start' : i === last ? 'end' : 'via');
    el.textContent = i === 0 ? (roundTrip ? '↺' : 'S') : i === last ? 'Z' : String(i + 1);
    el.title = 'Drag to move, click for options';
    const m = new maplibregl.Marker({ element: el, draggable: true }).setLngLat([lon, lat]).addTo(map);
    // a dragged point is a new place: no longer pinned or named (as in the app); a loop's start moves its end too
    m.on('dragend', () => {
      const p = m.getLngLat();
      state.points[i] = [p.lat, p.lng]; state.meta[i] = { pinned: false };
      if (roundTrip && i === 0) { state.points[last] = [p.lat, p.lng]; state.meta[last] = { pinned: false }; }
      changed();
    });
    el.addEventListener('click', (ev) => { ev.stopPropagation(); pointMenu(i, m); });
    markers.push(m);
  });
  $('points').innerHTML = r.rows.map((row) =>
    `<li data-i="${row.i}">${row.loop ? '↺ ' : ''}${escapeHtml(row.name)}` +
    (row.legM != null ? ` <span class="muted">${row.approx ? '~' : ''}${(row.legM / 1000).toFixed(1)} km</span>` : '') + '</li>').join('');
  ($('roundTrip') as HTMLButtonElement).disabled = state.points.length < 2 || roundTrip;
  ($('reverse') as HTMLButtonElement).disabled = state.points.length < 2;
}

/**
 * Point menu, the app's rule (ui/MapGestures.pointMenu – in an Android file, so mirrored here, 6 lines): a middle
 * point of a normal route "Start here"; of a round trip "Start & finish here"; "Delete" always.
 */
let menu: maplibregl.Popup | null = null;
function pointMenu(i: number, m: maplibregl.Marker) {
  menu?.remove();
  const count = state.points.length - (roundTrip ? 0 : 0);
  const middle = i >= 1 && i < count - 1;
  const box = document.createElement('div');
  box.className = 'pointmenu';
  const item = (label: string, act: () => void, danger = false) => {
    const b = document.createElement('button');
    b.textContent = label; if (danger) b.className = 'danger';
    b.onclick = () => { menu?.remove(); act(); };
    box.append(b);
  };
  if (middle) item(roundTrip ? 'Start & finish here' : 'Start here', () => applyEdit('startHere', i));
  item('Delete', () => {
    // deleting a loop's start deletes the loop's closing copy too (the app shows them as one point)
    if (roundTrip && i === 0) { applyEdit('delete', state.points.length - 1).then(() => applyEdit('delete', 0)); }
    else applyEdit('delete', i);
  }, true);
  menu = new maplibregl.Popup({ offset: 18, closeButton: false }).setDOMContent(box).setLngLat(m.getLngLat()).addTo(map);
}

/** One of the app's plan edits (PlanEdits, in the engine); names and pins follow their points. */
async function applyEdit(op: 'reverse' | 'roundTrip' | 'startHere' | 'delete' | 'move', index = 0, to = 0) {
  const byKey = new Map(state.points.map((p, i) => [key(p), state.meta[i]]));
  state.points = await engine.edit(op, state.points, index, to);
  state.meta = state.points.map((p) => ({ ...(byKey.get(key(p)) ?? { pinned: false }) }));
  await changed();
}

// ---- planning ----
const engine = new EngineClient();
(window as unknown as { __engine: EngineClient }).__engine = engine; // for the browser tests (timings)
status('Loading the routing engine…');
const ready = engine.configure(base, params.get('segments') ?? base + 'segments/', PACKED).then((r) => {
  status(r.ok ? `Routing engine ready (${Math.round(r.stats?.engineLoadMs ?? 0)} ms)` : `Engine failed: ${r.error}`);
  return r.ok;
});
ready.then((ok) => { if (ok) openFromLink(); });
addEventListener('hashchange', () => openFromLink());

/** A plan in the address (#p=…, from a QR code or a shared link) replaces the current one. */
let linkChecked = false; // until then the address may still hold a plan to open – do not overwrite it
async function openFromLink() {
  linkChecked = true;
  if (!/[#&?]p=/.test(location.href)) return;
  const shared = await engine.parse(location.href);
  if (!shared) { status('This link does not contain a readable plan.'); return; }
  state.points = shared.points;
  state.meta = shared.points.map((_, i) => ({ pinned: shared.pinned.includes(i), name: shared.names[String(i)] }));
  state.title = shared.title;
  state.activity = shared.activity; state.sac = shared.sac;
  actSel.value = state.activity; sacSel.value = String(state.sac);
  if (shared.points.length) {
    const b = new maplibregl.LngLatBounds();
    shared.points.forEach(([la, lo]) => b.extend([lo, la]));
    map.fitBounds(b, { padding: 60, maxZoom: 15, duration: 0 });
  }
  changed();
}

/** The current plan in the app's format (for the QR code, the link and the address bar). */
function sharedPlan(): SharedPlan {
  const names: Record<string, string> = {};
  state.meta.forEach((m, i) => { if (m.name) names[String(i)] = m.name; });
  return { points: state.points, activity: state.activity, sac: state.sac,
    pinned: state.meta.flatMap((m, i) => (m.pinned ? [i] : [])), names, title: state.title };
}

/** Keeps the plan in the address bar (#p=…): a reload or a bookmark brings it back; nothing goes to a server. */
async function rememberInAddress() {
  if (!linkChecked) return;
  const page = location.href.split('#')[0];
  if (!state.points.length) { if (location.hash) history.replaceState(null, '', page); return; }
  if (!(await ready)) return;
  history.replaceState(null, '', page + '#p=' + await engine.encode(sharedPlan()));
}

async function changed() {
  await drawPoints();
  ($('share') as HTMLButtonElement).disabled = state.points.length < 2;
  rememberInAddress();
  $('sacRow').style.display = state.activity === 'HIKE' ? '' : 'none';
  if (state.points.length < 2) { state.plan = null; render(); return; }
  const id = ++state.latest;
  status('Planning…');
  const r = await engine.plan(state.points, state.activity, state.sac, sharedPlan().pinned);
  if (id !== state.latest) return; // a newer request supersedes this one
  if (!r.ok || !r.plan) { status(`Planning failed: ${r.error}`); return; }
  state.plan = r.plan;
  drawPoints(); // leg distances along the new route line (the engine now has this plan)
  const s = r.stats!;
  status(`Planned for ${ACTIVITIES.find(([k]) => k === state.activity)?.[1]}${state.activity === "HIKE" ? " T" + state.sac : ""} in ${Math.round(s.routeMs)} ms` + (s.bytesFetched ? ` · routing data ${(s.bytesFetched / 1e6).toFixed(1)} MB in ${Math.round(s.fetchMs)} ms` : ''));
  render();
}

function render() {
  const p = state.plan;
  (map.getSource('planned-route') as GeoJSONSource | undefined)?.setData(p ? {
    type: 'FeatureCollection',
    features: p.legs.map((l) => ({ type: 'Feature', properties: { straight: l.straight },
      geometry: { type: 'LineString', coordinates: l.points.map(([la, lo]) => [lo, la]) } })),
  } : empty());
  if (!p) { $('summary').textContent = 'Click the map to set ' + (state.points.length ? 'a destination.' : 'a start point.'); $('profile').innerHTML = ''; return; }
  const h = Math.floor(p.timeS / 3600), m = Math.round((p.timeS % 3600) / 60);
  $('summary').innerHTML = `<span class="big">${(p.distanceM / 1000).toFixed(1)} km</span>
    <span>↑ ${Math.round(p.ascentM)} m</span><span>↓ ${Math.round(p.descentM)} m</span>
    <span>${h} h ${String(m).padStart(2, '0')}</span><span class="badge">${p.difficulty}</span>` +
    (p.error ? `<div class="notice">${escapeHtml(p.error)}</div>` : '');
  $('profile').innerHTML = profileSvg(p.profile);
}

actSel.onchange = () => { state.activity = actSel.value as Activity; localStorageSet('activity', state.activity); changed(); };
sacSel.onchange = () => { state.sac = Number(sacSel.value); localStorageSet('sac', String(state.sac)); changed(); };
// ---- search (the app's search code in the engine, search.ts) ----
setupSearch({
  map, engine,
  areas: async () => (useCells ? cells.searchAreas(map) : [{ key: 'map', dir: mapFolder }]),
  points: () => state.points,
  route: () => state.plan?.legs.flatMap((l) => l.points.map(([la, lo]) => [la, lo] as [number, number])) ?? [],
  add: (h, role) => addPlace(h, role),
}, $('searchbox'));

/**
 * The place card's actions, as the app's addPlace: a pinned stop named after the place (passed exactly). "Add to
 * route" goes where the card said (WaypointInsertion via AddPreview); a place already planned: "As start" = the point
 * menu's Start here, "As destination" moves it to the end.
 */
async function addPlace(h: Hit, role: PlaceRole) {
  const p: [number, number] = [Number(h.lat.toFixed(6)), Number(h.lon.toFixed(6))];
  const pv = await engine.placePreview(state.points, p[0], p[1]);
  const meta = { pinned: true, name: h.title };
  if (pv.existing != null) {
    if (role === 'START') await applyEdit('startHere', pv.existing);
    else if (role === 'DESTINATION') await applyEdit('move', pv.existing, state.points.length - 1);
    return;
  }
  const at = role === 'START' ? 0 : role === 'DESTINATION' ? state.points.length : pv.index ?? state.points.length;
  state.points.splice(at, 0, p);
  state.meta.splice(at, 0, meta);
  await changed();
}

$('reverse').onclick = () => applyEdit('reverse');
$('roundTrip').onclick = () => applyEdit('roundTrip');
$('clear').onclick = () => { state.points = []; state.meta = []; state.title = null; changed(); };
$('share').onclick = async () => {
  const link = location.href.split('#')[0] + '#p=' + await engine.encode(sharedPlan());
  shareDialog(link, state.title);
};
changed();

function status(t: string) { $('status').textContent = t; }
function empty(): FeatureCollection { return { type: 'FeatureCollection', features: [] }; }
function escapeHtml(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
function localStorageGet(k: string): string | null { try { return localStorage.getItem('onpath.' + k); } catch { return null; } }
function localStorageSet(k: string, v: string) { try { localStorage.setItem('onpath.' + k, v); } catch { /* expected: storage blocked */ } }
