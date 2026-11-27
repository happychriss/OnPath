// The live tour's viewer page (docs/design/live-tracking.md §3): the link's key comes after "#", is read once, taken out
// of the address bar and kept in memory only – a reload needs the link again. Everything shown is decided by the app's
// shared code in the engine (which status line wins, when to ask the server again, the numbers' format); this file only
// turns it into words and a map. Look (owner 2026-11-26, design „A“): the map fills the screen, a small pill on top, a
// soft sheet below with the name, the one sentence that matters and three facts – friendly, not technical.
import 'maplibre-gl/dist/maplibre-gl.css';
import '../fonts/manrope.css';
import './live.css';
import { lang, t } from '../i18n';
import { loadEngine, type LiveEngine, type StatusLine } from './engine';
import { LiveBox, type BoxState } from './box';
import { LiveMap } from './map';

const base = new URL('../', location.href.split('#')[0].split('?')[0]).href;
// the key: read, then out of the address bar (history, a screenshot of the bar, "share this page")
const link = location.hash;
if (link) history.replaceState(null, '', location.pathname + location.search);

const svg = (d: string) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d}</svg>`;
const ICON = {
  flag: svg('<path d="M5 21V4"/><path d="M5 4h11l-2 4 2 4H5"/>'),
  battery: svg('<rect x="2.5" y="7" width="17" height="10" rx="2.5"/><path d="M22 11v2"/><rect x="5" y="9.5" width="7" height="5" rx="1" fill="currentColor" stroke="none"/>'),
  pin: svg('<path d="M12 21s-6.5-6.2-6.5-11.2a6.5 6.5 0 0 1 13 0C18.5 14.8 12 21 12 21z"/><circle cx="12" cy="9.8" r="2.3"/>'),
  copy: svg('<rect x="8" y="8" width="12" height="12" rx="2.5"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>'),
  map: svg('<path d="M9 4 3 6.5v13.5l6-2.5 6 2.5 6-2.5V4l-6 2.5z"/><path d="M9 4v13.5M15 6.5V20"/>'),
  person: svg('<circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/>'),
};
const root = document.getElementById('live')!;
root.innerHTML = `
  <div id="mapbox"><div id="map"></div><div id="mapWait" class="mapwait"></div><button id="toPos" class="topos" hidden></button>
    <div id="count" hidden><span class="pdot"></span><span id="countText"></span></div></div>
  <section id="sheet">
    <div class="grab"></div>
    <div id="who" class="who"><div id="avatar" class="avatar"><span id="initial"></span><i class="badge"></i></div>
      <div class="whotext"><div id="title"></div><div id="status" class="status" role="status"><span id="line"></span></div></div></div>
    <div id="note" class="note" hidden></div>
    <div id="facts" class="list" hidden>
      <div id="togo" class="li" hidden><span class="ic go">${ICON.flag}</span><span id="togoLabel" class="lbl"></span><span id="togoText" class="val"></span></div>
      <div id="battery" class="li" hidden><span class="ic bat">${ICON.battery}</span><span id="batteryLabel" class="lbl"></span><span id="batteryText" class="val"></span></div>
      <div id="place" class="li" hidden><span class="ic pin">${ICON.pin}</span><span id="placeLabel" class="lbl"></span>
        <span class="val"><span id="placeText"></span><span id="coords" class="sub"></span></span></div>
    </div>
    <div id="acts" class="acts" hidden><button id="copy" class="btn">${ICON.copy}<span id="copyText"></span></button><a id="geo" class="btn">${ICON.map}<span id="geoText"></span></a></div>
    <footer id="foot"></footer>
  </section>`;
const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
document.title = t('web_live_page_title');
$('title').textContent = t('web_live_title');
$('line').textContent = t('web_live_loading');
$('foot').textContent = t('web_live_no_rescue');
$('toPos').textContent = t('web_live_to_position');
$('mapWait').textContent = t('web_live_map_wait');
$('copyText').textContent = t('web_live_copy');
$('geoText').textContent = t('web_live_open_maps');
$('togoLabel').textContent = t('web_live_row_arrival');
$('batteryLabel').textContent = t('web_live_row_battery');
$('placeLabel').textContent = t('web_live_row_last');
$('initial').innerHTML = ICON.person;

const clock = (ms: number) => new Intl.DateTimeFormat(lang, { hour: '2-digit', minute: '2-digit' }).format(new Date(ms));
const span = (min: number) => (min < 60 ? t('web_live_minutes', min) : t('web_live_hours', Math.floor(min / 60), String(min % 60).padStart(2, '0')));

let engine: LiveEngine | null = null;
let map: LiveMap | null = null;
let box: LiveBox | null = null;
let state: BoxState | null = null;

/**
 * One moment for "when was the last report" (owner 2026-11-26, night: the pill said 10:00, the list 09:56 – two times for
 * one question): the phone's last upload, on the server's clock (no phone clock, no skew) – the status line, the pill and
 * the "Zuletzt" row all count from it, and the place's row shows only the coordinates. Only a place clearly older than
 * that report ([OLD_FIX_MS] or two of the phone's intervals – a lost GPS while it still reports) says its own time.
 */
const OLD_FIX_MS = 10 * 60_000;
function oldFix(s: BoxState): boolean {
  const fix = s.head?.fix, up = s.lastWriteMs;
  return !!fix && up != null && up - fix.time > Math.max(OLD_FIX_MS, 2 * (s.head?.nextS ?? 0) * 1000);
}

/** The one sentence that matters, from the shared rule (LiveStatusLine): the words for its key – the fact only, never a
 *  guess why (owner 2026-11-26). */
function words(l: StatusLine): string {
  switch (l.key) {
    case 'stopped': return t('web_live_stopped');
    case 'broken': return t('web_live_broken');
    case 'no_data': return t('web_live_no_data');
    case 'ended': return t(l.arrived ? 'web_live_ended_arrived' : 'web_live_ended', l.atMs != null ? clock(l.atMs) : '').replace(/ · $/, '');
    case 'no_contact': return t('web_live_no_contact', span(l.minutes ?? 0));
    case 'low_battery': return t('web_live_low_battery', l.percent ?? 0);
    case 'off_route': return t('web_live_off_route');
    case 'pause': return t('web_live_pause');
    case 'late': return l.atMs != null ? t('web_live_late', clock(l.atMs)) : t('web_live_late_plain');
    case 'moving': return state?.route.length ? t('web_live_moving') : t('web_live_moving_free'); // owner 2026-11-27, hint list 20
  }
}

/** After the planned moment the pill says "gleich …" this long before "waiting for the phone": the viewer asks right after
 *  the moment and then every 5 s for a while (LivePolicy.VIEWER_SLACK_MS + VIEWER_EAGER_MS) – no amber flicker every cycle. */
const OVERDUE_GRACE_S = 20;
let countTimer = 0;
const mmss = (sec: number) => Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0');

/** What the pill counts down, from what the page has: the head's nextS (the phone's planned interval, LivePolicy.intervalS)
 *  minus the time since the last upload (the server's clock, see BoxState.sinceLastWrite). No request.
 *  null = no countdown (nothing known, tour over, no contact); overdue = the planned moment is long past. */
function countdown(): { left: number; total: number; overdue: boolean } | null {
  const h = state?.head, since = state?.sinceLastWrite();
  if (!h || since == null || state?.dead || state?.ended != null || h.state === 'ended' || !(h.nextS > 0)) return null;
  const key = document.body.dataset.state;
  if (key !== 'moving' && key !== 'pause' && key !== 'off_route' && key !== 'low_battery' && key !== 'late') return null; // no contact etc. have their own words
  const left = Math.ceil(h.nextS - since / 1000);
  return { left, total: h.nextS, overdue: left < -OVERDUE_GRACE_S };
}
/** One timer (1 s) while the countdown runs and the tab is visible. With no contact the pill says when it last reported. */
function updateCount() {
  const c = countdown(), pill = $('count');
  if (!c) {
    clearInterval(countTimer); countTimer = 0;
    const at = state?.lastWriteMs;
    const quiet = document.body.dataset.state === 'no_contact' && at != null;
    pill.hidden = !quiet;
    pill.dataset.kind = 'quiet'; pill.dataset.overdue = '';
    if (quiet) $('countText').textContent = t('web_live_last_report', clock(at!));
    return;
  }
  pill.hidden = false;
  pill.dataset.kind = 'live';
  pill.dataset.overdue = c.overdue ? '1' : '';
  $('countText').textContent = c.overdue ? t('web_live_waiting') : c.left <= 0 ? t('web_live_soon') : t('web_live_next_point', mmss(c.left));
  if (c.overdue) { $('status').dataset.tone = 'amber'; $('who').dataset.tone = 'amber'; }
  if (document.hidden) { clearInterval(countTimer); countTimer = 0; return; } // hidden tab: nothing runs, redrawn when it is back
  if (!countTimer) countTimer = window.setInterval(updateCount, 1000);
}
document.addEventListener('visibilitychange', updateCount);

function render() {
  if (!engine || !state) return;
  const s = state, h = s.head;
  const since = s.sinceLastWrite() ?? 0;
  const line = JSON.parse(engine.liveStatus(link, s.headBlob, s.dead === 'gone', s.dead === 'broken', since, s.ended ?? -1)) as StatusLine;
  const dead = line.key === 'stopped' || line.key === 'broken';
  document.body.dataset.state = line.key;
  document.body.dataset.dead = dead ? '1' : '';
  const tone = dead || line.key === 'no_data' ? 'plain' : line.key === 'ended' ? (line.arrived ? 'good' : 'plain')
    : line.amber ? 'amber' : line.key === 'no_contact' ? 'grey' : line.key === 'pause' ? 'calm' : 'good';
  $('status').dataset.tone = tone; $('who').dataset.tone = tone;
  $('line').textContent = words(line);
  $('title').textContent = (!dead && h?.name) || t('web_live_title');
  const initial = !dead && h?.name ? [...h.name.trim()][0]?.toUpperCase() : undefined;
  if (initial) $('initial').textContent = initial; else $('initial').innerHTML = ICON.person;
  const note = $('note');
  note.hidden = !s.unreachable || dead; note.textContent = t(s.why === 'busy' ? 'web_live_busy' : s.why === 'offline' ? 'web_live_offline' : 'web_live_server');
  $('facts').hidden = dead || !h;
  $('acts').hidden = dead || !h?.fix;
  updateCount();
  $('mapbox').hidden = dead;
  if (dead) { // nothing of the tour stays in the page – not in a hidden row either
    map?.clear();
    for (const id of ['togoText', 'batteryText', 'placeText', 'coords']) $(id).textContent = '';
    for (const id of ['togo', 'battery', 'place']) $(id).hidden = true;
    delete $('place').dataset.coords; $('geo').removeAttribute('href');
    return;
  }
  if (!h) return;
  const togo = $('togo');
  togo.hidden = !(h.toGoText && h.arrivalMs != null && line.key !== 'ended');
  if (!togo.hidden) $('togoText').textContent = t('web_live_arrival', clock(h.arrivalMs!), h.toGoText!);
  const bat = $('battery');
  bat.hidden = h.batteryPct == null;
  if (h.batteryPct != null) {
    // with no contact the number is old: it says when it was true
    // no second clock time here: the status line, the pill and the row below already say when it was true
    $('batteryText').textContent = t('web_live_battery', h.batteryPct);
    bat.dataset.low = h.batteryPct < 15 ? '1' : '';
  }
  const place = $('place');
  place.hidden = false;
  const min = Math.floor(Math.max(0, since) / 60_000);
  $('placeText').textContent = min < 1 ? t('web_live_ago_now') : t('web_live_ago', span(min));
  if (h.fix) {
    // the last place is always on the page as text – what one reads out to the mountain rescue (§3)
    const coords = `${h.fix.lat.toFixed(5)}, ${h.fix.lon.toFixed(5)}`;
    $('coords').textContent = oldFix(s) ? t('web_live_place_from', clock(h.fix.time), coords) : coords;
    place.dataset.coords = coords;
    ($('geo') as HTMLAnchorElement).href = `geo:${h.fix.lat.toFixed(5)},${h.fix.lon.toFixed(5)}?q=${h.fix.lat.toFixed(5)},${h.fix.lon.toFixed(5)}`;
  } else { $('coords').textContent = ''; delete place.dataset.coords; }
  map?.show(s.route, s.chunks, h.fix, line.key === 'ended' ? 'ended' : line.key === 'no_contact' ? 'quiet' : 'live');
}

$('copy').addEventListener('click', async () => {
  const c = $('place').dataset.coords ?? '';
  try { await navigator.clipboard.writeText(c); $('copyText').textContent = t('web_live_copied'); setTimeout(() => { $('copyText').textContent = t('web_live_copy'); }, 2000); }
  catch { /* expected: the clipboard is blocked – the coordinates stand under "Zuletzt" as text to read out */ }
});

async function boot() {
  const t0 = performance.now();
  try { engine = await loadEngine(base, lang); } catch (e) {
    console.error(e);
    $('line').textContent = t('web_live_server'); // the page's own files did not load (offline / an old browser)
    return;
  }
  const keys = JSON.parse(engine.liveKeys(link)) as { box: string; token: string } | null;
  box = new LiveBox(engine, link, base + 'live/v1/', keys, (s) => {
    state = s; render();
    if (s.head && !firstLine) { firstLine = performance.now() - t0; (window as unknown as { __live: object }).__live = hook(); }
  });
  (window as unknown as { __live: object }).__live = hook();
  if (keys) {
    try { map = await LiveMap.create(base, $('map'), $<HTMLButtonElement>('toPos')); }
    catch (e) { console.error(e); $('mapbox').hidden = true; } // expected: no WebGL – the words and numbers still tell everything
  }
  box.start();
  setInterval(render, 20_000); // "vor 3 Min." grows without asking the server
}
let firstLine = 0;
/** For the browser tests: what the page asked and shows. */
const hook = () => ({ get asks() { return box?.asks ?? 0; }, get planned() { return box?.planned ?? 0; }, get drawn() { return map?.stats; },
  get firstLineMs() { return firstLine; }, render,
  askNow: () => box?.askNow(), engine });
void boot();
