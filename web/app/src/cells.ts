// Map from the map server's cells (docs/design/map-cells.md): the same files the app downloads, read in place.
// A cell is one zoom-10 tile and holds only its own tiles at z >= 10 (one PMTiles per layer), so every tile request
// has exactly one owner: the cell of its zoom-10 parent. The phone joins cells into one file per layer (§19); the
// browser instead routes each tile request to the owning cell's archive – nothing is copied or merged.
import * as maplibregl from 'maplibre-gl';
import type { StyleSpecification, GeoJSONSource } from 'maplibre-gl';
import type { Feature, FeatureCollection } from 'geojson';
import { PMTiles } from 'pmtiles';
import { PACKED, MemorySource } from './packed';

const CELL_Z = 10;
const LAYERS = ['base', 'overlays', 'terrain', 'contours'] as const;
type Layer = (typeof LAYERS)[number];
/** Zoom range per layer (as the cell builder writes them; overzoomed above). */
const ZOOMS: Record<Layer, [number, number]> = { base: [10, 14], overlays: [10, 14], terrain: [10, 13], contours: [12, 13] };

interface Cell { x: number; y: number; dir: string; archives: Partial<Record<Layer, PMTiles>> }

export class Cells {
  private cells = new Map<string, Promise<Cell | null>>();
  private poisLoaded = new Set<string>();
  private pois: Feature[] = [];
  /** Cells that exist (for the "no map here" hint). */
  readonly found = new Set<string>();

  /** [root] = the store root, e.g. ".../" with cells/f2/10/<x>/<y>/current.json below. */
  constructor(private root: string) {}

  private cell(x: number, y: number): Promise<Cell | null> {
    const key = `${x}/${y}`;
    let p = this.cells.get(key);
    if (!p) {
      p = (async () => {
        const base = `${this.root}cells/f2/${CELL_Z}/${x}/${y}/`;
        const r = await fetch(base + 'current.json').catch(() => null);
        if (!r || !r.ok) return null; // no cell built here (yet)
        const { version } = (await r.json()) as { version: string };
        this.found.add(key);
        return { x, y, dir: `${base}${version}/`, archives: {} };
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

  /** maplibre protocol "cell://<layer>/{z}/{x}/{y}". */
  readonly protocol = async (params: { url: string }, abort: AbortController) => {
    const m = /^cell:\/\/(\w+)\/(\d+)\/(\d+)\/(\d+)/.exec(params.url);
    if (!m) throw new Error('bad cell url ' + params.url);
    const layer = m[1] as Layer;
    const [z, x, y] = [Number(m[2]), Number(m[3]), Number(m[4])];
    // nothing there: an empty vector tile, or for terrain a flat one (a raster-dem tile must decode as an image)
    const empty = async () => ({ data: layer === 'terrain' ? await flatTerrain() : new Uint8Array(0) });
    if (z < CELL_Z) return empty(); // below the cells: nothing yet (the app shows its overview map there)
    const c = await this.cell(x >> (z - CELL_Z), y >> (z - CELL_Z));
    if (!c) return empty();
    const t = await this.archive(c, layer).getZxy(z, x, y, abort.signal);
    return t ? { data: new Uint8Array(t.data) } : empty();
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

const tileX = (lon: number) => Math.floor(((lon + 180) / 360) * 2 ** CELL_Z);
const tileY = (lat: number) => {
  const r = (lat * Math.PI) / 180;
  return Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * 2 ** CELL_Z);
};

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
