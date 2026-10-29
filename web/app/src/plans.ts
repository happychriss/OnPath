// "My plans" and "Pair phone" (web planner phase 2, docs/design/web-sync.md §9). Saved plans live in this browser and
// – once paired – also on the phone, end-to-end encrypted through the mailbox. Conflict copies show as "… (copy)".
import qrcode from 'qrcode-generator';
import type { PlanSync, SavedPlan, SyncStatus } from './sync';
import { icon } from './icons';

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
const when = (ms: number) => new Date(ms).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

/** The sync line under the lists: paired with whom, last sync, or what went wrong (plain words). */
export function syncLine(s: SyncStatus): { text: string; kind: SyncStatus['kind'] } {
  if (s.message) return { text: s.message, kind: s.kind };
  if (!s.paired) return { text: 'Saved in this browser only. Pair your phone to have them there too.', kind: 'info' };
  const who = s.devices.length ? s.devices.map((d) => d.name).join(', ') : 'no phone yet';
  const last = s.lastSync ? ` · synced ${new Date(s.lastSync).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}` : '';
  const pending = s.pending ? ` · ${s.pending} to send` : '';
  return { text: `Paired with ${who}${last}${pending}`, kind: 'info' };
}

export async function myPlansDialog(sync: PlanSync, host: PlanHost) {
  const d = dialog('plans', `
    <h2>My plans</h2>
    <button id="saveCurrent" class="outline">${icon('save', 18)}Save the current plan…</button>
    <ul id="planList" class="planlist" aria-label="Saved plans"></ul>
    <div id="plansStatus" class="status muted"></div>
    <div class="row end"><button id="syncNow" hidden>Sync now</button><button id="closePlans">Close</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  const cur = await host.current();
  $<HTMLButtonElement>('saveCurrent').disabled = !cur;
  if (!cur) $('saveCurrent').title = 'Set a start and a destination first';
  $('saveCurrent').onclick = () => { d.close(); requestSave(sync, host); };

  const render = async () => {
    const plans = await sync.list();
    const ul = $('planList');
    ul.innerHTML = plans.length ? '' : '<li class="muted">No saved plans yet.</li>';
    for (const p of plans) {
      const li = document.createElement('li');
      li.dataset.id = p.id;
      li.innerHTML = `<button class="open txt" title="Open this plan"><span class="name">${esc(p.title ?? 'Plan')}</span>
        <span class="muted small">${when(p.updatedAt)}${p.mine ? '' : ' · from the other device'}${p.id === host.openId() ? ' · shown now' : ''}</span></button>
        <button class="rename iconbtn" aria-label="Rename">✎</button><button class="del iconbtn" aria-label="Delete">✕</button>`;
      li.querySelector<HTMLButtonElement>('.open')!.onclick = () => { d.close(); host.open(p); };
      li.querySelector<HTMLButtonElement>('.rename')!.onclick = async () => {
        const t = prompt('New name of the plan', p.title ?? '');
        if (t != null && t.trim()) {
          await sync.rename(p.id, t.trim());
          if (p.id === host.openId()) host.saved({ ...p, title: t.trim() }); // "Saved as …" follows the new name
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
  const d = dialog('savechoice', `<h2>Save “${esc(existing.title ?? 'Plan')}”?</h2>
    <p class="muted">Save replaces the saved plan with this one. Save as new keeps it and adds this plan.</p>
    <div class="row end"><button id="saveAsNew">Save as new…</button><button id="saveOver" class="primary">Save</button></div>`);
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
  const fallback = 'Plan ' + when(Date.now());
  const d = dialog('savename', `<h2>Save plan</h2>
    <label class="muted small" for="saveTitle">Name</label>
    <input id="saveTitle" maxlength="100" placeholder="${esc(fallback)}" value="${esc(cur.title ?? '')}">
    <div class="row end"><button id="saveCancel">Cancel</button><button id="savePlan" class="primary">Save</button></div>`);
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
  box.innerHTML = `<p>Delete “${esc(p.title ?? 'Plan')}”? It is also deleted on the paired phone.</p>
    <div class="row end"><button class="keep">Keep</button><button class="danger primary">Delete</button></div>`;
  parent.append(box);
  box.querySelector<HTMLButtonElement>('.keep')!.onclick = () => box.remove();
  box.querySelector<HTMLButtonElement>('.danger')!.onclick = () => { box.remove(); del(); };
}

export async function pairDialog(sync: PlanSync, page: string) {
  const d = dialog('pair', `<h2>Pair phone</h2><div id="pairBody"><p class="muted">Preparing…</p></div>
    <div id="pairStatus" class="status muted"></div>
    <div class="row end" id="pairButtons"><button id="closePair">Close</button></div>`);
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
      <div class="qr" role="img" aria-label="QR code for pairing">${qr.createSvgTag({ scalable: true, margin: 2 })}</div>
      <p>In the OnPath app: <b>☰ → Pair with browser</b>, then scan this code.</p>
      <p class="check">Check: <b id="checkDigits">${esc(p.check)}</b> – the app shows the same digits.</p>
      <p class="muted small">Your plans are encrypted on this browser and on the phone. The sync server only passes
        on locked data – it cannot read your plans. Keep this code to yourself: whoever scans it sees your saved plans.</p>
      <input id="pairLink" readonly aria-label="Pairing link" value="${esc(p.link)}">`;
    // until the phone said hello: sync every 3 s (the app scans, then writes its name into the mailbox)
    clearInterval(poll);
    poll = window.setInterval(() => sync.syncNow(), 3000);
  };

  const render = async () => {
    const s = sync.status;
    const line = syncLine(s);
    $('pairStatus').textContent = s.paired && !s.devices.length && !s.message ? 'Waiting for the phone…' : line.text;
    $('pairStatus').classList.toggle('error', line.kind === 'error');
    if (s.paired && s.devices.length) {
      clearInterval(poll);
      $('pairBody').innerHTML = `<p>Paired with <b>${esc(s.devices.map((x) => x.name).join(', '))}</b>. Saved plans
        sync both ways by themselves while this page is open.</p>
        <p class="muted small">Check digits: ${esc(s.check ?? '')}</p>`;
      if (!$('pairButtons').querySelector('#unpair')) {
        const b = document.createElement('button');
        b.id = 'unpair'; b.className = 'danger'; b.textContent = 'Unpair';
        b.onclick = () => confirmUnpair(d, async () => {
          const err = await sync.unpair();
          $('pairStatus').textContent = err ?? 'Unpaired. Your plans stay in this browser.';
          if (!err) { $('pairBody').innerHTML = '<p>This browser is not paired.</p>'; b.remove(); }
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
  box.innerHTML = `<p>Unpair? Plans stop syncing; they stay on this browser and on the phone.</p>
    <div class="row end"><button class="keep">Stay paired</button><button class="danger primary">Unpair</button></div>`;
  parent.append(box);
  box.querySelector<HTMLButtonElement>('.keep')!.onclick = () => box.remove();
  box.querySelector<HTMLButtonElement>('.danger')!.onclick = () => { box.remove(); unpair(); };
}

/** A pairing link opened in a browser (normally the phone app scans it): pair this browser instead? */
export function joinDialog(sync: PlanSync, link: string) {
  const d = dialog('join', `<h2>Pairing code</h2>
    <p>This link pairs a device with your saved plans. Usually you scan it with the <b>OnPath app</b> on your phone
      (☰ → Pair with browser).</p>
    <p class="muted small">You can also pair this browser instead – then your saved plans sync here too.</p>
    <div id="joinStatus" class="status"></div>
    <div class="row end"><button id="joinCancel">Cancel</button><button id="joinOk" class="primary">Pair this browser</button></div>`);
  d.querySelector<HTMLButtonElement>('#joinCancel')!.onclick = () => d.close();
  d.querySelector<HTMLButtonElement>('#joinOk')!.onclick = async () => {
    const err = await sync.join(link);
    const st = d.querySelector('#joinStatus')!;
    if (err) { st.textContent = err; st.classList.add('error'); return; }
    st.textContent = `Paired · check digits ${sync.status.check ?? ''}`;
    d.querySelector<HTMLButtonElement>('#joinOk')!.remove();
    d.querySelector<HTMLButtonElement>('#joinCancel')!.textContent = 'Close';
  };
}
