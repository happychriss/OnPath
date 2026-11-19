// The planner's finished legs in the browser (owner 2026-11-18: München–Venedig opened again routed every leg for
// minutes) – the phone's leg store (data/LegFiles, files/legs/) in IndexedDB. The engine decides everything about a leg
// (its key, whether it is still fresh – WebLegStore.kt); this file only keeps the texts, bounded like LegFiles: at most
// MAX_LEGS legs and MAX_BYTES, the least recently used go first (down to 80 %). Runs in the engine worker.
// Robust first: no IndexedDB (an old browser, a blocked private window), a full quota, a broken store – the plan routes
// its legs as before; each failure is reported once ([note] → the page's problem log), never thrown.

/** Legs kept: as the phone (LegFiles.MAX_FILES / MAX_BYTES) – a 35 km bike leg is ~50–100 KB of text. */
export const MAX_LEGS = 3_000;
export const MAX_BYTES = 40 * 1024 * 1024;
const DB = 'onpath-legs', LEGS = 'legs', META = 'meta';
interface Meta { bytes: number; used: number }

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
}
function done(tx: IDBTransaction): Promise<void> {
  return new Promise((res, rej) => { tx.oncomplete = () => res(); tx.onerror = () => rej(tx.error); tx.onabort = () => rej(tx.error ?? new Error('aborted')); });
}

export class LegStoreIdb {
  private dbp: Promise<IDBDatabase | null> | null = null;
  /** Legs and bytes in the store – counted once, then kept up to date by [write]. */
  private count = -1;
  private bytes = 0;

  constructor(private note: (message: string, e?: unknown) => void, private maxLegs = MAX_LEGS, private maxBytes = MAX_BYTES) {}

  private db(): Promise<IDBDatabase | null> {
    return (this.dbp ??= new Promise((res) => {
      try {
        if (typeof indexedDB === 'undefined') { this.note('Leg store: no IndexedDB – legs are routed again after a reload'); res(null); return; }
        const r = indexedDB.open(DB, 1);
        r.onupgradeneeded = () => { r.result.createObjectStore(LEGS); r.result.createObjectStore(META); };
        r.onsuccess = () => res(r.result);
        r.onerror = () => { this.note('Leg store: IndexedDB not opened – legs are routed again after a reload', r.error); res(null); };
        r.onblocked = () => { this.note('Leg store: IndexedDB blocked – legs are routed again after a reload'); res(null); };
      } catch (e) { this.note('Leg store: IndexedDB not opened – legs are routed again after a reload', e); res(null); }
    }));
  }

  /** The stored texts of [names] (those there); each read counts as a use. Never throws: a failure = no legs. */
  async read(names: string[]): Promise<Map<string, string>> {
    const out = new Map<string, string>();
    if (!names.length) return out;
    const d = await this.db();
    if (!d) return out;
    try {
      const tx = d.transaction([LEGS, META], 'readwrite');
      const legs = tx.objectStore(LEGS), meta = tx.objectStore(META);
      const now = Date.now();
      await Promise.all(names.map(async (n) => {
        const text = await req(legs.get(n)) as unknown;
        if (typeof text !== 'string') return;
        out.set(n, text);
        meta.put({ bytes: text.length, used: now } satisfies Meta, n); // the oldest use goes first when the store is full
      }));
      await done(tx);
    } catch (e) { this.note('Leg store: legs not read – routed again', e); }
    return out;
  }

  /** Keeps [legs] (name, text); beyond the bounds the least recently used go. Never throws. */
  async write(legs: [string, string][]): Promise<void> {
    if (!legs.length) return;
    const d = await this.db();
    if (!d) return;
    try {
      if (this.count < 0) await this.countAll(d);
      const tx = d.transaction([LEGS, META], 'readwrite');
      const st = tx.objectStore(LEGS), meta = tx.objectStore(META);
      const now = Date.now();
      const before = await Promise.all(legs.map(([n]) => req(meta.get(n)) as Promise<Meta | undefined>));
      legs.forEach(([n, text], i) => {
        st.put(text, n);
        meta.put({ bytes: text.length, used: now } satisfies Meta, n);
        const b = before[i];
        if (b) this.bytes -= b.bytes; else this.count++;
        this.bytes += text.length;
      });
      await done(tx);
      if (this.count > this.maxLegs || this.bytes > this.maxBytes) await this.prune(d, new Set(legs.map(([n]) => n)));
    } catch (e) {
      this.count = -1; // counted again next time
      this.note('Leg store: legs not stored (storage full or blocked) – routed again next time', e);
    }
  }

  private async countAll(d: IDBDatabase) {
    const all = await req(d.transaction(META, 'readonly').objectStore(META).getAll()) as Meta[];
    this.count = all.length;
    this.bytes = all.reduce((s, m) => s + (m?.bytes ?? 0), 0);
  }

  /** The least recently used legs go, down to 80 % of both bounds; [keep] (just written) stay. */
  private async prune(d: IDBDatabase, keep: Set<string>) {
    const tx = d.transaction([LEGS, META], 'readwrite');
    const legs = tx.objectStore(LEGS), meta = tx.objectStore(META);
    const [keys, vals] = await Promise.all([req(meta.getAllKeys()), req(meta.getAll()) as Promise<Meta[]>]);
    const all = keys.map((k, i) => ({ k: String(k), m: vals[i] })).sort((a, b) => (a.m?.used ?? 0) - (b.m?.used ?? 0));
    let n = all.length, b = all.reduce((s, x) => s + (x.m?.bytes ?? 0), 0), gone = 0;
    for (const x of all) {
      if (n <= this.maxLegs * 0.8 && b <= this.maxBytes * 0.8) break;
      if (keep.has(x.k)) continue;
      legs.delete(x.k); meta.delete(x.k);
      n--; b -= x.m?.bytes ?? 0; gone++;
    }
    await done(tx);
    this.count = n; this.bytes = b;
    console.info(`[routing] leg store full: ${gone} least used legs removed (${n} legs, ${Math.round(b / 1024)} KB left)`);
  }
}
