// Map from the map server's cells (docs/design/map-cells.md): the same files the app downloads, read in place.
// A cell is one zoom-10 tile and holds only its own tiles at z >= 10 (one PMTiles per layer), so every tile request
// has exactly one owner: the cell of its zoom-10 parent. The phone joins cells into one file per layer (§19); the
// browser instead routes each tile request to the owning cell's archive – nothing is copied or merged.
// Region packages (map-cells.md §20) are read the same way: a z7 tile (cells/f2/7/<x>/<y>/…, same R2 layout and
// current.json) owns the tiles z7–9 under it – base, overlays (long-distance routes) and hillshade (the style source
// region-shade). The phone joins them into its files and gives Overview.merge their boxes; here the style is merged
// for packages at build time and the boxes found are set on the layers' `within` filter (overview.ts followPackages).
import * as maplibregl from 'maplibre-gl';
import type { StyleSpecification, GeoJSONSource } from 'maplibre-gl';
import type { Feature, FeatureCollection } from 'geojson';
import { PMTiles } from 'pmtiles';
import { PACKED, MemorySource } from './packed';

const CELL_Z = 10;
/** Region packages: z7 tiles, the map z7–9 (map-cells.md §20; the app's Overview.PACKAGE_ZOOM). */
export const PACKAGE_Z = 7;
const LAYERS = ['base', 'overlays', 'terrain', 'contours'] as const;
/** Files a package brings (CellStore.LAYERS: hillshade.pmtiles = the style source region-shade). */
const PACKAGE_LAYERS = ['base', 'overlays', 'hillshade'] as const;
type Layer = (typeof LAYERS)[number] | 'hillshade';
/** Zoom range per layer (as the cell builder writes them; overzoomed above). */
const ZOOMS: Record<(typeof LAYERS)[number], [number, number]> = { base: [10, 14], overlays: [10, 14], terrain: [10, 13], contours: [12, 13] };

interface Cell { z: number; x: number; y: number; dir: string; archives: Partial<Record<Layer, PMTiles>> }

export class Cells {
  private cells = new Map<string, Promise<Cell | null>>();
  private poisLoaded = new Set<string>();
  private pois: Feature[] = [];
  /**
   * Cells whose detailed map draws (a first tile came back) – the overview's cover follows these, so the overview stays
   * visible where a cell is missing or still loading (never a blank map; the app covers its installed cells).
   */
  readonly found = new Set<string>();
  /** Cells looked up and not built (current.json missing) – map areas on demand (areas.ts) asks for these. */
  readonly absent = new Set<string>();
  /** Bumped when a cell becomes ready (areas.ts): the tile URLs change, so maplibre asks again (reload). */
  generation = 0;
  /** Region packages that exist, "x/y" of their z7 tile (overview.ts followPackages). */
  readonly packages = new Set<string>();
  /** Package files found unreadable (warned once). */
  private missing = new Set<string>();

  /** [root] = the store root, e.g. ".../" with cells/f2/10/<x>/<y>/current.json below. */
  constructor(private root: string) {}

  /** The cell (z10) or region package (z7) at [x],[y]; null when none is built there (yet). */
  private cell(x: number, y: number, z = CELL_Z): Promise<Cell | null> {
    const key = z === CELL_Z ? `${x}/${y}` : `${z}/${x}/${y}`;
    let p = this.cells.get(key);
    if (!p) {
      p = (async () => {
        const base = `${this.root}cells/f2/${z}/${x}/${y}/`;
        const r = await fetch(base + 'current.json', { cache: 'no-cache' }).catch(() => null);
        if (!r || !r.ok) {
          if (z === CELL_Z && r?.status === 404) this.absent.add(`${x}/${y}`);
          if (!r || r.status !== 404) this.cells.delete(key); // a network error: asked again next time
          return null; // nothing built here (yet)
        }
        const { version } = (await r.json()) as { version: string };
        if (z === CELL_Z) this.absent.delete(`${x}/${y}`); else this.packages.add(`${x}/${y}`);
        return { z, x, y, dir: `${base}${version}/`, archives: {} };
      })();
      this.cells.set(key, p);
    }
    return p;
  }

  private archive(c: Cell, layer: Layer): PMTiles {
    const url = `${c.dir}${layer}.pmtiles`;
    // the preview host has no HTTP range: there the whole file is fetched once (base64) and read from memory
    return (c.archives[layer] ??= new PMTiles(PACKED ? new MemorySource(url) : url));
  }

  /** True when the cell at zoom-10 tile [x],[y] is built (current.json there). */
  async exists(x: number, y: number): Promise<boolean> { return (await this.cell(x, y)) != null; }

  /**
   * The cells at [ids] were just built (areas.ts): forget the "not there" answer and reload the cell sources (new tile
   * URLs) – the detailed map appears without a page reload.
   */
  async refresh(map: maplibregl.Map, ids: [number, number][]) {
    for (const [x, y] of ids) { this.cells.delete(`${x}/${y}`); this.poisLoaded.delete(`${x}/${y}`); }
    await Promise.all(ids.map(([x, y]) => this.cell(x, y)));
    this.generation++;
    for (const layer of LAYERS) {
      const src = map.getSource(layer) as unknown as { setTiles?: (t: string[]) => void } | undefined;
      src?.setTiles?.([`cell://${layer}/{z}/{x}/{y}?g=${this.generation}`]);
    }
    await this.loadPois(map);
  }

  /** maplibre protocol "cell://<layer>/{z}/{x}/{y}". */
  readonly protocol = async (params: { url: string }, abort: AbortController) => {
    const m = /^cell:\/\/(\w+)\/(\d+)\/(\d+)\/(\d+)/.exec(params.url);
    if (!m) throw new Error('bad cell url ' + params.url);
    const layer = m[1] as Layer;
    const [z, x, y] = [Number(m[2]), Number(m[3]), Number(m[4])];
    // nothing there: an empty vector tile, for terrain a flat one (a raster-dem tile must decode as an image), for the
    // packages' relief a clear one (the overview below shows through)
    const empty = async () => ({ data: layer === 'terrain' ? await flatTerrain() : layer === 'hillshade' ? await clearTile() : new Uint8Array(0) });
    if (z < PACKAGE_Z) return empty(); // below the packages: the overview map draws there
    if (z < CELL_Z) {
      // z7–9: the region package of the z7 parent (base, overlays, hillshade), else nothing
      if (!(PACKAGE_LAYERS as readonly string[]).includes(layer)) return empty();
      const pk = await this.cell(x >> (z - PACKAGE_Z), y >> (z - PACKAGE_Z), PACKAGE_Z);
      if (!pk) return empty();
      try {
        const t = await this.archive(pk, layer).getZxy(z, x, y, abort.signal);
        return t ? { data: new Uint8Array(t.data) } : empty();
      } catch (e) {
        if (abort.signal.aborted) throw e;
        // a package without this file (or a broken one): the overview goes on there – said once per file
        const k = `${pk.dir}${layer}`;
        if (!this.missing.has(k)) { this.missing.add(k); console.warn(`region package ${pk.x}/${pk.y}: ${layer}.pmtiles not readable – left out`, e); }
        return empty();
      }
    }
    if (layer === 'hillshade') return empty(); // the packages' relief ends at z9 (region-relief maxzoom 10)
    const c = await this.cell(x >> (z - CELL_Z), y >> (z - CELL_Z));
    if (!c) return empty();
    try {
      const t = await this.archive(c, layer).getZxy(z, x, y, abort.signal);
      if (t && layer === 'base') this.found.add(`${c.x}/${c.y}`); // it draws: the overview may hide there now
      return t ? { data: new Uint8Array(t.data) } : empty();
    } catch (e) {
      if (abort.signal.aborted) throw e;
      // a cell without this file (contours are optional) or a broken one: left out, the overview stays there – said once
      const k = `${c.dir}${layer}`;
      if (!this.missing.has(k)) { this.missing.add(k); console.warn(`map cell ${c.x}/${c.y}: ${layer}.pmtiles not readable – left out`, e); }
      return empty();
    }
  };

  /** The app's format-2 style with its file sources pointed at the cells; the POI source starts empty. */
  style(s: StyleSpecification): StyleSpecification {
    for (const layer of LAYERS) {
      const src = s.sources[layer] as unknown as Record<string, unknown> | undefined;
      if (!src) continue;
      delete src.url;
      src.tiles = [`cell://${layer}/{z}/{x}/{y}`];
      [src.minzoom, src.maxzoom] = [0, ZOOMS[layer][1]];
    }
    if (s.sources.pois) (s.sources.pois as { data: unknown }).data = { type: 'FeatureCollection', features: [] };
    // the region packages' relief (map-cells.md §20): their hillshade.pmtiles, z7–9 – used by the style merged for
    // packages (Overview.merge with packages: region-relief over the overview's fills); the plain style drops it below
    const shade = s.sources['region-shade'] as unknown as Record<string, unknown> | undefined;
    if (shade && packagesStyle(s)) {
      delete shade.url;
      Object.assign(shade, { tiles: ['cell://hillshade/{z}/{x}/{y}'], minzoom: PACKAGE_Z, maxzoom: CELL_Z - 1, tileSize: 256 });
      // never a blank map (web-planner.md §17): a package's relief goes on, magnified, above zoom 9 where no cell is –
      // it lies under the cover, which hides it where a cell draws (the app stops it at 10: there its cells are installed)
      const relief = s.layers.find((l) => l.id === 'region-relief');
      if (relief) delete relief.maxzoom;
    }
    // any other file source has no cell here: left out with its layers, like the app's OptionalSources.strip does
    // for a missing file
    const gone = Object.entries(s.sources).filter(([id, src]) => id !== 'pois' && !(LAYERS as readonly string[]).includes(id) &&
      /file:\/\//.test(String((src as { url?: unknown }).url ?? (src as { data?: unknown }).data ?? ''))).map(([id]) => id);
    for (const id of gone) delete s.sources[id];
    s.layers = s.layers.filter((l) => !('source' in l) || !gone.includes(l.source as string));
    return s;
  }

  /** The z10 tiles (cells) in view, nearest to the centre first. */
  private inView(map: maplibregl.Map): [number, number][] {
    const b = map.getBounds();
    const c = map.getCenter();
    const want: [number, number][] = [];
    for (let x = tileX(b.getWest()); x <= tileX(b.getEast()); x++)
      for (let y = tileY(b.getNorth()); y <= tileY(b.getSouth()); y++) want.push([x, y]);
    const cx = tileX(c.lng), cy = tileY(c.lat);
    return want.sort((a, b2) => Math.hypot(a[0] - cx, a[1] - cy) - Math.hypot(b2[0] - cx, b2[1] - cy));
  }

  /** Search areas: the existing cells in view (at most [max], nearest first) as {key, folder URL}. */
  async searchAreas(map: maplibregl.Map, max = 4): Promise<{ key: string; dir: string }[]> {
    const near = this.inView(map).slice(0, max);
    const cells = await Promise.all(near.map(([x, y]) => this.cell(x, y)));
    return cells.filter((c): c is Cell => c != null).map((c) => ({ key: `cell-10-${c.x}-${c.y}`, dir: c.dir }));
  }

  /** Loads the POIs of the cells in view (z >= 10) into the style's single POI source, like the app's merged file. */
  async loadPois(map: maplibregl.Map) {
    if (map.getZoom() < CELL_Z - 0.5) return;
    const want = this.inView(map);
    if (want.length > 16) return; // zoomed far out: skip
    let added = false;
    await Promise.all(want.map(async ([x, y]) => {
      const key = `${x}/${y}`;
      if (this.poisLoaded.has(key)) return;
      this.poisLoaded.add(key);
      const c = await this.cell(x, y);
      if (!c) return;
      const r = await fetch(c.dir + 'pois.geojson').catch(() => null);
      if (!r || !r.ok) return;
      const fc = (await r.json()) as FeatureCollection;
      this.pois.push(...fc.features);
      added = true;
    }));
    if (added) (map.getSource('pois') as GeoJSONSource | undefined)?.setData({ type: 'FeatureCollection', features: this.pois });
  }
}

/** True for the style merged for region packages (overview layers outside the packages: `<id>~overview~outside`). */
export const packagesStyle = (s: StyleSpecification) => s.layers.some((l) => l.id.endsWith('~outside'));

const tileX = (lon: number) => Math.floor(((lon + 180) / 360) * 2 ** CELL_Z);
const tileY = (lat: number) => {
  const r = (lat * Math.PI) / 180;
  return Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * 2 ** CELL_Z);
};

let clear: Promise<Uint8Array> | null = null;
/** A 256 px transparent PNG: no package relief here. */
function clearTile(): Promise<Uint8Array> {
  return (clear ??= (async () => {
    const c = new OffscreenCanvas(256, 256);
    c.getContext('2d')!.clearRect(0, 0, 256, 256); // convertToBlob needs a context
    return new Uint8Array(await (await c.convertToBlob({ type: 'image/png' })).arrayBuffer());
  })());
}

let flat: Promise<Uint8Array> | null = null;
/** A 256 px Terrarium tile at 0 m (R=128, G=0, B=0): no relief, no shading. */
function flatTerrain(): Promise<Uint8Array> {
  return (flat ??= (async () => {
    const c = new OffscreenCanvas(256, 256);
    const g = c.getContext('2d')!;
    g.fillStyle = 'rgb(128,0,0)';
    g.fillRect(0, 0, 256, 256);
    return new Uint8Array(await (await c.convertToBlob({ type: 'image/png' })).arrayBuffer());
  })());
}
