// Points list reordering like the app's PointsSection (owner 2026-10-28: the dragged row must visibly move): a drag
// that starts on ≡ lifts that row, it follows the pointer (mouse, finger, pen) and the other rows move aside to show
// where it lands; near the list's top / bottom edge the list scrolls. The arithmetic is the app's PlanPoints.dropIndex /
// PlanPoints.shift (routing/PlanPoints.kt, mirrored: it runs on every pointer move, the engine is a worker away).
// Keyboard: ≡ is a button, Arrow up / down moves its row by one.

/** PlanPoints.dropIndex: the row a drag of [offset] px from row [from] lands on. */
export const dropIndex = (from: number, offset: number, rowPx: number, count: number) =>
  rowPx <= 0 || count <= 0 ? from : Math.min(count - 1, Math.max(0, from + Math.round(offset / rowPx)));
/** PlanPoints.shift: by how many rows row [i] moves aside while row [from] is over row [to]. */
export const shift = (i: number, from: number, to: number) =>
  from < to && i > from && i <= to ? -1 : to < from && i >= to && i < from ? 1 : 0;

const EDGE = 40; // px from the list's visible edge where it starts to scroll

/** [onMove] gets the rows' data-i values (point indices) of the dragged row and of the row it lands on. */
export function sortable(list: HTMLElement, onMove: (from: number, to: number) => void) {
  let drag: { li: HTMLElement; rows: HTMLElement[]; from: number; to: number; y0: number; top0: number; y: number; rowPx: number; raf: number } | null = null;
  let swallowClick = false; // the click the drag's own pointerup makes (dispatched before any timer runs)

  const update = () => {
    const d = drag!;
    const offset = d.y - d.y0 + (list.scrollTop - d.top0);
    d.to = dropIndex(d.from, offset, d.rowPx, d.rows.length);
    d.li.style.transform = `translateY(${offset}px)`;
    d.rows.forEach((r, k) => { if (k !== d.from) r.style.transform = `translateY(${shift(k, d.from, d.to) * d.rowPx}px)`; });
  };
  const autoScroll = () => {
    const d = drag;
    if (!d) return;
    const box = list.getBoundingClientRect();
    const step = d.y < box.top + EDGE ? -8 : d.y > box.bottom - EDGE ? 8 : 0;
    if (step) { const before = list.scrollTop; list.scrollTop += step; if (list.scrollTop !== before) update(); }
    d.raf = requestAnimationFrame(autoScroll);
  };
  const end = (commit: boolean) => {
    const d = drag;
    if (!d) return;
    drag = null;
    cancelAnimationFrame(d.raf);
    list.classList.remove('sorting');
    d.li.classList.remove('lifted');
    d.rows.forEach((r) => { r.style.transform = ''; });
    swallowClick = true; setTimeout(() => { swallowClick = false; }, 0);
    if (commit && d.to !== d.from) onMove(Number(d.rows[d.from].dataset.i), Number(d.rows[d.to].dataset.i));
  };

  list.addEventListener('pointerdown', (e) => {
    const grip = (e.target as HTMLElement).closest('.grip');
    const li = grip?.closest('li') as HTMLElement | null;
    if (!grip || !li || e.button > 0) return;
    e.preventDefault();
    const rows = [...list.querySelectorAll<HTMLElement>(':scope > li')];
    const from = rows.indexOf(li);
    const next = rows[from + 1] ?? rows[from - 1];
    const rowPx = next ? Math.abs(next.offsetTop - li.offsetTop) : li.offsetHeight;
    drag = { li, rows, from, to: from, y0: e.clientY, top0: list.scrollTop, y: e.clientY, rowPx, raf: 0 };
    (grip as HTMLElement).setPointerCapture(e.pointerId);
    list.classList.add('sorting');
    li.classList.add('lifted');
    drag.raf = requestAnimationFrame(autoScroll);
  });
  list.addEventListener('pointermove', (e) => { if (drag) { drag.y = e.clientY; update(); } });
  list.addEventListener('pointerup', () => end(true));
  list.addEventListener('pointercancel', () => end(false));
  list.addEventListener('keydown', (e) => {
    const grip = (e.target as HTMLElement).closest('.grip');
    if (!grip || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return;
    e.preventDefault();
    const rows = [...list.querySelectorAll<HTMLElement>(':scope > li')];
    const from = rows.indexOf(grip.closest('li') as HTMLElement);
    const to = from + (e.key === 'ArrowUp' ? -1 : 1);
    if (to >= 0 && to < rows.length) onMove(Number(rows[from].dataset.i), Number(rows[to].dataset.i));
  });
  // the click that ends a drag must not also "show the point on the map" – only that one: a ✕ or a row clicked right
  // after a drop works at once, as in the app (a 250 ms window used to swallow it; parity-smoke "Points list ✕")
  list.addEventListener('click', (e) => { if (swallowClick) { swallowClick = false; e.stopImmediatePropagation(); } }, true);
}
