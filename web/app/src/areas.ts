// Map areas on demand (docs/design/web-planner.md §17; the app: map-cells.md §8/§9/§19.3, cells/CellTriggers + MapCells,
// ui/CellCard.kt CellCards.build – mirrored, web/parity.json). Where a map area (a zoom-10 cell) is not built yet, the
// page asks the doorman to build it – through the host's proxy (/maps/v1/cells/…, web/host/src/maps.js: the host adds
// the app key, the browser never holds it), polls its status every poll_s while the page is visible, shows the app's
// strip texts ("Preparing map for this area – about 6 min", rows, "2 more areas · waiting") and, when a cell is ready,
// reloads the cell sources – the files are read in place from R2 (/data/), so only its current.json appears.
// Triggers, mirrored for a desktop: a point set in the plan (T1, own cell), the position after Locate (T3), a search
// result shown (T2), the computed route (T4, the cells the line crosses, at most 12) and – the web's own, the owner's
// wish – the map resting at zoom >= 10 for REST_MS on a spot without detailed map (the centre cell). Each cell is asked
// for once per page (dedupe); cells already built are never asked for.
import type * as maplibregl from 'maplibre-gl';
import type { Cells } from './cells';
import type { MapNotices, Notice } from './mapnotice';

export type Why = 'here' | 'planned' | 'searched' | 'view';
type Reason = 'point' | 'near' | 'corridor';
type State =
  | { s: 'requested' } | { s: 'queued'; eta?: number } | { s: 'building'; progress: number; eta?: number }
  | { s: 'ready' } | { s: 'failed'; reason: string } | { s: 'offline'; message: string };
export type MapTexts = Record<string, string | { one: string | null; other: string }>;

const CELL_Z = 10;
/** The map rests this long at zoom >= 10 before its centre's area is asked for (scrolling alone never asks). */
export const REST_MS = 4000;
const MAX_CORRIDOR = 12;
const MAX_ROWS = 3; // CellCards.MAX_ROWS
const BACKOFF_S = [30, 60, 120, 300]; // server down / 5xx (the app: 1, 2, 5, 10 min – shorter while the page is open)
/** Web text (no app text fits: the app says "Map downloads not available in this build"). */
export const NOT_ON_WEB = 'The detailed map for this area is not available on the web yet – the overview map is shown.';
export const OVERVIEW_WAIT = 'Overview only – the detailed map for this area is asked for when the map rests here.';

const tileX = (lon: number) => Math.floor(((lon + 180) / 360) * 2 ** CELL_Z);
const tileY = (lat: number) => {
  const r = (lat * Math.PI) / 180;
  return Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * 2 ** CELL_Z);
};
export const cellOf = (lat: number, lon: number) => `10-${tileX(lon)}-${tileY(lat)}`;
const xy = (id: string) => id.split('-').slice(1).map(Number) as [number, number];

/** XmlStrings.format: `%N$s` / `%N$d` → the N-th argument. */
export function fmt(raw: string, ...args: (string | number)[]): string {
  return raw.replace(/%(\d)\$[sd]/g, (_, n) => String(args[Number(n) - 1]));
}

export class MapAreas {
  private states = new Map<string, State>();
  /** The open job (the app's CellJob): what the strip shows; later triggers join it. */
  private job: { why: Why; own: string | null; ids: string[] } | null = null;
  private hidden = false;
  /** False once the proxy says the web has no key (owner step open): nothing more is asked. */
  private available = true;
  private restTimer = 0;
  private pollTimer = 0;
  private failures = 0;
  private waitingRest: string | null = null;
  /** The map rests at zoom >= 10 on a spot without detailed map (what "not available on the web" refers to). */
  private centreAbsent = false;
  private naHidden = false;
  private t: MapTexts = {};

  constructor(private map: maplibregl.Map, private cells: Cells, private api: string, texts: Promise<MapTexts>,
    private notices: MapNotices, private fetchImpl: typeof fetch = (...a) => fetch(...a)) {
    texts.then((t) => { this.t = t; this.show(); }).catch((e) => console.warn('map texts not loaded', e));
    map.on('movestart', () => { clearTimeout(this.restTimer); if (this.waitingRest) { this.waitingRest = null; this.show(); } });
    map.on('moveend', () => this.rested());
    map.once('idle', () => this.rested()); // the page may open on a spot without detailed map
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && this.pending().length) this.poll(); });
  }

  private s(key: string, ...args: (string | number)[]) {
    const v = this.t[key];
    return typeof v === 'string' ? fmt(v, ...args) : key;
  }
  private p(key: string, count: number, ...args: (string | number)[]) {
    const v = this.t[key];
    return v && typeof v === 'object' ? fmt((count === 1 ? v.one : null) ?? v.other, ...args) : key;
  }

  /** T1 / T2 / T3: the area of a point set, a search result shown, the own position. */
  async wantPoint(lat: number, lon: number, why: Why) {
    const id = cellOf(lat, lon);
    if (await this.cells.exists(...xy(id))) return;
    this.request([id], 'point', why, id);
  }

  /** T4: the areas the computed route crosses (at most 12, nearest the start first). */
  async wantLine(line: [number, number][]) {
    const ids: string[] = [];
    for (const [la, lo] of line) { const id = cellOf(la, lo); if (!ids.includes(id)) ids.push(id); if (ids.length >= MAX_CORRIDOR) break; }
    const built = await Promise.all(ids.map((id) => this.cells.exists(...xy(id))));
    const missing = ids.filter((_, i) => !built[i]);
    if (missing.length) this.request(missing, 'corridor', 'planned', null);
  }

  /** The web's own trigger: the map rests at zoom >= 10 on a spot without detailed map → its centre area. */
  private rested() {
    clearTimeout(this.restTimer);
    const c = this.map.getCenter();
    const id = cellOf(c.lat, c.lng);
    if (this.map.getZoom() < CELL_Z) { this.centreAbsent = false; this.show(); return; }
    this.cells.exists(...xy(id)).then((there) => {
      const now = this.map.getCenter();
      if (cellOf(now.lat, now.lng) !== id) return; // moved on meanwhile
      this.centreAbsent = !there;
      if (there || this.states.has(id) || !this.available) { this.show(); return; }
      this.waitingRest = id;
      this.show();
      this.restTimer = window.setTimeout(() => {
        const now = this.map.getCenter();
        if (cellOf(now.lat, now.lng) !== id || this.map.getZoom() < CELL_Z) return;
        this.waitingRest = null;
        this.request([id], 'point', 'view', null);
      }, REST_MS);
    });
  }

  /** Ids not asked for yet; joins the open job (several triggers, one strip) or starts one. */
  private request(ids: string[], reason: Reason, why: Why, own: string | null) {
    const fresh = ids.filter((id) => !this.states.has(id));
    if (!fresh.length) return;
    if (!this.job || this.job.ids.every((id) => this.done(id))) { this.job = { why, own, ids: [] }; this.hidden = false; }
    for (const id of fresh) { this.job.ids.push(id); this.states.set(id, { s: 'requested' }); }
    if (!this.job.own && own) { this.job.own = own; this.job.why = why; }
    if (!this.available) { this.show(); return; }
    this.show();
    this.send('request', fresh, reason);
  }

  private done(id: string) { const s = this.states.get(id)?.s; return s === 'ready' || s === 'failed'; }
  private pending() { return [...this.states.entries()].filter(([, v]) => v.s !== 'ready' && v.s !== 'failed').map(([k]) => k); }

  /** One call through the proxy; [ids] at most 12 (the proxy's limit). */
  private async send(kind: 'request' | 'status', ids: string[], reason: Reason = 'point') {
    let r: Response;
    try {
      r = kind === 'request'
        ? await this.fetchImpl(this.api + 'cells/request', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ cells: ids, reason, formats: [2] }) })
        : await this.fetchImpl(this.api + 'cells/status?ids=' + ids.join(','));
    } catch (e) {
      console.info('map areas: no connection to the map server', e); // expected offline: INFO, the strip says it
      return this.offline(ids, this.s('maps_err_no_connection'));
    }
    if (r.status === 503 || r.status === 404) { // no key on this host (owner step open), or no proxy at all (a static host)
      console.info(`map areas: not available on this host (HTTP ${r.status})`);
      this.available = false;
      clearTimeout(this.pollTimer);
      return this.show();
    }
    if (r.status === 429 || r.status >= 500) {
      const after = Number(r.headers.get('retry-after')) || 0;
      return this.offline(ids, this.s('maps_err_unreachable'), after);
    }
    if (!r.ok) { // 400: a bug on our side – said once, not retried
      console.error(`map areas: ${kind} refused (HTTP ${r.status}): ${(await r.text()).slice(0, 200)}`);
      for (const id of ids) this.states.set(id, { s: 'failed', reason: this.s('maps_err_area_failed') });
      return this.show();
    }
    this.failures = 0;
    const a = (await r.json()) as { cells: { id: string; state: string; eta_s?: number; progress?: number; reason?: string }[]; poll_s?: number };
    const ready: [number, number][] = [];
    for (const c of a.cells ?? []) {
      const was = this.states.get(c.id);
      if (!was) continue;
      if (c.state === 'ready') { if (was.s !== 'ready') ready.push(xy(c.id)); this.states.set(c.id, { s: 'ready' }); }
      else if (c.state === 'queued') this.states.set(c.id, { s: 'queued', eta: c.eta_s });
      else if (c.state === 'building') this.states.set(c.id, { s: 'building', progress: c.progress ?? 0, eta: c.eta_s });
      else if (c.state === 'failed') this.states.set(c.id, { s: 'failed', reason: c.reason || this.s('maps_err_area_failed') });
      else this.states.set(c.id, { s: 'requested' }); // "none": the next poll asks again
    }
    // "none" after a request = the doorman lost it: asked again with the next poll
    if (ready.length) await this.cells.refresh(this.map, ready);
    this.show();
    this.schedule(Math.min(60, Math.max(5, a.poll_s ?? 15)));
  }

  private offline(ids: string[], message: string, afterS = 0) {
    for (const id of ids) if (!this.done(id)) this.states.set(id, { s: 'offline', message });
    this.show();
    this.schedule(Math.max(afterS, BACKOFF_S[Math.min(this.failures++, BACKOFF_S.length - 1)]));
  }

  private schedule(seconds: number) {
    clearTimeout(this.pollTimer);
    if (!this.pending().length) return;
    this.pollTimer = window.setTimeout(() => this.poll(), seconds * 1000);
  }

  /** Status of every open cell (cells never requested because the server was down are requested now). */
  private poll() {
    clearTimeout(this.pollTimer);
    if (document.visibilityState !== 'visible') return; // the app polls only in the foreground; visible again → poll
    const open = this.pending();
    const ask = open.filter((id) => { const s = this.states.get(id)!.s; return s === 'requested' || s === 'offline'; });
    const watch = open.filter((id) => !ask.includes(id));
    for (let i = 0; i < ask.length; i += 12) this.send('request', ask.slice(i, i + 12), 'point');
    for (let i = 0; i < watch.length; i += 12) this.send('status', watch.slice(i, i + 12));
  }

  /** The strip (mirror of CellCards.build). */
  private show() { this.notices.set('areas', this.build()); }

  build(): Notice | null {
    const close = { label: this.s('maps_hide_cd'), onClose: () => { this.hidden = true; } };
    const job = this.job;
    // no key on this host (owner step open): said while the map rests on a spot without detailed map – nothing hangs
    if (!this.available) return this.centreAbsent && !this.naHidden ? { kind: 'info', title: NOT_ON_WEB, close: { label: 'Hide', onClose: () => { this.naHidden = true; } } } : null;
    if (!job || this.hidden) return this.waitingRest ? { kind: 'info', title: OVERVIEW_WAIT } : null;
    const all = job.ids;
    const st = (id: string) => this.states.get(id)!;
    if (all.every((id) => st(id).s === 'ready')) return null; // closes by itself when every area is there (MapLoad)
    const open = all.filter((id) => !this.done(id));
    const failed = all.filter((id) => st(id).s === 'failed');
    if (!open.length && failed.length) {
      const reason = (st(failed[0]) as { reason: string }).reason;
      return { kind: 'error', title: failed.length === 1 ? reason : this.s('maps_card_failed_many', failed.length, reason), close: { ...close, label: 'Hide' } };
    }
    const busy = open.filter((id) => st(id).s !== 'offline');
    if (!busy.length) {
      const msg = (st(open[0]) as { message: string }).message;
      return { kind: 'info', title: msg === this.s('maps_err_no_connection') ? this.s('maps_card_offline') : msg, close };
    }
    const eta = open.map((id) => st(id)).map((x) => (x.s === 'queued' || x.s === 'building' ? x.eta : undefined)).filter((e): e is number => e != null);
    const title = eta.length ? this.s('maps_card_preparing_min', Math.max(1, Math.ceil(Math.max(...eta) / 60))) : this.s('maps_card_preparing');
    const ordered = [...all].sort((a, b) => (a === job.own ? -1 : b === job.own ? 1 : 0));
    const lines = ordered.slice(0, MAX_ROWS).map((id, i) => `${this.name(id, job, i)} · ${this.rowState(st(id))}`);
    const rest = ordered.slice(MAX_ROWS);
    if (rest.length) {
      const r = rest.filter((id) => st(id).s === 'ready').length;
      lines.push(r === rest.length ? this.p('maps_card_more_ready', rest.length, rest.length)
        : r > 0 ? this.p('maps_card_more_some_ready', rest.length, rest.length, r) : this.p('maps_card_more_waiting', rest.length, rest.length));
    }
    return { kind: 'busy', title, lines, close };
  }

  private rowState(x: State): string {
    switch (x.s) {
      case 'ready': return this.s('maps_row_ready');
      case 'queued': return x.eta != null ? this.s('maps_state_waiting_min', Math.ceil(x.eta / 60)) : this.s('maps_state_waiting');
      case 'building': {
        const pct = Math.round(x.progress * 100);
        return x.eta != null ? this.s('maps_state_building_min', pct, Math.ceil(x.eta / 60)) : this.s('maps_state_building', pct);
      }
      case 'failed': return x.reason;
      case 'offline': return x.message;
      default: return this.s('maps_state_waiting');
    }
  }

  /** CellCards.name: "Your point" / "Your position" for the own area, a direction for a neighbour, "Map area 2" else. */
  private name(id: string, job: { why: Why; own: string | null }, index: number): string {
    if (id === job.own) return this.s(job.why === 'here' ? 'maps_row_your_position' : 'maps_row_your_point');
    if (!job.own) return this.s('maps_row_area_n', index + 1);
    const [cx, cy] = xy(id), [ox, oy] = xy(job.own);
    const a = ((Math.atan2(oy - cy, cx - ox) * 180) / Math.PI + 360) % 360;
    const dirs = ['east', 'north_east', 'north', 'north_west', 'west', 'south_west', 'south', 'south_east'];
    return this.s('maps_row_area_' + dirs[Math.floor((a + 22.5) / 45) % 8]);
  }
}
