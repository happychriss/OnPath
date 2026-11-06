// "My routes" and the Phone window (web planner phase 2, docs/design/web-sync.md §9, §14; what they show: §14.6). Saved
// routes live in this browser in folders – one per paired phone, plus "Without phone" (owner order A-017) – and, after
// Save, on the phone, end-to-end encrypted through the mailbox. Conflict copies show as "… (copy)" / "… (Kopie)".
import qrcode from 'qrcode-generator';
import { NONE, shownTitle, type PlanSync, type SavedPlan, type SyncStatus } from './sync';
import { icon } from './icons';
import { t, locale } from './i18n';
import { problem } from './diag';
import { planKey, trackKey, statsOf, statsText } from './planstats';
import { recentPlans, dropRecent, SHOWN as RECENT_SHOWN, type RecentPlan } from './recent';

export interface PlanHost {
  /** The current plan as a plan code + its title, or null when there is nothing to save. */
  current(): Promise<{ code: string; title: string | null } | null>;
  /** The saved plan the page shows now (to save a new version of it), or null. */
  openId(): string | null;
  /** Its stored plan code when it was opened / last saved here (sync/SaveRule: changed meanwhile → save as a copy). */
  openedCode(): string | null;
  /** One of the app's shared rules in the engine (route names, SaveRule – the worker's CALLS). */
  engineCall(fn: string, ...args: (string | number)[]): Promise<string>;
  /** A calm line where the user acted (the sheet's status line). */
  note(text: string): void;
  /** The folder the shown saved route belongs to (the open folder for a new route). */
  openFolder(): string;
  /** Shows [plan] from [folder] (a route, or a synced GPX track). */
  open(plan: SavedPlan, folder: string): void;
  /** A name proposal for a new saved plan ("Algund → Meran", the app's RouteNamer), "" when none fits. */
  suggestName(): Promise<string>;
  /** The plan link's title while the plan still has the link's points (the app's linkTitle), else null. */
  linkTitle(): string | null;
  /** The plan was saved as [p] in [folder] – the page shows "Saved as …". */
  saved(p: SavedPlan, folder: string): void;
  /** My routes → ⋮ → Export GPX: [p] from [folder] as a GPX file (a route is opened and planned first). */
  exportGpx(p: SavedPlan, folder: string): void;
  /** My routes → ⋮ → Share plan: [p]'s link and QR code (the sheet's Share dialog). */
  share(p: SavedPlan): void;
  /** Öffnen → GPX importieren: the file picker (gpximport.ts). */
  importGpx(): void;
  /** Meine Touren → Zuletzt → a click: that plan on the map (recent.ts). */
  openRecent(r: RecentPlan): void;
  /** The saved plan just made from Zuletzt (shown „✓ Gerade gespeichert“ once), null = none. */
  justSaved(): string | null;
  setJustSaved(id: string): void;
}

let openDialog: HTMLDialogElement | null = null;

/**
 * A failed write in My routes (IndexedDB quota, a storage reset, Safari private mode – review 2026-11-01, web M11): one
 * red line at the top of the list where the user acted, the detail for the diagnostics. Never silent.
 */
function failLine(d: HTMLElement, text: string, e: unknown) {
  problem('sync', text, e, 'error');
  let el = d.querySelector<HTMLElement>('.failline');
  if (!el) {
    el = document.createElement('p');
    el.className = 'notice failline'; el.setAttribute('role', 'alert');
    d.querySelector('#planList')?.before(el);
  }
  el.textContent = '⚠ ' + text;
}

/**
 * Asks the browser once to keep this page's storage (review 2026-11-01, web H5): without it Safari may clear routes
 * kept only here after 7 days without a visit, and other browsers under disk pressure. Asked on the first Save.
 */
let persistAsked = false;
function askPersist() {
  if (persistAsked) return;
  persistAsked = true;
  navigator.storage?.persist?.().then((ok) => { if (!ok) problem('sync', 'the browser did not grant persistent storage (routes may be cleared by it)'); },
    (e) => problem('sync', 'persistent storage not asked', e));
}

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
/** A date as the app writes it in My routes (its common_date pattern: "30. Okt. 2026" / "30 Oct 2026"). */
/**
 * The short month as it reads inside a date, like the app's SimpleDateFormat "MMM" ("Okt." with its dot in German –
 * Intl's month alone gives the standalone "Okt"; owner 2026-10-31).
 */
export function shortMonth(d: Date): string {
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', year: 'numeric' }).formatToParts(d).find((p) => p.type === 'month')?.value ?? '';
}

export function appDate(ms: number): string {
  const d = new Date(ms), month = shortMonth(d);
  return t('common_date').replace(/yyyy|MMM|d/g, (k) => (k === 'yyyy' ? String(d.getFullYear()) : k === 'MMM' ? month : String(d.getDate())));
}
const titleOf = (p: { title: string | null }) => shownTitle(p.title, t('web_route_default_name'));

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

/** Lower case without accents, ß → ss – the app's RoutesTab.fold ("koln" finds "Köln", "strasse" finds "Straße"). */
export const foldName = (s: string) => s.toLowerCase().normalize('NFD').replace(/\p{Mn}+/gu, '').replace(/ß/g, 'ss');
/** The app's RoutesTab.search: every word of [query] is in the name; blank = everything (the list's order kept). */
export function searchPlans<T extends { title: string | null }>(plans: T[], query: string): T[] {
  const words = foldName(query).split(' ').filter((w) => w.trim());
  if (!words.length) return plans;
  return plans.filter((p) => { const n = foldName(titleOf(p)); return words.every((w) => n.includes(w)); });
}

/**
 * My routes – one plain list (web-sync.md §14.6): title, the rows, Close. A row: the kind in a circle as in the app
 * (planned route blue, imported GPX purple), the name, "Planned · 30 Oct 2026" / "GPX · …"; on the right where it lies
 * (🖥 this browser, 📱 the phone, both) and ⋮ with Rename, Save as copy, Export GPX, Share plan, Delete (copy and share
 * for planned routes – the app's TourItemMenu). Bold = changed here and not on the phone
 * yet; the open route on a light green. A click on the row opens it. Follows the sync live.
 */
export async function myPlansDialog(sync: PlanSync, host: PlanHost) {
  const d = dialog('plans', `
    <h2 id="plansTitle">${esc(t('routes_title'))}</h2>
    <label class="plansearch" hidden><span class="sic">${icon('search', 20)}</span><input id="planSearch" type="search" autocomplete="off"
      placeholder="${esc(t('routes_search_hint'))}" aria-label="${esc(t('routes_search_hint'))}"><button id="planSearchClear" class="iconbtn" hidden
      aria-label="${esc(t('search_clear_query'))}" title="${esc(t('search_clear_query'))}">${icon('close', 20)}</button></label>
    <ul id="planList" class="planlist" aria-labelledby="plansTitle"></ul>
    <div class="row spread"><button id="plansImportGpx" class="textbtn">${icon('fileOpen', 18)}${esc(t('routes_import_gpx'))}</button><button id="closePlans">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  const closeMenus = () => d.querySelectorAll<HTMLElement>('.rowmenu').forEach((m) => { m.hidden = true; m.previousElementSibling?.setAttribute('aria-expanded', 'false'); });
  d.addEventListener('click', (e) => { if (!(e.target as HTMLElement).closest('.rowmenu, .more')) closeMenus(); });
  $('planList').addEventListener('scroll', closeMenus); // the menu stays fixed: it goes when its row moves

  // the app's search box of Meine Touren (RoutesTab.search, owner 2026-11-06): shown once there is anything to search
  let query = '';
  const search = $<HTMLInputElement>('planSearch');
  search.addEventListener('input', () => { query = search.value; $('planSearchClear').hidden = !query; render(); });
  $('planSearchClear').onclick = (e) => { e.preventDefault(); search.value = ''; query = ''; $('planSearchClear').hidden = true; search.focus(); render(); };
  $('plansImportGpx').onclick = () => { d.close(); host.importGpx(); };
  let allRecent = false;
  const justSaved = host.justSaved();
  const sectionHead = (text: string) => Object.assign(document.createElement('li'), { className: 'sectionhead', role: 'heading', textContent: text.toUpperCase() });
  /** A plan under Zuletzt: the route sign pale (only a draft), the words as a saved one's; Speichern (blue) · Löschen (red, no question) on it. */
  const recentRow = (r: RecentPlan) => {
    const li = document.createElement('li');
    li.className = 'recent'; li.dataset.id = r.id;
    const s = statsOf(planKey(r.code));
    li.innerHTML = `<button class="open" title="${esc(t('web_plans_open'))}"><span class="kindic pale">${icon('route', 22)}</span>
      <span class="txt"><span class="nameline"><span class="name">${esc(r.title)}</span></span>${s ? `<span class="stats">${esc(statsText(s))}</span>` : ''}<span class="muted small sub">${esc(t('web_kind_route') + ' · ' + appDate(r.at))}</span></span></button>
      <div class="recentacts"><button class="rsave">${icon('save', 18)}${esc(t('common_save'))}</button><button class="rdel">${icon('delete', 18)}${esc(t('common_delete'))}</button></div>`;
    li.querySelector<HTMLButtonElement>('.open')!.onclick = () => { d.close(); host.openRecent(r); };
    li.querySelector<HTMLButtonElement>('.rsave')!.onclick = async () => {
      const into = sync.open;
      if (!(await roomFor(sync, host, into))) return;
      let id: string;
      try { id = await sync.save(r.code, storedTitle(r.title), undefined, into); } catch (e) { failLine(d, t('web_save_failed'), e); return; }
      askPersist();
      dropRecent(r.id);
      host.setJustSaved(id);
      await myPlansDialog(sync, host);
    };
    li.querySelector<HTMLButtonElement>('.rdel')!.onclick = () => { dropRecent(r.id); render(); };
    return li;
  };
  const render = async () => {
    const folder = sync.open, paired = sync.status.paired;
    const all = await sync.list(folder);
    const plans = searchPlans(all, query);
    // Zuletzt (the app's drafts, owner 2026-11-06): the plans kept by themselves on top – the newest three, the rest on
    // demand – then the saved ones under „Gespeichert“; searched like the rest (the app's RoutesTab.search)
    const recentAll = recentPlans();
    const recent = searchPlans(recentAll, query);
    const ul = $('planList');
    if (ul.querySelector('.confirm')) return; // a delete question is open: keep the list as it is
    search.closest<HTMLElement>('.plansearch')!.hidden = !all.length && !recentAll.length;
    ul.innerHTML = plans.length || recent.length ? '' : `<li class="muted">${esc((all.length || recentAll.length) && query.trim() ? t('routes_search_none', query.trim()) : t('web_plans_none'))}</li>`;
    if (!all.length && !recentAll.length) await oldPhoneLine(ul);
    if (recent.length) {
      ul.append(sectionHead(t('routes_section_recent')));
      for (const r of allRecent ? recent : recent.slice(0, RECENT_SHOWN)) ul.append(recentRow(r));
      if (!allRecent && recent.length > RECENT_SHOWN) {
        const li = document.createElement('li'); li.className = 'allrecent';
        li.innerHTML = `<button class="textbtn">${esc(t('routes_recent_all', recent.length))}</button>`;
        li.querySelector('button')!.onclick = () => { allRecent = true; render(); };
        ul.append(li);
      }
      if (plans.length) ul.append(sectionHead(t('routes_section_saved')));
    }
    for (const p of plans) {
      const li = document.createElement('li');
      li.dataset.id = p.id;
      li.dataset.kind = p.kind;
      // 🖥 = changed last here (all of Without phone), 📱 = a version of it is on the phone (in its mailbox)
      const here = !paired || p.mine, phone = paired && p.onPhone;
      li.dataset.where = [here ? 'browser' : '', phone ? 'phone' : ''].filter(Boolean).join('+');
      if (paired && p.dirty) li.classList.add('waiting');
      if (p.id === host.openId() && folder === host.openFolder()) li.classList.add('current');
      if (p.id === justSaved) li.classList.add('justsaved');
      const kind = p.kind === 'track' ? t('web_kind_gpx') : t('web_kind_route');
      const kindName = p.kind === 'track' ? t('routes_kind_imported') : t('routes_kind_planned');
      const where = (cls: string, key: string, ic: 'monitor' | 'phone') =>
        `<span class="w ${cls}" role="img" title="${esc(t(key))}" aria-label="${esc(t(key))}">${icon(ic, 18)}</span>`;
      li.innerHTML = `<button class="open" title="${esc(t('web_plans_open'))}"><span class="kindic" title="${esc(kindName)}">${icon(p.kind === 'track' ? 'fileOpen' : 'route', 22)}</span>
        <span class="txt"><span class="nameline"><span class="name">${esc(titleOf(p))}</span>${tagOf(p)}</span>${statsLine(p)}<span class="muted small sub">${esc(kind + ' · ' + appDate(p.updatedAt))}</span>${p.id === justSaved ? `<span class="justsavedline">${esc(t('routes_just_saved'))}</span>` : ''}</span></button>
        <span class="where">${here ? where('browser', 'web_where_browser', 'monitor') : ''}${phone ? where('phone', 'web_where_phone', 'phone') : ''}</span>
        <button class="more iconbtn" aria-haspopup="menu" aria-expanded="false" aria-label="${esc(t('web_route_options'))}" title="${esc(t('web_route_options'))}">${icon('moreVert', 22)}</button>
        <div class="menu rowmenu" role="menu" hidden><button class="rename" role="menuitem">${esc(t('common_rename'))}</button>${p.kind === 'plan' ? `<button class="copy" role="menuitem">${esc(t('plan_save_as_copy'))}</button>` : ''}<button class="export" role="menuitem">${esc(t('common_export_gpx'))}</button>${p.kind === 'plan' ? `<button class="sharerow" role="menuitem">${esc(t('plan_share_plan'))}</button>` : ''}<button class="del" role="menuitem">${esc(t('common_delete'))}</button></div>`;
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
        try { await sync.rename(p.id, stored, folder); } catch (e) { failLine(d, t('web_rename_failed'), e); return; }
        if (p.id === host.openId()) host.saved({ ...p, title: stored }, folder); // "Saved as …" follows the new name
        render();
      };
      li.querySelector<HTMLButtonElement>('.copy')?.addEventListener('click', async () => {
        closeMenus();
        let failed: unknown = null;
        try { await copyRoute(sync, host, p, folder); } catch (e) { failed = e; } // the name dialog takes My routes' place …
        await myPlansDialog(sync, host); // … and My routes comes back, with the copy in it
        if (failed && openDialog) failLine(openDialog, t('web_copy_failed'), failed);
      });
      q('.export').onclick = () => { closeMenus(); d.close(); host.exportGpx(p, folder); };
      li.querySelector<HTMLButtonElement>('.sharerow')?.addEventListener('click', () => { closeMenus(); d.close(); host.share(p); });
      q('.del').onclick = async () => {
        closeMenus();
        if (folder !== NONE) { // on the phone: its recycle bin is the net – no question, as the app (owner 2026-11-06)
          try { await sync.remove(p.id, folder); } catch (e) { await render(); failLine(d, t('web_delete_failed'), e); return; }
          await render(); $('closePlans').focus(); return;
        }
        askInPlace(li, t('web_delete_q_local', titleOf(p)),
          t('common_delete'), () => li.querySelector('.more'), async () => {
            try { await sync.remove(p.id, folder); } catch (e) { await render(); failLine(d, t('web_delete_failed'), e); return; }
            await render(); $('closePlans').focus();
          }, t('web_keep'));
      };
      ul.append(li);
    }
  };
  /**
   * The open folder is empty but closed folders still hold routes (after Unpair / pairing another phone, owner
   * 2026-11-02): a calm line instead of only "No saved routes yet" – they are still here, the Phone window lists them.
   */
  const oldPhoneLine = async (ul: HTMLElement) => {
    const names: string[] = [];
    for (const f of sync.status.closed) if (f.key !== sync.open && (await sync.count(f.key)) > 0) names.push(f.name);
    if (!names.length) return;
    ul.innerHTML = `<li class="muted oldphone"><span>${esc(t('web_plans_old_phone', names.join(', ')))}</span>
      <button class="textbtn openphone">${esc(t('web_plans_old_phone_open'))}</button></li>`;
    ul.querySelector<HTMLButtonElement>('.openphone')!.onclick = () => { d.close(); phoneDialog(sync, location.href); };
  };
  const off = sync.onChange(() => { if (d.open && !d.querySelector('.rowmenu:not([hidden])')) render(); });
  d.addEventListener('close', () => off());
  $('closePlans').onclick = () => d.close();
  await render();
}

/**
 * The quiet lines "Remove routes of ‹phone› from this browser" for the closed folders (A-017 rule 16). No question –
 * unless the folder holds routes never sent to that phone (owner 2026-11-02): they would be lost, so a FRAGE in place
 * says how many, with Cancel and a red Remove.
 */
function removeLines(box: HTMLElement, sync: PlanSync) {
  if (box.querySelector('.confirm')) return; // a remove question is open: keep its line as it is
  box.querySelectorAll('.removefolder').forEach((b) => b.remove());
  for (const f of sync.status.closed) {
    const b = document.createElement('button');
    b.className = 'textbtn quiet removefolder';
    b.dataset.folder = f.key;
    b.textContent = t('web_remove_folder', f.name);
    b.onclick = async () => {
      let n = 0;
      try { n = await sync.unsent(f.key); }
      catch (e) { problem('sync', 'unsent routes of a closed folder not counted – asking anyway', e); n = -1; }
      if (n === 0) { await sync.removeFolder(f.key); return; }
      const text = n === 1 ? t('web_remove_folder_q_one', f.name) : n > 1 ? t('web_remove_folder_q', f.name, n) : t('web_remove_folder_q_unknown', f.name);
      askInPlace(b, text, t('common_remove'), () => box.querySelector<HTMLElement>(`.removefolder[data-folder="${CSS.escape(f.key)}"]`),
        () => { sync.removeFolder(f.key); });
    };
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
    <div id="phoneFill" class="phonefill" hidden><span class="filltext small"></span><span class="fillbar" aria-hidden="true"><i></i></span></div>
    <div id="phoneStatus" class="status" role="status"></div>
    <div class="row wrap" id="phoneRow"><button id="pairPhone" class="primary" hidden>${esc(t('web_pair_phone'))}</button><button id="syncNow" hidden>${esc(t('web_plans_sync_now'))}</button></div>
    <button id="howToggle" class="textbtn quiet howtoggle" aria-expanded="false" aria-controls="howBox">${esc(t('web_phone_how_link'))}</button>
    <div id="howBox" class="howbox" hidden>
      <ul class="how"><li>${esc(t('web_phone_why'))}</li><li>${esc(t('web_phone_how_2'))}</li><li>${esc(t('web_phone_how_3'))}</li></ul>
      <p class="muted small privacy">${esc(t('web_phone_privacy'))} <a href="${esc(t('web_url_privacy'))}" target="_blank" rel="noopener">${esc(t('web_phone_more'))}</a></p>
    </div>
    <div id="phoneMore" class="phonemore"><button id="pairOther" class="textbtn quiet" hidden>${esc(t('web_pair_other'))}</button></div>
    <!-- Unpair away from "Sync now" (incident 2026-10-31: right beside it, one slip ended the pairing): a red text button
         at the bottom, never the focused one; still no question (A-017 rule 3) -->
    <div class="unpairline"><button id="unpair" class="textbtn danger" hidden>${esc(t('web_unpair'))}</button></div>
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
    // 250 tours per phone (SyncLimits, owner 2026-11-07): „N von 250 Touren“, a thin bar, amber from 200
    $('phoneFill').hidden = !s.paired;
    if (s.paired) {
      const fill = $('phoneFill');
      fill.querySelector('.filltext')!.textContent = t('web_phone_fill', s.tours, s.maxTours);
      fill.querySelector<HTMLElement>('.fillbar i')!.style.width = `${Math.min(100, (100 * s.tours) / s.maxTours)}%`;
      fill.dataset.kind = s.tours >= s.maxTours ? 'full' : s.tours >= s.warnTours ? 'warn' : 'ok';
      fill.title = s.use ? t('web_phone_fill_use', s.use.items, s.use.maxItems) : '';
    }
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

/**
 * The phone's mark as the app's tag (ui/RouteTag, ui-style.md "Tags", docs/handoff-web-route-tags.md): "New" / "Changed"
 * after the name – came from the phone, not opened here yet; gone when opened (sync.seen). Never cut: the name gives way.
 */
/** Length and climb when this browser knows them (planstats.ts), else nothing – the list never waits for a route. */
function statsLine(p: SavedPlan): string {
  const s = statsOf(p.kind === 'track' ? trackKey(p.id) : planKey(p.plan));
  return s ? `<span class="stats">${esc(statsText(s))}</span>` : '';
}

function tagOf(p: SavedPlan): string {
  if (!p.mark) return '';
  const word = t(p.mark === 'new' ? 'routes_tag_new' : 'routes_tag_changed');
  return `<span class="tag" data-mark="${p.mark}">${esc(word)}</span>`;
}

/** A name as stored: a copy keeps its marker (SyncMerge.storedTitle) – "… (Kopie)" typed back is " (copy)" in the item. */
function storedTitle(name: string): string {
  const label = t('web_copy_suffix').trim(), n = name.trim();
  return n.endsWith(label) ? n.slice(0, -label.length).trimEnd() + ' (copy)' : n;
}

/** The app's PlanNames.newRouteName (shared, routing/PlanNames.kt – was mirrored here): [taken] names get " (2)", " (3)" … */
const newRouteName = (host: PlanHost, proposed: string | null | undefined, dated: string, taken: string[]) =>
  host.engineCall('planNewRouteName', proposed ?? '', dated, taken.join('\n'));

/** The app's date name (AppViewModel.defaultName): msg_name_route + msg_name_date_pattern ("d MMM, HH:mm" / "d. MMM, HH:mm"). */
export function datedName(ms = Date.now()): string {
  const d = new Date(ms), month = shortMonth(d);
  const two = (n: number) => String(n).padStart(2, '0');
  const date = t('msg_name_date_pattern').replace(/yyyy|MMM|HH|mm|d/g, (k) =>
    k === 'yyyy' ? String(d.getFullYear()) : k === 'MMM' ? month : k === 'HH' ? two(d.getHours()) : k === 'mm' ? two(d.getMinutes()) : String(d.getDate()));
  return `${t('msg_name_route')} ${date}`;
}

/** The app's PlanNames.copyName (shared): "Name (2)", the next free number; a copy of "Name (2)" is "Name (3)". */
const copyName = (host: PlanHost, name: string, taken: string[]) => host.engineCall('planCopyName', name, taken.join('\n'));

/** The app's RouteRules.cleanName: trimmed; blank keeps [old] (the proposal / old name). */
export const cleanName = (input: string, old: string) => input.trim() || old;

/**
 * Save's name dialog (the app's SaveNameDialog, owner 2026-10-31 "Save always asks for the name"): title Save route /
 * Save as copy, the name field with [name] fully selected, Cancel · Save; Enter saves, Escape cancels. [later] (the
 * place proposal, arriving after the dialog opened) replaces the field until the user types. Resolves the typed name
 * (blank kept as blank – [cleanName] decides) or null on Cancel.
 */
type MaybeName = Promise<string | null>;
function nameDialog(kind: boolean | 'as', name: string, later?: MaybeName): MaybeName {
  return new Promise((resolve) => {
    const d = dialog('savename', `<h2>${esc(t(kind === 'as' ? 'web_save_as' : kind ? 'plan_save_as_copy' : 'plan_save_route'))}</h2>
      <label class="field"><span class="muted small">${esc(t('routes_name_label'))}</span><input id="saveTitle" maxlength="100" autocomplete="off"></label>
      <div class="row end"><button id="saveCancel">${esc(t('common_cancel'))}</button><button id="saveOk" class="primary">${esc(t('common_save'))}</button></div>`);
    const input = d.querySelector<HTMLInputElement>('#saveTitle')!;
    let typed = false, answer: string | null = null;
    const fill = (n: string) => { input.value = n; input.focus(); input.select(); };
    fill(name);
    input.addEventListener('input', () => { typed = true; });
    later?.then((n) => { if (n && !typed && d.open) fill(n); });
    const ok = () => { answer = input.value; d.close(); };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); ok(); } });
    d.querySelector<HTMLButtonElement>('#saveOk')!.onclick = ok;
    d.querySelector<HTMLButtonElement>('#saveCancel')!.onclick = () => d.close();
    d.addEventListener('close', () => resolve(answer));
  });
}

/** How long the dialog waits for the place proposal of a new route's name (the app's NAME_WAIT_MS; the date name meanwhile). */
const NAME_WAIT_MS = 5_000;

/**
 * Save – the header's Save, ⋯ → Save route – as the app does it since 2026-10-31 (RouteRules.saveMode, AppViewModel.askSave
 * / confirmSave): always the name dialog first. The shown route belongs to a saved route that still exists → that route's
 * name, and Save overwrites it – renamed when the name changed, still ONE route; else the proposal ([newRouteName]: the
 * plan link's title while unchanged, else the place proposal "Vellau → Leiteralm" – shown when it arrives – else
 * "Route 31 Oct, 10:12") and a new route, the open one from then on. Cancel saves nothing. [then] runs after a save
 * (paired: everything waiting goes to the phone).
 */
/**
 * Room for one more NEW tour in [folder] (250 per phone, the shared SyncLimits – owner 2026-11-07)? If not: Save turns
 * amber and the line says why („250 Touren auf dem Handy – lösche erst welche.“), before any name is asked.
 */
async function roomFor(sync: PlanSync, host: PlanHost, folder: string): Promise<boolean> {
  if (await sync.hasRoom(folder)) return true;
  sync.refuseTour();
  host.note(t('web_sync_tour_cap', sync.status.maxTours));
  return false;
}

export async function saveRoute(sync: PlanSync, host: PlanHost, then?: () => void) {
  if (saving) return; // one click, one route (owner 2026-10-28: saving made duplicates)
  saving = true;
  try {
    const cur = await host.current();
    if (!cur) { then?.(); return; }
    const id = host.openId(), folder = host.openFolder();
    const plans = (await sync.list(folder)).filter((p) => p.kind === 'plan');
    const existing = id ? plans.find((p) => p.id === id) : undefined;
    // sync/SaveRule (review sync H3, shared): the stored route changed meanwhile (the phone's edit came in) – an
    // overwrite would lose that edit, so the work is saved as a copy; the other version stays
    if (existing && (await host.engineCall('saveKeepsOtherEdit', host.openedCode() ?? '', existing.plan)) === 'true') {
      if (!(await roomFor(sync, host, folder))) return;
      const first = await copyName(host, titleOf(existing), plans.map((p) => titleOf(p)));
      const input = await nameDialog(false, first);
      if (input == null) return;
      const name = cleanName(input, first), title = storedTitle(name);
      const newId = await sync.save(cur.code, title, undefined, folder);
      askPersist();
      host.saved({ id: newId, kind: 'plan', title, plan: cur.code, track: '', updatedAt: Date.now(), device: '', mine: true, dirty: true, onPhone: false }, folder);
      host.note(t('web_route_changed_elsewhere', name));
    } else if (existing) { // OVERWRITE: a new version of the open saved route, renamed when the name changed
      const old = titleOf(existing);
      const input = await nameDialog(false, old);
      if (input == null) return;
      const name = cleanName(input, old), title = name === old ? existing.title : storedTitle(name);
      await sync.save(cur.code, title, existing.id, folder);
      askPersist();
      host.saved({ ...existing, title, plan: cur.code, updatedAt: Date.now(), mine: true }, folder);
    } else { // NEW_ROUTE
      if (!(await roomFor(sync, host, sync.open))) return;
      const dated = datedName(), taken = plans.map((p) => titleOf(p)), link = host.linkTitle();
      const first = await newRouteName(host, link, dated, taken);
      // the app's proposeName: the link's title while unchanged, else the place proposal (waited for 5 s at most)
      const later = link ? undefined : Promise.race([host.suggestName().catch(() => ''), new Promise<string>((r) => setTimeout(() => r(''), NAME_WAIT_MS))])
        .then((p) => (p ? newRouteName(host, p, dated, taken) : null));
      const input = await nameDialog(false, first, later);
      if (input == null) return;
      const title = storedTitle(cleanName(input, first));
      const into = sync.open;
      const newId = await sync.save(cur.code, title, undefined, into);
      askPersist();
      host.saved({ id: newId, kind: 'plan', title, plan: cur.code, track: '', updatedAt: Date.now(), device: '', mine: true, dirty: true, onPhone: false }, into);
    }
    then?.();
  } finally { saving = false; }
}
let saving = false;

/**
 * Speichern ▾ → Speichern unter (owner 2026-11-06, A): the shown route as a NEW saved plan – the name dialog with a free
 * name (the open plan's "<name> (2)", else the proposal); the new one is the open plan from then on, the old one stays.
 */
export async function saveRouteAs(sync: PlanSync, host: PlanHost, then?: () => void) {
  if (saving) return;
  saving = true;
  try {
    const cur = await host.current();
    if (!cur) return;
    const folder = sync.open;
    if (!(await roomFor(sync, host, folder))) return;
    const plans = (await sync.list(folder)).filter((p) => p.kind === 'plan');
    const taken = plans.map((p) => titleOf(p)), open = host.openId() ? plans.find((p) => p.id === host.openId()) : undefined;
    const first = open ? await copyName(host, titleOf(open), taken) : await newRouteName(host, host.linkTitle(), datedName(), taken);
    const later = open || host.linkTitle() ? undefined : Promise.race([host.suggestName().catch(() => ''), new Promise<string>((r) => setTimeout(() => r(''), NAME_WAIT_MS))])
      .then((p) => (p ? newRouteName(host, p, datedName(), taken) : null));
    const input = await nameDialog('as', first, later);
    if (input == null) return;
    const title = storedTitle(cleanName(input, first));
    const newId = await sync.save(cur.code, title, undefined, folder);
    askPersist();
    host.saved({ id: newId, kind: 'plan', title, plan: cur.code, track: '', updatedAt: Date.now(), device: '', mine: true, dirty: true, onPhone: false }, folder);
    then?.();
  } finally { saving = false; }
}

/**
 * My routes → ⋮ → Save as copy (planned routes; the app's requestCopyRoute, owner 2026-10-31 – was the plan sheet's ⋯):
 * the name dialog with "<name> (2)" ([copyName]); Save stores a copy of the saved route – the shown plan stays as it is.
 */
async function copyRoute(sync: PlanSync, host: PlanHost, p: SavedPlan, folder: string): Promise<boolean> {
  if (saving || p.kind !== 'plan') return false;
  saving = true;
  try {
    if (!(await roomFor(sync, host, folder))) return false;
    const taken = (await sync.list(folder)).filter((x) => x.kind === 'plan').map((x) => titleOf(x));
    const first = await copyName(host, titleOf(p), taken);
    const input = await nameDialog(true, first);
    if (input == null) return false;
    await sync.save(p.plan, storedTitle(cleanName(input, first)), undefined, folder);
    return true;
  } finally { saving = false; }
}

/**
 * Pair phone: always a new code (A-017 point 2), the QR code, the steps in the app and the check digits; once the
 * phone answered (its hello, or – already paired with another browser – its JOIN), "Paired with …".
 * Unpair lives in the Phone window only (owner 2026-10-30: not three "Unpair").
 */
export async function pairDialog(sync: PlanSync, page: string, then?: () => void) {
  const d = dialog('pair', `<h2>${esc(t('web_pair_phone'))}</h2><div id="pairBody"><p class="muted">${esc(t('web_pair_preparing'))}</p></div>
    <div id="pairStatus" class="status muted"></div>
    <div class="row end" id="pairButtons"><button id="closePair">${esc(t('common_close'))}</button></div>`);
  const $ = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
  $('closePair').onclick = () => d.close();
  let poll = 0, shown = false;
  d.addEventListener('close', () => clearInterval(poll));
  if (then) d.addEventListener('close', () => { if (sync.status.paired) then(); }); // Aufs Handy: the plan follows the pairing

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
      // the app's sentence after pairing (sync_paired_plan) – what Save does from now on – until OK
      $('pairBody').innerHTML = `<p>${t('web_pair_done', `<b>${esc(s.phone ?? s.devices.map((x) => x.name).join(', '))}</b>`)}</p>
        <p class="paireddone">${esc(t('sync_paired_plan'))}</p>`;
      $('closePair').textContent = t('common_ok');
    }
  };
  const off = sync.onChange(() => { if (d.open) render(); });
  d.addEventListener('close', () => off());

  const p = await sync.pairing(page);
  if (!d.open) return;
  if ('error' in p) { $('pairBody').innerHTML = `<p class="status error">${esc(p.error)}</p>`; return; }
  if ('paired' in p) { shown = true; render(); return; } // the code shown before was answered meanwhile: "Paired with …"
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
