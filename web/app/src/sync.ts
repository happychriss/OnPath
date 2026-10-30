// Plan sync, phase 2 (docs/design/web-sync.md): the browser's "My routes" and the pairing with the phone. The app's own
// Kotlin decides everything about the data (com.onpath.app.sync, in the engine): pairing code, keys, encryption, the
// item format, the conflict rule (newer wins, the older stays as a copy). This file only stores the ledger texts
// (IndexedDB) and talks to the mailbox (/sync/v1/, same host – encrypted blobs only).
//
// Folders (owner order A-017, web-sync.md §14): one folder per phone (key = the phone's device id) plus "Without phone".
// Pairing opens the phone's folder; Unpair closes it (no question, the routes stay here). Phone → browser comes by
// itself (pull on load, when the tab is visible again, online, every 60 s); browser → phone only with Save (push).
// Routes planned while no phone was paired belong to the next phone that is paired (§14.6 rule 2): they join its folder
// as waiting (unsent) – nothing goes without a click on Save.
import type { EngineClient } from './engine/client';
import { t, locale } from './i18n';

export interface SavedPlan {
  id: string; kind: 'plan' | 'track'; title: string | null; plan: string; track: string; updatedAt: number; device: string; mine: boolean;
  /** Not in the mailbox in this version yet (what Save sends) / a version of it is in the mailbox (§14.6: 🖥 📱, bold). */
  dirty: boolean; onPhone: boolean;
  /** From the phone since the last look (A-017 rule 11) – kept as data, no longer shown (§14.6). */
  mark?: 'new' | 'changed';
}
export interface Device { name: string; updatedAt: number }
export interface FolderInfo { key: string; name: string }
export interface SyncStatus {
  paired: boolean; check: string | null; devices: Device[];
  /** The user's changes in the open folder not on the phone yet (routes, tracks, deletes) – what Save sends. */
  pending: number; lastSync: number | null;
  /** When the phone app last used the mailbox (the host's appSeen, to the minute); null = not known / not yet. */
  phoneSeen: number | null;
  /** The open folder: its key ("none" = Without phone) and its shown name ("Pixel 8", "Without phone"). */
  folder: FolderInfo;
  /** The paired phone's shown name (the open folder), null when not paired. */
  phone: string | null;
  /** The other phones' folders kept in this browser (closed) – each can be removed from here (A-017 rule 16). */
  closed: FolderInfo[];
  /** A new pairing waits for the phone (its code is shown). */
  waiting: boolean;
  /** The mailbox refused the last Save as full (507): amber hint beside Save, nothing lost. */
  full: boolean;
  /** A plain-language line about the last sync (empty when all is well). */
  message: string; kind: 'info' | 'warning' | 'error';
}
/** The open route's unsaved state, kept per folder (A-017 point 1): it comes back with its folder. */
export interface Draft { code: string; savedId: string | null; savedTitle: string | null; savedCode: string | null; savedFolder?: string | null }

/** The folder for planning without a phone (A-017 rule 1). */
export const NONE = 'none';
const EVERY_MS = 60_000;
const TIMEOUT_MS = 15_000;
/** Device roles in a hello (web-sync.md §5.1). */
const ROLE_PHONE = 1, ROLE_BROWSER = 2;

interface Folder { key: string; name: string | null; mailbox: string | null; pairedAt: number; seen: number | null }
interface Dir { open: string; list: Folder[] }
interface Pending { pair: string; ledger: string }
interface Hello { device: string; name: string | null; role: number | null; deleted: boolean; updatedAt: number }

/** Key–value store: IndexedDB, or memory when the browser blocks it (private window) – the page still works. */
class Store {
  private db: Promise<IDBDatabase | null>;
  private mem = new Map<string, string>();
  persistent = true;

  constructor() {
    this.db = new Promise((resolve) => {
      try {
        const r = indexedDB.open('onpath', 1);
        r.onupgradeneeded = () => r.result.createObjectStore('kv');
        r.onsuccess = () => resolve(r.result);
        r.onerror = () => { this.persistent = false; resolve(null); };
      } catch { this.persistent = false; resolve(null); } // expected: IndexedDB blocked (privacy settings, old browser)
    });
  }

  async get(k: string): Promise<string | null> {
    const db = await this.db;
    if (!db) return this.mem.get(k) ?? null;
    return new Promise((resolve) => {
      const r = db.transaction('kv').objectStore('kv').get(k);
      r.onsuccess = () => resolve(typeof r.result === 'string' ? r.result : null);
      r.onerror = () => resolve(this.mem.get(k) ?? null);
    });
  }

  async set(k: string, v: string | null): Promise<void> {
    if (v == null) this.mem.delete(k); else this.mem.set(k, v);
    const db = await this.db;
    if (!db) return;
    await new Promise<void>((resolve, reject) => {
      const t = db.transaction('kv', 'readwrite');
      if (v == null) t.objectStore('kv').delete(k); else t.objectStore('kv').put(v, k);
      t.oncomplete = () => resolve();
      t.onerror = () => reject(t.error ?? new Error('storage write failed'));
    });
  }
}

class Gone extends Error {}
class Full extends Error {}

/** The host's cap on new mailboxes per network and day (web/host/src/sync.js, web-sync.md §8): 429 code "new-mailbox-cap". */
async function capped(r: Response): Promise<boolean> {
  if (r.status !== 429) return false;
  const body = await r.clone().json().catch(() => ({})) as { code?: string }; // expected: the plain rate limit has no JSON code
  return body.code === 'new-mailbox-cap';
}

/** A conflict copy's title in the page's language: SyncMerge's " (copy)" is shown as " (Kopie)" in German (display only). */
export function shownTitle(title: string | null, fallback: string): string {
  if (!title) return fallback;
  return title.endsWith(' (copy)') ? title.slice(0, -' (copy)'.length) + ' ' + t('web_copy_suffix') : title;
}

export class PlanSync {
  private store = new Store();
  private chain: Promise<unknown> = Promise.resolve();
  private timer = 0;
  private listeners = new Set<() => void>();
  private folderListeners = new Set<(from: string, to: string) => void>();
  private dir: Dir = { open: NONE, list: [] };
  status: SyncStatus = { paired: false, check: null, devices: [], pending: 0, lastSync: null, phoneSeen: null,
    folder: { key: NONE, name: '' }, phone: null, closed: [], waiting: false, full: false, message: '', kind: 'info' };

  constructor(private engine: EngineClient, private api: string) {}

  /** Called after every change (list, pairing, sync status). */
  onChange(f: () => void) { this.listeners.add(f); return () => this.listeners.delete(f); }
  private changed() { for (const f of this.listeners) f(); }
  /** Called when another folder opens (pairing, unpairing, the phone ended it) – the page swaps the open route's draft. */
  onFolder(f: (from: string, to: string) => void) { this.folderListeners.add(f); return () => this.folderListeners.delete(f); }

  async init() {
    if (!(await this.store.get('device'))) await this.store.set('device', await this.call('syncNewDevice', rand(8)));
    await this.migrate();
    this.dir = await this.readDir();
    // paired from before §14.6 (a Without phone list beside the phone's folder): those routes join it as waiting now
    if (this.dir.open !== NONE) { const key = this.dir.open; await this.locked(() => this.adoptNone(key, false)); }
    await this.refresh();
    if (!this.store.persistent) this.say(t('web_sync_not_kept'), 'warning');
    const tick = () => { if (document.visibilityState === 'visible') this.syncNow(); };
    this.timer = window.setInterval(tick, EVERY_MS);
    addEventListener('online', tick);
    document.addEventListener('visibilitychange', tick);
    this.syncNow();
  }

  /** The open folder's key ("none" = Without phone). */
  get open(): string { return this.dir.open; }

  /** Saved routes and tracks of [folder] (default: the open one), newest first, with the phone's marks. */
  async list(folder = this.dir.open): Promise<SavedPlan[]> {
    const items = JSON.parse(await this.call('syncList', await this.ledgerOf(folder), await this.device())) as SavedPlan[];
    const marks = await this.marks(folder);
    for (const p of items) if (marks[p.id]) p.mark = marks[p.id];
    return items;
  }

  /** How many routes and tracks [folder] holds. */
  async count(folder: string): Promise<number> { return (await this.list(folder)).length; }

  /** Saves [plan] (a plan code) as a new plan, or as a new version of [id], in [folder]. Returns the plan's id. Never sends. */
  async save(plan: string, title: string | null, id?: string, folder = this.dir.open): Promise<string> {
    const key = await this.locked(async () => {
      const k = id ?? await this.call('syncNewId', rand(16));
      await this.setLedgerOf(folder, await this.call('syncSave', await this.ledgerOf(folder), k, plan, title ?? '', Date.now(), await this.device()));
      return k;
    });
    await this.refresh();
    return key;
  }

  /** Keeps an imported GPX track in the open folder (A-017 rule 14: it goes to the phone with Save). Its id, or null. */
  async saveTrack(segments: [number, number, number | null][][], marks: { lat: number; lon: number; name: string | null }[],
    activity: string, sac: number, title: string): Promise<string | null> {
    const segs = segments.map((s) => s.map(([la, lo, e]) => `${la},${lo}` + (e == null ? '' : `,${e}`)).join(';')).join('|');
    const wpts = marks.map((m) => `${m.lat} ${m.lon} ${(m.name ?? '').replace(/\s+/g, ' ')}`).join('\n');
    const track = await this.call('syncTrackFit', segs, wpts, activity, sac);
    if (!track) return null;
    const folder = this.dir.open;
    const key = await this.locked(async () => {
      const k = await this.call('syncNewId', rand(16));
      await this.setLedgerOf(folder, await this.call('syncSaveTrack', await this.ledgerOf(folder), k, track, title, Date.now(), await this.device()));
      return k;
    });
    await this.refresh();
    return key;
  }

  /** A synced track as GPX text (to show it like an imported file). */
  trackGpx(p: SavedPlan): Promise<string> { return this.call('syncTrackGpx', p.track, p.title ?? ''); }

  async rename(id: string, title: string, folder = this.dir.open) {
    await this.locked(async () => {
      await this.setLedgerOf(folder, await this.call('syncRename', await this.ledgerOf(folder), id, title, Date.now(), await this.device()));
    });
    await this.refresh();
  }

  async remove(id: string, folder = this.dir.open) {
    await this.locked(async () => {
      await this.setLedgerOf(folder, await this.call('syncDelete', await this.ledgerOf(folder), id, Date.now(), await this.device()));
      await this.unmark(folder, id);
    });
    await this.refresh();
  }

  /** The route was opened: its "New from the phone" / "Changed on the phone" mark goes (A-017 rule 11). */
  async seen(id: string, folder = this.dir.open) {
    if (!(await this.marks(folder))[id]) return;
    await this.unmark(folder, id);
    this.changed();
  }

  /** The open route's unsaved state in [folder] (default: the open one). */
  async draft(folder = this.dir.open): Promise<Draft | null> {
    try { return JSON.parse((await this.store.get(`fach:${folder}:draft`)) ?? 'null') as Draft | null; }
    catch { return null; } // expected: a damaged draft – the route in the address bar stays
  }
  async setDraft(d: Draft | null, folder = this.dir.open) { await this.store.set(`fach:${folder}:draft`, d ? JSON.stringify(d) : null); }

  /**
   * Pair phone: always a new code (A-017 point 2 – never another phone's), its mailbox claimed; the open folder stays
   * open until the phone answered (then its folder opens). The pairing link (QR code) and check digits.
   */
  async pairing(page: string): Promise<{ link: string; check: string } | { error: string }> {
    const pair = await this.call('syncPairNew', rand(32));
    const k = await this.keys(pair);
    try {
      const r = await this.fetch(k.mailbox, { method: 'POST' }, k.token);
      if (await capped(r)) return { error: t('web_sync_pair_cap') };
      if (!r.ok) return { error: t('web_sync_pair_refused', r.status) };
    } catch {
      return { error: t('web_sync_pair_offline') }; // expected: offline
    }
    const old = await this.pending();
    await this.store.set('pending', JSON.stringify({ pair, ledger: '' } satisfies Pending));
    if (old) this.endMailbox(old.pair); // the code shown before is never used now
    await this.refresh();
    return { link: page.split('#')[0] + '#pair=' + pair, check: k.check };
  }

  /** Joins the pairing in [text] (a #pair= link opened in this browser); the phone's hello then opens its folder. */
  async join(text: string): Promise<string | null> {
    const pair = await this.call('syncPairParse', text);
    if (!pair) return t('web_sync_not_a_code');
    if ((await this.store.get('pair')) === pair) return null;
    await this.store.set('pending', JSON.stringify({ pair, ledger: '' } satisfies Pending));
    await this.refresh();
    await this.syncNow();
    return null;
  }

  /**
   * Unpair in the browser (A-017 point 8): no question, no DELETE – the code is forgotten here, this browser's hello is
   * written as deleted (so the phone stops naming it), the folder closes; its routes stay in this browser.
   */
  async unpair(): Promise<void> {
    const pair = await this.store.get('pair');
    if (!pair) return;
    const name = this.status.phone ?? '';
    await this.locked(async () => {
      const dev = await this.device();
      const hello = await this.call('syncHelloId', dev);
      await this.setLedger(await this.call('syncDelete', await this.ledger(), hello, Date.now(), dev));
      try { await this.push(await this.keys(pair), pair, hello); }
      catch (e) { console.warn('[Problems] INFO sync: unpaired offline – the phone still names this browser', e); } // expected: offline
      await this.closeFolder();
    });
    await this.refresh();
    this.say(t('web_sync_unpaired', name), 'info');
  }

  /** "Remove routes of ‹phone› from this browser" (A-017 rule 16): only the local copy of a closed folder goes. */
  async removeFolder(key: string) {
    if (key === NONE || key === this.dir.open) return;
    await this.locked(async () => {
      for (const s of ['ledger', 'marks', 'draft']) await this.store.set(`fach:${key}:${s}`, null);
      this.dir.list = this.dir.list.filter((f) => f.key !== key);
      await this.writeDir();
    });
    await this.refresh();
  }

  /** Pull now: what the phone changed comes in (and a waiting pairing is looked at). Never throws – see [status]. */
  syncNow(): Promise<void> { return this.sync(false); }

  /** Save → the phone (A-017 rule 6): everything unsaved in the open folder goes to the mailbox. Never throws. */
  sendAll(): Promise<void> { return this.sync(true); }

  private sync(all: boolean): Promise<void> {
    return this.locked(async () => {
      try {
        const p = await this.pending();
        if (p) await this.pendingRound(p);
        const pair = await this.store.get('pair');
        if (!pair) return;
        await this.round(pair, all);
        this.status.lastSync = Date.now();
        this.status.full = false;
        this.say('', 'info');
      } catch (e) {
        if (e instanceof Gone) {
          await this.closeFolder();
          this.say(t('web_sync_gone'), 'info');
        } else if (e instanceof Full) {
          this.status.full = true;
          this.say(t('web_sync_full_hint'), 'warning');
        } else if (e instanceof TypeError || (e as Error).name === 'TimeoutError' || !navigator.onLine) {
          this.say(t('web_sync_offline'), 'info'); // expected: no network
        } else {
          this.say(t('web_sync_failed', (e as Error).message), 'error');
          console.warn('[Problems] WARN sync', e);
        }
      }
    }).then(() => this.refresh());
  }

  /** One round with the open folder's mailbox: hello, pull + merge, push (only the hello unless [all] – Save). */
  private async round(pair: string, all: boolean) {
    const k = await this.keys(pair);
    const device = await this.device();
    await this.setLedger(await this.call('syncHelloAs', await this.ledger(), device, deviceName(), Date.now(), ROLE_BROWSER));
    const hello = await this.call('syncHelloId', device);
    for (let attempt = 0; attempt < 4; attempt++) {
      await this.pull(k, pair);
      if (this.dir.open === NONE) return; // the folder was closed meanwhile (cannot happen inside the lock – safety)
      const { conflict, sent } = await this.push(k, pair, all ? '' : hello);
      if (!conflict && sent < 64) return;
    }
  }

  /** Reads the open folder's mailbox from where it stood; the phone's hello names the folder, its changes get marks. */
  private async pull(k: Keys, pair: string) {
    for (let page = 0; page < 50; page++) {
      const before = JSON.parse(await this.call('syncState', await this.ledger())).since as number;
      const body = await this.read(k, before);
      if (body.appSeen !== undefined) this.status.phoneSeen = body.appSeen; // an older host has no appSeen: keep what we know
      const lines = body.items.map((i) => `${i.id} ${i.rev} ${i.blob}`).join('\n');
      const peek = JSON.parse(await this.call('syncPeek', pair, lines)) as { hellos: Hello[] };
      await this.namePhone(peek.hellos);
      const was = before > 0 ? new Map((await this.list()).map((p) => [p.id, p.updatedAt])) : null;
      const out = await this.call('syncApply', await this.ledger(), pair, lines, body.rev);
      const cut = out.lastIndexOf('#bad ');
      await this.setLedger(out.slice(0, cut));
      const bad = Number(out.slice(cut + 5));
      if (bad > 0) this.say(t('web_sync_unreadable', bad), 'warning');
      if (was) await this.markPhoneChanges(was);
      if (!body.more) break;
    }
  }

  /** Pushes the open folder's changes ([only] = one item id, "" = all); 409 = someone wrote first → read again. */
  private async push(k: Keys, pair: string, only: string): Promise<{ conflict: boolean; sent: number }> {
    const lines = (await this.call('syncOutboxOf', await this.ledger(), pair, rand(12 * 64), only)).split('\n').filter(Boolean);
    let conflict = false;
    for (const line of lines) {
      const [id, base, blob, sent] = line.split(' ');
      const r = await this.fetch(`${k.mailbox}/${id}`, {
        method: 'PUT', body: fromB64url(blob) as BufferSource,
        headers: { 'x-onpath-base': base, 'content-type': 'application/octet-stream' },
      }, k.token);
      if (r.status === 409) { conflict = true; continue; }
      if (!r.ok) await this.refused(r);
      const { rev } = await r.json() as { rev: number };
      await this.setLedger(await this.call('syncAcked', await this.ledger(), sent, rev));
    }
    return { conflict, sent: lines.length };
  }

  /**
   * A pairing that waits for the phone (its code was shown or opened here). The phone answers in one of two ways
   * (web-sync.md §5.1): an already paired phone writes a JOIN with its own code → adopt it, delete this transitional
   * mailbox, open the phone's folder and read its mailbox from the start; an unpaired phone writes its hello → this
   * mailbox is the phone's from now on. Meanwhile this browser's hello goes in (the phone shows "Paired with …").
   */
  private async pendingRound(p: Pending) {
    const k = await this.keys(p.pair);
    const device = await this.device();
    let ledger = await this.call('syncHelloAs', p.ledger, device, deviceName(), Date.now(), ROLE_BROWSER);
    let body;
    try {
      body = await this.read(k, JSON.parse(await this.call('syncState', ledger)).since as number);
    } catch (e) {
      if (e instanceof Gone) { await this.store.set('pending', null); return; } // ended or taken: the code is dead
      throw e;
    }
    const lines = body.items.map((i) => `${i.id} ${i.rev} ${i.blob}`).join('\n');
    const peek = JSON.parse(await this.call('syncPeek', p.pair, lines)) as { join: { device: string; code: string } | null; hellos: Hello[] };
    const out = await this.call('syncApply', ledger, p.pair, lines, body.rev);
    ledger = out.slice(0, out.lastIndexOf('#bad '));
    if (peek.join) {
      const to = await this.keys(peek.join.code);
      this.endMailbox(p.pair); // the transitional mailbox (A-017 point 3)
      await this.store.set('pending', null);
      await this.openFolder(peek.join.device, peek.join.code, to.mailbox, null, null);
      return;
    }
    const phone = phoneHello(peek.hellos, device);
    if (phone) {
      await this.store.set('pending', null);
      await this.openFolder(phone.device, p.pair, k.mailbox, phone.name, ledger);
      return;
    }
    // still waiting: send our hello only
    const lines2 = (await this.call('syncOutboxOf', ledger, p.pair, rand(12 * 4), await this.call('syncHelloId', device))).split('\n').filter(Boolean);
    for (const line of lines2) {
      const [id, base, blob, sent] = line.split(' ');
      const r = await this.fetch(`${k.mailbox}/${id}`, { method: 'PUT', body: fromB64url(blob) as BufferSource,
        headers: { 'x-onpath-base': base, 'content-type': 'application/octet-stream' } }, k.token);
      if (r.ok) ledger = await this.call('syncAcked', ledger, sent, (await r.json() as { rev: number }).rev);
    }
    await this.store.set('pending', JSON.stringify({ pair: p.pair, ledger } satisfies Pending));
  }

  /**
   * Opens the folder of phone [key] with pairing [pair] (A-017 rule 2/4): an existing folder comes back with its unsaved
   * changes and marks, read again from the start (since = 0; from a new mailbox only the unsaved changes stay, the rest
   * comes back from the phone); a new one starts with [ledger] or empty. The folder open before closes (no DELETE).
   */
  private async openFolder(key: string, pair: string, mailbox: string, name: string | null, ledger: string | null) {
    const from = this.dir.open;
    const f = this.dir.list.find((x) => x.key === key);
    if (f) {
      await this.setLedgerOf(key, await this.call('syncRebase', await this.ledgerOf(key), f.mailbox === mailbox ? 1 : 0));
      f.mailbox = mailbox; f.pairedAt = Date.now();
      if (name) f.name = name;
    } else {
      this.dir.list.push({ key, name, mailbox, pairedAt: Date.now(), seen: null });
      await this.setLedgerOf(key, ledger ?? '');
    }
    this.dir.open = key;
    await this.store.set('pair', pair);
    await this.writeDir();
    await this.adoptNone(key, true);
    this.status.phoneSeen = null;
    await this.refresh();
    if (from !== key) for (const l of this.folderListeners) l(from, key);
  }

  /**
   * §14.6 rule 2: the routes and tracks of Without phone join the folder [key] as waiting (same id, unsent – Save sends
   * them; never uploaded by themselves), Without phone is emptied. [takeDraft] (a pairing): the route open in Without
   * phone stays open – it becomes the folder's open route. Called inside the lock.
   */
  private async adoptNone(key: string, takeDraft: boolean) {
    if (key === NONE) return;
    const dev = await this.device();
    const items = JSON.parse(await this.call('syncList', await this.ledgerOf(NONE), dev)) as SavedPlan[];
    if (items.length) {
      let l = await this.ledgerOf(key);
      for (const p of items) {
        l = p.kind === 'track' ? await this.call('syncSaveTrack', l, p.id, p.track, p.title ?? '', p.updatedAt, dev)
          : await this.call('syncSave', l, p.id, p.plan, p.title ?? '', p.updatedAt, dev);
      }
      await this.setLedgerOf(key, l);
      for (const x of ['ledger', 'marks']) await this.store.set(`fach:${NONE}:${x}`, null);
    }
    const moved = (d: Draft): Draft => ({ ...d, savedFolder: d.savedFolder === NONE ? key : d.savedFolder ?? null });
    const none = await this.draft(NONE);
    if (takeDraft && none?.code) {
      await this.setDraft(moved(none), key);
      await this.setDraft(null, NONE);
    } else {
      const own = await this.draft(key);
      if (own?.savedFolder === NONE) await this.setDraft(moved(own), key);
    }
  }

  /** The open folder closes (Unpair, or the phone ended the pairing): Without phone opens; nothing is deleted. */
  private async closeFolder() {
    const from = this.dir.open;
    await this.store.set('pair', null);
    this.dir.open = NONE;
    await this.writeDir();
    this.status.phoneSeen = null;
    await this.refresh();
    if (from !== NONE) for (const l of this.folderListeners) l(from, NONE);
  }

  /** The phone's hello in the open folder's mailbox gives the folder its name (and its key after an old store's move). */
  private async namePhone(hellos: Hello[]) {
    const f = this.dir.list.find((x) => x.key === this.dir.open);
    if (!f) return;
    const phone = phoneHello(hellos, await this.device());
    if (!phone) return;
    if (f.key.startsWith('mb:') && !this.dir.list.some((x) => x.key === phone.device)) {
      // a store from before the folders (paired, the phone not known yet): the folder takes the phone's device id
      for (const s of ['ledger', 'marks', 'draft']) {
        await this.store.set(`fach:${phone.device}:${s}`, await this.store.get(`fach:${f.key}:${s}`));
        await this.store.set(`fach:${f.key}:${s}`, null);
      }
      f.key = phone.device; this.dir.open = phone.device;
    }
    if (phone.device === f.key && phone.name && phone.name !== f.name) f.name = phone.name;
    await this.writeDir();
  }

  /**
   * Marks routes that came in from the phone's mailbox since [was] (id → updatedAt before this page was read): the
   * phone's own changes and those another browser saved to the phone (A-017 scenario "Laptop + Desktop": "the other
   * sees it as New from the phone") – everything not changed last by this browser.
   */
  private async markPhoneChanges(was: Map<string, number>) {
    const folder = this.dir.open;
    const marks = await this.marks(folder);
    let n = 0;
    for (const p of await this.list(folder)) {
      if (p.mine) continue;
      const before = was.get(p.id);
      if (before === p.updatedAt) continue;
      marks[p.id] = before === undefined ? 'new' : (marks[p.id] === 'new' ? 'new' : 'changed');
      n++;
    }
    if (n) await this.store.set(`fach:${folder}:marks`, JSON.stringify(marks));
  }

  private async marks(folder: string): Promise<Record<string, 'new' | 'changed'>> {
    try { return JSON.parse((await this.store.get(`fach:${folder}:marks`)) ?? '{}'); }
    catch { return {}; } // expected: damaged marks – they only decorate the list
  }
  private async unmark(folder: string, id: string) {
    const m = await this.marks(folder);
    if (!m[id]) return;
    delete m[id];
    await this.store.set(`fach:${folder}:marks`, JSON.stringify(m));
  }

  /** GET one page since [since]; 404 = not claimed yet (or expired after a year): any key holder may claim it. */
  private async read(k: Keys, since: number) {
    let r = await this.fetch(`${k.mailbox}?since=${since}`, {}, k.token);
    if (r.status === 404) {
      const c = await this.fetch(k.mailbox, { method: 'POST' }, k.token);
      if (!c.ok) await this.refused(c);
      r = await this.fetch(`${k.mailbox}?since=${since}`, {}, k.token);
    }
    if (!r.ok) await this.refused(r);
    return await r.json() as { rev: number; more: boolean; items: { id: string; rev: number; blob: string }[]; appSeen?: number | null };
  }

  /** DELETE a mailbox only this browser used (a transitional or never-scanned one) – best effort, in the background. */
  private endMailbox(pair: string) {
    this.keys(pair).then((k) => this.fetch(k.mailbox, { method: 'DELETE' }, k.token))
      .catch((e) => console.warn('[Problems] INFO sync: a transitional mailbox was not deleted (it expires by itself)', e)); // expected: offline
  }

  private async refused(r: Response): Promise<never> {
    if (r.status === 410 || r.status === 403) throw new Gone(t('web_sync_gone'));
    if (await capped(r)) throw new Error(t('web_sync_pair_cap'));
    if (r.status === 429) throw new Error(t('web_sync_busy'));
    if (r.status === 507) throw new Full(t('web_sync_full_hint'));
    throw new Error(t('web_sync_answered', r.status));
  }

  private async refresh() {
    const pair = await this.store.get('pair');
    const ledger = await this.ledger();
    const st = JSON.parse(await this.call('syncState', ledger));
    const f = this.dir.list.find((x) => x.key === this.dir.open);
    this.status.paired = !!pair && this.dir.open !== NONE;
    this.status.check = this.status.paired ? (await this.keys(pair!)).check : null;
    this.status.folder = { key: this.dir.open, name: f ? this.folderName(f) : t('web_folder_none') };
    this.status.phone = this.status.paired && f ? this.folderName(f) : null;
    const others = this.status.paired ? JSON.parse(await this.call('syncDevices', ledger, await this.device())) as (Device & { device: string })[] : [];
    const phone = others.find((d) => d.device === this.dir.open);
    this.status.devices = this.status.paired ? (f?.name ? [{ name: f.name, updatedAt: phone?.updatedAt ?? 0 }] : others) : [];
    this.status.pending = this.status.paired ? st.unsaved : 0;
    this.status.closed = this.dir.list.filter((x) => x.key !== this.dir.open).map((x) => ({ key: x.key, name: this.folderName(x) }));
    this.status.waiting = !!(await this.store.get('pending'));
    if (f && this.status.phoneSeen != null) f.seen = this.status.phoneSeen;
    this.changed();
  }

  /** "Pixel 8"; two phones with the same name: "Pixel 8 · paired 30 Oct" (A-017 point 9). */
  private folderName(f: Folder): string {
    const name = f.name ?? t('web_folder_phone');
    const same = this.dir.list.filter((x) => (x.name ?? t('web_folder_phone')) === name).length > 1;
    return same ? t('web_folder_paired_on', name, new Date(f.pairedAt).toLocaleDateString(locale, { day: 'numeric', month: 'short' })) : name;
  }

  private say(message: string, kind: SyncStatus['kind']) { this.status.message = message; this.status.kind = kind; this.changed(); }

  /** One change at a time across this browser's tabs (Web Locks; one chain per tab where the browser lacks them). */
  private locked<T>(f: () => Promise<T>): Promise<T> {
    const locks = (navigator as Navigator & { locks?: LockManager }).locks;
    const run = locks ? () => locks.request('onpath-plans', f) as Promise<T> : f;
    const p = this.chain.then(run, run);
    this.chain = p.catch(() => undefined); // expected: the caller sees the error; the chain goes on
    return p;
  }

  /**
   * Once: the store from before the folders (keys "ledger", "pair") moves into folders (A-017 point 1) – paired: the
   * phone's folder (key = the other device's hello, else the mailbox until the phone says hello), else Without phone.
   */
  private async migrate() {
    if (await this.store.get('folders')) return;
    const old = (await this.store.get('ledger')) ?? '';
    const pair = await this.store.get('pair');
    const dir: Dir = { open: NONE, list: [] };
    if (pair) {
      const k = await this.keys(pair);
      const devs = JSON.parse(await this.call('syncDevices', old, await this.device())) as (Device & { device: string })[];
      const key = devs[0]?.device ?? 'mb:' + k.mailbox;
      await this.store.set(`fach:${key}:ledger`, old);
      dir.open = key;
      dir.list.push({ key, name: devs[0]?.name || null, mailbox: k.mailbox, pairedAt: Date.now(), seen: null });
    } else {
      await this.store.set(`fach:${NONE}:ledger`, old);
    }
    await this.store.set('folders', JSON.stringify(dir));
    await this.store.set('ledger', null);
  }

  private async readDir(): Promise<Dir> {
    try {
      const d = JSON.parse((await this.store.get('folders')) ?? '') as Dir;
      if (d && typeof d.open === 'string' && Array.isArray(d.list)) return d;
    } catch (e) { console.warn('[Problems] WARN sync: the folder list was unreadable – Without phone opens', e); }
    return { open: NONE, list: [] };
  }
  private writeDir() { return this.store.set('folders', JSON.stringify(this.dir)); }
  private async pending(): Promise<Pending | null> {
    try { return JSON.parse((await this.store.get('pending')) ?? 'null') as Pending | null; }
    catch { return null; } // expected: damaged – a new Pair phone makes a new one
  }

  private ledgerOf = async (folder: string) => (await this.store.get(`fach:${folder}:ledger`)) ?? '';
  private setLedgerOf = (folder: string, t: string) => this.store.set(`fach:${folder}:ledger`, t);
  private ledger = () => this.ledgerOf(this.dir.open);
  private setLedger = (t: string) => this.setLedgerOf(this.dir.open, t);
  private device = async () => (await this.store.get('device')) ?? '';
  private async keys(pair: string): Promise<Keys> {
    return JSON.parse(await this.call('syncKeys', pair));
  }
  private call(fn: string, ...args: (string | number)[]) { return this.engine.call(fn, ...args); }

  private fetch(path: string, init: RequestInit, token: string) {
    return fetch(this.api + path, {
      ...init, cache: 'no-store', signal: AbortSignal.timeout(TIMEOUT_MS),
      // x-onpath-client: the host tells the phone's requests from ours ("phone last synced", web-sync.md §8)
      headers: { ...(init.headers as Record<string, string> ?? {}), authorization: 'Bearer ' + token, 'x-onpath-client': 'web' },
    });
  }

  stop() { clearInterval(this.timer); }
}

interface Keys { mailbox: string; token: string; check: string }

/** The phone's hello among [hellos]: role 1, else (an app from before the role) one that is not a browser and not us. */
function phoneHello(hellos: Hello[], me: string): Hello | null {
  const live = hellos.filter((h) => !h.deleted && h.device !== me);
  return live.find((h) => h.role === ROLE_PHONE) ?? live.find((h) => h.role == null) ?? null;
}

/** "Firefox on Linux" – what the phone shows as the paired browser (encrypted, never seen by the server). */
export function deviceName(ua = navigator.userAgent): string {
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome'
    : /Safari\//.test(ua) ? 'Safari' : t('web_device_browser');
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows'
    : /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? t('web_device_name', browser, os) : browser;
}

function rand(n: number): string {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return toB64url(b);
}
function toB64url(b: Uint8Array): string {
  let s = '';
  for (const x of b) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function fromB64url(s: string): Uint8Array {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
