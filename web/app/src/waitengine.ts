// The wait window's engine: the app's shared progress math (core domain/WaitProgress.kt via WebWait.kt) in a second
// instance of the Wasm engine on the page's own thread. The routing worker cannot do it: BRouter blocks it while it
// routes, and the window's bar must move every half second meanwhile (never standing). Only the wait calls are used
// here – the instance holds no routing data. The app's screen-state slices run in it too (WebState.kt, slices.ts): the
// page's thread is where My routes and Save's dialog live. Robust first: until it is loaded, or when it cannot load, the window still
// shows a bar that never goes back (fallback below), and a failed call is reported, never thrown at the page.
import { problem } from './diag';

declare const __BUILD_ID__: string;
const BUILD = typeof __BUILD_ID__ === 'string' ? __BUILD_ID__ : 'dev';

export interface WaitStepUi { label: string; state: 'done' | 'now' | 'later' }
export interface WaitUi { title: string; route: boolean; percent: number; steps: WaitStepUi[]; line: string | null; paused: boolean; late: boolean }
export interface WaitEnd { full: WaitUi | null; shares?: string; check?: string; part?: string }

interface Exports {
  setLanguage(lang: string): string;
  waitStart(shares: string, check: string, part: string): void;
  waitTick(now: number, areas: string, dlDone: number, dlTotal: number, map: boolean, routing: boolean, join: number,
    routeDone: number, routeTotal: number, checking: boolean, names: string, points: number, paused: boolean): string;
  waitEnd(now: number, finished: boolean): string;
  signTick(now: number, areas: string): number;
  signSeed(percent: number): void;
  /** The app's screen-state slices (WebState.kt) – slices.ts drives them. */
  WebStateApi: unknown;
}
declare const TeaVM: { wasmGC: { load(url: string): Promise<{ exports: Exports }> } };

let ex: Exports | null = null;
let failed = false;
let settle: (ok: boolean) => void = () => {};
/** Resolves once the instance loaded (true) or could not load (false). */
export const waitEngineLoaded: Promise<boolean> = new Promise((r) => { settle = r; });
/** The instance's slice exports (WebState.kt, slices.ts), null until it loaded. */
export const sliceExports = (): unknown => ex?.WebStateApi ?? null;
/** How long the instance took to load (ms; −1 = not yet) – for the tests and the diagnostics. */
export let loadMs = -1;

/** Loads the instance once (after the page's engine, so the two do not compete for the first second). */
export async function loadWaitEngine(base: string, lang: string): Promise<void> {
  if (ex || failed) return;
  const t0 = performance.now();
  try {
    const g = globalThis as unknown as { TeaVM?: unknown };
    if (!g.TeaVM) await import(/* @vite-ignore */ new URL('engine/onpath-web-engine.wasm-runtime.js?v=' + BUILD, base).href);
    const { exports } = await TeaVM.wasmGC.load(new URL('engine/onpath-web-engine.wasm?v=' + BUILD, base).href);
    exports.setLanguage(lang);
    ex = exports;
    loadMs = Math.round(performance.now() - t0);
    console.info(`[wait] the window's engine ready in ${loadMs} ms`);
    settle(true);
  } catch (e) {
    failed = true;
    problem('progress', 'The wait window engine did not load – the window shows a plain bar', e);
    settle(false);
  }
}

/** The instance is there (the window's rows and words come from it). */
export const waitEngineReady = () => !!ex;
/** Loaded or given up: the window need not wait for it any longer. */
export const waitEngineSettled = () => !!ex || failed;

function guard<T>(what: string, f: (e: Exports) => T, fallback: T): T {
  if (!ex) return fallback;
  try { return f(ex); } catch (e) {
    problem('progress', `wait engine: ${what} failed`, e);
    return fallback;
  }
}

export const waitStart = (shares: string, check: string, part: string) => guard('start', (e) => e.waitStart(shares, check, part), undefined);
export function waitTick(...a: Parameters<Exports['waitTick']>): WaitUi | null {
  return guard('tick', (e) => JSON.parse(e.waitTick(...a)) as WaitUi, null);
}
export function waitEnd(now: number, finished: boolean): WaitEnd {
  return guard('end', (e) => JSON.parse(e.waitEnd(now, finished)) as WaitEnd, { full: null });
}
/** The sign's percent over the whole action; −1 = nothing loads (or no engine: the sign shows no number then). */
export const signTick = (now: number, areas: string): number => guard('sign', (e) => e.signTick(now, areas), -2);
export const signSeed = (percent: number) => guard('seed', (e) => e.signSeed(percent), undefined);
