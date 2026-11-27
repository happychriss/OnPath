// The activity picker (the app's ActivityPicker dialog): the six activities with the line under each, and for Hike the
// SAC level T1–T5 with its short description. Names come from the app's Activity enum (engine call "activities");
// the SAC texts are the app's (routes_sac_<n>_name / _text, ui/Screens.SacScale) in the page's language.
import type { Activity } from './engine/types';
import { activityIcon } from './icons';
import { t } from './i18n';

export interface ActivityRow { key: Activity; label: string; line: string; chip: string }

/** The drop-down's pace section (engine call paceChoices – the app's Settings → Pace, ui/PaceSettings.kt PaceSection). */
export interface PaceChoices {
  title: string; group: 'hike' | 'bike'; groupLabel: string; intro: string; selected: string;
  levels: { key: string; label: string; meaning: string }[];
}

/**
 * „Markierten Wegen folgen“ (the app's Settings → Planen, core MarkedTrails, owner 2026-11-27): the level 0 Weniger ·
 * 1 Standard · 2 Mehr and where a pick goes (main.ts: engine + localStorage, the plan routes again).
 */
export interface TrailsHost {
  level(): number;
  pick(level: number): Promise<void>;
}

/** Where the pace section gets its rows and where a pick goes (main.ts: engine + localStorage). */
export interface PaceHost {
  choices(a: Activity): Promise<PaceChoices>;
  pick(group: 'hike' | 'bike', level: string): Promise<void>;
}

const SAC_KEYS = [
  ['routes_sac_1_name', 'routes_sac_1_text', 'routes_sac_1_short'], ['routes_sac_2_name', 'routes_sac_2_text', 'routes_sac_2_short'],
  ['routes_sac_3_name', 'routes_sac_3_text', 'routes_sac_3_short'], ['routes_sac_4_name', 'routes_sac_4_text', 'routes_sac_4_short'],
  ['routes_sac_5_name', 'routes_sac_5_text', 'routes_sac_5_short'],
];
const SAC = SAC_KEYS.map(([n, d, s]) => [t(n), t(d), t(s)]);

/**
 * A drop-down anchored to the activity chip (owner 2026-10-28: no dialog, no Done): a click on an activity picks it and
 * closes; Hike carries its T1–T5 row in the same list – a T level picks Hike with that level and closes.
 */
export class ActivityPicker {
  private rows: ActivityRow[] = [];
  private box: HTMLDivElement;
  private act: Activity = 'HIKE';
  private sac = 2;
  /** A T chip was tapped since the list opened: it stays open and offers „Fertig“. */
  private levelPicked = false;
  private anchor: HTMLElement | null = null;

  private pace: PaceChoices | null = null;

  constructor(private onPick: (a: Activity, sac: number) => void, private paceHost?: PaceHost, private trailsHost?: TrailsHost) {
    this.box = document.createElement('div');
    this.box.className = 'actmenu';
    this.box.id = 'activityMenu';
    this.box.setAttribute('role', 'menu');
    this.box.hidden = true;
    this.box.addEventListener('click', (e) => {
      e.stopPropagation();
      const t = e.target as HTMLElement;
      const pace = t.closest<HTMLElement>('[data-pace]')?.dataset.pace;
      if (pace && this.pace && this.paceHost) {
        // a setting, not a pick: the list stays open, the time in the sheet follows (main.ts)
        const group = this.pace.group;
        this.pace.selected = pace;
        this.drawPace();
        this.box.querySelector<HTMLElement>(`[data-pace="${pace}"]`)?.focus();
        this.paceHost.pick(group, pace).catch((err) => console.warn('pace not set', err));
        return;
      }
      const trails = t.closest<HTMLElement>('[data-trails]')?.dataset.trails;
      if (trails != null && this.trailsHost) {
        // a setting, not a pick: the list stays open, the plan routes again (main.ts)
        const l = Number(trails);
        this.drawTrails(l);
        this.box.querySelector<HTMLElement>(`[data-trails="${l}"]`)?.focus();
        this.trailsHost.pick(l).catch((err) => console.warn('marked trails not set', err));
        return;
      }
      const s = t.closest<HTMLElement>('[data-sac]')?.dataset.sac;
      const a = t.closest<HTMLElement>('[data-act]')?.dataset.act as Activity | undefined;
      if (t.closest('#actDone')) { this.close(); this.anchor?.focus(); return; }
      if (s) this.pickLevel(Number(s));
      else if (a) this.pick(a, this.sac);
    });
    this.box.addEventListener('keydown', (e) => { if (e.key === 'Escape') { this.close(); this.anchor?.focus(); } });
    document.addEventListener('click', (e) => {
      if (!this.box.hidden && !this.box.contains(e.target as Node) && !this.anchor?.contains(e.target as Node)) this.close();
    });
  }

  setRows(rows: ActivityRow[]) { this.rows = rows; }
  row(a: Activity) { return this.rows.find((r) => r.key === a); }
  /** The six with their names (Meine Touren's activity filter and activity line). */
  allRows(): ActivityRow[] { return this.rows; }
  get isOpen() { return !this.box.hidden; }

  /** Opens under [anchor] (the chip); a second click on the chip closes it. */
  toggle(anchor: HTMLElement, a: Activity, sac: number) {
    if (this.isOpen) { this.close(); return; }
    this.anchor = anchor;
    this.act = a; this.sac = sac; this.levelPicked = false;
    this.draw();
    this.loadPace(a);
    document.body.append(this.box);
    this.box.hidden = false;
    this.place();
    anchor.setAttribute('aria-expanded', 'true');
    this.box.querySelector<HTMLElement>('[aria-checked="true"]')?.focus();
  }

  /**
   * Under the chip; on a phone (sheet at the bottom) above it when there is more room there. The list never covers the
   * chip: when it is taller than the room on its side (the pace section arrives a moment later and makes it taller –
   * it pushed the list up over the chip on an 800 px window), it scrolls inside instead of moving.
   */
  private place() {
    const anchor = this.anchor;
    if (!anchor) return;
    const r = anchor.getBoundingClientRect(), gap = 6, edge = 8;
    this.box.style.maxHeight = '';
    const h = this.box.scrollHeight;
    const roomBelow = innerHeight - r.bottom - gap - edge, roomAbove = r.top - gap - edge;
    const below = h <= roomBelow || roomBelow >= roomAbove;
    const room = Math.max(0, below ? roomBelow : roomAbove);
    this.box.style.maxHeight = room + 'px';
    this.box.style.left = Math.max(edge, Math.min(r.left, innerWidth - this.box.offsetWidth - edge)) + 'px';
    this.box.style.top = (below ? r.bottom + gap : r.top - gap - Math.min(h, room)) + 'px';
  }

  close() {
    this.box.hidden = true;
    this.anchor?.setAttribute('aria-expanded', 'false');
  }

  private pick(a: Activity, sac: number) { this.close(); this.onPick(a, sac); }

  /**
   * A T chip (app 2a54f2e4, owner 2026-11-08, father's feedback): Hike with that level at once (the plan routes again),
   * but the list stays open so the level's line „T2 · Bergwandern: …“ can be read; the way out says „Fertig“.
   */
  private pickLevel(level: number) {
    const was = this.act;
    this.act = 'HIKE'; this.sac = level; this.levelPicked = true;
    this.onPick('HIKE', level);
    const pace = this.pace;
    this.draw();
    if (was === 'HIKE' && pace) { this.pace = pace; this.drawPace(); } else this.loadPace('HIKE');
    this.place(); // the line and „Fertig“ make the list taller
    this.box.querySelector<HTMLElement>(`[data-sac="${level}"]`)?.focus();
  }

  /** The pace section of the current activity's group (Hiking, or Cycling · <bike>), from the engine. */
  private loadPace(a: Activity) {
    if (!this.paceHost) return;
    this.paceHost.choices(a).then((c) => {
      if (this.act !== a || this.box.hidden) return;
      this.pace = c;
      this.drawPace();
      this.place();
    }).catch((err) => console.warn('pace choices not loaded', err));
  }

  /** One row of three under the pace (the app's Settings → Planen, same words): „Wanderrouten folgen“ on foot,
   *  „Radrouten folgen“ on a bike (the app's Planen row title, owner 2026-11-27). */
  private drawTrails(level = this.trailsHost?.level() ?? 1) {
    if (!this.trailsHost) return;
    let sec = this.box.querySelector<HTMLDivElement>('#trailsRow');
    if (!sec) { sec = document.createElement('div'); sec.id = 'trailsRow'; sec.className = 'pacerow'; this.box.insertBefore(sec, this.box.querySelector('.actdone')); }
    const words = ['settings_trails_less', 'settings_trails_standard', 'settings_trails_more'].map((k) => t(k));
    const title = t(this.act === 'HIKE' ? 'settings_trails_hike' : 'settings_trails_bike');
    sec.innerHTML = `<div class="pacehead"><b>${esc(title)}</b></div>
      <div class="seg" role="group" aria-label="${esc(title)}">${words.map((w, i) =>
        `<button data-trails="${i}" role="menuitemradio" aria-checked="${i === level}" class="${i === level ? 'on' : ''}">${esc(w)}</button>`).join('')}</div>`;
  }

  private drawPace() {
    let sec = this.box.querySelector<HTMLDivElement>('#paceRow');
    if (!this.pace) { sec?.remove(); return; }
    if (!sec) { sec = document.createElement('div'); sec.id = 'paceRow'; sec.className = 'pacerow'; this.box.insertBefore(sec, this.box.querySelector('#trailsRow') ?? this.box.querySelector('.actdone')); }
    const c = this.pace, on = c.levels.find((l) => l.key === c.selected) ?? c.levels[2];
    sec.innerHTML = `<div class="pacehead"><b>${esc(c.title)}</b> <span class="muted small">${esc(c.groupLabel)}</span></div>
      <div class="seg" role="group" aria-label="${esc(c.title + ' – ' + c.groupLabel)}">${c.levels.map((l) =>
        `<button data-pace="${l.key}" role="menuitemradio" aria-checked="${l.key === on.key}" class="${l.key === on.key ? 'on' : ''}" title="${esc(l.label + ' – ' + l.meaning)}">${esc(l.label)}</button>`).join('')}</div>
      <div class="muted small" id="paceMeaning">${esc(on.label)} · ${esc(on.meaning)}</div>`; // no intro (the app's PaceSection, owner 2026-11-27)
  }

  private draw() {
    this.pace = null;
    this.box.innerHTML = this.rows.map((r) => {
      const on = r.key === this.act;
      const row = `<button data-act="${r.key}" role="menuitemradio" aria-checked="${on}" class="${on ? 'on' : ''}">
          ${activityIcon(r.key)}<span class="txt"><b>${esc(r.label)}</b><span class="muted small">${esc(r.line)}</span></span></button>`;
      if (r.key !== 'HIKE') return row;
      // five rows „Bergwandern (T2)“ over one short line, read BEFORE the click (the app's SacChips, owner 2026-11-26 night)
      return row + `<div id="sacRow" class="sacrow" role="group" aria-label="${esc(t('routes_sac_title'))}">${[1, 2, 3, 4, 5].map((n) => {
            const [name, text, short] = SAC[n - 1];
            const sel = this.act === 'HIKE' && n === this.sac;
            return `<button data-sac="${n}" role="menuitemradio" aria-checked="${sel}" class="sacpick${sel ? ' on' : ''}" title="T${n} · ${esc(name)} – ${esc(text)}"><b>${esc(t('routes_sac_row', name, n))}</b><span class="muted small">${esc(short)}</span></button>`;
          }).join('')}</div>`;
    }).join('') + (this.levelPicked ? `<div class="actdone"><button id="actDone" class="textbtn">${esc(t('common_done'))}</button></div>` : '');
    this.drawTrails();
  }
}

function esc(t: string) { return t.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
