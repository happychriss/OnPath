// The shared Kotlin (com.onpath.app.live in the Wasm engine, web/engine WebLive.kt) for the viewer page: the same engine
// file as the planner's, loaded on the page itself – only small, quick calls (no routing, no profiles, no worker).
declare const __BUILD_ID__: string;
const BUILD = typeof __BUILD_ID__ === 'string' ? __BUILD_ID__ : 'dev';
declare const TeaVM: { wasmGC: { load(url: string): Promise<{ exports: LiveEngine }> } };

export interface LiveEngine {
  init(root: string): void;
  setLanguage(lang: string): string;
  activities(): string;
  liveKeys(link: string): string;
  liveOpen(link: string, name: string, blob: string): string;
  liveStatus(link: string, headBlob: string, gone: boolean, broken: boolean, sinceLastWriteMs: number, endedMs: number): string;
  liveNext(sinceLastWriteMs: number, nextS: number, emptyAsks: number, over: boolean, retryAfterMs: number): number;
}

export interface Fix { lat: number; lon: number; ele: number | null; time: number; accuracy: number | null }
export interface Head {
  kind: 'head'; state: 'recording' | 'paused' | 'ended'; fix: Fix | null; gpsLost: boolean; arrived: boolean; offRouteM: number | null;
  distanceM: number; ascentM: number; movingS: number; startedMs: number; toGoM: number | null; arrivalMs: number | null;
  stillS: number | null; batteryPct: number | null; activity: string; name: string; counter: number; nextS: number; chunks: number | null;
  distanceText: string; movingText: string; ascentText: string; toGoText: string | null;
}
export interface Route { kind: 'route'; line: [number, number][] }
/** points: [segment, lat, lon, ele | null, timeMs] */
export interface Chunk { kind: 'chunk'; n: number; points: [number, number, number, number | null, number][] }
export interface StatusLine { key: 'stopped' | 'broken' | 'no_data' | 'ended' | 'no_contact' | 'off_route' | 'pause' | 'moving';
  minutes: number | null; metres: number | null; atMs: number | null; amber: boolean; arrived: boolean }

/** Loads the engine once; [base] = the site's root (where engine/ lies). */
export async function loadEngine(base: string, lang: string): Promise<LiveEngine> {
  await import(/* @vite-ignore */ new URL('engine/onpath-web-engine.wasm-runtime.js?v=' + BUILD, base).href);
  const { exports } = await TeaVM.wasmGC.load(new URL('engine/onpath-web-engine.wasm?v=' + BUILD, base).href);
  exports.init('/onpath');
  exports.setLanguage(lang);
  return exports;
}
