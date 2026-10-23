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
import type { Activity, PlanResult } from './engine/types';
import { profileSvg } from './profile';
import './style.css';

const ACTIVITIES: [Activity, string][] = [
  ['BIKE_TOURING', 'Bike touring'], ['BIKE_TREKKING', 'Bike trekking'], ['EBIKE', 'E-bike'],
  ['MTB', 'MTB'], ['EMTB', 'MTB e-bike'], ['HIKE', 'Hike'],
];
const ROUTE = '#1E3FAE';
const base = new URL('./', location.href).href;
const params = new URLSearchParams(location.search);

const state = {
  points: [] as [number, number][],
  activity: (localStorageGet('activity') as Activity) ?? 'HIKE',
  sac: Number(localStorageGet('sac') ?? 2),
  plan: null as PlanResult | null,
  latest: 0,
};

document.querySelector<HTMLDivElement>('#app')!.innerHTML = `
  <div id="map"></div>
  <aside id="panel">
    <header><b>OnPath</b> <span class="muted">web planner · test</span></header>
    <label>Activity <select id="activity">${ACTIVITIES.map(([k, l]) => `<option value="${k}">${l}</option>`).join('')}</select></label>
    <label id="sacRow">Trail difficulty <select id="sac">${[1, 2, 3, 4, 5].map((t) => `<option value="${t}">T${t}</option>`).join('')}</select></label>
    <div id="summary" class="summary muted">Click the map to set a start point.</div>
    <div id="profile"></div>
    <ol id="points"></ol>
    <div class="row"><button id="reverse">Reverse</button><button id="clear">Clear</button></div>
    <div id="status" class="status muted"></div>
  </aside>`;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const actSel = $<HTMLSelectElement>('activity');
const sacSel = $<HTMLSelectElement>('sac');
actSel.value = state.activity; sacSel.value = String(state.sac);

// ---- map ----
maplibregl.setWorkerUrl(maplibreWorkerUrl); // the bundle moves maplibre's module worker; tell it where
maplibregl.addProtocol('pmtiles', new Protocol().tile);
const map = new maplibregl.Map({
  container: 'map', style: await loadStyle(), center: [11.16, 46.67], zoom: 12, attributionControl: false,
});
(window as unknown as { __map: maplibregl.Map }).__map = map; // for the browser tests
map.addControl(new maplibregl.NavigationControl({ visualizePitch: false }), 'top-right');
map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-right');
map.addControl(new maplibregl.AttributionControl({ compact: true, customAttribution: '© OpenStreetMap contributors, © OpenMapTiles' }));
map.on('load', () => {
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
  const data = params.get('data') ?? base + 'map/';
  const fix = (u: string) => u.replace('pmtiles://file://{DATA_DIR}/', 'pmtiles://' + data).replace('file://{DATA_DIR}/', data);
  s.glyphs = base + 'fonts/{fontstack}/{range}.pbf';
  s.sprite = base + 'sprites/onpath';
  const missing = new Set<string>();
  await Promise.all(Object.entries(s.sources).map(async ([id, src]) => {
    const x = src as { url?: string; data?: unknown };
    if (typeof x.url === 'string') x.url = fix(x.url);
    if (typeof x.data === 'string') x.data = fix(x.data);
    const file = (x.url ?? (x.data as string)).replace('pmtiles://', '');
    const ok = await fetch(file, { method: 'HEAD' }).then((r) => r.ok).catch(() => false);
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
  changed();
});

function drawMarkers() {
  markers.splice(0).forEach((m) => m.remove());
  state.points.forEach(([lat, lon], i) => {
    const el = document.createElement('div');
    el.className = 'pin ' + (i === 0 ? 'start' : i === state.points.length - 1 ? 'end' : 'via');
    el.textContent = i === 0 ? 'S' : i === state.points.length - 1 ? 'Z' : String(i);
    el.title = 'Drag to move, click to delete';
    const m = new maplibregl.Marker({ element: el, draggable: true }).setLngLat([lon, lat]).addTo(map);
    m.on('dragend', () => { const p = m.getLngLat(); state.points[i] = [p.lat, p.lng]; changed(); });
    el.addEventListener('click', (ev) => { ev.stopPropagation(); state.points.splice(i, 1); changed(); });
    markers.push(m);
  });
  $('points').innerHTML = state.points.map(([lat, lon], i) =>
    `<li>${i === 0 ? 'Start' : i === state.points.length - 1 ? 'Destination' : 'Via ' + i} <span class="muted">${lat.toFixed(4)}, ${lon.toFixed(4)}</span></li>`).join('');
}

// ---- planning ----
const engine = new EngineClient();
status('Loading the routing engine…');
engine.configure(base, params.get('segments') ?? base + 'segments/').then((r) =>
  status(r.ok ? `Routing engine ready (${Math.round(r.stats?.engineLoadMs ?? 0)} ms)` : `Engine failed: ${r.error}`));

async function changed() {
  drawMarkers();
  $('sacRow').style.display = state.activity === 'HIKE' ? '' : 'none';
  if (state.points.length < 2) { state.plan = null; render(); return; }
  const id = ++state.latest;
  status('Planning…');
  const r = await engine.plan(state.points, state.activity, state.sac);
  if (id !== state.latest) return; // a newer request supersedes this one
  if (!r.ok || !r.plan) { status(`Planning failed: ${r.error}`); return; }
  state.plan = r.plan;
  const s = r.stats!;
  status(`Planned in ${Math.round(s.routeMs)} ms` + (s.bytesFetched ? ` · routing data ${(s.bytesFetched / 1e6).toFixed(1)} MB in ${Math.round(s.fetchMs)} ms` : ''));
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
$('reverse').onclick = () => { state.points.reverse(); changed(); };
$('clear').onclick = () => { state.points = []; changed(); };
changed();

function status(t: string) { $('status').textContent = t; }
function empty(): FeatureCollection { return { type: 'FeatureCollection', features: [] }; }
function escapeHtml(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
function localStorageGet(k: string): string | null { try { return localStorage.getItem('onpath.' + k); } catch { return null; } }
function localStorageSet(k: string, v: string) { try { localStorage.setItem('onpath.' + k, v); } catch { /* expected: storage blocked */ } }
