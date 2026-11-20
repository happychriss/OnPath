// The POI a route point sits on (the app's poi/PinnedPoiKinds lookup): its details in the point's menu. The planner draws
// such a point as the plain numbered point (owner 2026-11-20: „ein normaler Punkt reicht“ – a planner-only look on a big
// screen); the app keeps the POI's symbol in a blue ring with a badge (map/MapController.poiMarker).
import type { Map as MlMap, GeoJSONFeature } from 'maplibre-gl';
import { problem } from './diag';

/** A POI this close to a pinned point is the one it was pinned on (PinnedPoiKinds.SAME_SPOT_M). */
export const SAME_SPOT_M = 5;

/** Only kinds the map draws with their own symbol (the sprite's "poi-<kind>"; "default" is no kind). */
export function isPoiKind(map: MlMap, kind: string | null | undefined): kind is string {
  return !!kind && kind !== 'default' && map.hasImage('poi-' + kind);
}

function metres(lat1: number, lon1: number, lat2: number, lon2: number) {
  const k = Math.PI / 180, x = (lon2 - lon1) * k * Math.cos(((lat1 + lat2) / 2) * k), y = (lat2 - lat1) * k;
  return Math.sqrt(x * x + y * y) * 6371000;
}

/**
 * The POI itself (kind, name, elevation) the map has loaded nearest the point within [SAME_SPOT_M] – the one a route
 * point sits on; null when none.
 */
export function poiFeatureAt(map: MlMap, lat: number, lon: number): GeoJSONFeature | null {
  const src = map.getSource('pois');
  if (!src) return null;
  let fs: GeoJSONFeature[];
  try { fs = map.querySourceFeatures('pois', src.type === 'vector' ? { sourceLayer: 'pois' } : undefined); }
  catch (e) { problem('map', 'POIs under the pinned points not read; their menus without POI details', e); return null; }
  let best: GeoJSONFeature | null = null, bestM = SAME_SPOT_M;
  for (const f of fs) {
    if (f.geometry.type !== 'Point') continue;
    const [lo, la] = f.geometry.coordinates;
    const kind = f.properties?.kind == null ? null : String(f.properties.kind);
    const m = metres(lat, lon, la, lo);
    if (m <= bestM && isPoiKind(map, kind)) { best = f; bestM = m; }
  }
  return best;
}
