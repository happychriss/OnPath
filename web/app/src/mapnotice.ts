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
  /** The load overlay (loadoverlay.ts): 0..100 and its text ("45 %") – a bar under the title. */
  progress?: number;
  progressText?: string;
  /** One text button (the route's Cancel). */
  action?: { label: string; act: () => void };
}

/**
 * Slots in their order on screen: the load overlay, the position, a cell whose map fails, then the map areas. While the
 * load overlay shows it is the one wait: the other busy lines step back; otherwise only the first busy line shows, and
 * only when it is the page's one wait (onewait.ts, body[data-wait="strip"]; owner 2026-11-13 – one wait at a time).
 */
const ORDER = ['load', 'locate', 'loading', 'cells', 'areas'] as const;
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

  private render() {
    this.box.replaceChildren();
    const load = this.shown.has('load');
    let count = 0;
    for (const slot of ORDER) {
      const n = this.shown.get(slot);
      if (!n || (load && slot !== 'load' && n.kind === 'busy')) continue;
      count++;
      const el = document.createElement('div');
      el.className = `mapnote ${n.kind}` + (slot === 'load' ? ' load' : '');
      el.dataset.slot = slot;
      const head = document.createElement('div');
      head.className = 'head';
      if (n.kind === 'busy' && n.progress == null) head.append(Object.assign(document.createElement('span'), { className: 'spinner', ariaHidden: 'true' }));
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
      if (n.progress != null) {
        el.dataset.pct = String(n.progress);
        const row = document.createElement('div');
        row.className = 'progress';
        const bar = document.createElement('div');
        bar.className = 'bar';
        bar.setAttribute('role', 'progressbar');
        bar.setAttribute('aria-valuemin', '0'); bar.setAttribute('aria-valuemax', '100'); bar.setAttribute('aria-valuenow', String(n.progress));
        bar.append(Object.assign(document.createElement('i'), { style: `width: ${n.progress}%` }));
        row.append(bar, Object.assign(document.createElement('span'), { className: 'pct', textContent: n.progressText ?? `${n.progress} %` }));
        el.append(row);
      }
      for (const l of n.lines ?? []) el.append(Object.assign(document.createElement('div'), { className: 'line', textContent: l }));
      if (n.action) {
        const a = n.action;
        const b = Object.assign(document.createElement('button'), { className: 'act', textContent: a.label });
        b.onclick = () => a.act();
        el.append(b);
      }
      this.box.append(el);
    }
    this.box.hidden = count === 0;
    this.onBusy([...this.shown].some(([slot, n]) => slot !== 'load' && n.kind === 'busy') && !load);
  }
}
