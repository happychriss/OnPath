// Tour suggestions – the ONE contract (docs/design/tour-suggestions.md "Planner framework"). Owner 2026-11-13: "die
// Routing-Mechanismen, die wir nachher entwickeln, müssen einfach ausgetauscht werden können, und KI ist nur ein
// weiterer Parameter. Alles andere bleibt gleich."
//
//   TourWish  ──►  SuggestProvider (a mechanism: poc · ring · later the real core)  ──►  Candidate …
//                                                                                          │ session.ts
//                  the planner's own plan computation (engine.plan) for EVERY mechanism ◄──┘
//                                                                                          ▼
//                                                                                     Suggestion (card + plan points)
//
// A mechanism only says WHERE a loop goes (a line or a few points) and WHY it is nice. Length, time, ascent,
// difficulty, the line on the map and the plan points always come from the planner's existing plan computation, so a
// card shows exactly what "Übernehmen" gives – whatever mechanism made it.
import type { Activity, PlanResult } from '../engine/types';

export type LatLon = [number, number];
export type Level = 'low' | 'mid' | 'high';
export type Effort = 'easy' | 'medium' | 'sporty';

/** What the user asks for. Every mechanism gets the same wish; a mechanism may ignore what it cannot use. */
export interface TourWish {
  start: LatLon;
  /** The tapped place's name (a POI / found place), or null for a plain map point. */
  startName: string | null;
  activity: Activity;
  /** Hike: the SAC T level (the activity chip's). */
  sac: number;
  hours: number;
  /** Pace is not asked again: 'model' = the planner's own time model (its pace levels) decides the time. */
  pace: 'model';
  effort: Effort;
  nature: Level;
  quiet: Level;
  /** Known signed routes ("Bekannte Wege"). */
  known: Level;
  water: boolean;
  /** Per activity (surfaceOptions): hike natural | mixed | paved, bike asphalt | firm | any, … */
  surface: string;
  /** "Neue Vorschläge" raises it: another set of loops for the same wish. */
  seed: number;
  /** AI region knowledge per map area – just one more field. Off = the mechanism must not use it. */
  tips: boolean;
}

/** What a mechanism hands out. Exactly one of [track] / [points]. */
export interface Candidate {
  /** Stable within one run (the mechanism's own id). */
  id: string;
  /** A speaking name ("Ohligser Heide – Krüdersheide"); the mechanism's words. */
  title: string;
  /** Why it is nice, most important first ("2,7 km im Naturschutzgebiet Ohligser Heide"). */
  reasons: string[];
  /** Honest hints of the mechanism ("mit Fähre"). Length hints are added by the session for every mechanism alike. */
  hints: string[];
  /** The loop as a line (start … start): the session turns it into few plan points (the app's TrackToPlan). */
  track?: LatLon[];
  /** Or the plan points themselves (start, vias …, start). */
  points?: LatLon[];
}

/** A finished suggestion: a card, a line and the plan it becomes. */
export interface Suggestion {
  id: string;
  title: string;
  reasons: string[];
  hints: string[];
  /** The plan points "Übernehmen" commits (a round trip: first = last). */
  points: LatLon[];
  /** The planner's plan of those points: texts, difficulty, legs (the line), profile. */
  plan: PlanResult;
  /** Which mechanism made it (for the dev line only). */
  via: string;
}

export type SuggestEvent =
  | { type: 'progress'; text: string; found: number; of: number }
  | { type: 'suggestion'; suggestion: Suggestion }
  /** The run is over: [count] suggestions were handed out (0 = nothing found). */
  | { type: 'done'; count: number }
  | { type: 'error'; message: string; detail?: string };

/** What a mechanism may use. It never touches the page, the plan or the map. */
export interface ProviderContext {
  /** How many suggestions are wanted. */
  count: number;
  /** Aborted when the user cancels or a newer request supersedes this one. */
  signal: AbortSignal;
  /** The page's language ('de' | 'en') for the mechanism's own words. */
  lang: string;
  /** A wait text for the card ("Runden werden gesucht"). */
  progress(text: string): void;
}

/** A failure a mechanism can explain in plain words ("Rechner nicht erreichbar"): its message is shown as it is. */
export class ProviderMessage extends Error {}

/**
 * A suggestion mechanism. Adding one = one file with this object + one line in registry.ts.
 * [run] hands candidates out one by one (best first) and resolves when it has no more; it throws on failure.
 */
export interface SuggestProvider {
  /** The setting's value (?suggestVia=<id>). */
  id: string;
  /** For the dev line under the cards (a developer's word, not a UI text). */
  devName: string;
  /** A deliberately simple stand-in: never a default, the UI says so. */
  standIn?: boolean;
  run(wish: TourWish, ctx: ProviderContext, emit: (c: Candidate) => void): Promise<void>;
}

/** The surface line's options per activity (the first is the default). Keys = text keys web_suggest_surface_<key>. */
export function surfaceOptions(a: Activity): string[] {
  if (a === 'HIKE') return ['natural', 'mixed', 'paved'];
  if (a === 'BIKE_TREKKING') return ['gravel', 'mixed'];
  if (a === 'MTB' || a === 'EMTB') return ['trails', 'forest'];
  return ['firm', 'asphalt', 'any'];
}
