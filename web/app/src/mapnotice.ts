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

/** Slots in their order on screen: the position, then the map loading, then the map areas. */
const ORDER = ['locate', 'loading', 'areas'] as const;
export type Slot = (typeof ORDER)[number];

export class MapNotices {
  private shown = new Map<Slot, Notice>();
  constructor(private box: HTMLElement) { box.setAttribute('role', 'status'); box.setAttribute('aria-live', 'polite'); this.render(); }

  set(slot: Slot, n: Notice | null) {
    if (n) this.shown.set(slot, n); else this.shown.delete(slot);
    this.render();
  }

  get(slot: Slot): Notice | undefined { return this.shown.get(slot); }

  private render() {
    this.box.replaceChildren();
    for (const slot of ORDER) {
      const n = this.shown.get(slot);
      if (!n) continue;
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
    this.box.hidden = this.shown.size === 0;
  }
}
