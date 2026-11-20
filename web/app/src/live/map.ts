// The viewer page's map (docs/design/live-tracking.md §3): the planner's map loading as it is (cells.ts, overview.ts –
// the same style, map areas and world overview), plus three things of its own: the planned route, the way so far and
// the last place. It follows the last place until the user moves the map; then "Zur Position" brings it back.
import * as maplibregl from 'maplibre-gl';
import type { StyleSpecification, GeoJSONSource } from 'maplibre-gl';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { Protocol } from 'pmtiles';
import { Cells } from '../cells';
import { overviewStyle, followCover, followPackages } from '../overview';
import type { Chunk } from './engine';

const ROUTE = '#1E3FAE'; // the app's route blue (ui/Theme OnPathColors.Route) – the plan, thin and light
const WAY = '#1E3FAE';   // the same blue, strong – the way really walked (green would vanish among the map's hiking trails)
type Line = [number, number][]; // [lon, lat]

const lines = (segs: Line[]) => ({ type: 'Feature' as const, properties: {},
  geometry: { type: 'MultiLineString' as const, coordinates: segs.filter((s) => s.length >= 2) } });

/** The chunks' points as lines, one per segment (a pause or a GPS gap starts a new one – never a straight jump). */
export function waySegments(chunks: Chunk[]): Line[] {
  const out: Line[] = [];
  let seg = -1;
  for (const c of chunks) for (const p of c.points) {
    if (p[0] !== seg || !out.length) { out.push([]); seg = p[0]; }
    out[out.length - 1].push([p[2], p[1]]);
  }
  return out;
}

export class LiveMap {
  readonly map: maplibregl.Map;
  private ready = false;
  private pending: (() => void) | null = null;
  private marker: maplibregl.Marker;
  private dot = document.createElement('div');
  private following = true;
  private last: [number, number] | null = null;
  private fitted = false;

  private constructor(style: StyleSpecification, box: HTMLElement, private back: HTMLButtonElement, cells: Cells | null) {
    this.map = new maplibregl.Map({ container: box, style, center: [11.16, 46.67], zoom: 9, attributionControl: false, maxPitch: 0 });
    this.dot.className = 'livedot';
    this.marker = new maplibregl.Marker({ element: this.dot });
    if (cells) { followCover(this.map, cells.found); cells.attach(this.map); followPackages(this.map, cells.packages); }
    this.map.on('error', () => { /* expected: a place without map data answers 404 – the way is drawn all the same */ });
    this.map.on('load', () => {
      const m = this.map;
      for (const id of ['live-route', 'live-way']) m.addSource(id, { type: 'geojson', data: lines([]) });
      const round = { 'line-join': 'round', 'line-cap': 'round' } as const;
      m.addLayer({ id: 'live-route-casing', type: 'line', source: 'live-route', layout: round, paint: { 'line-color': '#fff', 'line-width': 6, 'line-opacity': 0.8 } });
      m.addLayer({ id: 'live-route', type: 'line', source: 'live-route', layout: round, paint: { 'line-color': ROUTE, 'line-width': 3, 'line-opacity': 0.45 } });
      m.addLayer({ id: 'live-way-casing', type: 'line', source: 'live-way', layout: round, paint: { 'line-color': '#fff', 'line-width': 9 } });
      m.addLayer({ id: 'live-way', type: 'line', source: 'live-way', layout: round, paint: { 'line-color': WAY, 'line-width': 5 } });
      this.ready = true;
      this.pending?.(); this.pending = null;
    });
    // the user moved the map: stop following; the button brings the last place back
    const own = (e: { originalEvent?: unknown }) => { if (e.originalEvent && this.following) { this.following = false; this.back.hidden = !this.last; } };
    this.map.on('dragstart', own); this.map.on('zoomstart', own);
    back.addEventListener('click', () => {
      this.following = true; back.hidden = true;
      if (this.last) this.map.easeTo({ center: this.last, zoom: Math.max(this.map.getZoom(), 14) });
    });
    (window as unknown as { __map: maplibregl.Map }).__map = this.map; // for the browser tests
  }

  /** The map with the planner's style; [base] = the site's root. Never throws for missing map files. */
  static async create(base: string, box: HTMLElement, back: HTMLButtonElement): Promise<LiveMap> {
    maplibregl.setWorkerUrl(maplibreWorkerUrl);
    const pmtiles = new Protocol();
    maplibregl.addProtocol('pmtiles', pmtiles.tile);
    const cells = new Cells(new URLSearchParams(location.search).get('data') ?? base + 'data/');
    maplibregl.addProtocol('cell', cells.protocol);
    const s: StyleSpecification = (await overviewStyle(base, pmtiles)) ?? await (await fetch(base + 'style/onpath.json')).json();
    s.glyphs = base + 'fonts/{fontstack}/{range}.pbf';
    s.sprite = base + 'sprites/onpath';
    return new LiveMap(cells.style(s), box, back, cells);
  }

  /** Draws the tour: [route] as [lat, lon] pairs, the chunks that count, the last place and its look. */
  show(route: [number, number][], chunks: Chunk[], last: { lat: number; lon: number } | null, look: 'live' | 'quiet' | 'ended') {
    const draw = () => {
      const way = waySegments(chunks);
      (this.map.getSource('live-route') as GeoJSONSource).setData(lines([route.map(([la, lo]) => [lo, la] as [number, number])]));
      (this.map.getSource('live-way') as GeoJSONSource).setData(lines(way));
      this.dot.dataset.look = look;
      const end = way.length ? way[way.length - 1][way[way.length - 1].length - 1] : null;
      const at: [number, number] | null = last ? [last.lon, last.lat] : end;
      if (at) this.marker.setLngLat(at).addTo(this.map); else this.marker.remove();
      const moved = !!at && (!this.last || at[0] !== this.last[0] || at[1] !== this.last[1]);
      this.last = at;
      if (!at) return;
      if (!this.fitted) { // first data: the whole way so far (and the plan) in view
        this.fitted = true;
        const b = new maplibregl.LngLatBounds(at, at);
        for (const s of way) for (const p of s) b.extend(p);
        for (const [la, lo] of route) b.extend([lo, la]);
        this.map.fitBounds(b, { padding: 48, maxZoom: 15, animate: false });
      } else if (moved && this.following) this.map.easeTo({ center: at });
      this.stats = { way: way.map((s) => s.length), route: route.length };
    };
    if (this.ready) draw(); else this.pending = draw;
  }

  /** Nothing of the tour stays on the map (sharing stopped). */
  clear() { this.show([], [], null, 'ended'); this.stats = { way: [], route: 0 }; }

  /** What is drawn – for the browser tests. */
  stats: { way: number[]; route: number } = { way: [], route: 0 };
}
