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

/**
 * My routes: the saved routes, and at the bottom "Pair with phone" (owner 2026-10-30 – pairing lives here, not in
 * the header): not paired → Pair phone (the QR code + check digits, [pair]); paired → "Paired with …", Sync now, Unpair.
 */
export async function myPlansDialog(sync: PlanSync, host: PlanHost, pair: () => void, atSync = false) {
  const save = await host.saveState(); // before the dialog shows: its Save button is right from the start
  const d = dialog('plans', `
    <h2>${esc(t('routes_title'))}</h2>
    <h3 id="planHead" class="listhead">${icon('route', 20)}${esc(t('web_plans_heading'))}</h3>
    <button id="saveCurrent" class="outline">${icon('save', 18)}${esc(t('plan_save_current_route'))}</button>
    <ul id="planList" class="planlist" aria-labelledby="planHead"></ul>
    <section class="syncsec" aria-labelledby="syncTitle">
      <h3 id="syncTitle">${icon('sync', 20)}${esc(t('web_plans_sync_title'))}</h3>
      <div id="plansStatus" class="status muted"></div>
      <div class="row" id="syncRow"><button id="pairPhone" hidden>${esc(t('web_pair_phone'))}</button><button id="syncNow" hidden>${esc(t('web_plans_sync_now'))}</button><button id="unpair" class="danger" hidden>${esc(t('web_unpair'))}</button></div>
    </section>
    <div class="row end"><button id="closePlans">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  // only planned routes sync (no tracks) – the heading says so. Save: greyed on an empty route, ✓ while saved and
  // unchanged (a click still asks Save again / Save as new – the app's saveMode)
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
    if (!sync.status.paired) d.querySelector<AskBox>('.syncsec .confirm')?.cancel(); // ended meanwhile: nothing to ask
    if (ul.querySelector('.confirm')) return syncRender(); // a delete question is open: keep the list as it is
    ul.innerHTML = plans.length ? '' : `<li class="muted">${esc(t('web_plans_none'))}</li>`;
    for (const p of plans) {
      const li = document.createElement('li');
      li.dataset.id = p.id;
      li.innerHTML = `<button class="open txt" title="${esc(t('web_plans_open'))}"><span class="name">${esc(p.title ?? t('plan_route_default_name'))}</span>
        <span class="muted small">${when(p.updatedAt)}${p.mine ? '' : ' · ' + esc(t('web_plans_from_other'))}${p.id === host.openId() ? ' · ' + esc(t('web_plans_shown_now')) : ''}</span></button>
        <button class="rename iconbtn" aria-label="${esc(t('common_rename'))}">✎</button><button class="del iconbtn" aria-label="${esc(t('common_delete'))}">✕</button>`;
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
    syncRender();
  };
  const syncRender = () => {
    const line = syncLine(sync.status);
    const st = $('plansStatus');
    st.textContent = line.text;
    st.classList.toggle('error', line.kind === 'error');
    st.classList.toggle('warning', line.kind === 'warning');
    $('syncNow').hidden = !sync.status.paired;
    $('unpair').hidden = !sync.status.paired;
    $('pairPhone').hidden = sync.status.paired;
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  d.addEventListener('close', () => off());
  $('syncNow').onclick = () => sync.syncNow();
  $('pairPhone').onclick = () => pair(); // the pairing dialog takes this one's place
  // the question takes the place of "Sync now / Unpair" (owner 2026-10-30: one "Unpair" at a time, above Close)
  $('unpair').onclick = () => askInPlace($('syncRow'), t('web_unpair_q'), t('web_unpair'), () => $('unpair'), async () => {
    const err = await sync.unpair();
    await render();
    $('plansStatus').textContent = err ?? t('web_sync_unpaired');
    $('plansStatus').classList.toggle('error', !!err);
    d.querySelector<HTMLButtonElement>('.syncsec button:not([hidden])')?.focus();
  });
  $('closePlans').onclick = () => d.close();
  await render();
  if (atSync) { // from the header's paired status: straight to "Pair with phone"
    const sec = d.querySelector<HTMLElement>('.syncsec')!;
    sec.scrollIntoView({ block: 'nearest' });
    sec.querySelector<HTMLButtonElement>('button:not([hidden])')?.focus();
  }
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

export async function pairDialog(sync: PlanSync, page: string) {
  const d = dialog('pair', `<h2>${esc(t('web_pair_phone'))}</h2><div id="pairBody"><p class="muted">${esc(t('web_pair_preparing'))}</p></div>
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
    if (s.paired && s.devices.length) {
      clearInterval(poll);
      $('pairBody').innerHTML = `<p>${t('web_pair_done', `<b>${esc(s.devices.map((x) => x.name).join(', '))}</b>`)}</p>
        <p class="muted small">${esc(t('web_pair_digits', s.check ?? ''))}</p>`;
      if (!$('pairButtons').querySelector('#unpair')) {
        const b = document.createElement('button');
        b.id = 'unpair'; b.className = 'danger'; b.textContent = t('web_unpair');
        b.onclick = () => askInPlace($('pairButtons'), t('web_unpair_q'), t('web_unpair'), () => b, async () => {
          const err = await sync.unpair();
          $('pairStatus').textContent = err ?? t('web_sync_unpaired');
          if (!err) { $('pairBody').innerHTML = `<p>${esc(t('web_pair_not_paired'))}</p>`; b.remove(); }
          $('closePair').focus();
        });
        $('pairButtons').prepend(b);
      }
    }
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  d.addEventListener('close', () => off());
  if (sync.status.paired && sync.status.devices.length) render(); else { await showCode(); render(); }
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
