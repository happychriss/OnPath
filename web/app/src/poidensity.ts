// The density limit for city POIs – the app's cells/PoiDensity (owner 2026-11-13, central London: „in einer Riesenstadt
// sucht man mit der App keine Restaurants“), mirrored (web/parity.json): restaurants, cafés, bars, snack bars and shops
// are counted per 512 px map tile of the zoom shown; where more than CAP share one tile only the CAP most notable
// (named first, then the fixed priority "pr") are drawn at that zoom, the others one zoom closer, all from ALL_FROM.
// A POI hidden at a zoom stays hidden at every lower zoom. Every other kind is never limited.
//
// The app builds this into the POI tiles it writes when it joins cells (PoiTiles: "dz" = the first zoom, the tiles
// below leave the POI out). The planner reads each cell's pois.geojson in place (cells.ts): it sets the same "dz" on
// the features of one cell – the app counts per cell too – and the app's style draws a feature only from
// `zoom >= dz` (tools/make_style.py pois()). Pure, no imports (test/poidensity-check.mjs runs it in plain Node).

/** Limited kinds → the zoom the style starts drawing them (PoiDensity.KINDS). */
export const KINDS: Readonly<Record<string, number>> = { restaurant: 12, cafe: 12, bar: 14, fast_food: 14, shop: 15 };
/** Most limited POIs drawn per 512 px tile of the zoom shown. */
export const CAP = 24;
/** From this zoom every POI is drawn (no limit). */
export const ALL_FROM = 17;
/** Feature property: the first zoom the POI is drawn at (only when later than its kind's own zoom). */
export const PROP = 'dz';

/** One limited POI: Web Mercator position (0..1), kind, named or not, fixed priority (higher = more notable). */
export interface DensityPoint { mx: number; my: number; kind: string; named: boolean; pr: number }

export const mercX = (lon: number) => (lon + 180) / 360;
export function mercY(lat: number): number {
  const r = (Math.max(-85.0511, Math.min(85.0511, lat)) * Math.PI) / 180;
  return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2;
}

/** The app's PoiDensity.firstZooms: the first zoom each point is drawn at (same order); a kind outside KINDS keeps 0. */
export function firstZooms(points: DensityPoint[]): number[] {
  const out: number[] = points.map((p) => (p.kind in KINDS ? ALL_FROM : 0));
  const limited = points.map((_, i) => i).filter((i) => points[i].kind in KINDS);
  if (!limited.length) return out;
  // more notable first: named, higher "pr", then a fixed position order (the same result on every build)
  const order = (a: number, b: number) => {
    const p = points[a], q = points[b];
    return Number(q.named) - Number(p.named) || q.pr - p.pr || p.my - q.my || p.mx - q.mx;
  };
  const stillShown = new Set(limited); // shown at every zoom from the one checked up to ALL_FROM - 1
  const lowest = Math.min(...Object.values(KINDS));
  for (let z = ALL_FROM - 1; z >= lowest; z--) {
    const n = 2 ** z;
    const byTile = new Map<string, number[]>();
    for (const i of limited) {
      if (KINDS[points[i].kind] > z) continue;
      const k = `${Math.floor(points[i].mx * n)}/${Math.floor(points[i].my * n)}`;
      let g = byTile.get(k);
      if (!g) byTile.set(k, g = []);
      g.push(i);
    }
    const shownHere = new Set<number>();
    for (const g of byTile.values()) for (const i of g.sort(order).slice(0, CAP)) shownHere.add(i);
    for (const i of limited) {
      if (!stillShown.has(i) || KINDS[points[i].kind] > z) continue;
      if (shownHere.has(i)) out[i] = z; else stillShown.delete(i);
    }
  }
  return out;
}

interface PoiFeature { geometry?: { type?: string; coordinates?: unknown } | null; properties?: Record<string, unknown> | null }

/**
 * One cell's POIs (its pois.geojson): "dz" on every limited POI drawn later than its kind (PoiTiles.densityZooms – a
 * stale "dz" is replaced, kept nowhere else). Returns how many got one. Never throws on an odd feature (left as it is).
 */
export function applyDensity(features: PoiFeature[]): number {
  const at: number[] = [];
  const points: DensityPoint[] = [];
  features.forEach((f, i) => {
    const p = f.properties;
    const kind = typeof p?.kind === 'string' ? p.kind : null;
    if (!p || !kind || !(kind in KINDS)) return;
    let c: unknown = f.geometry?.coordinates;
    while (Array.isArray(c) && Array.isArray(c[0])) c = c[0]; // the first position, as GeoJsonMerge.firstPosition
    if (!Array.isArray(c) || typeof c[0] !== 'number' || typeof c[1] !== 'number') return;
    const pr = Number(p.pr);
    points.push({ mx: mercX(c[0]), my: mercY(c[1]), kind, named: typeof p.name === 'string' && p.name.trim() !== '', pr: Number.isFinite(pr) ? Math.trunc(pr) : 0 });
    at.push(i);
  });
  const zooms = firstZooms(points);
  let n = 0;
  points.forEach((p, j) => {
    const props = features[at[j]].properties!;
    if (zooms[j] > KINDS[p.kind]) { props[PROP] = zooms[j]; n++; } else delete props[PROP];
  });
  return n;
}
