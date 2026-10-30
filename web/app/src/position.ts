// My position for the locate control and "Go here" (the app's LocateControl + GPS dot). The browser asks for
// permission on the first use; nothing is sent anywhere.
import * as maplibregl from 'maplibre-gl';
import { t } from './i18n';

/** The app's GpsStatus names (recording/LocationTracker.kt), plus UNKNOWN = not asked yet. */
export type GpsState = 'UNKNOWN' | 'NO_PERMISSION' | 'OFF' | 'SEARCHING' | 'WEAK' | 'GOOD';

export class Position {
  status: GpsState = 'geolocation' in navigator ? 'UNKNOWN' : 'OFF';
  fix: [number, number] | null = null;
  following = false;
  private watch: number | null = null;
  private dot: maplibregl.Marker | null = null;
  private waiters: ((p: [number, number] | null) => void)[] = [];

  constructor(private map: maplibregl.Map, private onChange: () => void) {
    map.on('dragstart', () => { if (this.following) { this.following = false; onChange(); } });
  }

  /** CSS class of the small dot on the locate button (good / weak / searching / bad, like the app's gpsColor). */
  get dotClass(): string {
    return this.status === 'GOOD' ? 'good' : this.status === 'WEAK' ? 'weak'
      : this.status === 'SEARCHING' || this.status === 'UNKNOWN' ? 'grey' : 'bad';
  }

  /** Locate button: centre on my position and follow it until the map is moved by hand. */
  locate() {
    this.following = true;
    this.start();
    if (this.fix) this.map.easeTo({ center: [this.fix[1], this.fix[0]], zoom: Math.max(this.map.getZoom(), 15) });
    this.onChange();
  }

  /** The position once (for "Go here"): the last fix, or the next one within 20 s; null when there is none. */
  once(): Promise<[number, number] | null> {
    if (this.fix) return Promise.resolve(this.fix);
    this.start();
    if (this.status === 'OFF' || this.status === 'NO_PERMISSION') return Promise.resolve(null);
    return new Promise((resolve) => {
      this.waiters.push(resolve);
      setTimeout(() => { const i = this.waiters.indexOf(resolve); if (i >= 0) { this.waiters.splice(i, 1); resolve(null); } }, 20_000);
    });
  }

  private start() {
    if (this.watch != null || !('geolocation' in navigator)) return;
    this.status = 'SEARCHING';
    this.watch = navigator.geolocation.watchPosition((p) => {
      this.fix = [p.coords.latitude, p.coords.longitude];
      this.status = p.coords.accuracy <= 50 ? 'GOOD' : 'WEAK';
      this.draw();
      if (this.following) this.map.easeTo({ center: [this.fix[1], this.fix[0]], zoom: Math.max(this.map.getZoom(), 15) });
      this.waiters.splice(0).forEach((w) => w(this.fix));
      this.onChange();
    }, (err) => {
      if (err.code === err.PERMISSION_DENIED) {
        this.status = 'NO_PERMISSION';
        // the next click on Locate asks again (after the user allowed it in the browser)
        if (this.watch != null) navigator.geolocation.clearWatch(this.watch);
        this.watch = null;
      }
      else if (err.code === err.POSITION_UNAVAILABLE) this.status = 'OFF';
      if (this.status === 'NO_PERMISSION' || this.status === 'OFF') {
        this.following = false;
        this.waiters.splice(0).forEach((w) => w(null));
      }
      this.onChange();
    }, { enableHighAccuracy: true, maximumAge: 10_000 });
  }

  private draw() {
    if (!this.fix) return;
    if (!this.dot) {
      const el = document.createElement('div');
      el.className = 'mypos';
      el.title = t('maps_row_your_position');
      this.dot = new maplibregl.Marker({ element: el }).setLngLat([this.fix[1], this.fix[0]]).addTo(this.map);
    } else this.dot.setLngLat([this.fix[1], this.fix[0]]);
  }
}
