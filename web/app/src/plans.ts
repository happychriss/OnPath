// "My routes" and "Pair phone" (web planner phase 2, docs/design/web-sync.md §9, §14). Saved routes live in this
// browser in folders – one per paired phone, plus "Without phone" (owner order A-017) – and, after Save, on the phone,
// end-to-end encrypted through the mailbox. Conflict copies show as "… (copy)" / "… (Kopie)".
import qrcode from 'qrcode-generator';
import { NONE, shownTitle, type PlanSync, type SavedPlan, type SyncStatus } from './sync';
import { icon } from './icons';
import { t, locale } from './i18n';

export interface PlanHost {
  /** The current plan as a plan code + its title, or null when there is nothing to save. */
  current(): Promise<{ code: string; title: string | null } | null>;
  /** The saved plan the page shows now (to save a new version of it), or null. */
  openId(): string | null;
  /** The folder the shown saved route belongs to (the open folder for a new route). */
  openFolder(): string;
  /** The shown route's save state: empty (nothing to save), save, or saved and unchanged as [title]. */
  saveState(): Promise<{ state: 'empty' | 'save' | 'saved'; title: string | null }>;
  /** Shows [plan] from [folder] (a route, or a synced GPX track). */
  open(plan: SavedPlan, folder: string): void;
  /** A name proposal for a new saved plan ("Algund → Meran", the app's RouteNamer), "" when none fits. */
  suggestName(): Promise<string>;
  /** The plan was saved as [p] in [folder] – the page shows "Saved as …". */
  saved(p: SavedPlan, folder: string): void;
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
const titleOf = (p: { title: string | null }) => shownTitle(p.title, t('plan_route_default_name'));

/** The sync line under the lists: paired with whom, last sync, or what went wrong (plain words). */
export function syncLine(s: SyncStatus): { text: string; kind: SyncStatus['kind'] } {
  if (s.message) return { text: s.message, kind: s.kind };
  if (!s.paired) return { text: t('web_sync_browser_only'), kind: 'info' };
  const who = s.phone ?? (s.devices.length ? s.devices.map((d) => d.name).join(', ') : t('web_sync_no_phone_yet'));
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
  const who = s.phone ?? s.devices.map((d) => d.name).join(', ');
  if (!who) return { kind: 'stale', head: t('web_sync_paired') + ' · ' + t('web_sync_no_phone_yet'), why: t('web_phone_stale_why') };
  if (s.phoneSeen != null) {
    return now - s.phoneSeen <= PHONE_FRESH_MS
      ? { kind: 'fresh', head: t('web_phone_fresh', ago(s.phoneSeen, now)), why: t('web_sync_paired_with', who) }
      : { kind: 'stale', head: t('web_phone_stale', ago(s.phoneSeen, now)), why: t('web_phone_stale_why') };
  }
  return { kind: 'fresh', head: s.lastSync ? t('web_phone_browser_synced', ago(s.lastSync, now)) : t('web_sync_paired'), why: t('web_sync_paired_with', who) };
}

/** The quiet lines "Remove routes of ‹phone› from this browser" for the closed folders (A-017 rule 16), no question. */
function removeLines(box: HTMLElement, sync: PlanSync) {
  box.innerHTML = '';
  for (const f of sync.status.closed) {
    const b = document.createElement('button');
    b.className = 'textbtn quiet removefolder';
    b.dataset.folder = f.key;
    b.textContent = t('web_remove_folder', f.name);
    b.onclick = () => sync.removeFolder(f.key);
    box.append(b);
  }
  box.hidden = !sync.status.closed.length;
}

/**
 * My routes (owner 2026-10-30 / A-017): the open folder's planned routes and imported tracks – heading "Your planned
 * routes · Pixel 8" (or "· Without phone"), Save current route, the list (the phone's new / changed routes bold with a
 * grey line until opened), Close. Paired, "Without phone (n)" looks into the routes planned before pairing (open one →
 * "Copy to ‹phone›"); the closed folders of other phones can be removed from here.
 */
export async function myPlansDialog(sync: PlanSync, host: PlanHost) {
  const save = await host.saveState(); // before the dialog shows: its Save button is right from the start
  let view = sync.open;
  const d = dialog('plans', `
    <h2>${esc(t('routes_title'))}</h2>
    <h3 id="planHead" class="listhead">${icon('route', 20)}<span id="planHeadText"></span></h3>
    <button id="saveCurrent" class="outline">${icon('save', 18)}${esc(t('plan_save_current_route'))}</button>
    <ul id="planList" class="planlist" aria-labelledby="planHead"></ul>
    <div id="folderSwitch" class="folderswitch" hidden></div>
    <div id="removeFolders" class="removefolders" hidden></div>
    <div class="row end"><button id="closePlans">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  // Save: greyed on an empty route, ✓ while saved and unchanged (a click still asks Save again / Save as new – the
  // app's saveMode)
  const sb = $<HTMLButtonElement>('saveCurrent');
  sb.disabled = save.state === 'empty';
  sb.dataset.state = save.state;
  if (save.state === 'empty') sb.title = t('plan_err_set_start_dest');
  if (save.state === 'saved') {
    sb.innerHTML = `${icon('check', 18)}<span>${t('web_saved_as', `<b>${esc(shownTitle(save.title, t('plan_route_default_name')))}</b>`)}</span>`;
    sb.title = t('web_saved_title', save.title ?? t('plan_route_default_name'));
  }
  $('saveCurrent').onclick = () => { d.close(); requestSave(sync, host); };

  const render = async () => {
    if (view !== NONE && view !== sync.open) view = sync.open; // the folder closed meanwhile
    const plans = await sync.list(view);
    const ul = $('planList');
    if (ul.querySelector('.confirm')) return; // a delete question is open: keep the list as it is
    const folderName = view === NONE ? t('web_folder_none') : sync.status.folder.name;
    $('planHeadText').textContent = t('web_plans_heading_in', folderName);
    $('saveCurrent').hidden = view === NONE && sync.open !== NONE; // looking into Without phone: new routes go to the phone's folder
    ul.innerHTML = plans.length ? '' : `<li class="muted">${esc(t('web_plans_none'))}</li>`;
    for (const p of plans) {
      const li = document.createElement('li');
      li.dataset.id = p.id;
      li.dataset.kind = p.kind;
      if (p.mark) li.classList.add('marked');
      const line = p.mark ? esc(t(p.mark === 'new' ? 'web_mark_new' : 'web_mark_changed'))
        : when(p.updatedAt) + (p.kind === 'track' ? ' · ' + esc(t('web_plans_track')) : '') + (p.id === host.openId() ? ' · ' + esc(t('web_plans_shown_now')) : '');
      li.innerHTML = `<button class="open txt" title="${esc(t('web_plans_open'))}"><span class="name">${p.kind === 'track' ? icon('route', 16) : ''}${esc(titleOf(p))}</span>
        <span class="muted small mark">${line}</span></button>
        <button class="rename iconbtn" aria-label="${esc(t('common_rename'))}">✎</button><button class="del iconbtn" aria-label="${esc(t('common_delete'))}" title="${esc(t('common_delete'))}">${icon('delete', 20)}</button>`;
      const folder = view;
      li.querySelector<HTMLButtonElement>('.open')!.onclick = () => { d.close(); sync.seen(p.id, folder); host.open(p, folder); };
      li.querySelector<HTMLButtonElement>('.rename')!.onclick = async () => {
        const name = prompt(t('web_plans_new_name'), titleOf(p));
        if (name != null && name.trim()) {
          // a copy keeps its stored marker (SyncMerge.storedTitle): "… (Kopie)" typed back is " (copy)" in the item
          const label = ' ' + t('web_copy_suffix');
          const stored = name.trim().endsWith(label.trim()) ? name.trim().slice(0, -label.trim().length).trimEnd() + ' (copy)' : name.trim();
          await sync.rename(p.id, stored, folder);
          if (p.id === host.openId()) host.saved({ ...p, title: stored }, folder); // "Saved as …" follows the new name
          render();
        }
      };
      li.querySelector<HTMLButtonElement>('.del')!.onclick = () => askInPlace(li,
        folder === NONE ? t('web_delete_q_local', titleOf(p)) : t('web_delete_q', titleOf(p)),
        t('common_delete'), () => li.querySelector('.del'), async () => { await sync.remove(p.id, folder); await render(); $('saveCurrent').focus(); }, t('web_keep'));
      ul.append(li);
    }
    // paired: a look into Without phone (and back)
    const sw = $('folderSwitch');
    sw.innerHTML = '';
    const none = sync.open !== NONE ? await sync.count(NONE) : 0;
    if (sync.open !== NONE && (view === NONE || none > 0)) {
      const b = document.createElement('button');
      b.className = 'textbtn quiet';
      b.id = 'switchFolder';
      b.textContent = view === NONE ? t('web_folder_back', sync.status.folder.name) : t('web_folder_show_none', none);
      b.onclick = () => { view = view === NONE ? sync.open : NONE; render(); };
      sw.append(b);
    }
    sw.hidden = !sw.childElementCount;
    removeLines($('removeFolders'), sync);
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  d.addEventListener('close', () => off());
  $('closePlans').onclick = () => d.close();
  await render();
}

/**
 * The Phone window (owner 2026-10-30: the one place for pairing – header entry / ☰ entry, not in My routes): the state
 * on top ([phoneState] – grey / green / amber, one line why), "How it works" in three lines, privacy in one (+ More →
 * the privacy page), then Pair phone (→ the pairing dialog with the QR code and the steps) or Sync now · Pair another
 * phone · Unpair (A-017: no question – the routes stay here), the closed folders' "Remove routes of …" lines, Close at
 * the bottom right. Follows the sync live; the "… ago" text moves on every 30 s.
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
    <div class="row wrap" id="phoneRow"><button id="pairPhone" class="primary" hidden>${esc(t('web_pair_phone'))}</button><button id="syncNow" hidden>${esc(t('web_plans_sync_now'))}</button><button id="pairOther" hidden>${esc(t('web_pair_other'))}</button><button id="unpair" class="danger" hidden>${esc(t('web_unpair'))}</button></div>
    <div id="removeFolders" class="removefolders" hidden></div>
    <div class="row end"><button id="closePhone">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  const render = () => {
    const s = sync.status, st = phoneState(s);
    $('phoneState').dataset.kind = st.kind;
    $('phoneHead').textContent = st.head;
    $('phoneWhy').textContent = st.why;
    $('phoneStatus').textContent = s.message ? s.message : s.paired && s.pending ? t('web_sync_to_send', s.pending) : '';
    $('phoneStatus').classList.toggle('error', s.kind === 'error' && !!s.message);
    $('phoneStatus').classList.toggle('warning', s.kind === 'warning' && !!s.message);
    $('phoneStatus').classList.toggle('muted', !s.message || s.kind === 'info');
    $('syncNow').hidden = !s.paired;
    $('pairOther').hidden = !s.paired;
    $('unpair').hidden = !s.paired;
    $('pairPhone').hidden = s.paired;
    $('how1').hidden = !s.paired; // not paired: the state line says it already
    removeLines($('removeFolders'), sync);
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  const tick = window.setInterval(render, 30_000);
  d.addEventListener('close', () => { off(); clearInterval(tick); });
  $('pairPhone').onclick = () => pairDialog(sync, page); // the pairing dialog takes this one's place
  $('pairOther').onclick = () => pairDialog(sync, page); // a new code; this phone's folder closes once the other phone answers
  $('syncNow').onclick = () => sync.syncNow();
  // A-017 rule 3 / point 8: Unpair without a question – the folder closes, its routes stay in this browser
  $('unpair').onclick = async () => {
    await sync.unpair();
    render();
    d.querySelector<HTMLButtonElement>('#phoneRow button:not([hidden])')?.focus();
  };
  $('closePhone').onclick = () => d.close();
  render();
  d.querySelector<HTMLButtonElement>('#phoneRow button:not([hidden])')?.focus();
  if (sync.status.paired) sync.syncNow(); // the phone's time fresh from the host
}

/**
 * Save, as the app's AppViewModel.requestSave (RouteRules.saveMode): a plan shown from My routes (and still there) asks
 * "Save" (a new version of it, name kept – saveChoiceOverwrite) or "Save as new…"; otherwise the name dialog with the
 * app's name proposal (RouteNamer). Saving closes the dialog; the sheet then says "Saved as …"; [then] runs after a
 * save (the header's Save sends to the phone then – A-017).
 */
export async function requestSave(sync: PlanSync, host: PlanHost, then?: () => void) {
  const cur = await host.current();
  if (!cur) return;
  const id = host.openId(), folder = host.openFolder();
  const existing = id ? (await sync.list(folder)).find((p) => p.id === id && p.kind === 'plan') : undefined;
  if (!existing) { nameDialog(sync, host, cur, then); return; }
  const d = dialog('savechoice', `<h2>${esc(t('web_save_q', titleOf(existing)))}</h2>
    <p class="muted">${esc(t('web_save_choice_text'))}</p>
    <div class="row end"><button id="saveAsNew">${esc(t('plan_save_as_new'))}</button><button id="saveOver" class="primary">${esc(t('common_save'))}</button></div>`);
  d.querySelector<HTMLButtonElement>('#saveAsNew')!.onclick = () => { d.close(); nameDialog(sync, host, cur, then); };
  d.querySelector<HTMLButtonElement>('#saveOver')!.onclick = async () => {
    d.close();
    const title = existing.title ?? cur.title;
    await sync.save(cur.code, title, existing.id, folder);
    host.saved({ ...existing, title, plan: cur.code, updatedAt: Date.now(), mine: true }, folder);
    then?.();
  };
}

/** The name dialog: opens at once, takes the app's proposal over while nothing was typed (AppViewModel.suggestedName). */
function nameDialog(sync: PlanSync, host: PlanHost, cur: { code: string; title: string | null }, then?: () => void) {
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
    const folder = sync.open; // a new route goes into the open folder
    const newId = await sync.save(cur.code, title, undefined, folder);
    host.saved({ id: newId, kind: 'plan', title, plan: cur.code, track: '', updatedAt: Date.now(), device: '', mine: true }, folder);
    then?.();
  };
  d.querySelector<HTMLButtonElement>('#saveCancel')!.onclick = () => d.close();
  d.querySelector<HTMLButtonElement>('#savePlan')!.onclick = save;
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); save(); } });
}

/**
 * Pair phone: always a new code (A-017 point 2), the QR code, the steps in the app and the check digits; once the
 * phone answered (its hello, or – already paired with another browser – its JOIN), "Paired with …".
 * Unpair lives in the Phone window only (owner 2026-10-30: not three "Unpair").
 */
export async function pairDialog(sync: PlanSync, page: string) {
  const d = dialog('pair', `<h2>${esc(t('web_pair_phone'))}</h2><div id="pairBody"><p class="muted">${esc(t('web_pair_preparing'))}</p></div>
    <div id="pairStatus" class="status muted"></div>
    <div class="row end" id="pairButtons"><button id="closePair">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  $('closePair').onclick = () => d.close();
  let poll = 0, shown = false;
  d.addEventListener('close', () => clearInterval(poll));

  const render = () => {
    const s = sync.status;
    if (!shown) return;
    if (s.waiting) {
      $('pairStatus').textContent = s.message && s.kind !== 'info' ? s.message : t('web_pair_waiting');
      $('pairStatus').classList.toggle('error', s.kind === 'error' && !!s.message);
      return;
    }
    if (s.paired) {
      clearInterval(poll);
      $('pairStatus').textContent = '';
      $('pairBody').innerHTML = `<p>${t('web_pair_done', `<b>${esc(s.phone ?? s.devices.map((x) => x.name).join(', '))}</b>`)}</p>`;
    }
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  d.addEventListener('close', () => off());

  const p = await sync.pairing(page);
  if (!d.open) return;
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
  shown = true;
  render();
  // until the phone answered: look every 3 s (the app scans, then writes its hello or its JOIN into the mailbox)
  poll = window.setInterval(() => sync.syncNow(), 3000);
  sync.syncNow();
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
    st.textContent = sync.status.paired ? t('web_join_paired', sync.status.check ?? '') : t('web_pair_waiting');
    d.querySelector<HTMLButtonElement>('#joinOk')!.remove();
    d.querySelector<HTMLButtonElement>('#joinCancel')!.textContent = t('common_close');
  };
}
