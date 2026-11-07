// The plan sheet's steps (owner 2026-11-07, proposal A „Punkte zuerst“, web-planner.md §24), at every width:
//   points = everything (facts first, plan row, lines, points with + Ziel hinzufügen, profile / „⤢ Großes Profil“, way
//            types) – the start state (owner 2026-11-07: „beim Start Punkte und Profil zeigen, man kann ihn zuklappen“);
//   small  = collapsed to the facts and the head (activity, Share) + the route's lines, with the chevron „3 Punkte ⌃“.
// Wide screens: the elevation profile lies along the map's bottom edge (terrainview.ts, pinned) unless hidden.
// Controls: the grab handle and the chevron toggle, a click on the facts opens, Arrow up / down on the handle and the
// chevron. The step is remembered in localStorage (onpath.sheetStep; an old 'profile' = points); nothing remembered = points.
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
  // remembered; an old 'profile' counts as the full sheet; nothing remembered → points (open: points and profile)
  let step: SheetStep = stored === 'small' ? 'small' : 'points';

  function render() {
    sheet.dataset.step = step;
    const more = step === 'small';
    const n = points();
    const label = more ? (n >= 2 ? t('web_sheet_points', String(n)) : t('web_sheet_more')) : t('web_sheet_less');
    // the phone's sheet opens upwards, the wide side panel downwards
    const up = phone() ? more : !more;
    chev.innerHTML = (more && n >= 2 ? `<span class="lbl">${t('web_sheet_points', String(n))}</span>` : '') + icon(up ? 'expandLess' : 'expandMore');
    chev.classList.toggle('withlabel', more && n >= 2);
    chev.title = label; chev.setAttribute('aria-label', label);
  }
  function set(s: SheetStep, remember = true) {
    if (s === step) return;
    step = s;
    if (remember) { try { localStorage.setItem(KEY, s); } catch { /* expected: storage blocked – kept for this page only */ } }
    render();
    changed();
  }
  const keys = (e: KeyboardEvent) => {
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
    e.preventDefault();
    const d = e.key === 'ArrowUp' ? 1 : -1;
    set(by(step, phone() ? d : (-d as 1 | -1))); // the bottom sheet opens upwards, the side panel downwards
  };
  handle.onclick = () => set(tapped(step));
  handle.addEventListener('keydown', keys);
  chev.onclick = (e) => { e.stopPropagation(); set(chevron(step)); };
  chev.addEventListener('keydown', keys);
  // the facts open the full sheet (not their buttons)
  summary.addEventListener('click', (e) => {
    if ((e.target as HTMLElement).closest('button, a, .menu, input')) return;
    if (step === 'small') set('points');
  });
  matchMedia('(max-width: 700px)').addEventListener('change', () => { render(); changed(); });
  render();
  return {
    get step(): SheetStep { return step; },
    set,
    /** Re-draw the chevron's label (the number of points changed). */
    refresh: render,
  };
}
