// Promise API around the engine worker. Newer requests supersede older ones in the UI (the caller keeps the last id).
import type { Activity, EngineReply, EngineRequest, Hit, PlacePreview, PointRows, Progress, SharedPlan } from './types';
import { t } from '../i18n';

type NoId<T> = T extends unknown ? Omit<T, 'id'> : never;

export class EngineClient {
  private worker!: Worker;
  private next = 1;
  private waiting = new Map<number, (r: EngineReply) => void>();
  private progress = new Map<number, (p: Progress) => void>();
  /** Every unanswered request as sent – a fresh worker gets them again in their order ([restart]). */
  private sent = new Map<number, EngineRequest>();
  /** Running plans: id → when it started routing (0 = still waiting for routing data). */
  private plans = new Map<number, number>();
  /** What a fresh worker must be told first: the page's config, the pace levels, the undo / redo history. */
  private cfg: NoId<EngineRequest> | null = null;
  private pace: (string | number | boolean)[] | null = null;
  private history = '';
  /** For the tests and the diagnostics: how often a long plan was stopped, and how long the last fresh worker took. */
  readonly restarts = { count: 0, lastMs: 0 };

  constructor() { this.spawn(); }

  private spawn() {
    const w = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
    this.worker = w;
    w.onmessage = (ev: MessageEvent<EngineReply>) => {
      if (w !== this.worker) return; // a stopped worker's last words
      const r = ev.data;
      if (r.progress) {
        if (r.progress.phase !== 'download' && this.plans.get(r.id) === 0) this.plans.set(r.id, performance.now());
        this.progress.get(r.id)?.(r.progress); return; // not the answer yet
      }
      if (r.history != null) this.history = r.history;
      this.waiting.get(r.id)?.(r);
      this.waiting.delete(r.id);
      this.progress.delete(r.id);
      this.sent.delete(r.id);
      this.plans.delete(r.id);
    };
    // robust first: a crashed worker (failed engine load, a trap the worker could not catch) must never leave the
    // page waiting forever – every open request gets a plain failure the caller already shows
    const failAll = (why: string) => {
      if (w !== this.worker) return;
      console.error('[engine] ' + why); // reported like the app's Problems log (console in the browser)
      for (const [id, resolve] of this.waiting) resolve({ id, ok: false, error: t('web_engine_stopped') });
      this.waiting.clear(); this.sent.clear(); this.plans.clear(); this.progress.clear();
    };
    w.onerror = (e) => failAll('worker error: ' + (e.message || 'unknown'));
    w.onmessageerror = () => failAll('worker message could not be read');
  }

  /** No answer at all after this long (routing has its own, shorter time limit in the engine) = the worker hangs. */
  private static readonly NO_ANSWER_MS = 180_000;
  /** A dropped plan still routing this long after the drop blocks the one worker: it is stopped ([restart]). */
  static readonly DROP_GRACE_MS = 1000;

  private ask(req: NoId<EngineRequest>, onProgress?: (p: Progress) => void): Promise<EngineReply> {
    const id = this.next++;
    if (onProgress) this.progress.set(id, onProgress);
    if (req.type === 'config') this.cfg = req;
    if (req.type === 'call' && req.fn === 'paceSet') this.pace = req.args;
    if (req.type === 'plan') this.plans.set(id, 0);
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (!this.waiting.delete(id)) return;
        this.sent.delete(id); this.plans.delete(id);
        console.error(`[engine] no answer to "${req.type}" in ${EngineClient.NO_ANSWER_MS / 1000} s`);
        resolve({ id, ok: false, error: t('web_engine_no_answer') });
      }, EngineClient.NO_ANSWER_MS);
      this.waiting.set(id, (r) => { clearTimeout(timer); resolve(r); });
      const full = { ...req, id } as EngineRequest;
      this.sent.set(id, full);
      this.worker.postMessage(full);
    });
  }

  /**
   * The page dropped every running plan (a newer edit, Neu, Abbrechen – the app's cancelRun). BRouter in the worker
   * cannot be stopped mid-way and the one worker answers in order, so a long dropped plan (Meran → Hilden: minutes)
   * would hold every later call. A plan still waiting for routing data is skipped by the worker; one still routing
   * [DROP_GRACE_MS] after the drop is stopped by starting a fresh worker ([restart]). Its promise answers
   * {cancelled: true} – the page ignores it as it ignores every superseded plan.
   */
  dropPlans() {
    const ids = [...this.plans.keys()];
    if (!ids.length) return;
    for (const target of ids) this.worker.postMessage({ type: 'drop', id: 0, target } as EngineRequest);
    const blocking = () => ids.some((i) => { const at = this.plans.get(i) ?? 0; return at > 0 && performance.now() - at >= EngineClient.DROP_GRACE_MS; });
    // routing for a while already: stopped at once; just started: a moment to finish by itself (a short route)
    if (blocking()) { this.restart(ids.filter((i) => this.plans.has(i))); return; }
    setTimeout(() => {
      if (ids.some((i) => (this.plans.get(i) ?? 0) > 0)) this.restart(ids.filter((i) => this.plans.has(i)));
    }, EngineClient.DROP_GRACE_MS);
  }

  /**
   * A fresh worker in place of one blocked by the dropped plans [ids]: those answer {cancelled}, the new worker is told
   * the config, the pace levels and the undo / redo history first, then every other open request again, in its order.
   * The routing data it needs is read again (the browser's cache); the plan's leg cache is rebuilt by the next plan.
   */
  private restart(ids: number[]) {
    const t0 = performance.now();
    this.worker.terminate();
    for (const id of ids) {
      this.waiting.get(id)?.({ id, ok: false, error: 'dropped', cancelled: true });
      this.waiting.delete(id); this.sent.delete(id); this.plans.delete(id); this.progress.delete(id);
    }
    this.spawn();
    this.restarts.count++;
    console.info(`[engine] a dropped plan blocked the engine – fresh worker (${this.restarts.count})`);
    const internal = (req: NoId<EngineRequest>, after?: (r: EngineReply) => void) => {
      const id = this.next++;
      this.waiting.set(id, (r) => after?.(r));
      this.worker.postMessage({ ...req, id } as EngineRequest);
    };
    if (this.cfg) internal(this.cfg, () => {
      this.restarts.lastMs = Math.round(performance.now() - t0);
      console.info(`[engine] fresh worker ready in ${this.restarts.lastMs} ms`);
    });
    if (this.history) internal({ type: 'call', fn: 'historyImport', args: [this.history] });
    if (this.pace) internal({ type: 'call', fn: 'paceSet', args: this.pace });
    for (const id of [...this.sent.keys()].sort((a, b) => a - b)) this.worker.postMessage(this.sent.get(id)!);
  }

  /** [base] = where engine/ and brouter/ are served; [segments] = rd5 files with HTTP range support. */
  configure(base: string, segments: string, packed = false, lang = 'en') { return this.ask({ type: 'config', base, segments, packed, lang }); }

  route(points: [number, number][], activity: Activity, sac: number) {
    return this.ask({ type: 'route', points: enc(points), activity, sac });
  }

  /** [pinned] = indices of points the route passes exactly (POI / place), as in the app's Planner;
   *  [rounds] ≥ 1 = a plan the user started: at the time limit the reply's plan is {slow: rounds} (SlowPlan); 0 = never asks. */
  plan(points: [number, number][], activity: Activity, sac: number, pinned: number[] = [], onProgress?: (p: Progress) => void, names = '', rounds = 0) {
    this.dropPlans(); // a new plan supersedes every running one (the page keeps the newest)
    return this.ask({ type: 'plan', points: enc(points), activity, sac, pinned: pinned.join(','), names, rounds }, onProgress);
  }

  /** The plan code (QR code, link) – the app's PlanCode. */
  async encode(plan: SharedPlan): Promise<string> {
    const r = await this.ask({ type: 'encode', plan });
    if (!r.ok || r.code == null) throw new Error(r.error ?? 'no plan code');
    return r.code;
  }

  /** One of the app's plan edits (PlanEdits): the new point list. */
  async edit(op: string, points: [number, number][], index = 0, to = 0): Promise<[number, number][]> {
    const r = await this.ask({ type: 'edit', op, points: enc(points), index, to });
    if (!r.ok || r.text == null) return points;
    return dec(r.text);
  }

  /** The Points list rows (the app's PlanPoints.rows); [names] = lines "index<TAB>name". */
  async rows(points: [number, number][], names: string): Promise<PointRows> {
    const r = await this.ask({ type: 'rows', points: enc(points), names });
    return r.ok && r.text ? JSON.parse(r.text) : { roundTrip: false, rows: [] };
  }

  /** Indexes the map areas (cells) not indexed yet; [dir] = folder URL with pois.geojson, base/overlays.pmtiles. */
  async searchAreas(areas: { key: string; dir: string }[]): Promise<void> { await this.ask({ type: 'searchAreas', areas }); }

  /** Offline hits (the app's index + ranking). */
  async search(text: string, lat: number, lon: number, route: [number, number][], category = ''): Promise<Hit[]> {
    const r = await this.ask({ type: 'search', text, lat, lon, route: enc(route), category });
    return r.ok && r.text ? JSON.parse(r.text) : [];
  }

  /** Offline + online hits for the last query, given Photon's answer (the app's merge). */
  async searchOnline(text: string, photon: string, category = ''): Promise<Hit[] | null> {
    const r = await this.ask({ type: 'searchOnline', text, photon, category });
    return r.ok && r.text ? JSON.parse(r.text) : null;
  }

  async placePreview(points: [number, number][], lat: number, lon: number): Promise<PlacePreview> {
    const r = await this.ask({ type: 'placePreview', points: enc(points), lat, lon });
    return r.ok && r.text ? JSON.parse(r.text) : { subtitle: '' };
  }

  /** The plan in a link or code, or null when there is none. */
  async parse(text: string): Promise<SharedPlan | null> {
    const r = await this.ask({ type: 'parse', text });
    return r.ok ? r.shared ?? null : null;
  }

  /** One of the app's small UI rules by name (worker CALLS): undo / redo, gestures, menus, Google link. */
  async call(fn: string, ...args: (string | number | boolean)[]): Promise<string> {
    const r = await this.ask({ type: 'call', fn, args });
    if (!r.ok) throw new Error(r.error ?? fn + ' failed');
    return r.text ?? '';
  }

  /** Downloads the routing data around [lat],[lon] ahead of the first plan; resolves with the bytes fetched. */
  async prefetch(lat: number, lon: number): Promise<number> {
    const r = await this.ask({ type: 'prefetch', lat, lon });
    return r.stats?.bytesFetched ?? 0;
  }

  /** A GPX file read with the app's GpxParser + SportType (WebImport.kt): the track panel's JSON, or {error}. */
  async gpxRead(data: Uint8Array, fileName: string, activity: Activity, sac: number): Promise<string> {
    const r = await this.ask({ type: 'gpxRead', data, fileName, activity, sac });
    if (!r.ok || r.text == null) throw new Error(r.error ?? 'GPX not read');
    return r.text;
  }

  /** The app's TrackToPlan for [track] (routing data along it fetched first): {points} or {error}. */
  async planFromTrack(track: [number, number][], activity: Activity, sac: number): Promise<string> {
    const r = await this.ask({ type: 'planFromTrack', points: enc(track), activity, sac });
    if (!r.ok || r.text == null) throw new Error(r.error ?? 'track not planned');
    return r.text;
  }

  /** Index where a clicked point goes (the app's WaypointInsertion rule). */
  async insertIndex(points: [number, number][], lat: number, lon: number, lineSnapM: number): Promise<number> {
    const r = await this.ask({ type: 'insert', points: enc(points), lat, lon, lineSnapM });
    return r.index ?? points.length;
  }
}

const enc = (points: [number, number][]) => points.map(([lat, lon]) => `${lat.toFixed(6)},${lon.toFixed(6)}`).join(';');
const dec = (text: string): [number, number][] =>
  text ? text.split(';').map((p) => p.split(',').map(Number) as [number, number]) : [];
