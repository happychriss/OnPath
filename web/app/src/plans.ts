// "My plans" and "Pair phone" (web planner phase 2, docs/design/web-sync.md §9). Saved plans live in this browser and
// – once paired – also on the phone, end-to-end encrypted through the mailbox. Conflict copies show as "… (copy)".
import qrcode from 'qrcode-generator';
import type { PlanSync, SavedPlan, SyncStatus } from './sync';
import { icon } from './icons';
import { t, locale } from './i18n';

export interface PlanHost {
  /** The current plan as a plan code + its title, or null when there is nothing to save. */
  current(): Promise<{ code: string; title: string | null } | null>;
  /** The saved plan the page shows now (to save a new version of it), or null. */
  openId(): string | null;
  /** The shown route's save state: empty (nothing to save), save, or saved and unchanged as [title]. */
  saveState(): Promise<{ state: 'empty' | 'save' | 'saved'; title: string | null }>;
  open(plan: SavedPlan): void;
  /** A name proposal for a new saved plan ("Algund → Meran", the app's RouteNamer), "" when none fits. */
  suggestName(): Promise<string>;
  /** The plan was saved as [p] – the page shows "Saved as …". */
  saved(p: SavedPlan): void;
}

let openDialog: HTMLDialogElement | null = null;

function dialog(cls: string, html: string): HTMLDialogElement {
  openDialog?.close();
  const d = document.createElement('dialog');
  d.className = 'share ' + cls;
  d.innerHTML = html;
  document.body.append(d);
  d.addEventListener('close', () => { d.remove(); if (openDialog === d) openDialog = null; });
  openDialog = d;
  d.showModal();
  return d;
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
const when = (ms: number) => new Date(ms).toLocaleString(locale, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

/** The sync line under the lists: paired with whom, last sync, or what went wrong (plain words). */
export function syncLine(s: SyncStatus): { text: string; kind: SyncStatus['kind'] } {
  if (s.message) return { text: s.message, kind: s.kind };
  if (!s.paired) return { text: t('web_sync_browser_only'), kind: 'info' };
  const who = s.devices.length ? s.devices.map((d) => d.name).join(', ') : t('web_sync_no_phone_yet');
  const last = s.lastSync ? ' · ' + t('web_sync_synced_at', new Date(s.lastSync).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })) : '';
  const pending = s.pending ? ' · ' + t('web_sync_to_send', s.pending) : '';
  return { text: t('web_sync_paired_with', who) + last + pending, kind: 'info' };
}

/** "3 days ago" / "vor 3 Tagen", "2 min. ago" / "vor 2 Min." (Intl, the page's language); under a minute "just now". */
export function ago(ms: number, now = Date.now()): string {
  const s = Math.max(0, now - ms) / 1000;
  if (s < 60) return t('web_ago_now');
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'always', style: 'short' });
  if (s < 3600) return rtf.format(-Math.floor(s / 60), 'minute');
  if (s < 86400) return rtf.format(-Math.floor(s / 3600), 'hour');
  return rtf.format(-Math.floor(s / 86400), 'day');
}

/**
 * Green while the phone synced within this time, amber after it (owner 2026-10-30). 24 h: the app syncs when it starts,
 * when it comes back and every minute while open – but never in the background (PlanSync.kt) – so a phone in use
 * syncs many times a day, and a day without any access means OnPath was not opened on it today. Shorter (the app's own
 * 6 h "not synced" warning) would turn amber every night in normal use; longer would hide that a route planned here
 * yesterday is not on the phone yet.
 */
export const PHONE_FRESH_MS = 24 * 3_600_000;

export interface PhoneState { kind: 'none' | 'fresh' | 'stale'; head: string; why: string }
/**
 * The phone in three states (header dot + tooltip, top of the Phone window): not paired (grey), paired and the phone
 * synced lately (green), paired but the phone has not synced for [PHONE_FRESH_MS] (amber). A host without appSeen (or
 * a pairing of two browsers): green with when this browser synced – no amber without knowing the phone's time.
 */
export function phoneState(s: SyncStatus, now = Date.now()): PhoneState {
  if (!s.paired) return { kind: 'none', head: t('web_pair_phone'), why: t('web_phone_why') };
  const who = s.devices.map((d) => d.name).join(', ');
  if (!who) return { kind: 'stale', head: t('web_sync_paired') + ' · ' + t('web_sync_no_phone_yet'), why: t('web_phone_stale_why') };
  if (s.phoneSeen != null) {
    return now - s.phoneSeen <= PHONE_FRESH_MS
      ? { kind: 'fresh', head: t('web_phone_fresh', ago(s.phoneSeen, now)), why: t('web_sync_paired_with', who) }
      : { kind: 'stale', head: t('web_phone_stale', ago(s.phoneSeen, now)), why: t('web_phone_stale_why') };
  }
  return { kind: 'fresh', head: s.lastSync ? t('web_phone_browser_synced', ago(s.lastSync, now)) : t('web_sync_paired'), why: t('web_sync_paired_with', who) };
}

/**
 * My routes (owner 2026-10-30): only the planned routes – heading "Your planned routes" with the app's planned-route
 * icon (only planned routes sync, no tracks), Save current route, the list, Close. The phone has its own window.
 */
export async function myPlansDialog(sync: PlanSync, host: PlanHost) {
  const save = await host.saveState(); // before the dialog shows: its Save button is right from the start
  const d = dialog('plans', `
    <h2>${esc(t('routes_title'))}</h2>
    <h3 id="planHead" class="listhead">${icon('route', 20)}${esc(t('web_plans_heading'))}</h3>
    <button id="saveCurrent" class="outline">${icon('save', 18)}${esc(t('plan_save_current_route'))}</button>
    <ul id="planList" class="planlist" aria-labelledby="planHead"></ul>
    <div class="row end"><button id="closePlans">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  // Save: greyed on an empty route, ✓ while saved and unchanged (a click still asks Save again / Save as new – the
  // app's saveMode)
  const sb = $<HTMLButtonElement>('saveCurrent');
  sb.disabled = save.state === 'empty';
  sb.dataset.state = save.state;
  if (save.state === 'empty') sb.title = t('plan_err_set_start_dest');
  if (save.state === 'saved') {
    sb.innerHTML = `${icon('check', 18)}<span>${t('web_saved_as', `<b>${esc(save.title ?? t('plan_route_default_name'))}</b>`)}</span>`;
    sb.title = t('web_saved_title', save.title ?? t('plan_route_default_name'));
  }
  $('saveCurrent').onclick = () => { d.close(); requestSave(sync, host); };

  const render = async () => {
    const plans = await sync.list();
    const ul = $('planList');
    if (ul.querySelector('.confirm')) return; // a delete question is open: keep the list as it is
    ul.innerHTML = plans.length ? '' : `<li class="muted">${esc(t('web_plans_none'))}</li>`;
    for (const p of plans) {
      const li = document.createElement('li');
      li.dataset.id = p.id;
      li.innerHTML = `<button class="open txt" title="${esc(t('web_plans_open'))}"><span class="name">${esc(p.title ?? t('plan_route_default_name'))}</span>
        <span class="muted small">${when(p.updatedAt)}${p.mine ? '' : ' · ' + esc(t('web_plans_from_other'))}${p.id === host.openId() ? ' · ' + esc(t('web_plans_shown_now')) : ''}</span></button>
        <button class="rename iconbtn" aria-label="${esc(t('common_rename'))}">✎</button><button class="del iconbtn" aria-label="${esc(t('common_delete'))}" title="${esc(t('common_delete'))}">${icon('delete', 20)}</button>`;
      li.querySelector<HTMLButtonElement>('.open')!.onclick = () => { d.close(); host.open(p); };
      li.querySelector<HTMLButtonElement>('.rename')!.onclick = async () => {
        const name = prompt(t('web_plans_new_name'), p.title ?? '');
        if (name != null && name.trim()) {
          await sync.rename(p.id, name.trim());
          if (p.id === host.openId()) host.saved({ ...p, title: name.trim() }); // "Saved as …" follows the new name
          render();
        }
      };
      li.querySelector<HTMLButtonElement>('.del')!.onclick = () => askInPlace(li, t('web_delete_q', p.title ?? t('plan_route_default_name')),
        t('common_delete'), () => li.querySelector('.del'), async () => { await sync.remove(p.id); await render(); $('saveCurrent').focus(); }, t('web_keep'));
      ul.append(li);
    }
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  d.addEventListener('close', () => off());
  $('closePlans').onclick = () => d.close();
  await render();
}

/**
 * The Phone window (owner 2026-10-30: the one place for pairing – header entry / ☰ entry, not in My routes): the state
 * on top ([phoneState] – grey / green / amber, one line why), "How it works" in three lines, privacy in one (+ More →
 * the privacy page), then Pair phone (→ the pairing dialog with the QR code and the steps) or Sync now · Show code
 * again · Unpair (the question takes their place), Close at the bottom right. Follows the sync live; the "… ago"
 * text moves on every 30 s.
 */
export function phoneDialog(sync: PlanSync, page: string) {
  const d = dialog('phone', `
    <h2>${esc(t('web_phone_title'))}</h2>
    <div id="phoneState" class="phonestate">${icon('phone', 28)}<i class="pdot" aria-hidden="true"></i>
      <div><b id="phoneHead"></b><p id="phoneWhy" class="muted small"></p></div></div>
    <div id="phoneStatus" class="status" role="status"></div>
    <h3 class="how">${esc(t('web_phone_how_title'))}</h3>
    <ul class="how"><li id="how1">${esc(t('web_phone_why'))}</li><li>${esc(t('web_phone_how_2'))}</li><li>${esc(t('web_phone_how_3'))}</li></ul>
    <p class="muted small privacy">${esc(t('web_phone_privacy'))} <a href="${esc(t('web_url_privacy'))}" target="_blank" rel="noopener">${esc(t('web_phone_more'))}</a></p>
    <div class="row wrap" id="phoneRow"><button id="pairPhone" class="primary" hidden>${esc(t('web_pair_phone'))}</button><button id="syncNow" hidden>${esc(t('web_plans_sync_now'))}</button><button id="codeAgain" hidden title="${esc(t('web_phone_code_again_title'))}">${esc(t('web_phone_code_again'))}</button><button id="unpair" class="danger" hidden>${esc(t('web_unpair'))}</button></div>
    <div class="row end"><button id="closePhone">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  let said = ''; // a result of Unpair, shown until the next change of the sync
  const render = () => {
    const s = sync.status, st = phoneState(s);
    $('phoneState').dataset.kind = st.kind;
    $('phoneHead').textContent = st.head;
    $('phoneWhy').textContent = st.why;
    const line = said || (s.message ? s.message : s.paired && s.pending ? t('web_sync_to_send', s.pending) : '');
    $('phoneStatus').textContent = line;
    $('phoneStatus').classList.toggle('error', !said && s.kind === 'error' && !!s.message);
    $('phoneStatus').classList.toggle('warning', !said && s.kind === 'warning' && !!s.message);
    $('phoneStatus').classList.toggle('muted', !s.message || s.kind === 'info');
    if (!s.paired) d.querySelector<AskBox>('.confirm')?.cancel(); // ended meanwhile: nothing to ask
    $('syncNow').hidden = !s.paired;
    $('codeAgain').hidden = !s.paired;
    $('unpair').hidden = !s.paired;
    $('pairPhone').hidden = s.paired;
    $('how1').hidden = !s.paired; // not paired: the state line says it already
  };
  const off = sync.onChange(() => { said = ''; if (d.open) render(); });
  const tick = window.setInterval(render, 30_000);
  d.addEventListener('close', () => { off(); clearInterval(tick); });
  $('pairPhone').onclick = () => pairDialog(sync, page); // the pairing dialog takes this one's place
  $('syncNow').onclick = () => sync.syncNow();
  $('codeAgain').onclick = () => pairDialog(sync, page, true); // a second device joins the same pairing (web-sync.md §13.2)
  // the question takes the place of "Sync now / Unpair" (owner 2026-10-30: one "Unpair" at a time, above Close)
  $('unpair').onclick = () => askInPlace($('phoneRow'), t('web_unpair_q'), t('web_unpair'), () => $('unpair'), async () => {
    const err = await sync.unpair();
    said = err ?? t('web_sync_unpaired');
    render();
    $('phoneStatus').classList.toggle('error', !!err);
    d.querySelector<HTMLButtonElement>('#phoneRow button:not([hidden])')?.focus();
  });
  $('closePhone').onclick = () => d.close();
  render();
  d.querySelector<HTMLButtonElement>('#phoneRow button:not([hidden])')?.focus();
  if (sync.status.paired) sync.syncNow(); // the phone's time fresh from the host
}

/**
 * Save, as the app's AppViewModel.requestSave (RouteRules.saveMode): a plan shown from My plans (and still there) asks
 * "Save" (a new version of it, name kept – saveChoiceOverwrite) or "Save as new…"; otherwise the name dialog with the
 * app's name proposal (RouteNamer). Saving closes the dialog; the sheet then says "Saved as …".
 */
export async function requestSave(sync: PlanSync, host: PlanHost) {
  const cur = await host.current();
  if (!cur) return;
  const id = host.openId();
  const existing = id ? (await sync.list()).find((p) => p.id === id) : undefined;
  if (!existing) { nameDialog(sync, host, cur); return; }
  const d = dialog('savechoice', `<h2>${esc(t('web_save_q', existing.title ?? t('plan_route_default_name')))}</h2>
    <p class="muted">${esc(t('web_save_choice_text'))}</p>
    <div class="row end"><button id="saveAsNew">${esc(t('plan_save_as_new'))}</button><button id="saveOver" class="primary">${esc(t('common_save'))}</button></div>`);
  d.querySelector<HTMLButtonElement>('#saveAsNew')!.onclick = () => { d.close(); nameDialog(sync, host, cur); };
  d.querySelector<HTMLButtonElement>('#saveOver')!.onclick = async () => {
    d.close();
    const title = existing.title ?? cur.title;
    await sync.save(cur.code, title, existing.id);
    host.saved({ id: existing.id, title, plan: cur.code, updatedAt: Date.now(), mine: true });
  };
}

/** The name dialog: opens at once, takes the app's proposal over while nothing was typed (AppViewModel.suggestedName). */
function nameDialog(sync: PlanSync, host: PlanHost, cur: { code: string; title: string | null }) {
  const fallback = t('web_plan_dated', when(Date.now()));
  const d = dialog('savename', `<h2>${esc(t('plan_save_route'))}</h2>
    <label class="muted small" for="saveTitle">${esc(t('routes_name_label'))}</label>
    <input id="saveTitle" maxlength="100" placeholder="${esc(fallback)}" value="${esc(cur.title ?? '')}">
    <div class="row end"><button id="saveCancel">${esc(t('common_cancel'))}</button><button id="savePlan" class="primary">${esc(t('common_save'))}</button></div>`);
  const input = d.querySelector<HTMLInputElement>('#saveTitle')!;
  let typed = !!cur.title;
  input.addEventListener('input', () => { typed = true; });
  input.select();
  host.suggestName().then((n) => { if (n && !typed && d.open) { input.value = n; input.select(); } });
  let busy = false;
  const save = async () => {
    if (busy) return; // one click, one plan (owner 2026-10-28: saving made duplicates)
    busy = true;
    const title = input.value.trim() || fallback;
    d.close();
    const newId = await sync.save(cur.code, title);
    host.saved({ id: newId, title, plan: cur.code, updatedAt: Date.now(), mine: true });
  };
  d.querySelector<HTMLButtonElement>('#saveCancel')!.onclick = () => d.close();
  d.querySelector<HTMLButtonElement>('#savePlan')!.onclick = save;
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); save(); } });
}

/**
 * Pair phone: the QR code, the steps in the app and the check digits; once the phone said hello, "Paired with …".
 * [again] (Phone window → Show code again, paired): the same code for a second device – it stays on the code.
 * Unpair lives in the Phone window only (owner 2026-10-30: not three "Unpair").
 */
export async function pairDialog(sync: PlanSync, page: string, again = false) {
  const d = dialog('pair', `<h2>${esc(t(again ? 'web_phone_code_again' : 'web_pair_phone'))}</h2><div id="pairBody"><p class="muted">${esc(t('web_pair_preparing'))}</p></div>
    <div id="pairStatus" class="status muted"></div>
    <div class="row end" id="pairButtons"><button id="closePair">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  $('closePair').onclick = () => d.close();
  let poll = 0;
  d.addEventListener('close', () => clearInterval(poll));

  const showCode = async () => {
    const p = await sync.pairing(page);
    if ('error' in p) { $('pairBody').innerHTML = `<p class="status error">${esc(p.error)}</p>`; return; }
    const qr = qrcode(0, 'M');
    qr.addData(p.link, 'Byte');
    qr.make();
    $('pairBody').innerHTML = `
      <div class="qr" role="img" aria-label="${esc(t('web_pair_qr'))}">${qr.createSvgTag({ scalable: true, margin: 2 })}</div>
      <p>${t('web_pair_how', `<b>${esc(t('web_pair_menu_path'))}</b>`)}</p>
      <p class="check">${t('web_pair_check', `<b id="checkDigits">${esc(p.check)}</b>`)}</p>
      <p class="muted small">${esc(t('web_pair_privacy'))}</p>
      <input id="pairLink" readonly aria-label="${esc(t('web_pair_link'))}" value="${esc(p.link)}">`;
    // until the phone said hello: sync every 3 s (the app scans, then writes its name into the mailbox)
    clearInterval(poll);
    poll = window.setInterval(() => sync.syncNow(), 3000);
  };

  const render = async () => {
    const s = sync.status;
    const line = syncLine(s);
    $('pairStatus').textContent = s.paired && !s.devices.length && !s.message ? t('web_pair_waiting') : line.text;
    $('pairStatus').classList.toggle('error', line.kind === 'error');
    if (s.paired && s.devices.length && !again) {
      clearInterval(poll);
      $('pairBody').innerHTML = `<p>${t('web_pair_done', `<b>${esc(s.devices.map((x) => x.name).join(', '))}</b>`)}</p>
        <p class="muted small">${esc(t('web_pair_digits', s.check ?? ''))}</p>`;
    }
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  d.addEventListener('close', () => off());
  if (sync.status.paired && sync.status.devices.length && !again) render(); else { await showCode(); render(); }
}

type AskBox = HTMLElement & { cancel(): void };

/**
 * A question that can lose something (CLAUDE.md "Messages": verb buttons, no body that repeats them), asked in place
 * (owner 2026-10-30, "zu viele Trennen"): the box takes the place of [row] – the buttons (or the list line) that asked –
 * with [no] + one red [verb]. No / Escape bring [row] back and focus [back]; [verb] does it. One question at a time.
 */
function askInPlace(row: HTMLElement, text: string, verb: string, back: () => HTMLElement | null, act: () => void, no = t('common_cancel')) {
  const d = row.closest('dialog')!;
  d.querySelector<AskBox>('.confirm')?.cancel();
  const box = document.createElement(row.tagName === 'LI' ? 'li' : 'div') as unknown as AskBox;
  box.className = 'confirm';
  box.setAttribute('role', 'group');
  box.innerHTML = `<p>${esc(text)}</p>
    <div class="row end"><button class="keep">${esc(no)}</button><button class="danger primary">${esc(verb)}</button></div>`;
  // Escape answers "no" instead of closing the whole dialog (keydown first; the dialog's cancel event as the fallback)
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); box.cancel(); } };
  const onCancel = (e: Event) => { e.preventDefault(); box.cancel(); };
  const end = () => { box.remove(); row.hidden = false; d.removeEventListener('keydown', onKey, true); d.removeEventListener('cancel', onCancel); };
  box.cancel = () => { end(); back()?.focus(); };
  d.addEventListener('keydown', onKey, true);
  d.addEventListener('cancel', onCancel);
  row.hidden = true;
  row.after(box);
  box.querySelector<HTMLButtonElement>('.keep')!.onclick = () => box.cancel();
  box.querySelector<HTMLButtonElement>('.danger')!.onclick = () => { end(); act(); };
  box.querySelector<HTMLButtonElement>('.keep')!.focus();
}

/** A pairing link opened in a browser (normally the phone app scans it): pair this browser instead? */
export function joinDialog(sync: PlanSync, link: string) {
  const d = dialog('join', `<h2>${esc(t('web_join_title'))}</h2>
    <p>${esc(t('web_join_text'))}</p>
    <p class="muted small">${esc(t('web_join_instead'))}</p>
    <div id="joinStatus" class="status"></div>
    <div class="row end"><button id="joinCancel">${esc(t('common_cancel'))}</button><button id="joinOk" class="primary">${esc(t('web_join_ok'))}</button></div>`);
  d.querySelector<HTMLButtonElement>('#joinCancel')!.onclick = () => d.close();
  d.querySelector<HTMLButtonElement>('#joinOk')!.onclick = async () => {
    const err = await sync.join(link);
    const st = d.querySelector('#joinStatus')!;
    if (err) { st.textContent = err; st.classList.add('error'); return; }
    st.textContent = t('web_join_paired', sync.status.check ?? '');
    d.querySelector<HTMLButtonElement>('#joinOk')!.remove();
    d.querySelector<HTMLButtonElement>('#joinCancel')!.textContent = t('common_close');
  };
}
