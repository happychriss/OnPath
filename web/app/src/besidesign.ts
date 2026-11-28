// Form B (owner 2026-11-19: „ich kann weitermachen“ – „B: einfach nur Prozent, muss aber passen für die gesamte Aktion“):
// the app's BesideSign / BesideDetails (ui/progress/BesideSign.kt, mirrored – web/parity.json). One small sign beside the
// map that covers nothing – a ring over the whole action – no number since 2026-11-28 (facts, not guesses: the details count the areas) (every map area the server prepares in the
// background together: the engine's WebSign on the app's ActionPercent), no names, no words. Tap → the details: the
// title, the ONE bar, what loads (one line each), Cancel all (red) · Close. In the planner the background loads are the
// map areas along a computed route and around the own position (areas.ts); the routing data fetched around the map view
// ahead of a first plan stays silent (nobody waits for it, as before).
import { t } from './i18n';
import { signTick, signSeed } from './waitengine';

/** Areas ready of all in the background action – counted from the lines ("id<TAB>state<TAB>…"). */
function counted(lines: string): { ready: number; total: number } {
  const ls = lines.split('\n').filter(Boolean);
  return { ready: ls.filter((l) => l.split('\t')[1] === 'ready').length, total: ls.length };
}

export interface BesideAreas {
  /** "id<TAB>state<TAB>progress<TAB>eta" per area of the background action. */
  lines: string;
  /** The details' rows: "Map area 2 · being prepared – 40 %". */
  rows: string[];
  /** Cancel all: the page stops waiting for them (the map still appears when it is there). */
  cancel: () => void;
}

export class BesideSign {
  private el: HTMLButtonElement;
  private details: HTMLElement | null = null;
  private now: BesideAreas | null = null;
  private timer = 0;
  private percent = -1;
  private ready = 0;
  private total = 0;

  constructor(host: HTMLElement) {
    const b = document.createElement('button');
    b.type = 'button';
    b.id = 'besidesign';
    b.className = 'besidesign';
    b.hidden = true;
    b.innerHTML = '<svg viewBox="0 0 20 20" aria-hidden="true"><circle class="track" cx="10" cy="10" r="8"/><circle class="arc" cx="10" cy="10" r="8"/></svg><span class="pct"></span>';
    b.onclick = () => this.open();
    host.append(b);
    this.el = b;
  }

  /** The window (form A) closed while these loads go on: the sign goes on from [percent], never below. */
  seed(percent: number) { signSeed(percent); }

  /** What loads in the background now (null = nothing: the sign goes). */
  set(a: BesideAreas | null) {
    this.now = a;
    if (a && !this.timer) this.timer = window.setInterval(() => this.tick(), 1000);
    this.tick();
  }

  /** The areas ready (−1 = no sign) – for the tests. */
  get shown() { return this.el.hidden ? -1 : this.ready; }

  private tick() {
    const a = this.now;
    const p = a ? signTick(performance.now(), a.lines) : signTick(performance.now(), '');
    if (!a || p === -1) {
      clearInterval(this.timer); this.timer = 0;
      this.el.hidden = true; this.percent = -1; this.close();
      return;
    }
    // facts, not guesses (captain 2026-11-28, VISION): no number on the sign – the ring fills by the areas that are
    // ready of all that load (counted), the details say „x von y Kartenbereichen“
    const c = counted(a.lines);
    this.ready = c.ready; this.total = c.total;
    this.percent = Math.max(this.percent, c.total ? Math.round((100 * c.ready) / c.total) : 0);
    this.el.hidden = false;
    this.place();
    this.el.querySelector('.pct')!.textContent = '';
    this.el.classList.toggle('spin', c.ready === 0);
    this.el.style.setProperty('--p', String(this.percent / 100));
    this.el.setAttribute('aria-label', t('maps_bg_chip_cd', t('web_bg_counted', c.ready, c.total)));
    this.el.title = t('maps_bg_chip_click');
    if (this.details) this.fill(this.details);
  }

  /** Under the top bar at the right (the app: under the ☰ button), and under the map strip where it reaches that far. */
  private place() {
    const host = this.el.parentElement?.getBoundingClientRect();
    if (!host) return;
    const bar = document.getElementById('topbar')?.getBoundingClientRect();
    const sign = this.el.getBoundingClientRect();
    let top = (bar?.bottom ?? host.top) + 8;
    const notes = document.getElementById('mapnotes');
    if (notes && !notes.hidden) {
      const r = notes.getBoundingClientRect();
      if (r.height > 0 && r.right > host.right - 12 - Math.max(sign.width, 90) && r.bottom + 8 > top) top = r.bottom + 8;
    }
    this.el.parentElement!.style.setProperty('--beside-top', `${Math.round(top - host.top)}px`);
  }

  private open() {
    if (this.details) { this.close(); return; }
    const d = document.createElement('div');
    d.className = 'besidedetails';
    d.setAttribute('role', 'dialog');
    d.innerHTML = '<div class="bdtitle"></div><div class="bdprog"><div class="bdbar"><i></i></div><span class="pct"></span></div>'
      + '<ul class="bdrows"></ul><div class="bdbuttons"><button type="button" class="bdcancel"></button><button type="button" class="bdclose"></button></div>';
    d.querySelector<HTMLButtonElement>('.bdcancel')!.onclick = () => { const a = this.now; this.close(); a?.cancel(); this.set(null); };
    d.querySelector<HTMLButtonElement>('.bdclose')!.onclick = () => this.close();
    this.el.after(d);
    this.details = d;
    this.fill(d);
  }

  private fill(d: HTMLElement) {
    d.querySelector('.bdtitle')!.textContent = t('maps_bg_dialog_title');
    d.querySelector<HTMLElement>('.bdbar i')!.style.width = `${Math.max(0, this.percent)}%`;
    d.querySelector('.bdprog .pct')!.textContent = t('web_bg_counted', this.ready, this.total);
    const ul = d.querySelector('.bdrows')!;
    // only counted values here (captain 2026-11-28): a row's server percent goes, its state and time stay
    ul.replaceChildren(...(this.now?.rows ?? []).map((r) => Object.assign(document.createElement('li'), { textContent: r.replace(/ – \d{1,3} ?%/g, '') })));
    d.querySelector('.bdcancel')!.textContent = t('common_cancel_all');
    d.querySelector('.bdclose')!.textContent = t('common_close');
  }

  private close() { this.details?.remove(); this.details = null; }
}
