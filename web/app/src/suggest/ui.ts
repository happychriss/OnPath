// Tour suggestions in the planner – the surface (sketch 2, the owner's flow of 2026-11-13; NOT SWITCHED ON: this
// file is loaded only when registry.ts suggestEnabled() says so, see main.ts):
//   first tap into an empty plan → the menu's row "Rundtour von hier" → the wish (wide: in the plan sheet; phone: a
//   window like the Save-name dialog; Abbrechen · OK) → the wait card → three loops on the map + three cards →
//   "Übernehmen" = an ordinary plan through the page's own commit (undo, share, save, phone sync untouched).
// The module never touches the plan itself: everything it changes on the page (its panel in the sheet, one map source,
// one marker, one message) is removed again by [leave]; the only way into the plan is host.adopt().
//
// UNDECIDED by the owner (my recommendations, marked in docs/design/tour-suggestions.md):
//   - the row is offered only while the plan is empty;
//   - the same row in the menu of a place / map symbol, under "Hierhin";
//   - the small line under the row ("Runden nach Dauer und Geschmack").
import * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource } from 'maplibre-gl';
import type { Activity } from '../engine/types';
import type { MenuItem } from '../menus';
import { icon } from '../icons';
import { t, tp, lang, number } from '../i18n';
import { post, dismiss } from '../msgcard';
import { chosenProvider, providers } from './registry';
import { SuggestSession, type SessionEngine } from './session';
import { surfaceOptions, type Effort, type LatLon, type Level, type SuggestEvent, type Suggestion, type TourWish } from './types';
import './suggest.css';

export interface SuggestHost {
  map: maplibregl.Map;
  engine: SessionEngine;
  sheet: HTMLElement;
  activity(): Activity;
  sac(): number;
  /** The activity chip's words ("Wandern · T2"). */
  activityLabel(): string;
  planEmpty(): boolean;
  phoneWidth(): boolean;
  /** The middle of the free map (page pixels) – where the planner's wait box sits. */
  mapMiddle(): { x: number; y: number };
  /** Map padding that keeps a fitted line clear of the sheet and the bars. */
  fitPadding(): maplibregl.PaddingOptions;
  closeMenus(): void;
  /** The one way into the plan: the page's own commit (history, plan run, share code, saving – as for any edit). */
  adopt(points: LatLon[]): Promise<void>;
}

export interface SuggestUi {
  /** The menu row for a start at [at] (a map point, or a place with its [name]); null when it is not offered. */
  menuItem(at: LatLon, name?: string | null): MenuItem | null;
  /** The wish or the suggestions are on screen. */
  active(): boolean;
  /** A click on the map while active: a suggestion's line under it is selected. */
  mapPress(pt: maplibregl.Point): void;
  /** The plan got points from somewhere else (a link, Meine Touren, the search): the suggestions go without a word. */
  planTouched(): void;
}

const COLORS = ['#1E3FAE', '#8E24AA', '#C2570B', '#00796B', '#6D4C41'];
const COUNT = 3;
const STORE = 'onpath.suggest.wish';
const DURATIONS = [1, 2, 3, 4];
const LEVELS: Level[] = ['low', 'mid', 'high'];
const EFFORTS: Effort[] = ['easy', 'medium', 'sporty'];
const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);

type Remembered = Pick<TourWish, 'hours' | 'effort' | 'nature' | 'quiet' | 'known' | 'water' | 'tips'> & { surface: Record<string, string> };
const DEFAULTS: Remembered = { hours: 2, effort: 'easy', nature: 'high', quiet: 'mid', known: 'mid', water: false, tips: false, surface: {} };

function remembered(): Remembered {
  try { return { ...DEFAULTS, ...(JSON.parse(localStorage.getItem(STORE) ?? '{}') as Partial<Remembered>) }; }
  catch { return { ...DEFAULTS }; } // expected: storage blocked or an old value – the defaults
}
function remember(r: Remembered) {
  try { localStorage.setItem(STORE, JSON.stringify(r)); } catch { /* expected: storage blocked – kept for this page only */ }
}

export function setupSuggest(host: SuggestHost): SuggestUi {
  const session = new SuggestSession(host.engine, () => ({
    searching: t('web_suggest_searching'), computing: (n, of) => t('web_suggest_computing', n, of), loadingMap: t('maps_load_loading'),
    shorter: t('web_suggest_shorter'), longer: t('web_suggest_longer'), failed: t('web_suggest_failed'), lang,
  }));
  const provider = () => chosenProvider(providers());

  let mode: 'off' | 'wish' | 'results' = 'off';
  let wish: TourWish | null = null;
  let vals = remembered();
  let seed = 1;
  let found: Suggestion[] = [];
  let selected = 0;
  let over = false; // the run has ended
  let panel: HTMLElement | null = null;
  let dlg: HTMLDialogElement | null = null;
  let marker: maplibregl.Marker | null = null;
  let chipWatch: MutationObserver | null = null;

  // ---- the map: one source, the lines of the suggestions ----
  function drawLines() {
    const map = host.map;
    if (!map.getSource('suggest-lines')) {
      if (!found.length) return;
      map.addSource('suggest-lines', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      const before = map.getLayer('planned-route-casing') ? 'planned-route-casing' : undefined;
      map.addLayer({ id: 'suggest-casing', type: 'line', source: 'suggest-lines', layout: { 'line-cap': 'round', 'line-join': 'round', 'line-sort-key': ['get', 'sel'] },
        paint: { 'line-color': '#fff', 'line-width': ['case', ['==', ['get', 'sel'], 1], 8, 5], 'line-opacity': 0.9 } }, before);
      map.addLayer({ id: 'suggest-line', type: 'line', source: 'suggest-lines', layout: { 'line-cap': 'round', 'line-join': 'round', 'line-sort-key': ['get', 'sel'] },
        paint: { 'line-color': ['get', 'color'], 'line-width': ['case', ['==', ['get', 'sel'], 1], 5, 2.5], 'line-opacity': ['case', ['==', ['get', 'sel'], 1], 1, 0.75] } }, before);
    }
    (map.getSource('suggest-lines') as GeoJSONSource).setData({ type: 'FeatureCollection', features: found.map((s, i) => ({
      type: 'Feature' as const, properties: { i, color: COLORS[i % COLORS.length], sel: i === selected ? 1 : 0 },
      geometry: { type: 'MultiLineString' as const, coordinates: s.plan.legs.map((l) => l.points.map(([la, lo]) => [lo, la])) } })) });
  }
  function clearLines() {
    const map = host.map;
    for (const id of ['suggest-line', 'suggest-casing']) if (map.getLayer(id)) map.removeLayer(id);
    if (map.getSource('suggest-lines')) map.removeSource('suggest-lines');
  }
  // a style change (the activity's map look) drops added layers: draw them again
  host.map.on('styledata', () => { if (mode === 'results' && found.length && !host.map.getSource('suggest-lines')) drawLines(); });

  function fitAll() {
    if (!found.length) return;
    const b = new maplibregl.LngLatBounds();
    for (const s of found) for (const l of s.plan.legs) for (const [la, lo] of l.points) b.extend([lo, la]);
    host.map.fitBounds(b, { padding: host.fitPadding(), maxZoom: 15, duration: 500 });
  }

  function showStart(at: LatLon) {
    marker?.remove();
    const el = document.createElement('div');
    el.className = 'suggeststart';
    marker = new maplibregl.Marker({ element: el }).setLngLat([at[1], at[0]]).addTo(host.map);
  }

  /** Everything this module put on the page goes; the plan is as it was. */
  function leave() {
    session.cancel();
    dismiss('suggest');
    chipWatch?.disconnect(); chipWatch = null;
    if (dlg?.open) { const d = dlg; dlg = null; d.close(); }
    dlg = null;
    panel?.remove(); panel = null;
    host.sheet.classList.remove('suggesting', 'suggest-wish', 'suggest-results');
    marker?.remove(); marker = null;
    clearLines();
    found = []; selected = 0; over = false; wish = null;
    mode = 'off';
    delete document.body.dataset.suggest;
  }

  // ---- the wish ----
  const seg = (name: string, options: [string, string][], value: string) =>
    `<div class="seg sg" role="group" data-name="${name}">${options.map(([v, label]) =>
      `<button type="button" data-v="${esc(v)}" class="${v === value ? 'on' : ''}" aria-pressed="${v === value}">${esc(label)}</button>`).join('')}</div>`;

  function wishHtml(name: string | null): string {
    const a = host.activity();
    const sopts = surfaceOptions(a);
    const surface = sopts.includes(vals.surface[a] ?? '') ? vals.surface[a] : sopts[0];
    const levels = (key: 'nature' | 'quiet' | 'known') => seg(key, LEVELS.map((l) => [l, t('web_suggest_level_' + l)]), vals[key]);
    const dur = DURATIONS.includes(vals.hours) ? String(vals.hours) : '';
    const via = provider();
    return `
      <div class="sghead"><h2>${esc(t('web_suggest_from_here'))}</h2>
        ${name ? `<div class="sgplace muted small"><i class="sgdot" aria-hidden="true"></i>${esc(name)}</div>` : ''}</div>
      <div class="sgrow sgact"><span class="sgactivity">${esc(host.activityLabel())}</span><span class="muted small">${esc(t('web_suggest_pace_note'))}</span></div>
      <div class="sglabel muted small">${esc(t('web_suggest_duration'))}</div>
      ${seg('hours', [['1', t('web_suggest_hours', 1)], ['2', t('web_suggest_hours', 2)], ['3', t('web_suggest_hours', 3)], ['4', t('web_suggest_half_day')]], dur)}
      <div class="sgfine"><button type="button" class="sgstep" data-d="-0.25" aria-label="−">−</button><output class="sgtime">${esc(hm(vals.hours))}</output><button type="button" class="sgstep" data-d="0.25" aria-label="+">+</button></div>
      <div class="sglabel muted small">${esc(t('web_suggest_important'))}</div>
      <div class="sglevels">
        <span>${esc(t('web_suggest_nature'))}</span>${levels('nature')}
        <span>${esc(t('web_suggest_quiet'))}</span>${levels('quiet')}
        <span>${esc(t('web_suggest_known'))}</span>${levels('known')}
      </div>
      <label class="sgcheck"><input type="checkbox" name="water" ${vals.water ? 'checked' : ''}><span>${esc(t('web_suggest_water'))}</span></label>
      <div class="sglabel muted small">${esc(t('web_suggest_surface'))}</div>
      ${seg('surface', sopts.map((s) => [s, t('web_suggest_surface_' + s)]), surface)}
      <details class="sgmore"><summary>${esc(t('web_suggest_more'))} <span class="muted small">${esc(t('web_suggest_effort'))}</span></summary>
        <div class="sglabel muted small">${esc(t('web_suggest_effort'))}</div>
        ${seg('effort', EFFORTS.map((e) => [e, t('web_suggest_effort_' + e)]), vals.effort)}
        <label class="sgcheck"><input type="checkbox" name="tips" ${vals.tips ? 'checked' : ''}><span>${esc(t('web_suggest_tips'))}</span></label>
        <div class="sgvia muted small">${esc(t('web_suggest_via', via.devName))}</div>
      </details>
      <div class="row end sgbuttons"><button type="button" class="sgcancel">${esc(t('common_cancel'))}</button><button type="button" class="primary sgok">${esc(t('common_ok'))}</button></div>`;
  }
  const hm = (h: number) => `${Math.floor(h)}:${String(Math.round((h % 1) * 60)).padStart(2, '0')} h`;

  function wire(root: HTMLElement, start: LatLon, name: string | null) {
    const read = () => {
      const a = host.activity();
      root.querySelectorAll<HTMLElement>('.sg').forEach((g) => {
        const on = g.querySelector<HTMLButtonElement>('button.on')?.dataset.v;
        if (on == null) return;
        const n = g.dataset.name!;
        if (n === 'hours') vals.hours = Number(on);
        else if (n === 'surface') vals.surface = { ...vals.surface, [a]: on };
        else (vals as unknown as Record<string, string>)[n] = on;
      });
      vals.water = !!root.querySelector<HTMLInputElement>('input[name=water]')?.checked;
      vals.tips = !!root.querySelector<HTMLInputElement>('input[name=tips]')?.checked;
    };
    root.querySelectorAll<HTMLElement>('.sg').forEach((g) => g.addEventListener('click', (e) => {
      const b = (e.target as HTMLElement).closest('button');
      if (!b) return;
      g.querySelectorAll('button').forEach((x) => { const on = x === b; x.classList.toggle('on', on); x.setAttribute('aria-pressed', String(on)); });
      if (g.dataset.name === 'hours') { vals.hours = Number(b.dataset.v); root.querySelector('.sgtime')!.textContent = hm(vals.hours); }
    }));
    root.querySelectorAll<HTMLButtonElement>('.sgstep').forEach((b) => (b.onclick = () => {
      vals.hours = Math.min(10, Math.max(0.5, Math.round((vals.hours + Number(b.dataset.d)) * 4) / 4));
      root.querySelector('.sgtime')!.textContent = hm(vals.hours);
      root.querySelectorAll<HTMLButtonElement>('.sg[data-name=hours] button').forEach((x) => { const on = Number(x.dataset.v) === vals.hours; x.classList.toggle('on', on); x.setAttribute('aria-pressed', String(on)); });
    }));
    root.querySelector<HTMLButtonElement>('.sgcancel')!.onclick = () => leave();
    root.querySelector<HTMLButtonElement>('.sgok')!.onclick = () => {
      read();
      remember(vals);
      const a = host.activity(), sopts = surfaceOptions(a);
      wish = { start, startName: name, activity: a, sac: host.sac(), hours: vals.hours, pace: 'model', effort: vals.effort, nature: vals.nature, quiet: vals.quiet,
        known: vals.known, water: vals.water, surface: sopts.includes(vals.surface[a] ?? '') ? vals.surface[a] : sopts[0], seed, tips: vals.tips };
      run();
    };
  }

  function openWish(start: LatLon, name: string | null, keep = false) {
    host.closeMenus();
    if (!keep) { leave(); vals = remembered(); seed = 1; }
    else { session.cancel(); dismiss('suggest'); panel?.remove(); panel = null; clearLines(); found = []; selected = 0; over = false; }
    mode = 'wish';
    document.body.dataset.suggest = 'wish';
    showStart(start);
    host.sheet.classList.remove('suggest-results');
    if (host.phoneWidth()) {
      // the phone: a window like the Save-name dialog (Abbrechen · OK); Esc / the backdrop's back = Abbrechen
      const d = document.createElement('dialog');
      d.className = 'share suggestwish';
      d.innerHTML = wishHtml(name);
      document.body.append(d);
      d.addEventListener('close', () => { d.remove(); if (dlg === d) { dlg = null; if (mode === 'wish') leave(); } });
      dlg = d;
      wire(d, start, name);
      d.showModal();
    } else {
      const p = document.createElement('div');
      p.id = 'suggestPanel';
      p.className = 'suggestpanel wish';
      const draw = () => { p.innerHTML = wishHtml(name); wire(p, start, name); };
      draw();
      host.sheet.prepend(p);
      panel = p;
      host.sheet.classList.add('suggesting', 'suggest-wish');
      // the activity chip stays the sheet's own (above the wish); a change redraws the surface line
      const chip = document.getElementById('activityChip');
      if (chip) { chipWatch = new MutationObserver(() => { if (mode === 'wish' && panel === p) draw(); }); chipWatch.observe(chip, { childList: true, subtree: true, characterData: true }); }
    }
  }

  // ---- the run: wait card, then the cards ----
  function waitCard(text: string) {
    post({ id: 'suggest', kind: 'hinweis', busy: true, tone: 'edit', title: text, detail: found.length ? t('web_suggest_found', found.length, COUNT) : undefined,
      buttons: [{ label: t('common_cancel'), act: () => leave() }], at: host.mapMiddle });
  }

  function run() {
    if (!wish) return;
    const w = wish;
    chipWatch?.disconnect(); chipWatch = null;
    if (dlg?.open) { const d = dlg; dlg = null; d.close(); }
    panel?.remove(); panel = null;
    host.sheet.classList.remove('suggest-wish');
    clearLines();
    found = []; selected = 0; over = false;
    mode = 'results';
    document.body.dataset.suggest = 'results';
    showStart(w.start);
    drawPanel();
    waitCard(t('web_suggest_searching'));
    void session.start(provider(), w, COUNT, (e) => onEvent(e));
  }

  function onEvent(e: SuggestEvent) {
    if (mode !== 'results') return;
    if (e.type === 'progress') { waitCard(e.text); return; }
    if (e.type === 'suggestion') {
      found.push(e.suggestion);
      drawPanel(); drawLines();
      if (found.length === 1) fitAll();
      return;
    }
    over = true;
    dismiss('suggest');
    if (e.type === 'error') {
      // a calm message with one way out; the plan was never touched
      leave();
      post({ id: 'suggest', kind: 'hinweis-warn', title: e.message, detail: t('web_suggest_plan_unchanged'), buttons: [{ label: t('common_ok'), act: () => {} }] });
      return;
    }
    drawPanel();
    if (found.length) fitAll();
  }

  function facts(s: Suggestion): string {
    const p = s.plan;
    const up = `↑ ${number(Math.round(p.ascentM), 0)} m`;
    return `${esc(p.texts.distance)} · ${esc(p.texts.time)} · ${esc(up)}`;
  }

  function drawPanel() {
    if (!wish) return;
    const w = wish;
    let p = panel;
    if (!p) {
      p = document.createElement('div');
      p.id = 'suggestPanel';
      p.className = 'suggestpanel results';
      host.sheet.prepend(p);
      panel = p;
      host.sheet.classList.add('suggesting', 'suggest-results');
    }
    const via = provider();
    const title = found.length ? tp('web_suggest_count', found.length, found.length) : over ? t('web_suggest_none') : t('web_suggest_searching');
    const sub = [host.activityLabel(), hm(w.hours), w.startName ? t('web_suggest_from', w.startName) : ''].filter(Boolean).join(' · ');
    p.innerHTML = `
      <div class="sghead results"><div><h2>${esc(title)}</h2><div class="muted small">${esc(sub)}</div></div>
        <button type="button" class="textbtn sgchange">${esc(t('web_suggest_change'))}</button>
        <button type="button" class="iconbtn sgclose" aria-label="${esc(t('common_close'))}" title="${esc(t('common_close'))}">${icon('close', 20)}</button></div>
      <div class="sgcards" role="list">${found.map((s, i) => `
        <div class="sgcard${i === selected ? ' on' : ''}" role="listitem" data-i="${i}" tabindex="0" style="--c:${COLORS[i % COLORS.length]}">
          <div class="sgctitle"><i class="sgline" aria-hidden="true"></i><b>${esc(s.title)}</b>${s.plan.difficulty ? `<span class="badge ${s.plan.level ?? ''}">${esc(s.plan.difficulty)}</span>` : ''}</div>
          <div class="sgfacts">${facts(s)}</div>
          ${s.reasons.length ? `<div class="sgreasons muted small">${esc(s.reasons.join(' · '))}</div>` : ''}
          ${s.hints.length ? `<div class="sghints small">${esc(s.hints.join(' · '))}</div>` : ''}
          <div class="row end"><button type="button" class="primary sgadopt" data-i="${i}">${esc(t('web_suggest_adopt'))}</button></div>
        </div>`).join('')}</div>
      ${over ? `<button type="button" class="textbtn sgagain">${icon('loop', 18)}${esc(t('web_suggest_again'))}</button>` : ''}
      <div class="sgvia muted small">${esc(t('web_suggest_via', via.devName))}${via.standIn ? ' – ' + esc(t('web_suggest_standin')) : ''}</div>`;
    p.querySelector<HTMLButtonElement>('.sgclose')!.onclick = () => leave();
    p.querySelector<HTMLButtonElement>('.sgchange')!.onclick = () => openWish(w.start, w.startName, true);
    const again = p.querySelector<HTMLButtonElement>('.sgagain');
    if (again) again.onclick = () => { seed++; wish = { ...w, seed }; run(); };
    p.querySelectorAll<HTMLElement>('.sgcard').forEach((c) => {
      const pick = () => select(Number(c.dataset.i));
      c.onclick = (e) => { if (!(e.target as HTMLElement).closest('button')) pick(); };
      c.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(); } };
    });
    p.querySelectorAll<HTMLButtonElement>('.sgadopt').forEach((b) => (b.onclick = () => adopt(Number(b.dataset.i))));
  }

  function select(i: number) {
    if (i === selected || !found[i]) return;
    selected = i;
    panel?.querySelectorAll<HTMLElement>('.sgcard').forEach((c) => c.classList.toggle('on', Number(c.dataset.i) === i));
    drawLines();
  }

  async function adopt(i: number) {
    const s = found[i];
    if (!s) return;
    const points = s.points.slice();
    leave(); // first everything of ours off the page, then the page's own commit – as if the points had been clicked
    await host.adopt(points);
  }

  return {
    menuItem(at, name = null) {
      if (!host.planEmpty()) return null; // UNDECIDED (recommendation): only while the plan is empty
      const p: LatLon = [Number(at[0].toFixed(6)), Number(at[1].toFixed(6))];
      return { icon: 'loop', label: t('web_suggest_from_here'), sub: t('web_suggest_from_here_sub'), act: () => openWish(p, name) };
    },
    active: () => mode !== 'off',
    mapPress(pt) {
      if (mode !== 'results' || !host.map.getLayer('suggest-line')) return;
      const f = host.map.queryRenderedFeatures([[pt.x - 8, pt.y - 8], [pt.x + 8, pt.y + 8]], { layers: ['suggest-line'] });
      if (f.length) { const i = Number(f[0].properties?.i); select(i); panel?.querySelector<HTMLElement>(`.sgcard[data-i="${i}"]`)?.scrollIntoView({ block: 'nearest', inline: 'center' }); }
    },
    planTouched() { if (mode !== 'off' && !host.planEmpty()) leave(); },
  };
}
