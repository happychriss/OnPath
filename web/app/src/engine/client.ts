// Promise API around the engine worker. Newer requests supersede older ones in the UI (the caller keeps the last id).
import type { Activity, EngineReply, EngineRequest, Hit, PlacePreview, PointRows, SharedPlan } from './types';

type NoId<T> = T extends unknown ? Omit<T, 'id'> : never;

export class EngineClient {
  private worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
  private next = 1;
  private waiting = new Map<number, (r: EngineReply) => void>();

  constructor() {
    this.worker.onmessage = (ev: MessageEvent<EngineReply>) => {
      this.waiting.get(ev.data.id)?.(ev.data);
      this.waiting.delete(ev.data.id);
    };
  }

  private ask(req: NoId<EngineRequest>): Promise<EngineReply> {
    const id = this.next++;
    return new Promise((resolve) => {
      this.waiting.set(id, resolve);
      this.worker.postMessage({ ...req, id } as EngineRequest);
    });
  }

  /** [base] = where engine/ and brouter/ are served; [segments] = rd5 files with HTTP range support. */
  configure(base: string, segments: string, packed = false) { return this.ask({ type: 'config', base, segments, packed }); }

  route(points: [number, number][], activity: Activity, sac: number) {
    return this.ask({ type: 'route', points: enc(points), activity, sac });
  }

  /** [pinned] = indices of points the route passes exactly (POI / place), as in the app's Planner. */
  plan(points: [number, number][], activity: Activity, sac: number, pinned: number[] = []) {
    return this.ask({ type: 'plan', points: enc(points), activity, sac, pinned: pinned.join(',') });
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
  async search(text: string, lat: number, lon: number, route: [number, number][]): Promise<Hit[]> {
    const r = await this.ask({ type: 'search', text, lat, lon, route: enc(route) });
    return r.ok && r.text ? JSON.parse(r.text) : [];
  }

  /** Offline + online hits for the last query, given Photon's answer (the app's merge). */
  async searchOnline(text: string, photon: string): Promise<Hit[] | null> {
    const r = await this.ask({ type: 'searchOnline', text, photon });
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
