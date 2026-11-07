// A newer planner deployed while this tab was open (backlog "Pairing in the browser – bugs" (2), Drive Teil 4 §1): the page
// knows its build id, version.json (never cached) says what is deployed. On a difference one box – "New version of the
// planner" [Reload] (a HINWEIS with its one way out, msgcard.ts) – never a reload by itself: the open route may be
// unsaved (it survives a reload in the address and the draft, but the user decides when). Checked when the tab comes
// back (focus / visible) and every 10 minutes while it is visible.
import { post } from './msgcard';
import { t } from './i18n';

declare const __BUILD_ID__: string;
/** This page's build (vite.config.ts). */
export const BUILD = typeof __BUILD_ID__ === 'string' ? __BUILD_ID__ : 'dev';

const EVERY_MS = 10 * 60_000;
let last = 0;

/** What is deployed now, or null (offline, no version.json on a test host). */
async function deployed(): Promise<string | null> {
  try {
    const r = await fetch(new URL('version.json', location.href), { cache: 'no-store' });
    if (!r.ok) return null;
    return ((await r.json()) as { build?: string }).build ?? null;
  } catch (e) {
    console.info('[Problems] INFO web: version not checked (offline?)', e); // expected: offline
    return null;
  }
}

/** Looks once (at most every 30 s); shows the box when a different build is deployed. */
export async function checkVersion(force = false) {
  if (!force && Date.now() - last < 30_000) return;
  last = Date.now();
  const now = await deployed();
  if (!now || now === BUILD) return;
  post({ id: 'version', kind: 'hinweis', title: t('web_new_version'), detail: t('web_new_version_detail'),
    buttons: [{ label: t('web_reload'), act: () => location.reload() }] });
}

export function watchVersion() {
  addEventListener('focus', () => { void checkVersion(); });
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') void checkVersion(); });
  setInterval(() => { if (document.visibilityState === 'visible') void checkVersion(true); }, EVERY_MS);
}
