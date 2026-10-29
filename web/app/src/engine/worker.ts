/// <reference lib="webworker" />
// The routing engine in a Web Worker: the app's own Kotlin + BRouter, compiled to Wasm by TeaVM (web/engine).
// It fetches what BRouter reads – the profiles once, the rd5 1° parts a plan needs via HTTP range (the phone's rule,
// Rd5Parts.forCorridor, decided inside the engine) – then routes. The page never blocks while it computes.
import type { EngineRequest, EngineReply, RouteStats } from './types';
import { fetchPacked } from '../b64';

interface WasmEngine {
  init(root: string): void;
  putFile(path: string, data: Int8Array): void;
  partsNeeded(points: string): string;
  putLayout(tile: string, index: Int8Array, tail: Int8Array): void;
  putPart(tile: string, index: number, data: Int8Array): boolean;
  writeParts(): void;
  route(points: string, activity: string, sac: number): string;
  plan(points: string, activity: string, sac: number, pinned: string): string;
  planCode(points: string, activity: string, sac: number, pinned: string, names: string, title: string): string;
  planParse(text: string): string;
  edit(op: string, points: string, index: number, to: number): string;
  rows(points: string, names: string): string;
  placePreview(points: string, lat: number, lon: number): string;
  searchAddArea(key: string, dir: string): number;
  searchHasArea(key: string): boolean;
  search(text: string, lat: number, lon: number, route: string): string;
  searchOnline(text: string, photonJson: string): string;
  insertIndex(points: string, lat: number, lon: number, lineSnapM: number): number;
  historyRecord(points: string): void;
  historyStep(op: string, points: string): string;
  historyState(): string;
  gesture(menuOpen: boolean, marker: number, poi: boolean, planEmpty: boolean): string;
  pointMenu(index: number, count: number, isPlace: boolean, roundTrip: boolean): string;
  fromMeBlocked(status: string, hasFix: boolean): string;
  googleMaps(name: string, lat: number, lon: number, label: string): string;
  poiMenu(kind: string, name: string, ele: string, lat: number, lon: number): string;
  gpx(name: string, activity: string, sac: number): string;
  activities(): string;
  problems(): string;
  longRoute(points: string, activity: string): string;
  planCancel(cause: string, canUndo: boolean, canRedo: boolean): string;
  planGaps(points: string, activity: string, names: string): string;
  gpxRead(data: Int8Array, fileName: string, activity: string, sac: number): string;
  planFromTrack(track: string, activity: string, sac: number): string;
}

/** Small synchronous calls the page may make by name (the app's pure UI rules – see client.ts). */
const CALLS = new Set(['historyRecord', 'historyStep', 'historyState', 'gesture', 'pointMenu', 'fromMeBlocked', 'googleMaps', 'poiMenu', 'gpx', 'activities',
  // web UI rules (WebUi.kt): route name proposal (Save), POI kind filters (Map content)
  'routeName', 'poiKinds', 'poiFilter',
  // the app's plan rules before routing (WebPlanRules.kt): long-route question, what Cancel does
  'longRoute', 'planCancel', 'planGaps',
  // plan sync (WebSync.kt – the app's com.onpath.app.sync)
  'syncPairNew', 'syncPairParse', 'syncKeys', 'syncNewId', 'syncNewDevice', 'syncList', 'syncDevices', 'syncState', 'syncSave',
  'syncRename', 'syncDelete', 'syncHello', 'syncForget', 'syncApply', 'syncOutbox', 'syncAcked']);

declare const TeaVM: { wasmGC: { load(url: string): Promise<{ exports: WasmEngine }> } };

let base = '';
let segments = '';
let packed = false; // preview host: rd5 published as <name>.b64.txt (see packed.ts)
const loadMs = { engine: 0 };

/**
 * The engine, loaded once. The promise is kept (not only the result): the page's first calls arrive while the Wasm
 * file is still loading, and each would otherwise load its own engine – parts stored in one were then missing in the
 * other ("part does not fit its index" on a slow line).
 */
let loading: Promise<WasmEngine> | null = null;
function getEngine(): Promise<WasmEngine> {
  loading ??= (async () => {
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
    return exports;
  })();
  return loading;
}

/** Whole files: the packed preview host (base64, no ranges) and servers that ignore ranges. Kept as promises, so
 * a prefetch and a plan asking at the same time download once. */
const whole = new Map<string, Promise<Uint8Array | null>>();

/**
 * Bytes [from, to] of a file. Uses an HTTP range request; a server that ignores ranges (answers 200 with the whole
 * file, e.g. a simple static host for a small test area) still works – the file is kept once and sliced.
 */
async function range(url: string, from: number, to: number): Promise<{ bytes: Uint8Array; total: number } | null> {
  if (packed && !whole.has(url)) whole.set(url, fetchPacked(url));
  let all = await whole.get(url);
  if (whole.has(url) && !all) return null;
  if (!all) {
    const r = await fetch(url, { headers: { Range: `bytes=${from}-${to}` } });
    if (r.status === 404) return null;
    if (r.status === 206) {
      const total = Number((r.headers.get('Content-Range') ?? '').split('/')[1] ?? NaN);
      return { bytes: new Uint8Array(await r.arrayBuffer()), total };
    }
    if (r.status !== 200) throw new Error(`${url}: HTTP ${r.status}`);
    all = new Uint8Array(await r.arrayBuffer());
    whole.set(url, Promise.resolve(all));
  }
  return { bytes: all.slice(from, Math.min(to, all.length - 1) + 1), total: all.length };
}

/** End offsets of the 25 parts (low 48 bits of each big-endian long in the 200-byte index). */
function partEnds(index: Uint8Array): number[] {
  const v = new DataView(index.buffer, index.byteOffset, 200);
  const ends: number[] = [];
  for (let i = 0; i < 25; i++) ends.push(Number(v.getBigUint64(i * 8) & 0xffffffffffffn));
  return ends;
}

const layoutLoads = new Map<string, Promise<number[] | null>>();
const partLoads = new Map<string, Promise<number>>();

/** The tile's part layout (its head and tail go to the engine once); null when there is no such file. */
function layout(e: WasmEngine, tile: string): Promise<number[] | null> {
  let p = layoutLoads.get(tile);
  if (!p) {
    p = (async () => {
      const url = new URL(tile, segments).href;
      const head = await range(url, 0, 199);
      if (!head) return null; // no data there: BRouter answers "no routing data"
      const ends = partEnds(head.bytes);
      const tail = await range(url, ends[24], head.total - 1);
      e.putLayout(tile, new Int8Array(head.bytes.buffer), new Int8Array(tail?.bytes.buffer ?? new ArrayBuffer(0)));
      return ends;
    })();
    layoutLoads.set(tile, p);
  }
  return p;
}

/**
 * Fetches the parts [points] need that the engine does not have yet (each part once, also when a prefetch and a plan
 * ask at the same time). Returns bytes downloaded by this call.
 */
async function ensureParts(e: WasmEngine, points: string): Promise<number> {
  const need: [string, number][] = JSON.parse(e.partsNeeded(points));
  const sizes = await Promise.all(need.map(([tile, i]) => {
    const k = tile + '#' + i;
    let p = partLoads.get(k);
    if (!p) {
      p = (async () => {
        const ends = await layout(e, tile);
        if (!ends) return 0;
        const start = i === 0 ? 200 : ends[i - 1];
        if (ends[i] <= start) return 0; // empty part (sea / no ways)
        const part = await range(new URL(tile, segments).href, start, ends[i] - 1);
        if (!part) return 0;
        if (!e.putPart(tile, i, new Int8Array(part.bytes.buffer))) {
          partLoads.delete(k); // a later plan may try again
          throw new Error(`${tile} part ${i} does not fit its index (${part.bytes.length} bytes, expected ${ends[i] - start})`);
        }
        return part.bytes.length;
      })();
      partLoads.set(k, p);
      return p;
    }
    return p.then(() => 0); // someone else's download – counted there
  }));
  return sizes.reduce((a, b) => a + b, 0);
}

/** A whole file (the packed preview host keeps binaries as base64 text). Null when it is not there. */
async function fileBytes(url: string): Promise<Uint8Array | null> {
  if (packed && !url.endsWith('.geojson')) return fetchPacked(url);
  const r = await fetch(url);
  return r.ok ? new Uint8Array(await r.arrayBuffer()) : null;
}

const indexing = new Map<string, Promise<void>>();
/** One map area into the app's search index: its POIs, base map (peaks, places) and overlays (trail names). */
function indexArea(e: WasmEngine, key: string, dir: string): Promise<void> {
  let p = indexing.get(key);
  if (!p) {
    p = (async () => {
      const folder = 'search/' + key.replace(/[^\w-]/g, '_');
      await Promise.all(['pois.geojson', 'base.pmtiles', 'overlays.pmtiles'].map(async (f) => {
        const b = await fileBytes(dir + f);
        if (b) e.putFile(folder + '/' + f, new Int8Array(b.buffer, b.byteOffset, b.length));
      }));
      e.searchAddArea(key, '/onpath/' + folder);
    })();
    indexing.set(key, p);
  }
  return p;
}

self.onmessage = async (ev: MessageEvent<EngineRequest>) => {
  const req = ev.data;
  try {
    if (req.type === 'config') {
      base = req.base; segments = req.segments; packed = !!req.packed;
      const e = await getEngine();
      reply({ id: req.id, ok: true, stats: { engineLoadMs: loadMs.engine } as RouteStats, problems: JSON.parse(e.problems()) });
      return;
    }
    const e = await getEngine();
    if (req.type === 'encode') {
      const p = req.plan;
      const names = Object.entries(p.names).map(([i, n]) => `${i}\t${n.replace(/[\t\n]/g, ' ')}`).join('\n');
      const pts = p.points.map(([la, lo]) => `${la.toFixed(6)},${lo.toFixed(6)}`).join(';');
      reply({ id: req.id, ok: true, code: e.planCode(pts, p.activity, p.sac, p.pinned.join(','), names, p.title ?? '') });
      return;
    }
    if (req.type === 'parse') {
      reply({ id: req.id, ok: true, shared: JSON.parse(e.planParse(req.text)) });
      return;
    }
    if (req.type === 'edit') { reply({ id: req.id, ok: true, text: e.edit(req.op, req.points, req.index, req.to) }); return; }
    if (req.type === 'rows') { reply({ id: req.id, ok: true, text: e.rows(req.points, req.names) }); return; }
    if (req.type === 'placePreview') { reply({ id: req.id, ok: true, text: e.placePreview(req.points, req.lat, req.lon) }); return; }
    if (req.type === 'searchAreas') {
      for (const a of req.areas) await indexArea(e, a.key, a.dir);
      reply({ id: req.id, ok: true });
      return;
    }
    if (req.type === 'search') { reply({ id: req.id, ok: true, text: e.search(req.text, req.lat, req.lon, req.route) }); return; }
    if (req.type === 'searchOnline') { reply({ id: req.id, ok: true, text: e.searchOnline(req.text, req.photon) }); return; }
    if (req.type === 'call') {
      if (!CALLS.has(req.fn)) throw new Error(`unknown engine call ${req.fn}`);
      const fn = (e as unknown as Record<string, (...a: unknown[]) => unknown>)[req.fn];
      const out = fn(...req.args);
      reply({ id: req.id, ok: true, text: out == null ? '' : String(out) });
      return;
    }
    if (req.type === 'prefetch') {
      // the routing data around the map view, while the user looks at the map (the first plan then starts at once)
      const bytes = await ensureParts(e, `${req.lat.toFixed(6)},${req.lon.toFixed(6)}`);
      reply({ id: req.id, ok: true, stats: { bytesFetched: bytes } as RouteStats });
      return;
    }
    if (req.type === 'insert') {
      reply({ id: req.id, ok: true, index: e.insertIndex(req.points, req.lat, req.lon, req.lineSnapM) });
      return;
    }
    if (req.type === 'gpxRead') {
      // GPX import (WebImport.kt – the app's GpxParser + SportType)
      reply({ id: req.id, ok: true, text: e.gpxRead(new Int8Array(req.data.buffer, req.data.byteOffset, req.data.length), req.fileName, req.activity, req.sac) });
      return;
    }
    const t0 = performance.now();
    const fetched = await ensureParts(e, req.points);
    if (req.type === 'planFromTrack') {
      // a shown track → plan (the app's TrackToPlan): the routing data along the whole track first, like the app
      e.writeParts();
      reply({ id: req.id, ok: true, text: e.planFromTrack(req.points, req.activity, req.sac), stats: { bytesFetched: fetched } as RouteStats,
        problems: JSON.parse(e.problems()) });
      return;
    }
    const t1 = performance.now();
    e.writeParts(); // new parts into the local rd5 files (once per plan, not per part)
    const storeMs = performance.now() - t1;
    const json = req.type === 'plan' ? e.plan(req.points, req.activity, req.sac, req.pinned ?? '') : e.route(req.points, req.activity, req.sac);
    const t2 = performance.now();
    reply({
      id: req.id, ok: true, [req.type === 'plan' ? 'plan' : 'result']: JSON.parse(json),
      stats: { engineLoadMs: loadMs.engine, fetchMs: t1 - t0, storeMs, routeMs: t2 - t1 - storeMs, bytesFetched: fetched },
      problems: JSON.parse(e.problems()),
    });
  } catch (err) {
    reply({ id: req.id, ok: false, error: String(err) });
  }
};

function reply(r: EngineReply) { (self as unknown as DedicatedWorkerGlobalScope).postMessage(r); }
