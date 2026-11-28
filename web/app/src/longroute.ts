// The app's long-route question (routing/LongRoute, ui/TourBar LongRouteDialog; docs/design/web-planner.md §15): a plan far
// beyond a day – a point in another country by mistake – is asked about before anything is routed or downloaded. Whether
// it is asked is the app's run head (PlanRecalcState.begin + RouteRun in the engine's WebPlanChain, [runHead]); the answer
// goes back to it ([longRouteOk] → runLongAnswer: "Plan anyway" = RouteRun.longOk, these points are not asked again;
// the not-routed line's button = runPlanAnyway). This module shows the dialog.
// Web addition (owner 2026-10-30, "war in Meran, suche nach Hilden, gebe als Start"): when one new point is what makes
// the plan long – it is far from every point the plan already had (a single point left from earlier, or a route in
// another region the user no longer sees) – the question says so and offers to start a new route there instead.
import type { EngineClient } from './engine/client';
import type { Activity } from './engine/types';
import { t } from './i18n';
import { problem } from './diag';

type LatLon = [number, number];
interface Ask { ask: boolean; title?: string; text?: string; plan?: string; cancel?: string; notRouted?: string; again?: string;
  /** The browser's long-leg question (RouteCost): the leg, its straight km, beyond the browser's memory (only „OK“). */
  leg?: number; km?: number; beyond?: boolean; /** RouteCost.words of the leg's estimate: „etwa 3–6 Minuten“ */ words?: string }

const keyOf = (pts: LatLon[]) => pts.map(([a, b]) => `${a.toFixed(6)},${b.toFixed(6)}`).join(';');

/**
 * What the app's run head decided for a plan (WebApi.runHead = PlanRecalcState.begin): route the run [gen] with [rounds]
 * time-limit periods, ask first (the question's texts), or nothing to route (fewer than two points).
 */
export type Head = { what: 'ROUTE'; gen: number; rounds: number; /** the run's search estimate, s (the wait's „etwa …“) */ estimateS?: number } | { what: 'NONE' } | ({ what: 'ASK' } & Ask);

/**
 * The run's head for [points]: a multi-day tour is never asked ([multiDay], owner 2026-11-18 – its day rows show a long day).
 * A head the engine could not answer routes at once (one period, never blocked on a question that can't be put).
 */
export async function runHead(engine: EngineClient, points: LatLon[], activity: Activity, sac: number, multiDay = false, pinned: number[] = [],
  names = ''): Promise<Head> {
  try {
    // said yes to earlier on this page: the run knows it (RouteRun.RoutedBefore) – also on a fresh chain (an opened plan,
    // a fresh worker), so it is never asked again in this page session, as before the shared run
    if (allowed.has(keyOf(points))) await engine.call('runRoutedBefore', keyOf(points));
    return JSON.parse(await engine.call('runHead', keyOf(points), activity, sac, !multiDay, pinned.join(','), names)) as Head;
  } catch (e) {
    problem('routing', 'the route run\'s head failed – routed without the long-plan question', e);
    return { what: 'ROUTE', gen: -1, rounds: 1 };
  }
}

/** "Plan anyway" / "Calculate" of the not-routed line (the app's PlanRunSlice.planAnyway): after "very long", not asked again. */
export async function planAnyway(engine: EngineClient, points: LatLon[]) {
  // the line said "very long": these points are said yes to – kept page-side too, so the yes holds even when the run did
  // not reach "not routed, too long" (a Cancel whose take-back failed – review 2026-11-20)
  // (the browser's long-leg rule, owner 2026-11-27: „Berechnen“ asks again with the estimate – no blind yes kept here;
  // the engine's run decides, RouteRun.PlanAnyway without askSlow)
  try { await engine.call('runPlanAnyway', keyOf(points)); } catch (e) { problem('routing', '„Plan anyway“ not kept – the question may come again', e); }
}

/**
 * The plans said yes to ("Plan anyway") in this page session – the page's memory beside the run's own (RouteRun.longOk
 * keeps one plan and starts empty in a fresh chain).
 */
const allowed = new Set<string>();

/** Texts of the not-routed line after Cancel: "Route not calculated – very long plan." + "Plan anyway". */
export let notRoutedTexts = { line: t('plan_not_routed_long'), button: t('plan_calculate') }; // no „Trotzdem planen“ (owner 2026-11-28)
/** Whether the not-routed line is the long plan's (its button = "Plan anyway") rather than a stopped wait's. */
let notRoutedLong = true;
/** The not-routed line for another reason (the app's NotRouted.STOPPED after Cancel of the wait). */
export function setNotRouted(line: string, button: string) { notRoutedTexts = { line, button }; notRoutedLong = false; }

/** The plan before a point edit: its points and their names (for "far from your route"). */
export interface Before { points: LatLon[]; names: (string | undefined)[] }
/** 'plan' = route now; 'cancel' = the user cancelled; `{ fresh }` = start a new route with only this point. */
export type LongAnswer = 'plan' | 'cancel' | { fresh: LatLon };

/**
 * The question the run head [a] asks (the plan [points] is very long): plan anyway, cancelled, or start fresh. After a point
 * edit ([before]) it is "Far from your route" when the one new point alone makes the plan long (the web's own question);
 * nothing is routed while it is open. The answer goes to the run (RouteRun.LongAnswer): yes = these points are not asked
 * again; Cancel = the page's cancel ([why] LONG); a fresh start is a new plan.
 */
export async function longRouteOk(engine: EngineClient, a: Ask, points: LatLon[], activity: Activity,
  before?: Before | null, stale: () => boolean = () => false, names: (string | undefined)[] = []): Promise<LongAnswer> {
  notRoutedTexts = { line: a.notRouted ?? notRoutedTexts.line, button: a.again ?? notRoutedTexts.button };
  notRoutedLong = true;
  void activity;
  const far = before ? farPoint(a, points, before, names) : null;
  const answer: LongAnswer = far ? await farQuestion(far) : (await question(a)) ? 'plan' : 'cancel';
  if (answer !== 'cancel' && answer !== 'plan') return answer;
  if (answer === 'plan') allowed.add(keyOf(points));
  // a newer question was raised meanwhile (another run of the page): this answer must not close that one in the run
  if (stale()) return answer;
  try { await engine.call('runLongAnswer', answer === 'plan', keyOf(points)); }
  catch (e) { problem('routing', 'the answer to the long-plan question not kept – it may be asked again', e); }
  return answer;
}

interface Far { point: LatLon; title: string; text: string; beyond: boolean }

/**
 * The one new point of a point edit when the browser's long-leg question (RouteCost, [a]) is about a leg that ends or
 * starts at it: one question „München ist ca. 600 km weit weg“ / „Berechnung etwa 3–6 Minuten.“ with [Von hier starten]
 * [Route berechnen] in place of the long-leg question (owner 2026-11-27 variant C – never two questions in a row). The km
 * = straight to the nearest point the plan had. Null otherwise (an edit of existing points, a plan long for other reasons):
 * the long-leg question as it is.
 */
function farPoint(a: Ask, points: LatLon[], before: Before, names: (string | undefined)[]): Far | null {
  if (a.leg == null) return null;
  const old = new Set(before.points.map((p) => keyOf([p])));
  const fresh = points.map((p, i) => [p, i] as const).filter(([p]) => !old.has(keyOf([p])));
  if (!before.points.length || fresh.length !== 1 || points.length !== before.points.length + 1) return null;
  const [p, idx] = fresh[0];
  if (a.leg !== idx && a.leg !== idx - 1) return null;
  const nearest = before.points.reduce((b, q) => (metres(p, q) < metres(p, b) ? q : b));
  const km = metres(p, nearest) / 1000;
  const n = String(km >= 100 ? Math.round(km / 10) * 10 : Math.max(1, Math.round(km)));
  const name = names[idx]?.trim();
  const title = name ? t('web_far_title_named', name, n) : t('web_far_title_unnamed', n);
  const text = a.beyond ? t('web_far_too_far') : t('web_far_calc', a.words ?? '');
  return { point: p, title, text, beyond: !!a.beyond };
}

/** Straight distance in metres (only to find the nearest point). */
function metres([a1, o1]: LatLon, [a2, o2]: LatLon): number {
  const r = Math.PI / 180, x = (o2 - o1) * r * Math.cos(((a1 + a2) / 2) * r), y = (a2 - a1) * r;
  return Math.hypot(x, y) * 6371000;
}

/**
 * „München ist ca. 600 km weit weg“ (owner 2026-11-27 variant C): [Von hier starten] = a new plan from this point (the old one
 * comes back with ↶) · [Route berechnen] = route and wait (the wait window's „läuft seit … · etwa …“, no second question);
 * too far for the browser's memory: only [Von hier starten]. A FRAGE with two verb buttons, the likelier (the user added the point)
 * primary; it stays until a button is clicked (Escape / outside change nothing).
 */
function farQuestion(far: Far): Promise<LongAnswer> {
  return new Promise((resolve) => {
    const d = document.createElement('dialog');
    d.className = 'share longroute far';
    const plan = far.beyond ? '' : `<button class="primary" id="lrPlan">${esc(t('web_far_calculate'))}</button>`;
    d.innerHTML = `<h2>${esc(far.title)}</h2><p>${esc(far.text)}</p>
      <div class="row"><button id="lrFresh"${far.beyond ? ' class="primary"' : ''}>${esc(t('web_far_start'))}</button>${plan}</div>`;
    document.body.append(d);
    const done = (v: LongAnswer) => { d.close(); d.remove(); resolve(v); };
    d.querySelector<HTMLButtonElement>('#lrFresh')!.onclick = () => done({ fresh: far.point });
    d.querySelector<HTMLButtonElement>('#lrPlan')?.addEventListener('click', () => done('plan'));
    d.addEventListener('cancel', (e) => e.preventDefault());
    d.showModal();
  });
}

/** The dialog stays until a button is clicked: Escape or a click outside must not undo anything (as in the app). */
function question(a: Ask): Promise<boolean> {
  return new Promise((resolve) => {
    const d = document.createElement('dialog');
    d.className = 'share longroute';
    // a leg beyond the browser's memory: only „OK“, which takes the point back (the app's „Zu weit für das Handy“ in the
    // browser's words, owner 2026-11-28 – no „Zwischenziel“; the hint: a shorter stretch first, then the next goal)
    const title = a.beyond ? t('web_too_far_title') : a.title ?? '';
    const text = a.beyond ? t('web_too_far_text') : a.text ?? '';
    const plan = a.beyond ? '' : `<button class="primary" id="lrPlan">${esc(a.plan ?? t('plan_calculate'))}</button>`;
    d.innerHTML = `<h2>⚠ ${esc(title)}</h2><p>${esc(text)}</p>
      <div class="row"><button id="lrCancel">${esc(a.cancel ?? t('common_cancel'))}</button>${plan}</div>`;
    document.body.append(d);
    const done = (v: boolean) => { d.close(); d.remove(); resolve(v); };
    d.querySelector<HTMLButtonElement>('#lrPlan')?.addEventListener('click', () => done(true));
    d.querySelector<HTMLButtonElement>('#lrCancel')!.onclick = () => done(false);
    d.addEventListener('cancel', (e) => e.preventDefault());
    d.showModal();
  });
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
