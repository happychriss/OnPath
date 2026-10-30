// The app's long-route question (routing/LongRoute, AppViewModel.recalc / answerLongRoute, ui/TourBar LongRouteDialog;
// docs/design/web-planner.md §15): a plan far beyond a day – a point in another country by mistake – is asked about
// before anything is routed or downloaded. Rule and texts come from the engine (WebPlanRules.kt); this module only
// shows the dialog. "Plan anyway" remembers the points (asked once per plan, like the app's longRouteOk).
import type { EngineClient } from './engine/client';
import type { Activity } from './engine/types';
import { t } from './i18n';

type LatLon = [number, number];
interface Ask { ask: boolean; title?: string; text?: string; plan?: string; cancel?: string; notRouted?: string; again?: string }

let okFor = '';
const keyOf = (pts: LatLon[]) => pts.map(([a, b]) => `${a.toFixed(6)},${b.toFixed(6)}`).join(';');

/** "Plan anyway" for these points without asking (the not-routed line's button). */
export function allowLongRoute(points: LatLon[]) { okFor = keyOf(points); }

/** Texts of the not-routed line after Cancel: "Route not calculated – very long plan." + "Plan anyway". */
export let notRoutedTexts = { line: t('plan_not_routed_long'), button: t('plan_anyway') };

/**
 * True = route now (short enough, or already allowed); false = the user cancelled. Shows the app's question when the
 * plan is very long; nothing is routed while it is open.
 */
export async function longRouteOk(engine: EngineClient, points: LatLon[], activity: Activity): Promise<boolean> {
  const k = keyOf(points);
  if (k === okFor) return true;
  const a = JSON.parse(await engine.call('longRoute', k, activity)) as Ask;
  if (!a.ask) return true;
  notRoutedTexts = { line: a.notRouted ?? notRoutedTexts.line, button: a.again ?? notRoutedTexts.button };
  const yes = await question(a);
  if (yes) okFor = k;
  return yes;
}

/** The dialog stays until a button is clicked: Escape or a click outside must not undo anything (as in the app). */
function question(a: Ask): Promise<boolean> {
  return new Promise((resolve) => {
    const d = document.createElement('dialog');
    d.className = 'share longroute';
    d.innerHTML = `<h2>⚠ ${esc(a.title ?? '')}</h2><p>${esc(a.text ?? '')}</p>
      <div class="row"><button id="lrCancel">${esc(a.cancel ?? t('common_cancel'))}</button><button class="primary" id="lrPlan">${esc(a.plan ?? t('plan_anyway'))}</button></div>`;
    document.body.append(d);
    const done = (v: boolean) => { d.close(); d.remove(); resolve(v); };
    d.querySelector<HTMLButtonElement>('#lrPlan')!.onclick = () => done(true);
    d.querySelector<HTMLButtonElement>('#lrCancel')!.onclick = () => done(false);
    d.addEventListener('cancel', (e) => e.preventDefault());
    d.showModal();
  });
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
