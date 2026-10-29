// The world overview below the cells (docs/design/web-planner.md §16; the app: map/Overview.kt, map-cells.md §7):
// the APK's overview files (Natural Earth base z0–5, pre-rendered hill shade z0–7) drawn where there are no cells, so
// the map is never blank. The style is the app's own Overview.merge, run at build time (web/engine task
// overviewStyle); here only its asset:// URLs are pointed at the page's overview/ folder, and the cover (detail-cover:
// "hide the overview where the detailed map is") gets the cells found so far – the app uses its installed cells.
import type * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource, StyleSpecification } from 'maplibre-gl';

const FILES = ['base.pmtiles', 'hillshade.pmtiles'];
const ASSET = 'pmtiles://asset://overview/';

/** The merged style when the overview files are deployed next to the page, else null (the plain style is used). */
export async function overviewStyle(base: string): Promise<StyleSpecification | null> {
  const dir = new URL(base + 'overview/', location.href).href;
  const there = await Promise.all(FILES.map((f) => fetch(dir + f, { method: 'HEAD' }).then((r) => r.ok).catch(() => false)));
  if (!there.every(Boolean)) return null;
  const r = await fetch(base + 'style/onpath-overview.json').catch(() => null);
  if (!r || !r.ok) return null;
  const s = (await r.json()) as StyleSpecification;
  for (const src of Object.values(s.sources)) {
    const x = src as { url?: string };
    if (typeof x.url === 'string' && x.url.startsWith(ASSET)) x.url = 'pmtiles://' + dir + x.url.slice(ASSET.length);
  }
  return s;
}

/** Keeps the cover on the cells found (z10 tiles "x/y"): there the detailed map hides the magnified overview. */
export function followCover(map: maplibregl.Map, found: Set<string>) {
  let shown = -1;
  const update = () => {
    if (found.size === shown) return;
    shown = found.size;
    const src = map.getSource('detail-cover') as GeoJSONSource | undefined;
    if (!src) return;
    src.setData({ type: 'FeatureCollection', features: [...found].map((k) => {
      const [x, y] = k.split('/').map(Number);
      const [w, n, e, s] = [lon(x), lat(y), lon(x + 1), lat(y + 1)];
      return { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [[[w, s], [e, s], [e, n], [w, n], [w, s]]] } };
    }) });
  };
  map.on('idle', update);
  map.on('sourcedata', update);
}

const lon = (x: number) => (x / 1024) * 360 - 180;
const lat = (y: number) => (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / 1024))) * 180) / Math.PI;
