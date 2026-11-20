/// <reference lib="webworker" />
// The routing engine in a Web Worker: the app's own Kotlin + BRouter, compiled to Wasm by TeaVM (web/engine).
// It fetches what BRouter reads – the profiles once, the rd5 1° parts a plan needs via HTTP range (the phone's rule,
// Rd5Parts.forCorridor, decided inside the engine) – then routes. The page never blocks while it computes.
import type { EngineRequest, EngineReply, RouteStats } from './types';
import { fetchPacked } from '../b64';
import { tourText } from '../tourtext';
import { LegStoreIdb } from './legstore';

declare const __BUILD_ID__: string;
const BUILD = typeof __BUILD_ID__ === 'string' ? __BUILD_ID__ : 'dev';

interface WasmEngine {
  init(root: string): void;
  setLanguage(lang: string): string;
  putFile(path: string, data: Int8Array): void;
  partsNeeded(points: string, activity: string): string;
  putLayout(tile: string, index: Int8Array, tail: Int8Array): void;
  putPart(tile: string, index: number, data: Int8Array): boolean;
  writeParts(): void;
  route(points: string, activity: string, sac: number): string;
  plan(points: string, activity: string, sac: number, pinned: string, names: string, rounds: number): string;
  historySnap(routed: string, snapped: string): void;
  planCode(points: string, activity: string, sac: number, pinned: string, names: string, title: string): string;
  planParse(text: string): string;
  edit(op: string, points: string, index: number, to: number): string;
  rows(points: string, names: string): string;
  placePreview(points: string, lat: number, lon: number): string;
  searchAddArea(key: string, dir: string): number;
  searchHasArea(key: string): boolean;
  search(text: string, lat: number, lon: number, route: string, category: string): string;
  searchOnline(text: string, photonJson: string, category: string): string;
  searchCategories(): string;
  searchReset(): void;
  keptState(home: string, favourites: string, code: string): string;
  keptApply(home: string, favourites: string, op: string, code: string): string;
  keptStart(home: string, favourites: string, recent: string, all: boolean, lat: number, lon: number): string;
  recentAdd(recent: string, code: string): string;
  insertIndex(points: string, lat: number, lon: number, lineSnapM: number): number;
  historyRecord(before: string, after: string): void;
  historyStep(op: string, points: string): string;
  historyState(): string;
  historyExport(): string;
  historyImport(text: string): void;
  gesture(menuOpen: boolean, marker: number, poi: boolean, planEmpty: boolean): string;
  pointMenu(index: number, count: number, isPlace: boolean, roundTrip: boolean, points: string): string;
  fromMeBlocked(status: string, hasFix: boolean): string;
  googleMaps(name: string, lat: number, lon: number, label: string): string;
  poiMenu(kind: string, name: string, ele: string, lat: number, lon: number): string;
  gpx(name: string, activity: string, sac: number, points: string, names: string): string;
  activities(): string;
  problems(): string;
  longRoute(points: string, activity: string, multiDay: boolean): string;
  planCancel(cause: string, points: string, activity: string, sac: number): string;
  gpxRead(data: Int8Array, fileName: string, activity: string, sac: number): string;
  planFromTrack(track: string, activity: string, sac: number): string;
  paceSet(hike: string, bike: string): string;
  paceChoices(activity: string): string;
  planTime(points: string, activity: string, sac: number): string;
  rangeStats(points: string, activity: string, sac: number, fromM: number, toM: number): string;
  mapTexts(): string;
  suggest(op: string, json: string): string;
  planCodeTour(points: string, activity: string, sac: number, pinned: string, names: string, title: string, tour: string): string;
  legsWanted(points: string, activity: string, sac: number): string;
  legGive(name: string, text: string): void;
  legsTaken(): string;
  legText(name: string): string;
  legsLast(): string;
}

/** Small synchronous calls the page may make by name (the app's pure UI rules – see client.ts). */
const CALLS = new Set(['historyRecord', 'historySnap', 'historyStep', 'historyState', 'historyImport', 'gesture', 'pointMenu', 'placeMenu', 'placeEdit', 'holdOnMap', 'placeBeside', 'locateLabel', 'fromMeBlocked', 'googleMaps', 'poiMenu', 'gpx', 'activities',
  // web UI rules (WebUi.kt): route name proposal (Save), POI kind filters (Map content)
  'routeName', 'poiKinds', 'poiFilter',
  // the app's map look per activity (map/ActivityLayers.kt): layer visibility + hiking band filter
  'activityLayers',
  // the app's pace setting (WebPace.kt): set the two levels, the drop-down's pace section, the last plan's time again
  'paceSet', 'paceChoices', 'planTime', 'rangeStats',
  // the app's plan rules before routing (WebPlanRules.kt): long-route question, what Cancel does
  'longRoute', 'planCancel',
  // the app's texts for map areas on demand and waits (WebMaps.kt)
  'mapTexts',
  // plan sync (WebSync.kt – the app's com.onpath.app.sync)
  'syncPairNew', 'syncPairParse', 'syncKeys', 'syncNewId', 'syncNewDevice', 'syncList', 'syncDevices', 'syncState', 'syncLimits', 'syncSave',
  'syncRename', 'syncDelete', 'syncHello', 'syncForget', 'syncApply', 'syncOutbox', 'syncAcked',
  // A-017 folders (web-sync.md §5.1): JOIN, role, tracks, push only on Save
  'syncOutboxOf', 'syncHelloAs', 'syncPeek', 'syncJoinBlob', 'syncHelloId', 'syncRebase', 'syncSaveTrack', 'syncTrackFit', 'syncTrackGpx',
  // moving tours between folders is not an edit (owner 2026-11-12): never stamped newer
  'syncTake',
  // My routes (owner 2026-11-13): the star (field 9) and a Zuletzt draft's activity line
  'syncSetFavourite', 'syncPlanFacts',
  // sync fields 7 / 8 (web-sync.md §5.2): Save with this engine's routing; the diagnostics line
  'syncSaveRouted', 'routingInfo',
  // the app's search chips and a fresh search (WebSearch.kt: SearchKinds.Category, SearchSession's kept list)
  'searchCategories', 'searchReset',
  // kept places and „Zuletzt gesucht“ (WebKept.kt – the app's SavedPlaces / SearchStore.addRecent, owner 2026-11-15)
  'keptState', 'keptApply', 'keptStart', 'recentAdd',
  // kept places ⇄ the sync (web-sync.md §5.4: the shared PlaceSync)
  'syncPlacesExport', 'syncPlacesImport', 'syncPlacesChanged', 'syncPlaceItems', 'syncPlacesForget',
  // the phone unpaired itself (its hello deleted – the app's guard)
  'syncPhoneGone',
  // the app's shared rules wired since main 2e50efbc: mailbox restart (M4), save as copy (H3), route names, route fit
  'syncRestart', 'saveKeepsOtherEdit', 'planNewRouteName', 'planCopyName', 'routeFirst', 'routeFitAfterPlace',
  // multi-day tours (WebDays.kt, stages 2 + 3): days, rules R1–R7, a pasted hotel, the trim and the append of a GPX track
  'daysView', 'daysRule', 'hotelText', 'trackCut', 'trackAppend']);

declare const TeaVM: { wasmGC: { load(url: string): Promise<{ exports: WasmEngine }> } };

let base = '';
let segments = '';
let lang = 'en'; // the text language (Txt.setLanguage), from the page's config before the engine loads
let packed = false; // preview host: rd5 published as <name>.b64.txt (see packed.ts)
/** Tests only (?slowRoute=ms): the router held this long before it routes and at every leg – a slow router, blocking the
 * worker like BRouter on a 600 km plan (robust-smoke 9: the calculation phase of the load card, Cancel). 0 = off. */
let slowMs = 0;
const hold = () => { if (!slowMs) return; const end = performance.now() + slowMs; while (performance.now() < end) { /* expected: a test's slow router */ } };
const loadMs = { engine: 0 };

/**
 * The engine, loaded once. The promise is kept (not only the result): the page's first calls arrive while the Wasm
 * file is still loading, and each would otherwise load its own engine – parts stored in one were then missing in the
 * other ("part does not fit its index" on a slow line).
 */
let loading: Promise<WasmEngine> | null = null;
function getEngine(): Promise<WasmEngine> {
  if (loading) return loading;
  const p = (async () => {
    const t0 = performance.now();
    // ?v=<build>: a new page never runs an old engine (or old profiles) from a cache (version.ts)
    await import(/* @vite-ignore */ new URL('engine/onpath-web-engine.wasm-runtime.js?v=' + BUILD, base).href);
    const { exports } = await TeaVM.wasmGC.load(new URL('engine/onpath-web-engine.wasm?v=' + BUILD, base).href);
    exports.init('/onpath');
    exports.setLanguage(lang); // the page's language (i18n.ts) for the app's texts the engine writes
    const list: string[] = await (await fetch(new URL('brouter/index.json?v=' + BUILD, base))).json();
    await Promise.all(list.map(async (f) => {
      const buf = await (await fetch(new URL('brouter/' + f + '?v=' + BUILD, base))).arrayBuffer();
      exports.putFile('assets/brouter/' + f, new Int8Array(buf));
    }));
    loadMs.engine = performance.now() - t0;
    return exports;
  })();
  // a failed load (network blip) is not kept: the next request loads again (review 2026-11-01, web B1)
  p.catch(() => { if (loading === p) loading = null; });
  loading = p;
  return p;
}

/**
 * Keeps [p] in [cache] under [key] while it is running or succeeded; a failure (network blip, HTTP 429 / 5xx) is
 * forgotten, so the next plan downloads again instead of getting the same error until a reload (review 2026-11-01, web B1).
 */
function keep<T>(cache: Map<string, Promise<T>>, key: string, p: Promise<T>): Promise<T> {
  cache.set(key, p);
  p.catch(() => { if (cache.get(key) === p) cache.delete(key); });
  return p;
}

/** A download that failed for a reason that may pass (offline, timeout, 429, 5xx) – tried once more after a moment. */
class Transient extends Error {}
const pause = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** fetch with one retry after 1.5 s when the network fails or the server is busy (429 / 5xx). */
async function fetchRetry(url: string, init?: RequestInit): Promise<Response> {
  for (let attempt = 0; ; attempt++) {
    try {
      const r = await fetch(url, init);
      if ((r.status === 429 || r.status >= 500) && attempt === 0) { await pause(1500); continue; }
      if (r.status === 429 || r.status >= 500) throw new Transient(`${url}: HTTP ${r.status}`);
      return r;
    } catch (e) {
      if (e instanceof Transient || attempt > 0) throw e;
      await pause(1500); // expected: offline for a moment – once more
    }
  }
}

/** Whole files: the packed preview host (base64, no ranges) and servers that ignore ranges. Kept as promises, so
 * a prefetch and a plan asking at the same time download once. */
const whole = new Map<string, Promise<Uint8Array | null>>();

/**
 * Bytes [from, to] of a file. Uses an HTTP range request; a server that ignores ranges (answers 200 with the whole
 * file, e.g. a simple static host for a small test area) still works – the file is kept once and sliced.
 */
async function range(url: string, from: number, to: number, onBytes?: (n: number) => void): Promise<{ bytes: Uint8Array; total: number } | null> {
  if (packed && !whole.has(url)) keep(whole, url, fetchPacked(url));
  let all = await whole.get(url);
  if (whole.has(url) && !all) return null;
  if (!all) {
    const r = await fetchRetry(url, { headers: { Range: `bytes=${from}-${to}` } });
    if (r.status === 404) return null;
    if (r.status === 206) {
      const total = Number((r.headers.get('Content-Range') ?? '').split('/')[1] ?? NaN);
      return { bytes: await body(r, onBytes), total };
    }
    if (r.status !== 200) throw new Error(`${url}: HTTP ${r.status}`);
    all = new Uint8Array(await r.arrayBuffer());
    whole.set(url, Promise.resolve(all));
  }
  return { bytes: all.slice(from, Math.min(to, all.length - 1) + 1), total: all.length };
}

/** A response's bytes; with [onBytes] read as they come, so the page can show how far a part is (the load overlay). */
async function body(r: Response, onBytes?: (n: number) => void): Promise<Uint8Array> {
  if (!onBytes || !r.body) return new Uint8Array(await r.arrayBuffer());
  const reader = r.body.getReader();
  const chunks: Uint8Array[] = [];
  let n = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value); n += value.length; onBytes(value.length);
  }
  const out = new Uint8Array(n);
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
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
/** Bytes each part download has received so far (tile#i), and the parts that are settled (in the engine, empty or failed). */
const partGot = new Map<string, number>();
const partSettled = new Set<string>();
/** The plans waiting for parts, told when bytes arrive (each throttles itself). */
const byteListeners = new Set<() => void>();
const heard = () => { for (const f of byteListeners) f(); };

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
    keep(layoutLoads, tile, p);
  }
  return p;
}

/**
 * Fetches the parts [points] need that the engine does not have yet (each part once, also when a prefetch and a plan
 * ask at the same time). Returns bytes downloaded by this call.
 */
async function ensureParts(e: WasmEngine, points: string, activity: string, onNeed?: (bytes: number, done: number) => void,
  given?: [string, number][]): Promise<number> {
  // the phone's corridor for the activity (OfflineCorridor: 10 km on foot, 20 km by bike; '' = not known → the largest);
  // [given] = the parts a caller worked out itself (round-trip suggestions: all within reach of the start)
  const need: [string, number][] = given ?? JSON.parse(e.partsNeeded(points, activity));
  let stop = () => {};
  if (onNeed) {
    // what is still to download (the layouts are small and fetched first), so the page can say how far it is (the load
    // overlay's percent): the sizes come from the files' own index – an honest total, no guess. A part another request
    // is downloading (the prefetch) counts too: this plan waits for it as well.
    const ends = await Promise.all(need.map(([tile]) => layout(e, tile).catch(() => null)));
    const open = new Map<string, number>();
    need.forEach(([tile, i], k) => {
      const en = ends[k], key = tile + '#' + i;
      if (!en || partSettled.has(key)) return;
      const size = Math.max(0, en[i] - (i === 0 ? 200 : en[i - 1]));
      if (size > 0) open.set(key, size);
    });
    const total = [...open.values()].reduce((a, b) => a + b, 0);
    if (total > 0) {
      const got = () => [...open].reduce((sum, [key, size]) => sum + (partSettled.has(key) ? size : Math.min(size, partGot.get(key) ?? 0)), 0);
      let last = 0;
      const tell = () => { const now = performance.now(); if (now - last < 100) return; last = now; onNeed(total, got()); };
      onNeed(total, got());
      byteListeners.add(tell);
      stop = () => { byteListeners.delete(tell); onNeed(total, total); };
    }
  }
  const sizes = await Promise.all(need.map(([tile, i]) => {
    const k = tile + '#' + i;
    let p = partLoads.get(k);
    if (!p) {
      partGot.set(k, 0);
      p = (async () => {
        try {
          const ends = await layout(e, tile);
          if (!ends) return 0;
          const start = i === 0 ? 200 : ends[i - 1];
          if (ends[i] <= start) return 0; // empty part (sea / no ways)
          const part = await range(new URL(tile, segments).href, start, ends[i] - 1, (n) => { partGot.set(k, (partGot.get(k) ?? 0) + n); heard(); });
          if (!part) return 0;
          if (!e.putPart(tile, i, new Int8Array(part.bytes.buffer))) {
            partLoads.delete(k); // a later plan may try again
            throw new Error(`${tile} part ${i} does not fit its index (${part.bytes.length} bytes, expected ${ends[i] - start})`);
          }
          return part.bytes.length;
        } finally { partSettled.add(k); partGot.delete(k); heard(); }
      })();
      keep(partLoads, k, p);
      // expected: a failed part is reported by noPart below; it is not settled for the next plan, which tries it again
      p.catch(() => { partSettled.delete(k); });
      return p.catch((err) => noPart(tile, i, err));
    }
    return p.then(() => 0, (err) => noPart(tile, i, err)); // someone else's download – counted there
  })).finally(stop);
  return sizes.reduce((a, b) => a + b, 0);
}

/**
 * A part that could not be downloaded (offline, server busy): the plan goes on without it – the engine then draws a
 * straight line with the app's grey "Map loads when online" line (RoutingDataNotice NOT_YET), never "Planning failed:
 * TypeError…". The next plan tries the part again (it is not kept as failed).
 */
function noPart(tile: string, i: number, err: unknown): number {
  console.warn(`[Problems] WARN routing: ${tile} part ${i} not downloaded – planned without it`, err);
  return 0;
}

/** A whole file (the packed preview host keeps binaries as base64 text). Null when it is not there. */
async function fileBytes(url: string): Promise<Uint8Array | null> {
  if (packed && !url.endsWith('.geojson')) return fetchPacked(url);
  const r = await fetchRetry(url);
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
    keep(indexing, key, p);
  }
  return p;
}

// ---- the finished legs in the browser (legstore.ts – IndexedDB; the engine's WebLegStore decides what counts) ----
/** Problems of this worker's own (the leg store), sent with the next answer to the page's problem log. */
const notes: { area: string; message: string; detail: string }[] = [];
const note = (area: string, message: string, e?: unknown) => {
  notes.push({ area, message, detail: e == null ? '' : e instanceof Error ? `${e.name}: ${e.message}` : String(e) });
};
const legStore = new LegStoreIdb((m, e) => note('routing', m, e));

/** Before a plan: its legs not in the engine's memory, read from the browser and handed over (fresh or not – the engine checks). */
async function legsIn(e: WasmEngine, points: string, activity: string, sac: number): Promise<number> {
  try {
    const names: string[] = JSON.parse(e.legsWanted(points, activity, sac));
    const got = await legStore.read(names);
    for (const [n, text] of got) e.legGive(n, text);
    return got.size;
  } catch (err) { note('routing', 'Leg store: legs not handed over – routed again', err); return 0; }
}

/** After an answer: the legs the engine routed (or kept under snapped points) into the browser. */
let legsWriting: Promise<void> = Promise.resolve();
function legsOut(e: WasmEngine) {
  try {
    const names: string[] = JSON.parse(e.legsTaken());
    if (!names.length) return;
    const legs = names.map((n) => [n, e.legText(n)] as [string, string]).filter(([, t]) => t);
    legsWriting = legsWriting.then(() => legStore.write(legs));
  } catch (err) { note('routing', 'Leg store: routed legs not taken – routed again next time', err); }
}

/** Plans the page dropped (a newer edit, Neu, Abbrechen) while they still waited for routing data: never routed. */
const dropped = new Set<number>();
/** The calls that change the undo / redo history: their reply carries it, for a fresh worker (client.ts restart). */
const HISTORY = new Set(['historyRecord', 'historySnap', 'historyStep', 'historyImport', 'planCancel']); // planCancel: its Undo / Redo is a step

self.onmessage = async (ev: MessageEvent<EngineRequest>) => {
  const req = ev.data;
  if (req.type === 'drop') { dropped.add(req.target); return; } // no answer: the page already stopped waiting
  try {
    if (req.type === 'config') {
      base = req.base; segments = req.segments; packed = !!req.packed; lang = req.lang ?? 'en'; slowMs = req.slowMs ?? 0;
      let e: WasmEngine;
      try { e = await getEngine(); } catch (err) {
        // the browser cannot compile the engine (Safari < 18.2 has no WasmGC): no reload helps – the page says so
        const unsupported = err instanceof WebAssembly.CompileError || err instanceof WebAssembly.LinkError ||
          typeof WebAssembly.validate !== 'function';
        reply({ id: req.id, ok: false, error: String(err), unsupported });
        return;
      }
      reply({ id: req.id, ok: true, stats: { engineLoadMs: loadMs.engine } as RouteStats, problems: JSON.parse(e.problems()) });
      return;
    }
    const e = await getEngine();
    if (req.type === 'encode') {
      const p = req.plan;
      const names = Object.entries(p.names).map(([i, n]) => `${i}\t${n.replace(/[\t\n]/g, ' ')}`).join('\n');
      const pts = p.points.map(([la, lo]) => `${la.toFixed(6)},${lo.toFixed(6)}`).join(';');
      // a multi-day tour carries its days and night marks (tourText, WebDays.parseTour)
      reply({ id: req.id, ok: true, code: p.tour ? e.planCodeTour(pts, p.activity, p.sac, p.pinned.join(','), names, p.title ?? '', tourText(p.tour))
        : e.planCode(pts, p.activity, p.sac, p.pinned.join(','), names, p.title ?? '') });
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
    if (req.type === 'search') { reply({ id: req.id, ok: true, text: e.search(req.text, req.lat, req.lon, req.route, req.category ?? '') }); return; }
    if (req.type === 'searchOnline') { reply({ id: req.id, ok: true, text: e.searchOnline(req.text, req.photon, req.category ?? '') }); return; }
    if (req.type === 'call') {
      if (!CALLS.has(req.fn)) throw new Error(`unknown engine call ${req.fn}`);
      const fn = (e as unknown as Record<string, (...a: unknown[]) => unknown>)[req.fn];
      const out = fn(...req.args);
      reply({ id: req.id, ok: true, text: out == null ? '' : String(out), history: HISTORY.has(req.fn) ? e.historyExport() : undefined });
      if (req.fn === 'historySnap') legsOut(e); // the legs kept under the snapped points (the plan as saved)
      return;
    }
    if (req.type === 'prefetch') {
      // the routing data around the map view, while the user looks at the map (the first plan then starts at once)
      const bytes = await ensureParts(e, `${req.lat.toFixed(6)},${req.lon.toFixed(6)}`, ''); // no plan yet: the largest corridor
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
    if (req.type === 'suggest') { await suggest(e, req); return; }
    const t0 = performance.now();
    const fetched = await ensureParts(e, req.points, req.activity ?? '', (bytes, done) => reply({ id: req.id, ok: true, progress: { phase: 'download', bytes, done } }));
    if (dropped.delete(req.id)) { reply({ id: req.id, ok: false, error: 'dropped', cancelled: true }); return; } // superseded while downloading
    if (req.type === 'plan') reply({ id: req.id, ok: true, progress: { phase: 'route' } });
    if (req.type === 'planFromTrack') {
      // a shown track → plan (the app's TrackToPlan): the routing data along the whole track first, like the app
      e.writeParts();
      reply({ id: req.id, ok: true, progress: { phase: 'route' } }); // from here it can be stopped (client.ts dropPlans)
      const gp = globalThis as unknown as { onpathPlanProgress?: (text: string) => void };
      gp.onpathPlanProgress = (text) => reply({ id: req.id, ok: true, progress: { phase: 'legs', text } }); // "37 %"
      try {
      reply({ id: req.id, ok: true, text: e.planFromTrack(req.points, req.activity, req.sac), stats: { bytesFetched: fetched } as RouteStats,
        problems: JSON.parse(e.problems()) });
      } finally { gp.onpathPlanProgress = undefined; }
      return;
    }
    // the plan's legs kept in the browser (a reopened tour, a reload): read before routing – the router then skips them
    const tl = performance.now();
    const legsRead = req.type === 'plan' ? await legsIn(e, req.points, req.activity, req.sac) : 0;
    const legsMs = performance.now() - tl;
    if (dropped.delete(req.id)) { reply({ id: req.id, ok: false, error: 'dropped', cancelled: true }); return; }
    const t1 = performance.now();
    e.writeParts(); // new parts into the local rd5 files (once per plan, not per part)
    const storeMs = performance.now() - t1;
    // the engine's progress while it plans (WebApi planProgress): the legs routed of the legs to route – the wait
    // window's steps (WebWait, the app's RouteWait)
    const g = globalThis as unknown as { onpathPlanProgress?: (text: string) => void };
    g.onpathPlanProgress = req.type === 'plan' ? (text) => {
      const j = JSON.parse(text) as { done: number; total: number };
      reply({ id: req.id, ok: true, progress: { phase: 'legs', text: '', done: j.done, total: j.total } });
      hold();
    } : undefined;
    let json: string;
    try {
      if (req.type === 'plan') hold();
      json = req.type === 'plan' ? e.plan(req.points, req.activity, req.sac, req.pinned ?? '', req.names ?? '', req.rounds ?? 0) : e.route(req.points, req.activity, req.sac);
    } finally { g.onpathPlanProgress = undefined; }
    const t2 = performance.now();
    const legs = req.type === 'plan' ? { ...JSON.parse(e.legsLast()), read: legsRead, readMs: Math.round(legsMs) } : undefined;
    reply({
      id: req.id, ok: true, [req.type === 'plan' ? 'plan' : 'result']: JSON.parse(json),
      stats: { engineLoadMs: loadMs.engine, fetchMs: t1 - t0 - legsMs, storeMs, routeMs: t2 - t1 - storeMs, bytesFetched: fetched, legs },
      problems: JSON.parse(e.problems()),
    });
    if (req.type === 'plan') legsOut(e);
  } catch (err) {
    reply({ id: req.id, ok: false, error: String(err) });
  }
};

// ---- round-trip suggestions (WebSuggest.kt, docs/design/tour-suggestions.md §7 stage 3) ----
const suggestFiles = new Map<string, Promise<void>>();
let suggestSettings: Promise<void> | null = null;

/** The method's numbers (tools/suggest-poc/settings.json, copied at build time) – once per engine. */
function settingsFor(e: WasmEngine): Promise<void> {
  if (!suggestSettings) {
    const p = (async () => {
      const r = await fetchRetry(new URL('suggest/settings.json?v=' + BUILD, base).href);
      if (!r.ok) throw new Error(`suggest settings: HTTP ${r.status}`);
      e.putFile('assets/suggest/settings.json', new Int8Array(await r.arrayBuffer()));
    })();
    p.catch(() => { if (suggestSettings === p) suggestSettings = null; });
    suggestSettings = p;
  }
  return suggestSettings;
}

/**
 * One map cell's suggest file ([root]cells/f2/10/x/y/<version>/suggest.bin, the cell's current version) into the
 * engine; a cell without one (not built, or built before the suggest layer) stays without – the engine then says
 * „Die Karte wird hier gerade aktualisiert“. Each cell once per page (a failed read is tried again next time).
 */
function suggestCell(e: WasmEngine, root: string, x: number, y: number): Promise<void> {
  const key = `${x}/${y}`;
  let p = suggestFiles.get(key);
  if (!p) {
    p = (async () => {
      const dir = new URL(`cells/f2/10/${x}/${y}/`, root).href;
      const cur = await fetchRetry(dir + 'current.json', { cache: 'no-cache' });
      if (cur.status === 404) return; // no map here
      if (!cur.ok) throw new Transient(`cell ${key}: current.json HTTP ${cur.status}`);
      const version = ((await cur.json()) as { version?: string }).version;
      if (!version) return;
      const b = await fileBytes(`${dir}${version}/suggest.bin`);
      if (b) e.putFile(`suggest/10-${x}-${y}/suggest.bin`, new Int8Array(b.buffer, b.byteOffset, b.length));
    })();
    keep(suggestFiles, key, p);
  }
  return p.catch((err) => console.warn(`[Problems] WARN suggest: suggest file of cell ${key} not read`, err));
}

async function suggest(e: WasmEngine, req: Extract<EngineRequest, { type: 'suggest' }>) {
  const t0 = performance.now();
  await settingsFor(e);
  const need: { parts: [string, number][]; cells: [number, number][]; error?: string } = JSON.parse(e.suggest('need', req.wish));
  if (need.error) { reply({ id: req.id, ok: true, text: JSON.stringify({ error: need.error }) }); return; }
  const progress = (bytes: number, done: number) => reply({ id: req.id, ok: true, progress: { phase: 'download', bytes, done } });
  const [fetched] = await Promise.all([ensureParts(e, '', '', progress, need.parts),
    ...need.cells.map(([x, y]) => suggestCell(e, req.cells, x, y))]);
  if (dropped.delete(req.id)) { reply({ id: req.id, ok: false, error: 'dropped', cancelled: true }); return; }
  reply({ id: req.id, ok: true, progress: { phase: 'route' } });
  const t1 = performance.now();
  const text = e.suggest('run', req.wish);
  const t2 = performance.now();
  reply({ id: req.id, ok: true, text, stats: { engineLoadMs: loadMs.engine, fetchMs: t1 - t0, routeMs: t2 - t1, bytesFetched: fetched },
    problems: JSON.parse(e.problems()) });
}

function reply(r: EngineReply) {
  if (notes.length) r = { ...r, notes: notes.splice(0) }; // the worker's own problems ride along to the page's log
  (self as unknown as DedicatedWorkerGlobalScope).postMessage(r);
}
