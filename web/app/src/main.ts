// OnPath web planner (docs/design/web-planner.md §6–§13). The page mirrors the phone's Plan screen in a web layout: a
// header line (logo, My routes, Pair phone / Paired, Save route, …), the search pill with the map toolbar beside it (undo / redo, eye,
// compass, locate, Map content), the plan sheet (activity chip, time,
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
import { setupSearch, type PlaceCard, type PlaceRole } from './search';
import { NO_PADDING, roomFor } from './cardanchor';
import { icon, activityIcon, needle, logo, type IconName } from './icons';
import { ActivityPicker, type ActivityRow, type PaceChoices } from './activity';
import { holdMenu, type MenuItem } from './menus';
import { Position } from './position';
import { PlanSync } from './sync';
import { myPlansDialog, joinDialog, requestSave, phoneDialog, phoneState } from './plans';
import { MapContentPanel } from './mapcontent';
import { sortable } from './sortable';
import { longRouteOk, allowLongRoute, notRoutedTexts, type Before } from './longroute';
import { setupGpxImport } from './gpximport';
import { overviewStyle, followCover, followPackages } from './overview';
import { PlanGapsLayer } from './plangaps';
import { MapNotices } from './mapnotice';
import { MapAreas, type MapTexts } from './areas';
import { t, tp, lang, languages, switchLanguage, number, distance, locale } from './i18n';
import { appHint } from './apphint';
import { setupSheetSteps } from './sheetstep';
import { installDiag, addDiagSection, problem } from './diag';
import './fonts/manrope.css';
import './style.css';
import manropeLicence from './fonts/manrope/OFL.txt?url';

const ROUTE = '#1E3FAE';
const COLORS = { start: '#2E7D32', via: ROUTE, end: '#C62828' }; // OnPathColors.Good / Route / Bad (ui/PlanPanel.stopColor)
const base = new URL('./', location.href).href;
const params = new URLSearchParams(location.search);
/** ?debug: developer lines ("Planned in … ms · routing data …") in the sheet's status line (owner 2026-10-30: hidden otherwise). */
const DEBUG = params.has('debug');
// start view: ?at=lon,lat,zoom, else the view kept over the language switch's reload (i18n.switchLanguage), else Meran
const lastView = (() => {
  try { const v = sessionStorage.getItem('onpath.viewOnce'); sessionStorage.removeItem('onpath.viewOnce'); return v; }
  catch { return null; } // expected: storage blocked → the default view
})();
const startAt = (params.get('at') ?? lastView ?? import.meta.env.VITE_START ?? '11.16,46.67,12').split(',').map(Number);

type Meta = { pinned: boolean; name?: string };
type LatLon = [number, number];
const state = {
  points: [] as LatLon[],
  /** Per point: pinned to a POI / place (passed exactly) and its name – kept from a received plan. */
  meta: [] as Meta[],
  title: null as string | null,
  /** The saved plan (My plans) the page shows, so "Save" writes a new version of it. */
  savedId: null as string | null,
  /** Its name and plan code as saved (the sheet says "Saved as …", and whether the plan changed since). */
  savedTitle: null as string | null,
  savedCode: null as string | null,
  activity: (localStorageGet('activity') as Activity) ?? 'HIKE',
  sac: Number(localStorageGet('sac') ?? 2),
  plan: null as PlanResult | null,
  planning: false,
  latest: 0,
  roundTrip: false,
  canUndo: false,
  canRedo: false,
  /** "Very long plan" was cancelled with nothing to take back: the points stay without a route (app: NotRouted.LONG). */
  notRouted: false,
};
/** Names and pins by point value, also for points only undo / redo can bring back (the app: PlanHistory.allPoints). */
const known = new Map<string, Meta>();
/** A pairing link (#pair=…) this page was opened with – taken out of the address at once (it holds the secret). */
const pairLink = /[#&]pair=/.test(location.hash) ? location.href : null;
if (pairLink) history.replaceState(null, '', location.href.split('#')[0]);

/**
 * The header's entries (owner 2026-10-28: a top line like komoot's web header, in onpath.earth's look). One list for
 * both places: inline in the header on a wide screen, the ☰ drop-down on a phone. Add an entry here (id, icon, label,
 * what it does); `act` is set further down, once the page's parts exist.
 */
const NAV: { id: string; icon: IconName; label: string; act?: () => void }[] = [
  // owner 2026-10-30: no "Send to phone" here – sharing is the sheet's Share icon. The phone pairing entry (#syncBadge,
  // drawn right after My routes) is always there: "Pair phone" opens the pairing dialog, "Paired" (green dot) My routes
  { id: 'menuPlans', icon: 'folder', label: t('routes_title') },
  { id: 'menuImportGpx', icon: 'route', label: t('routes_import_gpx') }, // gpximport.ts (the app's My routes → Import GPX)
  { id: 'menuAbout', icon: 'help', label: t('web_nav_help') },
];
/** The website's pages for this language (web/strings: the app, privacy, legal notice) – calm links, new tab. */
const siteLink = (key: string, label: string, cls = '') =>
  `<a class="${cls}" href="${t(key)}" target="_blank" rel="noopener">${escapeHtml(label)}</a>`;
const navItem = (id: string) => NAV.find((n) => n.id === id)!;

document.title = t('web_page_title');
document.querySelector<HTMLDivElement>('#app')!.innerHTML = `
  <header id="header">
    <a class="brand" href="${t('web_url_app')}" target="_blank" rel="noopener" title="${escapeHtml(t('web_brand_title'))}">${logo(30)}<span class="wordmark">OnPath <span class="product">${escapeHtml(t('web_product'))}</span></span></a>
    <button id="menuBtn" class="iconbtn" aria-label="${escapeHtml(t('plan_menu'))}" aria-haspopup="menu" aria-expanded="false">${icon('menu')}<i class="menudot" aria-hidden="true" hidden></i></button>
    <nav id="mainMenu" class="nav" aria-label="${escapeHtml(t('web_product'))}">
      ${NAV.map((n) => `<button id="${n.id}" role="menuitem" title="${escapeHtml(n.label)}">${icon(n.icon, 20)}<span>${escapeHtml(n.label)}</span></button>`
        + (n.id === 'menuPlans' ? `<button id="syncBadge" class="syncbadge" role="menuitem" title="${escapeHtml(t('web_pair_phone'))}">${icon('phone', 20)}<i class="syncdot" aria-hidden="true" hidden></i><span class="lbl">${escapeHtml(t('web_pair_phone'))}</span></button>` : '')).join('')}
      ${siteLink('web_url_app', t('web_link_app'), 'navlink')}
    </nav>
    <span class="hint muted">${escapeHtml(t('web_header_hint'))}</span>
    <span id="langSwitch" class="langswitch" role="group" aria-label="${escapeHtml(t('web_lang_switch'))}">${languages.map((l) =>
      `<button data-lang="${l}" lang="${l}" aria-pressed="${l === lang}" title="${escapeHtml(t('web_lang_name_' + l))}">${l.toUpperCase()}</button>`).join('')}</span>
  </header>
  <div id="stage">
  <div id="map"></div>
  <div id="topbar" class="topbar">
  <div id="searchbox"></div>
  <span class="barsep" aria-hidden="true"></span>
  <div id="toolbar" class="toolbar" role="toolbar" aria-label="${escapeHtml(t('web_map_tools'))}">
    <button id="undo" class="tool" aria-label="${escapeHtml(t('plan_undo'))}" title="${escapeHtml(t('web_undo_title'))}" disabled>${icon('undo')}</button>
    <button id="redo" class="tool" aria-label="${escapeHtml(t('plan_redo'))}" title="${escapeHtml(t('web_redo_title'))}" disabled>${icon('redo')}</button>
    <span class="sep"></span>
    <button id="eye" class="tool" aria-label="${escapeHtml(t('plan_hold_hide_route'))}" title="${escapeHtml(t('plan_hold_hide_route'))}" disabled>${icon('eye')}</button>
    <button id="compass" class="tool"></button>
    <button id="locate" class="tool"></button>
    <button id="fitRoute" class="tool" aria-label="${escapeHtml(t('web_fit_route'))}" disabled>${icon('fitScreen')}</button>
    <span class="sep"></span>
    <button id="mapContent" class="tool wide" aria-haspopup="dialog" title="${escapeHtml(t('web_map_content_title'))}">${icon('layers')}<span>${escapeHtml(t('web_map_content'))}</span>${icon('dropDown', 18)}</button>
  </div>
  </div>
  <div id="placecard" class="placecard" hidden></div>
  <div id="mapnotes" class="mapnotes" hidden></div>
  <div id="footer"><button id="credits" class="credit" aria-label="${escapeHtml(t('plan_map_credits'))}">© OpenStreetMap</button>
    <span class="sitelinks">${siteLink('web_url_privacy', t('web_link_privacy'))}<span aria-hidden="true">·</span>${siteLink('web_url_legal', t('web_link_legal'))}</span></div>
  <section id="sheet" aria-label="${escapeHtml(t('web_plan_sheet'))}">
    <button id="handle" aria-label="${escapeHtml(t('plan_resize_panel'))}" title="${escapeHtml(t('plan_resize_panel'))}" aria-controls="sheet"><span></span></button>
    <div class="sheethead">
      <button id="activityChip" class="chip" aria-haspopup="menu" aria-expanded="false"></button>
      <div id="summary"><div class="headline"></div><div class="subline muted"></div></div>
      <button id="share" class="sharebtn" aria-label="${escapeHtml(t('web_share_needs_two'))}" title="${escapeHtml(t('web_share_needs_two'))}" disabled>${icon('share', 18)}<span>${escapeHtml(t('web_share'))}</span></button>
      <button id="sheetMenuBtn" class="iconbtn" aria-label="${escapeHtml(t('plan_route_options'))}" aria-haspopup="menu">${icon('more')}</button>
      <button id="sheetStep" class="iconbtn" aria-controls="sheet"></button>
      <div id="sheetMenu" class="menu" role="menu" hidden>
        <button id="sheetSave" role="menuitem">${icon('save')}${escapeHtml(t('plan_save_route'))}</button>
        <button id="exportGpx" role="menuitem">${icon('download')}${escapeHtml(t('common_export_gpx'))}</button>
        <hr><button id="clear" role="menuitem">${icon('delete')}${escapeHtml(t('plan_clear_route'))}</button>
      </div>
    </div>
    <div id="savedLine" class="savedline muted small" hidden></div>
    <div id="notice" class="notice" hidden></div>
    <div id="offmap" class="offmap" hidden></div>
    <button id="warning" class="notice warnline" aria-expanded="false" hidden></button>
    <ul id="spots" class="spots" hidden></ul>
    <div id="profile"></div>
    <div id="pointsSection" hidden>
      <div class="pointshead"><b>${escapeHtml(t('plan_points'))}</b>
        <button id="reverse" class="textbtn">${icon('swap', 18)}${escapeHtml(t('plan_reverse'))}</button>
        <button id="roundTrip" class="textbtn">${icon('loop', 18)}${escapeHtml(t('plan_round_trip'))}</button>
        <button id="clearPoints" class="textbtn" title="${escapeHtml(t('plan_clear_route'))}" aria-label="${escapeHtml(t('plan_clear_route'))}">${icon('delete', 18)}${escapeHtml(t('common_delete'))}</button></div>
      <ol id="points"></ol>
    </div>
    <div id="status" class="status muted" role="status"></div>
    <div id="apphint"></div>
  </section>
  </div>`;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const phoneWidth = () => matchMedia('(max-width: 700px)').matches;
// the plan sheet's three steps (sheetstep.ts – the app's PlanSize): handle, chevron, a click on the summary; remembered
const sheetSteps = setupSheetSteps($('sheet'), $('handle'), $<HTMLButtonElement>('sheetStep'), document.querySelector<HTMLElement>('.sheethead')!, () => {});

// ---- map ----
maplibregl.setWorkerUrl(maplibreWorkerUrl); // the bundle moves maplibre's module worker; tell it where
const pmtiles = new Protocol();
maplibregl.addProtocol('pmtiles', pmtiles.tile);
// Map: the map server's cells (default), or one flat map folder (?map=folder, and the packed preview)
const useCells = params.get('map') !== 'folder' && import.meta.env.VITE_MAP !== 'folder';
const cells = new Cells(params.get('data') ?? base + 'data/');
const mapFolder = params.get('data') ?? base + 'map/'; // folder mode only
if (useCells) maplibregl.addProtocol('cell', cells.protocol);
installDiag(DEBUG); // the problem log, window.__diag(), ?debug → "Copy diagnostics" (diag.ts)
const map = new maplibregl.Map({
  // start view: ?at=lon,lat,zoom, else the build's VITE_START (preview), else Meran
  container: 'map', style: await loadStyle(), center: [startAt[0], startAt[1]], zoom: startAt[2],
  attributionControl: false, maxPitch: 0, // like the app: turn yes, tilt no
});
(window as unknown as { __viewText: () => string }).__viewText = () => { // read by i18n.switchLanguage before it reloads
  const c = map.getCenter();
  return `${c.lng.toFixed(5)},${c.lat.toFixed(5)},${map.getZoom().toFixed(2)}`;
};

(window as unknown as { __map: maplibregl.Map }).__map = map; // for the browser tests
// the scale bar sits in our footer, above the short credit (the app's MapFooter)
$('footer').prepend(new maplibregl.ScaleControl({ unit: 'metric', maxWidth: 90 }).onAdd(map));
map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right'); // zoom ± (wide screens; the compass is in the toolbar)
if (useCells) map.on('moveend', () => cells.loadPois(map));
if (useCells) followCover(map, cells.found); // the overview hides where the cells' detailed map is
if (useCells) cells.attach(map); // a failed cell file is tried again by reloading its source (cells.ts)
addDiagSection(() => {
  const c = map.getCenter();
  return `map: ${c.lng.toFixed(3)},${c.lat.toFixed(3)} z${map.getZoom().toFixed(1)}, style loaded ${map.isStyleLoaded()}, tiles loaded ${map.areTilesLoaded()}` +
    (useCells ? '\n' + cells.diag() : '') + `\nmap strip: ${$('mapnotes').innerText.replace(/\n/g, ' | ') || '-'}`;
});
map.on('error', (e) => { problem('map', 'map error', e.error); console.error(e.error); }); // maplibre's own; logged as before
if (useCells) followPackages(map, cells.packages); // region packages (z7–9): the overview's labels left out there
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
  // direction of travel: small white chevrons along the routed legs (the app's MapController.addDirectionArrows)
  map.addImage('route-arrow', arrowImage(), { pixelRatio: 2 });
  map.addLayer({ id: 'planned-route-arrows', type: 'symbol', source: 'planned-route', filter: routed, layout: {
    'symbol-placement': 'line', 'symbol-spacing': ['interpolate', ['linear'], ['zoom'], 12, 60, 16, 90],
    'icon-image': 'route-arrow', 'icon-size': ['interpolate', ['linear'], ['zoom'], 12, 0.55, 16, 0.8],
    'icon-rotation-alignment': 'map', 'icon-keep-upright': false, 'icon-allow-overlap': true, 'icon-ignore-placement': true } });
  applyMapContent();
  render();
});

// The style the app ships, pointed at web URLs; sources whose file is not there are left out (no blank errors).
async function loadStyle(): Promise<StyleSpecification> {
  // cells: with the world overview below z10 when its files are deployed (overview.ts – the app's Overview.merge)
  const s: StyleSpecification = (useCells ? await overviewStyle(base) : null) ?? await (await fetch(base + 'style/onpath.json')).json();
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
  if (missing.size) status(t('web_map_files_missing', [...missing].join(', ')));
  return s;
}

// ---- engine ----
const engine = new EngineClient();
const planGaps = new PlanGapsLayer(map, engine); // points the route does not reach (plangaps.ts)
(window as unknown as { __engine: EngineClient }).__engine = engine; // for the browser tests (timings)
(window as unknown as { __state: typeof state }).__state = state; // for the browser tests (pace: the plan the sheet shows)
status(t('web_engine_loading'), 'busy');
const ready = engine.configure(base, params.get('segments') ?? base + 'segments/', PACKED, lang).then((r) => {
  status(r.ok ? t('web_engine_ready', Math.round(r.stats?.engineLoadMs ?? 0)) : t('web_engine_failed', r.error ?? ''), r.ok ? 'info' : 'error');
  return r.ok;
});
// the pace setting (the app's Settings → Pace, Prefs keys hikePaceLevel / bikePaceLevel) before the first plan
const pace = { hike: localStorageGet('hikePaceLevel') ?? 'AVERAGE', bike: localStorageGet('bikePaceLevel') ?? 'AVERAGE' };
const paceReady: Promise<void> = ready.then(async (ok) => {
  if (!ok) return;
  try {
    const r = JSON.parse(await engine.call('paceSet', pace.hike, pace.bike));
    pace.hike = r.hike; pace.bike = r.bike; // unknown names → the app's rule (Pace.of)
  } catch (e) { console.warn('pace not set – the default (Average) is used', e); }
});
ready.then(async (ok) => { if (ok) { await paceReady; openFromLink(); prefetchView(); } });
// plan sync (docs/design/web-sync.md): My plans in this browser, paired with the phone through the mailbox
const sync = new PlanSync(engine, params.get('syncApi') ?? base + 'sync/v1/');
(window as unknown as { __sync: PlanSync }).__sync = sync; // for the browser tests
syncBadge(); // the header entry with its full tooltip at once – sync.init waits for the engine (CI race in sync-smoke)
const syncReady = ready.then(async (ok) => {
  if (!ok) return false;
  await sync.init();
  if (pairLink) joinDialog(sync, pairLink);
  syncBadge();
  sync.onChange(syncBadge);
  return true;
});
addEventListener('hashchange', () => openFromLink());

// ---- waits on the map: position, map loading, map areas on demand (mapnotice.ts, areas.ts – web-planner.md §17) ----
const notices = new MapNotices($('mapnotes'));
/** The app's texts for the strip and the waits (WebMaps.kt); empty until the engine is there. */
const mapTexts: Promise<MapTexts> = ready.then(async (ok) => (ok ? JSON.parse(await engine.call('mapTexts')) as MapTexts : {}))
  .catch((e) => { console.warn('map texts not loaded', e); return {}; });
const areas = useCells ? new MapAreas(map, cells, params.get('mapsApi') ?? base + 'maps/v1/', mapTexts, notices) : null;
// a cell in view whose detailed map does not load (cells.ts): grey while it is tried again, red when it is left out –
// the overview stays visible there either way (never a blank map)
function showCellTrouble() {
  const s = useCells ? cells.baseTrouble(map) : null;
  const now = notices.get('cells');
  const want = s === 'broken' ? t('web_maps_cell_failed') : s === 'busy' ? t('maps_err_unreachable') : null;
  if ((now?.title ?? null) === want) return;
  notices.set('cells', want ? { kind: s === 'broken' ? 'error' : 'info', title: want } : null);
}
if (useCells) { cells.onChange = showCellTrouble; map.on('moveend', showCellTrouble); }
(window as unknown as { __areas: MapAreas | null }).__areas = areas; // for the browser tests
// the map's tiles still loading 400 ms after it stopped: "Loading the map for your area" with a spinner, until idle
let loadingTimer = 0;
map.on('moveend', () => {
  clearTimeout(loadingTimer);
  loadingTimer = window.setTimeout(() => {
    if (!map.areTilesLoaded()) notices.set('loading', { kind: 'busy', title: t('maps_load_loading') });
  }, 400);
});
map.on('movestart', () => clearTimeout(loadingTimer));
map.on('idle', () => { clearTimeout(loadingTimer); if (notices.get('loading')) notices.set('loading', null); });
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
  runCause = nextCause ?? 'edit'; nextCause = null;
  editBefore = runCause === 'edit' ? { points: state.points, names: state.meta.map((m) => m.name) } : null;
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
  runCause = op;
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
  return name?.trim() ? name : i === 0 ? t('plan_point_start') : i === count - 1 && count > 1 ? t('plan_point_destination') : t('plan_point_stop', i + 1);
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
    el.title = t('web_marker_title', stopLabel(i, n, state.meta[i]?.name));
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
    const role = row.role === 'START' ? t('plan_point_start') : row.role === 'GOAL' ? t('plan_point_destination') : '';
    const color = row.loop ? COLORS.start : stopColor(row.i, n);
    return `<li data-i="${row.i}">
      <button class="grip" title="${escapeHtml(t('web_grip_title'))}" aria-label="${escapeHtml(t('web_grip_move', row.name))}">${icon('drag', 20)}</button>
      <span class="num" style="background:${color}">${row.loop ? '↺' : row.i + 1}</span>
      <span class="txt"><span>${escapeHtml(row.name)}</span>${role || row.loop ? `<span class="muted small">${escapeHtml(row.loop ? t('plan_point_start_finish') : role)}</span>` : ''}</span>
      ${row.legM != null ? `<span class="leg muted">${row.approx ? '~' : '+'}${number(row.legM / 1000, 1)} km</span>` : '<span class="leg"></span>'}
      <button class="del iconbtn" aria-label="${escapeHtml(t('web_delete_point', row.name))}">${icon('close', 20)}</button></li>`;
  }).join('');
  $<HTMLButtonElement>('roundTrip').disabled = n < 2 || state.roundTrip;
  $<HTMLButtonElement>('reverse').disabled = n < 2;
  $('pointsSection').hidden = n === 0;
}

// Points list: click = show on the map, ✕ = delete, drag ≡ = new order (PlanEdits.move; sortable.ts)
$('points').addEventListener('click', (e) => {
  const li = (e.target as HTMLElement).closest('li');
  if (!li || (e.target as HTMLElement).closest('.grip')) return;
  const i = Number(li.dataset.i);
  if ((e.target as HTMLElement).closest('.del')) { deletePoint(i); return; }
  const p = state.points[i];
  // phone: the sheet goes to the profile step so list and map are both visible (the app's showPointFromList)
  if (p && phoneWidth()) sheetSteps.set('profile');
  if (p) map.easeTo({ center: [p[1], p[0]], zoom: Math.max(map.getZoom(), 14) });
});
sortable($('points'), (from, to) => applyEdit('move', from, to));

// ---- presses on the map: a click is the app's hold (MapGestures.decide, in the engine) ----
let menu: maplibregl.Popup | null = null;
let placeCard: PlaceCard = { close: () => {}, isOpen: () => false }; // the search's place card (set up below)
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

// The pointing hand over what a click opens (owner 2026-10-30): a POI (its menu) and the route line (a point goes into
// that leg). Markers are DOM elements with their own cursor. One lookup per frame at most.
let hoverAt: maplibregl.Point | null = null;
map.on('mousemove', (e) => {
  const queued = hoverAt != null;
  hoverAt = e.point;
  if (queued) return;
  requestAnimationFrame(() => {
    const pt = hoverAt!; hoverAt = null;
    const layers = map.isMoving() ? [] : map.getStyle().layers
      .filter((l) => l.id.startsWith('poi-') || l.id === 'planned-route' || l.id === 'planned-route-casing').map((l) => l.id);
    const hit = layers.length > 0 && map.queryRenderedFeatures([[pt.x - 10, pt.y - 10], [pt.x + 10, pt.y + 10]], { layers }).length > 0;
    map.getCanvas().style.cursor = hit ? 'pointer' : '';
  });
});
map.on('mouseout', () => { map.getCanvas().style.cursor = ''; });

async function press(at: maplibregl.LngLat, marker: number, pt?: maplibregl.Point) {
  const results = document.getElementById('results');
  if (results && !results.hidden) { results.hidden = true; return; } // a click beside the search results only closes them
  if (placeCard.isOpen()) { placeCard.close(); return; } // … and one beside the place card closes it (nothing added)
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

const position = new Position(map, () => { renderFabs(); locateNotice(); });
/** Locate was clicked and its answer is not shown yet: the strip says why the map waits. */
let locating = false;
let locateTimer = 0;
function locateNotice() {
  if (!locating) return;
  clearTimeout(locateTimer);
  const close = { label: t('web_hide'), onClose: () => { locating = false; } };
  if (position.fix && (position.status === 'GOOD' || position.status === 'WEAK')) {
    locating = false;
    notices.set('locate', null);
    areas?.wantPoint(position.fix[0], position.fix[1], 'here'); // T3: the detailed map where you are
  } else if (position.status === 'NO_PERMISSION') {
    locating = false;
    notices.set('locate', { kind: 'error', title: t('web_locate_denied'), close });
  } else if (position.status === 'OFF') {
    locating = false;
    notices.set('locate', { kind: 'error', title: t('web_locate_off'), close });
  } else {
    notices.set('locate', { kind: 'busy', title: t('web_locate_finding'), close });
    // the browser shows its own "allow location?" question now: say where it is, so nobody waits for nothing
    navigator.permissions?.query({ name: 'geolocation' }).then((p) => {
      if (p.state === 'prompt' && locating && !position.fix)
        notices.set('locate', { kind: 'busy', title: t('web_locate_finding'), lines: [t('web_locate_allow')], close });
    }).catch(() => { /* expected: permissions API missing (older Safari) – the plain line stays */ });
    // still nothing after 20 s: say so (the browser keeps trying; a fix still moves the map there)
    locateTimer = window.setTimeout(() => { if (locating && !position.fix) notices.set('locate', { kind: 'busy', title: t('web_locate_still'), close }); }, 20_000);
  }
}

/** "Go here": the item and its reason when it cannot be used (the app's MapGestures.fromMeBlocked). */
async function goHereItem(onGo: () => void): Promise<MenuItem> {
  const blocked = position.status === 'UNKNOWN' ? '' : await engine.call('fromMeBlocked', position.status, position.fix != null);
  return { icon: 'gpsFixed', label: t('plan_go_here'), sub: blocked || (position.fix ? t('web_from_position') : t('web_from_position_ask')),
    disabled: !!blocked, act: onGo };
}

/** From my position to [to] (first point = me, unpinned). */
async function fromMe(to: LatLon, toMeta: Meta) {
  status(t('web_locate_finding'), 'busy');
  const me = await position.once();
  if (!me) { status(t('web_position_unavailable'), 'error'); return; }
  status('');
  await commit([me, to], [{ pinned: false }, toMeta]);
}

/** First press on an empty plan: "Start here" · "Go here" (ui/MapOverlays.FirstPointMenu). */
async function firstPointMenu(at: maplibregl.LngLat) {
  const p: LatLon = [at.lat, at.lng];
  openMenu(at, [
    { icon: 'tripOrigin', label: t('plan_start_here'), act: () => commit([p], [{ pinned: false }]) },
    await goHereItem(() => fromMe(p, { pinned: false })),
  ], `<b>${escapeHtml(t('plan_new_route'))}</b>`);
}

/** Point menu: number + name, then the app's entries for this point (MapGestures.pointMenu, in the engine). */
async function pointMenu(i: number) {
  const n = state.points.length, name = state.meta[i]?.name;
  const [lat, lon] = state.points[i];
  const acts: string[] = JSON.parse(await engine.call('pointMenu', i, n, !!name?.trim(), state.roundTrip));
  const items: MenuItem[] = acts.map((a): MenuItem => {
    if (a === 'MAKE_START') return { icon: 'tripOrigin', label: t('plan_start_here'), sub: tp('plan_removes_points', i, i), act: () => applyEdit('startHere', i) };
    if (a === 'LOOP_START') return { icon: 'tripOrigin', label: t('plan_start_finish_here'), sub: t('plan_same_round_trip'), act: () => applyEdit('startHere', i) };
    if (a === 'GOOGLE') return { icon: 'map', label: t('plan_open_google_maps'), act: () => google(name ?? '', lat, lon, '') };
    return { icon: 'delete', label: t('common_delete'), danger: true, act: () => deletePoint(i) };
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
    ? [{ icon: 'tripOrigin', label: t('plan_start_here'), act: () => addPlace(hit, 'START') },
      await goHereItem(() => fromMe([Number(lat.toFixed(6)), Number(lon.toFixed(6))], { pinned: true, name: info.title }))]
    : [{ icon: 'addLocation', label: t('plan_add_to_route'), act: () => addPlace(hit, 'SMART') }];
  items.push({ icon: 'map', label: t('plan_open_google_maps'), act: () => window.open(info.google, '_blank', 'noopener') });
  openMenu(new maplibregl.LngLat(lon, lat), items,
    `<span class="txt"><b>${escapeHtml(info.title)}</b><span class="small" style="color:${info.color}">${escapeHtml(info.sub)}</span></span>`);
}

/** "Open in Google Maps": the app's link (PoiLinks.googleMaps), in a new tab – Google content is never drawn here. */
async function google(name: string, lat: number, lon: number, label: string) {
  // The tab opens right in the click: a window.open after an await (the engine may still be busy with a route) loses
  // the click's permission and the browser blocks it silently (owner 2026-10-30: "Google geht nicht mehr").
  const tab = window.open('', '_blank');
  try {
    const url = await engine.call('googleMaps', name, lat, lon, label) as string;
    if (tab) { tab.opener = null; tab.location.href = url; } else window.open(url, '_blank', 'noopener');
  } catch (e) {
    tab?.close();
    console.warn('[google] link not made', e);
    status(t('web_google_failed'), 'error');
  }
}

// ---- shared links ----

/** A plan in the address (#p=…, from a QR code or a shared link) replaces the current one. */
let linkChecked = false; // until then the address may still hold a plan to open – do not overwrite it
async function openFromLink() {
  linkChecked = true;
  if (!/[#&?]p=/.test(location.href)) return;
  const shared = await engine.parse(location.href);
  if (!shared) { status(t('msg_plan_link_unreadable'), 'error'); return; }
  if (shared.points.map(key).join(';') === state.points.map(key).join(';') && shared.activity === state.activity && shared.sac === state.sac) return; // our own address update
  forgetSaved();
  // a shared plan opened in the browser: the one calm line about the app, in the sheet (never over the route – it is
  // there before the camera fits the plan beside the sheet)
  if (shared.points.length >= 2) appHint($('apphint'));
  await loadShared(shared);
}

/** Shows [shared] as the plan (from a link or from My plans). */
async function loadShared(shared: SharedPlan) {
  state.title = shared.title;
  setActivity(shared.activity, shared.sac, false);
  if (shared.points.length) {
    const b = new maplibregl.LngLatBounds();
    shared.points.forEach(([la, lo]) => b.extend([lo, la]));
    map.fitBounds(b, { padding: fitPadding(), maxZoom: 15, duration: 0 });
  }
  nextCause = 'other';
  await commit(shared.points, shared.points.map((_, i) => ({ pinned: shared.pinned.includes(i), name: shared.names[String(i)] })));
}

/** Room for the top bar, the sheet (side panel or bottom sheet) and the map controls when the camera fits a plan. */
function fitPadding(): maplibregl.PaddingOptions {
  const sheet = $('sheet').getBoundingClientRect();
  // the sheet as it is now (its step, or the track panel); the place card on a phone stands in for it
  if (phoneWidth()) {
    const card = document.body.classList.contains('card-open') ? $('placecard').getBoundingClientRect().height : 0;
    return { top: 130, bottom: Math.max(sheet.height, card) + 30, left: 40, right: 40 };
  }
  // wide: the room beside the side panel, or – when the panel is short (small step) – the larger room below it
  const stage = $('stage').getBoundingClientRect(), w = stage.width, h = stage.height;
  const beside = { top: 90, bottom: 50, left: sheet.right - stage.left + 50, right: 90 };
  const below = { top: sheet.bottom - stage.top + 40, bottom: 50, left: 40, right: 90 };
  const room = (p: typeof beside) => Math.max(0, w - p.left - p.right) * Math.max(0, h - p.top - p.bottom);
  return room(below) > room(beside) ? below : beside;
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
/** The plan before the point edit that is being planned now (the long-route question's "Far from your route"). */
let editBefore: Before | null = null;
async function changed() {
  const before = editBefore; editBefore = null;
  await drawPoints();
  const h = JSON.parse(await engine.call('historyState'));
  state.canUndo = h.canUndo; state.canRedo = h.canRedo;
  // Share (icon next to ⋯): always there (owner 2026-10-30), greyed until a start and a destination are set
  const sh = $<HTMLButtonElement>('share'), two = state.points.length >= 2, shw = two ? t('web_share') : t('web_share_needs_two');
  sh.disabled = !two; sh.title = shw; sh.setAttribute('aria-label', shw);
  rememberInAddress();
  state.notRouted = false;
  if (state.points.length < 2) { state.plan = null; state.planning = false; planGaps.clear(); render(); return; }
  // the app's long-route question first (routing/LongRoute): nothing is routed or downloaded until it is answered
  const answer = await longRouteOk(engine, state.points, state.activity, before);
  if (answer === 'cancel') { await cancelLongRoute(); return; }
  if (answer !== 'plan') { await startFresh(answer.fresh); return; }
  const id = ++state.latest;
  state.planning = true;
  render();
  newPointsAsked();
  // waits said where the user acted (the sheet): the routing data download (the app's "Loading the map for your area ·
  // about 9 MB", map-cells.md §19.8), then "Calculating the route…"
  const calculating = () => t('plan_calculating');
  status(calculating(), 'busy');
  const r = await engine.plan(state.points, state.activity, state.sac, sharedPlan().pinned, (pr) => {
    if (id !== state.latest) return;
    if (pr.phase === 'download') status(`${t('maps_load_loading')} · ${t('maps_about_mb', Math.max(1, Math.round(pr.bytes / 1e6)))}`, 'busy');
    else status(calculating(), 'busy');
  });
  if (id !== state.latest) return; // a newer request supersedes this one
  state.planning = false;
  if (!r.ok || !r.plan) { status(t('web_plan_failed', r.error ?? ''), 'error'); render(); return; }
  state.plan = r.plan;
  await planGaps.update(state.points, state.activity, namesText()); // points the route does not reach (app: PlanGaps)
  if (id !== state.latest) return;
  drawPoints(); // leg distances along the new route line (the engine now has this plan)
  const s = r.stats!;
  if (DEBUG) status(s.bytesFetched ? t('web_planned_in_data', Math.round(s.routeMs), number(s.bytesFetched / 1e6, 1), Math.round(s.fetchMs))
    : t('web_planned_in', Math.round(s.routeMs)));
  else status('');
  render();
  areas?.wantLine(r.plan.legs.flatMap((l) => l.points.map(([la, lo]) => [la, lo] as LatLon))); // T4: the map areas along the route
}

/**
 * "Start a new route here" (Far from your route): only the new point stays – one undo step with the edit that set it,
 * so ↶ brings the old route back (like Clear route, nothing more is said).
 */
async function startFresh(p: LatLon) {
  const m = state.meta[state.points.findIndex((q) => key(q) === key(p))] ?? { pinned: false };
  state.latest++; state.plan = null; state.planning = false;
  state.title = null; forgetSaved();
  setPoints([p], [m]);
  await changed();
}

/** Clear route (⋯ and the Points head; the app's RouteRules.canClear: from one point): no question – ↶ brings it back. */
function clearRoute() { closeMenus(); state.title = null; forgetSaved(); commit([], []); }

/**
 * A plan the map does not show (owner 2026-10-30: a point left in Meran, the map in Hilden): when no point and no piece
 * of the route is in view and the nearest point is more than [OFF_MAP_M] away, the sheet says so with Show (= the
 * toolbar's "Show whole route", also for a single point).
 */
const OFF_MAP_M = 25_000;
function renderOffMap() {
  const el = $('offmap');
  let off = false;
  if (state.points.length) {
    const v = map.getBounds(), c = map.getCenter();
    const inView = (la: number, lo: number) => v.contains([lo, la]);
    const line = state.plan?.legs.flatMap((l) => l.points) ?? [];
    const near = Math.min(...state.points.map(([la, lo]) => c.distanceTo(new maplibregl.LngLat(lo, la))));
    off = near > OFF_MAP_M && !state.points.some(([la, lo]) => inView(la, lo)) && !line.some(([la, lo]) => inView(la, lo));
  }
  if (off === !el.hidden) return;
  el.hidden = !off;
  el.innerHTML = '';
  if (!off) return;
  const b = document.createElement('button');
  b.className = 'textbtn'; b.id = 'showPlan'; b.textContent = t('common_show');
  b.onclick = fitRoute;
  el.append(t('web_route_off_map'), ' · ', b);
}
map.on('moveend', renderOffMap);

/** T1: the map area of each point newly set (more than 3 new at once = a loaded plan → only T4, like the app). */
const askedPoints = new Set<string>();
function newPointsAsked() {
  const fresh = state.points.filter((p) => !askedPoints.has(key(p)));
  fresh.forEach((p) => askedPoints.add(key(p)));
  if (fresh.length <= 3) for (const p of fresh) areas?.wantPoint(p[0], p[1], 'planned');
}

/** What started this run (the app's PlanStatus.Cause): Cancel of the long-route question takes back only that. */
let runCause = 'other';
let nextCause: string | null = null;
let activityBefore: { a: Activity; sac: number } | null = null;
/** Cancel of "This route is very long" (AppViewModel.cancelRun): undo / redo the change, put the activity back, or keep the points. */
async function cancelLongRoute() {
  const what = await engine.call('planCancel', runCause, state.canUndo, state.canRedo);
  state.latest++; state.plan = null; state.planning = false;
  if (what === 'UNDO') return step('undo');
  if (what === 'REDO') return step('redo');
  if (what === 'RESTORE_ACTIVITY' && activityBefore) return setActivity(activityBefore.a, activityBefore.sac, true);
  state.notRouted = true;
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
    head.innerHTML = `<span class="title">${escapeHtml(t('plan_plan_a_route'))}</span>`;
    const n = state.points.length;
    sub.textContent = n === 0 ? t('web_hint_first_point')
      : n === 1 ? t('web_hint_destination') : state.notRouted ? notRoutedTexts.line : t('plan_calculating');
    // one point: which one, also in the small step where the Points list is hidden (owner 2026-10-30)
    if (n === 1) sub.prepend(Object.assign(document.createElement('span'), { className: 'onepoint',
      textContent: t('web_one_point', state.meta[0]?.name || t('plan_point_start')) }));
    if (state.notRouted && n >= 2) {
      const b = document.createElement('button');
      b.className = 'textbtn'; b.id = 'planAnyway'; b.textContent = notRoutedTexts.button;
      b.onclick = () => { allowLongRoute(state.points); changed(); };
      sub.append(b);
    }
    $('profile').innerHTML = '';
    notice.hidden = true;
  } else {
    head.innerHTML = `<span class="big">${escapeHtml(p.texts.time)}</span><span class="dist">${escapeHtml(p.texts.distance)}</span>`;
    sub.innerHTML = `<span>${escapeHtml(p.texts.climb)}</span>` +
      `<span class="badge ${p.difficulty.split(' ')[0].toLowerCase()}" ${p.because ? `title="${escapeHtml(p.because)}"` : ''}>${escapeHtml(p.difficulty)}</span>`;
    $('profile').innerHTML = profileSvg(p.profile);
    // one red line: an unreachable point (the app's PlanGaps, with Show) or a failed leg – PlanPanel's order
    const red = planGaps.last.text ?? p.error;
    notice.hidden = !red;
    notice.innerHTML = red ? `⚠ ${escapeHtml(red)}` : '';
    if (planGaps.last.text) {
      const b = document.createElement('button');
      b.className = 'textbtn'; b.id = 'showGap'; b.textContent = t('common_show');
      b.onclick = () => planGaps.show(fitPadding());
      notice.append(' ', b);
    }
  }
  // route warnings (the app's RouteWarnings): one amber ⚠ line, click → "On this route", click a row → the map shows it
  const w = $('warning'), spots = $('spots');
  w.hidden = !p?.warning;
  w.textContent = p?.warning ? `⚠ ${p.warning}` : '';
  if (!p?.warning) { spots.hidden = true; w.setAttribute('aria-expanded', 'false'); }
  spots.innerHTML = (p?.spots ?? []).map((s, i) => `<li><button data-spot="${i}"><b>${escapeHtml(s.title)}</b><span class="muted small">${escapeHtml(s.detail)}</span></button></li>`).join('');
  $('sheet').classList.toggle('busy', state.planning);
  renderOffMap();
  renderSaved();
  renderChip();
  renderFabs();
}

const untitled = (p: SharedPlan) => engine.encode({ ...p, title: null });
let savedUntitledMemo: { code: string; key: Promise<string | null> } | null = null;
function savedUntitled(code: string): Promise<string | null> {
  if (savedUntitledMemo?.code !== code) {
    savedUntitledMemo = { code, key: engine.parse('p=' + code).then((p) => (p ? untitled(p) : null)) };
  }
  return savedUntitledMemo.key;
}

/** "Saved as …" under the summary while the plan is a saved one (the app's sheet line after Save). */
async function renderSaved() {
  const line = $('savedLine');
  line.hidden = !state.savedId || !state.savedTitle || state.points.length < 2;
  if (line.hidden) { saveState = state.points.length >= 2 ? 'save' : 'empty'; return; }
  // the name is in the code too, but naming it on Save (or a rename) is not a change of the route
  const changed = state.savedCode != null && (await ready) && (await untitled(sharedPlan())) !== (await savedUntitled(state.savedCode));
  line.innerHTML = `${icon('check', 16)}<span>${t(changed ? 'web_saved_as_changed' : 'web_saved_as', `<b>${escapeHtml(state.savedTitle!)}</b>`)}</span>`;
  saveState = changed ? 'save' : 'saved';
}
/** For My routes → "Save current route": greyed on an empty route, ✓ "Saved as …" while saved and unchanged. */
let saveState: 'empty' | 'save' | 'saved' = 'empty';

// ---- activity (the app's picker as a drop-down at the chip: six activities, Hike with its T level) ----
const picker = new ActivityPicker((a, sac) => setActivity(a, sac, true), {
  choices: async (a) => { await paceReady; return JSON.parse(await engine.call('paceChoices', a)) as PaceChoices; },
  pick: setPace,
});
/** A pace level for hiking or cycling: remembered here, handed to the engine, the shown plan's time again (no new route). */
async function setPace(group: 'hike' | 'bike', level: string) {
  pace[group] = level;
  localStorageSet(group === 'hike' ? 'hikePaceLevel' : 'bikePaceLevel', level);
  await paceReady;
  await engine.call('paceSet', pace.hike, pace.bike);
  if (!state.plan || state.planning) return; // a plan under way takes the new pace when it finishes
  const t = JSON.parse((await engine.call('planTime', enc(state.points), state.activity, state.sac)) || 'null') as { timeS: number; time: string } | null;
  if (!state.plan || state.planning) return;
  if (!t) { changed(); return; } // the engine's last plan is another one: plan again (same route, the new time)
  state.plan.timeS = t.timeS; state.plan.texts.time = t.time;
  render();
}
engine.call('activities').then((t) => { picker.setRows(JSON.parse(t) as ActivityRow[]); renderChip(); }).catch((e) => status(t('web_activities_failed', String(e)), 'error'));
function renderChip() {
  const row = picker.row(state.activity);
  $('activityChip').innerHTML = `${activityIcon(state.activity, 20)}<span>${escapeHtml(row?.chip ?? state.activity)}` +
    `${state.activity === 'HIKE' ? ' · T' + state.sac : ''}</span>${icon('dropDown', 18)}`;
  $('activityChip').setAttribute('aria-label', t('web_activity_chip_desc', `${row?.label ?? state.activity}${state.activity === 'HIKE' ? ' T' + state.sac : ''}`));
}
function setActivity(a: Activity, sac: number, plan: boolean) {
  const same = a === state.activity && sac === state.sac;
  if (plan && !same) { runCause = 'activity'; activityBefore = { a: state.activity, sac: state.sac }; }
  state.activity = a; state.sac = sac;
  localStorageSet('activity', a); localStorageSet('sac', String(sac));
  renderChip();
  if (plan && !same) changed();
}
$('activityChip').onclick = (e) => { e.stopPropagation(); closeMenus(); picker.toggle($('activityChip'), state.activity, state.sac); };

// ---- map toolbar (the app's MapControls: undo, redo, eye, compass, locate – always there, greyed when not possible) ----
function renderFabs() {
  const b = map.getBearing();
  const c = $('compass');
  c.innerHTML = needle(b);
  const what = Math.abs(b) > 1 ? t('web_compass_turned') : t('web_compass_north');
  c.title = what; c.setAttribute('aria-label', what);
  $<HTMLButtonElement>('eye').disabled = !state.plan || state.points.length < 2;
  $<HTMLButtonElement>('undo').disabled = !state.canUndo;
  $<HTMLButtonElement>('redo').disabled = !state.canRedo;
  const l = $('locate');
  l.innerHTML = icon(position.following ? 'gpsFixed' : 'gpsNotFixed') + `<span class="gpsdot ${position.dotClass}"></span>`;
  l.classList.toggle('on', position.following);
  const lw = position.status === 'OFF' ? t('web_locate_off_title') : position.status === 'NO_PERMISSION'
    ? t('web_locate_denied_title') : t('web_locate_title');
  l.title = lw; l.setAttribute('aria-label', lw);
  const f = $<HTMLButtonElement>('fitRoute');
  f.disabled = state.points.length < 2;
  f.title = f.disabled ? t('web_share_needs_two') : t('web_fit_route');
}
map.on('rotate', () => renderFabs());
$('compass').onclick = () => map.easeTo({ bearing: 0, pitch: 0 }, { keepFollow: true }); // north up keeps following
$('undo').onclick = () => step('undo');
$('redo').onclick = () => step('redo');
$('locate').onclick = () => { locating = true; position.locate(); locateNotice(); };
/** "Show whole route" (owner 2026-10-30): the planned line, every point and a shown GPX track in the free map room
 *  (fitPadding: clear of the sheet in any step, the top bar, the place card and the controls). Not keepFollow – it
 *  ends position following, like every camera move made for the user (CLAUDE.md "Map camera"). */
function fitRoute() {
  if (!state.points.length) return; // the button needs two; the off-map line's Show also one point
  const b = new maplibregl.LngLatBounds();
  state.points.forEach(([la, lo]) => b.extend([lo, la]));
  state.plan?.legs.forEach((l) => l.points.forEach(([la, lo]) => b.extend([lo, la])));
  const track = gpxImport.shown;
  track?.segments.flat().forEach(([la, lo]) => b.extend([lo, la]));
  track?.waypoints.forEach((w) => b.extend([w.lon, w.lat]));
  // fitBounds adds the map's own padding to ours – a padding left by an older easeTo would shrink the room (§13 row 19)
  const p = map.getPadding();
  if (p.top || p.bottom || p.left || p.right) map.setPadding(NO_PADDING);
  map.fitBounds(b, { padding: fitPadding(), maxZoom: 16 });
}
$('fitRoute').onclick = fitRoute;
/** Eye: hold to hide the route and the markers, release shows them again (the app's EyeControl). */
function peek(on: boolean) {
  if (hidden === on) return;
  hidden = on;
  for (const id of ['planned-route-casing', 'planned-route', 'planned-straight', 'planned-route-arrows']) if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', on ? 'none' : 'visible');
  markers.forEach((m) => { m.getElement().style.visibility = on ? 'hidden' : ''; });
  $('eye').innerHTML = icon(on ? 'eyeOff' : 'eye');
  $('eye').classList.toggle('on', on);
}
const eye = $('eye');
eye.addEventListener('pointerdown', (e) => { if ((eye as HTMLButtonElement).disabled) return; eye.setPointerCapture(e.pointerId); peek(true); });
for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture']) eye.addEventListener(ev, () => peek(false));
eye.addEventListener('keydown', (e) => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); peek(true); } });
eye.addEventListener('keyup', () => peek(false));
eye.addEventListener('contextmenu', (e) => e.preventDefault()); // a long press must not open the browser's menu
window.addEventListener('keydown', (e: KeyboardEvent) => {
  const typing = (e.target as HTMLElement).closest('input, textarea');
  if (e.key === 'Escape') { closeMenu(); closeMenus(); picker.close(); placeCard.close(); }
  if (typing || !(e.ctrlKey || e.metaKey)) return;
  const k = e.key.toLowerCase();
  if (k === 'z' && !e.shiftKey) { e.preventDefault(); step('undo'); }
  else if ((k === 'z' && e.shiftKey) || k === 'y') { e.preventDefault(); step('redo'); }
});

// ---- search (the app's search code in the engine, search.ts) ----
placeCard = setupSearch({
  map, engine, fitPadding,
  areas: async () => (useCells ? cells.searchAreas(map) : [{ key: 'map', dir: mapFolder }]),
  points: () => state.points,
  route: () => state.plan?.legs.flatMap((l) => l.points.map(([la, lo]) => [la, lo] as LatLon)) ?? [],
  add: (h, role) => addPlace(h, role),
  google: (h) => google(h.name ?? '', h.lat, h.lon, h.name ? '' : h.title),
  cardShown: (open) => document.body.classList.toggle('card-open', open),
  placeShown: (lat, lon) => areas?.wantPoint(lat, lon, 'searched'), // T2
}, $('searchbox'), $('placecard'));

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
  $('sheetMenu').hidden = true; $('sheetMenuBtn').setAttribute('aria-expanded', 'false');
  $('mainMenu').classList.remove('open'); $('menuBtn').setAttribute('aria-expanded', 'false');
}
// ☰ (phone width): the header's entries as a drop-down
$('menuBtn').onclick = (e) => {
  e.stopPropagation();
  const open = !$('mainMenu').classList.contains('open');
  closeMenus(); picker.close();
  $('mainMenu').classList.toggle('open', open); $('menuBtn').setAttribute('aria-expanded', String(open));
};
$('mainMenu').addEventListener('click', (e) => {
  const b = (e.target as HTMLElement).closest('button');
  if (!b) return;
  e.stopPropagation(); closeMenus();
  NAV.find((n) => n.id === b.id)?.act?.();
});
$('sheetMenuBtn').onclick = (e) => { e.stopPropagation(); $<HTMLButtonElement>('exportGpx').disabled = !state.plan; $<HTMLButtonElement>('sheetSave').disabled = !state.plan; toggleMenu('sheetMenuBtn', 'sheetMenu'); };
document.addEventListener('click', (e) => { if (!(e.target as HTMLElement).closest('.menu, .nav')) closeMenus(); });

$('reverse').onclick = () => applyEdit('reverse');
$('roundTrip').onclick = () => applyEdit('roundTrip');
$('clear').onclick = clearRoute;
$('clearPoints').onclick = clearRoute;
function forgetSaved() { state.savedId = null; state.savedTitle = null; state.savedCode = null; }
const plansHost = {
  current: async () => (state.points.length >= 2 && (await ready) ? { code: await engine.encode(sharedPlan()), title: state.title } : null),
  openId: () => state.savedId,
  saveState: async () => { await renderSaved(); return { state: saveState, title: state.savedTitle }; },
  open: async (p: { id: string; plan: string; title: string | null }) => {
    const shared = await engine.parse('p=' + p.plan);
    if (!shared) { status(t('web_plan_unreadable'), 'error'); return; }
    state.savedId = p.id; state.savedTitle = p.title; state.savedCode = p.plan;
    if (state.points.length && shared.points.map(key).join(';') === state.points.map(key).join(';')) { state.title = p.title; render(); return; }
    await loadShared({ ...shared, title: p.title });
  },
  saved: (p: { id: string; plan: string; title: string | null }) => {
    state.savedId = p.id; state.savedTitle = p.title; state.savedCode = p.plan; state.title = p.title;
    rememberInAddress();
    render();
    status(t('plan_saved_as', p.title ?? t('plan_route_default_name')));
  },
  suggestName,
};
/** The app's name proposal (AppViewModel.suggestName → RouteNamer): stop names from places, else the map's places. */
async function suggestName(): Promise<string> {
  if (state.points.length < 2) return '';
  try {
    await engine.searchAreas(await (useCells ? cells.searchAreas(map) : Promise.resolve([{ key: 'map', dir: mapFolder }])));
    const line = state.plan?.legs.flatMap((l) => l.points) ?? state.points;
    const step = Math.max(1, Math.ceil(line.length / 400)); // thinned: ends kept, enough for "via" landmarks
    const thin = line.filter((_, i) => i % step === 0 || i === line.length - 1);
    const pinnedName = (i: number) => (state.meta[i]?.pinned && state.meta[i]?.name) || '';
    const via = state.points.slice(1, -1).map((_, k) => pinnedName(k + 1)).filter(Boolean);
    const names = [pinnedName(0), pinnedName(state.points.length - 1), ...via].map((n) => n.replace(/\t/g, ' ')).join('\t');
    return await engine.call('routeName', enc(thin as LatLon[]), names);
  } catch (e) {
    status(t('web_no_name', String(e)));
    return '';
  }
}
navItem('menuPlans').act = () => myPlansDialog(sync, plansHost);
/** The phone entry in the header (☰ on phones), the one place for pairing (owner 2026-10-30): phone + "Pair phone"
 *  (no dot) / "Paired" with a green dot (the phone synced lately) or an amber one (it has not synced for a day) –
 *  phoneState; the tooltip says the state and why. A click opens the Phone window. Follows sync.onChange live, and the
 *  "… ago" moves on every 30 s. */
function syncBadge() {
  const b = $('syncBadge'), s = sync.status, st = phoneState(s);
  const dot = b.querySelector<HTMLElement>('.syncdot')!;
  dot.hidden = !s.paired;
  dot.dataset.kind = st.kind;
  const md = $('menuBtn').querySelector<HTMLElement>('.menudot')!; // phone width: the same dot on ☰, seen without opening it
  md.hidden = !s.paired;
  md.dataset.kind = st.kind;
  b.classList.toggle('paired', s.paired);
  b.querySelector('.lbl')!.textContent = t(s.paired ? 'web_sync_paired' : 'web_pair_phone');
  b.title = st.head + ' – ' + (s.paired && s.message ? s.message : st.why);
  b.setAttribute('aria-label', b.title);
}
window.setInterval(() => syncBadge(), 30_000);
$('syncBadge').onclick = async () => {
  closeMenus();
  if (!(await syncReady)) return; // the engine did not start – the status line says so
  phoneDialog(sync, location.href);
};
// Save, two ways as in the app: the sheet's ⋯ → Save route (PlanPanel's SheetMenuItems) and My routes → Save current route
$('sheetSave').onclick = () => { closeMenus(); requestSave(sync, plansHost); };
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
  if (state.points.length < 2) { status(t('plan_err_set_start_dest')); return; }
  shareDialog(location.href.split('#')[0] + '#p=' + await engine.encode(sharedPlan()), state.title, appHint);
};
$('share').onclick = share;
navItem('menuAbout').act = () => credits();
// Map content (toolbar): the app's POI and map settings in one large panel, applied live (mapcontent.ts)
const mapContent = new MapContentPanel(map, engine, (t) => status(t, 'error'));
function applyMapContent() { mapContent.apply(); }
$('mapContent').onclick = () => { closeMenus(); picker.close(); mapContent.open(); };
// ☰ → Import GPX and a .gpx dropped on the map (gpximport.ts: the app's GpxParser, SportType, TrackToPlan)
const gpxImport = setupGpxImport({
  map, engine, status, fitPadding,
  activity: () => ({ activity: state.activity, sac: state.sac }),
  setActivity: (a, sac) => setActivity(a, sac, true),
  plan: async (points, title) => { state.title = title; state.savedId = null; await commit(points); },
});
navItem('menuImportGpx').act = () => gpxImport.pick();
(window as unknown as { __gpx: typeof gpxImport }).__gpx = gpxImport; // for the browser tests
$('credits').onclick = () => credits();
$('warning').onclick = () => { const sp = $('spots'); sp.hidden = !sp.hidden; $('warning').setAttribute('aria-expanded', String(!sp.hidden)); };
$('spots').addEventListener('click', (e) => {
  const i = (e.target as HTMLElement).closest<HTMLElement>('[data-spot]')?.dataset.spot;
  const s = i != null ? state.plan?.spots[Number(i)] : null;
  if (!s || !s.line.length) return;
  if (s.zoom != null) { const [la, lo] = s.line[Math.floor(s.line.length / 2)]; map.easeTo({ center: [lo, la], zoom: s.zoom, ...roomFor(map, fitPadding()) }); return; }
  const b = new maplibregl.LngLatBounds();
  s.line.forEach(([la, lo]) => b.extend([lo, la]));
  map.fitBounds(b, { padding: fitPadding(), maxZoom: 17 });
});

/** Full credits (the app's Settings → About, MAP_CREDITS); the map shows only "© OpenStreetMap". */
function credits() {
  const d = document.createElement('dialog');
  d.className = 'share';
  const li = (k: string) => `<li>${escapeHtml(t(k))}</li>`;
  d.innerHTML = `<h2>${escapeHtml(t('web_about_title'))}</h2>
    <p class="muted">${escapeHtml(t('web_about_text'))}</p>
    <b>${escapeHtml(t('web_about_how'))}</b><ul class="credits">${['web_about_how_1', 'web_about_how_2', 'web_about_how_3', 'web_about_how_4'].map(li).join('')}</ul>
    <b>${escapeHtml(t('settings_about_credits'))}</b><ul class="credits">${['settings_credit_osm', 'settings_credit_omt', 'settings_credit_terrain',
      'settings_credit_ne', 'settings_credit_brouter', 'web_credit_photon', 'settings_credit_icons', 'web_credit_maplibre'].map(li).join('')}
      <li>${escapeHtml(t('web_credit_font'))} <a href="${manropeLicence}" target="_blank" rel="noopener">SIL Open Font License 1.1</a></li></ul>
    <p class="muted small">${escapeHtml(t('settings_about_licence'))} · ${siteLink('web_url_privacy', t('web_link_privacy'))} · ${siteLink('web_url_legal', t('web_link_legal'))}</p>
    <div class="row"><button class="primary" id="closeCredits">${escapeHtml(t('common_close'))}</button></div>`;
  document.body.append(d);
  d.querySelector<HTMLButtonElement>('#closeCredits')!.onclick = () => d.close();
  d.addEventListener('close', () => d.remove());
  d.showModal();
}

// the language switch (i18n.ts): remembered, the page reloads in that language (the plan stays in the address)
$('langSwitch').addEventListener('click', (e) => {
  const l = (e.target as HTMLElement).closest<HTMLElement>('[data-lang]')?.dataset.lang as typeof lang | undefined;
  if (l && l !== lang) switchLanguage(l);
});

// phone layout: the map controls and the footer sit above the sheet
new ResizeObserver(() => document.documentElement.style.setProperty('--sheet-h', $('sheet').offsetHeight + 'px')).observe($('sheet'));
changed();

/** The sheet's status line; 'busy' = a wait the user started (spinner, CLAUDE.md "Messages"). */
function status(t: string, kind: 'info' | 'error' | 'busy' = 'info') {
  const s = $('status');
  s.textContent = t;
  s.classList.toggle('error', kind === 'error');
  s.classList.toggle('busy', kind === 'busy' && t !== '');
}
function empty(): FeatureCollection { return { type: 'FeatureCollection', features: [] }; }
function escapeHtml(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
function localStorageGet(k: string): string | null { try { return localStorage.getItem('onpath.' + k); } catch { return null; } } // expected: storage blocked
function localStorageSet(k: string, v: string) { try { localStorage.setItem('onpath.' + k, v); } catch { /* expected: storage blocked */ } }

/** White chevron ">" pointing along the line (the app's MapController.arrowBitmap: 16 px, drawn at 2× here). */
function arrowImage(): ImageData {
  const s = 32, c = document.createElement('canvas');
  c.width = c.height = s;
  const g = c.getContext('2d')!;
  g.strokeStyle = '#fff'; g.lineWidth = s * 0.16; g.lineCap = 'round'; g.lineJoin = 'round';
  g.beginPath(); g.moveTo(s * 0.32, s * 0.2); g.lineTo(s * 0.72, s * 0.5); g.lineTo(s * 0.32, s * 0.8); g.stroke();
  return g.getImageData(0, 0, s, s);
}
