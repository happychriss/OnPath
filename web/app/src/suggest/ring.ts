// Mechanism `ring` – A STAND-IN, NOT A ROUTE FINDER. It puts three points on a circle through the start and lets the
// planner route the round trip. Its only job is to prove the in-browser path end to end (worker, progress, cancel, a
// newer request superseding an older one) without any server. It knows nothing about forest, roads or known routes and
// must never be a default on the live site (registry.ts). The real mechanism is the network search of
// tools/suggest-poc, to be ported into the shared core (docs/design/tour-suggestions.md "Planner framework").
import type { Activity } from '../engine/types';
import type { Candidate, LatLon, SuggestProvider, TourWish } from './types';

/** Rough speeds for the circle's size only (the card's time comes from the planner's own time model). */
const KMH: Record<Activity, number> = { HIKE: 4, BIKE_TOURING: 16, BIKE_TREKKING: 15, EBIKE: 19, MTB: 11, EMTB: 14 };
/** Ways are longer than the circle drawn through the via points. */
const DETOUR = 1.35;

export function ringPoints(wish: TourWish, bearingDeg: number): LatLon[] {
  const km = wish.hours * KMH[wish.activity];
  const r = km / DETOUR / (2 * Math.PI); // the circle's radius in km
  const [lat, lon] = wish.start;
  const kmLat = 111.32, kmLon = 111.32 * Math.cos((lat * Math.PI) / 180);
  const b = (bearingDeg * Math.PI) / 180;
  // the circle's centre lies r away in the bearing's direction; the start is on the circle
  const cLat = lat + (r * Math.cos(b)) / kmLat, cLon = lon + (r * Math.sin(b)) / kmLon;
  const back = b + Math.PI; // the centre → the start
  const at = (turn: number): LatLon => {
    const a = back + turn;
    return [Number((cLat + (r * Math.cos(a)) / kmLat).toFixed(6)), Number((cLon + (r * Math.sin(a)) / kmLon).toFixed(6))];
  };
  return [wish.start, at(Math.PI / 2), at(Math.PI), at((3 * Math.PI) / 2), wish.start];
}

const DIRS: Record<string, string[]> = {
  de: ['Norden', 'Nordosten', 'Osten', 'Südosten', 'Süden', 'Südwesten', 'Westen', 'Nordwesten'],
  en: ['north', 'north-east', 'east', 'south-east', 'south', 'south-west', 'west', 'north-west'],
};

export const ring: SuggestProvider = {
  id: 'ring',
  devName: 'ring – Platzhalter (Kreis um den Start, kein Routenfinder)',
  standIn: true,
  async run(wish, ctx, emit) {
    const de = ctx.lang === 'de';
    const dirs = DIRS[de ? 'de' : 'en'];
    // twice as many directions as wanted: some will not route (water, no ways); the seed turns the fan
    const n = ctx.count * 2, first = (wish.seed * 47) % 360;
    for (let k = 0; k < n; k++) {
      if (ctx.signal.aborted) return;
      const bearing = (first + (k * 360) / n + (k % 2 ? 180 / n : 0)) % 360;
      const c: Candidate = {
        id: `ring-${wish.seed}-${k}`,
        title: de ? `Runde nach ${dirs[Math.round(bearing / 45) % 8]}` : `Loop to the ${dirs[Math.round(bearing / 45) % 8]}`,
        reasons: [de ? 'Platzhalter: ein Kreis um den Start, ohne Geschmack' : 'Stand-in: a circle around the start, no taste'],
        hints: [],
        points: ringPoints(wish, bearing),
      };
      emit(c);
      await new Promise((r) => setTimeout(r, 0)); // one at a time: the session plans each before the next is made
    }
  },
};
