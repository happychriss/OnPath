// Multi-day tours in the planner (docs/design/multi-day-tours.md §3, stages 2 + 3; owner 2026-11-17, sketches round 2 + 3):
// the guide panel (☰ → Mehrtagestour planen: 1 · Route, 2 · Tage), the days box, the overview in the plan sheet, the
// day-end symbols on the map (grey dashed moon = night not decided, blue moon = Übernachtung, green bed = Hotel), the
// night menus and the hotel dialog. Every rule is the engine's (WebDays.kt → the app's shared PlanDays); this file only
// shows what the engine says and hands each change to the page as one undo step (host.apply).
import * as maplibregl from 'maplibre-gl';
import type { EngineClient } from './engine/client';
import type { Activity, Hit, PlanResult } from './engine/types';
import type { MenuItem } from './menus';
import { icon } from './icons';
import { photonUrl } from './search';
import { t, tp } from './i18n';
import { tourText, type Tour } from './tourtext';
import './multiday.css';

type LatLon = [number, number];

/** A rule's answer (WebDays.rule): the whole plan, one undo step. */
export interface DayPlan {
  points: LatLon[]; pinned: number[]; names: Record<string, string>; tour: Tour | null;
  /** R1 with no grey proposal left: N + 1 (said in one line). */
  dayAdded: boolean;
  /** The plan point the rule was about (a new night), −1 = none. */
  index: number;
}

type EndKind = 'PROPOSAL' | 'OVERNIGHT' | 'HOTEL';
/** WebDays.view. */
interface DaysView {
  routed: boolean; days: number; canRemove: boolean; suggested: number | null; suggestedText: string | null; countText: string;
  totals: string; perDay: string; limits: string | null; totalM: number;
  rows: { n: number; title: string; stats: string; fromM: number; toM: number; kind: EndKind | null; plan: number | null; endText: string; over: string | null }[];
  ends: { night: number; kind: EndKind; lat: number; lon: number; plan: number | null; alongM: number; name: string }[];
}

export interface DaysHost {
  map: maplibregl.Map;
  engine: EngineClient;
  points(): LatLon[];
  pinned(): number[];
  namesText(): string;
  activity(): Activity;
  sac(): number;
  title(): string | null;
  setTitle(title: string | null): void;
  tour(): Tour | null;
  /** The shown plan when it is routed for the points as they are now (else null). */
  routedPlan(): PlanResult | null;
  /** One undo step: the plan a rule made. */
  apply(p: DayPlan): Promise<void>;
  status(text: string, kind?: 'info' | 'error'): void;
  pickGpx(): void;
  openSaved(): void;
  clearRoute(): void;
  suggestName(): Promise<string>;
  searchAreas(): Promise<void>;
  markRange(r: [number, number] | null): void;
  fitPadding(): maplibregl.PaddingOptions;
  openMenu(at: maplibregl.LngLat, items: MenuItem[], head: string): void;
  /** „Einfügen“ at a spot on the route – the page's normal add. */
  addPoint(at: maplibregl.LngLat): void;
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
const enc = (pts: LatLon[]) => pts.map(([la, lo]) => `${la.toFixed(6)},${lo.toFixed(6)}`).join(';');
const clean = (s: string | null | undefined) => (s ?? '').replace(/[\t\n]/g, ' ').trim();

/** A day end's symbol (owner 2026-11-17, round 3 A): grey dashed moon, blue moon, green bed. */
export function endSymbol(kind: EndKind, size = 26): string {
  const cls = kind === 'PROPOSAL' ? 'grey' : kind === 'OVERNIGHT' ? 'blue' : 'green';
  return `<span class="daysym ${cls}" style="width:${size}px;height:${size}px">${icon(kind === 'HOTEL' ? 'bed' : 'moon', Math.round(size * 0.62))}</span>`;
}

export class MultiDay {
  /** The guide panel is open (☰ → Mehrtagestour planen) – step 1 until „Weiter: Tage festlegen“. */
  guideOpen = false;
  /** „Gespeicherte Route öffnen“ under „Noch etwas anhängen“: the next saved route opened is appended. */
  private appendSaved = false;
  private view: DaysView | null = null;
  private viewFor = '';
  private viewAsk = 0;
  private proposals: maplibregl.Marker[] = [];
  /** The day row picked: that day highlighted on the map and in the profiles. */
  private selected: number | null = null;
  private box: HTMLElement;
  private guide: HTMLElement;

  constructor(private h: DaysHost, private sheet: HTMLElement) {
    this.box = document.createElement('div');
    this.box.id = 'tourOverview'; this.box.className = 'touroverview'; this.box.hidden = true;
    (sheet.querySelector('#savedLine') ?? sheet.firstElementChild)!.after(this.box);
    this.guide = document.createElement('div');
    this.guide.id = 'tourGuide'; this.guide.className = 'tourguide'; this.guide.hidden = true;
    (sheet.querySelector('#handle') ?? sheet.firstElementChild)?.after(this.guide); // under the grab handle
    // the day borders in both profiles, again whenever a profile is drawn anew
    const watch = (el: Element | null) => { if (el) new MutationObserver(() => this.borders()).observe(el, { childList: true }); };
    watch(document.getElementById('profile'));
    watch(document.querySelector('#bigProfile .bpplot'));
    (window as unknown as { __days: unknown }).__days = this; // for the browser tests
  }

  get isTour(): boolean { return !!this.h.tour(); }
  /** What a GPX track loaded now is for (gpximport.ts tourMode): the first route, or one to append. */
  gpxMode(): 'off' | 'first' | 'append' { return !this.guideOpen ? 'off' : this.h.points().length >= 2 ? 'append' : 'first'; }
  /** My routes opened a saved route: append it instead of opening it (once). */
  takeAppend(): boolean { const a = this.appendSaved && this.guideOpen && this.h.points().length >= 2; this.appendSaved = false; return a; }
  get lastView(): DaysView | null { return this.view; }

  // ---- engine calls ----

  private async rule(op: string, n = 0, lat = 0, lon = 0, text = '', tour: Tour | null = this.h.tour()): Promise<DayPlan | null> {
    const r = await this.h.engine.call('daysRule', op, enc(this.h.points()), this.h.activity(), this.h.sac(), this.h.pinned().join(','),
      this.h.namesText(), tourText(tour), n, lat, lon, text);
    return r && r !== 'null' ? JSON.parse(r) as DayPlan : null;
  }

  private async days(tour: Tour | null = this.h.tour()): Promise<DaysView | null> {
    const r = await this.h.engine.call('daysView', enc(this.h.points()), this.h.activity(), this.h.sac(), this.h.pinned().join(','),
      this.h.namesText(), tourText(tour ?? { days: 1, limits: { timeMin: null, ascentM: null }, nights: {}, description: null }));
    const v = JSON.parse(r) as DaysView;
    return v.routed ? v : null;
  }

  /** A rule's plan as one undo step; a day added by R1 is said in one line. */
  private async take(p: DayPlan | null, notPossible?: string) {
    if (!p) { if (notPossible) this.h.status(notPossible); return; }
    await this.h.apply(p);
    if (p.dayAdded) this.h.status(t('days_day_added'));
  }

  // ---- the way in: the guide panel ----

  openGuide() { this.guideOpen = true; this.render(); }
  closeGuide() { this.guideOpen = false; this.appendSaved = false; this.render(); }

  private renderGuide() {
    const g = this.guide;
    g.hidden = !this.guideOpen;
    this.sheet.classList.toggle('guide', this.guideOpen);
    if (!this.guideOpen) { g.innerHTML = ''; return; }
    const pts = this.h.points(), has = pts.length >= 2, plan = this.h.routedPlan();
    const way = (id: string, ic: 'fileOpen' | 'folder', label: string, line: string) =>
      `<button class="tgway" id="${id}">${icon(ic, 20)}<span class="txt"><b>${esc(label)}</b><span class="muted small">${esc(line)}</span></span></button>`;
    const ways = way(has ? 'tgGpxMore' : 'tgGpx', 'fileOpen', t('days_load_gpx'), t('days_load_gpx_line')) +
      way(has ? 'tgSavedMore' : 'tgSaved', 'folder', t('days_open_saved'), t('days_open_saved_line'));
    g.innerHTML = `<div class="tghead"><b>${esc(t('days_plan_tour'))}</b>
        <button class="iconbtn" id="tgClose" aria-label="${esc(t('common_close'))}" title="${esc(t('common_close'))}">${icon('close', 20)}</button></div>
      <div class="tgstep"><span class="tgnum ${has ? 'done' : ''}">${has ? icon('check', 16) : '1'}</span><b>${esc(t('days_step_route'))}</b></div>
      <div class="tgbody">${has ? `<div class="tgroute"><span class="txt"><b>${esc(this.h.title() || t('web_route_default_name'))}</b>
          <span class="muted small">${esc(plan?.texts.distance ?? t('plan_calculating'))}</span></span>
          <button class="iconbtn" id="tgDrop" aria-label="${esc(t('common_remove'))}" title="${esc(t('common_remove'))}">${icon('close', 18)}</button></div>
        <div class="muted small tgmore">${esc(t('days_append_more'))}</div>` : ''}${ways}</div>
      <div class="tgstep"><span class="tgnum ${has ? '' : 'off'}">2</span><b>${esc(t('days_step_days'))}</b></div>
      <button class="primary tgnext" id="tgNext" ${has && plan ? '' : 'disabled'}>${esc(t('days_next_days'))}</button>`;
    const on = (id: string, f: () => void) => { const b = g.querySelector<HTMLButtonElement>('#' + id); if (b) b.onclick = f; };
    on('tgClose', () => this.closeGuide());
    on('tgGpx', () => this.h.pickGpx()); on('tgGpxMore', () => this.h.pickGpx());
    on('tgSaved', () => { this.appendSaved = false; this.h.openSaved(); });
    on('tgSavedMore', () => { this.appendSaved = true; this.h.openSaved(); });
    on('tgDrop', () => this.h.clearRoute());
    on('tgNext', () => { void this.daysBox(); });
  }

  // ---- the days box ----

  /** „Weiter: Tage festlegen“ / „Als Mehrtagestour planen“ / the overview's name or limits: one box, everything under each other. */
  async daysBox() {
    if (document.querySelector('dialog.daysbox')) return;
    if (!this.h.routedPlan()) { this.h.status(t('plan_calculating')); return; }
    const now = this.h.tour();
    const d = document.createElement('dialog');
    d.className = 'share daysbox';
    const lim = now?.limits ?? { timeMin: null, ascentM: null };
    d.innerHTML = `<h2>${esc(t('days_box_title'))}</h2>
      <label class="dbrow"><span class="dblabel">${esc(t('days_name'))}</span><input id="dbName" maxlength="100" autocomplete="off"></label>
      <div class="dbrow limits"><span class="dblabel">${esc(t('days_max_per_day'))}</span><span class="dblimits">
        <label><input id="dbHours" type="number" inputmode="decimal" min="1" max="24" step="0.5" value="${lim.timeMin ? lim.timeMin / 60 : ''}"> ${esc(t('days_limit_hours'))}</label>
        <label><input id="dbAscent" type="number" inputmode="numeric" min="100" max="20000" step="100" value="${lim.ascentM ?? ''}"> ${esc(t('days_limit_ascent'))}</label></span></div>
      <div class="dbrow"><span class="dblabel">${esc(t('days_days'))}</span><span class="stepper">
        <button id="dbMinus" class="iconbtn" aria-label="${esc(t('days_fewer'))}" title="${esc(t('days_fewer'))}">−</button><b id="dbN"></b>
        <button id="dbPlus" class="iconbtn" aria-label="${esc(t('days_more'))}" title="${esc(t('days_more'))}">+</button></span></div>
      <div class="muted small" id="dbSuggest"></div>
      <div class="dbper" id="dbPer"></div>
      <div class="row end"><button id="dbBack">${esc(t('common_back'))}</button><button class="primary" id="dbDone">${esc(t('common_done'))}</button></div>`;
    document.body.append(d);
    const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
    const name = $<HTMLInputElement>('dbName');
    name.value = this.h.title() ?? '';
    if (!name.value) void this.h.suggestName().then((n) => { if (!name.value) name.value = n; });
    const nights = Object.keys(now?.nights ?? {}).length;
    let n = now?.days ?? 2;
    const limits = () => {
      const h = Number($<HTMLInputElement>('dbHours').value), a = Number($<HTMLInputElement>('dbAscent').value);
      return { timeMin: h > 0 ? Math.round(Math.min(24, h) * 60) : null, ascentM: a > 0 ? Math.round(Math.min(20000, a)) : null };
    };
    const tourOf = (): Tour => ({ days: n, limits: limits(), nights: now?.nights ?? {}, description: now?.description ?? null });
    let ask = 0;
    const show = async (fromLimits: boolean) => {
      const my = ++ask;
      if (fromLimits) {
        const v0 = await this.days(tourOf());
        if (my !== ask) return;
        if (v0?.suggested) n = Math.max(v0.suggested, nights + 1);
      }
      n = Math.max(1, nights + 1, Math.min(99, n));
      $('dbN').textContent = tp('days_count', n, n);
      $<HTMLButtonElement>('dbMinus').disabled = n <= Math.max(1, nights + 1);
      const v = await this.days(tourOf());
      if (my !== ask || !v) return;
      $('dbSuggest').textContent = v.suggestedText ?? '';
      $('dbPer').textContent = v.perDay;
    };
    $<HTMLButtonElement>('dbMinus').onclick = () => { n--; void show(false); };
    $<HTMLButtonElement>('dbPlus').onclick = () => { n++; void show(false); };
    for (const id of ['dbHours', 'dbAscent']) $<HTMLInputElement>(id).oninput = () => { void show(true); };
    $<HTMLButtonElement>('dbBack').onclick = () => d.close();
    $<HTMLButtonElement>('dbDone').onclick = async () => {
      const tour = tourOf();
      const p = await this.rule('start', n, 0, 0, '', tour);
      d.close();
      if (!p) return;
      this.h.setTitle(name.value.trim() || null);
      this.guideOpen = false; this.appendSaved = false;
      await this.take(p);
    };
    d.addEventListener('close', () => d.remove());
    d.showModal();
    await show(false);
  }

  // ---- the overview, the map symbols, the profile borders ----

  /** After every change of the plan or its route (main.ts render): guide, overview, symbols. */
  render() {
    this.renderGuide();
    const tour = this.h.tour(), plan = this.h.routedPlan();
    if (!tour || !plan) { this.box.hidden = true; this.clearSymbols(); this.view = null; this.viewFor = ''; this.borders(); if (!tour) this.select(null); return; }
    const key = enc(this.h.points()) + '|' + tourText(tour) + '|' + this.h.activity() + this.h.sac() + '|' + this.h.namesText() + '|' + plan.distanceM;
    if (key === this.viewFor && this.view) { this.box.hidden = false; return; }
    const my = ++this.viewAsk;
    // the day ends are named after the places nearby (the planner's place index of the map areas along the route)
    void this.h.searchAreas().catch((e) => console.warn('[Problems] WARN days: no place names for the day ends', e)).then(() => this.days()).then((v) => {
      if (my !== this.viewAsk) return;
      this.view = v; this.viewFor = v ? key : '';
      this.overview(); this.symbols(); this.borders();
    }, (e) => console.warn('[Problems] WARN days: the days were not worked out', e));
  }

  private overview() {
    const v = this.view, tour = this.h.tour();
    this.box.hidden = !v || !tour;
    if (!v || !tour) return;
    if (this.selected != null && this.selected > v.rows.length) this.select(null);
    this.box.innerHTML = `<div class="tohead"><button class="toname" id="toName" title="${esc(t('days_box_title'))}">${esc(this.h.title() || t('days_multi_day_tour'))}</button>
        <span class="stepper"><button id="toMinus" class="iconbtn" ${v.canRemove ? '' : 'disabled'} aria-label="${esc(t('days_fewer'))}" title="${esc(v.canRemove ? t('days_fewer') : t('days_remove_first'))}">−</button>
        <b id="toCount">${esc(v.countText)}</b>
        <button id="toPlus" class="iconbtn" aria-label="${esc(t('days_more'))}" title="${esc(t('days_more'))}">+</button></span></div>
      <div class="tototals muted">${esc(v.totals)}</div>
      ${v.limits ? `<button class="tolimits muted small" id="toLimits">${esc(v.limits)}</button>` : ''}
      ${v.canRemove ? '' : `<div class="tohint muted small">${esc(t('days_remove_first'))}</div>`}
      <ol class="todays">${v.rows.map((r) => `<li><button class="today${r.over ? ' over' : ''}${this.selected === r.n ? ' sel' : ''}" data-day="${r.n}" aria-pressed="${this.selected === r.n}">
          <span class="dn">${r.n}</span><span class="txt"><b>${esc(r.title)}</b><span class="muted small">${esc(r.stats)}</span>
          ${r.over ? `<span class="overline small">${esc(r.over)}</span>` : r.endText ? `<span class="muted small">${esc(r.endText)}</span>` : ''}</span>
          ${r.kind ? endSymbol(r.kind, 22) : `<span class="daysym goal">${icon('flag', 14)}</span>`}</button></li>`).join('')}</ol>`;
    const b = (id: string) => this.box.querySelector<HTMLButtonElement>('#' + id);
    b('toName')!.onclick = () => { void this.daysBox(); };
    if (b('toLimits')) b('toLimits')!.onclick = () => { void this.daysBox(); };
    b('toMinus')!.onclick = async () => this.take(await this.rule('setDays', v.days - 1), t('days_remove_first'));
    b('toPlus')!.onclick = async () => this.take(await this.rule('setDays', v.days + 1));
    this.box.querySelectorAll<HTMLButtonElement>('.today').forEach((row) => {
      row.onclick = () => { const n = Number(row.dataset.day); this.select(this.selected === n ? null : n, true); };
    });
  }

  /** Day [n] highlighted on the map and in the profiles ([fit]: the map shows it), or none. */
  select(n: number | null, fit = false) {
    this.selected = n;
    const r = n != null ? this.view?.rows[n - 1] : null;
    this.h.markRange(r ? [r.fromM, r.toM] : null);
    this.box.querySelectorAll<HTMLButtonElement>('.today').forEach((row) => {
      const on = Number(row.dataset.day) === n; row.classList.toggle('sel', on); row.setAttribute('aria-pressed', String(on));
    });
    const plan = this.h.routedPlan();
    if (!r || !fit || !plan) return;
    // the day's part of the line in view (display only: the distances as the profile counts them)
    const b = new maplibregl.LngLatBounds();
    let along = 0, prev: [number, number] | null = null;
    for (const leg of plan.legs) for (const [la, lo] of leg.points) {
      if (prev) along += metres(prev, [la, lo]);
      prev = [la, lo];
      if (along >= r.fromM - 1 && along <= r.toM + 1) b.extend([lo, la]);
    }
    if (!b.isEmpty()) this.h.map.fitBounds(b, { padding: this.h.fitPadding(), maxZoom: 14 });
  }

  /** The grey proposals on the map (blue / green ends are the plan's own points – main.ts draws them with nightLook). */
  private symbols() {
    this.clearSymbols();
    for (const e of this.view?.ends ?? []) {
      if (e.kind !== 'PROPOSAL') continue;
      const el = document.createElement('div');
      el.className = 'dayend proposal';
      el.innerHTML = endSymbol('PROPOSAL');
      el.title = `${t('days_night_n', e.night)} · ${t('days_open_night')}`;
      const m = new maplibregl.Marker({ element: el, draggable: true }).setLngLat([e.lon, e.lat]).addTo(this.h.map);
      let dragged = 0;
      // R7 (owner 2026-11-17: „Wird dort blau“): dropped = an Übernachtung there
      m.on('dragend', async () => { dragged = Date.now(); const q = m.getLngLat(); await this.take(await this.rule('addNight', 0, q.lat, q.lng)); });
      el.addEventListener('click', (ev) => {
        ev.stopPropagation();
        if (Date.now() - dragged < 300) return;
        this.h.openMenu(new maplibregl.LngLat(e.lon, e.lat), [
          { icon: 'moon', label: t('days_overnight_here'), act: async () => this.take(await this.rule('addNight', 0, e.lat, e.lon)) },
          { icon: 'bed', label: t('days_set_hotel'), act: () => { void this.hotelDialog(e.night, -1, [e.lat, e.lon]); } },
        ], `${endSymbol('PROPOSAL', 22)}<b>${esc(t('days_night_n', e.night))}</b>`);
      });
      this.proposals.push(m);
    }
  }

  private clearSymbols() { this.proposals.splice(0).forEach((m) => m.remove()); }

  /** Thin lines at the day ends in the sheet's and the big profile. */
  private borders() {
    const v = this.view, prof = this.h.routedPlan()?.profile;
    const total = prof && prof.dist.length ? prof.dist[prof.dist.length - 1] : 0;
    for (const plot of document.querySelectorAll<HTMLElement>('#profile .profplot, #bigProfile .profplot')) {
      const want = v && this.h.tour() && total > 0 ? v.ends.map((e) => `${((e.alongM / total) * 100).toFixed(2)}`).join(',') : '';
      if ((plot.dataset.days ?? '') === want) continue;
      plot.dataset.days = want;
      plot.querySelectorAll('.dayborder').forEach((x) => x.remove());
      if (want) for (const e of v!.ends) {
        const i = document.createElement('i');
        i.className = 'dayborder ' + e.kind.toLowerCase();
        i.style.left = `${((e.alongM / total) * 100).toFixed(2)}%`;
        plot.append(i);
      }
    }
  }

  // ---- the night menus ----

  /** A plan point's night mark, if any (main.ts draws it as the moon / bed). */
  nightOf(i: number): EndKind | null {
    const n = this.h.tour()?.nights[String(i)];
    return n ? (n.hotel ? 'HOTEL' : 'OVERNIGHT') : null;
  }

  /** The night number of plan point [i] (its day end), from the last view. */
  private nightNo(i: number): number { return this.view?.ends.find((e) => e.plan === i)?.night ?? 0; }

  /** The menu of a blue moon / green bed (owner 2026-11-17): blue „Hotel hinterlegen“ · „Übernachtung entfernen“; green „Hotel ändern“ · „Hotel entfernen“ · „Übernachtung entfernen“. */
  nightMenu(i: number): { items: MenuItem[]; head: string } | null {
    const kind = this.nightOf(i);
    if (!kind) return null;
    const night = this.nightNo(i), hotel = this.h.tour()?.nights[String(i)]?.hotel;
    const pt = this.h.points()[i];
    const items: MenuItem[] = kind === 'HOTEL' ? [
      { icon: 'bed', label: t('days_change_hotel'), act: () => { void this.hotelDialog(night, i, pt); } },
      { icon: 'moon', label: t('days_remove_hotel'), act: async () => this.take(await this.rule('removeHotel', i)) },
      { icon: 'delete', label: t('days_remove_overnight'), danger: true, act: async () => this.take(await this.rule('removeNight', i)) },
    ] : [
      { icon: 'bed', label: t('days_set_hotel'), act: () => { void this.hotelDialog(night, i, pt); } },
      { icon: 'delete', label: t('days_remove_overnight'), danger: true, act: async () => this.take(await this.rule('removeNight', i)) },
    ];
    const title = hotel?.name || (night ? t('days_night_n', night) : t(kind === 'HOTEL' ? 'days_hotel' : 'days_overnight'));
    return { items, head: `${endSymbol(kind, 22)}<b>${esc(title)}</b>` };
  }

  /** ✕ on a hotel's point (scenario N: „with a hotel it asks first“): true = remove it. */
  askRemoveHotel(): Promise<boolean> {
    return new Promise((done) => {
      const d = document.createElement('dialog');
      d.className = 'share discard';
      d.innerHTML = `<h2>${esc(t('days_remove_hotel_ask'))}</h2>
        <div class="row end"><button id="rhCancel">${esc(t('common_cancel'))}</button><button class="primary danger" id="rhOk">${esc(t('common_remove'))}</button></div>`;
      document.body.append(d);
      let yes = false;
      d.querySelector<HTMLButtonElement>('#rhCancel')!.onclick = () => d.close();
      d.querySelector<HTMLButtonElement>('#rhOk')!.onclick = () => { yes = true; d.close(); };
      d.addEventListener('close', () => { d.remove(); done(yes); });
      d.showModal();
    });
  }

  /** A click on the route of a multi-day tour: „Übernachtung hier“ and the normal „Einfügen“. */
  routeMenu(at: maplibregl.LngLat) {
    this.h.openMenu(at, [
      { icon: 'moon', label: t('days_overnight_here'), act: async () => this.take(await this.rule('addNight', 0, at.lat, at.lng)) },
      { icon: 'addLocation', label: t('plan_add_as_stop'), act: () => this.h.addPoint(at) },
    ], '');
  }

  /** „Hotel für diese Tour“ in a found place's menu (scenario F): which night – the nearest offered. */
  async hotelForTour(hit: Hit) {
    const near = await this.rule('nearest', 0, hit.lat, hit.lon);
    const k = (near as unknown as { night?: number } | null)?.night ?? 1;
    const ends = this.view?.ends ?? [];
    if (!ends.length) return;
    const d = document.createElement('dialog');
    d.className = 'share nightpick';
    d.innerHTML = `<h2>${esc(t('days_which_night'))}</h2><div class="nightlist">${ends.map((e) => `<label class="nightrow">
        <input type="radio" name="night" value="${e.night}" ${e.night === k ? 'checked' : ''}>${endSymbol(e.kind, 20)}
        <span>${esc(e.night === k ? t('days_night_nearest', e.night) : t('days_night_n', e.night))} · ${esc(e.name)}</span></label>`).join('')}</div>
      <div class="row end"><button id="npCancel">${esc(t('common_cancel'))}</button><button class="primary" id="npTake">${esc(t('days_hotel_for_tour'))}</button></div>`;
    document.body.append(d);
    d.querySelector<HTMLButtonElement>('#npCancel')!.onclick = () => d.close();
    d.querySelector<HTMLButtonElement>('#npTake')!.onclick = async () => {
      const n = Number(d.querySelector<HTMLInputElement>('input[name=night]:checked')?.value ?? k);
      d.close();
      const e = ends.find((x) => x.night === n);
      if (e) await this.setHotel(e.plan ?? -1, [e.lat, e.lon], hit.lat, hit.lon, hit.name ?? hit.title, hit.address ?? hit.subtitle, null);
    };
    d.addEventListener('close', () => d.remove());
    d.showModal();
  }

  private async setHotel(index: number, end: LatLon, lat: number, lon: number, name: string | null, address: string | null, link: string | null) {
    await this.take(await this.rule('setHotel', index, lat, lon, [clean(name), clean(address), clean(link), end[0].toFixed(6), end[1].toFixed(6)].join('\t')));
  }

  /**
   * „Hotel hinterlegen“ / „Hotel ändern“ for night [night] (plan point [index], −1 = a grey proposal at [end]): search
   * the hotel, or paste a Google Maps / Booking link or an address (the engine reads it like a shared place).
   */
  async hotelDialog(night: number, index: number, end: LatLon) {
    const d = document.createElement('dialog');
    d.className = 'share hoteldlg';
    d.innerHTML = `<h2>${esc(t('days_hotel_title', night))}</h2>
      <input id="hotelQ" type="search" autocomplete="off" placeholder="${esc(t('days_hotel_hint'))}" aria-label="${esc(t('days_hotel_hint'))}">
      <ul class="hotelres" id="hotelRes"></ul><div class="muted small" id="hotelNote"></div>
      <div class="row end"><button id="hotelCancel">${esc(t('common_cancel'))}</button></div>`;
    document.body.append(d);
    const q = d.querySelector<HTMLInputElement>('#hotelQ')!, list = d.querySelector<HTMLUListElement>('#hotelRes')!, note = d.querySelector<HTMLElement>('#hotelNote')!;
    d.querySelector<HTMLButtonElement>('#hotelCancel')!.onclick = () => d.close();
    type Row = { title: string; sub: string; lat: number; lon: number; name: string | null; address: string | null; link: string | null };
    const show = (rows: Row[], msg: string) => {
      list.innerHTML = rows.map((r, k) => `<li><button data-k="${k}"><b>${esc(r.title)}</b><span class="muted small">${esc(r.sub)}</span></button></li>`).join('');
      note.textContent = msg;
      list.querySelectorAll<HTMLButtonElement>('button').forEach((b) => {
        b.onclick = async () => { const r = rows[Number(b.dataset.k)]; d.close(); await this.setHotel(index, end, r.lat, r.lon, r.name, r.address, r.link); };
      });
    };
    let gen = 0, timer = 0;
    const run = async () => {
      const my = ++gen, text = q.value.trim();
      if (!text) { show([], ''); return; }
      const read = JSON.parse(await this.h.engine.call('hotelText', text)) as
        { lat: number | null; lon: number | null; name: string | null; address: string | null; link: string | null; search: string | null } | null;
      if (my !== gen) return;
      if (read && read.lat != null && read.lon != null) {
        show([{ title: read.name ?? read.address ?? text, sub: read.address ?? '', lat: read.lat, lon: read.lon, name: read.name, address: read.address, link: read.link }], '');
        return;
      }
      const s = read?.search ?? text;
      const rowOf = (h: Hit): Row => ({ title: h.title, sub: h.subtitle, lat: h.lat, lon: h.lon, name: h.name ?? h.title, address: h.address ?? (h.subtitle || null), link: read?.link ?? null });
      await this.h.searchAreas();
      const offline = await this.h.engine.search(s, end[0], end[1], [], '');
      if (my !== gen) return;
      show(offline.slice(0, 8).map(rowOf), s.length >= 3 ? t('search_searching_online') : offline.length ? '' : t('days_hotel_none'));
      if (s.length < 3) return;
      let mixed: Hit[] | null = null;
      try {
        const r = await fetch(photonUrl(s, end[0], end[1]));
        if (r.ok) mixed = await this.h.engine.searchOnline(s, await r.text(), '');
      } catch { /* expected: offline or Photon unreachable – the offline rows stay */ }
      if (my !== gen) return;
      const all = mixed ?? offline;
      show(all.slice(0, 8).map(rowOf), all.length ? (all.some((h) => h.online) ? t('web_search_online_credit') : '') : t('days_hotel_none'));
    };
    q.oninput = () => { clearTimeout(timer); timer = window.setTimeout(() => { void run(); }, 300); };
    d.addEventListener('close', () => { gen++; clearTimeout(timer); d.remove(); });
    d.showModal();
    q.focus();
  }
}

/** Metres between two points (display only). */
function metres(a: [number, number], b: [number, number]): number {
  const r = Math.PI / 180, x = (b[1] - a[1]) * r * Math.cos(((a[0] + b[0]) / 2) * r), y = (b[0] - a[0]) * r;
  return Math.sqrt(x * x + y * y) * 6371000;
}
