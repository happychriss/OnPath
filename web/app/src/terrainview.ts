// The terrain view (owner 2026-11-05, "wir bauen A und C"; docs/design/web-planner.md §19):
// A – the elevation profile linked to the map: the sheet's profile and a big one below the map share the app's cursor
//     (ElevationProfile: a line + "954 m · 3.2 km · 8 %", a white dot with the route's ring on the map – MapController's
//     highlight-dot); pointing at the route on the map moves the cursor in the profile. While the big profile is open the
//     route is coloured by steepness (the profile's colours).
// C – 3D: the map's own terrain (MapLibre setTerrain) from the cells' raster-dem source "terrain" (Terrarium, z10–13 –
//     the same files as the relief, a flat tile where none is), tilted, with a sky. Only on demand (the toolbar's 3D);
//     view only – a click on the map does not plan while it is on (one quiet line says so). Off: flat, north kept,
//     tilt 0, maxPitch 0 again (the app: turn yes, tilt no).
import * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource } from 'maplibre-gl';
import type { PlanResult } from './engine/types';
import { mountProfile, lineOf, pointAt, steepRuns, STEEP_COLOURS, type Line, type Profile, type ProfileView } from './profile';
import type { FeatureCollection } from 'geojson';
import { t } from './i18n';
import { problem } from './diag';

const CURSOR = 'profile-cursor', STEEP = 'profile-steep';
const MIN_ZOOM_3D = 11;

export class RouteProfile {
  private line: Line = { pts: [], cum: [] };
  private profile: Profile | null = null;
  private small: ProfileView | null = null;
  private big: ProfileView | null = null;
  private open = false;

  constructor(private map: maplibregl.Map, private routeColour: string, private sheetHost: HTMLElement,
    private panel: HTMLElement, private onOpenChange: (open: boolean) => void) {
    panel.querySelector<HTMLButtonElement>('.bpclose')!.onclick = () => this.setOpen(false);
  }

  /** On map load: the cursor dot and the steepness line (empty until used). */
  addLayers(before?: string) {
    const m = this.map;
    m.addSource(CURSOR, { type: 'geojson', data: empty() });
    m.addSource(STEEP, { type: 'geojson', data: empty() });
    m.addLayer({ id: STEEP, type: 'line', source: STEEP, layout: { 'line-join': 'round', 'line-cap': 'round', visibility: 'none' },
      paint: { 'line-color': ['get', 'colour'], 'line-width': 6 } }, before);
    m.addLayer({ id: CURSOR, type: 'circle', source: CURSOR,
      paint: { 'circle-radius': 7, 'circle-color': '#fff', 'circle-stroke-color': this.routeColour, 'circle-stroke-width': 4 } });
  }

  get isOpen() { return this.open; }

  /** A new plan (or none): the sheet's profile, and the big one when open. Same profile object = nothing to redraw. */
  update(plan: PlanResult | null, noElevation: string) {
    const p = plan?.profile ?? null;
    if (p === this.profile && plan) return;
    this.profile = p;
    this.line = plan && p ? lineOf(plan.legs) : { pts: [], cum: [] };
    this.cursor(null);
    if (!plan) { this.sheetHost.innerHTML = ''; this.small = null; this.setOpen(false); return; }
    if (!p || p.dist.length < 2) {
      this.sheetHost.innerHTML = `<div class="muted small noele">${escapeHtml(noElevation)}</div>`;
      this.small = null; this.setOpen(false); return;
    }
    this.small = mountProfile(this.sheetHost, p, (m) => this.cursor(m, 'small'));
    const b = document.createElement('button');
    b.className = 'iconbtn profbig'; b.id = 'profileBig';
    b.title = t('web_profile_big'); b.setAttribute('aria-label', t('web_profile_big'));
    b.setAttribute('aria-pressed', String(this.open));
    b.innerHTML = '<svg class="ic" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="M21 11V3h-8l3.29 3.29-10 10L3 13v8h8l-3.29-3.29 10-10z"/></svg>';
    b.onclick = () => this.setOpen(!this.open);
    this.sheetHost.querySelector('.profplot')!.append(b);
    if (this.open) this.drawBig();
  }

  setOpen(open: boolean) {
    if (open && !this.profile) open = false;
    if (open === this.open && (!open || this.big)) return;
    this.open = open;
    this.panel.hidden = !open;
    document.getElementById('profileBig')?.setAttribute('aria-pressed', String(open));
    if (open) this.drawBig(); else { this.big = null; this.panel.querySelector('.bpplot')!.innerHTML = ''; }
    this.steepLine();
    this.onOpenChange(open);
  }

  private drawBig() {
    if (!this.profile) return;
    this.big = mountProfile(this.panel.querySelector<HTMLElement>('.bpplot')!, this.profile, (m) => this.cursor(m, 'big'), { W: 1000, H: 150, flat: this.routeColour });
    this.steepLine();
  }

  /** The route coloured by steepness under the big profile (the app's gradient profile colours), else hidden. */
  private steepLine() {
    const src = this.map.getSource(STEEP) as GeoJSONSource | undefined;
    if (!src || !this.map.getLayer(STEEP)) return;
    const on = this.open && this.profile != null;
    try {
      src.setData(on ? { type: 'FeatureCollection', features: steepRuns(this.line, this.profile!).map((r) => ({ type: 'Feature',
        properties: { colour: r.cls === 0 ? this.routeColour : STEEP_COLOURS[r.cls] },
        geometry: { type: 'LineString', coordinates: r.coords } })) } : empty());
      this.map.setLayoutProperty(STEEP, 'visibility', on ? 'visible' : 'none');
    } catch (e) { problem('map', 'steepness line not drawn', e); }
  }

  /** The cursor at [m] metres: the dot on the map and the line in the profiles other than [from]. */
  private cursor(m: number | null, from?: 'small' | 'big' | 'map') {
    const at = m == null ? null : pointAt(this.line, m);
    (this.map.getSource(CURSOR) as GeoJSONSource | undefined)?.setData(at ? { type: 'Feature', properties: {},
      geometry: { type: 'Point', coordinates: [at[1], at[0]] } } : empty());
    if (from !== 'small') this.small?.show(m);
    if (from !== 'big') this.big?.show(m);
    (window as unknown as { __profileCursor: unknown }).__profileCursor = m == null ? null : { m, at }; // for the browser tests
  }

  /** The pointer over the map ([pt] in map pixels, null = left it): on the route → the cursor there. True when on it. */
  hover(pt: maplibregl.Point | null): boolean {
    if (!pt || !this.profile || !this.line.pts.length) { if (this.hovering) { this.hovering = false; this.cursor(null, 'map'); } return false; }
    let best = -1, bestD = 14 * 14;
    const { pts } = this.line;
    for (let j = 0; j < pts.length; j++) {
      const s = this.map.project([pts[j][1], pts[j][0]]);
      const d = (s.x - pt.x) ** 2 + (s.y - pt.y) ** 2;
      if (d < bestD) { bestD = d; best = j; }
    }
    if (best < 0) { if (this.hovering) { this.hovering = false; this.cursor(null, 'map'); } return false; }
    this.hovering = true;
    this.cursor(this.line.cum[best], 'map');
    return true;
  }
  private hovering = false;
}

export class Terrain3D {
  on = false;
  constructor(private map: maplibregl.Map, private button: HTMLButtonElement, private note: HTMLElement, private onChange: () => void) {
    button.onclick = () => this.set(!this.on);
    this.render();
  }

  /** The style has the cells' raster-dem source (not in a folder map without terrain.pmtiles). */
  get possible(): boolean { return this.map.getSource('terrain')?.type === 'raster-dem'; }

  render() {
    const b = this.button;
    b.disabled = !this.possible;
    b.classList.toggle('on', this.on);
    b.setAttribute('aria-pressed', String(this.on));
    const what = this.on ? t('web_terrain_off_title') : t('web_terrain_title');
    b.title = what; b.setAttribute('aria-label', what);
    document.body.classList.toggle('three-d', this.on);
    this.note.hidden = !this.on;
  }

  set(on: boolean) {
    if (on === this.on) return;
    const m = this.map;
    try {
      if (on) {
        if (!this.possible) return;
        // the cells carry heights from zoom 10 (z10–13); further out a tile is flat (0 m) and the mountains would stand on
        // walls – so 3D keeps the camera close (zoom ≥ 11) and the horizon away (tilt ≤ 60°)
        m.setMaxPitch(60);
        m.setMinZoom(MIN_ZOOM_3D);
        m.setSourceTileLodParams(1.5, 6, 'terrain'); // heights near the camera's zoom also further away (not the flat z<10)
        m.setTerrain({ source: 'terrain', exaggeration: 1.3 });
        m.setSky({ 'sky-color': '#9fc6e8', 'horizon-color': '#e6eff5', 'fog-color': '#f2f4f0', 'sky-horizon-blend': 0.6,
          'horizon-fog-blend': 0.6, 'fog-ground-blend': 0.75, 'atmosphere-blend': 0 });
        this.on = true;
        m.easeTo({ pitch: 55, zoom: Math.max(m.getZoom(), 12.5), duration: 900 });
      } else {
        this.on = false;
        m.setTerrain(null);
        m.setSky({});
        m.jumpTo({ pitch: 0 });
        m.setMaxPitch(0);
        m.setMinZoom(0);
        m.setSourceTileLodParams(4, 3, 'terrain'); // MapLibre's defaults
      }
    } catch (e) {
      problem('map', `3D terrain not switched ${on ? 'on' : 'off'}`, e);
      this.on = false;
      try { m.setTerrain(null); m.jumpTo({ pitch: 0 }); m.setMaxPitch(0); m.setMinZoom(0); } catch (e2) { problem('map', 'flat map not restored after 3D', e2); }
    }
    this.render();
    this.onChange();
  }
}

function empty(): FeatureCollection { return { type: 'FeatureCollection', features: [] }; }
function escapeHtml(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!)); }
