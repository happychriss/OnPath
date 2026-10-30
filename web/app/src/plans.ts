// "My routes" and the Phone window (web planner phase 2, docs/design/web-sync.md §9, §14; what they show: §14.6). Saved
// routes live in this browser in folders – one per paired phone, plus "Without phone" (owner order A-017) – and, after
// Save, on the phone, end-to-end encrypted through the mailbox. Conflict copies show as "… (copy)" / "… (Kopie)".
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
  /** Shows [plan] from [folder] (a route, or a synced GPX track). */
  open(plan: SavedPlan, folder: string): void;
  /** A name proposal for a new saved plan ("Algund → Meran", the app's RouteNamer), "" when none fits. */
  suggestName(): Promise<string>;
  /** The plan was saved as [p] in [folder] – the page shows "Saved as …". */
  saved(p: SavedPlan, folder: string): void;
  /** My routes → ⋮ → Export GPX: [p] from [folder] as a GPX file (a route is opened and planned first). */
  exportGpx(p: SavedPlan, folder: string): void;
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
/** A date as the app writes it in My routes (its common_date pattern: "30. Okt. 2026" / "30 Oct 2026"). */
export function appDate(ms: number): string {
  const d = new Date(ms), month = new Intl.DateTimeFormat(locale, { month: 'short' }).format(d);
  return t('common_date').replace(/yyyy|MMM|d/g, (k) => (k === 'yyyy' ? String(d.getFullYear()) : k === 'MMM' ? month : String(d.getDate())));
}
const titleOf = (p: { title: string | null }) => shownTitle(p.title, t('plan_route_default_name'));

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

/**
 * The header's Phone button (§14.6): not paired "Pair phone"; paired the phone's name with the dot of [phoneState]
 * and a small second line "synced 2 min. ago" (green) / "last 3 days ago" (amber, the phone away for a day).
 */
export function phonePill(s: SyncStatus, now = Date.now()): { kind: PhoneState['kind']; name: string; sub: string } {
  const kind = phoneState(s, now).kind;
  if (!s.paired) return { kind, name: t('web_pair_phone'), sub: '' };
  const name = s.phone ?? (s.devices.map((d) => d.name).join(', ') || t('web_folder_phone'));
  const seen = s.phoneSeen ?? s.lastSync;
  const sub = seen == null ? '' : kind === 'stale' ? t('web_phone_last_ago', ago(seen, now)) : t('web_phone_synced_ago', ago(seen, now));
  return { kind, name, sub };
}

/**
 * My routes – one plain list (web-sync.md §14.6): title, the rows, Close. A row: the kind in a circle as in the app
 * (planned route blue, imported GPX purple), the name, "Planned · 30 Oct 2026" / "GPX · …"; on the right where it lies
 * (🖥 this browser, 📱 the phone, both) and ⋮ with Rename, Export GPX, Delete. Bold = changed here and not on the phone
 * yet; the open route on a light green. A click on the row opens it. Follows the sync live.
 */
export async function myPlansDialog(sync: PlanSync, host: PlanHost) {
  const d = dialog('plans', `
    <h2 id="plansTitle">${esc(t('routes_title'))}</h2>
    <ul id="planList" class="planlist" aria-labelledby="plansTitle"></ul>
    <div class="row end"><button id="closePlans">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  const closeMenus = () => d.querySelectorAll<HTMLElement>('.rowmenu').forEach((m) => { m.hidden = true; m.previousElementSibling?.setAttribute('aria-expanded', 'false'); });
  d.addEventListener('click', (e) => { if (!(e.target as HTMLElement).closest('.rowmenu, .more')) closeMenus(); });
  $('planList').addEventListener('scroll', closeMenus); // the menu stays fixed: it goes when its row moves

  const render = async () => {
    const folder = sync.open, paired = sync.status.paired;
    const plans = await sync.list(folder);
    const ul = $('planList');
    if (ul.querySelector('.confirm')) return; // a delete question is open: keep the list as it is
    ul.innerHTML = plans.length ? '' : `<li class="muted">${esc(t('web_plans_none'))}</li>`;
    for (const p of plans) {
      const li = document.createElement('li');
      li.dataset.id = p.id;
      li.dataset.kind = p.kind;
      // 🖥 = changed last here (all of Without phone), 📱 = a version of it is on the phone (in its mailbox)
      const here = !paired || p.mine, phone = paired && p.onPhone;
      li.dataset.where = [here ? 'browser' : '', phone ? 'phone' : ''].filter(Boolean).join('+');
      if (paired && p.dirty) li.classList.add('waiting');
      if (p.id === host.openId() && folder === host.openFolder()) li.classList.add('current');
      const kind = p.kind === 'track' ? t('web_kind_gpx') : t('routes_tab_planned');
      const kindName = p.kind === 'track' ? t('routes_kind_imported') : t('routes_kind_planned');
      const where = (cls: string, key: string, ic: 'monitor' | 'phone') =>
        `<span class="w ${cls}" role="img" title="${esc(t(key))}" aria-label="${esc(t(key))}">${icon(ic, 18)}</span>`;
      li.innerHTML = `<button class="open" title="${esc(t('web_plans_open'))}"><span class="kindic" title="${esc(kindName)}">${icon(p.kind === 'track' ? 'fileOpen' : 'route', 22)}</span>
        <span class="txt"><span class="name">${esc(titleOf(p))}</span><span class="muted small sub">${esc(kind + ' · ' + appDate(p.updatedAt))}</span></span></button>
        <span class="where">${here ? where('browser', 'web_where_browser', 'monitor') : ''}${phone ? where('phone', 'web_where_phone', 'phone') : ''}</span>
        <button class="more iconbtn" aria-haspopup="menu" aria-expanded="false" aria-label="${esc(t('plan_route_options'))}" title="${esc(t('plan_route_options'))}">${icon('moreVert', 22)}</button>
        <div class="menu rowmenu" role="menu" hidden><button class="rename" role="menuitem">${esc(t('common_rename'))}</button><button class="export" role="menuitem">${esc(t('common_export_gpx'))}</button><button class="del" role="menuitem">${esc(t('common_delete'))}</button></div>`;
      const q = <T extends HTMLElement>(sel: string) => li.querySelector<T>(sel)!;
      q('.open').onclick = () => { d.close(); sync.seen(p.id, folder); host.open(p, folder); };
      q('.more').onclick = (e) => {
        e.stopPropagation();
        const m = q('.rowmenu'), open = m.hidden;
        closeMenus();
        m.hidden = !open;
        q('.more').setAttribute('aria-expanded', String(open));
        if (!open) return;
        // fixed at its ⋮ (below it, or above when there is no room) – the scrolling list never cuts it off
        const r = q('.more').getBoundingClientRect(), h = m.offsetHeight, w = m.offsetWidth;
        m.style.left = Math.max(8, r.right - w) + 'px';
        m.style.top = (r.bottom + 4 + h > innerHeight - 8 ? r.top - 4 - h : r.bottom + 4) + 'px';
        q<HTMLButtonElement>('.rowmenu button').focus();
      };
      q('.rename').onclick = async () => {
        closeMenus();
        const name = prompt(t('web_plans_new_name'), titleOf(p));
        if (name == null || !name.trim()) return;
        const stored = storedTitle(name);
        await sync.rename(p.id, stored, folder);
        if (p.id === host.openId()) host.saved({ ...p, title: stored }, folder); // "Saved as …" follows the new name
        render();
      };
      q('.export').onclick = () => { closeMenus(); d.close(); host.exportGpx(p, folder); };
      q('.del').onclick = () => {
        closeMenus();
        askInPlace(li, folder === NONE ? t('web_delete_q_local', titleOf(p)) : t('web_delete_q', titleOf(p)),
          t('common_delete'), () => li.querySelector('.more'), async () => { await sync.remove(p.id, folder); await render(); $('closePlans').focus(); }, t('web_keep'));
      };
      ul.append(li);
    }
  };
  const off = sync.onChange(() => { if (d.open && !d.querySelector('.rowmenu:not([hidden])')) render(); });
  d.addEventListener('close', () => off());
  $('closePlans').onclick = () => d.close();
  await render();
}

/** The quiet lines "Remove routes of ‹phone› from this browser" for the closed folders (A-017 rule 16), no question. */
function removeLines(box: HTMLElement, sync: PlanSync) {
  box.querySelectorAll('.removefolder').forEach((b) => b.remove());
  for (const f of sync.status.closed) {
    const b = document.createElement('button');
    b.className = 'textbtn quiet removefolder';
    b.dataset.folder = f.key;
    b.textContent = t('web_remove_folder', f.name);
    b.onclick = () => sync.removeFolder(f.key);
    box.append(b);
  }
}

/**
 * The Phone window – small (web-sync.md §14.6): the state with its dot and one sentence ([phoneState]); not paired
 * "Pair phone" (→ the pairing dialog: QR code, the steps, the check number), paired "Sync now" and "Unpair" (no
 * question – the routes stay here, A-017); "How does it work?" opens the explanation and the privacy line in place;
 * at the bottom, small: "Pair another phone" and the closed folders' "Remove routes of …"; Close. Follows the sync
 * live; the "… ago" text moves on every 30 s.
 */
export function phoneDialog(sync: PlanSync, page: string) {
  const d = dialog('phone', `
    <h2>${esc(t('web_phone_title'))}</h2>
    <div id="phoneState" class="phonestate">${icon('phone', 28)}<i class="pdot" aria-hidden="true"></i>
      <div><b id="phoneHead"></b><p id="phoneWhy" class="muted small"></p></div></div>
    <div id="phoneStatus" class="status" role="status"></div>
    <div class="row wrap" id="phoneRow"><button id="pairPhone" class="primary" hidden>${esc(t('web_pair_phone'))}</button><button id="syncNow" hidden>${esc(t('web_plans_sync_now'))}</button><button id="unpair" class="danger" hidden>${esc(t('web_unpair'))}</button></div>
    <button id="howToggle" class="textbtn quiet howtoggle" aria-expanded="false" aria-controls="howBox">${esc(t('web_phone_how_link'))}</button>
    <div id="howBox" class="howbox" hidden>
      <ul class="how"><li>${esc(t('web_phone_why'))}</li><li>${esc(t('web_phone_how_2'))}</li><li>${esc(t('web_phone_how_3'))}</li></ul>
      <p class="muted small privacy">${esc(t('web_phone_privacy'))} <a href="${esc(t('web_url_privacy'))}" target="_blank" rel="noopener">${esc(t('web_phone_more'))}</a></p>
    </div>
    <div id="phoneMore" class="phonemore"><button id="pairOther" class="textbtn quiet" hidden>${esc(t('web_pair_other'))}</button></div>
    <div class="row end"><button id="closePhone">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  const render = () => {
    const s = sync.status, st = phoneState(s);
    $('phoneState').dataset.kind = st.kind;
    $('phoneHead').textContent = st.head;
    $('phoneWhy').textContent = st.why;
    $('phoneStatus').textContent = s.message;
    $('phoneStatus').classList.toggle('error', s.kind === 'error' && !!s.message);
    $('phoneStatus').classList.toggle('warning', s.kind === 'warning' && !!s.message);
    $('phoneStatus').classList.toggle('muted', !s.message || s.kind === 'info');
    $('syncNow').hidden = !s.paired;
    $('unpair').hidden = !s.paired;
    $('pairPhone').hidden = s.paired;
    $('pairOther').hidden = !s.paired;
    removeLines($('phoneMore'), sync);
    $('phoneMore').hidden = !s.paired && !s.closed.length;
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  const tick = window.setInterval(render, 30_000);
  d.addEventListener('close', () => { off(); clearInterval(tick); });
  $('howToggle').onclick = () => {
    const open = $('howBox').hidden;
    $('howBox').hidden = !open;
    $('howToggle').setAttribute('aria-expanded', String(open));
  };
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

/** A name as stored: a copy keeps its marker (SyncMerge.storedTitle) – "… (Kopie)" typed back is " (copy)" in the item. */
function storedTitle(name: string): string {
  const label = t('web_copy_suffix').trim(), n = name.trim();
  return n.endsWith(label) ? n.slice(0, -label.length).trimEnd() + ' (copy)' : n;
}

let savePop: { el: HTMLElement; close(): void } | null = null;
/** Closes the Save field if it is open; true when it was. */
export function closeSavePop(): boolean {
  if (!savePop) return false;
  savePop.close();
  return true;
}

/**
 * Save "as in Word" (web-sync.md §14.6 rule 5): a small field right under the Save button (no dialog, never a question
 * "overwrite or new"): the name – the open saved route's, else the app's proposal Start → Destination (RouteNamer) –
 * selected, and one button [label]. Enter saves, Escape or a click beside it cancels. The same name overwrites the open
 * saved route; a new route, or a saved one under a changed name ("Save as"), becomes a new route (the old one stays) and
 * is the open route from then on. [then] runs after the save (paired: everything waiting goes to the phone).
 */
export async function savePopover(anchor: HTMLElement, sync: PlanSync, host: PlanHost, label: string, then?: () => void) {
  closeSavePop();
  const cur = await host.current();
  if (!cur) { then?.(); return; }
  const id = host.openId(), folder = host.openFolder();
  const existing = id ? (await sync.list(folder)).find((p) => p.id === id && p.kind === 'plan') : undefined;
  const fallback = t('web_plan_dated', when(Date.now()));
  const el = document.createElement('div');
  el.className = 'savepop';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', label);
  el.innerHTML = `<label class="muted small" for="savePopName">${esc(t('routes_name_label'))}</label>
    <div class="savepoprow"><input id="savePopName" maxlength="100" placeholder="${esc(fallback)}" value="${esc(existing ? titleOf(existing) : cur.title ?? '')}">
    <button id="savePopGo" class="primary">${esc(label)}</button></div>`;
  document.body.append(el);
  const r = anchor.getBoundingClientRect(), w = el.offsetWidth;
  el.style.top = r.bottom + 6 + 'px';
  el.style.left = Math.max(8, Math.min(r.right - w, innerWidth - w - 8)) + 'px';
  const input = el.querySelector<HTMLInputElement>('#savePopName')!;
  let typed = !!(existing || cur.title), busy = false;
  input.addEventListener('input', () => { typed = true; });
  input.focus();
  input.select();
  if (!typed) host.suggestName().then((n) => { if (n && !typed && el.isConnected) { input.value = n; input.select(); } });
  const outside = (e: PointerEvent) => { if (!el.contains(e.target as Node) && !anchor.contains(e.target as Node)) close(); };
  const close = () => {
    document.removeEventListener('pointerdown', outside, true);
    el.remove();
    if (savePop?.el === el) savePop = null;
  };
  setTimeout(() => document.addEventListener('pointerdown', outside, true)); // not the click that opened it
  savePop = { el, close };
  const go = async () => {
    if (busy) return; // one click, one route (owner 2026-10-28: saving made duplicates)
    busy = true;
    const name = input.value.trim() || fallback;
    close();
    anchor.focus();
    if (existing && name === titleOf(existing)) { // the same name: a new version of the open saved route
      await sync.save(cur.code, existing.title, existing.id, folder);
      host.saved({ ...existing, plan: cur.code, updatedAt: Date.now(), mine: true }, folder);
    } else { // a new route, or "Save as" under a new name – the old one stays as it is
      const title = storedTitle(name), into = sync.open;
      const newId = await sync.save(cur.code, title, undefined, into);
      host.saved({ id: newId, kind: 'plan', title, plan: cur.code, track: '', updatedAt: Date.now(), device: '', mine: true, dirty: true, onPhone: false }, into);
    }
    then?.();
  };
  el.querySelector<HTMLButtonElement>('#savePopGo')!.onclick = go;
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); go(); }
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); anchor.focus(); }
  });
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
