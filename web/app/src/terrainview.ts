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
import { mountProfile, lineOf, pointAt, steepRuns, lineBetween, STEEP_COLOURS, type Line, type Profile, type ProfileView } from './profile';
import type { FeatureCollection } from 'geojson';
import { t } from './i18n';
import { problem } from './diag';

const STEEP = 'profile-steep', RANGE = 'profile-range';
const MIN_ZOOM_3D = 11;

export class RouteProfile {
  private line: Line = { pts: [], cum: [] };
  private profile: Profile | null = null;
  private small: ProfileView | null = null;
  private big: ProfileView | null = null;
  private open = false;
  /** The marked range in the big profile [from, to] metres (owner 2026-11-06), null = none. */
  private marked: [number, number] | null = null;
  private rangeAsk = 0;
  /** Length · time · climb of a stretch of the plan – the engine's WebPace.rangeStats (the page sets it). */
  rangeStats: ((from: number, to: number) => Promise<{ text: string } | null>) | null = null;
  /** The cursor's dot on the map: a DOM marker, not a map source – moving it never redraws the map (in 3D every source
   * update re-drew the whole terrain: 3D + big profile ran at ~8 frames/s, owner 2026-11-06). Same look as the app's
   * highlight-dot: white, the route's colour as a 4 px ring, 22 px across. */
  private dot: maplibregl.Marker | null = null;
  /** A range dragged in the big profile, applied at most every 40 ms (the map's highlighter and the engine's numbers). */
  private pendingRange: [number, number] | null | undefined = undefined;
  private rangeTimer = 0;

  /**
   * Wide screens (owner 2026-11-07, plan sheet A + the profile along the bottom): the big profile is always there while a
   * plan has heights – no ✕, no „⤢ Großes Profil“ (main.ts sets it with the width).
   */
  private pinnedOn = false;

  constructor(private map: maplibregl.Map, private routeColour: string, private sheetHost: HTMLElement,
    private panel: HTMLElement, private onOpenChange: (open: boolean, auto: boolean) => void) {
    panel.querySelector<HTMLButtonElement>('.bpclose')!.onclick = () => this.setOpen(false);
    // the range's numbers in the big profile's head, beside the title
    const head = panel.querySelector<HTMLElement>('.bphead')!;
    const lbl = document.createElement('span');
    lbl.className = 'bprange'; lbl.hidden = true; lbl.setAttribute('aria-live', 'polite');
    head.querySelector('.bplegend')?.before(lbl);
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && this.marked) this.mark(null); });
  }

  /** On map load: the cursor dot and the steepness line (empty until used). */
  addLayers(before?: string) {
    const m = this.map;
    m.addSource(STEEP, { type: 'geojson', data: empty() });
    m.addSource(RANGE, { type: 'geojson', data: empty() });
    // the marked range: a wide highlighter under the route (owner 2026-11-06)
    m.addLayer({ id: RANGE, type: 'line', source: RANGE, layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': '#FFD54F', 'line-width': 18, 'line-opacity': 0.85 } }, before);
    // a white edge under the steepness colours: the flat green must stay visible on the green map
    m.addLayer({ id: STEEP + '-edge', type: 'line', source: STEEP, layout: { 'line-join': 'round', 'line-cap': 'round', visibility: 'none' },
      paint: { 'line-color': '#fff', 'line-width': 9 } }, before);
    m.addLayer({ id: STEEP, type: 'line', source: STEEP, layout: { 'line-join': 'round', 'line-cap': 'round', visibility: 'none' },
      paint: { 'line-color': ['get', 'colour'], 'line-width': 6 } }, before);
    const el = document.createElement('div');
    el.className = 'profdot'; el.style.borderColor = this.routeColour; el.hidden = true;
    this.dot = new maplibregl.Marker({ element: el }).setLngLat([0, 0]).addTo(m);
  }

  get isOpen() { return this.open; }

  /** The route's vertices with their distance along it (the flyover follows it), empty without a plan. */
  get routeLine(): Line { return this.line; }
  /** Called when the route line changes (a new plan, an edit, none) – the flyover stops then. */
  readonly lineListeners: (() => void)[] = [];
  /** The cursor at [m] metres from outside (the flyover): the dot on the map and the line in both profiles; null = none. */
  showAt(m: number | null) { this.cursor(m); }

  /** A new plan (or none): the sheet's profile, and the big one when open. Same profile object = nothing to redraw. */
  update(plan: PlanResult | null, noElevation: string) {
    const p = plan?.profile ?? null;
    if (p === this.profile && plan) return;
    this.profile = p;
    this.line = plan && p ? lineOf(plan.legs) : { pts: [], cum: [] };
    this.lineListeners.forEach((f) => f());
    this.cursor(null);
    this.mark(null);
    if (!plan) { this.sheetHost.innerHTML = ''; this.small = null; this.setOpen(false); return; }
    if (!p || p.dist.length < 2) {
      this.sheetHost.innerHTML = `<div class="muted small noele">${escapeHtml(noElevation)}</div>`;
      this.small = null; this.setOpen(false); return;
    }
    // a labelled button in the row above the drawing, beside the heights – never on top of the graph (owner review
    // 2026-11-05); the ⤢ alone was not seen (owner 2026-11-06): „⤢ Großes Profil“, and a click on the small profile opens it too
    const big = `<button class="profbig" id="profileBig" title="${escapeHtml(t('web_profile_big'))}" aria-pressed="${this.open}"><svg class="ic" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="M21 11V3h-8l3.29 3.29-10 10L3 13v8h8l-3.29-3.29 10-10z"/></svg><span></span></button>`;
    this.small = mountProfile(this.sheetHost, p, (m) => this.cursor(m, 'small'), undefined, big);
    this.sheetHost.querySelector<HTMLButtonElement>('#profileBig')!.onclick = () => this.setOpen(!this.open);
    const plot = this.sheetHost.querySelector<HTMLElement>('.profplot')!;
    plot.classList.add('opens'); plot.title = t('web_profile_big_title');
    plot.addEventListener('click', () => { if (!this.open) this.setOpen(true); });
    this.bigButton();
    if (this.open) this.drawBig();
    else if (this.pinnedOn) this.setOpen(true, true);
  }

  /** Pinned (wide) or not (phone): pinned opens the big profile whenever a plan has heights. */
  set pinned(on: boolean) {
    if (on === this.pinnedOn) return;
    this.pinnedOn = on;
    this.panel.classList.toggle('pinned', on);
    if (on) this.setOpen(true, true); else this.setOpen(false, true);
  }
  get pinned(): boolean { return this.pinnedOn; }

  setOpen(open: boolean, auto = false) {
    if (open && !this.profile) open = false;
    if (!open && this.pinnedOn && this.profile) return; // pinned: stays while there are heights
    if (open === this.open && (!open || this.big)) return;
    this.open = open;
    this.panel.hidden = !open;
    this.bigButton();
    if (open) this.drawBig(); else { this.mark(null); this.big = null; this.panel.querySelector('.bpplot')!.innerHTML = ''; }
    this.steepLine();
    this.onOpenChange(open, auto);
  }

  /** The sheet's button: „⤢ Großes Profil“, while open „Profil schließen“. */
  private bigButton() {
    const b = document.getElementById('profileBig');
    if (!b) return;
    b.setAttribute('aria-pressed', String(this.open));
    // the phone's full row says what it is (owner 2026-11-07): „⤢ Höhenprofil groß“
    const row = matchMedia('(max-width: 700px)').matches;
    b.querySelector('span')!.textContent = t(this.open ? 'web_profile_big_close' : row ? 'web_profile_big_row' : 'web_profile_big_open');
    b.title = this.open ? t('web_profile_big_close') : t('web_profile_big');
  }

  private drawBig() {
    if (!this.profile) return;
    this.big = mountProfile(this.panel.querySelector<HTMLElement>('.bpplot')!, this.profile, (m) => this.cursor(m, 'big'), { W: 1000, H: 150 }, '',
      (r) => this.markSoon(r));
    this.panel.querySelector<HTMLElement>('.bpplot .profplot')!.title = t('web_profile_range_hint');
    this.big.range(this.marked);
    this.steepLine();
  }

  /** A range from the big profile (already shaded there): at most every 40 ms while dragging – the first at once, the
   * newest when the time is up (a timer, not a frame: an idle map draws no frames, and the drag's end must still land). */
  private markSoon(r: [number, number] | null) {
    if (this.rangeTimer) { this.pendingRange = r; return; }
    this.mark(r, true);
    const tick = () => {
      const next = this.pendingRange;
      this.pendingRange = undefined;
      if (next === undefined) { this.rangeTimer = 0; return; }
      this.mark(next, true);
      this.rangeTimer = window.setTimeout(tick, 40);
    };
    this.rangeTimer = window.setTimeout(tick, 40);
  }

  /** Mark [r] (from the big profile: [fromProfile], already shaded there) or clear it: the map's highlighter, the numbers. */
  private mark(r: [number, number] | null, fromProfile = false) {
    if (!fromProfile && this.rangeTimer) { clearTimeout(this.rangeTimer); this.rangeTimer = 0; this.pendingRange = undefined; }
    this.marked = r;
    if (!fromProfile) this.big?.range(r);
    (this.map.getSource(RANGE) as GeoJSONSource | undefined)?.setData(r ? { type: 'Feature', properties: {},
      geometry: { type: 'LineString', coordinates: lineBetween(this.line, r[0], r[1]) } } : empty());
    const lbl = this.panel.querySelector<HTMLElement>('.bprange');
    const ask = ++this.rangeAsk;
    if (!lbl) return;
    if (!r || !this.rangeStats) { lbl.hidden = true; lbl.textContent = ''; return; }
    this.rangeStats(r[0], r[1]).then((st) => {
      if (ask !== this.rangeAsk) return; // a newer range or none meanwhile
      lbl.hidden = !st; lbl.textContent = st?.text ?? '';
    }, (e) => problem('plan', 'range numbers not computed', e));
    (window as unknown as { __profileRange: unknown }).__profileRange = r; // for the browser tests
  }

  /** The route coloured by steepness under the big profile (the app's gradient profile colours), else hidden. */
  private steepLine() {
    const src = this.map.getSource(STEEP) as GeoJSONSource | undefined;
    if (!src || !this.map.getLayer(STEEP)) return;
    // pinned (wide): the route stays plan blue – the steepness is read in the profile below the map (owner 2026-11-07)
    const on = this.open && this.profile != null && !this.pinnedOn;
    try {
      src.setData(on ? { type: 'FeatureCollection', features: steepRuns(this.line, this.profile!).map((r) => ({ type: 'Feature',
        properties: { colour: STEEP_COLOURS[r.cls] ?? STEEP_COLOURS[0] },
        geometry: { type: 'LineString', coordinates: r.coords } })) } : empty());
      this.map.setLayoutProperty(STEEP, 'visibility', on ? 'visible' : 'none');
      this.map.setLayoutProperty(STEEP + '-edge', 'visibility', on ? 'visible' : 'none');
    } catch (e) { problem('map', 'steepness line not drawn', e); }
  }

  /** The cursor at [m] metres: the dot on the map and the line in the profiles other than [from]. */
  private cursor(m: number | null, from?: 'small' | 'big' | 'map') {
    const at = m == null ? null : pointAt(this.line, m);
    if (this.dot) {
      if (at) this.dot.setLngLat([at[1], at[0]]);
      this.dot.getElement().hidden = !at;
    }
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
  /** Called after 3D was switched on or off (the flyover: its button, stop when 3D goes). */
  readonly listeners: (() => void)[] = [];
  /** [frame]: the flat camera that shows the whole route clear of the sheet (main.ts cameraForBounds), null = no route. */
  constructor(private map: maplibregl.Map, private button: HTMLButtonElement, private note: HTMLElement, private onChange: () => void,
    private frame: () => { center: maplibregl.LngLat; zoom: number; padding: maplibregl.PaddingOptions } | null = () => null) {
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
        // walls – so 3D keeps the camera close (zoom ≥ 11); the sky and its fog hide the far edge (tilt ≤ 70°)
        m.setMaxPitch(70);
        m.setMinZoom(MIN_ZOOM_3D);
        m.setSourceTileLodParams(1.5, 6, 'terrain'); // heights near the camera's zoom also further away (not the flat z<10)
        m.setTerrain({ source: 'terrain', exaggeration: 1.5 });
        m.setSky({ 'sky-color': '#9fc6e8', 'horizon-color': '#e6eff5', 'fog-color': '#f2f4f0', 'sky-horizon-blend': 0.6,
          'horizon-fog-blend': 0.6, 'fog-ground-blend': 0.75, 'atmosphere-blend': 0 });
        this.on = true;
        // clearly in perspective (owner review 2026-11-05: "barely tilted"): 62°, framed on the route when there is one –
        // looking at it from the south-east a little, so the slopes face the viewer; tilting enlarges the near part, so a
        // bit further out than the flat fit; the route's middle in the middle of the free room (padding = sheet, profile)
        this.frameRoute(900);
      } else {
        this.on = false;
        m.setTerrain(null);
        m.setSky({});
        m.jumpTo({ pitch: 0, padding: { top: 0, bottom: 0, left: 0, right: 0 } }); // the 3D frame's room for the sheet goes too
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
    this.listeners.forEach((f) => f());
  }

  /** The 3D camera on the route (or where the map is without one): 62°, from the south-east a little. */
  frameRoute(duration: number) {
    const m = this.map;
    const f = this.frame();
    const zoom = f ? f.zoom - 0.6 : Math.max(m.getZoom(), 12.5);
    m.easeTo({ center: f?.center ?? m.getCenter(), zoom: Math.max(MIN_ZOOM_3D, zoom), pitch: 62,
      bearing: f ? -20 : m.getBearing(), padding: f?.padding ?? m.getPadding(), duration });
  }
}

function empty(): FeatureCollection { return { type: 'FeatureCollection', features: [] }; }
function escapeHtml(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!)); }
