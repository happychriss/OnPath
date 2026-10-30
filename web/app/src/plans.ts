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

export async function myPlansDialog(sync: PlanSync, host: PlanHost) {
  const d = dialog('plans', `
    <h2>${esc(t('web_nav_my_plans'))}</h2>
    <button id="saveCurrent" class="outline">${icon('save', 18)}${esc(t('web_plans_save_current'))}</button>
    <ul id="planList" class="planlist" aria-label="${esc(t('web_plans_saved'))}"></ul>
    <div id="plansStatus" class="status muted"></div>
    <div class="row end"><button id="syncNow" hidden>${esc(t('web_plans_sync_now'))}</button><button id="closePlans">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  const cur = await host.current();
  $<HTMLButtonElement>('saveCurrent').disabled = !cur;
  if (!cur) $('saveCurrent').title = t('plan_err_set_start_dest');
  $('saveCurrent').onclick = () => { d.close(); requestSave(sync, host); };

  const render = async () => {
    const plans = await sync.list();
    const ul = $('planList');
    ul.innerHTML = plans.length ? '' : `<li class="muted">${esc(t('web_plans_none'))}</li>`;
    for (const p of plans) {
      const li = document.createElement('li');
      li.dataset.id = p.id;
      li.innerHTML = `<button class="open txt" title="${esc(t('web_plans_open'))}"><span class="name">${esc(p.title ?? t('web_plan_default'))}</span>
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
      li.querySelector<HTMLButtonElement>('.del')!.onclick = () => confirmDelete(d, p, async () => { await sync.remove(p.id); render(); });
      ul.append(li);
    }
    const line = syncLine(sync.status);
    const st = $('plansStatus');
    st.textContent = line.text;
    st.classList.toggle('error', line.kind === 'error');
    st.classList.toggle('warning', line.kind === 'warning');
    $('syncNow').hidden = !sync.status.paired;
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  d.addEventListener('close', () => off());
  $('syncNow').onclick = () => sync.syncNow();
  $('closePlans').onclick = () => d.close();
  render();
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
  const d = dialog('savechoice', `<h2>${esc(t('web_save_q', existing.title ?? t('web_plan_default')))}</h2>
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
  const d = dialog('savename', `<h2>${esc(t('web_save_plan'))}</h2>
    <label class="muted small" for="saveTitle">${esc(t('web_save_name'))}</label>
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

/** Deleting loses something – one question, verb buttons. */
function confirmDelete(parent: HTMLDialogElement, p: SavedPlan, del: () => void) {
  const box = document.createElement('div');
  box.className = 'confirm';
  box.innerHTML = `<p>${esc(t('web_delete_q', p.title ?? t('web_plan_default')))}</p>
    <div class="row end"><button class="keep">${esc(t('web_keep'))}</button><button class="danger primary">${esc(t('common_delete'))}</button></div>`;
  parent.append(box);
  box.querySelector<HTMLButtonElement>('.keep')!.onclick = () => box.remove();
  box.querySelector<HTMLButtonElement>('.danger')!.onclick = () => { box.remove(); del(); };
}

export async function pairDialog(sync: PlanSync, page: string) {
  const d = dialog('pair', `<h2>${esc(t('web_nav_pair'))}</h2><div id="pairBody"><p class="muted">${esc(t('web_pair_preparing'))}</p></div>
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
        b.onclick = () => confirmUnpair(d, async () => {
          const err = await sync.unpair();
          $('pairStatus').textContent = err ?? t('web_sync_unpaired');
          if (!err) { $('pairBody').innerHTML = `<p>${esc(t('web_pair_not_paired'))}</p>`; b.remove(); }
        });
        $('pairButtons').prepend(b);
      }
    }
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  d.addEventListener('close', () => off());
  if (sync.status.paired && sync.status.devices.length) render(); else { await showCode(); render(); }
}

function confirmUnpair(parent: HTMLDialogElement, unpair: () => void) {
  const box = document.createElement('div');
  box.className = 'confirm';
  box.innerHTML = `<p>${esc(t('web_unpair_q'))}</p>
    <div class="row end"><button class="keep">${esc(t('web_stay_paired'))}</button><button class="danger primary">${esc(t('web_unpair'))}</button></div>`;
  parent.append(box);
  box.querySelector<HTMLButtonElement>('.keep')!.onclick = () => box.remove();
  box.querySelector<HTMLButtonElement>('.danger')!.onclick = () => { box.remove(); unpair(); };
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
