// The world overview below the cells (docs/design/web-planner.md §16; the app: map/Overview.kt, map-cells.md §7):
// the APK's overview files (Natural Earth base z0–5, pre-rendered hill shade z0–7) drawn where there are no cells, so
// the map is never blank. The style is the app's own Overview.merge, run at build time (web/engine task
// overviewStyle); here only its asset:// URLs are pointed at the page's overview/ folder, and the cover (detail-cover:
// "hide the overview where the detailed map is") gets the cells found so far – the app uses its installed cells.
// Region packages (z7–9, map-cells.md §20): the build also writes the style merged for packages
// (onpath-overview-packages.json, Overview.merge with a placeholder box); the page prefers it and puts the boxes of the
// packages it finds into the `within` filter of the overview labels from z7 (followPackages) – the app reloads its
// style with the installed packages' boxes. Without any package that style draws like the plain overview one.
import type * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource, StyleSpecification } from 'maplibre-gl';

const FILES = ['base.pmtiles', 'hillshade.pmtiles'];
const ASSET = 'pmtiles://asset://overview/';

/** The merged style when the overview files are deployed next to the page, else null (the plain style is used). */
export async function overviewStyle(base: string): Promise<StyleSpecification | null> {
  const dir = new URL(base + 'overview/', location.href).href;
  const there = await Promise.all(FILES.map((f) => fetch(dir + f, { method: 'HEAD' }).then((r) => r.ok).catch(() => false)));
  if (!there.every(Boolean)) return null;
  let r = await fetch(base + 'style/onpath-overview-packages.json').catch(() => null);
  if (!r || !r.ok) {
    console.info('no region-package style (onpath-overview-packages.json): the overview style without packages is used');
    r = await fetch(base + 'style/onpath-overview.json').catch(() => null);
  }
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

/**
 * Keeps the overview's labels from z7 (`<id>~outside`) out of the region packages found (z7 tiles "x/y"): their
 * filter's `within` geometry becomes the packages' boxes – the app's Overview.multiPolygon (web/parity.json).
 */
export function followPackages(map: maplibregl.Map, found: Set<string>) {
  let shown = 0;
  const update = () => {
    if (found.size === shown || !map.isStyleLoaded()) return;
    shown = found.size;
    const geo = { type: 'MultiPolygon', coordinates: [...found].map((k) => {
      const [x, y] = k.split('/').map(Number);
      const [w, n, e, s] = [lon(x, 7), lat(y, 7), lon(x + 1, 7), lat(y + 1, 7)];
      return [[[w, s], [e, s], [e, n], [w, n], [w, s]]];
    }) };
    for (const l of map.getStyle().layers) {
      if (!l.id.endsWith('~outside')) continue;
      const f = map.getFilter(l.id);
      if (f) map.setFilter(l.id, withGeometry(f, geo) as maplibregl.FilterSpecification);
    }
    console.info(`region packages: ${[...found].join(', ')} – overview labels left out there from zoom 7`);
  };
  map.on('idle', update);
  map.on('sourcedata', update);
}

/** [f] with every ["within", …] geometry replaced by [geo]. */
function withGeometry(f: unknown, geo: object): unknown {
  if (!Array.isArray(f)) return f;
  if (f[0] === 'within') return ['within', geo];
  return f.map((x) => withGeometry(x, geo));
}

const lon = (x: number, z = 10) => (x / 2 ** z) * 360 - 180;
const lat = (y: number, z = 10) => (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / 2 ** z))) * 180) / Math.PI;
