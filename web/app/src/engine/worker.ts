/// <reference lib="webworker" />
// The routing engine in a Web Worker: the app's own Kotlin + BRouter, compiled to Wasm by TeaVM (web/engine).
// It fetches what BRouter reads – the profiles once, the rd5 1° parts a plan needs via HTTP range (the phone's rule,
// Rd5Parts.forCorridor, decided inside the engine) – then routes. The page never blocks while it computes.
import type { EngineRequest, EngineReply, RouteStats } from './types';

interface WasmEngine {
  init(root: string): void;
  putFile(path: string, data: Int8Array): void;
  partsNeeded(points: string): string;
  putLayout(tile: string, index: Int8Array, tail: Int8Array): void;
  putPart(tile: string, index: number, data: Int8Array): boolean;
  route(points: string, activity: string, sac: number): string;
  plan(points: string, activity: string, sac: number): string;
  insertIndex(points: string, lat: number, lon: number, lineSnapM: number): number;
  problems(): string;
}

declare const TeaVM: { wasmGC: { load(url: string): Promise<{ exports: WasmEngine }> } };

let engine: WasmEngine | null = null;
let base = '';
let segments = '';
const loadMs = { engine: 0 };
const missingTiles = new Set<string>();
const layouts = new Map<string, number[]>();

async function getEngine(): Promise<WasmEngine> {
  if (engine) return engine;
  const t0 = performance.now();
  await import(/* @vite-ignore */ new URL('engine/onpath-web-engine.wasm-runtime.js', base).href);
  const { exports } = await TeaVM.wasmGC.load(new URL('engine/onpath-web-engine.wasm', base).href);
  exports.init('/onpath');
  const list: string[] = await (await fetch(new URL('brouter/index.json', base))).json();
  await Promise.all(list.map(async (f) => {
    const buf = await (await fetch(new URL('brouter/' + f, base))).arrayBuffer();
    exports.putFile('assets/brouter/' + f, new Int8Array(buf));
  }));
  loadMs.engine = performance.now() - t0;
  engine = exports;
  return exports;
}

async function range(url: string, from: number, to: number): Promise<{ bytes: Uint8Array; total: number } | null> {
  const r = await fetch(url, { headers: { Range: `bytes=${from}-${to}` } });
  if (r.status === 404) return null;
  if (r.status !== 206) throw new Error(`${url}: HTTP ${r.status} (range requests needed)`);
  const total = Number((r.headers.get('Content-Range') ?? '').split('/')[1] ?? NaN);
  return { bytes: new Uint8Array(await r.arrayBuffer()), total };
}

/** End offsets of the 25 parts (low 48 bits of each big-endian long in the 200-byte index). */
function partEnds(index: Uint8Array): number[] {
  const v = new DataView(index.buffer, index.byteOffset, 200);
  const ends: number[] = [];
  for (let i = 0; i < 25; i++) ends.push(Number(v.getBigUint64(i * 8) & 0xffffffffffffn));
  return ends;
}

/** Fetches the parts a plan needs that the engine does not have yet. Returns bytes downloaded. */
async function ensureParts(e: WasmEngine, points: string): Promise<number> {
  const need: [string, number][] = JSON.parse(e.partsNeeded(points));
  let bytes = 0;
  const tiles = [...new Set(need.map(([t]) => t))].filter((t) => !missingTiles.has(t));
  await Promise.all(tiles.map(async (tile) => {
    const url = new URL(tile, segments).href;
    if (!layouts.has(tile)) {
      const head = await range(url, 0, 199);
      if (!head) { missingTiles.add(tile); return; } // no data there: BRouter answers "no routing data"
      const ends = partEnds(head.bytes);
      const tail = await range(url, ends[24], head.total - 1);
      bytes += 200 + (tail?.bytes.length ?? 0);
      e.putLayout(tile, new Int8Array(head.bytes.buffer), new Int8Array(tail?.bytes.buffer ?? new ArrayBuffer(0)));
      layouts.set(tile, ends);
    }
    const ends = layouts.get(tile)!;
    await Promise.all(need.filter(([t]) => t === tile).map(async ([, i]) => {
      const start = i === 0 ? 200 : ends[i - 1];
      if (ends[i] <= start) return; // empty part (sea / no ways)
      const part = await range(url, start, ends[i] - 1);
      if (!part) return;
      bytes += part.bytes.length;
      if (!e.putPart(tile, i, new Int8Array(part.bytes.buffer))) throw new Error(`${tile} part ${i} does not fit its index`);
    }));
  }));
  return bytes;
}

self.onmessage = async (ev: MessageEvent<EngineRequest>) => {
  const req = ev.data;
  try {
    if (req.type === 'config') {
      base = req.base; segments = req.segments;
      const e = await getEngine();
      reply({ id: req.id, ok: true, stats: { engineLoadMs: loadMs.engine } as RouteStats, problems: JSON.parse(e.problems()) });
      return;
    }
    const e = await getEngine();
    if (req.type === 'insert') {
      reply({ id: req.id, ok: true, index: e.insertIndex(req.points, req.lat, req.lon, req.lineSnapM) });
      return;
    }
    const t0 = performance.now();
    const fetched = await ensureParts(e, req.points);
    const t1 = performance.now();
    const json = req.type === 'plan' ? e.plan(req.points, req.activity, req.sac) : e.route(req.points, req.activity, req.sac);
    const t2 = performance.now();
    reply({
      id: req.id, ok: true, [req.type === 'plan' ? 'plan' : 'result']: JSON.parse(json),
      stats: { engineLoadMs: loadMs.engine, fetchMs: t1 - t0, routeMs: t2 - t1, bytesFetched: fetched },
      problems: JSON.parse(e.problems()),
    });
  } catch (err) {
    reply({ id: req.id, ok: false, error: String(err) });
  }
};

function reply(r: EngineReply) { (self as unknown as DedicatedWorkerGlobalScope).postMessage(r); }
