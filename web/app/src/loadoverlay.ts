// The load overlay (owner 2026-11-13: „man ist im Urlaub und plant für einen neuen Ort … Die Meldung, dass die Karte noch
// geladen wird, sollte als Overlay erscheinen, mit einer prozentualen Fortschrittsangabe“ – docs/design/web-planner.md
// §27). One calm card at the top of the map while the map of a new area loads – its tiles, the routing data under a
// plan, a map area the server prepares – with one line, a percent and a bar; the route calculation is its last phase.
// While it shows it is the only wait: the map strip's other spinners, the route's wait box and the sheet's wait line
// step back (mapnotice.ts, main.ts). It goes by itself when everything is there.
//
// What is measured (honest numbers only, weighted into one percent that never goes back):
// - routing data: bytes received / bytes needed – the sizes come from the rd5 files' own index (worker.ts ensureParts);
// - map tiles: the tiles of the view that were still loading, counted as they load (maplibre's tile states; their size
//   is not known before they come, so each tile weighs TILE_UNITS);
// - a map area the server prepares: the doorman's own progress per area (areas.ts), an area weighs BUILD_UNITS;
// - not measurable, weighted by a fixed share and done in one step: the routing engine starting (ENGINE_UNITS), a shared
//   link before its routing data size is known (LINK_UNITS, replaced by the real bytes once known), the route
//   calculation (CALC_UNITS – the router cannot say how far it is).
import type * as maplibregl from 'maplibre-gl';
import type { MapNotices, Notice } from './mapnotice';

export const TILE_UNITS = 40_000; // a typical vector tile, bytes
export const BUILD_UNITS = 20_000_000; // a map area being prepared takes minutes – it outweighs every download
export const ENGINE_UNITS = 3_000_000; // the Wasm engine and its profiles
export const LINK_UNITS = 4_000_000; // a link's routing data before its size is known (a typical 10 km corridor)
export const CALC_UNITS = 1_000_000; // the route calculation, the last phase

/**
 * Jobs with done / total units → one fraction 0..1 that never goes back. When the work grows (a job added, a total
 * changed) the bar does not jump back: what is left of it is shared by the work still open from that moment (rebase).
 */
export class LoadProgress {
  private jobs = new Map<string, { done: number; total: number }>();
  private base = 0;
  private from = 0;
  private shown = 0;

  set(id: string, done: number, total: number) {
    total = Math.max(0, total);
    done = Math.min(Math.max(0, done), total);
    const old = this.jobs.get(id);
    if (old && old.total === total && done >= old.done) { old.done = done; this.advance(); return; }
    this.advance();
    this.jobs.set(id, { done, total });
    this.rebase();
  }
  finish(id: string) { const j = this.jobs.get(id); if (j) this.set(id, j.total, j.total); }
  remove(id: string) { if (!this.jobs.has(id)) return; this.advance(); this.jobs.delete(id); this.rebase(); }
  isOpen(id: string) { const j = this.jobs.get(id); return !!j && j.done < j.total; }
  openIds(): string[] { return [...this.jobs].filter(([, j]) => j.done < j.total).map(([id]) => id); }
  get open() { return this.openIds().length > 0; }
  /** 0..1; below 1 while anything is open (never 100 % before the end, never back). */
  fraction(): number {
    this.advance();
    return this.open ? Math.min(this.shown, 0.99) : this.jobs.size ? 1 : this.shown;
  }
  /** A new wait: finished jobs are forgotten, the bar starts at 0 for the work still open. */
  restart() {
    for (const [id, j] of this.jobs) if (j.done >= j.total) this.jobs.delete(id);
    this.base = 0; this.shown = 0; this.from = this.sums().d;
  }
  private sums() { let d = 0, t = 0; for (const j of this.jobs.values()) { d += j.done; t += j.total; } return { d, t }; }
  private rebase() { this.base = this.shown; this.from = this.sums().d; }
  private advance() {
    const { d, t } = this.sums();
    const room = t - this.from;
    const g = room <= 0 ? 1 : (d - this.from) / room;
    this.shown = Math.max(this.shown, Math.min(1, this.base + (1 - this.base) * Math.max(0, g)));
  }
}

/** How a job speaks on the card; [opens]: it alone brings the card up (the engine and the calculation only join one). */
export interface JobLook {
  title: string;
  /** Higher wins the card's line (a map area being prepared > the map loading > the route calculation). */
  prio: number;
  opens: boolean;
  lines?: string[];
  close?: Notice['close'];
  action?: Notice['action'];
}

export class LoadOverlay {
  readonly progress = new LoadProgress();
  private looks = new Map<string, JobLook>();
  private visible = false;
  private showTimer = 0;
  private hideTimer = 0;
  private renderTimer = 0;
  private lastPct = -1;

  /** [delayMs]: a wait shorter than this shows nothing (no flash); [onVisible]: the page's other waits step back. */
  constructor(private notices: MapNotices, private delayMs: number, private onVisible: (on: boolean) => void,
    private percent: (f: number) => string) {}

  get shown() { return this.visible; }
  /** The card is coming (its delay runs) – onewait.ts brings it at once when two other waits would show meanwhile. */
  get due() { return !this.visible && !!this.showTimer; }
  showNow() { if (!this.due) return; clearTimeout(this.showTimer); this.showTimer = 0; this.show(); }
  /** Called when the card becomes due (the arbiter decides again). */
  onDue: (() => void) | null = null;

  job(id: string, done: number, total: number, look: JobLook) {
    this.looks.set(id, look);
    this.progress.set(id, done, total);
    this.update();
  }
  finish(id: string) { if (this.looks.has(id)) { this.progress.finish(id); this.update(); } }
  remove(id: string) { if (this.looks.delete(id)) { this.progress.remove(id); this.update(); } }
  isOpen(id: string) { return this.progress.isOpen(id); }

  private update() {
    const open = this.progress.openIds();
    if (!this.visible) {
      if (!open.some((id) => this.looks.get(id)?.opens)) {
        clearTimeout(this.showTimer); this.showTimer = 0;
        if (!open.length) { this.progress.restart(); for (const id of [...this.looks.keys()]) if (!this.progress.isOpen(id)) this.looks.delete(id); }
        return;
      }
      if (!this.showTimer) {
        this.progress.restart(); // the percent counts from the moment the wait began
        this.showTimer = window.setTimeout(() => { this.showTimer = 0; if (this.progress.openIds().some((id) => this.looks.get(id)?.opens)) this.show(); }, this.delayMs);
        this.onDue?.();
      }
      return;
    }
    if (open.length && this.hideTimer) { clearTimeout(this.hideTimer); this.hideTimer = 0; this.progress.restart(); }
    this.scheduleRender();
  }

  private show() {
    this.visible = true;
    this.lastPct = -1;
    this.onVisible(true);
    this.render();
  }

  private scheduleRender() {
    if (this.renderTimer) return;
    this.renderTimer = window.setTimeout(() => { this.renderTimer = 0; this.render(); }, 80);
  }

  private render() {
    if (!this.visible) return;
    const open = this.progress.openIds();
    const f = this.progress.fraction();
    if (!open.length) {
      // done: the full bar for a moment, then the card goes by itself
      this.put(1, this.lastLook ?? { title: '', prio: 0, opens: false });
      if (!this.hideTimer) this.hideTimer = window.setTimeout(() => this.hide(), 350);
      return;
    }
    const look = open.map((id) => this.looks.get(id)!).filter(Boolean).sort((a, b) => b.prio - a.prio)[0];
    this.lastLook = look;
    this.put(f, look, open);
  }
  private lastLook: JobLook | null = null;

  private put(f: number, look: JobLook, open: string[] = []) {
    const looks = open.map((id) => this.looks.get(id)).filter((l): l is JobLook => !!l);
    const pct = Math.max(this.lastPct, Math.floor(f * 100));
    this.lastPct = pct;
    this.notices.set('load', {
      kind: 'busy', title: look.title, lines: look.lines, progress: pct, progressText: this.percent(pct / 100),
      close: look.close, action: looks.find((l) => l.action)?.action,
    });
  }

  private hide() {
    this.hideTimer = 0;
    if (this.progress.open) { this.render(); return; }
    this.visible = false;
    this.notices.set('load', null);
    this.progress.restart();
    for (const id of [...this.looks.keys()]) if (!this.progress.isOpen(id)) this.looks.delete(id);
    this.lastLook = null;
    this.onVisible(false);
  }
}

/**
 * The map tiles of the view that are still loading, as one job: every tile seen loading counts; it is done when it is
 * there (or failed, or no longer in the view – then nobody waits for it). Read from maplibre's tile managers (no public
 * API for the tiles in view); without them the job is just "loading / loaded".
 */
export function watchTiles(map: maplibregl.Map, overlay: LoadOverlay, look: () => JobLook) {
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
      if (loaded) { overlay.finish('tiles'); } else overlay.job('tiles', 0, TILE_UNITS, look());
    } else {
      for (const key of seen) if (!inView.has(key)) done.add(key);
      if (seen.size && (loaded || done.size >= seen.size)) {
        overlay.job('tiles', seen.size * TILE_UNITS, seen.size * TILE_UNITS, look());
        seen.clear(); done.clear();
      } else if (seen.size) overlay.job('tiles', done.size * TILE_UNITS, seen.size * TILE_UNITS, look());
    }
    if (!loaded) timer = window.setTimeout(tick, 200);
  };
  const soon = () => { if (!timer) timer = window.setTimeout(tick, 50); };
  map.on('dataloading', soon);
  map.on('data', soon);
  map.on('idle', () => { clearTimeout(timer); tick(); });
}
