// Plan sync, phase 2 (docs/design/web-sync.md): the browser's "My plans" and the pairing with the phone. The app's own
// Kotlin decides everything about the data (com.onpath.app.sync, in the engine): pairing code, keys, encryption, the
// item format, the conflict rule (newer wins, the older stays as a copy). This file only stores the ledger text
// (IndexedDB) and talks to the mailbox (/sync/v1/, same host – encrypted blobs only).
import type { EngineClient } from './engine/client';
import { t } from './i18n';

export interface SavedPlan { id: string; title: string | null; plan: string; updatedAt: number; mine: boolean }
export interface Device { name: string; updatedAt: number }
export interface SyncStatus {
  paired: boolean; check: string | null; devices: Device[]; pending: number; lastSync: number | null;
  /** A plain-language line about the last sync (empty when all is well). */
  message: string; kind: 'info' | 'warning' | 'error';
}

const EVERY_MS = 30_000;
const TIMEOUT_MS = 15_000;

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

export class PlanSync {
  private store = new Store();
  private chain: Promise<unknown> = Promise.resolve();
  private timer = 0;
  private soon = 0;
  private listeners = new Set<() => void>();
  status: SyncStatus = { paired: false, check: null, devices: [], pending: 0, lastSync: null, message: '', kind: 'info' };

  constructor(private engine: EngineClient, private api: string) {}

  /** Called after every change (list, pairing, sync status). */
  onChange(f: () => void) { this.listeners.add(f); return () => this.listeners.delete(f); }
  private changed() { for (const f of this.listeners) f(); }

  async init() {
    if (!(await this.store.get('device'))) await this.store.set('device', await this.call('syncNewDevice', rand(8)));
    await this.refresh();
    if (!this.store.persistent) this.say(t('web_sync_not_kept'), 'warning');
    const tick = () => { if (document.visibilityState === 'visible') this.syncSoon(0); };
    this.timer = window.setInterval(tick, EVERY_MS);
    addEventListener('online', tick);
    document.addEventListener('visibilitychange', tick);
    this.syncSoon(0);
  }

  async list(): Promise<SavedPlan[]> {
    return JSON.parse(await this.call('syncList', await this.ledger(), await this.device()));
  }

  /** Saves [plan] (a plan code) as a new plan, or as a new version of [id]. Returns the plan's id. */
  async save(plan: string, title: string | null, id?: string): Promise<string> {
    return this.locked(async () => {
      const key = id ?? await this.call('syncNewId', rand(16));
      await this.setLedger(await this.call('syncSave', await this.ledger(), key, plan, title ?? '', Date.now(), await this.device()));
      this.syncSoon();
      return key;
    });
  }

  async rename(id: string, title: string) {
    await this.locked(async () => {
      await this.setLedger(await this.call('syncRename', await this.ledger(), id, title, Date.now(), await this.device()));
    });
    this.syncSoon();
  }

  async remove(id: string) {
    await this.locked(async () => {
      await this.setLedger(await this.call('syncDelete', await this.ledger(), id, Date.now(), await this.device()));
    });
    this.syncSoon();
  }

  /** The pairing link (QR code) and check digits; a new pairing is made (and its mailbox claimed) when there is none. */
  async pairing(page: string): Promise<{ link: string; check: string } | { error: string }> {
    let pair = await this.store.get('pair');
    if (!pair) {
      pair = await this.call('syncPairNew', rand(32));
      const k = await this.keys(pair);
      try {
        const r = await this.fetch(k.mailbox, { method: 'POST' }, k.token);
        if (!r.ok) return { error: t('web_sync_pair_refused', r.status) };
      } catch {
        return { error: t('web_sync_pair_offline') }; // expected: offline
      }
      await this.store.set('pair', pair);
      await this.refresh();
      this.syncSoon(0);
    }
    return { link: page.split('#')[0] + '#pair=' + pair, check: (await this.keys(pair)).check };
  }

  /** Joins the pairing in [text] (a #pair= link) – what the phone app does after scanning. Null = done, else why not. */
  async join(text: string): Promise<string | null> {
    const pair = await this.call('syncPairParse', text);
    if (!pair) return t('web_sync_not_a_code');
    const now = await this.store.get('pair');
    if (now === pair) return null;
    if (now) return t('web_sync_already_paired');
    await this.store.set('pair', pair);
    await this.refresh();
    await this.syncNow();
    return null;
  }

  /** Ends the pairing on both sides: the mailbox is deleted; the plans stay in this browser. */
  async unpair(): Promise<string | null> {
    const pair = await this.store.get('pair');
    if (!pair) return null;
    const k = await this.keys(pair);
    try {
      const r = await this.fetch(k.mailbox, { method: 'DELETE' }, k.token);
      if (!r.ok && r.status !== 410 && r.status !== 404) return t('web_sync_unpair_failed', r.status);
    } catch {
      return t('web_sync_unpair_offline'); // expected: offline
    }
    await this.locked(() => this.forget());
    await this.refresh();
    this.say(t('web_sync_unpaired'), 'info');
    return null;
  }

  syncSoon(ms = 400) {
    clearTimeout(this.soon);
    this.soon = window.setTimeout(() => { this.syncNow(); }, ms);
  }

  /** One sync round now (pull, merge, push); never throws – the outcome is in [status]. */
  syncNow(): Promise<void> {
    return this.locked(async () => {
      const pair = await this.store.get('pair');
      if (!pair) return;
      try {
        await this.round(pair);
        this.status.lastSync = Date.now();
        this.say('', 'info');
      } catch (e) {
        if (e instanceof Gone) {
          await this.forget();
          this.say(e.message, 'warning');
        } else if (e instanceof TypeError || (e as Error).name === 'TimeoutError' || !navigator.onLine) {
          this.say(t('web_sync_offline'), 'info'); // expected: no network
        } else {
          this.say(t('web_sync_failed', (e as Error).message), 'error');
          console.warn('[Problems] WARN sync', e);
        }
      }
    }).then(() => this.refresh());
  }

  private async round(pair: string) {
    const k = await this.keys(pair);
    const device = await this.device();
    await this.setLedger(await this.call('syncHello', await this.ledger(), device, deviceName(), Date.now()));
    for (let attempt = 0; attempt < 4; attempt++) {
      // 1. pull everything newer than what this browser has read
      for (let page = 0; page < 50; page++) {
        const since = JSON.parse(await this.call('syncState', await this.ledger())).since as number;
        let r = await this.fetch(`${k.mailbox}?since=${since}`, {}, k.token);
        if (r.status === 404) { // not claimed yet (or expired after a year): any holder of the key may claim it
          const c = await this.fetch(k.mailbox, { method: 'POST' }, k.token);
          if (!c.ok) await this.refused(c);
          r = await this.fetch(`${k.mailbox}?since=${since}`, {}, k.token);
        }
        if (!r.ok) await this.refused(r);
        const body = await r.json() as { rev: number; more: boolean; items: { id: string; rev: number; blob: string }[] };
        const out = await this.call('syncApply', await this.ledger(), pair, body.items.map((i) => `${i.id} ${i.rev} ${i.blob}`).join('\n'), body.rev);
        const cut = out.lastIndexOf('#bad ');
        await this.setLedger(out.slice(0, cut));
        const bad = Number(out.slice(cut + 5));
        if (bad > 0) this.say(t('web_sync_unreadable', bad), 'warning');
        if (!body.more) break;
      }
      // 2. push what changed here; 409 = another device wrote first → read again, the merge keeps both
      const lines = (await this.call('syncOutbox', await this.ledger(), pair, rand(12 * 64))).split('\n').filter(Boolean);
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
      if (!conflict && lines.length < 64) return;
    }
  }

  private async refused(r: Response): Promise<never> {
    if (r.status === 410 || r.status === 403) throw new Gone(t('web_sync_gone'));
    if (r.status === 429) throw new Error(t('web_sync_busy'));
    if (r.status === 507) throw new Error(t('web_sync_full'));
    throw new Error(t('web_sync_answered', r.status));
  }

  /** Forgets the pairing (call inside [locked]); the plans stay and upload again after a new pairing. */
  private async forget() {
    await this.setLedger(await this.call('syncForget', await this.ledger()));
    await this.store.set('pair', null);
  }

  private async refresh() {
    const pair = await this.store.get('pair');
    const ledger = await this.ledger();
    const st = JSON.parse(await this.call('syncState', ledger));
    this.status.paired = !!pair;
    this.status.check = pair ? (await this.keys(pair)).check : null;
    this.status.devices = pair ? JSON.parse(await this.call('syncDevices', ledger, await this.device())) : [];
    this.status.pending = st.pending;
    this.changed();
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

  private ledger = async () => (await this.store.get('ledger')) ?? '';
  private setLedger = (t: string) => this.store.set('ledger', t);
  private device = async () => (await this.store.get('device')) ?? '';
  private async keys(pair: string): Promise<{ mailbox: string; token: string; check: string }> {
    return JSON.parse(await this.call('syncKeys', pair));
  }
  private call(fn: string, ...args: (string | number)[]) { return this.engine.call(fn, ...args); }

  private fetch(path: string, init: RequestInit, token: string) {
    return fetch(this.api + path, {
      ...init, cache: 'no-store', signal: AbortSignal.timeout(TIMEOUT_MS),
      headers: { ...(init.headers as Record<string, string> ?? {}), authorization: 'Bearer ' + token },
    });
  }

  stop() { clearInterval(this.timer); }
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
