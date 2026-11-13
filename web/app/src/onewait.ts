// One wait at a time (owner 2026-11-13: „das irritiert den Benutzer“ – docs/design/web-planner.md §27). The page has
// five places that say "please wait": the load card (loadoverlay.ts), the route's wait box over the map (main.ts
// waitCard), the map strip's spinners (mapnotice.ts: position, "Preparing map for this area"), the sheet's wait line
// („Calculating the route…“) and the engine's status line. Each of them only says whether it *wants* to show; this
// arbiter picks the one that does show, by priority, and writes it to body[data-wait] – style.css hides the others in
// the same task (no frame with two). The load card first: when its 400 ms delay runs and two other waits want to show
// at once, the card comes now (it carries them – the area's rows are its lines, the calculation its last phase).
export type Wait = 'load' | 'box' | 'strip' | 'sheet' | 'status';
/** Highest first: the card (it carries the others), the box (the user's route, with Cancel), the strip, the sheet, the status line. */
export const WAIT_ORDER: readonly Wait[] = ['load', 'box', 'strip', 'sheet', 'status'];

export class OneWait {
  private want = new Set<Wait>();
  private deciding = false;
  /** The load card is due (its delay runs) – asked when two other waits want to show; [loadNow] brings it at once. */
  loadDue: () => boolean = () => false;
  loadNow: () => void = () => {};

  constructor(private el: HTMLElement) {}

  /** [w] wants to show (on) or not (off). */
  set(w: Wait, on: boolean) {
    if (on === this.want.has(w)) return;
    if (on) this.want.add(w); else this.want.delete(w);
    this.decide();
  }

  /** The wait shown now ('' = none). */
  get winner(): Wait | '' { return WAIT_ORDER.find((w) => this.want.has(w)) ?? ''; }

  /** Decide again (the load card became due). */
  decide() {
    if (this.deciding) return; // loadNow → set('load') while deciding: the outer call writes the result
    this.deciding = true;
    try {
      if (!this.want.has('load') && [...this.want].length > 1 && this.loadDue()) this.loadNow();
      const win = this.winner;
      if ((this.el.dataset.wait ?? '') !== win) this.el.dataset.wait = win;
    } finally { this.deciding = false; }
  }
}
