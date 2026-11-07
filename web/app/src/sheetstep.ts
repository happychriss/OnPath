// The plan sheet's steps (owner 2026-11-07, proposal A „Punkte zuerst“, web-planner.md §24):
//   wide screens: no steps – the side panel always shows everything (points first, facts, lines, way types); the
//                 elevation profile lies along the map's bottom edge (terrainview.ts, pinned), not in the panel
//   phone:        two steps – small = the head (activity, Share) + the facts + the route's lines, with the chevron
//                 „3 Punkte ⌃“; points = everything (points, + Ziel hinzufügen, facts, profile with „⤢ Höhenprofil groß“)
// Controls on the phone: the grab handle and the chevron toggle, a click on the facts opens, Arrow up / down on the
// handle and the chevron. The phone's step is remembered in localStorage (onpath.sheetStep; an old 'profile' = points).
import { icon } from './icons';
import { t } from './i18n';

export type SheetStep = 'small' | 'points';
const KEY = 'onpath.sheetStep';

/** The handle and the chevron: the other step. */
export function tapped(s: SheetStep): SheetStep { return s === 'points' ? 'small' : 'points'; }
export const chevron = tapped;
/** One step more (+1) or less (−1), stopping at the ends (arrow keys). */
export function by(_s: SheetStep, d: 1 | -1): SheetStep { return d > 0 ? 'points' : 'small'; }

const phone = () => matchMedia('(max-width: 700px)').matches;

/**
 * Wires the handle, the chevron and the facts of [sheet]; [changed] runs after every step change (map padding).
 * [points] gives the number of route points for the chevron's „3 Punkte“.
 */
export function setupSheetSteps(sheet: HTMLElement, handle: HTMLElement, chev: HTMLButtonElement, summary: HTMLElement, changed: () => void,
  points: () => number = () => 0) {
  let stored: string | null = null;
  try { stored = localStorage.getItem(KEY); } catch { /* expected: storage blocked – the default step */ }
  // the phone's step: remembered, an old 'profile' counts as the full sheet; nothing remembered → small (the map first)
  let step: SheetStep = stored === 'small' ? 'small' : stored === 'points' || stored === 'profile' ? 'points' : 'small';

  function render() {
    // wide: always everything (the CSS hides handle and chevron); phone: the chosen step
    sheet.dataset.step = phone() ? step : 'points';
    const more = step === 'small';
    const n = points();
    const label = more ? (n >= 2 ? t('web_sheet_points', String(n)) : t('web_sheet_more')) : t('web_sheet_less');
    chev.innerHTML = (more && n >= 2 ? `<span class="lbl">${t('web_sheet_points', String(n))}</span>` : '') + icon(more ? 'expandLess' : 'expandMore');
    chev.classList.toggle('withlabel', more && n >= 2);
    chev.title = label; chev.setAttribute('aria-label', label);
  }
  function set(s: SheetStep, remember = true) {
    if (s === step || !phone()) return;
    step = s;
    if (remember) { try { localStorage.setItem(KEY, s); } catch { /* expected: storage blocked – kept for this page only */ } }
    render();
    changed();
  }
  const keys = (e: KeyboardEvent) => {
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
    e.preventDefault();
    set(by(step, e.key === 'ArrowUp' ? 1 : -1)); // the bottom sheet opens upwards
  };
  handle.onclick = () => set(tapped(step));
  handle.addEventListener('keydown', keys);
  chev.onclick = (e) => { e.stopPropagation(); set(chevron(step)); };
  chev.addEventListener('keydown', keys);
  // the facts open the full sheet on the phone (not their buttons)
  summary.addEventListener('click', (e) => {
    if ((e.target as HTMLElement).closest('button, a, .menu, input')) return;
    if (step === 'small') set('points');
  });
  matchMedia('(max-width: 700px)').addEventListener('change', () => { render(); changed(); });
  render();
  return {
    get step(): SheetStep { return phone() ? step : 'points'; },
    set,
    /** Re-draw the chevron's label (the number of points changed). */
    refresh: render,
  };
}
