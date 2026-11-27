// The map strip (docs/design/web-planner.md §17): one place on the map that says why the user waits for something they
// started on the map – finding the position, the map loading, a map area being prepared (the app's strip at the top of
// the map: ui/CellCard.kt, BackgroundDownloads.kt MapNoticeStrip). CLAUDE.md "Messages": progress = a spinner where the
// user started it, INFO grey, ERROR red with one way out; nothing dismisses itself while the user still needs it –
// each line goes when its wait is over (or by × where the user may hide it).

export type NoticeKind = 'busy' | 'info' | 'error';
export interface Notice {
  kind: NoticeKind;
  title: string;
  /** Smaller lines below the title (an area's rows, "2 more areas · waiting"). */
  lines?: string[];
  /** × to hide (aria label); hiding calls [onClose]. */
  close?: { label: string; onClose: () => void };
}

/**
 * Slots in their order on screen: the position, a cell whose map fails, then the map areas. While the wait window
 * (loadoverlay.ts) shows it is the one message: every line steps back ([cover]) – the busy ones and the notes too
 * („Nur Übersicht – setze hier einen Punkt …“ while that very map loads would contradict it; owner: overlapping
 * messages are a bug) – and comes back afterwards only if it is still true (its slot is still set). Otherwise only the
 * first busy line shows, and only when it is the page's one wait (onewait.ts, body[data-wait="strip"]; owner
 * 2026-11-13 – one wait at a time).
 */
const ORDER = ['locate', 'loading', 'cells', 'areas'] as const;
export type Slot = (typeof ORDER)[number];

export class MapNotices {
  private shown = new Map<Slot, Notice>();
  /** [onBusy]: whether a busy strip wants to show (onewait.ts decides whether it does – one wait at a time). */
  constructor(private box: HTMLElement, private onBusy: (busy: boolean) => void = () => {}) { box.setAttribute('role', 'status'); box.setAttribute('aria-live', 'polite'); this.render(); }

  set(slot: Slot, n: Notice | null) {
    if (n) this.shown.set(slot, n); else this.shown.delete(slot);
    this.render();
  }

  get(slot: Slot): Notice | undefined { return this.shown.get(slot); }

  /** The wait window shows: every line steps back (the window is the one message over the map). */
  cover(on: boolean) { if (on !== this.covered) { this.covered = on; this.render(); } }
  private covered = false;

  private render() {
    this.box.replaceChildren();
    const load = this.covered;
    let count = 0;
    for (const slot of ORDER) {
      const n = this.shown.get(slot);
      if (!n || load) continue;
      // one statement per state (owner 2026-11-27): the cells' line and the areas' note both speak about the detailed map
      // (offline, server busy) – the cells' line says it, the areas' note steps back unless it is a wait with progress
      if (slot === 'areas' && n.kind !== 'busy' && this.shown.has('cells')) continue;
      count++;
      const el = document.createElement('div');
      el.className = `mapnote ${n.kind}`;
      el.dataset.slot = slot;
      const head = document.createElement('div');
      head.className = 'head';
      if (n.kind === 'busy') head.append(Object.assign(document.createElement('span'), { className: 'spinner', ariaHidden: 'true' }));
      if (n.kind === 'error') head.append(Object.assign(document.createElement('span'), { className: 'warn', textContent: '⚠', ariaHidden: 'true' }));
      head.append(Object.assign(document.createElement('span'), { className: 'title', textContent: n.title }));
      if (n.close) {
        const x = document.createElement('button');
        x.className = 'x'; x.textContent = '×'; x.title = n.close.label; x.setAttribute('aria-label', n.close.label);
        const onClose = n.close.onClose;
        x.onclick = () => { this.set(slot, null); onClose(); };
        head.append(x);
      }
      el.append(head);
      for (const l of n.lines ?? []) el.append(Object.assign(document.createElement('div'), { className: 'line', textContent: l }));
      this.box.append(el);
    }
    this.box.hidden = count === 0;
    this.onBusy([...this.shown].some(([, n]) => n.kind === 'busy') && !load);
  }
}
