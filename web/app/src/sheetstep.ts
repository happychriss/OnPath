// The plan sheet's three steps – the app's PlanSize (ui/PlanPanel.kt: MINI, COMPACT, FULL) on every screen width
// (owner 2026-10-30, option A; docs/design/web-planner.md §11.3). One body, a step is only how much shows:
//   small   = the summary (activity chip · Share · ⋯ · chevron, time + distance, ↑/↓ + difficulty badge) + the route's
//             red / amber / grey lines (the app keeps them above its MINI step end: nothing that needs attention hides)
//   profile = + the elevation profile and "Saved as …"
//   points  = + the Points list (today's full sheet)
// No big main button (owner 2026-10-30): Share is the icon next to ⋯, in every step. Waits / errors in the status line
// show in every step; plain info lines only from the profile step on.
// Controls: the grab handle (the app's handle tap: one step bigger, from Points back to the profile), the chevron
// next to ⋯ (Show more until Points, then Show less → small), a click on the summary (= the handle), Arrow up / down on
// the handle and the chevron (towards the sheet's open edge). The step is remembered in localStorage (onpath.sheetStep).
import { icon } from './icons';
import { t } from './i18n';

export type SheetStep = 'small' | 'profile' | 'points';
const STEPS: SheetStep[] = ['small', 'profile', 'points'];
const KEY = 'onpath.sheetStep';

/** The app's PlanSize.tapped: grow one step; on Points go back to the profile. */
export function tapped(s: SheetStep): SheetStep { return s === 'points' ? 'profile' : STEPS[STEPS.indexOf(s) + 1]; }
/** The chevron: one step more until Points, then back to the small step. */
export function chevron(s: SheetStep): SheetStep { return s === 'points' ? 'small' : STEPS[STEPS.indexOf(s) + 1]; }
/** One step more (+1) or less (−1), stopping at the ends (arrow keys). */
export function by(s: SheetStep, d: 1 | -1): SheetStep { return STEPS[Math.min(2, Math.max(0, STEPS.indexOf(s) + d))]; }

const phone = () => matchMedia('(max-width: 700px)').matches;

/** Wires the handle, the chevron and the summary of [sheet]; [changed] runs after every step change (map padding). */
export function setupSheetSteps(sheet: HTMLElement, handle: HTMLElement, chev: HTMLButtonElement, summary: HTMLElement, changed: () => void) {
  let stored: string | null = null;
  try { stored = localStorage.getItem(KEY); } catch { /* expected: storage blocked – the default step */ }
  // nothing remembered: the wide side panel shows everything (as before), the phone's bottom sheet the profile (the app's default)
  const byWidth = (): SheetStep => (phone() ? 'profile' : 'points');
  let chosen = STEPS.includes(stored as SheetStep); // until the user picks a step, it follows the width's default
  let step: SheetStep = chosen ? stored as SheetStep : byWidth();

  function render() {
    sheet.dataset.step = step;
    const more = step !== 'points';
    const label = t(more ? 'web_sheet_more' : 'web_sheet_less');
    // the side panel opens downwards (⌄ = more), the bottom sheet upwards (⌃ = more)
    const down = more !== phone();
    chev.innerHTML = icon(down ? 'expandMore' : 'expandLess');
    chev.title = label; chev.setAttribute('aria-label', label);
  }
  function set(s: SheetStep, remember = true) {
    if (s === step) return;
    step = s;
    chosen = true;
    if (remember) { try { localStorage.setItem(KEY, s); } catch { /* expected: storage blocked – kept for this page only */ } }
    render();
    changed();
  }
  const keys = (e: KeyboardEvent) => {
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
    e.preventDefault();
    // towards the open edge = more: down for the side panel, up for the bottom sheet
    set(by(step, (e.key === 'ArrowUp') === phone() ? 1 : -1));
  };
  handle.onclick = () => set(tapped(step));
  handle.addEventListener('keydown', keys);
  chev.onclick = (e) => { e.stopPropagation(); set(chevron(step)); };
  chev.addEventListener('keydown', keys);
  // the summary row steps like the handle (not its buttons: the chip, "Plan anyway", ⋯)
  summary.addEventListener('click', (e) => {
    if ((e.target as HTMLElement).closest('button, a, .menu, input')) return;
    set(tapped(step));
  });
  // the chevron's direction follows the width; without a chosen step so does the step (side panel: all, phone: profile)
  matchMedia('(max-width: 700px)').addEventListener('change', () => { if (!chosen) step = byWidth(); render(); changed(); });
  render();
  return {
    get step() { return step; },
    set,
  };
}
