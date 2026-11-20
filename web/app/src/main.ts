// OnPath web planner (docs/design/web-planner.md §6–§13). The page mirrors the phone's Plan screen in a web layout: a
// header line (logo, My routes, Import GPX, …; on the right Save and the Phone button), the search pill with the map toolbar beside it (eye,
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
import { FlyOver } from './flyover';
import { STEEP_COLOURS } from './profile';
import { PACKED, usePacked, packedGlyphs } from './packed';
import { Cells } from './cells';
import { PIN_CHIP_HALF_W, setupSearch, type PinRows, type PlaceCard, type PlaceRole } from './search';
import { HOME_ROW_SHOWN } from './pinmenu';
import { emphasised } from './pinplace';
import { NO_PADDING, roomFor } from './cardanchor';
import { icon, activityIcon, needle, logo, type IconName } from './icons';
import { ActivityPicker, type ActivityRow, type PaceChoices } from './activity';
import { RouteThumbs } from './routethumbs';
import { holdMenu, setRow, type MenuItem } from './menus';
import { keptStore, searchRecent, type KeptPlaces, type KeptRules } from './keptplaces';
import { Position } from './position';
import { PlanSync, shownTitle, type SavedPlan } from './sync';
import { myPlansDialog, joinDialog, saveRoute, saveRouteAs, phoneDialog, pairDialog, phonePill, appDate, datedName } from './plans';
import { keepPlan, afterSaved, renameRecentIf, startUp as zuletztStartUp, type RecentPlan } from './recent';
import { fitsFirstRoute, settleMs, slice, slicesReady } from './slices';
import { MapContentPanel } from './mapcontent';
import { sortable } from './sortable';
import { longRouteOk, allowLongRoute, notRoutedTexts, setNotRouted, type Before } from './longroute';
import { post, dismiss, onShown } from './msgcard';
import { OneWait } from './onewait';
import { setupGpxImport } from './gpximport';
import { overviewStyle, followCover, followPackages } from './overview';
import { PlanGapsLayer } from './plangaps';
import { MapNotices } from './mapnotice';
import { LoadOverlay, watchTiles, ENGINE_UNITS, LINK_UNITS, type PlanIn } from './loadoverlay';
import { BesideSign } from './besidesign';
import { loadWaitEngine, waitEngineReady, loadMs as waitLoadMs } from './waitengine';
import { MapAreas, type MapTexts } from './areas';
import { t, tp, lang, languages, switchLanguage, number, distance, locale } from './i18n';
import { appHint } from './apphint';
import { setupSheetSteps } from './sheetstep';
import { installDiag, addDiagSection, problem } from './diag';
import { watchVersion, BUILD } from './version';
import { keepStats, planKey, trackKey, statsText } from './planstats';
import { keepShape } from './routeshape';
import { RouteReveal } from './routereveal';
import { poiFeatureAt } from './poipin';
import { installSuggestSwitch, setupSuggest, SUGGEST_LAYERS, suggestOn, type SuggestUi } from './suggest';
import { MultiDay, endSymbol, type DayPlan } from './multiday';
import { tourText, type Tour } from './tourtext';
/** A track's segments as [lat, lon] lists (Meine Touren's line). */
const trackSegs = (segs: [number, number, number | null][][]) => segs.map((g) => g.map(([la, lo]) => [la, lo] as [number, number]));
import { plannerRouteZooms } from './routezooms';
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

/** [kind]: the POI a pinned point was picked from (tap / search hit); not stored – the planner draws the plain point (owner 2026-11-20). */
type Meta = { pinned: boolean; name?: string; kind?: string };
type LatLon = [number, number];
const state = {
  points: [] as LatLon[],
  /** Per point: pinned to a POI / place (passed exactly) and its name – kept from a received plan. */
  meta: [] as Meta[],
  title: null as string | null,
  /** The saved plan (My plans) the page shows, so "Save" writes a new version of it. */
  savedId: null as string | null,
  /** The plan was opened from Meine Touren → Zuletzt (recent.ts) and still belongs to that entry. */
  recentId: null as string | null,
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
  /** A multi-day tour (multiday.ts, docs/design/multi-day-tours.md): days, limits, night marks by point index; null = a normal plan. */
  tour: null as Tour | null,
};
/** The multi-day tour UI (multiday.ts) – set up once the sheet and the map exist; render() asks it. */
let multiDay: MultiDay | null = null;
/**
 * The tour beside the engine's undo / redo history (which keeps only the points): one snapshot per historyRecord, so
 * every tour action is one undo step like a point edit (multi-day tours §2: „each is one undo step“).
 */
const tourUndo: (Tour | null)[] = [], tourRedo: (Tour | null)[] = [];
/** The tour the next commit sets (a rule's result, a loaded plan, Neu); undefined = the marks follow the edit (R6). */
let nextTour: Tour | null | undefined;
/** Names and pins by point value, also for points only undo / redo can bring back (the app: PlanHistory.allPoints). */
const known = new Map<string, Meta>();
/** A pairing link (#pair=…) this page was opened with – taken out of the address at once (it holds the secret). */
const pairLink = /[#&]pair=/.test(location.hash) ? location.href : null;
if (pairLink) history.replaceState(null, '', location.href.split('#')[0]);

/**
 * The header (owner 2026-11-06, A – "everything I do with a plan belongs together"): left after the brand ONE plan bar
 * like a file menu – [+ Neu] [📂 Öffnen] [💾 Speichern ▾] [📱 Aufs Handy ▾]; right only Help, the app link and the
 * language. Phone width: the bar is one "Plan ▾" drop-down (the same buttons as rows, the ▾ entries spelled out, Help and
 * the app link at its end). `.wonly` = only in the wide bar, `.ponly` = only in the phone drop-down.
 */
const hb = (id: string, ic: IconName, label: string, cls = '', extra = '') =>
  `<button id="${id}" class="pb ${cls}" role="menuitem" title="${escapeHtml(label)}" ${extra}>${icon(ic, 20)}<span class="lbl">${escapeHtml(label)}</span></button>`;
const more = (id: string, menu: string, label: string) =>
  `<button id="${id}" class="pb splitmore wonly" aria-haspopup="menu" aria-expanded="false" aria-controls="${menu}" title="${escapeHtml(label)}" aria-label="${escapeHtml(label)}">${icon('dropDown', 20)}</button>`;
/** The website's pages for this language (web/strings: the app, privacy, legal notice) – calm links, new tab. */
const siteLink = (key: string, label: string, cls = '') =>
  `<a class="${cls}" href="${t(key)}" target="_blank" rel="noopener">${escapeHtml(label)}</a>`;

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
    <button id="menuBtn" class="pb planmenubtn" aria-haspopup="menu" aria-expanded="false" aria-controls="mainMenu">${icon('route', 20)}<span class="lbl">${escapeHtml(t('web_plan_menu'))}</span>${icon('dropDown', 20)}</button>
    <button id="phoneMini" class="iconbtn phonemini" aria-haspopup="dialog" hidden>${icon('phone', 22)}<i class="syncdot" aria-hidden="true"></i></button>
    <nav id="mainMenu" class="planbar" aria-label="${escapeHtml(t('web_plan_menu'))}">
      ${hb('newPlan', 'add', t('web_plan_new'), '', 'disabled')}
      ${hb('menuPlans', 'folder', t('routes_title'))}
      ${hb('menuMultiDay', 'moon', t('days_plan_tour'), 'multidaybtn')}
      ${hb('menuPlanAsTour', 'moon', t('days_plan_as_tour'), 'ponly', 'disabled')}
      ${hb('menuImportGpx', 'fileOpen', t('routes_import_gpx'), 'ponly')}
      <span class="split">${hb('menuSavePlan', 'save', t('common_save'), 'savebtn', 'data-state="empty" disabled')}${more('saveMore', 'saveMenu', t('web_save_more'))}</span>
      ${hb('menuSaveAs', 'edit', t('web_save_as'), 'ponly', 'disabled')}
      ${hb('menuExportGpx', 'download', t('common_export_gpx'), 'ponly', 'disabled')}
      <span class="split">${hb('syncBadge', 'phone', t('web_to_phone'), 'phonebtn', 'aria-haspopup="dialog"').replace('<span class="lbl">' + escapeHtml(t('web_to_phone')) + '</span>',
        `<span class="lines"><span class="l1"><span class="lbl">${escapeHtml(t('web_to_phone'))}</span><i class="syncdot" aria-hidden="true" hidden></i></span><span class="sub" hidden></span></span>`)}${more('phoneMore', 'phoneMenu', t('web_phone_title'))}</span>
      ${hb('menuPhone', 'sync', t('web_phone_menu'), 'ponly')}
      <span class="sep ponly" aria-hidden="true"></span>
      ${hb('menuAboutP', 'help', t('web_nav_help'), 'ponly')}
      <a class="pb navlink ponly" role="menuitem" href="${t('web_url_try')}" target="_blank" rel="noopener">${icon('download', 20)}<span class="lbl">${escapeHtml(t('web_link_app'))}</span></a>
    </nav>
    <div id="saveMenu" class="menu headmenu" role="menu" hidden>
      <button id="saveAsItem" role="menuitem">${icon('edit', 20)}<span>${escapeHtml(t('web_save_as'))}</span></button>
      <button id="exportGpxItem" role="menuitem">${icon('download', 20)}<span>${escapeHtml(t('common_export_gpx'))}</span></button>
      <button id="planAsTourItem" role="menuitem">${icon('moon', 20)}<span>${escapeHtml(t('days_plan_as_tour'))}</span></button>
    </div>
    <span class="hint muted">${escapeHtml(t('web_header_hint'))}</span>
    <span id="headRight" class="headright">
      <button id="menuAbout" class="pb quietbtn" title="${escapeHtml(t('web_nav_help'))}">${icon('help', 20)}<span class="lbl">${escapeHtml(t('web_nav_help'))}</span></button>
      <a class="pb quietbtn navlink" href="${t('web_url_try')}" target="_blank" rel="noopener" title="${escapeHtml(t('web_link_app'))}" aria-label="${escapeHtml(t('web_link_app'))}">${icon('download', 20)}</a>
      <span id="langSwitch" class="langswitch" role="group" aria-label="${escapeHtml(t('web_lang_switch'))}">${languages.map((l) =>
        `<button data-lang="${l}" lang="${l}" aria-pressed="${l === lang}" title="${escapeHtml(t('web_lang_name_' + l))}">${l.toUpperCase()}</button>`).join('')}</span>
    </span>
  </header>
  <div id="stage">
  <div id="map"></div>
  <div id="topbar" class="topbar">
  <div id="searchbox"></div>
  <span class="barsep" aria-hidden="true"></span>
  <div id="toolbar" class="toolbar" role="toolbar" aria-label="${escapeHtml(t('web_map_tools'))}">
    <button id="eye" class="tool" aria-label="${escapeHtml(t('plan_hold_hide_route'))}" title="${escapeHtml(t('plan_hold_hide_route'))}" disabled>${icon('eye')}</button>
    <button id="compass" class="tool"></button>
    <button id="locate" class="tool"></button>
    <button id="fitRoute" class="tool" aria-label="${escapeHtml(t('web_fit_route'))}" disabled>${icon('fitScreen')}</button>
    <button id="terrain3d" class="tool three" aria-pressed="false" disabled>${escapeHtml(t('web_terrain_3d'))}</button>
    <span class="sep"></span>
    <button id="mapContent" class="tool wide" aria-haspopup="dialog" title="${escapeHtml(t('web_map_content_title'))}">${icon('layers')}<span>${escapeHtml(t('web_map_content'))}</span>${icon('dropDown', 18)}</button>
  </div>
  <!-- undo / redo in their own pill under the bar's right end (the app's UndoPill, owner 2026-11-08: ↶ ↷ in the tool row read
       as „turn the map“) – two round buttons, shown only while something can be undone or redone, the other one greyed -->
  <div id="undoPill" class="undopill" role="group" hidden>
    <button id="undo" class="tool" aria-label="${escapeHtml(t('plan_undo'))}" title="${escapeHtml(t('web_undo_title'))}" disabled>${icon('undo')}</button>
    <button id="redo" class="tool" aria-label="${escapeHtml(t('plan_redo'))}" title="${escapeHtml(t('web_redo_title'))}" disabled>${icon('redo')}</button>
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
  <div id="footer"><button id="credits" class="credit" aria-label="${escapeHtml(t('plan_map_credits'))}">© OpenStreetMap · OpenMapTiles</button>
    <span class="sitelinks">${siteLink('web_url_privacy', t('web_link_privacy'))}<span aria-hidden="true">·</span>${siteLink('web_url_legal', t('web_link_legal'))}</span></div>
  <section id="sheet" aria-label="${escapeHtml(t('web_plan_sheet'))}">
    <button id="handle" aria-label="${escapeHtml(t('plan_resize_panel'))}" title="${escapeHtml(t('plan_resize_panel'))}" aria-controls="sheet"><span></span></button>
    <!-- the facts first of all (owner 2026-11-07: „Routenlänge und Höhen ↑/↓ als oberstes – das ist immer das Wichtigste“):
         length · ↑ · ↓ big, then the time, max and the difficulty -->
    <div id="summary"><div class="headline"></div><div class="subline"></div></div>
    <div class="sheethead">
      <button id="activityChip" class="chip" aria-haspopup="menu" aria-expanded="false"></button>
      <button id="share" class="sharebtn" aria-label="${escapeHtml(t('web_share_needs_two'))}" title="${escapeHtml(t('web_share_needs_two'))}" disabled>${icon('share', 18)}<span>${escapeHtml(t('web_share'))}</span></button>
      <button id="sheetStep" class="iconbtn" aria-controls="sheet"></button>
    </div>
    <!-- the app's PlanActions (owner 2026-10-31, no ⋯): icon + word side by side; Save is the header's, GPX is in Share -->
    <div id="planActions" class="planactions" role="group" hidden>
      <!-- no ✕ Neu planen here (owner 2026-11-06): the plan bar's + Neu is the one way – a deliberate difference to the app -->
      <button id="reverse" class="textbtn">${icon('swap', 18)}${escapeHtml(t('plan_reverse'))}</button>
      <button id="roundTrip" class="textbtn">${icon('loop', 18)}${escapeHtml(t('plan_round_trip'))}</button>
    </div>
    <div id="savedLine" class="savedline muted small" hidden></div>
    <div id="routingNote" class="muted small" hidden></div>
    <div id="notice" class="notice" hidden></div>
    <div id="offmap" class="offmap" hidden></div>
    <button id="warning" class="notice warnline" aria-expanded="false" hidden></button>
    <ul id="spots" class="spots" hidden></ul>
    <!-- the points after facts, plan row and lines (owner 2026-11-07, the phone's order; Google Maps' list): handles, + Ziel hinzufügen -->
    <div id="pointsSection" hidden>
      <!-- the phone's full sheet hides the map's undo pill: the same ↶ ↷ at the „Punkte“ title (the app's UndoPill inSheet,
           owner 2026-11-18 answer 4) – shown with the title only while the map's pill steps back (body.undo-crowded) -->
      <div class="pointshead"><b>${escapeHtml(t('plan_points'))}</b><div id="sheetUndoPill" class="sheetundo" role="group" hidden>
        <button id="sheetUndo" class="tool" aria-label="${escapeHtml(t('plan_undo'))}" title="${escapeHtml(t('web_undo_title'))}" disabled>${icon('undo')}</button>
        <button id="sheetRedo" class="tool" aria-label="${escapeHtml(t('plan_redo'))}" title="${escapeHtml(t('web_redo_title'))}" disabled>${icon('redo')}</button></div></div>
      <ol id="points"></ol>
      <button id="addDest" class="adddest">${icon('add', 18)}<span>${escapeHtml(t('web_add_destination'))}</span></button>
    </div>
    <div id="profile"></div>
    <div id="infoline" class="infoline" hidden></div>
    <button id="changedLine" class="infoline changedline" hidden></button>
    <div id="breakdown" class="breakdown" hidden></div>
    <div id="status" class="status muted" role="status"></div>
    <div id="apphint"></div>
  </section>
  </div>`;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const phoneWidth = () => matchMedia('(max-width: 700px)').matches;
// one wait at a time (onewait.ts, owner 2026-11-13): every wait says whether it wants to show, one shows (body[data-wait])
const waits = new OneWait(document.body);
onShown((m) => waits.set('box', m?.id === 'routing'));
/** The sync once it exists (the header's Save is drawn before it). */
let syncRef: PlanSync | null = null;
/**
 * The shown route: empty (fewer than two points), save (new, or changed since it was saved), saved (and unchanged).
 * Declared before the first saveEntry call below: the dev server (unbundled ES module) keeps `let` in its temporal dead
 * zone, so a later declaration crashed `npm run dev` ("Cannot access 'saveState' before initialization", donald 2026-11-13).
 */
let saveState: 'empty' | 'save' | 'saved' = 'empty';
saveEntry('empty'); // the header's Save: greyed until a route exists (renderSaved keeps it current)
// the plan sheet's three steps (sheetstep.ts – the app's PlanSize): handle, chevron, a click on the summary; remembered
/** A shared link's first route is loading on a phone: the peek shows the link's name, the wait is the load overlay. */
let linkOpening = false;
const sheetSteps = setupSheetSteps($('sheet'), $('handle'), $<HTMLButtonElement>('sheetStep'), $('summary'), () => {}, () => state.points.length);

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
installSuggestSwitch(DEBUG); // ?debug → „Tourvorschläge: an / aus“: the off-switch of the round-trip suggestions (suggest.ts); off = nothing
watchVersion(); // a newer planner deployed while this tab is open: one box "New version of the planner" [Reload] (version.ts)
addDiagSection(() => `build: ${BUILD}`);
const baseStyle = await loadStyle();
/** The style's own sources and layers – what Meine Touren's map pictures draw (routethumbs.ts), never the page's additions. */
const baseIds = { sources: new Set(Object.keys(baseStyle.sources)), layers: new Set(baseStyle.layers.map((l) => l.id)) };
const map = new maplibregl.Map({
  // start view: ?at=lon,lat,zoom, else the build's VITE_START (preview), else Meran
  container: 'map', style: baseStyle, center: [startAt[0], startAt[1]], zoom: startAt[2],
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
const routeProfile = new RouteProfile(map, ROUTE, $('profile'), $('bigProfile'), (open, auto) => {
  document.body.classList.toggle('bigprofile-open', open);
  if (open && !auto) fitRoute(); // opened by hand: the whole route above the big profile
});
// wide screens: the profile lies along the map's bottom edge for good (owner 2026-11-07, A + B's profile); phone: on demand
const pinProfile = () => { routeProfile.pinned = !phoneWidth(); };
pinProfile();
matchMedia('(max-width: 700px)').addEventListener('change', pinProfile);
// the big profile's marked range: length · time · climb from the engine (the plan's own time model, WebPace.rangeStats)
routeProfile.rangeStats = async (a, b) => JSON.parse((await engine.call('rangeStats', enc(state.points), state.activity, state.sac, a, b)) || 'null');
const terrain3d = new Terrain3D(map, $<HTMLButtonElement>('terrain3d'), $('threeDNote'), () => markers.forEach((m) => m.setDraggable(!terrain3d.on)),
  () => { // 3D frames the route like the fit button (clear of the sheet and the big profile)
    const b = routeBounds();
    if (!b) return null;
    const p = map.getPadding();
    if (p.top || p.bottom || p.left || p.right) map.setPadding(NO_PADDING);
    const padding = fitPadding(), cam = map.cameraForBounds(b, { padding, maxZoom: 16 });
    return cam?.zoom != null ? { center: b.getCenter(), zoom: cam.zoom, padding } : null;
  });
new FlyOver(map, terrain3d, routeProfile, $('terrain3d')); // 3D: fly along the route (owner 2026-11-06, D)
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
  const s: StyleSpecification = (useCells ? await overviewStyle(base, pmtiles) : null) ?? await (await fetch(base + 'style/onpath.json')).json();
  plannerRouteZooms(s); // routes further out than on the phone (routezooms.ts, owner 2026-11-20)
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
    // ?slowRoute=ms: the tests' slow router (worker.ts hold – robust-smoke 9)
    const r = await engine.configure(base, params.get('segments') ?? base + 'segments/', PACKED, lang, Number(params.get('slowRoute') ?? 0) || 0);
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
ready.then(async (ok) => {
  if (ok) { await paceReady; openFromLink(); prefetchView(); }
  else overlay.remove('link'); // no engine: the link cannot be opened – its wait ends (the engine's error line says why)
});
// plan sync (docs/design/web-sync.md): My plans in this browser, paired with the phone through the mailbox
const sync = new PlanSync(engine, params.get('syncApi') ?? base + 'sync/v1/');
syncRef = sync;
// Zuletzt at the start: the bin's old entries go, once the drafts beyond the one go to the bin (recent.ts – the app's
// RoutesState.trimDrafts, once the slices' instance is there; failures are reported there)
void zuletztStartUp();
(window as unknown as { __sync: PlanSync }).__sync = sync; // for the browser tests
syncBadge(); // the header entry with its full tooltip at once – sync.init waits for the engine (CI race in sync-smoke)
const syncReady = ready.then(async (ok) => {
  if (!ok) return false;
  await sync.init();
  // the saved routes in the recycle bin (deleted without a phone, owner 2026-11-15): older than 30 days → gone
  try { await sync.binStartUp(); } catch (e) { problem('routes', 'recycle bin: the start-up clean-up failed – tried again at the next start', e); }
  if (pairLink) joinDialog(sync, pairLink);
  syncBadge();
  sync.onChange(syncBadge);
  sync.onChange(() => saveEntry(saveState)); // dark while anything is to save or waits for the phone (§14.6)
  sync.onFolder((_from, to) => { folderSwitch = openDraft(to).catch((e) => problem('sync', 'the open route did not follow the folder switch', e, 'error')); });
  saveEntry(saveState);
  $('syncBadge').dataset.ready = '1'; // the sync runs (tests wait for this)
  return true;
});
addEventListener('hashchange', () => openFromLink());

// ---- waits on the map: position, map loading, map areas on demand (mapnotice.ts, areas.ts – web-planner.md §17) ----
const notices = new MapNotices($('mapnotes'), (busy) => waits.set('strip', busy));
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
  // the map cannot be loaded: the wait for it ends (the window goes – never „loading map“ beside the red line)
  if (s === 'broken') { firstMap = false; overlay.fail('tiles'); }
  if ((now?.title ?? null) === want) return;
  notices.set('cells', want ? { kind: s === 'broken' ? 'error' : 'info', title: want } : null);
}
if (useCells) { cells.onChange = showCellTrouble; map.on('moveend', showCellTrouble); }
(window as unknown as { __areas: MapAreas | null }).__areas = areas; // for the browser tests
// The wait window (form A, loadoverlay.ts – owner 2026-11-13 / 2026-11-19 „ich muss warten“): while the map of a new
// place loads – its tiles, a map area the server prepares for the user's point, a link's or a plan's routing data – and
// while a route is calculated: ONE window over the map, one bar over all steps, at most 3 rows, Cancel. It is then the
// only wait: the strip's other spinners, the sheet's wait line and the engine's status line step back (onewait.ts).
// Background loads (map areas along the route) are the sign beside the map instead (form B, besidesign.ts).
/** A route alone brings the wait window only after this long (a fast route shows nothing – the app's slow route). */
const WAIT_CARD_MS = Number(params.get('waitMs') ?? 1000); // ?waitMs=0: the browser tests see every wait
const beside = new BesideSign($('stage'));
const overlay = new LoadOverlay(400, WAIT_CARD_MS, (on) => {
  document.body.dataset.loadover = on ? '1' : '';
  notices.cover(on);
  waits.set('load', on);
}, () => { const m = mapMiddle(), st = $('stage').getBoundingClientRect(), bar = $('topbar').getBoundingClientRect(), sh = $('sheet').getBoundingClientRect();
  return { ...m, top: bar.bottom, bottom: phoneWidth() ? Math.min(sh.top, st.bottom) : st.bottom }; },
(pct) => { beside.seed(pct); areas?.handOver(); });
// the window is due (its delay runs) while two other waits want to show: it comes at once and carries them
waits.loadDue = () => overlay.due;
waits.loadNow = () => overlay.showNow();
overlay.onDue = () => waits.decide();
// tiles alone bring the window up only for the page's first map (the app's „first map of your area“), once the map
// rests; later they stream in where the user looks and only join a window that is up for a place's map or a route –
// a window over the map's middle for every pan would stand between the user and the map
let firstMap = true;
map.once('idle', () => { firstMap = false; });
watchTiles(map, overlay, () => firstMap && !map.isMoving(), () => useCells && cells.baseTrouble(map) === 'broken');
overlay.job('engine', 0, ENGINE_UNITS, 'engine', false);
ready.then(() => overlay.finish('engine'), () => overlay.remove('engine'));
// the window's own engine (waitengine.ts): a second instance of the Wasm engine on this thread, from the start – the
// page's first map waits for it (the browser's HTTP cache serves the one download to both)
void loadWaitEngine(base, lang);
(window as unknown as { __wait: unknown }).__wait = { loadMs: () => waitLoadMs, ready: waitEngineReady, now: () => overlay.now, overlay: () => overlay, beside: () => beside.shown }; // for the browser tests
// a shared link: its routing data is not known before the engine read the link – a share of the bar until then
if (/[#&?]p=/.test(location.href)) overlay.job('link', 0, LINK_UNITS, 'routing');
if (areas) {
  areas.onWait = (list) => overlay.areas(list);
  areas.onBackground = (b) => beside.set(b);
}
map.on('movestart', (e) => { if ((e as { originalEvent?: unknown }).originalEvent) lastMapTouch = performance.now(); }); // a pan / zoom by the user
// any press on the map counts as touched (the app's lastMapTouchMs): a point being set stops the first route's fit
map.on('mousedown', () => { lastMapTouch = performance.now(); });
map.on('touchstart', () => { lastMapTouch = performance.now(); });
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
/**
 * The found place the last edit added (the app's TakenPin, field 2026-11-07 S10 – a far destination "forgotten"): its pin
 * went into the plan; when Cancel (long-route question or the wait) takes that add back, the pin comes back so the place
 * is never lost. [plan] = the points right after the add – only a Cancel of exactly that plan brings the pin back.
 */
let pinTaken: { hit: Hit; at: LatLon; plan: string | null } | null = null;
/** The next commit opens another plan (a link, a saved or recent plan): it starts with nothing to undo (owner 2026-11-13). */
let freshHistory = false;
async function commit(points: LatLon[], meta?: Meta[]) {
  // the add of a found place (TakenPin): the first commit after it is that add's plan; any later commit drops it
  if (pinTaken) { if (pinTaken.plan == null) pinTaken.plan = enc(points); else pinTaken = null; }
  runCause = nextCause ?? 'edit'; nextCause = null;
  if (runCause === 'edit') state.otherRouting = false; // edited here: the route is the page's own now
  editBefore = runCause === 'edit' ? { points: state.points, names: state.meta.map((m) => m.name) } : null;
  // sent now with the points as they are (the worker keeps the order), not waited for: a long plan still running in the
  // one engine worker must not hold the page (Clear / Delete while planning answers at once, as in the app)
  const fresh = freshHistory; freshHistory = false;
  const recorded = fresh ? engine.call('historyImport', '#') : engine.call('historyRecord', enc(state.points), enc(points));
  // the tour beside the history: a fresh plan starts with none to undo; else the tour as it was is one undo step
  const tourBefore = state.tour, pointsBefore = state.points, metaBefore = state.meta;
  if (fresh) { tourUndo.length = 0; tourRedo.length = 0; } else { tourUndo.push(tourBefore); if (tourUndo.length > 100) tourUndo.shift(); tourRedo.length = 0; }
  const wantTour = nextTour; nextTour = undefined;
  // the plan's first route, or a place from the card (the app's routing/RouteFit.firstRoute / fitAfterPlace, shared):
  // the map shows it whole once it is there, unless the map was touched meanwhile (fitFirstRoute). Asked before the
  // plan: the one worker answers in order, so this is known when the route comes.
  const fromCard = placeFit; placeFit = false;
  // asked when the press that made this edit happened (pressInOrder): a press after it – even one made before this commit,
  // a fast click – is a touch after the ask and keeps the map where the user is working (its hold menu stays open)
  const asked = pressedAt ?? performance.now();
  const fit = (yes: string) => { if (yes === 'true') { fitNextRoute = true; fitAskedAt = asked; } };
  engine.call('routeFirst', state.points.length, points.length).then(fit, (e) => problem('plan', 'route fit rule not asked', e));
  if (fromCard) engine.call('routeFitAfterPlace', enc(state.points), enc(points)).then(fit, (e) => problem('plan', 'route fit rule not asked', e));
  setPoints(points, meta);
  // a multi-day tour: night marks follow their points, a dragged night stays one (R6 – the engine's PlanDays.afterEdit)
  state.tour = wantTour !== undefined ? wantTour : tourBefore ? await followTour(pointsBefore, metaBefore, tourBefore, points) : null;
  await changed();
  await recorded;
}

/** PlanDays.afterEdit for an edit [before] → [after] of a multi-day tour: the tour with its marks moved along. */
async function followTour(before: LatLon[], meta: Meta[], tour: Tour, after: LatLon[]): Promise<Tour | null> {
  if (enc(before) === enc(after)) return tour;
  try {
    const names = meta.map((m, i) => (m.name ? `${i}\t${m.name.replace(/[\t\n]/g, ' ')}` : '')).filter(Boolean).join('\n');
    const r = await engine.call('daysRule', 'afterEdit', enc(before), state.activity, state.sac,
      meta.flatMap((m, i) => (m.pinned ? [i] : [])).join(','), names, tourText(tour), 0, 0, 0, enc(after));
    return r && r !== 'null' ? (JSON.parse(r) as DayPlan).tour ?? tour : tour;
  } catch (e) {
    problem('days', 'night marks not moved along with the edit', e);
    return tour;
  }
}

/** A multi-day rule's plan (multiday.ts) as one undo step: points, pins, names and the tour. */
async function applyDayPlan(p: DayPlan) {
  await queueEdit(async () => {
    const meta = p.points.map((q, i): Meta => ({ ...(known.get(key(q)) ?? { pinned: false }), pinned: p.pinned.includes(i), name: p.names[String(i)] }));
    nextTour = p.tour;
    return { planned: commit(p.points, meta) };
  });
}

function setPoints(points: LatLon[], meta?: Meta[]) {
  state.points = points;
  state.meta = meta ?? points.map((p) => ({ ...(known.get(key(p)) ?? { pinned: false }) }));
  state.points.forEach((p, i) => known.set(key(p), state.meta[i]));
  if (points.length && suggestUi?.active()) suggestUi.close(); // a plan began meanwhile: the round-trip panel gives the sheet back
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
  pinTaken = null; // the user's own Undo / Redo: a later Cancel brings no pin back (cancelLongRoute reads it first)
  return queueEdit(async () => stepTo(op, await engine.call('historyStep', op, enc(state.points))));
}

/**
 * The plan after an Undo / Redo [op] the engine took (PlanEditState.undoStep / redoStep – the user's own, or the one a
 * Cancel asked for): [t] = its points, 'null' = nothing to take back. The tour of that step comes with it.
 */
function stepTo(op: 'undo' | 'redo', t: string): { planned: Promise<void> } | null {
  runCause = op;
  state.otherRouting = false;
  if (t === 'null') return null;
  // the tour of that step (kept beside the history in commit)
  const from = op === 'undo' ? tourUndo : tourRedo, to = op === 'undo' ? tourRedo : tourUndo;
  if (from.length) { to.push(state.tour); state.tour = from.pop()!; }
  setPoints(dec(t));
  return { planned: changed() };
}

/**
 * Deletes plan point [i] – map menu Delete and the Points list ✕: the app's PlanPoints.deleteAt (in the engine), so a
 * round trip stays closed at the next point, as on the phone. Names and pins follow their points by value.
 */
async function deletePoint(i: number) {
  // a hotel's point of a multi-day tour: asked first – its hotel goes with it (multi-day tours §4 scenario N)
  if (multiDay?.nightOf(i) === 'HOTEL' && !(await multiDay.askRemoveHotel())) return;
  await applyEdit('delete', i);
}

// ---- markers ----
const markers: maplibregl.Marker[] = [];
let draggedAt = 0;
let hidden = false; // the eye is held

/** stopLabel of the app (ui/MapOverlays.stopLabel – an Android file, so mirrored: 4 lines). */
function stopLabel(i: number, count: number, name?: string | null) {
  return name?.trim() ? name : i === 0 ? t('plan_point_start') : i === count - 1 && count > 1 ? t('plan_point_destination') : t('plan_point_stop', i + 1);
}
/** A stop's sign: ↺ for a loop's start, a white flag for the goal (the app's GoalFlag, owner 2026-11-06 polish 7), else its number. */
const FLAG = '<svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true"><path fill="#fff" d="M14.4 6 14 4H5v17h2v-7h5.6l.4 2h7V6z"/></svg>';
const stopSign = (i: number, count: number, loop: boolean) => (loop ? '↺' : count > 1 && i === count - 1 ? FLAG : String(i + 1));
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
    const color = state.roundTrip && i === 0 ? COLORS.start : stopColor(i, n), sign = stopSign(i, n, state.roundTrip && i === 0);
    // a night of a multi-day tour: its blue moon / green bed (owner 2026-11-17, round 3 A) instead of the number
    const night = multiDay?.nightOf(i) ?? null;
    // a point on a POI (a station picked as a stop) is the plain numbered point too – the planner's look on a big screen
    // (owner 2026-11-20: „ein normaler Punkt reicht“; the app keeps the POI's symbol in a blue ring, MapController.poiMarker);
    // its name is in the title and the Points list, its details in its menu
    const el = document.createElement('div');
    if (night) { el.className = 'pin night'; el.innerHTML = endSymbol(night, 28); el.dataset.plan = String(i); }
    else {
      el.className = 'pin';
      el.style.background = color;
      el.innerHTML = sign;
    }
    // a night says which day of how many ends there (owner 2026-11-20); multiday.ts sets it again once the days are there
    const nightLabel = night ? multiDay?.nightLabel(i) ?? null : null;
    el.title = t('web_marker_title', nightLabel ?? stopLabel(i, n, state.meta[i]?.name));
    if (nightLabel) el.setAttribute('aria-label', nightLabel);
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
      pressInOrder(m.getLngLat(), i);
    });
    markers.push(m);
  });
  // the row texts come from the app's PlanPoints.roleText / legText (shared, web review M15) – the page only renders them
  $('points').innerHTML = r.rows.map((row) => {
    const color = row.loop ? COLORS.start : stopColor(row.i, n);
    return `<li data-i="${row.i}">
      <button class="grip" title="${escapeHtml(t('web_grip_title'))}" aria-label="${escapeHtml(t('web_grip_move', row.name))}">${icon('drag', 20)}</button>
      <span class="num" style="background:${color}">${stopSign(row.i, n, row.loop)}</span>
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
  sheetSteps.refresh(); // the phone's chevron says „3 Punkte“
  $('planActions').hidden = n === 0; // the app's RouteRules.canClear: something to close
  $<HTMLButtonElement>('newPlan').disabled = n === 0; // New plan: the same rule as ✕ Neu planen
  // Speichern ▾ → Save as / Export GPX: need a route (two points; GPX the planned line)
  for (const id of ['menuSaveAs', 'saveAsItem']) $<HTMLButtonElement>(id).disabled = n < 2;
  for (const id of ['menuExportGpx', 'exportGpxItem']) $<HTMLButtonElement>(id).disabled = n < 2 || !state.plan;
  for (const id of ['menuPlanAsTour', 'planAsTourItem']) $<HTMLButtonElement>(id).disabled = n < 2;
}

// Points list: click = show on the map, ✕ = delete, drag ≡ = new order (PlanEdits.move; sortable.ts)
$('points').addEventListener('click', (e) => {
  const li = (e.target as HTMLElement).closest('li');
  if (!li || (e.target as HTMLElement).closest('.grip')) return;
  const i = Number(li.dataset.i);
  if ((e.target as HTMLElement).closest('.del')) { deletePoint(i); return; }
  const p = state.points[i];
  // phone: the sheet goes to the profile step so list and map are both visible (the app's showPointFromList)
  if (p && phoneWidth()) sheetSteps.set('small');
  if (p) map.easeTo({ center: [p[1], p[0]], zoom: Math.max(map.getZoom(), 14) });
});
sortable($('points'), (from, to) => applyEdit('move', from, to));

// ---- presses on the map: a click is the app's hold (MapGestures.decide, in the engine) ----
let menu: maplibregl.Popup | null = null;
/** Round-trip suggestions (suggest.ts) – on by default, null when the ?debug switch turned them off (set up at the end of this file). */
let suggestUi: SuggestUi | null = null;
let placeCard: PlaceCard = { close: () => {}, isOpen: () => false, removePin: () => {}, planChanged: () => {}, showPin: () => {}, hasPin: () => false }; // the search's place card (set up below)
function closeMenu() { menu?.remove(); menu = null; }
function openMenu(at: maplibregl.LngLat, items: MenuItem[], head: string) {
  closeMenu();
  const m = new maplibregl.Popup({ offset: 18, closeButton: false, closeOnClick: false, maxWidth: '320px', className: 'holdpopup' })
    .setDOMContent(holdMenu(head, items, closeMenu)).setLngLat(at).addTo(map);
  m.on('close', () => { if (menu === m) menu = null; });
  menu = m;
}

map.on('click', (e) => pressInOrder(e.lngLat, -1, e.point));
map.on('contextmenu', (e) => { e.preventDefault(); pressInOrder(e.lngLat, -1, e.point); }); // right-click / long press: the same

/**
 * Presses one after the other: each is decided on the plan and the menu as the press before it left them – its menu open,
 * its point in the plan (not yet routed). A fast double click gives what two slow clicks give: without this, a click
 * right after one on a POI was decided while that POI's menu was still being built (engine calls), so it added a point
 * and the POI's menu opened after it, with the old plan's rows (edit-smoke, 2026-11-17).
 */
let pressChain: Promise<void> = Promise.resolve();
/** When the press being decided now happened: its edit's route fit counts map touches from then (commit). */
let pressedAt: number | null = null;
function pressInOrder(at: maplibregl.LngLat, marker: number, pt?: maplibregl.Point) {
  const when = performance.now();
  pressChain = pressChain.then(async () => {
    pressedAt = when;
    try { await press(at, marker, pt); } finally { pressedAt = null; }
  }).catch((e) => problem('plan', 'a press on the map failed', e, 'error'));
}

// The pointing hand over what a click opens (owner 2026-10-30): a POI (its menu) and the route line (a point goes into
// that leg). Markers are DOM elements with their own cursor. One lookup per frame at most.
let hoverAt: maplibregl.Point | null = null;
map.on('mousemove', (e) => {
  const queued = hoverAt != null;
  hoverAt = e.point;
  if (queued) return;
  requestAnimationFrame(() => {
    const pt = hoverAt!; hoverAt = null;
    // in 3D nothing is clickable (view only), and a hit test there samples the terrain for every feature – the costliest
    // work of a pointer move in 3D (owner 2026-11-06: slow); skip it
    // a POI only through its symbol (poiAt – a bare name is no POI)
    const layers = map.isMoving() || terrain3d.on ? [] : map.getStyle().layers
      .filter((l) => l.id === 'planned-route' || l.id === 'planned-route-casing').map((l) => l.id);
    const hit = !map.isMoving() && !terrain3d.on && (poiAt(pt) != null
      || (layers.length > 0 && map.queryRenderedFeatures([[pt.x - 10, pt.y - 10], [pt.x + 10, pt.y + 10]], { layers }).length > 0));
    // on the route: the profile's cursor follows (terrain view A) – in 3D the pointer only shows, it does not plan
    const onRoute = routeProfile.hover(map.isMoving() ? null : pt);
    map.getCanvas().style.cursor = hit && !terrain3d.on ? 'pointer' : onRoute ? 'crosshair' : '';
  });
});
map.on('mouseout', () => { map.getCanvas().style.cursor = ''; routeProfile.hover(null); });

/**
 * The POI lookup (`poiAt`) reads MapLibre's last symbol placement. Right after the map moved (a pan, the search's own
 * move for the found place's menu) that placement still holds the old screen positions until the next one is done –
 * a few hundred ms, more on a slow GPU: a click on a POI drawn right there found nothing, or the POI that stood there
 * before the move (search-smoke, Gasthaus Egger, 2026-11-16). So a click waits until the placement is the one for this
 * camera (or the map is 'idle'), and then looks at the clicked place's spot as it is now. The app reads its POIs at the
 * camera's idle, too (PoiTap). The wait was 1 s at most: on a slow GPU (SwiftShader under load, frames > 1 s) the click
 * still read the old placement and a POI became a plain map click (search-smoke 1 in 4, 2026-11-17) – now 5 s at most,
 * ended by the first frame whose placement fits the camera.
 */
let mapSettled = false;
map.on('movestart', () => { mapSettled = false; });
map.on('idle', () => { mapSettled = true; });
/** MapLibre's committed symbol placement was made for the camera as it is now; null = not known (another MapLibre). */
function placedHere(): boolean | null {
  type Cam = { center?: { lng: number; lat: number }; zoom?: number; bearing?: number; pitch?: number };
  const t = (map as unknown as { style?: { placement?: { transform?: Cam } } }).style?.placement?.transform;
  if (!t?.center || t.zoom == null) return null;
  const c = map.getCenter(), near = (x: number, y: number) => Math.abs(x - y) < 1e-9;
  return !map.isMoving() && near(t.center.lng, c.lng) && near(t.center.lat, c.lat) && near(t.zoom, map.getZoom())
    && near(t.bearing ?? 0, map.getBearing()) && near(t.pitch ?? 0, map.getPitch());
}
async function settledPoint(at: maplibregl.LngLat): Promise<maplibregl.Point> {
  if (!mapSettled && placedHere() !== true) {
    await new Promise<void>((done) => {
      const fin = () => { clearTimeout(timer); map.off('idle', fin); map.off('render', frame); done(); };
      const frame = () => { if (placedHere() === true) fin(); };
      const timer = setTimeout(fin, 5000);
      map.on('idle', fin);
      map.on('render', frame);
      map.triggerRepaint(); // nothing left to draw: 'idle' comes with the next frame
    });
  }
  return map.project(at);
}

async function press(at: maplibregl.LngLat, marker: number, pt?: maplibregl.Point) {
  const results = document.getElementById('results');
  if (results && !results.hidden) { results.hidden = true; return; } // a click beside the search results only closes them
  if (placeCard.isOpen()) { // … and one beside the found place's menu closes it (nothing added)
    placeCard.close();
    // a click on a POI shows it at once – its menu in place of the closed one, no second click (the app ba904656, owner
    // 2026-11-15 on a hike: a tap on a POI works also with a menu open)
    if (terrain3d.on || !pt) return;
    pt = await settledPoint(at);
    const poi = markerNear(pt) < 0 ? poiAt(pt) : null;
    if (poi) await poiMenu(poi);
    return;
  }
  if (terrain3d.on) { closeMenu(); return; } // 3D is for looking (the note on the map says so); planning is on the flat map
  if (pt) pt = await settledPoint(at);
  if (marker < 0 && pt) marker = markerNear(pt);
  const poi = pt && marker < 0 ? poiAt(pt) : null;
  const action = await engine.call('gesture', menu != null, marker, poi != null, state.points.length === 0);
  // each waits until its menu is open or its point is in the plan (pressInOrder); the route is not waited for
  if (action.startsWith('PointMenu:')) await pointMenu(Number(action.split(':')[1]));
  else if (action === 'PoiMenu' && poi) await poiMenu(poi);
  else if (action === 'FirstPointMenu') await firstPointMenu(at);
  // a multi-day tour: a click on its route asks „Übernachtung hier“ or „Einfügen“ (multi-day tours §3.6)
  else if (action === 'AddPoint' && multiDay?.isTour && pt && onRouteLine(pt)) multiDay.routeMenu(at);
  else if (action === 'AddPoint') { void addPoint(at); await editChain; }
  else if (action === 'CloseMenu') closeMenu();
}

/** The click at [pt] is on the planned route line (the pointing hand's rule, 10 px either side). */
function onRouteLine(pt: maplibregl.Point): boolean {
  const layers = ['planned-route', 'planned-route-casing'].filter((l) => map.getLayer(l));
  return layers.length > 0 && map.queryRenderedFeatures([[pt.x - 10, pt.y - 10], [pt.x + 10, pt.y + 10]], { layers }).length > 0;
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

/**
 * The style layers that draw a POI's symbol – the app's PoiHit.symbolLayer: every `poi-` layer except the text-only
 * `-name` ones. A name drawn without its symbol is no POI (owner 2026-11-15, Hilden: a bus stop's bare name read like a
 * street name and a hold on it opened the place menu; core feature C19).
 */
function poiSymbolLayers(): string[] {
  return map.getStyle().layers.map((l) => l.id).filter((id) => id.startsWith('poi-') && !id.endsWith('-name')); // POI_LAYER_PREFIX
}

/** The click box's half (px) and the app's PoiHit.ICON_HALF_DP: a POI is hit only through its icon, not its name. */
const CLICK_HALF_PX = 10, ICON_HALF_PX = 11;

/**
 * The POI under a click (the app's PoiHit.pick): one the map draws with its symbol whose icon the click's box touches –
 * the name beside an icon is no target of its own; the nearest POI point wins.
 */
function poiAt(pt: maplibregl.Point): MapGeoJSONFeature | null {
  const layers = poiSymbolLayers();
  if (!layers.length) return null;
  const f = map.queryRenderedFeatures([[pt.x - CLICK_HALF_PX, pt.y - CLICK_HALF_PX], [pt.x + CLICK_HALF_PX, pt.y + CLICK_HALF_PX]], { layers });
  const reach = CLICK_HALF_PX + ICON_HALF_PX;
  let best: MapGeoJSONFeature | null = null, bestD = Infinity;
  for (const x of f) {
    if (x.geometry.type !== 'Point') continue;
    const s = map.project((x.geometry as GeoJSON.Point).coordinates as [number, number]);
    if (Math.abs(s.x - pt.x) > reach || Math.abs(s.y - pt.y) > reach) continue;
    const d = (s.x - pt.x) ** 2 + (s.y - pt.y) ** 2;
    if (d < bestD) { bestD = d; best = x; }
  }
  return best;
}

/** A hold's answer from the engine (WebApi.holdOnMap = the app's AppViewModel.holdOnMap). */
type HoldAnswer = { action: 'ADD'; index: number; why: string } | { action: 'ASK'; main: string[]; stop: number; why: string };

/**
 * The engine's answer for a click at [at] with the points as they are now; [lineSnapM] = 24 px on screen in metres. Off
 * the line the engine measures appending and inserting on the real paths for the plan's activity (owner 2026-11-17
 * „Always real paths“, at most ~1.5 s, straight lines on any failure) – in the worker, the page stays free.
 */
async function holdAnswer(at: maplibregl.LngLat, lineSnapM: number, straight = false): Promise<HoldAnswer> {
  // [straight]: no activity → the engine decides on the straight lines (WebApi.holdOnMap's „no activity“)
  return JSON.parse(await engine.call('holdOnMap', enc(state.points), at.lat, at.lng, state.roundTrip, lineSnapM,
    straight ? '' : state.activity, state.sac)) as HoldAnswer;
}

/**
 * [holdAnswer] for the plan shown when it comes back (the app's PlaceAddSlice HoldTry, [umbau]'s finding 2026-11-20):
 * an answer measured for a plan no longer shown (points, activity, level or round trip changed meanwhile) is never applied –
 * the hold is decided again on the plan now shown, measured once more, after a second change on the straight lines; so a
 * click always ends in a point or the menu.
 */
async function holdAnswerNow(at: maplibregl.LngLat, lineSnapM: number): Promise<HoldAnswer> {
  const shown = () => [state.points, state.activity, state.sac, state.roundTrip] as const;
  for (const straight of [false, false, true]) {
    const before = shown();
    const a = await holdAnswer(at, lineSnapM, straight);
    const now = shown();
    if (straight || before.every((v, i) => v === now[i])) return a;
    console.info('[Problems] TRACE plan: Hold beside the plan: measured for a plan no longer shown – decided again on the plan now shown');
  }
  throw new Error('not reached');
}

/** 24 px on screen at [lat] in metres – the app's LINE_SNAP_DP („on the line“). */
function lineSnapAt(lat: number): number {
  return 24 * 40075016 * Math.cos((lat * Math.PI) / 180) / (512 * 2 ** map.getZoom());
}

/**
 * The turning ring on a held spot while the engine measures its paths (the app's RoutingRing for vm.holdMeasuring,
 * MapOverlays); returns its remover. Shown only after the app's RoutingSpot.DELAY_MS – a quick measure shows nothing.
 */
const MEASURE_RING_DELAY_MS = 400;
function measuringRing(at: maplibregl.LngLat): () => void {
  let marker: maplibregl.Marker | null = null;
  const timer = window.setTimeout(() => {
    // the turning ring inside a still box: MapLibre places a marker by its element's own transform
    const box = document.createElement('div'), ring = document.createElement('div');
    box.style.pointerEvents = 'none';
    ring.className = 'measurering';
    box.appendChild(ring);
    marker = new maplibregl.Marker({ element: box }).setLngLat(at).addTo(map);
  }, MEASURE_RING_DELAY_MS);
  return () => { clearTimeout(timer); marker?.remove(); };
}

/**
 * A click (the app's hold) on the empty map or the line – the app's holdOnMap (F-PLAN-15, owner 2026-11-17 21:40: „die
 * echte Routendifferenz … die kürzere gewinnt; nur fast gleich → fragen“): fewer than 2 points or on the line → where
 * WaypointInsertion puts it; else appending and inserting measured on the real paths (a turning ring meanwhile): the
 * clearly shorter at once (the new goal / a stop), within 20 % of each other the small menu „Einfügen“ / „Neues Ziel“
 * beside a ring at the spot (holdAddMenu). The next click waits for this one's answer (pressInOrder).
 */
function addPoint(at: maplibregl.LngLat) {
  // ~24 px on screen = "clicked on the line" (the phone uses 24 dp)
  const snap = lineSnapAt(at.lat);
  return queueEdit(async () => {
    const done = state.points.length >= 2 && !state.roundTrip ? measuringRing(at) : () => {};
    let a: HoldAnswer;
    try { a = await holdAnswerNow(at, snap); } finally { done(); }
    if (a.action === 'ASK') { holdAddMenu(at, a, snap); return null; }
    const pts = state.points.slice(), meta = state.meta.slice();
    const i = Math.max(0, Math.min(a.index, pts.length));
    pts.splice(i, 0, [at.lat, at.lng]); meta.splice(i, 0, { pinned: false });
    return { planned: commit(pts, meta) };
  });
}

/** The ring at a held spot while its hold menu is open (the app's HOLD_RING_DP, px). */
const HOLD_RING_PX = 12;

/**
 * The hold menu at a held spot (the app's HoldAddOverlay + HoldAddMenu, owner 2026-11-15): a small ring marks the spot,
 * beside it only the two rows „Einfügen“ / „Neues Ziel“, the cheaper first and tinted, no title – never over the spot
 * (MapGestures.placeBeside, in the engine). A click elsewhere / Escape adds nothing; the menu closes when the spot leaves
 * the map.
 */
function holdAddMenu(at: maplibregl.LngLat, a: { main: string[]; stop: number }, lineSnapM: number) {
  const p: LatLon = [at.lat, at.lng];
  const strong = emphasised(a.main);
  const items = a.main.map((item): MenuItem => item === 'ADD_STOP'
    // „Einfügen“ / „Neues Ziel“: the app's holdAsStop / holdAsNewDestination on the points as they are then (PlaceAddState)
    ? { icon: 'addLocation', label: t('plan_add_as_stop'), key: item, strong: item === strong, act: () => {
      void queueEdit(() => placeEdit('holdStop', p, lineSnapM, { pinned: false }));
    } }
    : { icon: 'flag', label: t('plan_make_destination'), key: item, strong: item === strong, act: () => {
      void queueEdit(() => placeEdit('holdGoal', p, lineSnapM, { pinned: false }));
    } });
  closeMenu();
  const box = holdMenu('', items, closeMenu);
  box.classList.add('holdadd');
  const m = new maplibregl.Popup({ anchor: 'top-left', offset: [0, 0], closeButton: false, closeOnClick: false, maxWidth: '320px',
    className: 'holdpopup holdaddpopup' }).setDOMContent(box).setLngLat(at).addTo(map);
  const ring = document.createElement('div');
  ring.className = 'holdring';
  ring.style.width = ring.style.height = `${2 * HOLD_RING_PX}px`;
  const marker = new maplibregl.Marker({ element: ring }).setLngLat(at).addTo(map);
  const place = async () => {
    const s = map.project(at), c = map.getCanvas();
    const w = c.clientWidth, h = c.clientHeight;
    if (s.x < 0 || s.y < 0 || s.x > w || s.y > h) { if (menu === m) closeMenu(); return; } // MapGestures.anchorVisible
    const el = m.getElement().querySelector<HTMLElement>('.maplibregl-popup-content');
    if (!el) return;
    const r = el.getBoundingClientRect();
    const room = menuRoom(s.x);
    const ctl = document.querySelector('.maplibregl-ctrl-bottom-right .maplibregl-ctrl-group')?.getBoundingClientRect();
    const column = ctl && ctl.width > 0 ? Math.round(ctl.width + 16 + 8) : 0; // the zoom buttons at the right edge + their margin
    const margin = 12, gap = HOLD_RING_PX + 10;
    const xy = (await engine.call('placeBeside', s.x, s.y, Math.round(r.width), Math.round(r.height), margin, Math.round(room.top),
      w - margin, Math.floor(room.bottom), gap, column) as string).split(',').map(Number);
    if (menu === m) m.setOffset([xy[0] - s.x, xy[1] - s.y]);
  };
  let placing = false; // one placement at a time (a move sends many frames)
  const onMove = () => { if (placing) return; placing = true; void place().finally(() => { placing = false; }); };
  map.on('move', onMove);
  m.on('close', () => { map.off('move', onMove); marker.remove(); if (menu === m) menu = null; });
  menu = m;
  void place();
}

/**
 * The free map between the top bar and the plan sheet (phone) or the big profile / the map's bottom (wide), above the
 * footer (scale + credits) where it lies under [x] (map pixels; on a wide screen it stands in the corner beside the
 * sheet): the found place's pin and its name chip (search.ts) and the hold menu never go under it (the app's
 * PinMenuOverlay / HoldAddOverlay bottomPx = the bottom panel with its footer).
 */
function menuRoom(x: number): { top: number; bottom: number } {
  const st = $('stage').getBoundingClientRect(), bar = $('topbar').getBoundingClientRect(), bp = $('bigProfile');
  const top = bar.height > 0 ? bar.bottom - st.top : 0;
  const under = phoneWidth() ? $('sheet').getBoundingClientRect().top : bp.hidden ? st.bottom : bp.getBoundingClientRect().top;
  const foot = $('footer').getBoundingClientRect(), px = st.left + x;
  const footTop = foot.height > 0 && px >= foot.left - PIN_CHIP_HALF_W && px <= foot.right + PIN_CHIP_HALF_W ? foot.top - 4 : st.bottom;
  return { top, bottom: Math.max(top, Math.min(st.bottom, under, footTop) - st.top) };
}

/**
 * An add from a menu or the place card, decided by the app's PlaceAddState in the engine (WebApi.placeEdit) on the points as
 * they are now: [op] = holdStop / holdGoal (the hold menu), poiStop / poiGoal (a held place's rows), SMART / START /
 * DESTINATION (the place card). The plan it hands back is committed (one Undo step); the new point [at] gets [m], every
 * other point keeps its name and pin. Call inside queueEdit. Null = nothing changed (the place is a point already).
 */
async function placeEdit(op: string, at: LatLon, lineSnapM: number, m: Meta, title = '', kind = '') {
  const r = JSON.parse(await engine.call('placeEdit', op, enc(state.points), at[0], at[1], lineSnapM, title, kind)) as
    { points?: string | null; pointMenu?: number };
  if (r.pointMenu != null || !r.points) {
    // no commit: what the add's click set up for its commit (the pin's TakenPin, the fit after a place) is dropped
    placeFit = false;
    if (pinTaken?.plan == null) pinTaken = null;
    // the place is a stop already (the app's addPoiWith → openPointMenu „POI that is a stop“): its point menu
    if (r.pointMenu != null) { const i = r.pointMenu; setTimeout(() => { void pointMenu(i); }, 0); }
    return null;
  }
  const was = new Map(state.points.map((q, i) => [key(q), state.meta[i]] as const));
  const after = dec(r.points).map((q): LatLon => (key(q) === key(at) && !was.has(key(at)) ? at : q));
  const meta = after.map((q): Meta => was.get(key(q)) ?? (key(q) === key(at) ? m : { ...(known.get(key(q)) ?? { pinned: false }) }));
  return { planned: commit(after, meta) };
}

const position = new Position(map, () => { renderFabs(); locateNotice(); void updateLocateLabel(); });
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
  const items: MenuItem[] = [
    { icon: 'tripOrigin', label: t('plan_start_here'), act: () => commit([p], [{ pinned: false }]) },
    await goHereItem(() => fromMe(p, { pinned: false })),
  ];
  // „Rundtour von hier“ only for the very first point (owner 2026-11-18): never once the plan has a point
  openMenu(at, suggestUi && !state.points.length ? suggestUi.withRow(items, 0, p) : items, `<b>${escapeHtml(t('plan_new_route'))}</b>`);
}

/**
 * Point menu: number + name, then the app's entries for this point (MapGestures.pointMenu, in the engine). A point that
 * sits on a POI (the start, a stop or the goal) also shows the POI's details under its name – kind · elevation, as its
 * own menu would (the app ba904656, owner 2026-11-15 on a hike: a tap on a POI shows its details also when it is a route
 * point; the planner's click is the app's hold, so one click gives both).
 */
async function pointMenu(i: number) {
  // a night of a multi-day tour: its own menu (owner 2026-11-17 – Hotel hinterlegen / ändern / entfernen, Übernachtung entfernen)
  const nm = multiDay?.nightMenu(i);
  if (nm) { const [la, lo] = state.points[i]; openMenu(new maplibregl.LngLat(lo, la), nm.items, nm.head); return; }
  const n = state.points.length, name = state.meta[i]?.name;
  const [lat, lon] = state.points[i];
  const acts: string[] = JSON.parse(await engine.call('pointMenu', i, n, !!name?.trim(), state.roundTrip, enc(state.points)));
  const poi = state.meta[i]?.pinned ? poiFeatureAt(map, lat, lon) : null;
  const pr = poi?.properties ?? {};
  const info: { title: string; sub: string; color: string } | null = poi
    ? JSON.parse(await engine.call('poiMenu', String(pr.kind ?? ''), String(pr.name ?? ''), pr.ele != null ? String(pr.ele) : '', lat, lon)) : null;
  const items: MenuItem[] = acts.map((a): MenuItem => {
    if (a === 'MAKE_START') return { icon: 'tripOrigin', label: t('plan_start_here'), sub: tp('plan_removes_points', i, i), act: () => applyEdit('startHere', i) };
    if (a === 'LOOP_START') return { icon: 'tripOrigin', label: t('plan_start_finish_here'), sub: t('plan_same_round_trip'), act: () => applyEdit('startHere', i) };
    if (a === 'GOOGLE') return { icon: 'map', label: t('plan_open_google_maps'), act: () => google(name ?? '', lat, lon, '') };
    return { icon: 'delete', label: t('common_delete'), danger: true, act: () => deletePoint(i) };
  });
  // a multi-day tour: „Übernachtung hier“ first (owner 2026-11-18) – this point becomes a night (blue moon)
  const night = multiDay?.pointNightItem(i);
  if (night) items.unshift(night);
  const color = state.roundTrip && i === 0 ? COLORS.start : stopColor(i, n);
  const title = `<b>${escapeHtml(stopLabel(i, n, name))}</b>`;
  openMenu(new maplibregl.LngLat(lon, lat), items,
    `<span class="num" style="background:${color}">${stopSign(i, n, state.roundTrip && i === 0)}</span>` + (info
      ? `<span class="txt">${title}<span class="small poidetails" style="color:${info.color}">${escapeHtml(info.sub)}</span></span>` : title));
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
  const hit = { title: info.title, name: pr.name ? String(pr.name) : null, kind: String(pr.kind ?? ''), lat, lon, code: info.code } as Hit;
  const r = await placeRows(hit, info, false);
  const at = new maplibregl.LngLat(lon, lat);
  openMenu(at, [...r.main, ...r.keep, r.google], r.head);
}

/**
 * The rows of a held place's menu (the app's PoiMenu + PinMenuItems): its head, the add entries (the app's PlaceAddMenu,
 * owner 2026-11-07 / 2026-11-15 / 2026-11-17: one line each, nothing behind „⋯ Mehr“; while planning only the matching row
 * in a clear case – „Einfügen“ on the line or when inserting is clearly shorter on the paths, „Neues Ziel“ when appending
 * is – and both, the cheaper first and tinted, only when near equal: the rows a hold on the place gives, the app's
 * PlaceMenuMeasure) and Google. [pin] = the found place's pin (search.ts, owner 2026-11-13): an add is a place from the
 * search – the new route is fitted (fitAfterPlace) and a Cancel of the long-route question brings the pin back (TakenPin).
 */
async function placeRows(hit: Hit, info: { title: string; sub: string; color: string; google?: string }, pin: boolean):
  Promise<PinRows> {
  const { lat, lon } = hit;
  const pm = JSON.parse(await engine.call('placeMenu', enc(state.points), lat, lon, state.roundTrip, lineSnapAt(lat),
    state.activity, state.sac)) as { main: string[]; stop: number; keepOld?: boolean };
  const p: LatLon = [Number(lat.toFixed(6)), Number(lon.toFixed(6))];
  /** Before an add from the pin: TakenPin + fit the new route (the old place card's host.add). */
  const fromPin = () => { if (pin) { pinTaken = { hit, at: p, plan: null }; placeFit = true; } };
  const strong = emphasised(pm.main);
  const row = async (item: string): Promise<MenuItem> =>
    ({ ...(await row0(item)), key: item === 'SET_DESTINATION' ? 'ADD' : item, strong: item === strong });
  const row0 = async (item: string): Promise<MenuItem> => {
    if (item === 'START_HERE') return { icon: 'tripOrigin', label: t('plan_start_here'), act: () => { fromPin(); void addPlace(hit, 'START', pin); } };
    if (item === 'GO_HERE') return goHereItem(() => { fromPin(); void fromMe(p, { pinned: true, name: info.title, kind: hit.kind }); });
    if (item === 'ADD_STOP') return { icon: 'addLocation', label: t('plan_add_as_stop'), act: () => { fromPin(); void placeAddAs(hit, 'poiStop'); } };
    if (item === 'NEW_DESTINATION') return { icon: 'flag', label: t('plan_make_destination'), act: () => { fromPin(); void placeAddAs(hit, 'poiGoal'); } };
    return { icon: 'addLocation', label: (await engine.placePreview(state.points, lat, lon)).label ?? t('search_card_set_destination'),
      act: () => { fromPin(); void addPlace(hit, 'SMART', pin); } };
  };
  const head = `<span class="txt"><b>${escapeHtml(info.title)}</b><span class="small" style="color:${info.color}">${escapeHtml(info.sub)}</span></span>`;
  const googleRow: MenuItem = { icon: 'map', label: t('plan_open_google_maps'),
    act: () => { if (info.google) window.open(info.google, '_blank', 'noopener'); else google(hit.name ?? '', lat, lon, hit.name ? '' : hit.title); } };
  const main = await Promise.all(pm.main.map(row));
  // a multi-day tour: a found place can be the hotel of a night (scenario F) – the nearest day end is offered
  if (multiDay?.isTour && pm.main.length) main.push({ icon: 'bed', label: t('days_hotel_for_tour'), key: 'HOTEL', act: () => { void multiDay?.hotelForTour(hit); } });
  // „Rundtour von hier“ only while the plan is empty (owner 2026-11-18: only when setting the very first point)
  return { head, main: suggestUi && !state.points.length ? suggestUi.withRow(main, pm.main.indexOf('START_HERE'), p, info.title) : main, keep: await keepRows(hit.code), google: googleRow };
}

// ---- kept places (owner 2026-11-15, „Orte merken“ – the app's b2504c3d, F-PLAN-14; keptplaces.ts) ----
const lines = (k: KeptPlaces) => [k.home ?? '', k.favourites.join('\n')] as const;
const keptRules: KeptRules = {
  apply: async (k, op, code) => JSON.parse(await engine.call('keptApply', ...lines(k), op, code)) as KeptPlaces,
  state: async (k, code) => JSON.parse(await engine.call('keptState', ...lines(k), code)) as { favourite: boolean; home: boolean },
  addRecent: async (recent, code) => (await engine.call('recentAdd', recent.join('\n'), code)).split('\n').filter((l) => l),
};
const kept = keptStore(keptRules);
sync.keptPlaces(kept); // synced with the paired phone (web-sync.md §5.4); not paired: this browser only
const recentSearch = searchRecent(keptRules);
/** The app's OnPathColors.Favourite (My routes' star). */
const FAVOURITE = '#B07D00';

/**
 * A held place's kept-place rows (the app's PoiMenu FAVOURITE / HOME): after the route rows and a thin line, „☆ Merken“
 * (set: the gold „★ Gemerkt“, a click removes it) and „⌂ Als Zuhause festlegen“ (on the home place „Zuhause entfernen“ –
 * both off while HOME_ROW_SHOWN is, pinmenu.ts);
 * the menu stays open and shows the new state. None for a place without its line (nothing to keep).
 */
async function keepRows(code: string | undefined): Promise<MenuItem[]> {
  if (!code) return [];
  let st = { favourite: false, home: false };
  try { st = await kept.state(code); } catch (e) { problem('search', 'Kept places: state not read – shown as not kept', e); }
  const fav = (on: boolean) => (on ? { icon: 'star' as const, label: t('place_favourite_on'), iconColor: FAVOURITE }
    : { icon: 'starOutline' as const, label: t('place_favourite_add') });
  const home = (on: boolean) => ({ icon: 'home' as const, label: t(on ? 'place_home_remove' : 'place_home_set') });
  const rows: MenuItem[] = [
    { ...fav(st.favourite), key: 'FAVOURITE', divider: true, stay: true, act: (b) => {
      st.favourite = !st.favourite; setRow(b, fav(st.favourite));
      void (st.favourite ? kept.keep(code) : kept.unkeep(code));
    } },
    { ...home(st.home), key: 'HOME', stay: true, act: (b) => {
      st.home = !st.home; setRow(b, home(st.home));
      void (st.home ? kept.setHome(code) : kept.clearHome());
    } },
  ];
  // the app's shownMenuRows: no „Als Zuhause festlegen“ while HOME_ROW_SHOWN is off (owner 2026-11-19)
  return HOME_ROW_SHOWN ? rows : rows.filter((r) => r.key !== 'HOME');
}

/**
 * Place menu → „Einfügen“ (poiStop: the app's addPoiAsStop – the best place on the route, never behind the goal) or „Neues
 * Ziel“ (poiGoal: poiAsNewDestination – while planning the old goal stays as the last stop, owner 2026-11-14), pinned with
 * its name. One Undo brings the plan before back.
 */
async function placeAddAs(h: Hit, op: 'poiStop' | 'poiGoal') {
  const p: LatLon = [Number(h.lat.toFixed(6)), Number(h.lon.toFixed(6))];
  await queueEdit(() => placeEdit(op, p, 30, { pinned: true, name: h.title, kind: h.kind }, h.title ?? '', h.kind ?? ''));
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
  try { await openLink(); } catch (e) {
    problem('link', 'opening the plan link failed', e, 'error');
    status(t('msg_plan_link_unreadable'), 'error');
    overlay.fail('link'); // nothing came: no full bar beside the red line
  } finally { overlay.finish('link'); } // whatever happened to the link, its wait is over (also when it threw)
}
async function openLink() {
  const first = !linkChecked;
  linkChecked = true;
  if (!/[#&?]p=/.test(location.href)) { overlay.remove('link'); if (!draftsOn) restoreDraft(); return; }
  const shared = await engine.parse(location.href);
  if (!shared) { overlay.remove('link'); status(t('msg_plan_link_unreadable'), 'error'); return; }
  if (shared.points.length < 2) overlay.remove('link'); // nothing to route: no routing data to wait for
  if (shared.points.map(key).join(';') === state.points.map(key).join(';') && shared.activity === state.activity && shared.sac === state.sac) { overlay.remove('link'); return; } // our own address update
  if (state.points.length) await keepAsRecent('plan link opened');
  forgetSaved();
  // a shared plan opened in the browser: the one calm line about the app, in the sheet (never over the route – it is
  // there before the camera fits the plan beside the sheet)
  if (shared.points.length >= 2) appHint($('apphint'));
  // a phone (owner 2026-11-13): the person who got the link sees the route on the map first – the sheet peeks (facts
  // only, not remembered), the wait is the load overlay over the map, the route is fitted above the peek once it is there
  if (shared.points.length >= 2 && phoneWidth() && !(first && reloaded())) { linkOpening = true; sheetSteps.set('small', false); }
  await loadShared(shared);
  overlay.finish('link'); // its plan ran (or was asked first / stopped): the link's share of the bar is done
  // the link's title is the name Save proposes while its points are unchanged (the app's linkTitle)
  linkTitle = shared.title ? { title: shared.title, at: enc(state.points) } : null;
  if (!draftsOn) await restoreDraft(); // the page opened with its route in the address: which saved route it is
}
/** The page was reloaded (its own plan in the address): the remembered step stays. */
function reloaded() {
  try { return (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined)?.type === 'reload'; } catch { return false; } // expected: old browser – treat as a link
}
/** The link's first route is there (or failed / was replaced): the sheet's head shows the plan as usual again. */
function linkOpened() { linkOpening = false; }
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
  engine.dropPlans(); // another plan replaces the shown one – a long plan still routing must not hold it
  state.title = shared.title;
  state.otherRouting = openedOtherRouting; openedOtherRouting = false;
  setActivity(shared.activity, shared.sac, false);
  if (shared.points.length) {
    const b = new maplibregl.LngLatBounds();
    shared.points.forEach(([la, lo]) => b.extend([lo, la]));
    map.fitBounds(b, { padding: fitPadding(), maxZoom: 15, duration: 0 });
  }
  nextCause = 'other';
  freshHistory = true; // opening a plan is no edit: no undo back to the plan before it
  nextTour = shared.tour ?? null; // a multi-day tour comes with its days and nights
  await commit(shared.points, shared.points.map((_, i) => ({ pinned: shared.pinned.includes(i), name: shared.names[String(i)] })));
}

/** Room for the top bar, the sheet (side panel or bottom sheet) and the map controls when the camera fits a plan. */
/** A route point marker's radius of air beside every bar or button that borders the map (the app's FitPaddingRule.AIR_DP). */
const FIT_AIR = 16;
/** The map's top edge under the search box / tool bar: a stop's sign near the top is never cut (app 9e72c868). */
function topClear(fallback: number): number {
  const bar = $('topbar')?.getBoundingClientRect(), stage = $('stage').getBoundingClientRect();
  return bar && bar.height > 0 ? Math.max(fallback, bar.bottom - stage.top + FIT_AIR) : fallback;
}

function fitPadding(): maplibregl.PaddingOptions {
  const sheet = $('sheet').getBoundingClientRect();
  // the big elevation profile below the map (terrain view A): the route stays above it
  const bp = $('bigProfile'), bpH = bp.hidden ? 0 : $('stage').getBoundingClientRect().bottom - bp.getBoundingClientRect().top;
  // the sheet as it is now (its step, or the track panel); the place card on a phone stands in for it
  if (phoneWidth()) {
    const card = document.body.classList.contains('card-open') ? $('placecard').getBoundingClientRect().height : 0;
    // never more than the screen allows: at least 120 px of map stay, or fitBounds refuses to move at all
    const mapH = $('stage').getBoundingClientRect().height;
    const top = topClear(130);
    return { top, bottom: Math.min(Math.max(sheet.height, card, bpH) + 30, Math.max(0, mapH - top - 120)), left: 40, right: 40 };
  }
  // wide: the room beside the side panel, or – when the panel is short (small step) – the larger room below it
  const stage = $('stage').getBoundingClientRect(), w = stage.width, h = stage.height;
  const beside = { top: topClear(90), bottom: Math.max(50, bpH + 30), left: sheet.right - stage.left + 50, right: 90 };
  const below = { top: sheet.bottom - stage.top + 40, bottom: Math.max(50, bpH + 30), left: 40, right: 90 };
  const room = (p: typeof beside) => Math.max(0, w - p.left - p.right) * Math.max(0, h - p.top - p.bottom);
  return room(below) > room(beside) ? below : beside;
}

/** The current plan in the app's format (for the QR code, the link and the address bar). */
function sharedPlan(): SharedPlan {
  const names: Record<string, string> = {};
  state.meta.forEach((m, i) => { if (m.name) names[String(i)] = m.name; });
  return { points: state.points, activity: state.activity, sac: state.sac,
    pinned: state.meta.flatMap((m, i) => (m.pinned ? [i] : [])), names, title: state.title, tour: state.tour };
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
/** The open route's swap to another folder ([openDraft]) under way – a Save waits for it (it changes which plan is open). */
let folderSwitch: Promise<void> = Promise.resolve();
/** Another folder opened (pairing, unpairing, the phone ended it): its own open route comes back, or an empty map. */
async function openDraft(folder: string) {
  draftsOn = false;
  const d = await sync.draft(folder);
  const shared = d?.code ? await engine.parse('p=' + d.code) : null;
  forgetSaved();
  // which saved route it is goes in before the plan loads (as plansHost.open does): loading routes it, which takes
  // seconds, and a Save meanwhile – the pairing's OK saves the open plan – saw "never saved" and asked for a name
  // again (sync-smoke scenario 1, CI 2026-11-13)
  if (shared && d) { state.savedId = d.savedId; state.savedTitle = d.savedTitle; state.savedCode = d.savedCode; state.savedFolder = d.savedFolder ?? null; state.storedCode = d.storedCode ?? null; }
  if (shared) await loadShared(shared);
  else if (state.points.length) { state.title = null; await commit([], []); }
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
/** Planning or not – also for the page's tests (body[data-planning]); off takes the wait box away. */
function setPlanning(on: boolean, routed = false) {
  state.planning = on;
  document.body.dataset.planning = on ? '1' : '';
  if (!on) { linkOpened(); overlay.finish('route'); overlay.plan(null, undefined, routed); } // [routed]: only a route that came ends the wait at 100 %
  sheetWait();
}
/** The sheet's wait line („Calculating the route…“) wants to show while a plan runs (onewait.ts decides whether it does). */
function sheetWait() {
  waits.set('sheet', state.planning && !!document.querySelector('#summary .subline.waitline'));
}
function mapMiddle() {
  const st = $('stage').getBoundingClientRect(), sh = $('sheet').getBoundingClientRect(), bar = $('topbar').getBoundingClientRect();
  if (phoneWidth()) return { x: st.left + st.width / 2, y: (bar.bottom + Math.min(sh.top, st.bottom)) / 2 };
  const left = Math.max(st.left, sh.right);
  return { x: (left + st.right) / 2, y: (bar.bottom + st.bottom) / 2 };
}
/**
 * Cancel of the wait (the app's cancelRouting → cancelRun(NotRouted.STOPPED)): the running plan is dropped (a long one
 * stops for real – client.ts dropPlans starts a fresh worker), then the app's PlanStatus.onCancel: undo the edit, put
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
  keepStats(planKey(code), { m: p.distanceM, up: p.ascentM, down: p.descentM, s: p.timeS, lv: p.level });
  keepShape(planKey(code), p.legs.map((l) => l.points.map(([la, lo]) => [la, lo] as [number, number])));
}
/** The plan before the point edit that is being planned now (the long-route question's "Far from your route"). */
let editBefore: Before | null = null;
/**
 * The newest [changed] run: an older run still waiting (its points drawn, the history, the long-route rule) stops when
 * a newer one started – it would plan the points as they are now, which may be fewer than two (a folder switch or Clear
 * while it waited: the engine's Planner throws „Failed requirement.“, sync-smoke 2026-11-17) or are planned by the newer
 * run anyway.
 */
let changeRun = 0;
async function changed() {
  const run = ++changeRun, stale = () => run !== changeRun;
  // a plan still running for the points before is dropped: the one worker must not hold the next calls behind it
  // (a long plan routes for minutes – client.ts dropPlans stops it, the app's cancelRun)
  engine.dropPlans();
  const before = editBefore; editBefore = null; dismiss('slowplan'); // a new edit answers the time-limit question
  placeCard.planChanged(); // the pin card's "+ Zur Route" / "Hierhin" follows the plan
  await drawPoints();
  if (stale()) return;
  const few = state.points.length < 2;
  // fewer than two points (Clear route, a point deleted): no route – shown at once; a plan still running in the engine
  // was dropped above (state.latest ignores its answer, as the app cancels it).
  if (few) { state.latest++; state.plan = null; setPlanning(false); planGaps.clear(); status(''); }
  // Share (icon next to ⋯): always there (owner 2026-10-30), greyed until a start and a destination are set
  const sh = $<HTMLButtonElement>('share'), two = state.points.length >= 2, shw = two ? t('web_share') : t('web_share_needs_two');
  sh.disabled = !two; sh.title = shw; sh.setAttribute('aria-label', shw);
  rememberInAddress();
  state.notRouted = false;
  if (few) render();
  const h = JSON.parse(await engine.call('historyState'));
  if (stale()) return;
  state.canUndo = h.canUndo; state.canRedo = h.canRedo;
  if (few) { render(); return; }
  planGaps.clear(); // the last plan's gaps belong to other points
  // the app's long-route question first (routing/LongRoute): nothing is routed or downloaded until it is answered
  const answer = await longRouteOk(engine, state.points, state.activity, before, !!state.tour);
  if (stale()) return;
  if (answer === 'cancel') { await cancelLongRoute(); cancelLongRouteCamera(); return; }
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
  post({ id: 'slowplan', kind: 'frage', tone: 'edit', title: t('plan_slow_title'), detail: t('plan_slow_text'), at: mapMiddle, buttons: [
    { label: t('common_cancel'), act: () => { dismiss('slowplan'); problem('routing', `slow plan: cancel after ${rounds} period(s)`); setNotRouted(t('plan_not_routed_stopped'), t('plan_calculate')); cancelLongRoute(); } },
    { label: t('plan_slow_wait'), act: () => { dismiss('slowplan'); problem('routing', `slow plan: keep waiting after ${rounds} period(s)`); planRun(rounds + 1); } },
  ] });
}

/** One plan run of the current points with [rounds] time-limit periods (1, more after "Weiter warten"). */
async function planRun(rounds: number) {
  dismiss('slowplan');
  // a plan needs two points (the engine's Planner requires them): fewer is no route, never a request
  if (state.points.length < 2) { problem('plan', `plan run for ${state.points.length} point(s) – nothing to plan`); return; }
  const id = ++state.latest;
  setPlanning(true);
  render();
  newPointsAsked();
  // the wait (owner 2026-11-02: "man guckt auf den Bildschirm und nichts passiert"; 2026-11-19 „Route berechnet ist auch
  // A“): the wait window (loadoverlay.ts) – after about a second for a route alone, at once with the map of a new place
  // (its routing data here): the routing data's bytes, then its legs and „prüfen“ as steps of ONE bar, and Cancel (the
  // app's cancelRouting); a fast route shows nothing (no flash)
  let run: PlanIn = { done: 0, total: 0, checking: false, join: null, names: namesText(), points: state.points.length, known: false };
  overlay.plan(run, () => { cancelRouting(); });
  const routed = state.points;
  const r = await engine.plan(routed, state.activity, state.sac, sharedPlan().pinned, (pr) => {
    if (id !== state.latest) return;
    run = { ...run, known: true };
    if (pr.phase === 'download') {
      // the routing data: bytes received of the bytes needed (sizes from the files' index – worker.ts ensureParts)
      overlay.remove('link');
      overlay.job('route', pr.done, pr.bytes, 'routing');
      run = { ...run, join: 0 };
    } else {
      overlay.finish('route');
      // 'route': the data is written into the engine's files (the join step) until the router reports its first leg
      if (pr.phase === 'route') run = { ...run, join: run.join != null ? 0.5 : null };
      if (pr.phase === 'legs') run = { ...run, join: null, done: pr.done ?? 0, total: pr.total ?? 0, checking: (pr.total ?? 0) > 0 && (pr.done ?? 0) >= (pr.total ?? 0) };
    }
    if (id === state.latest && state.planning) overlay.plan(run);
  }, namesText(), rounds);
  if (id !== state.latest) return; // a newer request supersedes this one (or Cancel dropped it)
  setPlanning(false, r.ok && !!r.plan && !r.plan.failed && !r.plan.slow);
  if (r.ok && r.plan?.slow) { askSlowPlan(r.plan.slow); render(); return; }
  if (!r.ok || !r.plan || r.plan.failed) {
    // with what was asked (points, activity, rounds): the next failure tells which plan it was
    problem('routing', 'plan request failed', `${r.error ?? r.plan?.detail} – ${routed.length} points ${enc(routed)}, ${state.activity} T${state.sac}, rounds ${rounds}`, 'error'); // plain words for the user, the detail for the diagnostics
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
    // the app's WholeRoute.SETTLE_MS (slices.ts; 500 until its instance is there): a moment for the map; not when the
    // user moved it meanwhile or the plan moved on (MapViewSlice.fitFirstRoute)
    setTimeout(() => { if (fitsFirstRoute(lastMapTouch > asked, state.plan === plan)) fitRoute(); else console.info(`[map] route fit left out: ${lastMapTouch > asked ? 'the map was touched' : 'another plan'}`); }, settleMs());
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
  const wasSaved = await samePointsAsSaved();
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
  state.tour = null; // one point left: no tour
  setPoints([p], [m]);
  await changed();
}

/** Clear route (⋯ and the Points head; the app's RouteRules.canClear: from one point): no question – ↶ brings it back. */
function clearRoute() { closeMenus(); state.title = null; forgetSaved(); nextTour = null; commit([], []); }

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
/**
 * Cancel of "This route is very long" / of the wait: the app's PlanEditState.cancelRun(STOPPED) in the engine (WebPlanChain)
 * decides for the run's cause – the change undone / redone (the engine's history took that step), the activity put back,
 * or the points kept.
 */
async function cancelLongRoute() {
  engine.dropPlans(); // the running plan stops for real (client.ts) – planCancel below must not wait behind it
  overlay.finish('link'); // a link's plan stopped: nothing more to wait for
  state.latest++; // from now on the stopped run's reports are ignored (planRun) – not only after the answer below
  const before = runCause === 'activity' ? activityBefore : null;
  const answer = await engine.call('planCancel', runCause, enc(state.points), before?.a ?? '', before?.sac ?? 0) as string;
  const { what, points } = JSON.parse(answer) as { what: string; points: string | null };
  state.latest++; state.plan = null; setPlanning(false);
  const taken = pinTaken?.plan === enc(state.points) ? pinTaken : null; pinTaken = null;
  const pinBack = () => { // TakenPin.back: the place is no point of the plan any more and no other pin is shown
    if (!taken || placeCard.hasPin()) return;
    if (state.points.some(([la, lo]) => Math.abs(la - taken.at[0]) < 1e-6 && Math.abs(lo - taken.at[1]) < 1e-6)) return;
    placeCard.showPin(taken.hit);
  };
  if (what === 'UNDO' || what === 'REDO') {
    const op = what === 'UNDO' ? 'undo' : 'redo';
    await queueEdit(async () => stepTo(op, points ?? 'null'));
    pinBack(); return;
  }
  if (what === 'RESTORE_ACTIVITY' && activityBefore) return setActivity(activityBefore.a, activityBefore.sac, true);
  state.notRouted = true;
  render();
}

const routeReveal = new RouteReveal((legs) => (map.getSource('planned-route') as GeoJSONSource | undefined)?.setData(legs.length ? {
  type: 'FeatureCollection',
  features: legs.map((l) => ({ type: 'Feature', properties: { straight: l.straight }, geometry: { type: 'LineString', coordinates: l.pts } })),
} : empty()));
/** The sheet's texts follow the app's PlanStatus.noRouteLine ("Hold" → "Click"; the web does not record). */
function render() {
  const p = state.points.length >= 2 ? state.plan : null;
  // a new route draws itself in 0.5 s (routereveal.ts, the app's RouteReveal); the same route again is drawn as it is
  routeReveal.draw(p, p ? p.legs.map((l) => ({ straight: l.straight, pts: l.points.map(([la, lo]) => [lo, la] as [number, number]) })) : []);
  const head = document.querySelector<HTMLDivElement>('#summary .headline')!;
  const sub = document.querySelector<HTMLDivElement>('#summary .subline')!;
  const notice = $('notice');
  sub.classList.toggle('muted', !p);
  sub.classList.remove('waitline');
  sheetWait();
  if (!p) {
    const n = state.points.length;
    const waiting = linkOpening && n >= 2 && !state.notRouted;
    head.innerHTML = `<span class="title">${escapeHtml(waiting && state.title ? state.title : t('plan_plan_a_route'))}</span>`;
    sub.textContent = n === 0 ? t('web_hint_first_point')
      : n === 1 ? t('web_hint_destination') : state.notRouted ? notRoutedTexts.line : t('plan_calculating');
    // the sheet's wait line steps back while another wait shows (onewait.ts, body[data-wait], style.css)
    sub.classList.toggle('waitline', n >= 2 && !state.notRouted);
    sheetWait();
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
    // the app's Format texts: "12.5 km", "↑ 842 m  ↓ 310 m  ·  max 954 m", "4 h 36 min" – length and ↑ ↓ big and first
    const [updown, ...rest] = p.texts.climb.split('·');
    head.innerHTML = `<span class="big dist">${escapeHtml(p.texts.distance)}</span><span class="big updown">${escapeHtml(updown.trim())}</span>`;
    sub.innerHTML = `<span class="time">${escapeHtml(p.texts.time)}</span>` +
      (rest.length ? `<span class="muted">${escapeHtml(rest.join('·').trim())}</span>` : '') +
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
  multiDay?.render(); // a multi-day tour: the guide, the overview, the day ends on the map
}

/**
 * The app's RouteRules.isUnsaved: only the points count (their lat/lon) – a changed activity, T level, point name, pin
 * or title is no change (laptop 2026-11-15, as the app). Compared as codes of the saved plan with the one or the other
 * point list, so both go through the same rounding.
 */
const pointsOnly = (p: SharedPlan, points: SharedPlan['points']) => engine.encode({ ...p, points, title: null, pinned: [], names: {} });
/**
 * A saved plan's parsed code and its points-only code, kept per code (the same text always gives the same): the plan on
 * the map is compared with its own saved route at every edit, and with every saved route of the folder when it leaves
 * the map – one parse each, once. Bounded, the oldest first out.
 */
const savedPointsMemo = new Map<string, { plan: Promise<SharedPlan | null>; key: Promise<string | null> }>();
function savedPoints(code: string) {
  let m = savedPointsMemo.get(code);
  if (!m) {
    if (savedPointsMemo.size >= 600) savedPointsMemo.delete(savedPointsMemo.keys().next().value as string);
    const plan = engine.parse('p=' + code);
    m = { plan, key: plan.then((p) => (p ? pointsOnly(p, p.points) : null)) };
    savedPointsMemo.set(code, m);
  }
  return m;
}
/** [points] are exactly the saved plan [code]'s points (RouteRules.isUnsaved false against it) – lat/lon only. */
async function samePointsAs(code: string, points: SharedPlan['points']): Promise<boolean> {
  const m = savedPoints(code), saved = await m.plan;
  if (!saved || saved.points.length !== points.length) return false; // another number of points: another plan, no encode
  return (await pointsOnly(saved, points)) === (await m.key);
}
/** The plan on the map has the saved plan's points (RouteRules.isUnsaved false); false when nothing was saved. */
async function samePointsAsSaved(): Promise<boolean> {
  return state.savedCode != null && samePointsAs(state.savedCode, state.points);
}

/** "Saved as …" under the summary while the plan is a saved one (the app's sheet line after Save). */
/** The saved plan on the map changed since its Save – its points only (RouteRules.isUnsaved). */
async function savedPlanChanged(): Promise<boolean> {
  if (state.savedCode == null || !(await ready)) return false;
  if (!(await samePointsAsSaved())) return true;
  // a multi-day tour's days and nights are part of it (multi-day tours §1 #10: saved as every plan – so a change asks for Save)
  const saved = await savedPoints(state.savedCode).plan;
  return tourText(saved?.tour) !== tourText(state.tour);
}
/** The plan on the map differs from its saved version (or was never saved) – Meine Touren's „Speichern“ on its row. */
let planUnsaved = false;
async function renderSaved() {
  const line = $('savedLine');
  if (!state.savedId || state.points.length < 2) {
    line.hidden = true; planUnsaved = state.points.length >= 2;
    saveEntry(state.points.length >= 2 ? 'save' : 'empty'); return;
  }
  const changed = await savedPlanChanged();
  planUnsaved = changed;
  // a plain line (owner 2026-10-31): a new name comes with the next Save (its dialog) or My routes → ⋮ → Rename;
  // a saved plan without a name keeps its saved state, only the line has nothing to name
  line.hidden = !state.savedTitle;
  if (!line.hidden) line.innerHTML = `${icon('check', 16)}<span class="savedname">${t(changed ? 'web_saved_as_changed' : 'web_saved_as', `<b>${escapeHtml(shownTitle(state.savedTitle, ''))}</b>`)}</span>`;
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

/**
 * Speichern in the plan bar (web-sync.md §14.6; owner 2026-11-06, A: always "Speichern" – paired it also sends, "Aufs
 * Handy" beside it says where): dark (the app green) while anything is to save – the shown route new or changed, or
 * (paired) the folder's unsent changes: renames, deletes, GPX imports, the routes waiting for the phone; light with ✓
 * "Gespeichert" when all is saved; greyed when there is nothing at all; amber when the mailbox is full (507, nothing lost).
 */
function saveEntry(st: 'empty' | 'save' | 'saved') {
  saveState = st;
  const b = $<HTMLButtonElement>('menuSavePlan');
  const s = syncRef?.status;
  const paired = !!s?.paired, waiting = paired && s!.pending > 0;
  const shown = paired && s!.full ? 'full' : st === 'save' || waiting ? 'dark' : st === 'saved' ? 'done' : 'empty';
  const label = shown === 'done' ? t('web_saved_short') : t('common_save');
  // paired (owner 2026-11-06): everything saved goes to the phone too – the Save button carries a small phone
  const tip = shown === 'full' ? s!.message : shown === 'dark' ? (paired ? t('web_save_also_phone', s!.phone ?? '') : t('web_save_title_local'))
    : shown === 'done' ? (paired ? t('web_saved_title_phone', s!.phone ?? '') : t('web_saved_title_here')) : t('web_share_needs_two');
  b.disabled = shown === 'empty';
  b.dataset.state = shown;
  b.classList.toggle('unsaved', shown === 'dark');
  b.title = tip;
  b.setAttribute('aria-label', label + ' – ' + tip);
  b.classList.toggle('tophone', paired);
  b.innerHTML = `<span class="saveic">${icon(shown === 'done' ? 'check' : 'save', 20)}${paired ? `<span class="savephone" aria-hidden="true">${icon('phone', 12)}</span>` : ''}</span><span class="lbl">${escapeHtml(label)}</span>`;
}
/**
 * A click on Save (the app's Save since 2026-10-31, "wie Word"; web-sync.md §14.6 rule 5 now): the shown route new or
 * changed – or saved, with nothing waiting – is saved at once (plans.ts saveRoute: overwrite the open saved route, else
 * a new one under the proposed name), no field, no question; then, paired, everything waiting goes to the phone. Only
 * waiting changes and the route unchanged: the click sends them at once. A rename = a click on "Saved as …".
 */
async function headerSave() {
  if (!(await syncReady)) return;
  await draftsRestored;
  await folderSwitch; // never rejects (reported where it starts)
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

// ---- map toolbar (the app's MapControls: eye, compass, locate – always there, greyed when not possible; undo / redo their own pill) ----
function renderFabs() {
  const b = map.getBearing();
  const c = $('compass');
  c.innerHTML = needle(b);
  const what = Math.abs(b) > 1 ? t('web_compass_turned') : t('web_compass_north');
  c.title = what; c.setAttribute('aria-label', what);
  // the eye: the plan's line, or a round-trip suggestion's loop on the map (also with an empty plan, owner 2026-11-20)
  $<HTMLButtonElement>('eye').disabled = !(state.plan && state.points.length >= 2) && !suggestUi?.loopShown();
  $<HTMLButtonElement>('undo').disabled = !state.canUndo;
  $<HTMLButtonElement>('redo').disabled = !state.canRedo;
  // the app's MapControlsEnabled.undoPill: the pill only while one of them can act (owner 2026-11-08)
  const undoPill = state.canUndo || state.canRedo;
  $('undoPill').hidden = !undoPill;
  $('sheetUndoPill').hidden = !undoPill; // the full points sheet's own pill (UndoPill inSheet)
  $<HTMLButtonElement>('sheetUndo').disabled = !state.canUndo;
  $<HTMLButtonElement>('sheetRedo').disabled = !state.canRedo;
  document.body.classList.toggle('undo-shown', undoPill);
  const l = $('locate');
  // Plan is quiet about GPS (the app's PlanGps, owner 2026-11-06 23:35): no colour dot; greyed without a position, still
  // clickable – it follows the position when one comes
  l.innerHTML = icon(position.following ? 'gpsFixed' : 'gpsNotFixed');
  l.classList.toggle('on', position.following);
  l.classList.toggle('greyed', !position.fix);
  const lw = position.status === 'OFF' ? t('web_locate_off_title') : position.status === 'NO_PERMISSION'
    ? t('web_locate_denied_title') : t('web_locate_title');
  l.title = lw; l.setAttribute('aria-label', lw);
  const far = l.dataset.far ?? ''; // off the map: the distance under the icon (updateLocateLabel)
  l.classList.toggle('far', !!far);
  if (far) {
    const d = document.createElement('span'); d.className = 'locdist'; d.textContent = far; l.append(d);
    const what = t('plan_locate_far_cd', far); l.title = what; l.setAttribute('aria-label', what);
  }
  const f = $<HTMLButtonElement>('fitRoute');
  f.disabled = state.points.length < 2;
  f.title = f.disabled ? t('web_share_needs_two') : t('web_fit_route');
}
map.on('rotate', () => renderFabs());
// north up keeps following; in 3D only the direction turns – the view stays tilted (owner 2026-11-06)
$('compass').onclick = () => map.easeTo(terrain3d.on ? { bearing: 0 } : { bearing: 0, pitch: 0 }, { keepFollow: true });
$('undo').onclick = () => step('undo');
$('redo').onclick = () => step('redo');
$('sheetUndo').onclick = () => step('undo');
$('sheetRedo').onclick = () => step('redo');
// Locate while my position is off the map (the app's LocateDistance, owner 2026-11-07): the distance under the icon
// („790 km“) and a tap frames the plan and me together; in view / without a position the tap does what it did
async function updateLocateLabel() {
  const f = position.fix, v = map.getBounds();
  const label = await engine.call('locateLabel', !!f, f?.[0] ?? 0, f?.[1] ?? 0, v.getSouth(), v.getWest(), v.getNorth(), v.getEast()).catch(() => '');
  const l = $('locate');
  if (label === (l.dataset.far ?? '')) return;
  l.dataset.far = label; // the label lives on the button: renderFabs draws it again after every redraw
  renderFabs();
}
map.on('moveend', () => { void updateLocateLabel(); });
$('locate').onclick = () => {
  const f = position.fix;
  if ($('locate').dataset.far && f) {
    // the plan (or a shown track) and me together, in the free map room; following stops
    const b = routeBounds() ?? new maplibregl.LngLatBounds();
    b.extend([f[1], f[0]]);
    const p = map.getPadding();
    if (p.top || p.bottom || p.left || p.right) map.setPadding(NO_PADDING);
    map.fitBounds(b, { padding: fitPadding(), maxZoom: 16 });
    return;
  }
  locating = true; position.locate(); locateNotice();
};
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
/**
 * After Cancel at the very-long-route question (the app's answerLongRoute(false), owner 2026-11-13): the map goes back
 * to the plan from before – the whole plan, or its one point at the one-place zoom; no point: it stays
 * (the app's LongRouteCancelCamera.after, run as it is – slices.ts). Only this Cancel moves the map, not Stop or the slow
 * plan's Cancel. Without the slices' instance the map stays (reported there).
 */
function cancelLongRouteCamera() {
  const line = state.plan?.legs.flatMap((l) => l.points.map(([la, lo]) => [la, lo] as LatLon)) ?? [];
  const fit = slice<LatLon[] | null>('cancelCamera', enc(state.points), enc(line));
  console.info(`[map] very long plan cancelled: camera → ${fit ? `fit (${fit.length} points)` : 'stays'}`);
  if (!fit) return;
  const b = new maplibregl.LngLatBounds();
  fit.forEach(([la, lo]) => b.extend([lo, la]));
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
/** Eye: hold to hide the route and the markers, release shows them again (the app's EyeControl) – also a round-trip
 *  suggestion's loop and start dot, to see the path under it (owner 2026-11-20; the cards stay). */
function peek(on: boolean) {
  if (hidden === on) return;
  hidden = on;
  for (const id of ['planned-route-casing', 'planned-route', 'planned-straight', 'planned-route-arrows', ...SUGGEST_LAYERS]) if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', on ? 'none' : 'visible');
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
  // the found place's menu: the very rows a hold on it gives (owner 2026-11-13, PinMenuItems) – the head's kind line
  // from the app's PoiMenu when the place is a POI kind, else the search row's own
  kept, recent: recentSearch,
  pinMenu: async (h) => {
    const info = JSON.parse(await engine.call('poiMenu', h.kind, h.name ?? '', '', h.lat, h.lon)) as { sub: string; color: string } | null;
    return placeRows(h, { title: h.title, sub: info?.sub ?? h.subtitle, color: info?.color ?? h.color }, true);
  },
  menuRoom, // the free map for the found place's pin and its menu (menuRoom above)
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
  if (role === 'REMOVE') { if (pv.existing != null) await deletePoint(pv.existing); return; }
  // the app's PlaceAddState.addPlace on the points as they are then: a planned place – „As start“ = Start here, „As
  // destination“ moves it to the end; else a pinned stop where the card said (WaypointInsertion), at the start or the end
  await queueEdit(() => placeEdit(role, p, 30, { pinned: true, name: h.title, kind: h.kind }, h.title ?? '', h.kind ?? ''));
}

// ---- menus, sheet, dialogs ----
function closeMenus() {
  $('mainMenu').classList.remove('open'); $('menuBtn').setAttribute('aria-expanded', 'false');
  $('saveMenu').hidden = true; $('saveMore').setAttribute('aria-expanded', 'false');
}
// Plan ▾ (phone width): the plan bar as a drop-down
$('menuBtn').onclick = (e) => {
  e.stopPropagation();
  const open = !$('mainMenu').classList.contains('open');
  closeMenus(); picker.close();
  $('mainMenu').classList.toggle('open', open); $('menuBtn').setAttribute('aria-expanded', String(open));
};
// Speichern ▾ (wide): Save as · Export GPX under the Save button
$('saveMore').onclick = (e) => {
  e.stopPropagation();
  const open = $('saveMenu').hidden;
  closeMenus(); picker.close();
  if (!open) return;
  const r = $('saveMore').parentElement!.getBoundingClientRect(), m = $('saveMenu');
  m.hidden = false;
  m.style.left = Math.max(8, Math.min(r.left, innerWidth - m.offsetWidth - 8)) + 'px';
  m.style.top = r.bottom + 6 + 'px';
  $('saveMore').setAttribute('aria-expanded', 'true');
  m.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
};
/** What each entry of the plan bar / its menus does (one table: the wide bar, the phone drop-down, Speichern ▾). */
const ACTS: Record<string, () => void> = {
  newPlan: () => { newPlan(); },
  menuPlans: () => myPlansDialog(sync, plansHost),
  menuImportGpx: () => gpxImport.pick(),
  menuSavePlan: () => { headerSave(); },
  menuSaveAs: () => { saveAs(); }, saveAsItem: () => { saveAs(); },
  menuExportGpx: () => { exportGpx(); }, exportGpxItem: () => { exportGpx(); },
  // multi-day tours (multiday.ts): the guide panel, or the open plan straight to the days box
  menuMultiDay: () => { multiDay?.openGuide(); },
  menuPlanAsTour: () => { void multiDay?.daysBox(); }, planAsTourItem: () => { void multiDay?.daysBox(); },
  syncBadge: () => { if (sync.status.paired) phoneWindow(); else toPhone(); },
  phoneMore: () => { phoneWindow(); }, menuPhone: () => { phoneWindow(); },
  menuAbout: () => credits(), menuAboutP: () => credits(),
};
$('phoneMini').onclick = () => { closeMenus(); phoneWindow(); };
for (const box of ['mainMenu', 'saveMenu', 'headRight']) {
  $(box).addEventListener('click', (e) => {
    const b = (e.target as HTMLElement).closest('button');
    if (!b || b.id === 'saveMore' || !(b.id in ACTS)) return;
    e.stopPropagation(); closeMenus();
    ACTS[b.id]();
  });
}
document.addEventListener('click', (e) => { if (!(e.target as HTMLElement).closest('.menu, .planbar')) closeMenus(); });
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  if (!$('saveMenu').hidden) { closeMenus(); $('saveMore').focus(); } else if ($('mainMenu').classList.contains('open')) { closeMenus(); $('menuBtn').focus(); }
});

$('reverse').onclick = () => applyEdit('reverse');
// „+ Ziel hinzufügen“ (proposal A): the search, whose place card adds the place (Hinzufügen) – as Google Maps' „Add stop“
$('addDest').onclick = () => { const q = document.getElementById('q') as HTMLInputElement | null; q?.focus(); q?.select(); };
$('roundTrip').onclick = () => applyEdit('roundTrip');
/** ✕ Neu planen in the plan row and the header's New plan (owner 2026-11-06): unsaved changes ask first, then empty. */
async function newPlan() { engine.dropPlans(); await keepAsRecent('new plan'); clearRoute(); placeCard.removePin(); } // a long plan must not hold Neu
/**
 * The plan leaves the map (Neu, another plan / link / GPX opened): not saved as it is, it goes under Meine Touren →
 * Zuletzt – no question (the app's keepAsDraft, owner 2026-11-06). The name: the plan's own, else the Save dialog's
 * proposal (waited for in the background), else the date name.
 */
async function keepAsRecent(why: string) {
  const fromRecent = state.recentId;
  state.recentId = null;
  if (state.points.length < 2) return; // one point is no plan
  // the plan itself unsaved (never saved, or changed since) – not Save's dark state, which also counts routes waiting for the phone
  // (read at once, like Save's state was: renderSaved keeps planUnsaved current; no await before the plan is read)
  const savedId = state.savedId && !planUnsaved ? state.savedId : null, savedFolder = state.savedFolder;
  const pts = enc(state.points), plan = { ...sharedPlan(), points: [...state.points] }, n = state.points.length;
  const own = state.title || plansHost.linkTitle();
  await slicesReady(); // the plan is read; the slice decides (loaded with the page – without it the plan is still kept)
  // what is kept is the app's RoutesState.keepDraft (recent.ts → the slice); this looks up what its store asks: the
  // saved route the plan is as it is – still there, in this browser's recycle bin, or gone (deleted here or on the
  // phone: then the plan is kept as a draft, never lost) – and a saved route with exactly its points
  let saved: 'there' | 'binned' | 'gone' | undefined;
  if (savedId) {
    let there = true;
    try {
      await syncReady;
      const folders = [...new Set([sync.open, savedFolder].filter((f): f is string => !!f))];
      there = (await Promise.all(folders.map((f) => sync.list(f)))).some((l) => l.some((p) => p.id === savedId));
    } catch (e) { there = false; problem('routes', 'Zuletzt: the saved routes could not be read – the plan is kept as a draft too (never lost)', e); }
    let binned = false;
    if (!there) {
      try { binned = (await sync.binnedRoutes()).some((e) => e.id === savedId); }
      catch (e) { problem('routes', 'Zuletzt: the recycle bin could not be read – the plan is kept as a draft too (never lost)', e); }
    }
    saved = there ? 'there' : binned ? 'binned' : 'gone';
    // saved as it is: that saved route takes the Zuletzt place (the „Gespeichert“ tag); in the bin: nothing twice
    if (saved !== 'gone') { keepPlan({ code: '', title: '', points: pts, savedId, saved }); return; }
  }
  if (!(await ready)) return;
  // changed (or new) but exactly the points of a saved route of the open folder: that route takes the Zuletzt place –
  // never a twin draft beside it (RouteRules.isUnsaved: points only – the page compares them, the slice decides)
  const twin = await savedTwin(plan.points);
  const code = await engine.encode(plan);
  const dated = datedName();
  const id = keepPlan({ code, title: own || dated, points: pts, replaces: fromRecent, savedId, saved, twin: twin?.id ?? null });
  if (!id) {
    if (twin) console.info(`[Problems] TRACE routes: Zuletzt: the plan has the points of the saved route "${twin.title ?? ''}" – it takes the place, no draft`);
    return;
  }
  keepPlanStats(code); // Zuletzt's numbers and line (Meine Touren's preview) – the plan is still the routed one here
  console.info(`[Problems] TRACE plan: kept under Zuletzt (${why}), ${n} points`);
  if (own) return;
  void Promise.race([suggestName(), new Promise<string>((r) => setTimeout(() => r(''), 5_000))]).then((name) => {
    // the place name follows as a rename (app 21b4a7b1) – never over a rename, a save or a delete meanwhile
    if (name && renameRecentIf(id, dated, name)) console.info(`[Problems] TRACE plan: Zuletzt "${dated}" → "${name}"`);
  }).catch((e) => problem('routes', 'no name for the plan under Zuletzt', e));
}
/**
 * The saved route of the open folder with exactly [points] (lat/lon, as [samePointsAs]), or null – the newest when there
 * are several. A failure reads as none: the plan is then kept as a draft (never lost).
 */
async function savedTwin(points: SharedPlan['points']): Promise<SavedPlan | null> {
  try {
    if (!(await syncReady)) return null;
    for (const p of await sync.list(sync.open)) if (p.kind === 'plan' && p.plan && (await samePointsAs(p.plan, points))) return p;
  } catch (e) { problem('routes', 'Zuletzt: the saved routes could not be compared – the plan is kept as a draft', e); }
  return null;
}
/** The saved plan just made from a Zuletzt entry: Meine Touren shows „✓ Gerade gespeichert“ on it. */
let justSavedFromRecent: string | null = null;
function forgetSaved() { state.savedId = null; state.savedTitle = null; state.savedCode = null; state.savedFolder = null; state.storedCode = null; }
/**
 * Meine Touren's map pictures (routethumbs.ts, the app's My routes L1): drawn on the map's style as it is now (the cells'
 * current tile addresses, the activity's look), without what the page adds (the plan, POIs, the profile).
 */
const routeThumbs = new RouteThumbs(() => {
  let s: StyleSpecification | undefined;
  try { s = map.getStyle(); } catch { return null; } // expected: the style is still being read – tried again with the next list
  if (!s?.layers?.length) return null;
  s.layers = s.layers.filter((l) => baseIds.layers.has(l.id));
  for (const k of Object.keys(s.sources)) if (!baseIds.sources.has(k)) delete s.sources[k];
  return s;
});
(window as unknown as { __thumbs: RouteThumbs }).__thumbs = routeThumbs; // for the browser tests
const plansHost = {
  thumbs: routeThumbs,
  /** The planning activity (the activity filter's preset each time Meine Touren opens) and the six with their names. */
  activity: () => state.activity,
  activityRows: () => picker.allRows(),
  samePoints: async (saved: string, other: string) => {
    const o = await savedPoints(other).plan;
    return !!o && samePointsAs(saved, o.points);
  },
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
      if (tr && tr !== before) { keepStats(trackKey(p.id), { m: tr.distanceM, up: tr.ascentM, down: tr.descentM, s: tr.durationS }); keepShape(trackKey(p.id), trackSegs(tr.segments)); }
      return;
    }
    const shared = await engine.parse('p=' + p.plan);
    if (!shared) { status(t('web_plan_unreadable'), 'error'); return; }
    // the multi-day guide's „Noch etwas anhängen“ → „Gespeicherte Route öffnen“: appended behind the route, not opened
    if (multiDay?.takeAppend()) { await appendRoute(shared.points); return; }
    if (shared.points.map(key).join(';') !== state.points.map(key).join(';')) await keepAsRecent('another plan opened');
    state.recentId = null;
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
    if (state.recentId) justSavedFromRecent = p.id;
    // saved: the saved route takes the Zuletzt place – the draft it came from and any with its points go (the app's afterSaved)
    afterSaved(p.id, state.recentId, state.points.length >= 2 ? enc(state.points) : null);
    state.recentId = null;
    state.savedId = p.id; state.savedTitle = p.title; state.savedCode = p.plan; state.title = p.title; state.savedFolder = folder; state.storedCode = p.plan;
    keepPlanStats(p.plan);
    rememberInAddress();
    render(); // the sheet says "Saved as …" – no message (the app's saveRoute)
  },
  suggestName,
  /** Meine Touren → Zuletzt → a click: the plan comes back, still belonging to its entry (saving it moves it). */
  openRecent: async (r: RecentPlan) => {
    const shared = await engine.parse('p=' + r.code);
    if (!shared) { status(t('web_plan_unreadable'), 'error'); return; }
    if (shared.points.map(key).join(';') !== state.points.map(key).join(';')) await keepAsRecent('a plan from Zuletzt opened');
    forgetSaved();
    await loadShared({ ...shared, title: r.title });
    state.recentId = r.id;
    rememberInAddress();
    keepPlanStats(r.code);
  },
  /** „✓ Gerade gespeichert“: the plan just saved from Zuletzt (its id), shown once in Meine Touren. */
  justSaved: () => { const id = justSavedFromRecent; justSavedFromRecent = null; return id; },
  setJustSaved: (id: string) => { justSavedFromRecent = id; },
  importGpx: () => gpxImport.pick(),
  live: () => {
    if (!state.points.length) return null;
    const p = state.plan, routed = !!p && !state.planning && planFor === enc(state.points) && !p.error && !p.failed;
    return {
      title: state.title || null, points: state.points.length, savedId: state.savedId, savedFolder: state.savedFolder,
      recentId: state.recentId, pointsKey: enc(state.points), unsaved: state.savedId ? planUnsaved : state.points.length >= 2, canSave: state.points.length >= 2,
      stats: routed ? { m: p!.distanceM, up: p!.ascentM, down: p!.descentM, s: p!.timeS } : null,
      segs: routed ? p!.legs.map((l) => l.points.map(([la, lo]) => [la, lo] as [number, number])) : null,
    };
  },
  saveLive: () => headerSave(),
  exportRecent: async (r: RecentPlan) => {
    await plansHost.openRecent(r);
    const end = Date.now() + 60_000;
    while ((state.planning || !state.plan) && Date.now() < end) await new Promise((res) => setTimeout(res, 200));
    await exportGpx();
  },
  planFrom: async (p: SavedPlan, folder: string) => {
    await plansHost.open(p, folder);
    document.getElementById('planFromTrack')?.click(); // the track panel's „Als Plan übernehmen“
  },
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
/** "📱 Aufs Handy" in the plan bar (owner 2026-11-06, A): not paired that label – a click pairs, then the plan goes over.
 *  Paired (owner 2026-11-06: Save sends anyway) it is no second Save but the phone's status, big enough to see (owner
 *  2026-11-07: "wichtiges Symbol, soll zeigen, wann zuletzt synchronisiert"): "📱 Pixel 8 🟢" and below it "synced 2 min.
 *  ago" (amber dot + "last seen 3 days ago" when the phone was away for a day – phoneState); a click opens the Phone
 *  window. Narrower headers drop the second line, then the name (icon + dot stay). Follows sync.onChange; "… ago" moves
 *  every 30 s. */
function syncBadge() {
  const b = $('syncBadge'), s = sync.status, pill = phonePill(s);
  const dot = b.querySelector<HTMLElement>('.syncdot')!;
  dot.hidden = !s.paired;
  dot.dataset.kind = pill.kind;
  b.classList.toggle('paired', s.paired);
  b.dataset.kind = pill.kind;
  const sub = b.querySelector<HTMLElement>('.sub')!;
  sub.textContent = s.paired ? pill.sub : '';
  sub.hidden = !s.paired || !pill.sub;
  b.querySelector<HTMLElement>('.lbl')!.textContent = s.paired ? pill.name : t('web_to_phone');
  b.title = s.paired ? [pill.name, pill.sub, s.message].filter(Boolean).join(' · ') : t('web_to_phone_pair') + ' – ' + t('web_phone_why');
  b.setAttribute('aria-label', s.paired ? t('web_phone_title') + ' – ' + b.title : t('web_to_phone') + ' – ' + b.title);
  // phone width: the plan bar is behind Plan ▾ – the paired phone's status stays in the header (📱 + dot, the same title)
  const mini = $('phoneMini');
  mini.hidden = !s.paired;
  mini.title = b.title; mini.setAttribute('aria-label', t('web_phone_title') + ' – ' + b.title);
  mini.querySelector<HTMLElement>('.syncdot')!.dataset.kind = pill.kind;
  $('phoneMore').hidden = s.paired; // paired the button itself opens the Phone window
  $('menuPhone').hidden = s.paired;
}
window.setInterval(() => syncBadge(), 30_000);
/**
 * Aufs Handy, not paired → the pairing window (QR code); once paired and closed, the open plan goes to the phone (Save
 * – its name dialog for a new plan – then everything waiting is sent). Paired → the same as Save (save + send).
 */
async function toPhone() {
  if (!(await syncReady)) return; // the engine did not start – the status line says so
  if (sync.status.paired) { await headerSave(); return; }
  pairDialog(sync, location.href, () => { if (sync.status.paired) afterPairing(); });
}
/**
 * OK after a pairing from "To phone": the open plan follows the pairing. Saved and unchanged, it never asks for a name
 * – the pairing moved it into the phone's folder as waiting and its first round may already have sent it (then OK
 * found nothing waiting, and Save of a saved plan asked for its name: sync-smoke scenario 1, CI 2026-11-13); OK sends
 * what still waits. New or changed: Save as always (its name dialog for a new plan), then send.
 */
async function afterPairing() {
  await draftsRestored;
  await folderSwitch; // the pairing's swap of the open route into the phone's folder
  await renderSaved();
  if (saveState === 'saved') { if (sync.status.paired) sync.sendAll(); return; }
  await headerSave();
}
/** Aufs Handy ▾ / the drop-down's Phone entry: the Phone window (state, Sync now, pair another, Unpair). */
async function phoneWindow() {
  if (!(await syncReady)) return;
  phoneDialog(sync, location.href);
}
/** Speichern ▾ → Speichern unter: the shown route as a NEW saved plan under a name (the open one stays as it was). */
async function saveAs() {
  if (!(await syncReady) || state.points.length < 2) return;
  await draftsRestored;
  try { await saveRouteAs(sync, plansHost, () => { if (sync.status.paired) sync.sendAll(); }); }
  catch (e) { problem('sync', 'route not saved as', e, 'error'); status(t('web_save_failed'), 'error'); }
}
const share = async () => {
  closeMenus();
  if (state.points.length < 2) { status(t('plan_err_set_start_dest')); return; }
  // Export GPX of the shown plan lives here since the sheet has no ⋯ (owner 2026-10-31); it needs the planned line
  shareDialog(location.href.split('#')[0] + '#p=' + await engine.encode(sharedPlan()), state.title, appHint, state.plan ? exportGpx : undefined);
};
$('share').onclick = share;
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
    nextTour = null; // a new route from a track: a normal plan until the days are set
    await commit(points, names ? points.map((_, i) => ({ pinned: !!pinned?.[i], name: names[i] ?? undefined })) : undefined);
  },
  mayReplacePlan: async () => { await keepAsRecent('GPX opened'); return true; }, // the app: the replaced plan goes under Zuletzt, no question
  // multi-day tours (stage 3): trim handles while the guide is open; „An die Route anhängen“ once it has a route
  tourMode: () => multiDay?.gpxMode() ?? 'off',
  append: async (points) => { await appendRoute(points); },
  // A-017 rule 14: an imported track is kept in the open folder and goes to the phone with Save
  imported: (tr) => {
    syncReady.then(async (ok) => {
      if (!ok) return;
      // 250 tours per phone (SyncLimits): the track still shows; it is just not kept – the amber Save says why
      if (!(await sync.hasRoom())) { sync.refuseTour(); return; }
      const id = await sync.saveTrack(tr.segments, tr.waypoints, tr.activity, tr.sac, tr.name);
      if (id) { keepStats(trackKey(id), { m: tr.distanceM, up: tr.ascentM, down: tr.descentM, s: tr.durationS }); keepShape(trackKey(id), trackSegs(tr.segments)); } // My routes' numbers + line
    }).catch((e) => problem('sync', 'imported track not kept', e));
  },
});
(window as unknown as { __gpx: typeof gpxImport }).__gpx = gpxImport; // for the browser tests

/** „An die Route anhängen“ (multi-day tours §3.3): [points] behind the last point – turned round when their end is nearer (TrackToPlan.append). One undo step. */
async function appendRoute(points: LatLon[]) {
  await queueEdit(async () => {
    const joined = dec(await engine.call('trackAppend', enc(state.points), enc(points)));
    return joined.length > state.points.length ? { planned: commit(joined) } : null;
  });
}

// the multi-day tour UI (multiday.ts): guide, days box, overview, day ends, night menus, hotel dialog
multiDay = new MultiDay({
  map, engine,
  points: () => state.points,
  pinned: () => state.meta.flatMap((m, i) => (m.pinned ? [i] : [])),
  namesText,
  activity: () => state.activity,
  sac: () => state.sac,
  title: () => state.title,
  setTitle: (title) => { state.title = title; rememberInAddress(); },
  tour: () => state.tour,
  routedPlan: () => (state.points.length >= 2 && state.plan && !state.planning && planFor === enc(state.points) ? state.plan : null),
  apply: applyDayPlan,
  status,
  pickGpx: () => gpxImport.pick(),
  openSaved: () => { void myPlansDialog(sync, plansHost); },
  clearRoute,
  suggestName,
  searchAreas: async () => { await engine.searchAreas(await (useCells ? cells.searchAreas(map) : Promise.resolve([{ key: 'map', dir: mapFolder }]))); },
  markRange: (r) => routeProfile.markRange(r),
  fitPadding,
  openMenu,
  addPoint: (at) => { void addPoint(at); },
}, $('sheet'));
render();
(window as unknown as { __planStats: unknown }).__planStats = { keepStats, planKey, trackKey, keepShape, statsText }; // for the browser tests (sort-smoke, routes-smoke)
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

/** Full credits (the app's Settings → About, MAP_CREDITS); the map shows "© OpenStreetMap · OpenMapTiles" (the app's MAP_CREDIT). */
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

/**
 * The app's About links (ui/AboutLinks.kt OnPathLinks, app ca5f0f7b, owner 2026-11-08): the website's pages in the page's
 * language (German under /de/ with German slugs, English at the root), mail and the source – the app's words.
 */
function aboutLinks(): [string, string][] {
  const site = 'https://onpath.earth', de = lang === 'de';
  return [
    ['settings_link_help', de ? `${site}/de/hilfe/` : `${site}/help/`],
    ['settings_link_website', de ? `${site}/de/` : `${site}/`],
    ['settings_link_contact', 'mailto:info@onpath.earth'],
    ['settings_link_privacy', de ? `${site}/de/datenschutz/` : `${site}/privacy/`],
    ['settings_link_legal', de ? `${site}/de/impressum/` : `${site}/legal/`],
    ['settings_link_source', 'https://github.com/happychriss/OnPath'],
  ];
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
    <b>${escapeHtml(t('settings_links'))}</b><ul class="credits aboutlinks">${aboutLinks().map(([k, href]) =>
      `<li><a href="${href}" target="_blank" rel="noopener">${escapeHtml(t(k))}</a></li>`).join('')}</ul>
    <p class="muted small">${escapeHtml(t('settings_about_licence'))}</p>
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
/** Phone: the bottom sheet pulled up past the undo pill hides the pill (the app's column steps back in the points step). */
function placeUndoPill() {
  const bar = $('topbar').getBoundingClientRect(), sheet = $('sheet').getBoundingClientRect();
  const crowded = matchMedia('(max-width: 700px)').matches && sheet.height > 0 && sheet.top < bar.bottom + 16 + 52 + 8;
  document.body.classList.toggle('undo-crowded', crowded);
}
new ResizeObserver(() => {
  document.documentElement.style.setProperty('--sheet-h', $('sheet').offsetHeight + 'px');
  placeUndoPill();
}).observe($('sheet'));
addEventListener('resize', placeUndoPill);
changed();

// ---- round-trip suggestions (suggest.ts, docs/design/tour-suggestions.md §7 stage 3) – on unless the ?debug switch turned them off ----
if (suggestOn()) {
  try {
    suggestUi = setupSuggest({
      map, engine, cells: params.get('data') ?? base + 'data/',
      activity: () => ({ activity: state.activity, sac: state.sac }),
      setActivity: (a, sac) => setActivity(a, sac, false),
      plan: async (points, title) => { state.title = title; forgetSaved(); await commit(points); },
      fitPadding,
      closeMenus: () => { closeMenu(); closeMenus(); },
      loopChanged: () => renderFabs(),
    });
    document.body.dataset.suggest = 'on';
  } catch (e) { problem('suggest', 'round-trip suggestions not set up', e); }
}

/** The sheet's status line; 'busy' = a wait the user started (spinner, CLAUDE.md "Messages"). */
function status(t: string, kind: 'info' | 'error' | 'busy' = 'info') {
  const s = $('status');
  s.textContent = t;
  s.classList.toggle('error', kind === 'error');
  s.classList.toggle('busy', kind === 'busy' && t !== '');
  waits.set('status', kind === 'busy' && t !== '');
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
