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
import { problem } from './diag';

const CELL_Z = 10;
/** Region packages: z7 tiles, the map z7–9 (map-cells.md §20; the app's Overview.PACKAGE_ZOOM). */
export const PACKAGE_Z = 7;
const LAYERS = ['base', 'overlays', 'terrain', 'contours'] as const;
/** Files a package brings (CellStore.LAYERS: hillshade.pmtiles = the style source region-shade). */
const PACKAGE_LAYERS = ['base', 'overlays', 'hillshade'] as const;
type Layer = (typeof LAYERS)[number] | 'hillshade';
/** Zoom range per layer (as the cell builder writes them; overzoomed above). */
const ZOOMS: Record<(typeof LAYERS)[number], [number, number]> = { base: [10, 14], overlays: [10, 14], terrain: [10, 13], contours: [12, 13] };

interface Cell { z: number; x: number; y: number; version: string; dir: string; archives: Partial<Record<Layer, PMTiles>> }

/**
 * A file read that failed (web-planner.md §17 "a cell whose map fails"): 'busy' = the server did not answer now (429,
 * 5xx, network – also an expired login) → tried again after a backoff, at most [MAX_TRIES] times; 'gone' = the file is
 * not there or not readable (404, 416, wrong bytes) → current.json is read again once (the cell may have a new version),
 * else the file is left out until the page is reloaded.
 */
interface Trouble { kind: 'busy' | 'gone'; tries: number; until: number; broken: boolean }
/** Seconds before the next try after the n-th failure (a 429 waits at least a minute: the host's Retry-After). */
const BACKOFF_S = [5, 15, 30, 60, 120, 300];
const MAX_TRIES = BACKOFF_S.length;
const kindOf = (e: unknown): 'busy' | 'gone' =>
  e instanceof TypeError || (e as Error)?.name === 'AbortError' || /Bad response code: (408|425|429|5\d\d)/.test(String((e as Error)?.message ?? e)) ? 'busy' : 'gone';

export class Cells {
  private cells = new Map<string, Promise<Cell | null>>();
  private poisLoaded = new Set<string>();
  /** POIs per cell ("x/y"), merged into the one POI source. */
  private pois = new Map<string, Feature[]>();
  /** Failed file reads per "<cell key>/<layer>" (see Trouble). */
  private trouble = new Map<string, Trouble>();
  /** current.json reads that failed without a 404 (server busy / offline), per cell key: not read again before [until]. */
  private lookupFails = new Map<string, { tries: number; until: number }>();
  /** current.json read again after a file of the cell was gone, per "<cell key>@<version>" (once): true = switched. */
  private rechecks = new Map<string, Promise<boolean>>();
  /** Versions replaced by a newer one ("<cell key>@<version>"): late failures of their reads are ignored. */
  private stale = new Set<string>();
  /** Layers to reload when the next try is due (one timer for all). */
  private retryLayers = new Set<string>();
  private retryTimer = 0;
  private retryAt = 0;
  private map: maplibregl.Map | null = null;
  /** Called when a cell's map starts or stops failing or drawing (main.ts: the map strip). */
  onChange: () => void = () => {};
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

  /** [root] = the store root, e.g. ".../" with cells/f2/10/<x>/<y>/current.json below. */
  constructor(private root: string) {}

  /** The map whose sources are reloaded after a failed read (main.ts). */
  attach(map: maplibregl.Map) { this.map = map; }

  /** The cell (z10) or region package (z7) at [x],[y]; null when none is built there (yet) or not known now. */
  private cell(x: number, y: number, z = CELL_Z): Promise<Cell | null> {
    const key = cellKey(z, x, y);
    let p = this.cells.get(key);
    if (!p) {
      const failed = this.lookupFails.get(key);
      if (failed && Date.now() < failed.until) return Promise.resolve(null); // the server was busy: not asked again yet
      p = (async () => {
        const base = `${this.root}cells/f2/${z}/${x}/${y}/`;
        let r: Response | null = null, err: unknown = null;
        try { r = await fetch(base + 'current.json', { cache: 'no-cache' }); } catch (e) { err = e; }
        if (r?.status === 404) { // nothing built here (yet) – remembered (map areas on demand asks for it)
          if (z === CELL_Z) this.absent.add(`${x}/${y}`);
          if (failed) { this.lookupFails.delete(key); this.onChange(); }
          return null;
        }
        let version: string | undefined;
        if (r?.ok) version = await r.json().then((j: { version?: string }) => j.version, (e) => { err = e; return undefined; });
        if (!version) {
          // busy / offline / an unreadable answer: asked again after a backoff (not on every tile – no request storm),
          // and the cell's sources are reloaded then, so its tiles come back
          this.cells.delete(key);
          const tries = (failed?.tries ?? 0) + 1;
          const wait = Math.max(BACKOFF_S[Math.min(tries, MAX_TRIES) - 1], r?.status === 429 ? 60 : 0);
          this.lookupFails.set(key, { tries, until: Date.now() + wait * 1000 });
          problem('cells', `map cell ${key}: current.json not read (${r ? 'HTTP ' + r.status : 'no connection'}) – trying again`, err);
          this.scheduleRetry(z === CELL_Z ? LAYERS : PACKAGE_LAYERS, wait);
          this.onChange();
          return null;
        }
        if (failed) { this.lookupFails.delete(key); this.onChange(); }
        if (z === CELL_Z) this.absent.delete(`${x}/${y}`); else this.packages.add(`${x}/${y}`);
        return { z, x, y, version, dir: `${base}${version}/`, archives: {} };
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

  /** True when the cell at zoom-10 tile [x],[y] is built (current.json there), false when not, null when not known now. */
  async exists(x: number, y: number): Promise<boolean | null> {
    if (await this.cell(x, y)) return true;
    return this.absent.has(`${x}/${y}`) ? false : null;
  }

  /**
   * The cells at [ids] were just built (areas.ts): forget the "not there" answer and reload the cell sources (new tile
   * URLs) – the detailed map appears without a page reload.
   */
  async refresh(map: maplibregl.Map, ids: [number, number][]) {
    for (const [x, y] of ids) this.forget(cellKey(CELL_Z, x, y));
    await Promise.all(ids.map(([x, y]) => this.cell(x, y)));
    this.reload(map, LAYERS);
    await this.loadPois(map);
  }

  /** Drops what is known about a cell (its version, files, failures, POIs) – it is read afresh on the next tile. */
  private forget(key: string) {
    this.cells.delete(key);
    this.poisLoaded.delete(key);
    this.pois.delete(key);
    this.lookupFails.delete(key);
    for (const l of [...LAYERS, 'hillshade']) this.trouble.delete(`${key}/${l}`);
    this.found.delete(key);
  }

  /** New tile URLs for [layers]: maplibre asks for every tile again (the tiles that came back empty come back). */
  private reload(map: maplibregl.Map | null, layers: readonly string[]) {
    if (!map) return;
    this.generation++;
    for (const layer of layers) {
      const src = map.getSource(layer === 'hillshade' ? 'region-shade' : layer) as unknown as { setTiles?: (t: string[]) => void } | undefined;
      src?.setTiles?.([`cell://${layer}/{z}/{x}/{y}?g=${this.generation}`]);
    }
  }

  /** Reloads [layers] in [seconds] (one timer; an earlier due time wins, layers join). */
  private scheduleRetry(layers: readonly string[], seconds: number) {
    for (const l of layers) this.retryLayers.add(l);
    const at = Date.now() + seconds * 1000;
    if (this.retryTimer && this.retryAt <= at) return;
    clearTimeout(this.retryTimer);
    this.retryAt = at;
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = 0;
      const ls = [...this.retryLayers];
      this.retryLayers.clear();
      this.reload(this.map, ls);
    }, at - Date.now() + 50);
  }

  /**
   * A read of [layer] in [c] failed (not aborted): left out for now, the overview stays visible there (never a blank
   * map), tried again with a backoff or – when the file is gone – after reading current.json once more. One step per
   * burst: the many tiles failing at once count as one failure.
   */
  private failed(c: Cell, layer: Layer, e: unknown) {
    const key = cellKey(c.z, c.x, c.y);
    const k = `${key}/${layer}`;
    const now = Date.now();
    if (this.stale.has(`${key}@${c.version}`)) return; // a read of a version already replaced
    const was = this.trouble.get(k);
    if (was && (was.broken || now < was.until)) return; // the same burst
    delete c.archives[layer]; // pmtiles keeps a failed header for good: a fresh archive reads it again on the next try
    const kind = kindOf(e);
    const tries = (was?.tries ?? 0) + 1;
    if (layer === 'base' && c.z === CELL_Z) this.found.delete(key);
    if (kind === 'gone') {
      // a new version may have replaced this one (the old folder goes later): current.json is read again, once per
      // version; the cell's other files that fail meanwhile wait for the same answer
      const rk = `${key}@${c.version}`;
      let p = this.rechecks.get(rk);
      if (!p) { p = this.recheck(c); this.rechecks.set(rk, p); }
      this.trouble.set(k, { kind, tries, until: now + 60_000, broken: false });
      void p.then((switched) => { if (!switched) { this.leftOut(c, layer, 'not readable', e); this.onChange(); } });
    } else if (tries > MAX_TRIES) {
      this.leftOut(c, layer, `not loaded after ${MAX_TRIES} tries`, e);
    } else {
      const wait = Math.max(BACKOFF_S[tries - 1], /\b429\b/.test(String((e as Error)?.message)) ? 60 : 0);
      this.trouble.set(k, { kind, tries, until: now + wait * 1000, broken: false });
      problem('cells', `map cell ${key}: ${layer}.pmtiles read failed – trying again`, e);
      this.scheduleRetry([layer], wait);
    }
    this.onChange();
  }

  /** [layer] of [c] left out until the page is reloaded (contours are optional: only a warning). */
  private leftOut(c: Cell, layer: Layer, why: string, e: unknown) {
    const key = cellKey(c.z, c.x, c.y);
    this.trouble.set(`${key}/${layer}`, { kind: 'gone', tries: MAX_TRIES, until: Infinity, broken: true });
    problem('cells', `map cell ${key}: ${layer}.pmtiles ${why} – left out until the page is reloaded`, e, layer === 'base' ? 'error' : 'warn');
  }

  /** current.json of [c] read again after a file of it was gone: a new version → switched to it (true). */
  private async recheck(c: Cell): Promise<boolean> {
    const key = cellKey(c.z, c.x, c.y);
    const r = await fetch(`${this.root}cells/f2/${c.z}/${c.x}/${c.y}/current.json`, { cache: 'no-cache' }).catch(() => null);
    const version = r?.ok ? await r.json().then((j: { version?: string }) => j.version, () => undefined) : undefined;
    if (!version || version === c.version) return false;
    problem('cells', `map cell ${key}: new version ${version} (the page had ${c.version}) – switched`, null);
    this.stale.add(`${key}@${c.version}`);
    this.forget(key);
    this.reload(this.map, c.z === CELL_Z ? LAYERS : PACKAGE_LAYERS);
    if (this.map) void this.loadPois(this.map);
    this.onChange();
    return true;
  }

  /** A read of [layer] in [c] worked again after a failure. */
  private recovered(c: Cell, layer: Layer) {
    if (this.trouble.delete(`${cellKey(c.z, c.x, c.y)}/${layer}`)) this.onChange();
  }

  /**
   * The detailed map's state in view (the map strip): 'broken' when a cell's base map is left out for good, 'busy' while
   * one is being tried again (or its current.json could not be read), else null.
   */
  baseTrouble(map: maplibregl.Map): 'busy' | 'broken' | null {
    if (map.getZoom() < CELL_Z - 0.5) return null;
    let out: 'busy' | 'broken' | null = null;
    for (const [x, y] of this.inView(map).slice(0, 16)) {
      const key = cellKey(CELL_Z, x, y);
      const t = this.trouble.get(`${key}/base`);
      if (t?.broken) return 'broken';
      if (t || this.lookupFails.has(key)) out = 'busy';
    }
    return out;
  }

  /** The cells' state for the diagnostics report. */
  diag(): string {
    const s = (until: number) => (until === Infinity ? 'left out' : `next try in ${Math.max(0, Math.round((until - Date.now()) / 1000))} s`);
    const lines = [`cells: root ${this.root}, generation ${this.generation}, drawing ${[...this.found].join(' ') || '-'}, not built ${this.absent.size}`];
    for (const [k, t] of this.trouble) lines.push(`  trouble ${k}: ${t.kind}, tries ${t.tries}, ${s(t.until)}`);
    for (const [k, f] of this.lookupFails) lines.push(`  current.json ${k}: failed ${f.tries}x, ${s(f.until)}`);
    return lines.join('\n');
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
      return this.read(pk, layer, z, x, y, abort, empty); // a package file that fails: the overview goes on there
    }
    if (layer === 'hillshade') return empty(); // the packages' relief ends at z9 (region-relief maxzoom 10)
    const c = await this.cell(x >> (z - CELL_Z), y >> (z - CELL_Z));
    if (!c) return empty();
    return this.read(c, layer, z, x, y, abort, empty);
  };

  /** One tile of [c]'s [layer]; a file in trouble is skipped (empty) until its next try is due. */
  private async read(c: Cell, layer: Layer, z: number, x: number, y: number, abort: AbortController, empty: () => Promise<{ data: Uint8Array }>) {
    const k = `${cellKey(c.z, c.x, c.y)}/${layer}`;
    const t0 = this.trouble.get(k);
    if (t0 && (t0.broken || Date.now() < t0.until)) return empty();
    try {
      const t = await this.archive(c, layer).getZxy(z, x, y, abort.signal);
      if (t0 && this.trouble.get(k) === t0) this.recovered(c, layer); // a try after the backoff worked
      // it draws: the overview may hide there now – not while other tiles of it failed (a read started before them)
      if (t && layer === 'base' && c.z === CELL_Z && !this.trouble.has(k) && !this.found.has(`${c.x}/${c.y}`)) {
        this.found.add(`${c.x}/${c.y}`);
        this.onChange();
      }
      return t ? { data: new Uint8Array(t.data) } : empty();
    } catch (e) {
      if (abort.signal.aborted) throw e;
      this.failed(c, layer, e);
      return empty();
    }
  }

  /** The app's format-2 style with its file sources pointed at the cells; the POI source starts empty. */
  style(s: StyleSpecification): StyleSpecification {
    for (const layer of LAYERS) {
      const src = s.sources[layer] as unknown as Record<string, unknown> | undefined;
      if (!src) continue;
      delete src.url;
      src.tiles = [`cell://${layer}/{z}/{x}/{y}`];
      [src.minzoom, src.maxzoom] = [0, ZOOMS[layer][1]];
    }
    // POIs: the cells carry pois.geojson, not the POI tiles the app writes when it joins cells – the style's stand-in
    // source is used, its layers lose "source-layer" (the app's OptionalSources.strip, "onpath:fallback-sources")
    const standIn = (s.metadata as Record<string, Record<string, unknown>> | undefined)?.['onpath:fallback-sources']?.pois;
    if (standIn && (s.sources.pois as { type?: string } | undefined)?.type !== 'geojson') {
      s.sources.pois = JSON.parse(JSON.stringify(standIn));
      for (const l of s.layers) if ('source' in l && l.source === 'pois') delete (l as { 'source-layer'?: string })['source-layer'];
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
      if (!c) { if (!this.absent.has(key)) this.poisLoaded.delete(key); return; } // not known now: a later move asks again
      try {
        const r = await fetch(c.dir + 'pois.geojson');
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const fc = (await r.json()) as FeatureCollection;
        if (this.poisLoaded.has(key)) { this.pois.set(key, fc.features); added = true; }
      } catch (e) {
        // POIs are optional: left out, asked again on a later move (once per move – no loop)
        this.poisLoaded.delete(key);
        problem('cells', `map cell ${key}: pois.geojson not loaded`, e);
      }
    }));
    if (added) (map.getSource('pois') as GeoJSONSource | undefined)?.setData({ type: 'FeatureCollection', features: [...this.pois.values()].flat() });
  }
}

const cellKey = (z: number, x: number, y: number) => (z === CELL_Z ? `${x}/${y}` : `${z}/${x}/${y}`);

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
