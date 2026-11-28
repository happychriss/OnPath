// The side panel on wide screens (owner 2026-11-28, proposal v2 „ok, so bauen“): the search sits at the top of the panel
// (the panel is the plan, search belongs on top – planner item 2026-11-27, the app's Daumen-Karte has its search in the
// card's head too), and the panel can be slid out like Google Maps' side panel – a tab „‹“ on its right edge, collapsed a
// tab „›“ at the left edge – so the map shows the whole tour. Collapsed, the search goes back into the top bar.
// Phone width keeps the bottom sheet with its steps (sheetstep.ts): no side tab, the search stays in the top bar.
// The collapsed state is remembered in localStorage (a per-browser convenience, like the sheet's step).
import { icon } from './icons';
import { t } from './i18n';

const KEY = 'onpath.sheetHidden';
const phone = () => matchMedia('(max-width: 700px)').matches;

export interface SheetPanel {
  /** Collapsed now (wide screens only). */
  readonly hidden: boolean;
  set(hidden: boolean): void;
  /** Puts the search where it belongs now (after a layout change: width, panel shown / hidden by another view). */
  place(): void;
}

/**
 * [sheet] the plan panel, [search] the search box (search.ts mounts into it), [bar] the top bar it comes from with its
 * separator [sep]; [changed] runs after the panel slid in or out (the map refits the whole tour into the freed room).
 */
export function setupSheetPanel(sheet: HTMLElement, search: HTMLElement, bar: HTMLElement, sep: HTMLElement, stage: HTMLElement,
  changed: () => void): SheetPanel {
  let hidden = false;
  try { hidden = localStorage.getItem(KEY) === '1'; } catch { /* expected: storage blocked – the panel shows */ }
  const tab = document.createElement('button');
  tab.id = 'sheetTab'; tab.className = 'sheettab'; tab.type = 'button';
  tab.setAttribute('aria-controls', 'sheet');
  stage.append(tab);

  function place() {
    const wide = !phone();
    document.body.classList.toggle('sheet-hidden', wide && hidden);
    tab.hidden = !wide;
    tab.innerHTML = icon(hidden ? 'chevronRight' : 'chevronLeft', 20);
    const label = t(hidden ? 'web_sheet_show' : 'web_sheet_hide');
    tab.title = label; tab.setAttribute('aria-label', label); tab.setAttribute('aria-expanded', String(!hidden));
    // the search in the panel only while the panel shows on a wide screen; else in the top bar, where it was
    const inPanel = wide && !hidden && getComputedStyle(sheet).display !== 'none';
    if (inPanel && search.parentElement !== sheet) sheet.prepend(search);
    if (!inPanel && search.parentElement !== bar) bar.insertBefore(search, sep);
    document.body.classList.toggle('search-in-sheet', inPanel);
  }
  function set(h: boolean) {
    if (h === hidden) return;
    hidden = h;
    try { localStorage.setItem(KEY, h ? '1' : '0'); } catch { /* expected: storage blocked – kept for this page only */ }
    place();
    changed();
  }
  tab.onclick = () => set(!hidden);
  addEventListener('resize', place);
  // another view hides the panel (the big profile, a place card on a phone): the search goes back to the bar meanwhile
  let placing = false;
  new MutationObserver(() => { if (placing) return; placing = true; try { place(); } finally { placing = false; } })
    .observe(document.body, { attributes: true, attributeFilter: ['class'] });
  place();
  return { get hidden() { return hidden; }, set, place };
}
