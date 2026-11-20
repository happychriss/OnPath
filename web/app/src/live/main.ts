// The live tour's viewer page (docs/design/live-tracking.md §3): the link's key comes after "#", is read once, taken out
// of the address bar and kept in memory only – a reload needs the link again. Everything shown is decided by the app's
// shared code in the engine (which status line wins, when to ask the server again, the numbers' format); this file only
// turns it into words and a map.
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

const root = document.getElementById('live')!;
root.innerHTML = `
  <header id="top"><div id="title"></div><div id="status" class="status" role="status"><span class="dot"></span><span id="line"></span></div></header>
  <div id="mapbox"><div id="map"></div><button id="toPos" class="topos" hidden></button></div>
  <section id="facts" hidden>
    <div id="numbers" class="row strong"></div>
    <div id="togo" class="row" hidden></div>
    <div id="battery" class="row" hidden></div>
    <div id="place" class="row" hidden><span id="placeText"></span>
      <span class="acts"><button id="copy" class="act"></button><a id="geo" class="act"></a></span></div>
  </section>
  <div id="note" class="note" hidden></div>
  <footer id="foot"></footer>`;
const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
document.title = t('web_live_page_title');
$('title').textContent = t('web_live_title');
$('line').textContent = t('web_live_loading');
$('foot').textContent = t('web_live_no_rescue');
$('toPos').textContent = t('web_live_to_position');
$('copy').textContent = t('web_live_copy');
$('geo').textContent = t('web_live_open_maps');

const clock = (ms: number) => new Intl.DateTimeFormat(lang, { hour: '2-digit', minute: '2-digit' }).format(new Date(ms));
const span = (min: number) => (min < 60 ? t('web_live_minutes', min) : t('web_live_hours', Math.floor(min / 60), String(min % 60).padStart(2, '0')));

let engine: LiveEngine | null = null;
let map: LiveMap | null = null;
let box: LiveBox | null = null;
let activities: Record<string, string> = {};
let state: BoxState | null = null;

/** The one line that matters, from the shared rule (LiveStatusLine): the words for its key. */
function words(l: StatusLine, hasPlace: boolean): string {
  switch (l.key) {
    case 'stopped': return t('web_live_stopped');
    case 'broken': return t('web_live_broken');
    case 'no_data': return t('web_live_no_data');
    case 'ended': return l.atMs != null ? t(l.arrived ? 'web_live_ended_arrived' : 'web_live_ended', clock(l.atMs)) : t('web_live_ended', '');
    case 'no_contact': return hasPlace && l.atMs != null ? t('web_live_no_contact', span(l.minutes ?? 0), clock(l.atMs)) : t('web_live_no_contact_plain', span(l.minutes ?? 0));
    case 'off_route': return t('web_live_off_route', `${l.metres ?? 0} m`);
    case 'pause': return t('web_live_pause', span(l.minutes ?? 0));
    case 'moving': return (l.minutes ?? 0) < 1 ? t('web_live_moving_now') : t('web_live_moving', l.minutes ?? 0);
  }
}

function render() {
  if (!engine || !state) return;
  const s = state, h = s.head;
  const since = s.sinceLastWrite() ?? 0;
  const line = JSON.parse(engine.liveStatus(link, s.headBlob, s.dead === 'gone', s.dead === 'broken', since, s.ended ?? -1)) as StatusLine;
  const dead = line.key === 'stopped' || line.key === 'broken';
  document.body.dataset.state = line.key;
  document.body.dataset.dead = dead ? '1' : '';
  const status = $('status');
  status.dataset.tone = dead || line.key === 'no_data' || line.key === 'ended' ? 'plain' : line.amber ? 'amber' : line.key === 'no_contact' ? 'grey' : line.key === 'pause' ? 'calm' : 'good';
  $('line').textContent = words(line, !!h?.fix);
  $('title').textContent = h?.name ? t('web_live_title_named', h.name) + (activities[h.activity] ? ' · ' + activities[h.activity] : '')
    : t('web_live_title') + (h && activities[h.activity] ? ' · ' + activities[h.activity] : '');
  const note = $('note');
  note.hidden = !s.unreachable || dead; note.textContent = t('web_live_server');
  $('facts').hidden = dead || !h;
  $('mapbox').hidden = dead;
  if (dead) { // nothing of the tour stays in the page – not in a hidden row either
    map?.clear();
    for (const id of ['numbers', 'togo', 'battery', 'placeText']) $(id).textContent = '';
    for (const id of ['togo', 'battery', 'place']) $(id).hidden = true;
    delete $('place').dataset.coords; $('geo').removeAttribute('href');
    return;
  }
  if (!h) return;
  $('numbers').textContent = `${h.distanceText} · ${h.movingText} · ↑ ${h.ascentText}`;
  const togo = $('togo');
  togo.hidden = !(h.toGoText && h.arrivalMs != null && line.key !== 'ended');
  if (!togo.hidden) togo.textContent = t('web_live_to_go', h.toGoText!, clock(h.arrivalMs!));
  const bat = $('battery');
  bat.hidden = h.batteryPct == null;
  if (h.batteryPct != null) { bat.textContent = t('web_live_battery', h.batteryPct); bat.dataset.low = h.batteryPct < 15 ? '1' : ''; }
  const place = $('place');
  place.hidden = !h.fix;
  if (h.fix) {
    const coords = `${h.fix.lat.toFixed(5)}, ${h.fix.lon.toFixed(5)}`;
    $('placeText').textContent = t('web_live_last_place', clock(h.fix.time)) + ' · ' + coords;
    place.dataset.coords = coords;
    ($('geo') as HTMLAnchorElement).href = `geo:${h.fix.lat.toFixed(5)},${h.fix.lon.toFixed(5)}?q=${h.fix.lat.toFixed(5)},${h.fix.lon.toFixed(5)}`;
  }
  map?.show(s.route, s.chunks, h.fix, line.key === 'ended' ? 'ended' : line.key === 'no_contact' ? 'quiet' : 'live');
}

$('copy').addEventListener('click', async () => {
  const c = $('place').dataset.coords ?? '';
  try { await navigator.clipboard.writeText(c); $('copy').textContent = t('web_live_copied'); setTimeout(() => { $('copy').textContent = t('web_live_copy'); }, 2000); }
  catch { /* expected: the clipboard is blocked – the coordinates stand there as text to read out */ }
});

async function boot() {
  const t0 = performance.now();
  try { engine = await loadEngine(base, lang); } catch (e) {
    console.error(e);
    $('line').textContent = t('web_live_server'); // the page's own files did not load (offline / an old browser)
    return;
  }
  try { activities = Object.fromEntries((JSON.parse(engine.activities()) as { key: string; label: string }[]).map((a) => [a.key, a.label])); }
  catch { /* expected: without the list the title simply has no activity word */ }
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
