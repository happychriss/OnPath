// The activity picker (the app's ActivityPicker dialog): the six activities with the line under each, and for Hike the
// SAC level T1–T5 with its short description. Names come from the app's Activity enum (engine call "activities");
// the SAC texts are the app's ui/Screens.SacScale (an Android file, so mirrored here).
import type { Activity } from './engine/types';
import { activityIcon } from './icons';

export interface ActivityRow { key: Activity; label: string; line: string; chip: string }

const SAC = [
  ['Hiking', 'Well-built path, flat or slightly steep. No fall risk. Trainers are enough.'],
  ['Mountain hiking', 'Continuous path, steep in places, fall risk not excluded. Some sure-footedness; hiking boots recommended.'],
  ['Demanding mountain hiking', 'Path not always visible; exposed places may have ropes or chains; hands sometimes needed for balance. Sure-footed, good hiking boots.'],
  ['Alpine hiking', 'Path not always present; hands needed in places; exposed terrain, steep grass, scree, easy snow fields. Experience in exposed terrain.'],
  ['Demanding alpine hiking', 'Often without path; easy climbing (up to grade II); very exposed terrain. Mountaineering experience needed.'],
];

export class ActivityPicker {
  private rows: ActivityRow[] = [];
  private d: HTMLDialogElement;
  private act: Activity = 'HIKE';
  private sac = 2;

  constructor(private onPick: (a: Activity, sac: number) => void) {
    this.d = document.createElement('dialog');
    this.d.className = 'picker';
    this.d.id = 'activityDialog';
    document.body.append(this.d);
    this.d.addEventListener('click', (e) => {
      const t = e.target as HTMLElement;
      if (t === this.d) { this.d.close(); return; } // click on the backdrop
      const a = t.closest<HTMLElement>('[data-act]')?.dataset.act as Activity | undefined;
      const s = t.closest<HTMLElement>('[data-sac]')?.dataset.sac;
      if (a) { this.act = a; this.pick(); }
      else if (s) { this.sac = Number(s); this.pick(); }
      else if (t.closest('#activityDone')) this.d.close();
    });
  }

  setRows(rows: ActivityRow[]) { this.rows = rows; }
  row(a: Activity) { return this.rows.find((r) => r.key === a); }

  open(a: Activity, sac: number) {
    this.act = a; this.sac = sac;
    this.draw();
    this.d.showModal();
  }

  /** The choice applies at once (the plan is routed again behind the dialog); Done closes. */
  private pick() { this.draw(); this.onPick(this.act, this.sac); }

  private draw() {
    const [name, text] = SAC[this.sac - 1];
    this.d.innerHTML = `<h2>Activity</h2>
      <div class="acts" role="radiogroup" aria-label="Activity">${this.rows.map((r) => `
        <button data-act="${r.key}" role="radio" aria-checked="${r.key === this.act}" class="${r.key === this.act ? 'on' : ''}">
          ${activityIcon(r.key)}<span class="txt"><b>${r.label}</b><span class="muted">${r.line}</span></span></button>`).join('')}</div>
      <div id="sacRow" ${this.act === 'HIKE' ? '' : 'hidden'}>
        <div class="muted">Hiking difficulty (SAC)</div>
        <div class="seg" role="radiogroup" aria-label="Hiking difficulty">${[1, 2, 3, 4, 5].map((t) =>
          `<button data-sac="${t}" role="radio" aria-checked="${t === this.sac}" class="${t === this.sac ? 'on' : ''}">T${t}</button>`).join('')}</div>
        <p class="muted">T${this.sac} · ${name} – ${text}</p>
      </div>
      <div class="row end"><button id="activityDone" class="textbtn">Done</button></div>`;
  }
}
