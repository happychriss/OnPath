// OnPath web planner (docs/design/web-planner.md §6–§11). The page mirrors the phone's Plan screen: search pill + ☰
// on top, the map controls (compass, eye, undo / redo, locate) bottom right, the plan sheet (activity chip, time,
// distance, climb + badge, profile, Points, one primary button) – a bottom sheet on a phone, a side panel on a wide
// screen. The app's own code decides and plans (compiled to Wasm, in a Web Worker): Planner + BRouter, RouteTimes,
// WaypointInsertion, PlanEdits, PlanPoints, PlanHistory (undo / redo), MapGestures (hold and point menus), PoiLinks
// (Google Maps link-out), GpxWriter. The map is the app's style + the same map files, drawn by maplibre-gl JS.
import * as maplibregl from 'maplibre-gl';
import type { StyleSpecification, GeoJSONSource, MapGeoJSONFeature } from 'maplibre-gl';
import type { FeatureCollection } from 'geojson';
import 'maplibre-gl/dist/maplibre-gl.css';
import { Protocol } from 'pmtiles';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { EngineClient } from './engine/client';
import type { Activity, PlanResult, SharedPlan, Hit } from './engine/types';
import { shareDialog } from './share';
import { profileSvg } from './profile';
import { PACKED, usePacked, packedGlyphs } from './packed';
import { Cells } from './cells';
import { setupSearch, type PlaceRole } from './search';
import { icon, activityIcon, needle } from './icons';
import { ActivityPicker, type ActivityRow } from './activity';
import { holdMenu, type MenuItem } from './menus';
import { Position } from './position';
import './style.css';

const ROUTE = '#1E3FAE';
const COLORS = { start: '#2E7D32', via: ROUTE, end: '#C62828' }; // OnPathColors.Good / Route / Bad (ui/PlanPanel.stopColor)
const base = new URL('./', location.href).href;
const params = new URLSearchParams(location.search);
const startAt = (params.get('at') ?? import.meta.env.VITE_START ?? '11.16,46.67,12').split(',').map(Number);

type Meta = { pinned: boolean; name?: string };
type LatLon = [number, number];
const state = {
  points: [] as LatLon[],
  /** Per point: pinned to a POI / place (passed exactly) and its name – kept from a received plan. */
  meta: [] as Meta[],
  title: null as string | null,
  activity: (localStorageGet('activity') as Activity) ?? 'HIKE',
  sac: Number(localStorageGet('sac') ?? 2),
  plan: null as PlanResult | null,
  planning: false,
  latest: 0,
  roundTrip: false,
  canUndo: false,
  canRedo: false,
};
/** Names and pins by point value, also for points only undo / redo can bring back (the app: PlanHistory.allPoints). */
const known = new Map<string, Meta>();

document.querySelector<HTMLDivElement>('#app')!.innerHTML = `
  <div id="map"></div>
  <div id="top">
    <div id="searchbox"></div>
    <button id="menuBtn" class="round" aria-label="Menu" aria-haspopup="menu">${icon('menu')}</button>
    <div id="mainMenu" class="menu" role="menu" hidden>
      <button id="menuShare" role="menuitem">${icon('phone')}Send to phone · Share</button>
      <hr><button id="menuAbout" role="menuitem">${icon('info')}About &amp; credits</button>
    </div>
  </div>
  <div id="footer"><button id="credits" class="credit" aria-label="Show map credits">© OpenStreetMap</button></div>
  <div id="fabs" class="fabgroup">
    <button id="compass" class="fab"></button>
    <button id="eye" class="fab" aria-label="Hold to hide the route" title="Hold to hide the route" hidden>${icon('eye')}</button>
    <button id="undo" class="fab" aria-label="Undo" title="Undo (Ctrl+Z)" hidden>${icon('undo')}</button>
    <button id="redo" class="fab" aria-label="Redo" title="Redo (Ctrl+Shift+Z)" hidden>${icon('redo')}</button>
    <button id="locate" class="fab"></button>
  </div>
  <section id="sheet" aria-label="Plan">
    <button id="handle" aria-label="Show or hide the points"><span></span></button>
    <div class="sheethead">
      <button id="activityChip" class="chip" aria-haspopup="dialog"></button>
      <div id="summary"><div class="headline"></div><div class="subline muted"></div></div>
      <button id="sheetMenuBtn" class="iconbtn" aria-label="Route actions" aria-haspopup="menu">${icon('more')}</button>
      <div id="sheetMenu" class="menu" role="menu" hidden>
        <button id="exportGpx" role="menuitem">${icon('download')}Export GPX</button>
        <hr><button id="clear" role="menuitem">${icon('delete')}Clear route</button>
      </div>
    </div>
    <div id="notice" class="notice" hidden></div>
    <div id="profile"></div>
    <div id="pointsSection" hidden>
      <div class="pointshead"><b>Points</b>
        <button id="reverse" class="textbtn">${icon('swap', 18)}Reverse</button>
        <button id="roundTrip" class="textbtn">${icon('loop', 18)}Round trip</button></div>
      <ol id="points"></ol>
    </div>
    <button id="share" class="primary wide" disabled>${icon('phone')}Send to phone · Share</button>
    <div id="status" class="status muted" role="status"></div>
  </section>`;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

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
  container: 'map', style: await loadStyle(), center: [startAt[0], startAt[1]], zoom: startAt[2],
  attributionControl: false, maxPitch: 0, // like the app: turn yes, tilt no
});
(window as unknown as { __map: maplibregl.Map }).__map = map; // for the browser tests
// the scale bar sits in our footer, above the short credit (the app's MapFooter)
$('footer').prepend(new maplibregl.ScaleControl({ unit: 'metric', maxWidth: 90 }).onAdd(map));
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
  render();
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

// ---- engine ----
const engine = new EngineClient();
(window as unknown as { __engine: EngineClient }).__engine = engine; // for the browser tests (timings)
status('Loading the routing engine…');
const ready = engine.configure(base, params.get('segments') ?? base + 'segments/', PACKED).then((r) => {
  status(r.ok ? `Routing engine ready (${Math.round(r.stats?.engineLoadMs ?? 0)} ms)` : `Engine failed: ${r.error}`, r.ok ? 'info' : 'error');
  return r.ok;
});
ready.then((ok) => { if (ok) { openFromLink(); prefetchView(); } });
addEventListener('hashchange', () => openFromLink());
// The routing data around the map view is fetched while the user looks at the map, so the first plan starts at once
// (docs/design/web-planner.md §11.2). Only from zoom 9: one 1° part is 2–10 MB.
let prefetchTimer = 0;
function prefetchView() {
  clearTimeout(prefetchTimer);
  prefetchTimer = window.setTimeout(() => {
    if (map.getZoom() < 9) return;
    const c = map.getCenter();
    engine.prefetch(c.lat, c.lng);
  }, 800);
}
map.on('moveend', () => { ready.then((ok) => { if (ok) prefetchView(); }); });

const enc = (pts: LatLon[]) => pts.map(([la, lo]) => `${la.toFixed(6)},${lo.toFixed(6)}`).join(';');
const dec = (t: string): LatLon[] => (t ? t.split(';').map((p) => p.split(',').map(Number) as LatLon) : []);
const key = ([lat, lon]: LatLon) => `${lat.toFixed(6)},${lon.toFixed(6)}`;
const namesText = () => state.meta.map((m, i) => (m.name ? `${i}\t${m.name.replace(/[\t\n]/g, ' ')}` : '')).filter(Boolean).join('\n');

// ---- plan changes: every change of the points is one undo step (the app's PlanHistory, in the engine) ----

/** Replaces the points (one undo step); names and pins follow their points by value. */
async function commit(points: LatLon[], meta?: Meta[]) {
  await engine.call('historyRecord', enc(state.points));
  setPoints(points, meta);
  await changed();
}

function setPoints(points: LatLon[], meta?: Meta[]) {
  state.points = points;
  state.meta = meta ?? points.map((p) => ({ ...(known.get(key(p)) ?? { pinned: false }) }));
  state.points.forEach((p, i) => known.set(key(p), state.meta[i]));
}

/** One of the app's plan edits (PlanEdits, in the engine). */
async function applyEdit(op: 'reverse' | 'roundTrip' | 'startHere' | 'delete' | 'move', index = 0, to = 0) {
  closeMenu();
  await commit(await engine.edit(op, state.points, index, to));
}

async function step(op: 'undo' | 'redo') {
  closeMenu();
  const t = await engine.call('historyStep', op, enc(state.points));
  if (t === 'null') return;
  setPoints(dec(t));
  await changed();
}

/** Deletes a point; a round trip's start goes with its closing copy (the app shows them as one point). */
async function deletePoint(i: number) {
  closeMenu();
  const last = state.points.length - 1;
  const drop = state.roundTrip && (i === 0 || i === last) ? [0, last] : [i];
  await commit(state.points.filter((_, k) => !drop.includes(k)), state.meta.filter((_, k) => !drop.includes(k)));
}

// ---- markers ----
const markers: maplibregl.Marker[] = [];
let draggedAt = 0;
let hidden = false; // the eye is held

/** stopLabel of the app (ui/MapOverlays.stopLabel – an Android file, so mirrored: 4 lines). */
function stopLabel(i: number, count: number, name?: string | null) {
  return name?.trim() ? name : i === 0 ? 'Start' : i === count - 1 && count > 1 ? 'Destination' : `Stop ${i + 1}`;
}
const stopColor = (i: number, count: number) => (i === 0 ? COLORS.start : i === count - 1 && count > 1 ? COLORS.end : COLORS.via);

/** Markers + the Points list (the app's PlanPoints rows: names, leg distances, a round trip shown once with ↺). */
async function drawPoints() {
  const r = await engine.rows(state.points, namesText());
  state.roundTrip = r.roundTrip;
  markers.splice(0).forEach((m) => m.remove());
  const n = state.points.length, last = n - 1;
  state.points.forEach(([lat, lon], i) => {
    if (state.roundTrip && i === last) return; // the closing point sits on the start
    const el = document.createElement('div');
    el.className = 'pin';
    el.style.background = state.roundTrip && i === 0 ? COLORS.start : stopColor(i, n);
    el.textContent = state.roundTrip && i === 0 ? '↺' : String(i + 1);
    el.title = `${stopLabel(i, n, state.meta[i]?.name)} – click for options, drag to move`;
    el.style.visibility = hidden ? 'hidden' : '';
    const m = new maplibregl.Marker({ element: el, draggable: true }).setLngLat([lon, lat]).addTo(map);
    // a dragged point is a new place: no longer pinned or named (as in the app); a loop's start moves its end too
    m.on('dragend', () => {
      draggedAt = Date.now();
      const p = m.getLngLat();
      const pts = state.points.slice(), meta = state.meta.slice();
      pts[i] = [p.lat, p.lng]; meta[i] = { pinned: false };
      if (state.roundTrip && i === 0) { pts[last] = [p.lat, p.lng]; meta[last] = { pinned: false }; }
      commit(pts, meta);
    });
    el.addEventListener('click', (ev) => {
      ev.stopPropagation();
      if (Date.now() - draggedAt < 300) return; // the click that ends a drag
      press(m.getLngLat(), i);
    });
    markers.push(m);
  });
  $('points').innerHTML = r.rows.map((row) => {
    const role = row.role === 'START' ? 'Start' : row.role === 'GOAL' ? 'Destination' : '';
    const color = row.loop ? COLORS.start : stopColor(row.i, n);
    return `<li data-i="${row.i}" draggable="true">
      <span class="grip" title="Drag to change the order">${icon('drag', 20)}</span>
      <span class="num" style="background:${color}">${row.loop ? '↺' : row.i + 1}</span>
      <span class="txt"><span>${escapeHtml(row.name)}</span>${role || row.loop ? `<span class="muted small">${row.loop ? 'Start & finish' : role}</span>` : ''}</span>
      ${row.legM != null ? `<span class="leg muted">${row.approx ? '~' : '+'}${(row.legM / 1000).toFixed(1)} km</span>` : '<span class="leg"></span>'}
      <button class="del iconbtn" aria-label="Delete ${escapeHtml(row.name)}">${icon('close', 20)}</button></li>`;
  }).join('');
  $<HTMLButtonElement>('roundTrip').disabled = n < 2 || state.roundTrip;
  $<HTMLButtonElement>('reverse').disabled = n < 2;
  $('pointsSection').hidden = n === 0;
}

// Points list: click = show on the map, ✕ = delete, drag = new order (PlanEdits.move)
let dragFrom = -1;
$('points').addEventListener('click', (e) => {
  const li = (e.target as HTMLElement).closest('li');
  if (!li) return;
  const i = Number(li.dataset.i);
  if ((e.target as HTMLElement).closest('.del')) { deletePoint(i); return; }
  const p = state.points[i];
  if (p) map.easeTo({ center: [p[1], p[0]], zoom: Math.max(map.getZoom(), 14) });
});
$('points').addEventListener('dragstart', (e) => { dragFrom = Number((e.target as HTMLElement).closest('li')?.dataset.i ?? -1); });
$('points').addEventListener('dragover', (e) => { if (dragFrom >= 0) e.preventDefault(); });
$('points').addEventListener('drop', (e) => {
  e.preventDefault();
  const to = Number((e.target as HTMLElement).closest('li')?.dataset.i ?? -1);
  if (dragFrom >= 0 && to >= 0 && to !== dragFrom) applyEdit('move', dragFrom, to);
  dragFrom = -1;
});

// ---- presses on the map: a click is the app's hold (MapGestures.decide, in the engine) ----
let menu: maplibregl.Popup | null = null;
function closeMenu() { menu?.remove(); menu = null; }
function openMenu(at: maplibregl.LngLat, items: MenuItem[], head: string) {
  closeMenu();
  const m = new maplibregl.Popup({ offset: 18, closeButton: false, closeOnClick: false, maxWidth: '320px', className: 'holdpopup' })
    .setDOMContent(holdMenu(head, items, closeMenu)).setLngLat(at).addTo(map);
  m.on('close', () => { if (menu === m) menu = null; });
  menu = m;
}

map.on('click', (e) => press(e.lngLat, -1, e.point));
map.on('contextmenu', (e) => { e.preventDefault(); press(e.lngLat, -1, e.point); }); // right-click / long press: the same

async function press(at: maplibregl.LngLat, marker: number, pt?: maplibregl.Point) {
  if (marker < 0 && pt) marker = markerNear(pt);
  const poi = pt && marker < 0 ? poiAt(pt) : null;
  const action = await engine.call('gesture', menu != null, marker, poi != null, state.points.length === 0);
  if (action.startsWith('PointMenu:')) pointMenu(Number(action.split(':')[1]));
  else if (action === 'PoiMenu' && poi) poiMenu(poi);
  else if (action === 'FirstPointMenu') firstPointMenu(at);
  else if (action === 'AddPoint') addPoint(at);
  else if (action === 'CloseMenu') closeMenu();
}

/** The marker within reach of the click (the app: MapGestures.nearestMarker; 20 px for a mouse, 48 for a finger). */
function markerNear(pt: maplibregl.Point): number {
  const r = matchMedia('(pointer: coarse)').matches ? 48 : 20;
  let best = -1, bestD = r * r;
  state.points.forEach(([la, lo], i) => {
    if (state.roundTrip && i === state.points.length - 1) return;
    const s = map.project([lo, la]);
    const d = (s.x - pt.x) ** 2 + (s.y - pt.y) ** 2;
    if (d <= bestD) { bestD = d; best = i; }
  });
  return best;
}

function poiAt(pt: maplibregl.Point): MapGeoJSONFeature | null {
  const layers = map.getStyle().layers.filter((l) => l.id.startsWith('poi-')).map((l) => l.id); // POI_LAYER_PREFIX
  if (!layers.length) return null;
  const f = map.queryRenderedFeatures([[pt.x - 10, pt.y - 10], [pt.x + 10, pt.y + 10]], { layers });
  return f.find((x) => x.geometry.type === 'Point') ?? null;
}

/** A click on the empty map or the line: the point goes where the app puts it (WaypointInsertion, in the engine). */
async function addPoint(at: maplibregl.LngLat) {
  // ~24 px on screen = "clicked on the line" (the phone uses 24 dp)
  const mPerPx = 40075016 * Math.cos((at.lat * Math.PI) / 180) / (512 * 2 ** map.getZoom());
  const i = await engine.insertIndex(state.points, at.lat, at.lng, 24 * mPerPx);
  const pts = state.points.slice(), meta = state.meta.slice();
  pts.splice(i, 0, [at.lat, at.lng]); meta.splice(i, 0, { pinned: false });
  await commit(pts, meta);
}

const position = new Position(map, () => renderFabs());

/** "Go here": the item and its reason when it cannot be used (the app's MapGestures.fromMeBlocked). */
async function goHereItem(onGo: () => void): Promise<MenuItem> {
  const blocked = position.status === 'UNKNOWN' ? '' : await engine.call('fromMeBlocked', position.status, position.fix != null);
  return { icon: 'gpsFixed', label: 'Go here', sub: blocked || (position.fix ? 'From your position' : 'From your position – asks for it'),
    disabled: !!blocked, act: onGo };
}

/** From my position to [to] (first point = me, unpinned). */
async function fromMe(to: LatLon, toMeta: Meta) {
  status('Finding your position…');
  const me = await position.once();
  if (!me) { status('Your position is not available. Set the start on the map.', 'error'); return; }
  status('');
  await commit([me, to], [{ pinned: false }, toMeta]);
}

/** First press on an empty plan: "Start here" · "Go here" (ui/MapOverlays.FirstPointMenu). */
async function firstPointMenu(at: maplibregl.LngLat) {
  const p: LatLon = [at.lat, at.lng];
  openMenu(at, [
    { icon: 'tripOrigin', label: 'Start here', act: () => commit([p], [{ pinned: false }]) },
    await goHereItem(() => fromMe(p, { pinned: false })),
  ], `<b>New route</b>`);
}

/** Point menu: number + name, then the app's entries for this point (MapGestures.pointMenu, in the engine). */
async function pointMenu(i: number) {
  const n = state.points.length, name = state.meta[i]?.name;
  const [lat, lon] = state.points[i];
  const acts: string[] = JSON.parse(await engine.call('pointMenu', i, n, !!name?.trim(), state.roundTrip));
  const items: MenuItem[] = acts.map((a): MenuItem => {
    if (a === 'MAKE_START') return { icon: 'tripOrigin', label: 'Start here', sub: `Removes the ${i === 1 ? '1 point' : `${i} points`} before it`, act: () => applyEdit('startHere', i) };
    if (a === 'LOOP_START') return { icon: 'tripOrigin', label: 'Start & finish here', sub: 'Same round trip, starts here', act: () => applyEdit('startHere', i) };
    if (a === 'GOOGLE') return { icon: 'map', label: 'Open in Google Maps', act: () => google(name ?? '', lat, lon, '') };
    return { icon: 'delete', label: 'Delete', danger: true, act: () => deletePoint(i) };
  });
  const color = state.roundTrip && i === 0 ? COLORS.start : stopColor(i, n);
  openMenu(new maplibregl.LngLat(lon, lat), items,
    `<span class="num" style="background:${color}">${state.roundTrip && i === 0 ? '↺' : i + 1}</span><b>${escapeHtml(stopLabel(i, n, name))}</b>`);
}

/** POI menu (ui/MapOverlays.PoiMenu): name, kind · elevation; Add to route (empty plan: Start here · Go here); Google. */
async function poiMenu(f: MapGeoJSONFeature) {
  const [lon, lat] = (f.geometry as GeoJSON.Point).coordinates;
  const pr = f.properties ?? {};
  const info = JSON.parse(await engine.call('poiMenu', String(pr.kind ?? ''), String(pr.name ?? ''), pr.ele != null ? String(pr.ele) : '', lat, lon));
  if (!info) return;
  const hit = { title: info.title, name: pr.name ? String(pr.name) : null, lat, lon } as Hit;
  const items: MenuItem[] = state.points.length === 0
    ? [{ icon: 'tripOrigin', label: 'Start here', act: () => addPlace(hit, 'START') },
      await goHereItem(() => fromMe([Number(lat.toFixed(6)), Number(lon.toFixed(6))], { pinned: true, name: info.title }))]
    : [{ icon: 'addLocation', label: 'Add to route', act: () => addPlace(hit, 'SMART') }];
  items.push({ icon: 'map', label: 'Open in Google Maps', act: () => window.open(info.google, '_blank', 'noopener') });
  openMenu(new maplibregl.LngLat(lon, lat), items,
    `<span class="txt"><b>${escapeHtml(info.title)}</b><span class="small" style="color:${info.color}">${escapeHtml(info.sub)}</span></span>`);
}

/** "Open in Google Maps": the app's link (PoiLinks.googleMaps), in a new tab – Google content is never drawn here. */
async function google(name: string, lat: number, lon: number, label: string) {
  window.open(await engine.call('googleMaps', name, lat, lon, label), '_blank', 'noopener');
}

// ---- shared links ----

/** A plan in the address (#p=…, from a QR code or a shared link) replaces the current one. */
let linkChecked = false; // until then the address may still hold a plan to open – do not overwrite it
async function openFromLink() {
  linkChecked = true;
  if (!/[#&?]p=/.test(location.href)) return;
  const shared = await engine.parse(location.href);
  if (!shared) { status('This link does not contain a readable plan.', 'error'); return; }
  if (shared.points.map(key).join(';') === state.points.map(key).join(';') && shared.activity === state.activity && shared.sac === state.sac) return; // our own address update
  state.title = shared.title;
  setActivity(shared.activity, shared.sac, false);
  if (shared.points.length) {
    const b = new maplibregl.LngLatBounds();
    shared.points.forEach(([la, lo]) => b.extend([lo, la]));
    map.fitBounds(b, { padding: fitPadding(), maxZoom: 15, duration: 0 });
  }
  await commit(shared.points, shared.points.map((_, i) => ({ pinned: shared.pinned.includes(i), name: shared.names[String(i)] })));
}

/** Room for the top bar, the sheet (side panel or bottom sheet) and the map controls when the camera fits a plan. */
function fitPadding(): maplibregl.PaddingOptions {
  const sheet = $('sheet').getBoundingClientRect();
  const phone = matchMedia('(max-width: 700px)').matches;
  return phone ? { top: 90, bottom: sheet.height + 30, left: 40, right: 80 } : { top: 90, bottom: 50, left: sheet.right + 50, right: 90 };
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

// ---- planning ----
async function changed() {
  await drawPoints();
  const h = JSON.parse(await engine.call('historyState'));
  state.canUndo = h.canUndo; state.canRedo = h.canRedo;
  $<HTMLButtonElement>('share').disabled = state.points.length < 2;
  rememberInAddress();
  if (state.points.length < 2) { state.plan = null; state.planning = false; render(); return; }
  const id = ++state.latest;
  state.planning = true;
  render();
  status('Planning…');
  const r = await engine.plan(state.points, state.activity, state.sac, sharedPlan().pinned);
  if (id !== state.latest) return; // a newer request supersedes this one
  state.planning = false;
  if (!r.ok || !r.plan) { status(`Planning failed: ${r.error}`, 'error'); render(); return; }
  state.plan = r.plan;
  drawPoints(); // leg distances along the new route line (the engine now has this plan)
  const s = r.stats!;
  status(`Planned in ${Math.round(s.routeMs)} ms` + (s.bytesFetched ? ` · routing data ${(s.bytesFetched / 1e6).toFixed(1)} MB in ${Math.round(s.fetchMs)} ms` : ''));
  render();
}

/** The sheet's texts follow the app's PlanStatus.noRouteLine ("Hold" → "Click"; the web does not record). */
function render() {
  const p = state.points.length >= 2 ? state.plan : null;
  (map.getSource('planned-route') as GeoJSONSource | undefined)?.setData(p ? {
    type: 'FeatureCollection',
    features: p.legs.map((l) => ({ type: 'Feature', properties: { straight: l.straight },
      geometry: { type: 'LineString', coordinates: l.points.map(([la, lo]) => [lo, la]) } })),
  } : empty());
  const head = document.querySelector<HTMLDivElement>('#summary .headline')!;
  const sub = document.querySelector<HTMLDivElement>('#summary .subline')!;
  const notice = $('notice');
  if (!p) {
    head.innerHTML = '<span class="title">Plan a route</span>';
    const n = state.points.length;
    sub.textContent = n === 0 ? 'Click the map where you want to start or go.'
      : n === 1 ? 'Click the map to set the destination.' : 'Calculating the route…';
    $('profile').innerHTML = '';
    notice.hidden = true;
  } else {
    head.innerHTML = `<span class="big">${escapeHtml(p.texts.time)}</span><span class="dist">${escapeHtml(p.texts.distance)}</span>`;
    sub.innerHTML = `<span>${escapeHtml(p.texts.climb)}</span>` +
      `<span class="badge ${p.difficulty.split(' ')[0].toLowerCase()}" ${p.because ? `title="${escapeHtml(p.because)}"` : ''}>${escapeHtml(p.difficulty)}</span>`;
    $('profile').innerHTML = profileSvg(p.profile);
    notice.hidden = !p.error;
    notice.innerHTML = p.error ? `⚠ ${escapeHtml(p.error)}` : '';
  }
  $('sheet').classList.toggle('busy', state.planning);
  renderChip();
  renderFabs();
}

// ---- activity (the app's picker: six activities, Hike with its T level) ----
const picker = new ActivityPicker((a, sac) => setActivity(a, sac, true));
engine.call('activities').then((t) => { picker.setRows(JSON.parse(t) as ActivityRow[]); renderChip(); }).catch((e) => status(`Activity names not loaded: ${e}`, 'error'));
function renderChip() {
  const row = picker.row(state.activity);
  $('activityChip').innerHTML = `${activityIcon(state.activity, 20)}<span>${escapeHtml(row?.chip ?? state.activity)}` +
    `${state.activity === 'HIKE' ? ' · T' + state.sac : ''}</span>${icon('dropDown', 18)}`;
  $('activityChip').setAttribute('aria-label', `Activity: ${row?.label ?? state.activity}${state.activity === 'HIKE' ? ' T' + state.sac : ''}. Change`);
}
function setActivity(a: Activity, sac: number, plan: boolean) {
  const same = a === state.activity && sac === state.sac;
  state.activity = a; state.sac = sac;
  localStorageSet('activity', a); localStorageSet('sac', String(sac));
  renderChip();
  if (plan && !same) changed();
}
$('activityChip').onclick = () => picker.open(state.activity, state.sac);

// ---- map controls (the app's MapControls: compass, eye, undo, redo, locate) ----
function renderFabs() {
  const b = map.getBearing();
  const c = $('compass');
  c.innerHTML = needle(b);
  const what = Math.abs(b) > 1 ? 'Map is turned. Click for north up' : 'Map is north up. Turn it with the right mouse button or two fingers';
  c.title = what; c.setAttribute('aria-label', what);
  $('eye').hidden = !state.plan || state.points.length < 2;
  $('undo').hidden = !state.canUndo;
  $('redo').hidden = !state.canRedo;
  const l = $('locate');
  l.innerHTML = icon(position.following ? 'gpsFixed' : 'gpsNotFixed') + `<span class="gpsdot ${position.dotClass}"></span>`;
  l.classList.toggle('on', position.following);
  const lw = position.status === 'OFF' ? 'Location is off or not available here' : position.status === 'NO_PERMISSION'
    ? 'Location not allowed in this browser' : 'Centre on my position';
  l.title = lw; l.setAttribute('aria-label', lw);
}
map.on('rotate', () => renderFabs());
$('compass').onclick = () => map.easeTo({ bearing: 0, pitch: 0 });
$('undo').onclick = () => step('undo');
$('redo').onclick = () => step('redo');
$('locate').onclick = () => position.locate();
/** Eye: hold to hide the route and the markers, release shows them again (the app's EyeControl). */
function peek(on: boolean) {
  if (hidden === on) return;
  hidden = on;
  for (const id of ['planned-route-casing', 'planned-route', 'planned-straight']) if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', on ? 'none' : 'visible');
  markers.forEach((m) => { m.getElement().style.visibility = on ? 'hidden' : ''; });
  $('eye').innerHTML = icon(on ? 'eyeOff' : 'eye');
  $('eye').classList.toggle('on', on);
}
const eye = $('eye');
eye.addEventListener('pointerdown', (e) => { eye.setPointerCapture(e.pointerId); peek(true); });
for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture']) eye.addEventListener(ev, () => peek(false));
eye.addEventListener('keydown', (e) => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); peek(true); } });
eye.addEventListener('keyup', () => peek(false));
eye.addEventListener('contextmenu', (e) => e.preventDefault()); // a long press must not open the browser's menu
window.addEventListener('keydown', (e: KeyboardEvent) => {
  const typing = (e.target as HTMLElement).closest('input, textarea');
  if (e.key === 'Escape') { closeMenu(); closeMenus(); }
  if (typing || !(e.ctrlKey || e.metaKey)) return;
  const k = e.key.toLowerCase();
  if (k === 'z' && !e.shiftKey) { e.preventDefault(); step('undo'); }
  else if ((k === 'z' && e.shiftKey) || k === 'y') { e.preventDefault(); step('redo'); }
});

// ---- search (the app's search code in the engine, search.ts) ----
setupSearch({
  map, engine,
  areas: async () => (useCells ? cells.searchAreas(map) : [{ key: 'map', dir: mapFolder }]),
  points: () => state.points,
  route: () => state.plan?.legs.flatMap((l) => l.points.map(([la, lo]) => [la, lo] as LatLon)) ?? [],
  add: (h, role) => addPlace(h, role),
  google: (h) => google(h.name ?? '', h.lat, h.lon, h.name ? '' : h.title),
}, $('searchbox'));

/**
 * The place card's actions, as the app's addPlace: a pinned stop named after the place (passed exactly). "Add to
 * route" goes where the card said (WaypointInsertion via AddPreview); a place already planned: "As start" = the point
 * menu's Start here, "As destination" moves it to the end, "Remove from route" deletes it.
 */
async function addPlace(h: Hit, role: PlaceRole) {
  closeMenu();
  const p: LatLon = [Number(h.lat.toFixed(6)), Number(h.lon.toFixed(6))];
  const pv = await engine.placePreview(state.points, p[0], p[1]);
  if (pv.existing != null) {
    if (role === 'START') await applyEdit('startHere', pv.existing);
    else if (role === 'DESTINATION') await applyEdit('move', pv.existing, state.points.length - 1);
    else if (role === 'REMOVE') await deletePoint(pv.existing);
    return;
  }
  const at = role === 'START' ? 0 : role === 'DESTINATION' ? state.points.length : pv.index ?? state.points.length;
  const pts = state.points.slice(), meta = state.meta.slice();
  pts.splice(at, 0, p); meta.splice(at, 0, { pinned: true, name: h.title });
  await commit(pts, meta);
}

// ---- menus, sheet, dialogs ----
function toggleMenu(btn: string, box: string) {
  const m = $(box), open = m.hidden;
  closeMenus();
  m.hidden = !open;
  $(btn).setAttribute('aria-expanded', String(open));
}
function closeMenus() {
  for (const [b, m] of [['menuBtn', 'mainMenu'], ['sheetMenuBtn', 'sheetMenu']]) { $(m).hidden = true; $(b).setAttribute('aria-expanded', 'false'); }
}
$('menuBtn').onclick = (e) => { e.stopPropagation(); toggleMenu('menuBtn', 'mainMenu'); };
$('sheetMenuBtn').onclick = (e) => { e.stopPropagation(); $<HTMLButtonElement>('exportGpx').disabled = !state.plan; toggleMenu('sheetMenuBtn', 'sheetMenu'); };
document.addEventListener('click', (e) => { if (!(e.target as HTMLElement).closest('.menu')) closeMenus(); });

$('reverse').onclick = () => applyEdit('reverse');
$('roundTrip').onclick = () => applyEdit('roundTrip');
$('clear').onclick = () => { closeMenus(); state.title = null; commit([], []); };
$('exportGpx').onclick = async () => {
  closeMenus();
  const r = JSON.parse(await engine.call('gpx', state.title ?? '', state.activity, state.sac));
  if (!r) return;
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([r.gpx], { type: 'application/gpx+xml' }));
  a.download = r.file;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
};
const share = async () => {
  closeMenus();
  if (state.points.length < 2) { status('Set a start and a destination first.'); return; }
  shareDialog(location.href.split('#')[0] + '#p=' + await engine.encode(sharedPlan()), state.title);
};
$('share').onclick = share;
$('menuShare').onclick = share;
$('menuAbout').onclick = () => { closeMenus(); credits(); };
$('credits').onclick = () => credits();
$('handle').onclick = () => $('sheet').classList.toggle('expanded');

/** Full credits (the app's Settings → About, MAP_CREDITS); the map shows only "© OpenStreetMap". */
function credits() {
  const d = document.createElement('dialog');
  d.className = 'share';
  d.innerHTML = `<h2>OnPath web planner</h2>
    <p class="muted">A test page: the OnPath app's own planning code in your browser. Plans stay in the link – nothing is stored on a server.</p>
    <b>Credits</b><ul class="credits">${['Map data © OpenStreetMap contributors (ODbL)', 'Vector tile schema © OpenMapTiles',
      'Terrain: Terrarium tiles (Mapzen / AWS Terrain Tiles; SRTM, Copernicus and others)', 'Routing: BRouter (MIT)',
      'Search online: Photon by komoot (OpenStreetMap data)', 'POI icons: Maki and Temaki (CC0)',
      'Map renderer: MapLibre GL JS (BSD licence)'].map((t) => `<li>${t}</li>`).join('')}</ul>
    <p class="muted small">Licence: Apache-2.0</p><div class="row"><button class="primary" id="closeCredits">Close</button></div>`;
  document.body.append(d);
  d.querySelector<HTMLButtonElement>('#closeCredits')!.onclick = () => d.close();
  d.addEventListener('close', () => d.remove());
  d.showModal();
}

// phone layout: the map controls and the footer sit above the sheet
new ResizeObserver(() => document.documentElement.style.setProperty('--sheet-h', $('sheet').offsetHeight + 'px')).observe($('sheet'));
changed();

function status(t: string, kind: 'info' | 'error' = 'info') { const s = $('status'); s.textContent = t; s.classList.toggle('error', kind === 'error'); }
function empty(): FeatureCollection { return { type: 'FeatureCollection', features: [] }; }
function escapeHtml(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
function localStorageGet(k: string): string | null { try { return localStorage.getItem('onpath.' + k); } catch { return null; } } // expected: storage blocked
function localStorageSet(k: string, v: string) { try { localStorage.setItem('onpath.' + k, v); } catch { /* expected: storage blocked */ } }
