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

/** Where the pace section gets its rows and where a pick goes (main.ts: engine + localStorage). */
export interface PaceHost {
  choices(a: Activity): Promise<PaceChoices>;
  pick(group: 'hike' | 'bike', level: string): Promise<void>;
}

const SAC_KEYS = [
  ['routes_sac_1_name', 'routes_sac_1_text'], ['routes_sac_2_name', 'routes_sac_2_text'], ['routes_sac_3_name', 'routes_sac_3_text'],
  ['routes_sac_4_name', 'routes_sac_4_text'], ['routes_sac_5_name', 'routes_sac_5_text'],
];
const SAC = SAC_KEYS.map(([n, d]) => [t(n), t(d)]);

/**
 * A drop-down anchored to the activity chip (owner 2026-10-28: no dialog, no Done): a click on an activity picks it and
 * closes; Hike carries its T1–T5 row in the same list – a T level picks Hike with that level and closes.
 */
export class ActivityPicker {
  private rows: ActivityRow[] = [];
  private box: HTMLDivElement;
  private act: Activity = 'HIKE';
  private sac = 2;
  private anchor: HTMLElement | null = null;

  private pace: PaceChoices | null = null;

  constructor(private onPick: (a: Activity, sac: number) => void, private paceHost?: PaceHost) {
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
      const s = t.closest<HTMLElement>('[data-sac]')?.dataset.sac;
      const a = t.closest<HTMLElement>('[data-act]')?.dataset.act as Activity | undefined;
      if (s) this.pick('HIKE', Number(s));
      else if (a) this.pick(a, this.sac);
    });
    this.box.addEventListener('keydown', (e) => { if (e.key === 'Escape') { this.close(); this.anchor?.focus(); } });
    document.addEventListener('click', (e) => {
      if (!this.box.hidden && !this.box.contains(e.target as Node) && !this.anchor?.contains(e.target as Node)) this.close();
    });
  }

  setRows(rows: ActivityRow[]) { this.rows = rows; }
  row(a: Activity) { return this.rows.find((r) => r.key === a); }
  get isOpen() { return !this.box.hidden; }

  /** Opens under [anchor] (the chip); a second click on the chip closes it. */
  toggle(anchor: HTMLElement, a: Activity, sac: number) {
    if (this.isOpen) { this.close(); return; }
    this.anchor = anchor;
    this.act = a; this.sac = sac;
    this.draw();
    this.loadPace(a);
    document.body.append(this.box);
    this.box.hidden = false;
    // under the chip; on a phone (sheet at the bottom) above it when there is no room below
    const r = anchor.getBoundingClientRect(), h = this.box.offsetHeight;
    const below = innerHeight - r.bottom - 8 >= h || r.top < h + 8;
    this.box.style.left = Math.max(8, Math.min(r.left, innerWidth - this.box.offsetWidth - 8)) + 'px';
    this.box.style.top = below ? Math.min(r.bottom + 6, innerHeight - h - 8) + 'px' : Math.max(8, r.top - h - 6) + 'px';
    anchor.setAttribute('aria-expanded', 'true');
    this.box.querySelector<HTMLElement>('[aria-checked="true"]')?.focus();
  }

  close() {
    this.box.hidden = true;
    this.anchor?.setAttribute('aria-expanded', 'false');
  }

  private pick(a: Activity, sac: number) { this.close(); this.onPick(a, sac); }

  /** The pace section of the current activity's group (Hiking, or Cycling · <bike>), from the engine. */
  private loadPace(a: Activity) {
    if (!this.paceHost) return;
    this.paceHost.choices(a).then((c) => {
      if (this.act !== a || this.box.hidden) return;
      this.pace = c;
      this.drawPace();
    }).catch((err) => console.warn('pace choices not loaded', err));
  }

  private drawPace() {
    let sec = this.box.querySelector<HTMLDivElement>('#paceRow');
    if (!this.pace) { sec?.remove(); return; }
    if (!sec) { sec = document.createElement('div'); sec.id = 'paceRow'; sec.className = 'pacerow'; this.box.append(sec); }
    const c = this.pace, on = c.levels.find((l) => l.key === c.selected) ?? c.levels[2];
    sec.innerHTML = `<div class="pacehead"><b>${esc(c.title)}</b> <span class="muted small">${esc(c.groupLabel)}</span></div>
      <div class="seg" role="group" aria-label="${esc(c.title + ' – ' + c.groupLabel)}">${c.levels.map((l) =>
        `<button data-pace="${l.key}" role="menuitemradio" aria-checked="${l.key === on.key}" class="${l.key === on.key ? 'on' : ''}" title="${esc(l.label + ' – ' + l.meaning)}">${esc(l.label)}</button>`).join('')}</div>
      <div class="muted small" id="paceMeaning">${esc(on.label)} · ${esc(on.meaning)}</div>
      <div class="muted small">${esc(c.intro)}</div>`;
  }

  private draw() {
    this.pace = null;
    this.box.innerHTML = this.rows.map((r) => {
      const on = r.key === this.act;
      const row = `<button data-act="${r.key}" role="menuitemradio" aria-checked="${on}" class="${on ? 'on' : ''}">
          ${activityIcon(r.key)}<span class="txt"><b>${esc(r.label)}</b><span class="muted small">${esc(r.line)}</span></span></button>`;
      if (r.key !== 'HIKE') return row;
      const [name, text] = SAC[this.sac - 1];
      return row + `<div id="sacRow" class="sacrow">
          <div class="seg" role="group" aria-label="${esc(t('routes_sac_title'))}">${[1, 2, 3, 4, 5].map((n) => {
            const [name, text] = SAC[n - 1];
            const sel = this.act === 'HIKE' && n === this.sac;
            return `<button data-sac="${n}" role="menuitemradio" aria-checked="${sel}" class="${sel ? 'on' : ''}" title="T${n} · ${esc(name)} – ${esc(text)}">T${n}</button>`;
          }).join('')}</div>
          <div class="muted small">T${this.sac} · ${esc(name)} – ${esc(text)}</div></div>`;
    }).join('');
  }
}

function esc(t: string) { return t.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
