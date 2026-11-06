// OnPath web planner (docs/design/web-planner.md §6–§13). The page mirrors the phone's Plan screen in a web layout: a
// header line (logo, My routes, Import GPX, …; on the right Save and the Phone button), the search pill with the map toolbar beside it (undo / redo, eye,
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
import { RouteProfile, Terrain3D } from './terrainview';
import { STEEP_COLOURS } from './profile';
import { PACKED, usePacked, packedGlyphs } from './packed';
import { Cells } from './cells';
import { setupSearch, type PlaceCard, type PlaceRole } from './search';
import { NO_PADDING, roomFor } from './cardanchor';
import { icon, activityIcon, needle, logo, type IconName } from './icons';
import { ActivityPicker, type ActivityRow, type PaceChoices } from './activity';
import { holdMenu, type MenuItem } from './menus';
import { Position } from './position';
import { PlanSync, shownTitle, type SavedPlan } from './sync';
import { myPlansDialog, joinDialog, saveRoute, askDiscard, setDiscardSave, phoneDialog, phonePill, appDate } from './plans';
import { MapContentPanel } from './mapcontent';
import { sortable } from './sortable';
import { longRouteOk, allowLongRoute, notRoutedTexts, setNotRouted, type Before } from './longroute';
import { post, dismiss } from './msgcard';
import { setupGpxImport } from './gpximport';
import { overviewStyle, followCover, followPackages } from './overview';
import { PlanGapsLayer } from './plangaps';
import { MapNotices } from './mapnotice';
import { MapAreas, type MapTexts } from './areas';
import { t, tp, lang, languages, switchLanguage, number, distance, locale } from './i18n';
import { appHint } from './apphint';
import { setupSheetSteps } from './sheetstep';
import { installDiag, addDiagSection, problem } from './diag';
import { watchVersion, BUILD } from './version';
import { keepStats, planKey, trackKey } from './planstats';
import './fonts/manrope.css';
import './style.css';
import manropeLicence from './fonts/manrope/OFL.txt?url';

const ROUTE = '#1E3FAE';
// the plan's points like the app since 2026-11-01 (owner, like Komoot – OnPathColors.PointStart / PointStop / PointGoal,
// MapController.POINT_*): dark green start, near-black stops, dark red goal, no white ring – apart from the blue route by hue
const COLORS = { start: '#245F46', via: '#262A2E', end: '#A32020' };
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
  /** The folder the saved plan is in (A-017: one per phone + "none" = Without phone); null for a new route. */
  savedFolder: null as string | null,
  /** The saved route's stored code as opened / last saved here – not moved along by snapping (sync/SaveRule, review sync H3). */
  storedCode: null as string | null,
  activity: (localStorageGet('activity') as Activity) ?? 'HIKE',
  sac: Number(localStorageGet('sac') ?? 2),
  plan: null as PlanResult | null,
  /** The open plan came from the phone, routed with another fingerprint (sync field 7, web-sync.md §5.2): one grey line. */
  otherRouting: false,
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
  // owner 2026-10-30: no "Send to phone" here – sharing is the sheet's Share icon. Save and the Phone button are not
  // entries: they sit on the right of the header, also at phone width (web-sync.md §14.6)
  // phone width only (owner 2026-11-06): the header has no room for New plan there – it is the first ☰ entry; wide it is a header button
  { id: 'menuNewPlan', icon: 'add', label: t('plan_new_route') },
  { id: 'menuPlans', icon: 'folder', label: t('routes_title') },
  { id: 'menuImportGpx', icon: 'route', label: t('routes_import_gpx') }, // gpximport.ts (the app's My routes → Import GPX)
  { id: 'menuAbout', icon: 'help', label: t('web_nav_help') },
];
/** The website's pages for this language (web/strings: the app, privacy, legal notice) – calm links, new tab. */
const siteLink = (key: string, label: string, cls = '') =>
  `<a class="${cls}" href="${t(key)}" target="_blank" rel="noopener">${escapeHtml(label)}</a>`;
const navItem = (id: string) => NAV.find((n) => n.id === id)!;

document.title = t('web_page_title');
// No WebGL2 (old browser, hardware acceleration off, some headless browsers): maplibre cannot draw and the page would
// stay blank without a word (owner 2026-10-30) – say so plainly and stop here.
if (!hasWebGl2()) {
  stopPage(t('web_no_webgl_title'), t('web_no_webgl_text'));
  console.warn('[map] no WebGL2 – the map cannot be drawn');
  await new Promise(() => {}); // expected: nothing else can run without a map
}
// The routing engine is TeaVM WasmGC (review 2026-11-01, web H2): Safari / iOS before 18.2, Chrome < 119, Firefox < 120
// cannot run it – a reload never helps, so say plainly that the browser is too old, before anything else loads.
if (!hasWasmGc()) {
  oldBrowserPage();
  await new Promise(() => {}); // expected: the planner cannot run without its engine
}
document.querySelector<HTMLDivElement>('#app')!.innerHTML = `
  <header id="header">
    <a class="brand" href="${t('web_url_app')}" target="_blank" rel="noopener" title="${escapeHtml(t('web_brand_title'))}">${logo(30)}<span class="wordmark">OnPath <span class="product">${escapeHtml(t('web_product'))}</span></span></a>
    <button id="menuBtn" class="iconbtn" aria-label="${escapeHtml(t('plan_menu'))}" aria-haspopup="menu" aria-expanded="false">${icon('menu')}</button>
    <nav id="mainMenu" class="nav" aria-label="${escapeHtml(t('web_product'))}">
      ${NAV.map((n) => `<button id="${n.id}" role="menuitem" title="${escapeHtml(n.label)}">${icon(n.icon, 20)}<span>${escapeHtml(n.label)}</span></button>`).join('')}
      <a class="navlink" href="${t('web_url_try')}" target="_blank" rel="noopener" title="${escapeHtml(t('web_link_app'))}">${icon('download', 20)}<span>${escapeHtml(t('web_link_app'))}</span></a>
    </nav>
    <span class="hint muted">${escapeHtml(t('web_header_hint'))}</span>
    <span id="headActs" class="headacts">
      <button id="newPlan" class="hbtn newbtn" title="${escapeHtml(t('plan_new_route'))}" aria-label="${escapeHtml(t('plan_new_route'))}" disabled>${icon('add', 20)}<span class="lbl">${escapeHtml(t('plan_new_route'))}</span></button>
      <button id="menuSavePlan" class="hbtn savebtn" data-state="empty" disabled>${icon('save', 20)}<span class="lbl">${escapeHtml(t('common_save'))}</span></button>
      <button id="syncBadge" class="hbtn phonebtn" aria-haspopup="dialog" title="${escapeHtml(t('web_pair_phone'))}">${icon('phone', 20)}<span class="lines"><span class="l1"><span class="lbl">${escapeHtml(t('web_pair_phone'))}</span><i class="syncdot" aria-hidden="true" hidden></i></span><span class="sub" hidden></span></span></button>
    </span>
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
    <button id="terrain3d" class="tool three" aria-pressed="false" disabled>${escapeHtml(t('web_terrain_3d'))}</button>
    <span class="sep"></span>
    <button id="mapContent" class="tool wide" aria-haspopup="dialog" title="${escapeHtml(t('web_map_content_title'))}">${icon('layers')}<span>${escapeHtml(t('web_map_content'))}</span>${icon('dropDown', 18)}</button>
  </div>
  </div>
  <div id="placecard" class="placecard" hidden></div>
  <div id="mapnotes" class="mapnotes" hidden></div>
  <div id="threeDNote" class="threednote" hidden>${escapeHtml(t('web_terrain_view_only'))}<br><span class="turnhint">${
    escapeHtml(t(matchMedia('(pointer: coarse)').matches ? 'web_terrain_turn_touch' : 'web_terrain_turn_mouse'))}</span></div>
  <section id="bigProfile" class="bigprofile" aria-label="${escapeHtml(t('plan_elevation_profile'))}" hidden>
    <div class="bphead"><b>${escapeHtml(t('plan_elevation_profile'))}</b><span class="bplegend muted small">${
      ['< 5 %', '5–10 %', '10–15 %', '> 15 %'].map((l, i) => `<span><i style="background:${STEEP_COLOURS[i]}"></i>${escapeHtml(l)}</span>`).join('')}</span>
      <button class="iconbtn bpclose" aria-label="${escapeHtml(t('common_close'))}" title="${escapeHtml(t('common_close'))}">${icon('close', 20)}</button></div>
    <div class="bpplot"></div>
  </section>
  <div id="footer"><button id="credits" class="credit" aria-label="${escapeHtml(t('plan_map_credits'))}">© OpenStreetMap</button>
    <span class="sitelinks">${siteLink('web_url_privacy', t('web_link_privacy'))}<span aria-hidden="true">·</span>${siteLink('web_url_legal', t('web_link_legal'))}</span></div>
  <section id="sheet" aria-label="${escapeHtml(t('web_plan_sheet'))}">
    <button id="handle" aria-label="${escapeHtml(t('plan_resize_panel'))}" title="${escapeHtml(t('plan_resize_panel'))}" aria-controls="sheet"><span></span></button>
    <div class="sheethead">
      <button id="activityChip" class="chip" aria-haspopup="menu" aria-expanded="false"></button>
      <div id="summary"><div class="headline"></div><div class="subline muted"></div></div>
      <button id="share" class="sharebtn" aria-label="${escapeHtml(t('web_share_needs_two'))}" title="${escapeHtml(t('web_share_needs_two'))}" disabled>${icon('share', 18)}<span>${escapeHtml(t('web_share'))}</span></button>
      <button id="sheetStep" class="iconbtn" aria-controls="sheet"></button>
    </div>
    <!-- the app's PlanActions (owner 2026-10-31, no ⋯): icon + word side by side; Save is the header's, GPX is in Share -->
    <div id="planActions" class="planactions" role="group" hidden>
      <button id="clear" class="textbtn">${icon('close', 18)}${escapeHtml(t('plan_close_plan'))}</button>
      <button id="reverse" class="textbtn">${icon('swap', 18)}${escapeHtml(t('plan_reverse'))}</button>
      <button id="roundTrip" class="textbtn">${icon('loop', 18)}${escapeHtml(t('plan_round_trip'))}</button>
    </div>
    <div id="savedLine" class="savedline muted small" hidden></div>
    <div id="routingNote" class="muted small" hidden></div>
    <div id="notice" class="notice" hidden></div>
    <div id="offmap" class="offmap" hidden></div>
    <button id="warning" class="notice warnline" aria-expanded="false" hidden></button>
    <ul id="spots" class="spots" hidden></ul>
    <div id="profile"></div>
    <div id="infoline" class="infoline" hidden></div>
    <button id="changedLine" class="infoline changedline" hidden></button>
    <div id="breakdown" class="breakdown" hidden></div>
    <div id="pointsSection" hidden>
      <div class="pointshead"><b>${escapeHtml(t('plan_points'))}</b></div>
      <ol id="points"></ol>
    </div>
    <div id="status" class="status muted" role="status"></div>
    <div id="apphint"></div>
  </section>
  </div>`;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const phoneWidth = () => matchMedia('(max-width: 700px)').matches;
/** The sync once it exists (the header's Save is drawn before it). */
let syncRef: PlanSync | null = null;
saveEntry('empty'); // the header's Save: greyed until a route exists (renderSaved keeps it current)
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
watchVersion(); // a newer planner deployed while this tab is open: one box "New version of the planner" [Reload] (version.ts)
addDiagSection(() => `build: ${BUILD}`);
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
// the terrain view (terrainview.ts, owner 2026-11-05): the profile linked to the map, 3D on demand
const routeProfile = new RouteProfile(map, ROUTE, $('profile'), $('bigProfile'), (open) => {
  document.body.classList.toggle('bigprofile-open', open);
  if (open) fitRoute(); // the whole route above the big profile
});
const terrain3d = new Terrain3D(map, $<HTMLButtonElement>('terrain3d'), $('threeDNote'), () => markers.forEach((m) => m.setDraggable(!terrain3d.on)),
  () => { // 3D frames the route like the fit button (clear of the sheet and the big profile)
    const b = routeBounds();
    if (!b) return null;
    const p = map.getPadding();
    if (p.top || p.bottom || p.left || p.right) map.setPadding(NO_PADDING);
    const padding = fitPadding(), cam = map.cameraForBounds(b, { padding, maxZoom: 16 });
    return cam?.zoom != null ? { center: b.getCenter(), zoom: cam.zoom, padding } : null;
  });
(window as unknown as { __terrain: Terrain3D }).__terrain = terrain3d; // for the browser tests
(window as unknown as { __profile: RouteProfile }).__profile = routeProfile;
// in 3D a point's pin stands on the ground: placed again once the heights under it are loaded (before that it sat at 0 m)
map.on('idle', () => { if (terrain3d.on) markers.forEach((m) => m.setLngLat(m.getLngLat())); });
if (useCells) followCover(map, cells.found); // the overview hides where the cells' detailed map is
if (useCells) cells.attach(map); // a failed cell file is tried again by reloading its source (cells.ts)
/** The app's Diagnostics line (routing/RoutingFingerprint + the loaded rd5 dates), refreshed after each plan. */
let routingLine = '-';
addDiagSection(() => `routing: ${routingLine}`);
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
  routeProfile.addLayers();
  terrain3d.render();
  applyMapContent();
  lookReady = true; applyActivityLook();
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
const planGaps = new PlanGapsLayer(map); // points the route does not reach (plangaps.ts)
(window as unknown as { __engine: EngineClient }).__engine = engine; // for the browser tests (timings)
(window as unknown as { __state: typeof state }).__state = state; // for the browser tests (pace: the plan the sheet shows)
status(t('web_engine_loading'), 'busy');
/** The engine, loaded with two more tries on a failed download (a network blip at start must not need a reload). */
async function configureEngine() {
  for (let i = 0; ; i++) {
    const r = await engine.configure(base, params.get('segments') ?? base + 'segments/', PACKED, lang);
    if (r.ok || r.unsupported || i >= 2) return r;
    problem('engine', 'routing engine not loaded – trying again', r.error);
    await new Promise((res) => setTimeout(res, 3000 * (i + 1)));
  }
}
const ready = configureEngine().then((r) => {
  if (r.ok) {
    document.body.dataset.engine = 'ready'; // the browser tests wait for this (the timing line is ?debug only)
    status(DEBUG ? t('web_engine_ready', Math.round(r.stats?.engineLoadMs ?? 0)) : '');
  } else if (r.unsupported) {
    problem('engine', 'routing engine cannot run in this browser', r.error, 'error');
    oldBrowserPage();
  } else {
    problem('engine', 'routing engine not loaded', r.error, 'error'); // the detail goes to the diagnostics only
    status(t('web_engine_failed'), 'error');
  }
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
syncRef = sync;
(window as unknown as { __sync: PlanSync }).__sync = sync; // for the browser tests
syncBadge(); // the header entry with its full tooltip at once – sync.init waits for the engine (CI race in sync-smoke)
const syncReady = ready.then(async (ok) => {
  if (!ok) return false;
  await sync.init();
  if (pairLink) joinDialog(sync, pairLink);
  syncBadge();
  sync.onChange(syncBadge);
  sync.onChange(() => saveEntry(saveState)); // dark while anything is to save or waits for the phone (§14.6)
  sync.onFolder((_from, to) => { openDraft(to); });
  saveEntry(saveState);
  $('syncBadge').dataset.ready = '1'; // the sync runs (tests wait for this)
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
map.on('movestart', (e) => { clearTimeout(loadingTimer); if ((e as { originalEvent?: unknown }).originalEvent) lastMapTouch = performance.now(); }); // a pan / zoom by the user
// any press on the map counts as touched (the app's lastMapTouchMs): a point being set stops the first route's fit
map.on('mousedown', () => { lastMapTouch = performance.now(); });
map.on('touchstart', () => { lastMapTouch = performance.now(); });
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
  if (runCause === 'edit') state.otherRouting = false; // edited here: the route is the page's own now
  editBefore = runCause === 'edit' ? { points: state.points, names: state.meta.map((m) => m.name) } : null;
  // sent now with the points as they are (the worker keeps the order), not waited for: a long plan still running in the
  // one engine worker must not hold the page (Clear / Delete while planning answers at once, as in the app)
  const recorded = engine.call('historyRecord', enc(state.points));
  // the plan's first route, or a place from the card (the app's routing/RouteFit.firstRoute / fitAfterPlace, shared):
  // the map shows it whole once it is there, unless the map was touched meanwhile (fitFirstRoute). Asked before the
  // plan: the one worker answers in order, so this is known when the route comes.
  const fromCard = placeFit; placeFit = false;
  const asked = performance.now();
  const fit = (yes: string) => { if (yes === 'true') { fitNextRoute = true; fitAskedAt = asked; } };
  engine.call('routeFirst', state.points.length, points.length).then(fit, (e) => problem('plan', 'route fit rule not asked', e));
  if (fromCard) engine.call('routeFitAfterPlace', enc(state.points), enc(points)).then(fit, (e) => problem('plan', 'route fit rule not asked', e));
  setPoints(points, meta);
  await changed();
  await recorded;
}

function setPoints(points: LatLon[], meta?: Meta[]) {
  state.points = points;
  state.meta = meta ?? points.map((p) => ({ ...(known.get(key(p)) ?? { pinned: false }) }));
  state.points.forEach((p, i) => known.set(key(p), state.meta[i]));
}

/**
 * Point edits one after another (review 2026-11-01, web H4): each works out its new list from the points as the edits
 * before it left them – two clicks while the one engine worker is busy with a long plan no longer both insert into the
 * same old list (the second destination landing before the first), and a late edit never overwrites a drag. Only the
 * point list waits; the plan of each edit runs as before (a newer plan supersedes an older one). [make] returns the
 * started plan ({ planned }), or null when there is nothing to change.
 */
let editChain: Promise<void> = Promise.resolve();
function queueEdit(make: () => Promise<{ planned: Promise<void> } | null>): Promise<void> {
  let planned: Promise<void> = Promise.resolve();
  const run = editChain.then(async () => { planned = (await make())?.planned ?? planned; });
  editChain = run.catch((e) => problem('plan', 'a point edit failed', e, 'error'));
  return run.then(() => planned);
}

/** One of the app's plan edits (PlanEdits, in the engine). */
function applyEdit(op: 'reverse' | 'roundTrip' | 'startHere' | 'delete' | 'move', index = 0, to = 0) {
  closeMenu();
  return queueEdit(async () => ({ planned: commit(await engine.edit(op, state.points, index, to)) }));
}

function step(op: 'undo' | 'redo') {
  closeMenu();
  return queueEdit(async () => {
    const t = await engine.call('historyStep', op, enc(state.points));
    runCause = op;
    state.otherRouting = false;
    if (t === 'null') return null;
    setPoints(dec(t));
    return { planned: changed() };
  });
}

/**
 * Deletes plan point [i] – map menu Delete and the Points list ✕: the app's PlanPoints.deleteAt (in the engine), so a
 * round trip stays closed at the next point, as on the phone. Names and pins follow their points by value.
 */
async function deletePoint(i: number) { await applyEdit('delete', i); }

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
  // no points: nothing to ask the engine (it may still be busy with a plan that was just cleared)
  const r = state.points.length ? await engine.rows(state.points, namesText()) : { roundTrip: false, rows: [] };
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
    const m = new maplibregl.Marker({ element: el, draggable: !terrain3d.on }).setLngLat([lon, lat]).addTo(map); // 3D: view only
    // a dragged point is a new place: no longer pinned or named (as in the app); a loop's start moves its end too
    m.on('dragend', () => {
      draggedAt = Date.now();
      const p = m.getLngLat();
      const was = key([lat, lon]), loop = state.roundTrip && i === 0;
      queueEdit(async () => {
        // by value: an edit queued before this drag may have moved the point to another index
        const k = state.points[i] && key(state.points[i]) === was ? i : state.points.findIndex((q) => key(q) === was);
        if (k < 0) return null; // the point went meanwhile (undo, delete) – nothing to move
        const pts = state.points.slice(), meta = state.meta.slice(), end = pts.length - 1;
        pts[k] = [p.lat, p.lng]; meta[k] = { pinned: false };
        if (loop && k === 0 && key(pts[end]) === was) { pts[end] = [p.lat, p.lng]; meta[end] = { pinned: false }; }
        return { planned: commit(pts, meta) };
      });
    });
    el.addEventListener('click', (ev) => {
      ev.stopPropagation();
      if (Date.now() - draggedAt < 300) return; // the click that ends a drag
      press(m.getLngLat(), i);
    });
    markers.push(m);
  });
  // the row texts come from the app's PlanPoints.roleText / legText (shared, web review M15) – the page only renders them
  $('points').innerHTML = r.rows.map((row) => {
    const color = row.loop ? COLORS.start : stopColor(row.i, n);
    return `<li data-i="${row.i}">
      <button class="grip" title="${escapeHtml(t('web_grip_title'))}" aria-label="${escapeHtml(t('web_grip_move', row.name))}">${icon('drag', 20)}</button>
      <span class="num" style="background:${color}">${row.loop ? '↺' : row.i + 1}</span>
      <span class="txt"><span>${escapeHtml(row.name)}</span>${row.roleText ? `<span class="muted small">${escapeHtml(row.roleText)}</span>` : ''}</span>
      ${row.legText ? `<span class="leg muted">${escapeHtml(row.legText)}</span>` : '<span class="leg"></span>'}
      <button class="del iconbtn" aria-label="${escapeHtml(t('web_delete_point', row.name))}">${icon('close', 20)}</button></li>`;
  }).join('');
  // "↻ Rundtour" is a switch (the app's PlanAction on=, owner 2026-11-06, onpath.private#40): filled with ✓ on a round
  // trip, a click takes the way back out; "⇅ Umkehren" grey when backwards is the same plan (A → B → A)
  const loop = $<HTMLButtonElement>('roundTrip');
  loop.disabled = !(state.roundTrip || (n >= 2 && (r.canRoundTrip ?? true)));
  loop.classList.toggle('on', state.roundTrip);
  loop.setAttribute('aria-pressed', String(state.roundTrip));
  loop.title = state.roundTrip ? t('plan_round_trip_on_desc') : '';
  loop.innerHTML = `${icon(state.roundTrip ? 'check' : 'loop', 18)}${escapeHtml(t('plan_round_trip'))}`;
  $<HTMLButtonElement>('reverse').disabled = n < 2 || !(r.canReverse ?? true);
  $('pointsSection').hidden = n === 0;
  $('planActions').hidden = n === 0; // the app's RouteRules.canClear: something to close
  for (const id of ['newPlan', 'menuNewPlan']) $<HTMLButtonElement>(id).disabled = n === 0; // New plan: the same rule as ✕ Neu planen
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
let placeCard: PlaceCard = { close: () => {}, isOpen: () => false, removePin: () => {}, planChanged: () => {} }; // the search's place card (set up below)
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
    // on the route: the profile's cursor follows (terrain view A) – in 3D the pointer only shows, it does not plan
    const onRoute = routeProfile.hover(map.isMoving() ? null : pt);
    map.getCanvas().style.cursor = hit && !terrain3d.on ? 'pointer' : onRoute ? 'crosshair' : '';
  });
});
map.on('mouseout', () => { map.getCanvas().style.cursor = ''; routeProfile.hover(null); });

async function press(at: maplibregl.LngLat, marker: number, pt?: maplibregl.Point) {
  const results = document.getElementById('results');
  if (results && !results.hidden) { results.hidden = true; return; } // a click beside the search results only closes them
  if (placeCard.isOpen()) { placeCard.close(); return; } // … and one beside the place card closes it (nothing added)
  if (terrain3d.on) { closeMenu(); return; } // 3D is for looking (the note on the map says so); planning is on the flat map
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
function addPoint(at: maplibregl.LngLat) {
  // ~24 px on screen = "clicked on the line" (the phone uses 24 dp)
  const mPerPx = 40075016 * Math.cos((at.lat * Math.PI) / 180) / (512 * 2 ** map.getZoom());
  return queueEdit(async () => {
    const i = await engine.insertIndex(state.points, at.lat, at.lng, 24 * mPerPx);
    const pts = state.points.slice(), meta = state.meta.slice();
    pts.splice(i, 0, [at.lat, at.lng]); meta.splice(i, 0, { pinned: false });
    return { planned: commit(pts, meta) };
  });
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
  const acts: string[] = JSON.parse(await engine.call('pointMenu', i, n, !!name?.trim(), state.roundTrip, enc(state.points)));
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

/**
 * POI menu (ui/MapOverlays.PoiMenu): name, kind · elevation; what adding it does – "Set as destination", "Add as stop
 * 2", … (the app's poiAddLabel = AddPreview.label) – (empty plan: Start here · Go here); Google.
 */
async function poiMenu(f: MapGeoJSONFeature) {
  const [lon, lat] = (f.geometry as GeoJSON.Point).coordinates;
  const pr = f.properties ?? {};
  const info = JSON.parse(await engine.call('poiMenu', String(pr.kind ?? ''), String(pr.name ?? ''), pr.ele != null ? String(pr.ele) : '', lat, lon));
  if (!info) return;
  const hit = { title: info.title, name: pr.name ? String(pr.name) : null, lat, lon } as Hit;
  const items: MenuItem[] = state.points.length === 0
    ? [{ icon: 'tripOrigin', label: t('plan_start_here'), act: () => addPlace(hit, 'START') },
      await goHereItem(() => fromMe([Number(lat.toFixed(6)), Number(lon.toFixed(6))], { pinned: true, name: info.title }))]
    : [{ icon: 'addLocation', label: (await engine.placePreview(state.points, lat, lon)).label ?? t('search_card_set_destination'),
      act: () => addPlace(hit, 'SMART') }];
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
  if (!/[#&?]p=/.test(location.href)) { if (!draftsOn) restoreDraft(); return; }
  const shared = await engine.parse(location.href);
  if (!shared) { status(t('msg_plan_link_unreadable'), 'error'); return; }
  if (shared.points.map(key).join(';') === state.points.map(key).join(';') && shared.activity === state.activity && shared.sac === state.sac) return; // our own address update
  forgetSaved();
  // a shared plan opened in the browser: the one calm line about the app, in the sheet (never over the route – it is
  // there before the camera fits the plan beside the sheet)
  if (shared.points.length >= 2) appHint($('apphint'));
  await loadShared(shared);
  // the link's title is the name Save proposes while its points are unchanged (the app's linkTitle)
  linkTitle = shared.title ? { title: shared.title, at: enc(state.points) } : null;
  if (!draftsOn) await restoreDraft(); // the page opened with its route in the address: which saved route it is
}
/** The next commit comes from the place card: RouteFit.fitAfterPlace decides whether its route is fitted. */
let placeFit = false;
/** The next route is the plan's first: fit it whole (the app's fitNextRoute); when it was asked; the last map gesture. */
let fitNextRoute = false, fitAskedAt = 0, lastMapTouch = 0;
/** A plan link's title and the points it was for (moved along when they are snapped) – the app's AppViewModel.linkTitle. */
let linkTitle: { title: string; at: string } | null = null;

/** Shows [shared] as the plan (from a link or from My plans). */
/** Set by My routes just before a plan from the phone opens (its otherRouting); any other load clears the grey line. */
let openedOtherRouting = false;
async function loadShared(shared: SharedPlan) {
  state.title = shared.title;
  state.otherRouting = openedOtherRouting; openedOtherRouting = false;
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
  // the big elevation profile below the map (terrain view A): the route stays above it
  const bp = $('bigProfile'), bpH = bp.hidden ? 0 : $('stage').getBoundingClientRect().bottom - bp.getBoundingClientRect().top;
  // the sheet as it is now (its step, or the track panel); the place card on a phone stands in for it
  if (phoneWidth()) {
    const card = document.body.classList.contains('card-open') ? $('placecard').getBoundingClientRect().height : 0;
    // never more than the screen allows: at least 120 px of map stay, or fitBounds refuses to move at all
    const mapH = $('stage').getBoundingClientRect().height;
    return { top: 130, bottom: Math.min(Math.max(sheet.height, card, bpH) + 30, Math.max(0, mapH - 130 - 120)), left: 40, right: 40 };
  }
  // wide: the room beside the side panel, or – when the panel is short (small step) – the larger room below it
  const stage = $('stage').getBoundingClientRect(), w = stage.width, h = stage.height;
  const beside = { top: 90, bottom: Math.max(50, bpH + 30), left: sheet.right - stage.left + 50, right: 90 };
  const below = { top: sheet.bottom - stage.top + 40, bottom: Math.max(50, bpH + 30), left: 40, right: 90 };
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
  if (!state.points.length) { if (location.hash) history.replaceState(null, '', page); storeDraft(''); return; }
  const asked = location.href;
  if (!(await ready)) return;
  const code = await engine.encode(sharedPlan());
  // the address changed while the engine encoded (a plan link opened, the hash typed): that newer address wins – writing
  // the old plan over it would make its hashchange see "our own address update" and drop the link (CI 2026-11-06)
  if (location.href !== asked) return;
  history.replaceState(null, '', page + '#p=' + code);
  storeDraft(code);
}

// ---- the open route per folder (A-017 point 1): kept with its folder, back when the folder opens again ----
let draftsOn = false; // only once the sync knows the open folder (and the page restored the saved route's state)
let draftsDone: () => void = () => {};
/** The reloaded page knows again which saved route it shows (the header's Save waits for it – never a duplicate). */
const draftsRestored = new Promise<void>((r) => { draftsDone = r; });
function storeDraft(code: string) {
  if (!draftsOn || !syncRef) return;
  syncRef.setDraft({ code, savedId: state.savedId, savedTitle: state.savedTitle, savedCode: state.savedCode, savedFolder: state.savedFolder, storedCode: state.storedCode })
    .catch((e) => console.warn('[Problems] WARN sync: the open route was not kept for its folder', e));
}
/** Another folder opened (pairing, unpairing, the phone ended it): its own open route comes back, or an empty map. */
async function openDraft(folder: string) {
  draftsOn = false;
  const d = await sync.draft(folder);
  const shared = d?.code ? await engine.parse('p=' + d.code) : null;
  forgetSaved();
  if (shared) await loadShared(shared);
  else if (state.points.length) { state.title = null; await commit([], []); }
  if (shared && d) { state.savedId = d.savedId; state.savedTitle = d.savedTitle; state.savedCode = d.savedCode; state.savedFolder = d.savedFolder ?? null; state.storedCode = d.storedCode ?? null; }
  draftsOn = true;
  render();
  rememberInAddress();
}
/**
 * At start. After a reload the address holds the route; its saved state (which saved route, which folder) comes from
 * the draft. Opened without a route in the address (bookmark, typed address, a tab the browser discarded) and nothing
 * on the map: the open route of this folder comes back, saved or not – like the app's "Plan restored at app start"
 * (review 2026-11-01, web H3: an unsaved route must not be lost).
 */
async function restoreDraft() {
  if (!(await syncReady)) return;
  const d = await sync.draft();
  if (d?.code && !state.points.length && !/[#&?]p=/.test(location.href)) {
    const shared = await engine.parse('p=' + d.code);
    if (shared && shared.points.length && !state.points.length) {
      await loadShared(shared);
      state.savedId = d.savedId; state.savedTitle = d.savedTitle; state.savedCode = d.savedCode; state.savedFolder = d.savedFolder ?? null; state.storedCode = d.storedCode ?? null;
      render();
    }
  } else if (d?.code && state.points.length && d.code === await engine.encode(sharedPlan())) {
    state.savedId = d.savedId; state.savedTitle = d.savedTitle; state.savedCode = d.savedCode; state.savedFolder = d.savedFolder ?? null; state.storedCode = d.storedCode ?? null;
    render();
  }
  draftsOn = true;
  draftsDone();
  if (state.points.length) rememberInAddress(); // the route back in the address (a reload keeps it) and in the draft
}

// ---- planning ----
/** The wait box shows only after this long (a fast route shows nothing). */
const WAIT_CARD_MS = Number(params.get('waitMs') ?? 1000); // ?waitMs=0: the browser tests see every wait
/** Planning or not – also for the page's tests (body[data-planning]); off takes the wait box away. */
function setPlanning(on: boolean) {
  state.planning = on;
  document.body.dataset.planning = on ? '1' : '';
  if (!on) dismiss('routing');
}
/** The wait box in the middle of the free map (beside / above the sheet), with Cancel – the app's onCancelRouting. */
function waitCard(text: string) {
  post({ id: 'routing', kind: 'hinweis', busy: true, title: text, buttons: [{ label: t('common_cancel'), act: cancelRouting }], at: mapMiddle });
}
function mapMiddle() {
  const st = $('stage').getBoundingClientRect(), sh = $('sheet').getBoundingClientRect(), bar = $('topbar').getBoundingClientRect();
  if (phoneWidth()) return { x: st.left + st.width / 2, y: (bar.bottom + Math.min(sh.top, st.bottom)) / 2 };
  const left = Math.max(st.left, sh.right);
  return { x: (left + st.right) / 2, y: (bar.bottom + st.bottom) / 2 };
}
/**
 * Cancel of the wait (the app's cancelRouting → cancelRun(NotRouted.STOPPED)): the running plan's result is dropped (the
 * one engine worker cannot stop mid-way – it finishes unseen), then the app's PlanStatus.onCancel: undo the edit, put
 * the activity back, or keep the points with "Route not calculated – calculation stopped." + Calculate.
 */
async function cancelRouting() {
  if (!state.planning) return;
  problem('routing', 'route calculation cancelled by the user');
  setNotRouted(t('plan_not_routed_stopped'), t('plan_calculate'));
  await cancelLongRoute();
}
/** The points the shown plan was routed for (enc) – My routes keeps its numbers only for exactly these. */
let planFor = '';
/**
 * My routes' length and climb (planstats.ts, owner 2026-11-06): kept for [code] when the shown plan is routed for the
 * points shown now and is a real route (no straight pieces, no gaps, routing data there) – never a stale plan's numbers.
 */
function keepPlanStats(code: string) {
  const p = state.plan;
  if (!p || state.planning || p.error || p.failed || p.notice || p.gaps.gaps.length || planFor !== enc(state.points)) return;
  if (p.legs.some((l) => l.straight)) return;
  keepStats(planKey(code), { m: p.distanceM, up: p.ascentM, down: p.descentM });
}
/** The plan before the point edit that is being planned now (the long-route question's "Far from your route"). */
let editBefore: Before | null = null;
async function changed() {
  const before = editBefore; editBefore = null; dismiss('slowplan'); // a new edit answers the time-limit question
  placeCard.planChanged(); // the pin card's "+ Zur Route" / "Hierhin" follows the plan
  await drawPoints();
  const few = state.points.length < 2;
  // fewer than two points (Clear route, a point deleted): no route – shown at once. A plan still running in the engine
  // (one worker, it cannot be stopped mid-way) is dropped when it ends (state.latest), as the app cancels it.
  if (few) { state.latest++; state.plan = null; setPlanning(false); planGaps.clear(); status(''); }
  // Share (icon next to ⋯): always there (owner 2026-10-30), greyed until a start and a destination are set
  const sh = $<HTMLButtonElement>('share'), two = state.points.length >= 2, shw = two ? t('web_share') : t('web_share_needs_two');
  sh.disabled = !two; sh.title = shw; sh.setAttribute('aria-label', shw);
  rememberInAddress();
  state.notRouted = false;
  if (few) render();
  const h = JSON.parse(await engine.call('historyState'));
  state.canUndo = h.canUndo; state.canRedo = h.canRedo;
  if (few) { render(); return; }
  planGaps.clear(); // the last plan's gaps belong to other points
  // the app's long-route question first (routing/LongRoute): nothing is routed or downloaded until it is answered
  const answer = await longRouteOk(engine, state.points, state.activity, before);
  if (answer === 'cancel') { await cancelLongRoute(); return; }
  if (answer !== 'plan') { await startFresh(answer.fresh); return; }
  await planRun(1);
}

/**
 * The time limit was reached (owner 2026-11-03, onpath.private#21 B, the app's slowPlanMessage / answerSlowPlan –
 * routing/SlowPlan): "Die Route ist lang, die Berechnung dauert. Weiter warten?" – Abbrechen · Weiter warten. A FRAGE,
 * it stays until answered; the points and the last line stay. Weiter warten = the run again with one more period (the
 * routed pieces come from the engine's cache), Abbrechen = like Cancel of the wait (PlanStatus.onCancel).
 */
function askSlowPlan(rounds: number) {
  post({ id: 'slowplan', kind: 'frage', title: t('plan_slow_title'), detail: t('plan_slow_text'), at: mapMiddle, buttons: [
    { label: t('common_cancel'), act: () => { dismiss('slowplan'); problem('routing', `slow plan: cancel after ${rounds} period(s)`); setNotRouted(t('plan_not_routed_stopped'), t('plan_calculate')); cancelLongRoute(); } },
    { label: t('plan_slow_wait'), act: () => { dismiss('slowplan'); problem('routing', `slow plan: keep waiting after ${rounds} period(s)`); planRun(rounds + 1); } },
  ] });
}

/** One plan run of the current points with [rounds] time-limit periods (1, more after "Weiter warten"). */
async function planRun(rounds: number) {
  dismiss('slowplan');
  const id = ++state.latest;
  setPlanning(true);
  render();
  newPointsAsked();
  // the wait (owner 2026-11-02: "man guckt auf den Bildschirm und nichts passiert"): after about a second one box in the
  // middle of the map – spinner, the routing data download (the app's "Loading the map for your area · about 9 MB",
  // map-cells.md §19.8), then "Calculating the route…", and Cancel; a fast route shows nothing (no flash)
  let wait = t('plan_calculating');
  const show = () => { if (id === state.latest && state.planning) waitCard(wait); };
  let shown = false;
  const waitTimer = setTimeout(() => { shown = true; show(); }, WAIT_CARD_MS);
  const routed = state.points;
  const r = await engine.plan(routed, state.activity, state.sac, sharedPlan().pinned, (pr) => {
    if (id !== state.latest) return;
    wait = pr.phase === 'download' ? `${t('maps_load_loading')} · ${t('maps_about_mb', Math.max(1, Math.round(pr.bytes / 1e6)))}`
      : pr.phase === 'legs' ? pr.text : t('plan_calculating');
    if (shown) show();
  }, namesText(), rounds);
  clearTimeout(waitTimer);
  if (id !== state.latest) return; // a newer request supersedes this one (or Cancel dropped it)
  setPlanning(false);
  if (r.ok && r.plan?.slow) { askSlowPlan(r.plan.slow); render(); return; }
  if (!r.ok || !r.plan || r.plan.failed) {
    problem('routing', 'plan request failed', r.error ?? r.plan?.detail, 'error'); // plain words for the user, the detail for the diagnostics
    status(t('web_plan_failed'), 'error'); render(); return;
  }
  state.plan = r.plan;
  // routing data not downloaded (offline a moment, server busy): straight line + the grey line; tried again by itself
  if (r.plan.notice === 'NOT_YET') setTimeout(retryMissingData, 20_000);
  if (r.plan.moved) await adoptSnapped(routed, r.plan.points);
  planFor = enc(state.points); // the points this plan belongs to (keepPlanStats)
  planGaps.set(r.plan.gaps); // points the route does not reach or skips (the app's PlanGaps, in PlanResult)
  if (fitNextRoute) {
    fitNextRoute = false;
    const asked = fitAskedAt, plan = state.plan;
    // WholeRoute.SETTLE_MS: a moment for the map; not when the user moved it meanwhile or the plan moved on
    setTimeout(() => { if (lastMapTouch <= asked && state.plan === plan) fitRoute(); }, 500);
  }
  drawPoints(); // leg distances along the new route line (the engine now has this plan)
  engine.call('routingInfo').then((l) => { routingLine = l; }, (e) => problem('routing', 'routing fingerprint line not read', e));
  const s = r.stats!;
  if (DEBUG) status(s.bytesFetched ? t('web_planned_in_data', Math.round(s.routeMs), number(s.bytesFetched / 1e6, 1), Math.round(s.fetchMs))
    : t('web_planned_in', Math.round(s.routeMs)));
  else status('');
  render();
  areas?.wantLine(r.plan.legs.flatMap((l) => l.points.map(([la, lo]) => [la, lo] as LatLon))); // T4: the map areas along the route
}

/** The last plan lacked routing data (download failed): plan the same points once more – back online or after a moment. */
let dataRetryFor = '';
function retryMissingData() {
  if (state.plan?.notice !== 'NOT_YET' || state.planning || state.points.length < 2) return;
  const k = enc(state.points) + '|' + state.activity + state.sac;
  if (dataRetryFor === k) return; // once per plan; an edit or "online" tries again
  dataRetryFor = k;
  changed();
}
addEventListener('online', () => { dataRetryFor = ''; retryMissingData(); });

/**
 * The plan's snapped points (the app's PlanResult / snapToRoute – owner: no off-road guidance): free points moved onto
 * the way the router used replace the clicked ones in place – no undo step, no new plan (the route belongs to them);
 * names and pins stay by index, the undo snapshots get the same values (PlanHistory.replacePoints), a saved plan stays
 * saved (its baseline moves along, the app's setSaved).
 */
async function adoptSnapped(routed: LatLon[], snapped: LatLon[]) {
  if (snapped.length !== routed.length || state.points !== routed) return;
  const wasSaved = state.savedCode != null && (await untitled(sharedPlan())) === (await savedUntitled(state.savedCode));
  // asked again after the engine answered: an edit queued behind this plan (a second quick click) may have set newer
  // points meanwhile – the old snapped list over them would drop that point (robust-smoke, CI 2026-11-03 / 11-05)
  if (state.points !== routed) return;
  state.points = snapped.map(([la, lo]) => [la, lo] as LatLon);
  state.points.forEach((p, i) => known.set(key(p), state.meta[i]));
  if (linkTitle && linkTitle.at === enc(routed)) linkTitle = { ...linkTitle, at: enc(state.points) };
  await engine.call('historySnap', enc(routed), enc(state.points));
  if (wasSaved) state.savedCode = await engine.encode(sharedPlan());
  rememberInAddress();
}

/**
 * "Start a new route here" (Far from your route): only the new point stays – one undo step with the edit that set it,
 * so ↶ brings the old route back (like Clear route, nothing more is said).
 */
async function startFresh(p: LatLon) {
  const m = state.meta[state.points.findIndex((q) => key(q) === key(p))] ?? { pinned: false };
  state.latest++; state.plan = null; setPlanning(false);
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
  state.latest++; state.plan = null; setPlanning(false);
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
    routeProfile.update(null, '');
    notice.hidden = true;
    $('infoline').hidden = true; $('breakdown').hidden = true; $('changedLine').hidden = true;
  } else {
    head.innerHTML = `<span class="big">${escapeHtml(p.texts.time)}</span><span class="dist">${escapeHtml(p.texts.distance)}</span>`;
    sub.innerHTML = `<span>${escapeHtml(p.texts.climb)}</span>` +
      `<span class="badge ${p.level ?? ''}" ${p.because ? `title="${escapeHtml(p.because)}"` : ''}>${escapeHtml(p.difficulty)}</span>`;
    // the app's ElevationProfile: "No elevation data" when ProfileData has none
    routeProfile.update(p, t('plan_no_elevation'));
    // one red line: an unreachable point (the app's PlanGaps, with Show) or a failed leg – PlanPanel's order
    // missing routing data is no error: the app's plain grey line (RoutingDataNotice) instead of the router's text
    const info = $('infoline');
    info.hidden = !p.notice;
    info.innerHTML = p.notice ? `${p.notice === 'LOADING' ? '<span class="spin" aria-hidden="true"></span>' : ''}${escapeHtml(p.noticeText ?? '')}` : '';
    renderBreakdown(p.breakdown);
    // "New route for MTB – check it" (the app's routeChangedNotice): until tapped – then the whole route shows – or the
    // next plan (an edit, another activity)
    const ch = $<HTMLButtonElement>('changedLine');
    ch.hidden = !p.changed || changedSeen === p;
    ch.textContent = p.changed ?? '';
    const red = planGaps.last.text ?? (p.notice ? null : p.error);
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
  const rn = $('routingNote');
  rn.hidden = !state.otherRouting || state.points.length < 2;
  rn.textContent = rn.hidden ? '' : t('web_routing_other_phone');
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
  if (line.hidden) { saveEntry(state.points.length >= 2 ? 'save' : 'empty'); return; }
  // the name is in the code too, but naming it on Save (or a rename) is not a change of the route
  const changed = state.savedCode != null && (await ready) && (await untitled(sharedPlan())) !== (await savedUntitled(state.savedCode));
  // a plain line (owner 2026-10-31): a new name comes with the next Save (its dialog) or My routes → ⋮ → Rename
  line.innerHTML = `${icon('check', 16)}<span class="savedname">${t(changed ? 'web_saved_as_changed' : 'web_saved_as', `<b>${escapeHtml(shownTitle(state.savedTitle, ''))}</b>`)}</span>`;
  saveEntry(changed ? 'save' : 'saved');
}
/**
 * Way types and surfaces – the app's RouteBreakdown (rules shared: routing/BreakdownRules): one line under its title
 * ("Cycleway 92 % · Asphalt 98 %"), a click opens the two thin bars with their legend (label · distance), again closes.
 */
let breakdownOpen = false;
function renderBreakdown(b: PlanResult['breakdown'] | undefined) {
  const el = $('breakdown');
  el.hidden = !b || !b.line;
  if (el.hidden || !b) { el.innerHTML = ''; return; }
  const bar = (title: string, parts: [string, string, string, number][]) => {
    const total = parts.reduce((s, p) => s + p[3], 0) || 1;
    return `<div class="bdtitle muted small">${escapeHtml(title)}</div><div class="bdbar">${parts.map((p) =>
      `<span style="flex:${Math.max(1, p[3])} 1 0;background:${p[2]}" title="${escapeHtml(p[1])}"></span>`).join('')}</div>
      <ul class="bdlegend">${parts.map((p) => `<li><i style="background:${p[2]}"></i>${escapeHtml(p[1])} ${escapeHtml(km(p[3], total))}</li>`).join('')}</ul>`;
  };
  el.innerHTML = `<button class="bdhead" aria-expanded="${breakdownOpen}"><span class="muted small">${escapeHtml(t('plan_way_types_surfaces'))}</span>
    <span class="bdline">${escapeHtml(b.line)}</span>${icon(breakdownOpen ? 'expandLess' : 'expandMore', 20)}</button>` +
    (breakdownOpen ? `<div class="bdbars">${bar(t('plan_way_types'), b.ways)}${bar(t('plan_surfaces'), b.surfaces)}</div>` : '');
  el.querySelector<HTMLButtonElement>('.bdhead')!.onclick = () => { breakdownOpen = !breakdownOpen; renderBreakdown(b); };
}
/** The plan whose "New route for …" line was tapped away. */
let changedSeen: PlanResult | null = null;
/** A legend distance: "1.2 km" / "350 m" in the page's language (the app's Format.distance for these lengths). */
const km = (m: number, _total: number) => (m >= 1000 ? `${number(m / 1000, 1)} km` : `${Math.round(m)} m`);

/** The shown route: empty (fewer than two points), save (new, or changed since it was saved), saved (and unchanged). */
let saveState: 'empty' | 'save' | 'saved' = 'empty';
/**
 * The header's Save (web-sync.md §14.6): not paired 💾 "Save" (in this browser), paired 📱 "Save to phone". Dark (the
 * app green) while anything is to save – the shown route new or changed, or (paired) the folder's unsent changes:
 * renames, deletes, GPX imports, the routes waiting for the phone; light with ✓ "Saved" / "On the phone" when all is
 * saved; greyed when there is nothing at all; amber when the mailbox is full (507, nothing lost). No hint beside it.
 */
function saveEntry(st: 'empty' | 'save' | 'saved') {
  saveState = st;
  const b = $<HTMLButtonElement>('menuSavePlan');
  const s = syncRef?.status;
  const paired = !!s?.paired, waiting = paired && s!.pending > 0;
  const shown = paired && s!.full ? 'full' : st === 'save' || waiting ? 'dark' : st === 'saved' ? 'done' : 'empty';
  const label = shown === 'done' ? t(paired ? 'web_saved_on_phone' : 'web_saved_short') : paired ? t('web_save_to_phone') : t('common_save');
  const tip = shown === 'full' ? s!.message : shown === 'dark' ? (paired ? t('web_save_title_phone', s!.phone ?? '') : t('web_save_title_local'))
    : shown === 'done' ? (paired ? t('web_saved_title_phone', s!.phone ?? '') : t('web_saved_title_here')) : t('web_share_needs_two');
  b.disabled = shown === 'empty';
  b.dataset.state = shown;
  b.classList.toggle('unsaved', shown === 'dark');
  b.title = tip;
  b.setAttribute('aria-label', label + ' – ' + tip);
  b.innerHTML = `${icon(shown === 'done' ? 'check' : paired ? 'phone' : 'save', 20)}<span class="lbl">${escapeHtml(label)}</span>`;
}
// "Speichern" in "Plan speichern?" (✕ Neu planen with unsaved changes): the header's Save; true once the plan is saved
setDiscardSave(async () => {
  if (!(await syncReady)) return false;
  let saved = false;
  try { await saveRoute(sync, plansHost, () => { saved = true; if (sync.status.paired) sync.sendAll(); }); }
  catch (e) { problem('sync', 'route not saved', e, 'error'); status(t('web_save_failed'), 'error'); }
  return saved;
}, () => state.title);
/**
 * A click on Save (the app's Save since 2026-10-31, "wie Word"; web-sync.md §14.6 rule 5 now): the shown route new or
 * changed – or saved, with nothing waiting – is saved at once (plans.ts saveRoute: overwrite the open saved route, else
 * a new one under the proposed name), no field, no question; then, paired, everything waiting goes to the phone. Only
 * waiting changes and the route unchanged: the click sends them at once. A rename = a click on "Saved as …".
 */
async function headerSave() {
  if (!(await syncReady)) return;
  await draftsRestored;
  await renderSaved();
  const paired = sync.status.paired, waiting = paired && sync.status.pending > 0;
  const send = () => { if (sync.status.paired) sync.sendAll(); };
  if (saveState === 'save' || (saveState === 'saved' && !waiting)) {
    // a failed write (quota, storage reset, private mode) is said, never silent (review 2026-11-01, web M11)
    try { await saveRoute(sync, plansHost, send); } catch (e) { problem('sync', 'route not saved', e, 'error'); status(t('web_save_failed'), 'error'); }
    return;
  }
  send();
}

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
engine.call('activities').then((t) => { picker.setRows(JSON.parse(t) as ActivityRow[]); renderChip(); }).catch((e) => { problem('engine', 'activity names not loaded', e, 'error'); status(t('web_activities_failed'), 'error'); });
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
  applyActivityLook();
  if (plan && !same) changed();
}
/**
 * The map look for the activity and T level – the app's map/ActivityLayers (MapController.setOverlay / setSacLimit):
 * the shipped style has the Hike look; bike activities get the bike map, their green / forbidden ways and the cycle
 * band, Hike the band filtered at its T level. Identical to the phone (owner 2026-10-31). A newer choice wins.
 */
let lookAsked = 0;
let lookReady = false; // the style's 'load' came (isStyleLoaded() is false while tiles load)
async function applyActivityLook() {
  if (!lookReady) return; // the load handler applies it
  const ask = ++lookAsked;
  const a = state.activity, sac = state.sac;
  try {
    const ids = map.getStyle().layers.map((l) => l.id).join(',');
    const j = JSON.parse(await engine.call('activityLayers', a, sac, ids)) as
      { visibility: Record<string, boolean>; filters: Record<string, unknown> };
    if (ask !== lookAsked) return;
    for (const [id, on] of Object.entries(j.visibility)) if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', on ? 'visible' : 'none');
    for (const [id, f] of Object.entries(j.filters)) if (map.getLayer(id)) map.setFilter(id, f as maplibregl.FilterSpecification);
    (window as unknown as { __look: string }).__look = `${a}/${sac}`; // for the browser tests
  } catch (e) { problem('map', `map look for ${a} T${sac} not applied`, e); }
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
// north up keeps following; in 3D only the direction turns – the view stays tilted (owner 2026-11-06)
$('compass').onclick = () => map.easeTo(terrain3d.on ? { bearing: 0 } : { bearing: 0, pitch: 0 }, { keepFollow: true });
$('undo').onclick = () => step('undo');
$('redo').onclick = () => step('redo');
$('locate').onclick = () => { locating = true; position.locate(); locateNotice(); };
/** "Show whole route" (owner 2026-10-30): the planned line, every point and a shown GPX track in the free map room
 *  (fitPadding: clear of the sheet in any step, the top bar, the place card and the controls). Not keepFollow – it
 *  ends position following, like every camera move made for the user (CLAUDE.md "Map camera"). */
function fitRoute() {
  const b = routeBounds();
  if (!b) return; // the button needs two; the off-map line's Show also one point
  // fitBounds adds the map's own padding to ours – a padding left by an older easeTo would shrink the room (§13 row 19)
  const p = map.getPadding();
  if (p.top || p.bottom || p.left || p.right) map.setPadding(NO_PADDING);
  map.fitBounds(b, { padding: fitPadding(), maxZoom: 16 });
}
/** The plan's points, its route and a shown GPX track – what fitRoute and 3D frame; null without points. */
function routeBounds(): maplibregl.LngLatBounds | null {
  if (!state.points.length) return null;
  const b = new maplibregl.LngLatBounds();
  state.points.forEach(([la, lo]) => b.extend([lo, la]));
  state.plan?.legs.forEach((l) => l.points.forEach(([la, lo]) => b.extend([lo, la])));
  const track = gpxImport.shown;
  track?.segments.flat().forEach(([la, lo]) => b.extend([lo, la]));
  track?.waypoints.forEach((w) => b.extend([w.lon, w.lat]));
  return b;
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
// Space held anywhere on the page = the eye held (owner 2026-11-01) – not while typing, not on a focused button
// (Space presses it) and not in a dialog; let go (or the tab loses focus) shows the route again
const spaceTarget = (e: KeyboardEvent) => e.key === ' ' && !e.ctrlKey && !e.metaKey && !e.altKey &&
  !(e.target as HTMLElement).closest('input, textarea, select, button, a, [contenteditable], dialog');
window.addEventListener('keydown', (e) => {
  if (!spaceTarget(e) || (eye as HTMLButtonElement).disabled) return;
  e.preventDefault(); peek(true);
});
window.addEventListener('keyup', (e) => { if (e.key === ' ') peek(false); });
window.addEventListener('blur', () => peek(false));
// the hint: the eye's tooltip names the key where there is a keyboard (a mouse – a phone has no Space to hold)
if (matchMedia('(pointer: fine)').matches) eye.title = t('web_eye_hotkey');
window.addEventListener('keydown', (e: KeyboardEvent) => {
  const typing = (e.target as HTMLElement).closest('input, textarea');
  if (e.key === 'Escape') { closeMenu(); closeMenus(); picker.close(); placeCard.close(); }
  // single keys (owner 2026-11-01): / = search, F = whole route, ? = the list – not while typing or in a dialog
  if (!typing && !e.ctrlKey && !e.metaKey && !e.altKey && !document.querySelector('dialog[open]')) {
    if (e.key === '/') { e.preventDefault(); const q = document.getElementById('q') as HTMLInputElement | null; q?.focus(); q?.select(); return; }
    if ((e.key === 'f' || e.key === 'F') && !$<HTMLButtonElement>('fitRoute').disabled) { e.preventDefault(); fitRoute(); return; }
    if (e.key === '?') { e.preventDefault(); keysDialog(); return; }
  }
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
  add: (h, role) => addPlace(h, role, true),
  goHere: (h) => { placeFit = true; void fromMe([Number(h.lat.toFixed(6)), Number(h.lon.toFixed(6))], { pinned: true, name: h.title }); },
  google: (h) => google(h.name ?? '', h.lat, h.lon, h.name ? '' : h.title),
  cardShown: (open) => document.body.classList.toggle('card-open', open),
  placeShown: (lat, lon) => areas?.wantPoint(lat, lon, 'searched'), // T2
}, $('searchbox'), $('placecard'));

/**
 * The place card's actions, as the app's addPlace: a pinned stop named after the place (passed exactly). "Add to
 * route" goes where the card said (WaypointInsertion via AddPreview); a place already planned: "As start" = the point
 * menu's Start here, "As destination" moves it to the end, "Remove from route" deletes it.
 * [fit] (from the place card – a search hit, often far off the shown part): the new route is fitted once it is there,
 * like the first route (the app's WholeRoute.fitAfterPlace, A-019 add-on 2); a tap on the map never moves it.
 */
async function addPlace(h: Hit, role: PlaceRole, fit = false) {
  closeMenu();
  const p: LatLon = [Number(h.lat.toFixed(6)), Number(h.lon.toFixed(6))];
  const pv = await engine.placePreview(state.points, p[0], p[1]);
  placeFit = fit; // the edit's commit asks RouteFit.fitAfterPlace (the plan changed and has a route)
  if (pv.existing != null) {
    if (role === 'START') await applyEdit('startHere', pv.existing);
    else if (role === 'DESTINATION') await applyEdit('move', pv.existing, state.points.length - 1);
    else if (role === 'REMOVE') await deletePoint(pv.existing);
    return;
  }
  const asked = state.points;
  await queueEdit(async () => {
    // the points changed while waiting (an edit before this one): where the place goes, worked out again
    const now = state.points === asked ? pv : await engine.placePreview(state.points, p[0], p[1]);
    if (now.existing != null) return null; // planned meanwhile
    const at = role === 'START' ? 0 : role === 'DESTINATION' ? state.points.length : now.index ?? state.points.length;
    const pts = state.points.slice(), meta = state.meta.slice();
    pts.splice(at, 0, p); meta.splice(at, 0, { pinned: true, name: h.title });
    return { planned: commit(pts, meta) };
  });
}

// ---- menus, sheet, dialogs ----
function closeMenus() {
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
document.addEventListener('click', (e) => { if (!(e.target as HTMLElement).closest('.menu, .nav')) closeMenus(); });

$('reverse').onclick = () => applyEdit('reverse');
$('roundTrip').onclick = () => applyEdit('roundTrip');
/** ✕ Neu planen in the plan row and the header's New plan (owner 2026-11-06): unsaved changes ask first, then empty. */
async function newPlan() { if (saveState === 'save' && !(await askDiscard())) return; clearRoute(); placeCard.removePin(); }
$('clear').onclick = newPlan;
$('newPlan').onclick = newPlan;
function forgetSaved() { state.savedId = null; state.savedTitle = null; state.savedCode = null; state.savedFolder = null; state.storedCode = null; }
const plansHost = {
  current: async () => (state.points.length >= 2 && (await ready) ? { code: await engine.encode(sharedPlan()), title: state.title } : null),
  linkTitle: () => (linkTitle && linkTitle.at === enc(state.points) ? linkTitle.title : null),
  openId: () => state.savedId,
  openedCode: () => state.storedCode,
  note: (text: string) => status(text),
  engineCall: (fn: string, ...args: (string | number)[]) => engine.call(fn, ...args),
  openFolder: () => (state.savedId && state.savedFolder) || sync.open,
  open: async (p: SavedPlan, folder: string) => {
    if (p.kind === 'track') {
      const before = gpxImport.shown;
      await gpxImport.showGpx(await sync.trackGpx(p), shownTitle(p.title, t('web_route_default_name')));
      const tr = gpxImport.shown; // a new one = this track read fine (an unreadable one leaves the old one shown)
      if (tr && tr !== before) keepStats(trackKey(p.id), { m: tr.distanceM, up: tr.ascentM, down: tr.descentM });
      return;
    }
    const shared = await engine.parse('p=' + p.plan);
    if (!shared) { status(t('web_plan_unreadable'), 'error'); return; }
    state.savedId = p.id; state.savedTitle = p.title; state.savedCode = p.plan; state.savedFolder = folder; state.storedCode = p.plan;
    if (state.points.length && shared.points.map(key).join(';') === state.points.map(key).join(';')) {
      state.title = p.title; state.otherRouting = !!p.otherRouting; render(); rememberInAddress(); keepPlanStats(p.plan); return;
    }
    openedOtherRouting = !!p.otherRouting;
    await loadShared({ ...shared, title: p.title });
    rememberInAddress();
    keepPlanStats(p.plan); // routed by now (loadShared waits for the plan) – My routes shows its numbers from now on
  },
  saved: (p: SavedPlan, folder: string) => {
    state.savedId = p.id; state.savedTitle = p.title; state.savedCode = p.plan; state.title = p.title; state.savedFolder = folder; state.storedCode = p.plan;
    keepPlanStats(p.plan);
    rememberInAddress();
    render(); // the sheet says "Saved as …" – no message (the app's saveRoute)
  },
  suggestName,
  share: async (p: SavedPlan) => {
    // the stored code carries the name it was saved with; a later rename is the name now
    const shared = await engine.parse('p=' + p.plan);
    if (!shared) { status(t('web_plan_unreadable'), 'error'); return; }
    const title = shownTitle(p.title, '') || null;
    shareDialog(location.href.split('#')[0] + '#p=' + await engine.encode({ ...shared, title }), title, appHint);
  },
  exportGpx: async (p: SavedPlan, folder: string) => {
    if (p.kind === 'track') { download(await sync.trackGpx(p), gpxName(shownTitle(p.title, t('web_route_default_name')), p.updatedAt)); return; }
    await plansHost.open(p, folder);
    // the route is planned first (the GPX holds the planned line, as the sheet's ⋯ → Export GPX)
    const end = Date.now() + 60_000;
    while ((state.planning || !state.plan) && Date.now() < end) await new Promise((r) => setTimeout(r, 200));
    await exportGpx();
  },
};
/** A GPX file name as the app's GpxWriter.fileName ("Meran 2000 _ Ifinger Hütte.gpx"); no name → the date. */
function gpxName(title: string, ms: number): string {
  return (title.replace(/[^A-Za-z0-9äöüÄÖÜß _.-]/g, '_').trim().slice(0, 60) || appDate(ms).replace(/[^A-Za-z0-9äöüÄÖÜß _.-]/g, '_')) + '.gpx';
}
function download(text: string, file: string) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([text], { type: 'application/gpx+xml' }));
  a.download = file;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
}
/** The shown plan as GPX (the app's PlanGpx in the engine: the line, the route points as <wpt>, "<name> - plan.gpx"). */
async function exportGpx() {
  const r = JSON.parse(await engine.call('gpx', state.title ?? '', state.activity, state.sac, enc(state.points), namesText(), sharedPlan().pinned.join(',')));
  if (r) download(r.gpx, r.file);
}
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
    problem('routes', 'no name proposed', e);
    status(t('web_no_name'));
    return '';
  }
}
navItem('menuPlans').act = () => myPlansDialog(sync, plansHost);
navItem('menuNewPlan').act = () => { newPlan(); };
/** The header's Phone button (§14.6): outlined "Pair phone"; paired a pill with the phone's name, its dot (green, or
 *  amber when the phone was away for a day – phoneState) and a small second line "synced 2 min. ago" / "last 3 days
 *  ago" (the tooltip says it all where the line has no room). A click opens the Phone window. Follows sync.onChange
 *  live, and the "… ago" moves on every 30 s. */
function syncBadge() {
  const b = $('syncBadge'), s = sync.status, pill = phonePill(s);
  const dot = b.querySelector<HTMLElement>('.syncdot')!;
  dot.hidden = !s.paired;
  dot.dataset.kind = pill.kind;
  b.classList.toggle('paired', s.paired);
  b.dataset.kind = pill.kind;
  b.querySelector('.lbl')!.textContent = pill.name;
  const sub = b.querySelector<HTMLElement>('.sub')!;
  sub.textContent = pill.sub;
  sub.hidden = !pill.sub;
  b.title = s.paired ? [pill.name, pill.sub, s.message].filter(Boolean).join(' · ') : t('web_pair_phone') + ' – ' + t('web_phone_why');
  b.setAttribute('aria-label', b.title);
}
window.setInterval(() => syncBadge(), 30_000);
$('syncBadge').onclick = async () => {
  closeMenus();
  if (!(await syncReady)) return; // the engine did not start – the status line says so
  phoneDialog(sync, location.href);
};
// Save: the header's button (§14.6, sends to the phone when paired) opens Save's name dialog (plans.ts saveRoute); a copy
// is My routes → ⋮ → Save as copy (the app since 2026-10-31)
$('menuSavePlan').onclick = () => { closeMenus(); headerSave(); };
const share = async () => {
  closeMenus();
  if (state.points.length < 2) { status(t('plan_err_set_start_dest')); return; }
  // Export GPX of the shown plan lives here since the sheet has no ⋯ (owner 2026-10-31); it needs the planned line
  shareDialog(location.href.split('#')[0] + '#p=' + await engine.encode(sharedPlan()), state.title, appHint, state.plan ? exportGpx : undefined);
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
  plan: async (points, title, names, pinned) => {
    state.title = title; forgetSaved();
    await commit(points, names ? points.map((_, i) => ({ pinned: !!pinned?.[i], name: names[i] ?? undefined })) : undefined);
  },
  mayReplacePlan: async () => state.points.length === 0 || saveState !== 'save' || await askDiscard(), // the app's unlessUnsaved (save paths P3)
  // A-017 rule 14: an imported track is kept in the open folder and goes to the phone with Save
  imported: (tr) => {
    syncReady.then(async (ok) => {
      if (!ok) return;
      const id = await sync.saveTrack(tr.segments, tr.waypoints, tr.activity, tr.sac, tr.name);
      if (id) keepStats(trackKey(id), { m: tr.distanceM, up: tr.ascentM, down: tr.descentM }); // My routes' numbers
    }).catch((e) => problem('sync', 'imported track not kept', e));
  },
});
navItem('menuImportGpx').act = () => gpxImport.pick();
(window as unknown as { __gpx: typeof gpxImport }).__gpx = gpxImport; // for the browser tests
$('credits').onclick = () => credits();
$('changedLine').onclick = () => { changedSeen = state.plan; $('changedLine').hidden = true; fitRoute(); }; // the app's tapRouteChangedLine
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
/** "?" (and Help → Keyboard shortcuts): every key the page knows, one dialog. */
function keysDialog() {
  if (document.querySelector('dialog.keys')) return;
  const d = document.createElement('dialog');
  d.className = 'share keys';
  const mod = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : t('web_key_ctrl');
  const row = (keys: string[], what: string) => `<tr><td>${keys.map((k) => `<kbd>${escapeHtml(k)}</kbd>`).join(' ')}</td><td>${escapeHtml(what)}</td></tr>`;
  d.innerHTML = `<h2>${escapeHtml(t('web_keys_title'))}</h2><table class="keystable">
    ${row([t('web_key_space')], t('web_keys_hide'))}
    ${row(['/'], t('web_keys_search'))}
    ${row(['F'], t('web_fit_route'))}
    ${row([mod + ' Z'], t('plan_undo'))}
    ${row([mod + ' ⇧ Z', mod + ' Y'], t('plan_redo'))}
    ${row(['Esc'], t('web_keys_close'))}
    ${row(['+', '−', '← ↑ → ↓'], t('web_keys_map'))}
    ${row(['?'], t('web_keys_this'))}</table>
    <div class="row"><button class="primary" id="closeKeys">${escapeHtml(t('common_close'))}</button></div>`;
  document.body.append(d);
  d.querySelector<HTMLButtonElement>('#closeKeys')!.onclick = () => d.close();
  d.addEventListener('close', () => d.remove());
  d.showModal();
}

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
    ${matchMedia('(pointer: fine)').matches ? `<p class="small"><button class="textbtn" id="openKeys">${escapeHtml(t('web_keys_title'))}</button> <span class="muted">(<kbd>?</kbd>)</span></p>` : ''}
    <p class="muted small">${escapeHtml(t('settings_about_licence'))} · ${siteLink('web_url_privacy', t('web_link_privacy'))} · ${siteLink('web_url_legal', t('web_link_legal'))}</p>
    <div class="row"><button class="primary" id="closeCredits">${escapeHtml(t('common_close'))}</button></div>`;
  document.body.append(d);
  d.querySelector<HTMLButtonElement>('#closeCredits')!.onclick = () => d.close();
  d.querySelector<HTMLButtonElement>('#openKeys')?.addEventListener('click', () => { d.close(); keysDialog(); });
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

/** One full-page message instead of the planner (no WebGL2, browser too old) – never a blank page. */
function stopPage(title: string, text: string) {
  document.querySelector<HTMLDivElement>('#app')!.innerHTML = `<main class="nowebgl" role="alert"><div>${logo(40)}
    <h1>${escapeHtml(title)}</h1><p>${escapeHtml(text)}</p></div></main>`;
}
function oldBrowserPage() {
  stopPage(t('web_old_browser_title'), t('web_old_browser_text'));
  document.body.dataset.engine = 'unsupported';
  console.warn('[engine] this browser cannot run the routing engine (WebAssembly GC missing)');
}

/**
 * Can this browser run the routing engine? A tiny module with one GC struct type (as wasm-feature-detect's "gc"):
 * WebAssembly.validate is false without WasmGC. Wasm exceptions etc. are caught later by the engine's CompileError.
 */
function hasWasmGc(): boolean {
  try {
    return typeof WebAssembly === 'object' && WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 120, 0]));
  } catch { return false; } // expected: no WebAssembly at all → too old
}

/** Can this browser draw the map? maplibre-gl needs WebGL2. */
function hasWebGl2(): boolean {
  try { return !!document.createElement('canvas').getContext('webgl2'); } catch { return false; } // expected: blocked → no
}
