// A route point pinned on a POI keeps the POI's symbol: a ring in route blue around it and a small badge top right with
// the number (the goal: its flag) – owner 2026-11-10, variant A. Mirrors the app's poi/PinnedPoiKinds (which kind) and
// map/MapController.poiMarker (the look; sizes in px = the app's dp).
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

/** The nearest POI the map has loaded within [SAME_SPOT_M] of the point (PinnedPoiKinds.lookup); null when none. */
export function poiKindAt(map: MlMap, lat: number, lon: number): string | null {
  const src = map.getSource('pois');
  if (!src) return null;
  let fs: GeoJSONFeature[];
  try { fs = map.querySourceFeatures('pois', src.type === 'vector' ? { sourceLayer: 'pois' } : undefined); }
  catch (e) { problem('map', 'POIs under the pinned points not read; plain points shown', e); return null; }
  let best: string | null = null, bestM = SAME_SPOT_M;
  for (const f of fs) {
    if (f.geometry.type !== 'Point') continue;
    const [lo, la] = f.geometry.coordinates;
    const kind = f.properties?.kind == null ? null : String(f.properties.kind);
    const m = metres(lat, lon, la, lo);
    if (m <= bestM && isPoiKind(map, kind)) { best = kind; bestM = m; }
  }
  return best;
}

const symbols = new Map<string, string>();
/** The sprite's "poi-<kind>" as a data URL (once per kind and style); null when the style has none. */
function symbolUrl(map: MlMap, kind: string): string | null {
  const id = 'poi-' + kind;
  const known = symbols.get(id);
  if (known) return known;
  try {
    const img = map.getImage(id);
    if (!img) return null;
    const { width, height, data } = img.data;
    const c = document.createElement('canvas');
    c.width = width; c.height = height;
    // a CPU canvas (willReadFrequently): toDataURL on a GPU canvas waits for the GPU process to finish the map's frames
    // first – with software GL (SwiftShader, no GPU) that held the page for seconds after a POI was added
    c.getContext('2d', { willReadFrequently: true })!.putImageData(new ImageData(new Uint8ClampedArray(data), width, height), 0, 0);
    const url = c.toDataURL();
    symbols.set(id, url);
    return url;
  } catch (e) { problem('map', `POI symbol ${kind} not drawn; plain point shown`, e); return null; }
}

/**
 * The marker element of a pinned point on a POI of [kind]: the symbol bigger in a blue ring, the [sign] (number, ↺ or
 * the flag) on a small badge in [badge] colour; null when the symbol is missing – the caller draws the plain point.
 */
export function poiPin(map: MlMap, kind: string, sign: string, badge: string): HTMLDivElement | null {
  const url = symbolUrl(map, kind);
  if (!url) return null;
  const el = document.createElement('div');
  el.className = 'pin poipin';
  el.dataset.kind = kind;
  el.innerHTML = `<img src="${url}" alt="" draggable="false"><span class="badge" style="background:${badge}">${sign}</span>`;
  return el;
}
