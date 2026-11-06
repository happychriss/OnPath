// The flyover (owner 2026-11-06, variant D of docs/design/web-planner.md §19): in 3D the camera flies along the route.
// Time-based (a slow frame moves further, never slower), ~40 s for 10 km at 1×; the camera looks ahead along the route
// and its direction is eased, so bends do not shake the picture; it climbs (zooms out a little) where the terrain behind
// the centre would reach it. The profiles' cursor moves along. A drag / zoom / turn by the user pauses; leaving 3D or a
// new route stops; Stop frames the route in 3D again. No new data – the 3D terrain of C.
import * as maplibregl from 'maplibre-gl';
import { pointAt, type Line } from './profile';
import type { RouteProfile, Terrain3D } from './terrainview';
import { t } from './i18n';
import { problem } from './diag';

type State = 'idle' | 'flying' | 'paused';
const PITCH = 55, ZOOM = 14.2, MIN_ZOOM = 11.5;

export class FlyOver {
  private state: State = 'idle';
  private d = 0; // metres along the route
  private speed = 1; // 1× or 2×
  private bearing = 0;
  private zoom = ZOOM;
  private last = 0; // the previous frame's time
  private raf = 0;
  private own = false; // the flyover's own camera step is running
  /** The last frames' time and direction – the browser tests check that the turns are calm. */
  readonly trace: { t: number; b: number }[] = [];
  private bar: HTMLDivElement;

  constructor(private map: maplibregl.Map, private terrain: Terrain3D, private profile: RouteProfile, host: HTMLElement) {
    this.bar = document.createElement('div');
    this.bar.id = 'flyBar';
    this.bar.className = 'flybar';
    this.bar.hidden = true;
    host.append(this.bar);
    terrain.listeners.push(() => { if (!terrain.on) this.stop(false); this.render(); });
    profile.lineListeners.push(() => { if (this.state !== 'idle') this.stop(false); this.render(); });
    // anyone else moves the camera (a drag, the wheel, the compass, fit route): pause – the camera is theirs now
    map.on('movestart', () => { if (this.state === 'flying' && !this.own) this.pause(); });
    (window as unknown as { __fly: unknown }).__fly = this; // for the browser tests
    this.render();
  }

  get status() { return { state: this.state, d: this.d, total: this.total, speed: this.speed }; }
  private get line(): Line { return this.profile.routeLine; }
  private get total() { const c = this.line.cum; return c.length ? c[c.length - 1] : 0; }

  /** Seconds for the whole route at 1×: ~40 s for 10 km, at least 20 s, at most 3 min. */
  private get seconds() { return Math.min(180, Math.max(20, this.total / 10_000 * 40)); }
  /** How far the camera looks ahead: a short route less (its bends are near), 300–800 m. */
  private get ahead() { return Math.min(800, Math.max(Math.min(300, this.total * 0.2), this.total * 0.05)); }

  start() {
    if (!this.terrain.on || this.total < 50) return;
    if (this.state === 'idle') { this.d = 0; this.zoom = ZOOM; this.bearing = this.directionAt(0); }
    this.state = 'flying';
    this.last = performance.now();
    this.raf = requestAnimationFrame((now) => this.frame(now));
    this.render();
  }

  pause() {
    if (this.state !== 'flying') return;
    this.state = 'paused';
    cancelAnimationFrame(this.raf);
    this.render();
  }

  /** Stop: the cursor goes; [frame] = back to the route in 3D (not when 3D itself went or the route changed). */
  stop(frame = true) {
    if (this.state === 'idle') return;
    this.state = 'idle';
    cancelAnimationFrame(this.raf);
    this.profile.showAt(null);
    if (frame && this.terrain.on) this.terrain.frameRoute(900);
    this.render();
  }

  private frame(now: number) {
    if (this.state !== 'flying') return;
    const dt = Math.min(1, (now - this.last) / 1000); // slow frames move further (time-based); a hidden tab does not jump
    this.last = now;
    this.d = Math.min(this.total, this.d + this.total / this.seconds * this.speed * dt);
    try {
      const want = this.directionAt(this.d);
      const turn = ((want - this.bearing + 540) % 360) - 180;
      this.bearing += turn * (1 - Math.exp(-dt / 0.9)); // eased: about a second to follow a bend
      this.zoom += (this.clearZoom() - this.zoom) * (1 - Math.exp(-dt / 0.6));
      const c = pointAt(this.line, Math.min(this.total, this.d + this.ahead * 0.25))!;
      this.own = true;
      try { this.map.jumpTo({ center: [c[1], c[0]], bearing: this.bearing, pitch: PITCH, zoom: this.zoom }); } finally { this.own = false; }
      this.trace.push({ t: now, b: this.bearing }); if (this.trace.length > 200) this.trace.shift();
      this.profile.showAt(this.d);
    } catch (e) {
      problem('map', 'flyover frame failed', e);
      this.stop(false);
      return;
    }
    if (this.d >= this.total) { this.state = 'idle'; this.render(); return; } // the end: the camera stays there
    this.raf = requestAnimationFrame((n) => this.frame(n));
  }

  /** The direction of the route around [m]: from a bit behind to [ahead] in front – one bend does not swing it. */
  private directionAt(m: number): number {
    const a = pointAt(this.line, Math.max(0, m - this.ahead * 0.3)), b = pointAt(this.line, Math.min(this.total, m + this.ahead));
    if (!a || !b) return this.bearing;
    const la1 = a[0] * Math.PI / 180, la2 = b[0] * Math.PI / 180, dl = (b[1] - a[1]) * Math.PI / 180;
    const y = Math.sin(dl) * Math.cos(la2), x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl);
    return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
  }

  /** The zoom at which the camera stays well above the terrain behind the centre (a slope behind would cut the view).
   * The camera sits behind the centre along the view: its distance is MapLibre's (half the canvas height over tan of half
   * the 36.87° field of view, in pixels) at this zoom's metres per pixel; height = cos(pitch) of it over the centre's ground. */
  private clearZoom(): number {
    const m = this.map;
    try {
      const c = m.getCenter(), h = m.getCanvas().clientHeight || 600;
      const mpp = 40_075_016.686 * Math.cos(c.lat * Math.PI / 180) / (512 * 2 ** m.getZoom());
      const dist = 0.5 / Math.tan(36.87 / 2 * Math.PI / 180) * h * mpp, p = m.getPitch() * Math.PI / 180;
      const camAlt = (m.queryTerrainElevation(c) ?? 0) + Math.cos(p) * dist;
      const back = Math.sin(p) * dist, b = (m.getBearing() + 180) * Math.PI / 180;
      const at = (f: number) => { // a point [f] of the way from the centre to under the camera
        const dLat = back * f * Math.cos(b) / 111_320, dLon = back * f * Math.sin(b) / (111_320 * Math.cos(c.lat * Math.PI / 180));
        return new maplibregl.LngLat(c.lng + dLon, c.lat + dLat);
      };
      const ground = Math.max(m.queryTerrainElevation(at(1)) ?? 0, m.queryTerrainElevation(at(0.6)) ?? 0, m.queryTerrainElevation(at(0.3)) ?? 0);
      const room = camAlt - ground;
      if (room < 250) return Math.max(MIN_ZOOM, this.zoom - 0.6); // too close: climb
      if (room > 600) return ZOOM; // plenty: back to the normal height
      return this.zoom;
    } catch (e) {
      problem('map', 'flyover height not checked', e);
      return ZOOM;
    }
  }

  private render() {
    const b = this.bar;
    b.hidden = !this.terrain.on;
    if (b.hidden) return;
    const can = this.total >= 50;
    const btn = (id: string, label: string, title: string, cls = '') =>
      `<button id="${id}" class="flybtn ${cls}" title="${esc(title)}" aria-label="${esc(title)}">${esc(label)}</button>`;
    if (this.state === 'idle') {
      b.innerHTML = btn('flyStart', '▶ ' + t('web_fly_start'), can ? t('web_fly_title') : t('web_fly_needs_route'), 'main');
      b.querySelector<HTMLButtonElement>('#flyStart')!.disabled = !can;
    } else {
      b.innerHTML = (this.state === 'flying' ? btn('flyPause', '❚❚ ' + t('web_fly_pause'), t('web_fly_pause'))
        : btn('flyResume', '▶ ' + t('web_fly_resume'), t('web_fly_resume'), 'main'))
        + btn('flyStop', '■ ' + t('web_fly_stop'), t('web_fly_stop'))
        + btn('flySpeed', this.speed === 1 ? '1×' : '2×', t('web_fly_speed'), 'speed');
    }
    b.querySelector('#flyStart')?.addEventListener('click', () => this.start());
    b.querySelector('#flyPause')?.addEventListener('click', () => this.pause());
    b.querySelector('#flyResume')?.addEventListener('click', () => this.start());
    b.querySelector('#flyStop')?.addEventListener('click', () => this.stop());
    b.querySelector('#flySpeed')?.addEventListener('click', () => { this.speed = this.speed === 1 ? 2 : 1; this.render(); });
  }
}

function esc(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!)); }
