// Promise API around the engine worker. Newer requests supersede older ones in the UI (the caller keeps the last id).
import type { Activity, EngineReply, EngineRequest, SharedPlan } from './types';

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

  /** The plan in a link or code, or null when there is none. */
  async parse(text: string): Promise<SharedPlan | null> {
    const r = await this.ask({ type: 'parse', text });
    return r.ok ? r.shared ?? null : null;
  }

  /** Index where a clicked point goes (the app's WaypointInsertion rule). */
  async insertIndex(points: [number, number][], lat: number, lon: number, lineSnapM: number): Promise<number> {
    const r = await this.ask({ type: 'insert', points: enc(points), lat, lon, lineSnapM });
    return r.index ?? points.length;
  }
}

const enc = (points: [number, number][]) => points.map(([lat, lon]) => `${lat.toFixed(6)},${lon.toFixed(6)}`).join(';');
