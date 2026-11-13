// Meine Touren's map pictures (owner 2026-11-13, the app's My routes L1 – app 21c0eac1 / 725b68e9 / 6ba34792): a square
// map tile left of each row's name – the route on the map, every route network left out, the whole route with its start
// and end inside. The app makes them with MapLibre's snapshotter on its offline map (map/ThumbMaker, RouteThumbs,
// ThumbRules); the planner the same way in the browser: one small MapLibre map off screen (84 × 84 CSS px at the screen's
// pixel ratio), the planner's own style without the `routes-*` layers and the `routes` data, the region square in Web
// Mercator around the route with 14 % + a marker free on every side (ThumbRules.region), the line 0.6 × the map's
// (3.6 px in a 6 px white casing), start (line colour) and goal (red) dots in a white ring, WebP (quality 82; PNG where a
// browser cannot write WebP).
// - Never blocks the list: a row shows its drawn line (routeshape.ts) until its picture is there; rows this browser has
//   no line for keep the kind's sign and get no picture (the list never routes anything).
// - One at a time, a short pause between two; the off-screen map goes 20 s after the last picture (its WebGL context).
// - Kept by line: the key is the row's line key (plan code / track id) + a hash of the line, so a route saved over gets a
//   new picture. In memory decoded-size capped (the app's RouteThumbImages 8 MB, w × h × 4) and in IndexedDB capped by
//   bytes (oldest use first out) – both made again when gone. A failure is an INFO and the row keeps its line (that
//   line is not tried again until the page reloads – the app: until the next start).
import * as maplibregl from 'maplibre-gl';
import type { StyleSpecification } from 'maplibre-gl';
import type { Seg } from './routeshape';
import { problem } from './diag';

/** ThumbRules.TILE_DP – the picture's edge in CSS px (the row's tile). */
export const TILE = 84;
/** ThumbRules.MARGIN / MARKER_DP / MARKER_RING_DP / LINE_SCALE; RouteLook.LINE_DP / CASING_DP. */
const MARGIN = 0.14, MARKER = 4.5, RING = 1.5, LINE_SCALE = 0.6, LINE = 6, CASING = 10;
/** ThumbRules.VERSION – raised when the look changes: every picture is made again once. */
const VERSION = 2;
/** ThumbMaker.QUALITY (0..1 here). */
const QUALITY = 0.82;
/** The plan blue (OnPathColors.ActionEdit = RouteShape.Tint.PLAN – planned and imported alike) and PointGoal. */
const LINE_COLOR = '#335A85', GOAL = '#A32020';
/** RouteThumbImages.MAX_BYTES: the decoded pictures in memory. */
const MEMORY_BYTES = 8 * 1024 * 1024;
/** The stored pictures in IndexedDB (2–10 KB each: room for the 250 tours of a phone and more). */
export const STORE_BYTES = 4 * 1024 * 1024;
/** A picture taking longer is given up (the row keeps its line) – the app's RouteSnapshot.TIMEOUT_MS is 15 s on its offline map; the browser fetches the map first. */
const TIMEOUT_MS = 30_000;
/** The pause between two pictures (the app's 400 ms; the list waits for none of them). */
const PAUSE_MS = 150;
/** The off-screen map goes this long after the last picture. */
const IDLE_MS = 20_000;

// ---- ThumbRules.region / project (pure, Web Mercator in 0..1, y down) ----
const mx = (lon: number) => (lon + 180) / 360;
const my = (lat: number) => { const s = Math.sin((Math.max(-85, Math.min(85, lat)) * Math.PI) / 180); return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI); };
const lonOf = (x: number) => x * 360 - 180;
const latOf = (y: number) => (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI;

/**
 * ThumbRules.region: the map region for a picture of [points] ([lat, lon]) as [south, west, north, east] – square in
 * Web Mercator around the points' middle, so a long thin route and a round trip fit whole, with [MARGIN] + a marker's
 * radius free on every side; at least 30 m across. Null without points.
 */
export function thumbRegion(points: [number, number][]): [number, number, number, number] | null {
  if (!points.length) return null;
  let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
  for (const [la, lo] of points) { const x = mx(lo), y = my(la); x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
  const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
  const span = Math.max(x1 - x0, y1 - y0, 30 / 40_075_016);
  const free = MARGIN + (MARKER + RING) / TILE;
  const half = span / 2 / (1 - 2 * free);
  return [latOf(cy + half), lonOf(cx - half), latOf(cy - half), lonOf(cx + half)];
}

/** ThumbRules.project: a point on a square picture of [region] [size] px wide (x, y). */
export function thumbProject([la, lo]: [number, number], r: [number, number, number, number], size: number): [number, number] {
  const x0 = mx(r[1]), x1 = mx(r[3]), y0 = my(r[2]), y1 = my(r[0]);
  return [((mx(lo) - x0) / (x1 - x0)) * size, ((my(la) - y0) / (y1 - y0)) * size];
}

/**
 * RouteSnapshot.withoutNetworks: the style without the route networks (owner 2026-11-13 on the Pixel: „ohne die lila
 * Wege“) – every layer `routes-*` and every layer drawn from the `routes` data (format 1: its source, format 2: its
 * source-layer), and that source. Also without what the page adds at run time (the plan, POIs, profile): only the map.
 */
export function thumbStyle(base: StyleSpecification): StyleSpecification {
  const s = JSON.parse(JSON.stringify(base)) as StyleSpecification;
  s.layers = s.layers.filter((l) => {
    const x = l as { id: string; source?: string; 'source-layer'?: string };
    return !x.id.startsWith('routes-') && x.source !== 'routes' && x['source-layer'] !== 'routes';
  });
  delete (s.sources as Record<string, unknown>).routes;
  return s;
}

/** A row's picture key: its line key (planKey / trackKey) and the line itself, so a changed line gets a new picture. */
export function thumbKey(lineKey: string, segs: Seg[]): string {
  let h = 2166136261 >>> 0;
  const add = (v: number) => { h = Math.imul(h ^ (Math.round(v * 1e5) | 0), 16777619) >>> 0; };
  for (const s of segs) { add(s.length); for (const [la, lo] of s) { add(la); add(lo); } }
  return `v${VERSION}|${lineKey}|${h.toString(36)}`;
}

// ---- IndexedDB: key → { blob, bytes, used } ----
const DB = 'onpath-thumbs', STORE = 'thumbs';
interface Stored { blob: Blob; bytes: number; used: number }
let dbp: Promise<IDBDatabase | null> | null = null;
function db(): Promise<IDBDatabase | null> {
  return (dbp ??= new Promise((res) => {
    try {
      const r = indexedDB.open(DB, 1);
      r.onupgradeneeded = () => { r.result.createObjectStore(STORE); };
      r.onsuccess = () => res(r.result);
      r.onerror = () => { problem('routes', 'map pictures: no IndexedDB – kept while the page is open', r.error); res(null); };
    } catch (e) { problem('routes', 'map pictures: no IndexedDB – kept while the page is open', e); res(null); }
  }));
}
function req<T>(r: IDBRequest<T>): Promise<T> { return new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); }); }
async function stored(key: string): Promise<Blob | null> {
  const d = await db();
  if (!d) return null;
  try {
    const st = d.transaction(STORE, 'readwrite').objectStore(STORE);
    const v = await req(st.get(key)) as Stored | undefined;
    if (!v) return null;
    st.put({ ...v, used: Date.now() }, key); // the oldest use goes first when the store is full
    return v.blob;
  } catch (e) { problem('routes', 'map picture unreadable – made again', e); return null; }
}
async function store(key: string, blob: Blob) {
  const d = await db();
  if (!d) return;
  try {
    const st = d.transaction(STORE, 'readwrite').objectStore(STORE);
    st.put({ blob, bytes: blob.size, used: Date.now() } satisfies Stored, key);
    // over the cap: the least recently used go
    const all: { key: string; bytes: number; used: number }[] = [];
    await new Promise<void>((res, rej) => {
      const c = st.openCursor();
      c.onsuccess = () => { const cur = c.result; if (!cur) { res(); return; } const v = cur.value as Stored; all.push({ key: String(cur.key), bytes: v.bytes, used: v.used }); cur.continue(); };
      c.onerror = () => rej(c.error);
    });
    let total = all.reduce((n, x) => n + x.bytes, 0);
    for (const x of all.sort((a, b) => a.used - b.used)) { if (total <= STORE_BYTES) break; if (x.key === key) continue; st.delete(x.key); total -= x.bytes; }
  } catch (e) { problem('routes', 'map picture not stored – made again next time', e); }
}

// ---- the pictures ----
interface Job { key: string; segs: Seg[]; resolve: (url: string | null) => void }

export class RouteThumbs {
  /** key → object URL, oldest use first (Map order); decoded bytes beside it. */
  private memory = new Map<string, { url: string; bytes: number }>();
  private memoryBytes = 0;
  private queue: Job[] = [];
  private waiting = new Map<string, Promise<string | null>>();
  private failed = new Set<string>();
  private running = false;
  private map: maplibregl.Map | null = null;
  private box: HTMLDivElement | null = null;
  private idleTimer = 0;
  private broken = false;
  /** Diagnostics and the smokes: pictures made (ms each), from the store, failed. */
  readonly stats = { made: 0, fromStore: 0, failed: 0, ms: [] as number[], layers: [] as string[] };

  /** [style] = the planner's map style as loaded (before anything is added at run time); null = no map now. */
  constructor(private style: () => StyleSpecification | null) {}

  /** The picture for [key] from memory at once, else null (then [get]). */
  cached(key: string): string | null {
    const m = this.memory.get(key);
    if (!m) return null;
    this.memory.delete(key); this.memory.set(key, m); // most recently used last
    return m.url;
  }

  /** The picture for [key] of [segs] (stored, else made – one at a time); null = none (the row keeps its line). */
  get(key: string, segs: Seg[]): Promise<string | null> {
    const have = this.cached(key);
    if (have) return Promise.resolve(have);
    if (this.failed.has(key) || this.broken || !this.allowed()) return Promise.resolve(null);
    let p = this.waiting.get(key);
    if (!p) {
      p = (async () => {
        const blob = await stored(key);
        if (blob) { this.stats.fromStore++; return this.keep(key, blob); }
        return new Promise<string | null>((resolve) => { this.queue.push({ key, segs, resolve }); void this.run(); });
      })().finally(() => this.waiting.delete(key));
      this.waiting.set(key, p);
    }
    return p;
  }

  /** Jobs no row asks for any more (the list closed): dropped; their promises answer null. */
  forgetQueued() {
    for (const j of this.queue.splice(0)) j.resolve(null);
    // the picture being made stops too, and the off-screen map goes at once: while it loads its tiles it draws frame
    // after frame on the page's thread – the plan the user goes back to comes first (a route drawing itself, a drag)
    if (this.stop) { this.stop(); this.drop(); }
  }
  /** Stops the wait of the picture being made (Meine Touren closed). */
  private stop: (() => void) | null = null;

  private async keep(key: string, blob: Blob): Promise<string> {
    const url = URL.createObjectURL(blob);
    const px = Math.round(TILE * this.ratio());
    const bytes = px * px * 4; // RouteThumbImages.bytesOf: decoded, 4 bytes a pixel
    const old = this.memory.get(key);
    if (old) { this.memoryBytes -= old.bytes; URL.revokeObjectURL(old.url); this.memory.delete(key); }
    this.memory.set(key, { url, bytes });
    this.memoryBytes += bytes;
    for (const [k, v] of this.memory) { // oldest use first out; an <img> already showing it keeps its decoded picture
      if (this.memoryBytes <= MEMORY_BYTES || k === key) break;
      URL.revokeObjectURL(v.url); this.memory.delete(k); this.memoryBytes -= v.bytes;
    }
    return url;
  }

  /**
   * Automated browsers make pictures only with `window.__thumbsOn` (like the route's reveal, `__reveal`): their software
   * GL (swiftshader) draws the off-screen map on the page's thread for seconds and starves the other tests' frames;
   * routes-smoke turns it on.
   */
  private allowed() { return !navigator.webdriver || !!(window as unknown as { __thumbsOn?: boolean }).__thumbsOn; }

  private ratio() { return Math.max(1, Math.min(3, window.devicePixelRatio || 1)); }

  private async run() {
    if (this.running) return;
    this.running = true;
    clearTimeout(this.idleTimer);
    try {
      for (let j = this.queue.shift(); j; j = this.queue.shift()) {
        const t0 = performance.now();
        let blob: Blob | null | 'later' = null;
        try { blob = await this.make(j.segs); } catch (e) { problem('routes', 'map picture of a route not made – its row keeps the drawn line', e); }
        if (blob === 'later') { j.resolve(null); continue; } // no map style yet (the page is starting): asked again with the next list
        if (!blob) { this.failed.add(j.key); this.stats.failed++; j.resolve(null); continue; }
        this.stats.made++; this.stats.ms.push(Math.round(performance.now() - t0)); if (this.stats.ms.length > 20) this.stats.ms.shift();
        void store(j.key, blob);
        j.resolve(await this.keep(j.key, blob));
        await new Promise((r) => setTimeout(r, PAUSE_MS)); // low priority: the page's own work goes first
      }
    } finally {
      this.running = false;
      this.idleTimer = window.setTimeout(() => this.drop(), IDLE_MS);
    }
  }

  /** The off-screen map and its WebGL context go (made again for the next picture). */
  private drop() {
    try { this.map?.remove(); } catch { /* expected: a lost context throws on remove – nothing to keep */ }
    this.map = null; this.box?.remove(); this.box = null;
  }

  /** The off-screen map, made once with the style; null = no style or no WebGL (then no pictures this page load). */
  private async offscreen(): Promise<maplibregl.Map | null> {
    if (this.map) return this.map;
    const base = this.style();
    if (!base) return null;
    const box = document.createElement('div');
    box.setAttribute('aria-hidden', 'true');
    box.className = 'thumbmaker';
    box.style.cssText = `position:fixed;left:-${TILE * 4}px;top:0;width:${TILE}px;height:${TILE}px;pointer-events:none;opacity:0;`;
    document.body.append(box);
    let m: maplibregl.Map;
    try {
      const style = thumbStyle(base);
      style.sources['thumb-line'] = { type: 'geojson', data: { type: 'FeatureCollection', features: [] } };
      style.layers.push(
        { id: 'thumb-casing', type: 'line', source: 'thumb-line', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#fff', 'line-width': CASING * LINE_SCALE } },
        { id: 'thumb-line', type: 'line', source: 'thumb-line', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': LINE_COLOR, 'line-width': LINE * LINE_SCALE } });
      m = new maplibregl.Map({ container: box, style, interactive: false, attributionControl: false, pixelRatio: this.ratio(),
        canvasContextAttributes: { preserveDrawingBuffer: true }, fadeDuration: 0, center: [0, 0], zoom: 1 } as maplibregl.MapOptions);
    } catch (e) {
      box.remove();
      this.broken = true;
      problem('routes', 'map pictures cannot be made in this browser (no WebGL) – the rows keep their drawn lines', e);
      return null;
    }
    // expected: tiles of a place without map data answer 404 – the main map reports the map's trouble, not each picture
    m.on('error', () => {});
    await new Promise<void>((res) => { if (m.isStyleLoaded()) res(); else m.once('load', () => res()); });
    this.map = m; this.box = box;
    this.stats.layers = m.getStyle().layers.map((l) => l.id); // what the pictures draw (the smokes: no route networks)
    return m;
  }

  /** One picture of [segs] (ThumbMaker.render): the region, the line, the map once idle, the two dots, encoded. */
  private async make(segs: Seg[]): Promise<Blob | null | 'later'> {
    const pts = segs.flat();
    const region = thumbRegion(pts);
    if (!region || segs.every((s) => s.length < 2)) return null;
    const m = await this.offscreen();
    if (!m) return this.broken ? null : 'later';
    (m.getSource('thumb-line') as maplibregl.GeoJSONSource).setData({ type: 'Feature', properties: {},
      geometry: { type: 'MultiLineString', coordinates: segs.filter((s) => s.length >= 2).map((s) => s.map(([la, lo]) => [lo, la])) } });
    // the square region exactly fills the TILE px picture: zoom from its Mercator span (512 px tiles)
    const span = mx(region[3]) - mx(region[1]);
    const zoom = Math.log2(TILE / (512 * span));
    m.jumpTo({ center: [lonOf((mx(region[1]) + mx(region[3])) / 2), latOf((my(region[0]) + my(region[2])) / 2)], zoom, bearing: 0, pitch: 0 });
    const idle = await new Promise<boolean | 'stop'>((res) => {
      const t = setTimeout(() => { m.off('idle', on); this.stop = null; res(false); }, TIMEOUT_MS);
      const on = () => { clearTimeout(t); this.stop = null; res(true); };
      this.stop = () => { clearTimeout(t); m.off('idle', on); this.stop = null; res('stop'); };
      m.once('idle', on);
      m.triggerRepaint();
    });
    if (idle === 'stop') return 'later'; // Meine Touren closed: the page's own work first – made when the list asks again
    if (!idle) { problem('routes', `map picture took longer than ${TIMEOUT_MS / 1000} s – its row keeps the drawn line`, null); return null; }
    const ratio = this.ratio(), px = Math.round(TILE * ratio);
    const c = document.createElement('canvas');
    c.width = c.height = px;
    const g = c.getContext('2d');
    if (!g) return null;
    g.drawImage(m.getCanvas(), 0, 0, px, px);
    // start (the line's colour) and goal (red), each in a white ring – the signs of the rows' drawn line
    const dot = ([la, lo]: [number, number], fill: string) => {
      const [x, y] = thumbProject([la, lo], region, px);
      g.beginPath(); g.arc(x, y, (MARKER + RING) * ratio, 0, Math.PI * 2); g.fillStyle = '#fff'; g.fill();
      g.beginPath(); g.arc(x, y, MARKER * ratio, 0, Math.PI * 2); g.fillStyle = fill; g.fill();
    };
    const lines = segs.filter((s) => s.length);
    dot(lines[0][0], LINE_COLOR);
    const last = lines[lines.length - 1];
    dot(last[last.length - 1], GOAL);
    return new Promise((res) => c.toBlob((b) => res(b), 'image/webp', QUALITY));
  }
}
