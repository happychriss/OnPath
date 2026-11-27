// Length and climb of a saved route or track for My routes (owner 2026-11-06: "nicht nur den Namen, auch die Länge und
// hoch und runter"). The sync record holds only the plan code (points, activity) – no numbers – so the page keeps them
// in this browser whenever it has them anyway: a plan routed here (saved, or opened and routed), an imported or opened
// track. A plan is known by its code (the same route = the same numbers, also a copy or the phone's version of it), a
// track by its id. Never routes anything for the list: a route this browser has not routed yet shows no numbers.
import { distance } from './i18n';
import { slice } from './slices';

/** [s] = the model time in seconds (Meine Touren's sort by Dauer), [lv] = the rating's level (the app stores it at saving,
 * 362459fc – for a later sort by difficulty); both missing in numbers kept before 2026-11-13. */
export interface PlanStats { m: number; up: number; down: number; s?: number; lv?: string }

const KEY = 'onpath.planStats';
/** The newest entries are kept; old ones go (a plan changed many times leaves its older codes behind). */
const MAX = 400;

/** FNV-1a, 32 bit, twice (two seeds) – a short key for a plan code of a few hundred characters. */
function hash(s: string): string {
  let a = 0x811c9dc5, b = 0x01000193 ^ 0x5bd1e995;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    a = Math.imul(a ^ c, 0x01000193);
    b = Math.imul(b ^ c, 0x5bd1e995);
  }
  return (a >>> 0).toString(36) + (b >>> 0).toString(36);
}

export const planKey = (code: string) => 'p' + hash(code);
export const trackKey = (id: string) => 't' + id;

function load(): Record<string, PlanStats> {
  try {
    const j = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    return j && typeof j === 'object' ? j : {};
  } catch { return {}; } // expected: storage blocked or an old, unreadable value – the list shows names only
}

export function statsOf(key: string): PlanStats | null {
  const s = load()[key];
  return s && Number.isFinite(s.m) && s.m > 0 ? s : null;
}

export function keepStats(key: string, s: PlanStats) {
  if (!(Number.isFinite(s.m) && s.m > 0)) return;
  const all = load();
  delete all[key]; // re-inserted last = newest
  all[key] = { m: s.m, up: s.up || 0, down: s.down || 0, ...(Number.isFinite(s.s) && s.s! > 0 ? { s: s.s } : {}), ...(s.lv ? { lv: s.lv } : {}) };
  const keys = Object.keys(all);
  for (const k of keys.slice(0, Math.max(0, keys.length - MAX))) delete all[k];
  try { localStorage.setItem(KEY, JSON.stringify(all)); } catch { /* expected: storage blocked – numbers not kept */ }
}

/**
 * The app's Format.duration ("4 h 05 min", under an hour "25 min"), run in the engine (slices.ts – never copied here,
 * 2026-11-27). Null = the engine's slices are not there (reported): the row leaves the time out rather than guessing.
 */
export function durationText(s: number): string | null {
  const t = slice<string>('formatDuration', s);
  return typeof t === 'string' ? t : null;
}

/**
 * The row's facts, as the app's My routes card (RouteRowActions.facts): "12,5 km · ↑ 842 m · 4 h 00 min" – length,
 * climb, the model time; no-break spaces inside each value (a value never breaks, the line wraps only between them).
 * The app always has a time (it recomputes the model time for every planned or imported row); numbers kept here before
 * 2026-11-13 carry none – then the time is left out rather than a made-up "0 min".
 */
export function statsText(s: PlanStats): string {
  const parts = [distance(s.m), `↑ ${Math.trunc(s.up)} m`]; // Format.distance / Format.meters
  const time = Number.isFinite(s.s) && s.s! > 0 ? durationText(s.s!) : null;
  if (time) parts.push(time);
  return parts.map((p) => p.replace(/ /g, ' ')).join(' · ');
}
