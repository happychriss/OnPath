// The one moment for the app (A-011, owner 2026-10-30): after "Send to phone" (in the QR dialog) and when a shared
// plan link is opened in the browser (in the plan sheet – never over the route), one calm line with what the app adds
// and "Get the app" → the website's try page (web/strings: web_url_try; never a store link). Once per browser session,
// closable ✕, never full screen, no tracking of any kind.
import { t } from './i18n';

const KEY = 'onpath.appHintShown';

function seen(): boolean {
  try { return sessionStorage.getItem(KEY) === '1'; } catch { return false; } // expected: storage blocked – then once per page load
}
let shownHere = false;

/** Shows the line in [box] (appended) unless it was shown in this session already. */
export function appHint(box: HTMLElement) {
  if (shownHere || seen()) return;
  shownHere = true;
  try { sessionStorage.setItem(KEY, '1'); } catch { /* expected: storage blocked */ }
  const el = document.createElement('div');
  el.className = 'apphint';
  el.id = 'appHintLine';
  const text = Object.assign(document.createElement('span'), { className: 'txt', textContent: t('web_app_hint') });
  const a = Object.assign(document.createElement('a'), { className: 'outline', href: t('web_url_try'), target: '_blank', rel: 'noopener', textContent: t('web_app_hint_button') });
  const x = Object.assign(document.createElement('button'), { className: 'x iconbtn', textContent: '×', title: t('common_close') });
  x.setAttribute('aria-label', t('common_close'));
  x.onclick = () => el.remove();
  el.append(text, a, x);
  box.append(el);
}
