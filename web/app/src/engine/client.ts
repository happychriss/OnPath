// Promise API around the engine worker. Newer requests supersede older ones in the UI (the caller keeps the last id).
import type { Activity, EngineReply, EngineRequest } from './types';

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

  plan(points: [number, number][], activity: Activity, sac: number) {
    return this.ask({ type: 'plan', points: enc(points), activity, sac });
  }

  /** Index where a clicked point goes (the app's WaypointInsertion rule). */
  async insertIndex(points: [number, number][], lat: number, lon: number, lineSnapM: number): Promise<number> {
    const r = await this.ask({ type: 'insert', points: enc(points), lat, lon, lineSnapM });
    return r.index ?? points.length;
  }
}

const enc = (points: [number, number][]) => points.map(([lat, lon]) => `${lat.toFixed(6)},${lon.toFixed(6)}`).join(';');
