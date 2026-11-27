// Round-trip suggestions in the planner (docs/design/tour-suggestions.md §7 "Stage 3 – the ready brief"): „Rundtour von
// hier“ under „Hier starten“ → the wish (sketch 5 B: activity as plain labels, time and ascent as two-handle range
// sliders, Ziel chips, Abbrechen · OK) → a wait card → the cards (sketch 4 A + the pills of sketch 7: badge, km · h · ↑,
// the text line, at most 2 green + 2 yellow pills) → „Übernehmen“ = a normal plan (the planner's plan-from-track path,
// round trip). On the map only the chosen card's loop (owner 2026-11-17). The cards (sketch 8, owner 2026-11-18 „Ja, so
// bauen“): side by side in one row along the map's bottom edge, the side panel stays the plan; on a phone a low strip
// with one card in front and the next looking in – swiping to a card or clicking it shows its loop. The method runs in
// the engine worker (WebSuggest.kt). On for everyone since 2026-11-18 (owner); ?debug → „Tourvorschläge: an“ is the
// off-switch (this browser only).
import type * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource } from 'maplibre-gl';
import type { EngineClient } from './engine/client';
import type { Activity } from './engine/types';
import type { ActivityRow } from './activity';
import type { MenuItem } from './menus';
import { t, tp, number } from './i18n';
import { problem } from './diag';
import './suggest.css';

type LatLon = [number, number];

const KEY = 'onpath.suggestTest';
/** The switch (this browser only): on unless the ?debug setting turned it off ('0'; an old '1' = on). */
export function suggestOn(): boolean {
  try { return localStorage.getItem(KEY) !== '0'; } catch { return true; } // expected: storage blocked = the default, on
}

/** ?debug: one small button that switches the feature on / off (reloads – the page sets it up at start). */
export function installSuggestSwitch(debug: boolean) {
  if (!debug) return;
  const b = document.createElement('button');
  b.id = 'suggestSwitch';
  b.type = 'button';
  b.className = 'suggestswitch';
  b.textContent = t(suggestOn() ? 'suggest_ui_test_on' : 'suggest_ui_test_off');
  b.onclick = () => {
    try { if (suggestOn()) localStorage.setItem(KEY, '0'); else localStorage.removeItem(KEY); }
    catch (e) { problem('suggest', 'test setting not stored', e); }
    location.reload();
  };
  document.body.append(b);
}

export interface SuggestHost {
  map: maplibregl.Map;
  engine: EngineClient;
  /** the map store root (cells/f2/10/…) – where the cells' suggest files are */
  cells: string;
  activity(): { activity: Activity; sac: number };
  setActivity(a: Activity, sac: number): void;
  /** the chosen loop as a plan (round trip) */
  plan(points: LatLon[], title: string): Promise<void>;
  fitPadding(): maplibregl.PaddingOptions;
  /** before the wish opens: menus closed, the plan empty or kept (the caller decides) */
  closeMenus(): void;
  /** a loop appeared on the map or went (the eye follows it – it can hide the loop, owner 2026-11-20) */
  loopChanged?(): void;
}
/** The suggestion's map drawings – what the eye hides while held (main.ts peek). */
export const SUGGEST_LAYERS = ['suggest-loop-casing', 'suggest-loop', 'suggest-start'] as const;

interface Pill { t: string; c: string }
interface Card { id: number; name: string; badge: string | null; loopKm: number | null; km: number; hours: number; ascent: number;
  line: string; pills: Pill[]; coords: LatLon[] }
interface Answer { cards?: Card[]; notes?: string[]; mapUpdating?: boolean; hours?: [number, number]; ascent?: [number, number]; error?: string }

const ZIEL = ['gipfel', 'wasser', 'aussicht', 'einkehr', 'kultur'] as const;
/** the chips' words (literal keys: gen-strings takes only the keys written out) */
const ZIEL_TEXT: Record<(typeof ZIEL)[number], string> = { gipfel: 'suggest_ziel_gipfel', wasser: 'suggest_ziel_wasser',
  aussicht: 'suggest_ziel_aussicht', einkehr: 'suggest_ziel_einkehr', kultur: 'suggest_ziel_kultur' };
/** The ranges' scales: time ½–8 h in ½ h, ascent 0–1500 m in 50 m (the top = no upper end). */
const H_MIN = 0.5, H_MAX = 8, H_STEP = 0.5, UP_MAX = 1500, UP_STEP = 50;
const isHike = (a: Activity) => a === 'HIKE';
/** Pre-set from the activity (sketch 5: Wandern 2–3 h, ↑ bis 400 m). */
const defaults = (a: Activity): { h: [number, number]; up: [number, number] } =>
  isHike(a) ? { h: [2, 3], up: [0, 400] } : a === 'MTB' || a === 'EMTB' ? { h: [2, 3], up: [0, 800] } : { h: [2, 3], up: [0, 600] };

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
const hm = (h: number) => `${Math.floor(h)}:${String(Math.round((h % 1) * 60) % 60).padStart(2, '0')}`;
const hText = (h: number) => (h % 1 === 0 ? String(h) : number(h, 1));

export function setupSuggest(host: SuggestHost) {
  const { map } = host;
  let panel: HTMLElement | null = null;
  let stripSize: ResizeObserver | null = null;
  let wish: { activity: Activity; sac: number; h: [number, number]; up: [number, number]; ziel: Set<string>; at: LatLon; name?: string } | null = null;
  let cards: Card[] = [];
  let chosen = -1;
  let run = 0;
  /** the planner's activities with their plain labels (the app's Activity.label – no icons, owner 2026-11-17) */
  let rows: ActivityRow[] = [];
  host.engine.call('activities').then((x) => { rows = JSON.parse(x) as ActivityRow[]; }).catch((e) => problem('suggest', 'activity names not loaded', e));

  function ensureLayer() {
    if (map.getSource('suggest-loop')) return;
    map.addSource('suggest-loop', { type: 'geojson', data: empty() });
    map.addLayer({ id: 'suggest-loop-casing', type: 'line', source: 'suggest-loop', filter: ['==', '$type', 'LineString'],
      layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#ffffff', 'line-width': 8 } });
    map.addLayer({ id: 'suggest-loop', type: 'line', source: 'suggest-loop', filter: ['==', '$type', 'LineString'],
      layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#335A85', 'line-width': 5 } });
    map.addLayer({ id: 'suggest-start', type: 'circle', source: 'suggest-loop', filter: ['==', '$type', 'Point'],
      paint: { 'circle-radius': 7, 'circle-color': '#245F46', 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 3 } });
  }
  function drawMap(fit: boolean) {
    try {
      ensureLayer();
      const c = cards[chosen];
      const feats: GeoJSON.Feature[] = [];
      if (wish) feats.push({ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [wish.at[1], wish.at[0]] } });
      if (c) feats.push({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: c.coords.map(([la, lo]) => [lo, la]) } });
      (map.getSource('suggest-loop') as GeoJSONSource).setData({ type: 'FeatureCollection', features: feats });
      if (fit && c && c.coords.length) {
        const lo = c.coords.map((p) => p[1]), la = c.coords.map((p) => p[0]);
        map.fitBounds([[Math.min(...lo), Math.min(...la)], [Math.max(...lo), Math.max(...la)]], { padding: padding(), maxZoom: 15, duration: 300 });
      }
    } catch (e) { problem('suggest', 'loop not drawn', e); }
    host.loopChanged?.();
  }

  /** The planner's fit padding, its bottom clear of the cards' strip (the loop above it, never under it). */
  function padding(): maplibregl.PaddingOptions {
    const p = { ...host.fitPadding() };
    const stage = map.getContainer().parentElement?.getBoundingClientRect();
    if (panel?.id === 'suggestStrip' && stage) {
      const r = panel.getBoundingClientRect();
      // at least 120 px of map stay between the paddings, or fitBounds does not move at all
      const room = Math.max(0, stage.height - (p.top ?? 0) - 120);
      p.bottom = Math.min(Math.max(p.bottom ?? 0, stage.bottom - r.top + 24), room);
    }
    return p;
  }

  /** The panel (in the sheet) or the strip (over the map) goes; the sheet is the plan again. */
  function unmount() {
    panel?.remove(); panel = null;
    document.getElementById('sheet')?.classList.remove('suggest');
    document.body.classList.remove('suggest-strip');
    stripSize?.disconnect(); stripSize = null;
  }

  function close() {
    run++;
    unmount();
    cards = []; chosen = -1;
    (map.getSource('suggest-loop') as GeoJSONSource | undefined)?.setData(empty());
    host.loopChanged?.();
  }

  function mount(html: string): HTMLElement {
    const sheet = document.getElementById('sheet')!;
    unmount();
    const p = document.createElement('div');
    p.id = 'suggestPanel';
    p.innerHTML = html;
    const handle = document.getElementById('handle');
    if (handle?.parentElement === sheet) handle.after(p); else sheet.prepend(p); // under the sheet's grab handle
    sheet.classList.add('suggest');
    panel = p;
    return p;
  }

  const activityLabel = (a: Activity, sac: number) => {
    const row = rows.find((r) => r.key === a);
    return `${row?.label ?? a}${isHike(a) ? ' · T' + sac : ''}`;
  };
  const hoursText = (h: [number, number]) => t('suggest_ui_hours', hText(h[0]), hText(h[1]));
  const upText = (up: [number, number]) => up[0] <= 0 && up[1] >= UP_MAX ? t('suggest_ui_ascent_any')
    : up[0] <= 0 ? t('suggest_ui_ascent_upto', up[1]) : up[1] >= UP_MAX ? t('suggest_ui_ascent_from', up[0]) : t('suggest_ui_ascent_range', up[0], up[1]);

  /** Two-handle range: two range inputs over one track (the lower can't pass the upper). */
  const range = (id: string, min: number, max: number, step: number, v: [number, number]) => {
    const name = esc(t(id === 'sgTime' ? 'suggest_ui_time' : 'suggest_ui_ascent'));
    return `<div class="sgrange" id="${id}"><div class="sgtrack"></div><div class="sgfill"></div>
      <input type="range" class="lo" min="${min}" max="${max}" step="${step}" value="${v[0]}" aria-label="${name} min">
      <input type="range" class="hi" min="${min}" max="${max}" step="${step}" value="${v[1]}" aria-label="${name} max"></div>`;
  };

  function openWish(at: LatLon, name?: string) {
    host.closeMenus();
    const cur = host.activity();
    const keep = wish && wish.at[0] === at[0] && wish.at[1] === at[1] ? wish : null;
    const d = defaults(cur.activity);
    wish = keep ?? { activity: cur.activity, sac: cur.sac, h: d.h, up: d.up, ziel: new Set(), at, name };
    const w = wish;
    const acts = rows.length ? rows : [{ key: w.activity, label: w.activity, line: '', chip: '' } as ActivityRow];
    const p = mount(`<div class="sghead"><b>${esc(t('suggest_ui_from_here'))}</b></div>
      <div class="sgrow"><span class="sglbl">${esc(t('suggest_ui_activity'))}</span><span class="sgfield">
        <select id="sgActivity" class="sgselect">${acts.map((r) => `<option value="${r.key}"${r.key === w.activity ? ' selected' : ''}>${esc(r.label)}</option>`).join('')}</select>
        <select id="sgSac" class="sgselect"${isHike(w.activity) ? '' : ' hidden'}>${[1, 2, 3, 4, 5].map((s) => `<option value="${s}"${s === w.sac ? ' selected' : ''}>T${s}</option>`).join('')}</select></span></div>
      <div class="sgrow"><span class="sglbl">${esc(t('suggest_ui_time'))}</span>${range('sgTime', H_MIN, H_MAX, H_STEP, w.h)}<span class="sgval" id="sgTimeVal"></span></div>
      <div class="sgrow"><span class="sglbl">${esc(t('suggest_ui_ascent'))}</span>${range('sgUp', 0, UP_MAX, UP_STEP, w.up)}<span class="sgval" id="sgUpVal"></span></div>
      <div class="sgrow top"><span class="sglbl">${esc(t('suggest_ui_ziel'))}</span><span class="sgchips">${ZIEL.map((z) =>
        `<button type="button" class="sgz${w.ziel.has(z) ? ' on' : ''}" data-z="${z}" aria-pressed="${w.ziel.has(z)}">${esc(t(ZIEL_TEXT[z]))}</button>`).join('')}</span></div>
      <div class="sgfoot"><button type="button" class="sgtext" id="sgCancel">${esc(t('common_cancel'))}</button>
        <button type="button" class="sgok" id="sgOk">${esc(t('common_ok'))}</button></div>`);
    const q = <T extends HTMLElement>(s: string) => p.querySelector<T>(s)!;
    const wire = (id: string, get: () => [number, number], set: (v: [number, number]) => void, label: () => string) => {
      const box = q<HTMLDivElement>('#' + id), lo = box.querySelector<HTMLInputElement>('.lo')!, hi = box.querySelector<HTMLInputElement>('.hi')!;
      const fill = box.querySelector<HTMLDivElement>('.sgfill')!, val = q<HTMLSpanElement>('#' + id + 'Val');
      const min = Number(lo.min), max = Number(lo.max);
      const show = () => {
        const [a, b] = get();
        fill.style.left = `calc(${((a - min) / (max - min)) * 100}% )`;
        fill.style.width = `${((b - a) / (max - min)) * 100}%`;
        val.textContent = label();
      };
      lo.oninput = () => { const v = Math.min(Number(lo.value), Number(hi.value)); lo.value = String(v); set([v, get()[1]]); show(); };
      hi.oninput = () => { const v = Math.max(Number(hi.value), Number(lo.value)); hi.value = String(v); set([get()[0], v]); show(); };
      show();
    };
    wire('sgTime', () => w.h, (v) => { w.h = v; }, () => hoursText(w.h));
    wire('sgUp', () => w.up, (v) => { w.up = v; }, () => upText(w.up));
    q<HTMLSelectElement>('#sgActivity').onchange = (e) => {
      w.activity = (e.target as HTMLSelectElement).value as Activity;
      q<HTMLSelectElement>('#sgSac').hidden = !isHike(w.activity);
    };
    q<HTMLSelectElement>('#sgSac').onchange = (e) => { w.sac = Number((e.target as HTMLSelectElement).value); };
    p.querySelectorAll<HTMLButtonElement>('.sgz').forEach((b) => b.onclick = () => {
      const z = b.dataset.z!;
      if (w.ziel.has(z)) w.ziel.delete(z); else w.ziel.add(z);
      b.classList.toggle('on', w.ziel.has(z)); b.setAttribute('aria-pressed', String(w.ziel.has(z)));
    });
    q<HTMLButtonElement>('#sgCancel').onclick = () => { close(); wish = null; };
    q<HTMLButtonElement>('#sgOk').onclick = () => void find();
    drawMap(false);
  }

  const summary = () => wish ? [activityLabel(wish.activity, wish.sac), hoursText(wish.h), '↑ ' + upText(wish.up)].join(' · ') : '';

  async function find() {
    if (!wish) return;
    const w = wish;
    const mine = ++run;
    const p = mount(`<div class="sgwait"><span class="msgspin" aria-hidden="true"></span><span id="sgWaitText">${esc(t('suggest_ui_wait'))}</span></div>
      <div class="sgsum muted small">${esc(summary())}</div>
      <div class="sgfoot"><button type="button" class="sgtext" id="sgCancel">${esc(t('common_cancel'))}</button></div>`);
    p.querySelector<HTMLButtonElement>('#sgCancel')!.onclick = () => { close(); };
    const body = {
      lat: w.at[0], lon: w.at[1], activity: w.activity, sac: w.sac, hours: w.h,
      ascent: w.up[0] <= 0 && w.up[1] >= UP_MAX ? null : [w.up[0] <= 0 ? null : w.up[0], w.up[1] >= UP_MAX ? null : w.up[1]],
      ziel: ZIEL.filter((z) => w.ziel.has(z)),
    };
    let r: Answer;
    try {
      r = JSON.parse(await host.engine.suggest(body, host.cells, (pr) => {
        if (mine !== run || pr.phase !== 'download') return;
        const el = document.getElementById('sgWaitText');
        if (el && pr.bytes > 0) el.textContent = t('suggest_ui_loading', Math.round((100 * pr.done) / pr.bytes));
      }));
    } catch (e) { problem('suggest', 'suggestions failed', e); r = { error: String(e) }; }
    if (mine !== run) return; // cancelled or a newer wish
    if (r.error) { showError(); return; }
    cards = r.cards ?? [];
    chosen = cards.length ? 0 : -1;
    showCards(r);
    drawMap(true);
  }

  function showError() {
    const p = mount(`<div class="sghead"><b>${esc(t('suggest_ui_from_here'))}</b>
        <button type="button" class="iconbtn" id="sgClose" aria-label="${esc(t('common_close'))}">✕</button></div>
      <div class="sgnote">${esc(t('suggest_ui_failed'))}</div>
      <div class="sgfoot"><button type="button" class="sgtext" id="sgChange">${esc(t('suggest_ui_change'))}</button></div>`);
    p.querySelector<HTMLButtonElement>('#sgClose')!.onclick = () => close();
    p.querySelector<HTMLButtonElement>('#sgChange')!.onclick = () => wish && openWish(wish.at, wish.name);
  }

  /**
   * The cards (sketch 8): one row along the bottom of the map area – a small head „3 Rundtouren · Wunsch ändern · ✕“,
   * the cards side by side (scroll-snap), dots on a phone. Clicking a card, the arrow keys, or – where one card is in
   * front at a time (a phone) – swiping to it chooses it: its loop alone on the map, the camera above the strip.
   */
  function showCards(r: Answer) {
    unmount();
    const p = document.createElement('section');
    p.id = 'suggestStrip';
    p.setAttribute('aria-label', tp('suggest_ui_count', cards.length, cards.length));
    p.innerHTML = `<div class="sghead"><b>${esc(tp('suggest_ui_count', cards.length, cards.length))}</b>
        <span class="sgsum">${esc(summary())}</span><span class="sgsp"></span>
        <button type="button" class="sgtext" id="sgChange">${esc(t('suggest_ui_change'))}</button>
        <button type="button" class="iconbtn" id="sgClose" aria-label="${esc(t('common_close'))}">✕</button></div>
      ${(r.notes ?? []).map((n) => `<div class="sgnote">${esc(n)}</div>`).join('')}
      <div class="sgcards" role="list"></div>
      <div class="sgdots" aria-hidden="true">${cards.length > 1 ? cards.map(() => '<i></i>').join('') : ''}</div>`;
    map.getContainer().parentElement!.append(p);
    document.body.classList.add('suggest-strip');
    panel = p;
    // its height for the map's credits above it (--strip-h)
    stripSize = new ResizeObserver(() => document.body.style.setProperty('--strip-h', `${p.offsetHeight}px`));
    stripSize.observe(p);
    p.querySelector<HTMLButtonElement>('#sgClose')!.onclick = () => close();
    p.querySelector<HTMLButtonElement>('#sgChange')!.onclick = () => wish && openWish(wish.at, wish.name);
    const row = p.querySelector<HTMLDivElement>('.sgcards')!;
    const dots = [...p.querySelectorAll<HTMLElement>('.sgdots i')];
    const els: HTMLDivElement[] = [];
    const mark = () => {
      els.forEach((el, k) => { el.classList.toggle('on', k === chosen); el.setAttribute('aria-current', String(k === chosen)); });
      dots.forEach((el, k) => el.classList.toggle('on', k === chosen));
    };
    /** one card in front at a time (a phone): the row's scroll position is the choice */
    const oneAtATime = () => els.length > 0 && els[0].offsetWidth > row.clientWidth / 2;
    const front = (i: number, smooth = true) => {
      const el = els[i];
      if (!el) return;
      const pad = parseFloat(getComputedStyle(row).paddingLeft) || 0;
      const left = el.offsetLeft - els[0].offsetLeft, right = left + el.offsetWidth + 2 * pad;
      // wide: only when the card is not wholly in view; a phone: always to the front
      if (oneAtATime() || left < row.scrollLeft || right > row.scrollLeft + row.clientWidth)
        row.scrollTo({ left, behavior: smooth ? 'smooth' : 'auto' });
    };
    const pick = (i: number, scroll = true) => {
      if (i < 0 || i >= cards.length) return;
      if (scroll) front(i);
      if (chosen === i) return;
      chosen = i;
      mark();
      drawMap(true);
    };
    cards.forEach((c, i) => {
      const d = document.createElement('div');
      d.className = 'sgcard';
      d.setAttribute('role', 'listitem');
      d.tabIndex = 0;
      d.innerHTML = `<div class="sgtop"><span class="sgbadge ${c.badge ? 'signed' : 'own'}">${esc(c.badge ? t('suggest_ui_badge_signed', c.badge) : t('suggest_ui_badge_own'))}</span>
          <button type="button" class="sgtake">${esc(t('suggest_ui_take'))}</button></div>
        <div class="sgname">${esc(c.name)}</div>
        <div class="sgstats">${esc(`${number(c.km, 1)} km · ${hm(c.hours)} h · ↑ ${c.ascent} m`)}${c.loopKm != null
          ? ` <span class="sgfrom">· ${esc(t('suggest_ui_on_loop', number(c.loopKm, 1)))}</span>` : ''}</div>
        ${c.line ? `<div class="sgline">${esc(c.line)}</div>` : ''}
        ${c.pills.length ? `<div class="sgpills">${c.pills.map((x) => `<span class="sgpill ${x.c}">${esc(x.t)}</span>`).join('')}</div>` : ''}`;
      d.onclick = () => pick(i);
      d.querySelector<HTMLButtonElement>('.sgtake')!.onclick = (e) => { e.stopPropagation(); void take(c, e.currentTarget as HTMLButtonElement); };
      row.append(d);
      els.push(d);
    });
    row.onkeydown = (e) => {
      if (e.target instanceof HTMLButtonElement) return;
      const here = els.indexOf((e.target as HTMLElement).closest('.sgcard') as HTMLDivElement);
      const i = e.key === 'ArrowRight' ? chosen + 1 : e.key === 'ArrowLeft' ? chosen - 1 : e.key === 'Enter' || e.key === ' ' ? here : -1;
      if (i < 0 || i >= cards.length) return;
      e.preventDefault();
      pick(i);
      els[i].focus({ preventScroll: true });
    };
    // a swipe: where the row comes to rest is the card in front (only where one card is in front at a time)
    let rest = 0;
    row.addEventListener('scroll', () => {
      clearTimeout(rest);
      rest = window.setTimeout(() => {
        if (!oneAtATime()) return;
        const step = els.length > 1 ? els[1].offsetLeft - els[0].offsetLeft : 1;
        const atEnd = row.scrollLeft > 0 && row.scrollLeft >= row.scrollWidth - row.clientWidth - 2;
        pick(atEnd ? els.length - 1 : Math.round(row.scrollLeft / step), false);
      }, 90);
    }, { passive: true });
    mark();
    front(chosen, false);
  }

  /** „Übernehmen“: the loop as a normal plan – the planner's plan-from-track path (TrackToPlan), closed as a round trip. */
  async function take(c: Card, btn: HTMLButtonElement) {
    if (!wish) return;
    const w = wish;
    btn.disabled = true; btn.classList.add('busy'); btn.textContent = t('suggest_ui_taking');
    const mine = ++run;
    let pts: LatLon[] | undefined;
    try {
      const r = JSON.parse(await host.engine.planFromTrack(c.coords, w.activity, w.sac)) as { points?: LatLon[]; error?: string };
      pts = r.points;
    } catch (e) { problem('suggest', 'loop not turned into a plan', e); }
    if (mine !== run) return;
    if (!pts || pts.length < 2) { btn.disabled = false; btn.classList.remove('busy'); btn.textContent = t('suggest_ui_failed'); return; }
    // a round trip: it ends where it starts (the planner's round-trip toggle closes it the same way)
    const [a, b] = [pts[0], pts[pts.length - 1]];
    if (a[0] !== b[0] || a[1] !== b[1]) pts = [...pts, a];
    const act = host.activity();
    if (act.activity !== w.activity || act.sac !== w.sac) host.setActivity(w.activity, w.sac);
    close();
    wish = null;
    await host.plan(pts, c.name);
  }

  /** „Rundtour ab hier“ – one row, the last of the route group wherever „Als Start“ is offered (owner 2026-11-26). */
  function menuRow(at: LatLon, name?: string): MenuItem {
    return { icon: 'loop', label: t('suggest_ui_from_here'), key: 'ROUND_TRIP_HERE', group: 'ROUTE', divider: true /* set apart by a thin line (owner 2026-11-27) */, act: () => openWish([Number(at[0].toFixed(6)), Number(at[1].toFixed(6))], name) };
  }

  /** [items] with the row put right after row [afterIndex] (the last route row – owner 2026-11-26); unchanged when that row is not there. */
  function withRow(items: MenuItem[], afterIndex: number, at: LatLon, name?: string): MenuItem[] {
    if (afterIndex < 0 || afterIndex >= items.length) return items;
    return [...items.slice(0, afterIndex + 1), menuRow(at, name), ...items.slice(afterIndex + 1)];
  }

  return { menuRow, withRow, close, active: () => panel != null, openWish, loopShown: () => !!cards[chosen] };
}

export type SuggestUi = ReturnType<typeof setupSuggest>;

const empty = (): GeoJSON.FeatureCollection => ({ type: 'FeatureCollection', features: [] });
