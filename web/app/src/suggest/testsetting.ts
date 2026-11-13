// The debug setting "Tourvorschläge (Test)" – the ONLY way to switch tour suggestions on outside this computer
// (owner 2026-11-14: "über unsere Debug-Einstellung das Feature einstellen … und das VPN, ansonsten kann ich es
// ausstellen, dann ist alles normal"). The planner's debug place is ?debug in the address (main.ts DEBUG: the developer
// lines and the "Copy diagnostics" button) – this adds one more small button there, which opens the setting:
//   switch off / on · Rechner (address of the computer at home) · Passwort · "Verbindung prüfen".
// Stored only in this browser under one key (remote.ts TEST_KEY); switching off removes every key of the feature and
// reloads – the planner is then exactly as before. Without ?debug this file puts nothing on the page.
// While the switch is on, the wish ("Mehr") and the cards carry a small link to this setting (ui.ts), so it can always
// be switched off without typing ?debug again.
import { t } from '../i18n';
import { clearTestSetting, pingRemote, saveTestSetting, testSetting, type PingResult, type TestSetting } from './remote';

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);

/** A set-up link's part after `#suggest-test=`: "<address>|<password>" (written by the owner's own computer). */
export function parseSetupHash(hash: string): TestSetting | null {
  const m = /[#&]suggest-test=([^&]+)/.exec(hash);
  if (!m) return null;
  let v: string;
  try { v = decodeURIComponent(m[1]); } catch { return null; } // expected: a mangled link
  const i = v.lastIndexOf('|');
  const url = (i < 0 ? v : v.slice(0, i)).trim(), token = i < 0 ? '' : v.slice(i + 1).trim();
  return url ? { url, token } : null;
}

const pingText = (r: PingResult): string => t('web_suggest_test_ping_' + r);

export function openTestSetting(prefill: TestSetting | null = null) {
  document.getElementById('suggestTest')?.remove();
  const cur = testSetting();
  const start = prefill ?? cur;
  const d = document.createElement('dialog');
  d.id = 'suggestTest';
  d.className = 'share savename suggesttest';
  d.innerHTML = `<h2>${esc(t('web_suggest_test_title'))}</h2>
    <p class="muted small">${esc(t('web_suggest_test_about'))}</p>
    <label class="sgcheck"><input type="checkbox" id="stOn" ${cur || prefill ? 'checked' : ''}><span>${esc(t('web_suggest_test_on'))}</span></label>
    <label class="field"><span class="muted small">${esc(t('web_suggest_test_host'))}</span><input id="stUrl" maxlength="200" autocomplete="off" autocapitalize="off" spellcheck="false" inputmode="url"></label>
    <label class="field"><span class="muted small">${esc(t('web_suggest_test_password'))}</span><input id="stToken" type="password" maxlength="200" autocomplete="off" autocapitalize="off" spellcheck="false"></label>
    <div class="row"><button type="button" id="stPing">${esc(t('web_suggest_test_check'))}</button><span id="stResult" class="small" role="status"></span></div>
    <p class="muted small" id="stVia">${esc(t('web_suggest_via', 'remote'))}</p>
    <div class="row end"><button type="button" id="stCancel">${esc(t('common_cancel'))}</button><button type="button" id="stOk" class="primary">${esc(t('common_ok'))}</button></div>`;
  document.body.append(d);
  const on = d.querySelector<HTMLInputElement>('#stOn')!, url = d.querySelector<HTMLInputElement>('#stUrl')!, token = d.querySelector<HTMLInputElement>('#stToken')!;
  const result = d.querySelector<HTMLElement>('#stResult')!;
  url.value = start?.url ?? '';
  token.value = start?.token ?? '';
  const now = (): TestSetting => ({ url: url.value.trim(), token: token.value.trim() });
  d.querySelector<HTMLButtonElement>('#stPing')!.onclick = async () => {
    if (!now().url) { result.textContent = t('web_suggest_test_need_host'); result.dataset.r = 'unreachable'; return; }
    result.textContent = '…';
    const r = await pingRemote(now());
    result.dataset.r = r;
    result.textContent = pingText(r);
  };
  d.querySelector<HTMLButtonElement>('#stCancel')!.onclick = () => d.close();
  d.querySelector<HTMLButtonElement>('#stOk')!.onclick = () => {
    const want = on.checked && !!now().url;
    if (on.checked && !now().url) { result.textContent = t('web_suggest_test_need_host'); result.dataset.r = 'unreachable'; return; }
    const was = JSON.stringify(cur);
    if (want) saveTestSetting(now()); else clearTestSetting();
    d.close();
    // the feature is set up when the page starts: a changed setting takes effect with a reload (the plan is in the address)
    if (JSON.stringify(testSetting()) !== was) {
      // switched on: the address loses ?debug (its buttons would sit over the cards on a phone) – the link in the wish
      // and under the cards leads back here; the set-up link's part goes in any case
      const q = new URLSearchParams(location.search);
      if (want) q.delete('debug');
      const search = q.toString().replace(/=(?=&|$)/g, '');
      history.replaceState(null, '', location.pathname + (search ? '?' + search : '') + location.hash.replace(/[#&]suggest-test=[^&]*/, ''));
      location.reload();
    }
  };
  d.addEventListener('close', () => d.remove());
  d.showModal();
}

/** Called once at page start (main.ts). [debug] = ?debug is in the address. */
export function installTestSetting(debug: boolean) {
  const link = parseSetupHash(location.hash);
  if (!debug && !link) return; // no debug: nothing at all (while the switch is on, the wish and the cards carry a link to the setting)
  const b = document.createElement('button');
  b.id = 'suggestTestBtn';
  b.type = 'button';
  b.textContent = t('web_suggest_test_title');
  // at the map's right edge, half way up: clear of the sheet, the cards and the bars on a phone and on a wide page
  b.style.cssText = 'position:fixed;z-index:5;right:12px;top:42%;padding:6px 10px;border-radius:10px;border:1px solid #888;background:#fff;color:#222;font:12px sans-serif';
  b.onclick = () => openTestSetting();
  document.body.append(b);
  if (link) openTestSetting(link); // a set-up link fills the fields; nothing is stored before OK
}
