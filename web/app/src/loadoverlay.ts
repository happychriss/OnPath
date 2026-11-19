// The wait window (form A, owner 2026-11-19: „ich muss warten“ – one window over the map, always the same; „A, aber nie
// mehr als 3 Häkchen“): the app's WaitWindow (ui/progress/WaitWindow.kt, ui/MapLoad.kt MapWait, ui/progress/RouteWait.kt –
// mirrored, web/parity.json). In the planner a new place's map, its routing data and the route calculation are steps of
// ONE window: sign + title, ONE bar 0–100 over all steps with its number beside it (never back, never standing – the
// shares learned in this browser, progressmemory.ts), at most 3 step rows with ticks, the time left („noch etwa 20
// Sekunden“, or „dauert etwas länger…“ when a step overruns), Cancel. The math is the app's own code in the engine
// (WebWait.kt via waitengine.ts); this file only collects what is measured and draws.
//
// What is measured (the page's honest numbers): a map area the server prepares – its own progress and time left
// (areas.ts); map tiles of the view still loading (counted as they load, TILE_UNITS each – their size is not known
// before they come); the routing data's bytes (sizes from the rd5 files' own index, worker.ts ensureParts); the engine's
// files while it starts (ENGINE_UNITS); a shared link before its routing data is known (LINK_UNITS); the routing data
// written into the engine (join); the legs the router works on and the check (the engine's Planner reports each leg).
// While it shows it is the only wait (onewait.ts): the map strip's spinners and the sheet's wait line step back. It
// goes by itself when everything is there (a moment at 100 %); Cancel stops a route, and a map that loads on goes on
// beside the map (form B, besidesign.ts – „A turns into B by itself“).
import type * as maplibregl from 'maplibre-gl';
import { t } from './i18n';
import { readProgress, writeProgress } from './progressmemory';
import { waitStart, waitTick, waitEnd, waitEngineReady, waitEngineSettled, type WaitUi } from './waitengine';

export const TILE_UNITS = 40_000; // a typical vector tile, bytes
export const ENGINE_UNITS = 3_000_000; // the Wasm engine and its profiles
export const LINK_UNITS = 4_000_000; // a link's routing data before its size is known (a typical 10 km corridor)
/** How often the window moves (its bar creeps smoothly between two reports – the app's RouteWait TICK_MS). */
export const TICK_MS = 500;
/** A first visit: the window waits this long at most for its engine before it shows a plain bar (meanwhile the
 * sheet's status line says the engine loads). */
const ENGINE_WAIT_MS = 15_000;

/** One map area the server prepares for this wait (areas.ts states). */
export interface AreaIn { id: string; state: string; progress: number; eta?: number }
/** The route run of this wait: [total] 0 = its legs not known yet; [join] = the routing data written (0..1), null = none / done. */
export interface PlanIn {
  done: number; total: number; checking: boolean; join: number | null; names: string; points: number;
  /** The worker's first report came (routing data to load, or the router starts): until then nothing is known – the plan
   * neither brings the window nor shows as steps (a route's steps first, then the map's before them, would read as going back). */
  known: boolean;
}

type Kind = 'map' | 'routing' | 'engine';
interface Job { done: number; total: number; kind: Kind; opens: boolean }

/** The ring's / the window's icons (the app's CloudDownload / Route / CloudOff, 24 px). */
const ICON = {
  map: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM19 18H6c-2.21 0-4-1.79-4-4 0-2.05 1.53-3.76 3.56-3.97l1.07-.11.5-.95A5.469 5.469 0 0 1 12 6c2.62 0 4.88 1.86 5.39 4.43l.3 1.5 1.53.11A2.98 2.98 0 0 1 22 15c0 1.65-1.35 3-3 3zm-5.55-8h-2.9v3H8l4 4 4-4h-2.55z"/></svg>',
  route: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M19 15.18V7c0-2.21-1.79-4-4-4s-4 1.79-4 4v10c0 1.1-.9 2-2 2s-2-.9-2-2V8.82C8.16 8.4 9 7.3 9 6c0-1.66-1.34-3-3-3S3 4.34 3 6c0 1.3.84 2.4 2 2.82V17c0 2.21 1.79 4 4 4s4-1.79 4-4V7c0-1.1.9-2 2-2s2 .9 2 2v8.18c-1.16.41-2 1.51-2 2.82 0 1.66 1.34 3 3 3s3-1.34 3-3c0-1.3-.84-2.4-2-2.82zM6 7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm12 12c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z"/></svg>',
  offline: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M24 15c0-2.64-2.05-4.78-4.65-4.96A7.49 7.49 0 0 0 12 4c-1.33 0-2.57.36-3.65.97l1.49 1.49C10.51 6.17 11.23 6 12 6c3.04 0 5.5 2.46 5.5 5.5v.5H19a2.996 2.996 0 0 1 1.66 5.49l1.42 1.42A4.982 4.982 0 0 0 24 15zM4.41 3.86 3 5.27l2.77 2.77h-.42A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h11.73l2 2 1.41-1.41L4.41 3.86zM6 18c-2.21 0-4-1.79-4-4s1.79-4 4-4h1.73l8 8H6z"/></svg>',
};

export class LoadOverlay {
  private jobs = new Map<string, Job>();
  private areaList: AreaIn[] = [];
  private planIn: PlanIn | null = null;
  private onCancelPlan: (() => void) | null = null;
  /** A wait runs (the engine's WebWait ticks from its first moment, also before the window shows). */
  private active = false;
  /** Cancel closed the window: it stays away until this wait is over. */
  private dismissed = false;
  private visible = false;
  private showTimer = 0;
  private hideTimer = 0;
  private tickTimer = 0;
  private soon = 0;
  private ui: WaitUi | null = null;
  private fallbackPct = 0;
  /** The highest percent this wait's window drew – what may never be undercut (the engine may come while it waits). */
  private drawn = 0;
  private since = 0;
  /** The engine's WebWait got this wait's learned shares (the engine may load while a first wait runs). */
  private started = false;
  /** A new wait takes the window over that is up (a map area joined a wait that had none). */
  private carry = false;
  private el: HTMLElement | null = null;

  /**
   * [delayMs]: a map wait shorter than this shows nothing (no flash); [planDelayMs]: the same for a route alone (the
   * app's slow route); [onVisible]: the page's other waits step back; [at]: where the window's centre goes (the free
   * map's middle); [onHandover]: Cancel while map areas load on – their percent goes on beside the map (form B).
   */
  constructor(private delayMs: number, private planDelayMs: number, private onVisible: (on: boolean) => void,
    private at: () => { x: number; y: number; top: number; bottom: number }, private onHandover: (percent: number) => void = () => {}) {}

  get shown() { return this.visible; }
  /** The window is coming (its delay runs) – onewait.ts brings it at once when two other waits would show meanwhile. */
  get due() { return !this.visible && !!this.showTimer; }
  showNow() { if (!this.due) return; clearTimeout(this.showTimer); this.showTimer = 0; this.show(); }
  /** Called when the window becomes due (the arbiter decides again). */
  onDue: (() => void) | null = null;
  /** The window now (for the tests and the diagnostics). */
  get now(): WaitUi | null { return this.visible ? this.ui : null; }

  /** A download of this wait: [done] / [total] bytes; [opens]: it alone brings the window up (the engine only joins one). */
  job(id: string, done: number, total: number, kind: Kind, opens = true) {
    total = Math.max(0, total);
    const old = this.jobs.get(id);
    this.jobs.set(id, { done: Math.min(Math.max(done, old?.total === total ? old.done : 0), total), total, kind, opens });
    this.update();
  }
  finish(id: string) { const j = this.jobs.get(id); if (j) { j.done = j.total; this.update(); } }
  remove(id: string) { if (this.jobs.delete(id)) this.update(); }
  isOpen(id: string) { const j = this.jobs.get(id); return !!j && j.done < j.total; }

  /** The map areas the server prepares that this wait waits for (the user's own point / search; [] = none). */
  areas(list: AreaIn[]) {
    const open = (l: AreaIn[]) => l.some((a) => a.state !== 'ready' && a.state !== 'failed');
    // a map area to prepare joins a wait that had none (the page's first map, tiles): a wait of its own – its
    // „vorbereiten“ step would otherwise stand before steps already done (the app's MapWait is one per place)
    // A window already up stays up (no gap, no second window: the user sees one wait) and its number never goes back
    if (this.active && open(list) && !open(this.areaList)) { this.carry = this.visible; this.end(false); this.dismissed = false; }
    this.areaList = list;
    this.update();
  }

  /** The route run of this wait (null = none / over); [onCancel] = Cancel stops it (the app's cancelRouting). */
  plan(p: PlanIn | null, onCancel?: () => void) {
    this.planIn = p;
    if (onCancel) this.onCancelPlan = onCancel;
    if (!p) this.onCancelPlan = null;
    this.update();
  }

  private openJobs() { return [...this.jobs.values()].filter((j) => j.done < j.total); }
  private areasOpen() { return this.areaList.some((a) => a.state !== 'ready' && a.state !== 'failed'); }
  private get open() { return this.openJobs().length > 0 || this.areasOpen() || !!this.planIn; }

  private update() {
    if (!this.open) { if (this.active) this.end(true); this.dismissed = false; return; }
    if (!this.active) this.begin();
    // a new measurement: the bar follows it soon (not only on the half-second tick – bytes come in steps)
    else if (!this.soon) this.soon = window.setTimeout(() => { this.soon = 0; this.tick(); }, 100);
    if (this.dismissed) return;
    const mapOpens = this.openJobs().some((j) => j.opens) || this.areasOpen();
    if (!this.visible && !this.showTimer && (mapOpens || this.planIn?.known)) {
      this.showTimer = window.setTimeout(() => {
        this.showTimer = 0;
        if (this.open && !this.dismissed && (this.openJobs().some((j) => j.opens) || this.areasOpen() || this.planIn?.known)) this.show();
      }, mapOpens ? this.delayMs : this.planDelayMs);
      this.onDue?.();
    }
  }

  private begin() {
    // the last wait's full bar still shows: it goes now – the new wait gets its own window (never 100 → 0 in one)
    const carry = this.carry && this.visible;
    this.carry = false;
    if (this.visible && !carry) { clearTimeout(this.hideTimer); this.hideTimer = 0; this.hide(); }
    this.active = true;
    this.since = performance.now();
    // a carried window keeps its picture and its number until the new wait's first tick (never back from it)
    if (!carry) { this.ui = null; this.drawn = 0; }
    this.fallbackPct = this.drawn; this.started = false;
    this.tick();
    this.tickTimer = window.setInterval(() => this.tick(), TICK_MS);
  }

  /** The wait is over ([finished]: everything is there – a moment at 100 %, and what it teaches is kept). */
  private end(finished: boolean) {
    this.active = false;
    clearInterval(this.tickTimer); this.tickTimer = 0;
    clearTimeout(this.soon); this.soon = 0;
    clearTimeout(this.showTimer); this.showTimer = 0;
    const r = this.started ? waitEnd(performance.now(), finished) : { full: null } as ReturnType<typeof waitEnd>;
    this.started = false;
    this.areaList = this.areaList.filter((a) => a.state !== 'ready' && a.state !== 'failed');
    if (finished) writeProgress({ shares: r.shares, check: r.check, part: r.part });
    for (const [id, j] of this.jobs) if (j.done >= j.total) this.jobs.delete(id);
    if (!this.visible || this.carry) return;
    if (finished && (r.full || this.ui)) {
      this.ui = r.full ?? { ...this.ui!, percent: 100, line: null, late: false };
      this.draw();
      this.hideTimer = window.setTimeout(() => this.hide(), 350);
    } else this.hide();
  }

  private tick() {
    if (!this.active) return;
    if (!this.started && waitEngineReady()) {
      const m = readProgress();
      waitStart(m.shares, m.check, m.part);
      this.started = true;
    }
    const jobs = [...this.jobs.values()];
    const sum = (k: (j: Job) => boolean, f: (j: Job) => number) => jobs.filter(k).reduce((s, j) => s + f(j), 0);
    const p = this.planIn?.known ? this.planIn : null;
    const areas = this.areaList.map((a) => `${a.id}\t${a.state}\t${a.progress}\t${a.eta ?? -1}`).join('\n');
    // while the server still prepares an area its map cannot come yet: the tiles loading now are the overview's, not
    // the area's download (it comes when the area is ready – the cells' sources reload)
    const preparing = this.areasOpen();
    const ui = waitTick(performance.now(), areas, sum(() => true, (j) => (preparing && j.kind === 'map' ? 0 : j.done)), sum(() => true, (j) => j.total),
      jobs.some((j) => j.kind === 'map') || this.areaList.length > 0, jobs.some((j) => j.kind === 'routing'),
      // the routing data is written into the engine before the route: its join step waits while it downloads
      p ? (p.join ?? -1) : jobs.some((j) => j.kind === 'routing' && j.done < j.total) ? 0 : -1,
      p?.done ?? 0, p ? p.total : -1, !!p?.checking, p?.names ?? '', p?.points ?? 0, typeof navigator !== 'undefined' && navigator.onLine === false);
    this.ui = ui ?? this.fallback(jobs, p);
    // the engine came while the plain bar showed: never back from what was drawn
    if (this.ui.percent < this.drawn) this.ui = { ...this.ui, percent: this.drawn };
    if (this.visible) this.draw();
  }

  /** No engine for the window (it did not load): a plain bar by what is measured, never back, never 100 before the end. */
  private fallback(jobs: Job[], p: PlanIn | null): WaitUi {
    const total = jobs.reduce((s, j) => s + j.total, 0), done = jobs.reduce((s, j) => s + j.done, 0);
    const legs = p && p.total > 0 ? p.done / p.total : 0;
    const f = p ? (total > 0 ? 0.6 * done / total + 0.4 * legs : legs) : total > 0 ? done / total : 0;
    this.fallbackPct = Math.max(this.fallbackPct, Math.min(99, Math.floor(f * 100)));
    const route = !!p && (total === 0 || done >= total);
    return { title: t(route ? 'wait_title_route' : 'wait_title_map'), route, percent: this.fallbackPct, steps: [], line: null, paused: false, late: false };
  }

  private show() {
    if (this.visible || !this.active || this.dismissed) return;
    // the window's engine is still loading (a first visit): a moment more – its rows and words, not a bare bar first
    if (!waitEngineSettled() && performance.now() - this.since < ENGINE_WAIT_MS) { this.showTimer = window.setTimeout(() => { this.showTimer = 0; this.show(); }, 150); return; }
    this.tick(); // fresh: the engine may have come since the last tick
    if (!this.ui) return;
    this.visible = true;
    this.onVisible(true);
    this.draw();
  }

  private hide() {
    this.hideTimer = 0;
    this.visible = false;
    this.el?.remove(); this.el = null;
    this.onVisible(false);
  }

  /** Cancel: a route stops (the app's cancelRouting); map areas that load on go on beside the map (form B). */
  private cancel() {
    const pct = this.ui?.percent ?? 0;
    const plan = this.onCancelPlan;
    const handover = this.areasOpen();
    this.dismissed = true;
    this.end(false);
    this.hide();
    if (handover) this.onHandover(pct);
    plan?.();
  }

  /** The window, updated in place (a click on Cancel never falls between two windows). */
  private draw() {
    const u = this.ui;
    if (!u) return;
    this.drawn = Math.max(this.drawn, u.percent);
    let el = this.el;
    if (!el) {
      el = this.el = document.createElement('div');
      el.id = 'waitwin';
      el.className = 'waitwin';
      el.setAttribute('role', 'status');
      el.setAttribute('aria-live', 'polite');
      el.innerHTML = '<div class="wwhead"><span class="wwsign"></span><span class="wwtitle"></span></div>'
        + '<div class="wwprog"><div class="wwbar" role="progressbar" aria-valuemin="0" aria-valuemax="100"><i></i></div><span class="wwpct"></span></div>'
        + '<ul class="wwsteps"></ul><div class="wwfoot"><span class="wwline"></span><button type="button" class="wwcancel"></button></div>';
      el.querySelector<HTMLButtonElement>('.wwcancel')!.onclick = () => this.cancel();
      document.body.append(el);
    }
    const sign = u.paused ? 'offline' : u.route ? 'route' : 'map';
    const s = el.querySelector<HTMLElement>('.wwsign')!;
    if (s.dataset.sign !== sign) { s.dataset.sign = sign; s.innerHTML = ICON[sign]; }
    el.querySelector('.wwtitle')!.textContent = u.title;
    el.dataset.pct = String(u.percent);
    el.classList.toggle('paused', u.paused);
    const bar = el.querySelector<HTMLElement>('.wwbar')!;
    bar.setAttribute('aria-valuenow', String(u.percent));
    bar.querySelector('i')!.style.width = `${u.percent}%`;
    el.querySelector('.wwpct')!.textContent = `${u.percent} %`; // the app's "34 %" in every language
    const ul = el.querySelector('.wwsteps')!;
    const rows = u.steps.slice(0, 3); // never more than 3 (the engine's WaitSteps.shown already keeps it so)
    while (ul.children.length > rows.length) ul.lastElementChild!.remove();
    rows.forEach((r, i) => {
      let li = ul.children[i] as HTMLElement | undefined;
      if (!li) { li = document.createElement('li'); li.innerHTML = '<span class="mark" aria-hidden="true"></span><span class="lab"></span>'; ul.append(li); }
      li.className = r.state;
      li.querySelector('.mark')!.textContent = r.state === 'done' ? '✓' : '';
      li.querySelector('.lab')!.textContent = r.label;
    });
    el.querySelector('.wwline')!.textContent = u.line ?? '';
    el.querySelector('.wwcancel')!.textContent = t('common_cancel');
    el.querySelector<HTMLButtonElement>('.wwcancel')!.hidden = !this.active; // the last picture at 100 %: nothing to cancel
    el.classList.toggle('done', !this.active); // … and it lets a click through to the map while it goes
    this.place(el);
  }

  /** In the free map's middle, whole on screen: under the top bar, over the sheet when there is room. */
  private place(el: HTMLElement) {
    const a = this.at();
    const h = el.offsetHeight;
    const y = Math.max(a.top + 8 + h / 2, Math.min(a.y, a.bottom - 8 - h / 2));
    el.style.left = `${Math.round(a.x)}px`;
    el.style.top = `${Math.round(Math.min(y, innerHeight - 8 - h / 2))}px`;
  }
}

/**
 * The map tiles of the view that are still loading, as one job: every tile seen loading counts; it is done when it is
 * there (or failed, or no longer in the view – then nobody waits for it). Read from maplibre's tile managers (no public
 * API for the tiles in view); without them the job is just "loading / loaded".
 */
export function watchTiles(map: maplibregl.Map, overlay: LoadOverlay, opens: () => boolean) {
  const seen = new Set<string>();
  const done = new Set<string>();
  let timer = 0;
  const tick = () => {
    timer = 0;
    type Tile = { state: string; tileID?: { key?: string } };
    const managers = (map as unknown as { style?: { tileManagers?: Record<string, { _inViewTiles?: { getAllTiles?: () => Tile[] } }> } }).style?.tileManagers;
    const inView = new Set<string>();
    let known = !!managers;
    for (const [sid, m] of Object.entries(managers ?? {})) {
      const tiles = m._inViewTiles?.getAllTiles?.();
      if (!tiles) { known = false; break; }
      for (const tile of tiles) {
        const key = sid + '/' + (tile.tileID?.key ?? '');
        inView.add(key);
        const ready = tile.state === 'loaded' || tile.state === 'errored';
        if (!ready) seen.add(key);
        if (ready && seen.has(key)) done.add(key);
      }
    }
    const loaded = map.areTilesLoaded();
    if (!known) {
      if (loaded) { overlay.finish('tiles'); } else overlay.job('tiles', 0, TILE_UNITS, 'map', opens());
    } else {
      for (const key of seen) if (!inView.has(key)) done.add(key);
      if (seen.size && (loaded || done.size >= seen.size)) {
        overlay.job('tiles', seen.size * TILE_UNITS, seen.size * TILE_UNITS, 'map', opens());
        seen.clear(); done.clear();
      } else if (seen.size) overlay.job('tiles', done.size * TILE_UNITS, seen.size * TILE_UNITS, 'map', opens());
    }
    if (!loaded) timer = window.setTimeout(tick, 200);
  };
  const soon = () => { if (!timer) timer = window.setTimeout(tick, 50); };
  map.on('dataloading', soon);
  map.on('data', soon);
  map.on('idle', () => { clearTimeout(timer); tick(); });
}
