// Mechanism `poc` – DEVELOPMENT ONLY. Asks the tour-suggestions POC on this computer (tools/suggest-poc/serve.py →
// localhost:8790, reached through the Vite dev server's /poc/ proxy) and hands its chosen loops on as lines. So the
// POC's routes of today and tomorrow (methods a / b / c) can be looked at inside the real planner without porting
// anything. It is registered only in a dev build (registry.ts: import.meta.env.DEV) – a production build does not
// contain a request to it, and the live site can never reach a localhost.
import type { Activity } from '../engine/types';
import type { Candidate, LatLon, Level, SuggestProvider, TourWish } from './types';

const ACT: Record<Activity, string> = { HIKE: 'hike', BIKE_TOURING: 'bike', EBIKE: 'bike', BIKE_TREKKING: 'gravel', MTB: 'mtb', EMTB: 'mtb' };
const LEVEL: Record<Level, string> = { low: 'niedrig', mid: 'mittel', high: 'hoch' };
const EFFORT = { easy: 'leicht', medium: 'mittel', sporty: 'sportlich' } as const;
/** The planner's surface keys → the POC's words (weights.json "surface"); unknown ones fall to the POC's default. */
const SURFACE: Record<string, string> = { natural: 'natur', mixed: 'gemischt', paved: 'befestigt', asphalt: 'asphalt', firm: 'fest', any: 'egal',
  gravel: 'schotter', trails: 'trails', forest: 'forstwege' };

interface PocCandidate {
  id: number; name: string; chosen?: boolean; coords: number[][]; tips?: { name: string; km?: number }[];
  numbers: { km: number; ferry_m?: number; known?: [string, number, string][]; reserves?: [string, number][]; lakes?: [string, number][];
    forest_pct?: number; self_cross?: number };
}

/** The POC's answer for [wish]; [base] ends with '/'. */
export function pocBody(wish: TourWish, method: string) {
  return {
    start: [wish.start[1], wish.start[0]], start_name: wish.startName ?? '', activity: ACT[wish.activity], t_level: wish.sac,
    hours: wish.hours, pace: 'normal', effort: EFFORT[wish.effort], natur: LEVEL[wish.nature], ruhe: LEVEL[wish.quiet], bekannt: LEVEL[wish.known],
    wasser: wish.water, surface: SURFACE[wish.surface] ?? null, seed: wish.seed, method, ai: wish.tips,
  };
}

const num = (v: number, lang: string) => new Intl.NumberFormat(lang === 'de' ? 'de' : 'en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(v);

export function pocCandidate(c: PocCandidate, lang: string): Candidate {
  const n = c.numbers, de = lang === 'de';
  const reasons: string[] = [];
  for (const [name, km] of (n.reserves ?? []).slice(0, 1)) reasons.push(de ? `${num(km, lang)} km im Naturschutzgebiet ${name}` : `${num(km, lang)} km in the ${name} nature reserve`);
  for (const [name, km] of (n.known ?? []).slice(0, 2)) reasons.push(`${num(km, lang)} km ${name.replace(/^[A-Z0-9]+ · /, '')}`);
  for (const [name, km] of (n.lakes ?? []).slice(0, 1)) reasons.push(de ? `${num(km, lang)} km am ${name}` : `${num(km, lang)} km along ${name}`);
  if (!reasons.length && n.forest_pct != null) reasons.push(de ? `${n.forest_pct} % am Wald` : `${n.forest_pct} % near forest`);
  const hints: string[] = [];
  if ((n.ferry_m ?? 0) > 0) hints.push(de ? 'mit Fähre' : 'with a ferry');
  return { id: `poc-${c.id}`, title: c.name, reasons, hints, track: c.coords.map(([lon, lat]) => [lat, lon] as LatLon) };
}

export function pocProvider(base: string, method: string): SuggestProvider {
  return {
    id: 'poc',
    devName: `poc – der Versuch am Rechner (Verfahren ${method.toUpperCase()})`,
    async run(wish, ctx, emit) {
      const r = await fetch(base + 'suggest', { method: 'POST', body: JSON.stringify(pocBody(wish, method)), signal: ctx.signal });
      if (!r.ok) throw new Error(`POC ${r.status}`);
      const d = (await r.json()) as { error?: string; candidates?: PocCandidate[] };
      if (d.error) throw new Error(d.error);
      for (const c of (d.candidates ?? []).filter((x) => x.chosen)) emit(pocCandidate(c, ctx.lang));
    },
  };
}
