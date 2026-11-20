// The viewer's side of the live box (docs/design/live-tracking.md §5.1, §6; the server: web/host/src/live.js): asks
// `GET /live/v1/<box>?since=N` with the link's read token – only when something new can be there. When to ask again is
// the shared rule LivePolicy.viewerNext (engine.liveNext), never a fixed tick: shortly after the phone's next planned
// upload, slower and slower without news, at once when the tab comes back (or the network), nothing while hidden,
// never again after the end or once the box is gone.
import type { LiveEngine, Head, Route, Chunk } from './engine';

export interface BoxState {
  /** 'gone' = 410 (stopped / expired); 'broken' = no key in the link or data that does not open with it. */
  dead: 'gone' | 'broken' | null;
  head: Head | null;
  headBlob: string;
  route: [number, number][];
  /** The chunks that count (below the head's `chunks`), in order. */
  chunks: Chunk[];
  /** The server's end mark (its clock, ms) or null. */
  ended: number | null;
  /** ms since the phone's last upload, by the server's clock plus the time since that answer; null = no answer yet. */
  sinceLastWrite: () => number | null;
  /** The last question failed (offline, 429, 5xx): the page says so quietly. */
  unreachable: boolean;
}

const ASK_FLOOR_MS = 5_000; // a tab flipped back and forth does not ask each time (LivePolicy.VIEWER_FLOOR_MS)

export class LiveBox {
  private since = 0;
  private blobs = new Map<string, string>();
  private chunks = new Map<number, Chunk>();
  private head: Head | null = null;
  private headBlob = '';
  private route: [number, number][] = [];
  private ended: number | null = null;
  private dead: 'gone' | 'broken' | null = null;
  private unreachable = false;
  /** server now − lastWrite at the last answer, and when (this page's steady clock) that answer came */
  private age: { ms: number; at: number } | null = null;
  private emptyAsks = 0;
  private retryAfter = -1;
  private timer = 0;
  private asking = false;
  private lastAsk = -Infinity;
  /** How many questions went to the server (the smoke counts them against §5.1). */
  asks = 0;
  /** In how many ms the next question was planned (-1 = never) – for the tests. */
  planned = 0;

  constructor(private engine: LiveEngine, private link: string, private api: string, private keys: { box: string; token: string } | null,
    private onChange: (s: BoxState) => void) {
    if (!keys) this.dead = 'broken';
    document.addEventListener('visibilitychange', () => { if (!document.hidden) this.now(); else this.pause(); });
    addEventListener('online', () => this.now());
  }

  state(): BoxState {
    const max = this.head?.chunks;
    const chunks = [...this.chunks.values()].filter((c) => max == null || c.n < max).sort((a, b) => a.n - b.n);
    return { dead: this.dead, head: this.head, headBlob: this.headBlob, route: this.route, chunks, ended: this.ended,
      sinceLastWrite: () => (this.age ? this.age.ms + (performance.now() - this.age.at) : null), unreachable: this.unreachable };
  }

  private get over() { return this.dead != null || this.ended != null || this.head?.state === 'ended'; }

  start() { this.onChange(this.state()); if (!document.hidden) void this.ask(); }

  /** The tab is back / the network is back: ask at once (not more often than the floor, never when it is over). */
  private now() {
    if (this.over || document.hidden) return;
    const wait = this.lastAsk + ASK_FLOOR_MS - performance.now();
    clearTimeout(this.timer);
    if (wait > 0) this.timer = window.setTimeout(() => void this.ask(), wait); else void this.ask();
  }
  /** Tests only: a question right now, whatever the pacing says. */
  askNow() { clearTimeout(this.timer); return this.ask(); }
  private pause() { clearTimeout(this.timer); this.timer = 0; }

  private plan() {
    clearTimeout(this.timer); this.timer = 0;
    const since = this.age ? this.age.ms + (performance.now() - this.age.at) : 0;
    const ms = this.engine.liveNext(since, this.head ? this.head.nextS : -1, this.emptyAsks, this.over, this.retryAfter);
    this.planned = ms;
    if (ms < 0 || document.hidden) return;
    this.timer = window.setTimeout(() => void this.ask(), ms);
  }

  private async ask() {
    if (this.asking || this.over || !this.keys || document.hidden) return;
    this.asking = true; this.lastAsk = performance.now();
    let fresh = false;
    try {
      this.retryAfter = -1;
      for (let more = true; more;) { // the server pages (≤ 500 items); a page never cuts an upload in two
        more = false;
        this.asks++;
        const r = await fetch(`${this.api}${this.keys.box}?since=${this.since}`, { headers: { authorization: 'Bearer ' + this.keys.token }, cache: 'no-store' });
        if (r.status === 410) { this.dead = 'gone'; this.forget(); break; }
        if (r.status === 404) { this.unreachable = false; break; } // not there yet (or long gone): "no data yet"
        if (r.status === 401 || r.status === 403) { this.dead = 'broken'; break; }
        if (!r.ok) { this.unreachable = true; this.retryAfter = Math.max(0, Number(r.headers.get('retry-after') ?? 0) * 1000 || 0); break; }
        const j = await r.json() as { rev: number; now: number; lastWrite: number; ended: number | null; more?: boolean;
          items: { name: string; rev: number; blob: string }[] };
        this.unreachable = false;
        this.age = { ms: Math.max(0, j.now - j.lastWrite), at: performance.now() };
        if (j.ended != null) this.ended = j.ended;
        if (j.rev > this.since) fresh = true;
        for (const it of j.items) if (!this.take(it.name, it.blob)) { this.dead = 'broken'; break; }
        this.since = Math.max(this.since, j.rev);
        more = !!j.more && this.dead == null;
      }
    } catch { // expected: offline or the server not reachable – said quietly, asked again later
      this.unreachable = true; this.retryAfter = 0;
    }
    this.emptyAsks = fresh ? 0 : this.emptyAsks + 1;
    this.asking = false;
    this.onChange(this.state());
    this.plan();
  }

  /** One item from the server, opened with the link's key; false = it does not open (a wrong or damaged link). */
  private take(name: string, blob: string): boolean {
    if (this.blobs.get(name) === blob) return true;
    const v = JSON.parse(this.engine.liveOpen(this.link, name, blob)) as Head | Route | Chunk | null;
    if (!v) return false;
    this.blobs.set(name, blob);
    if (v.kind === 'head') { this.head = v; this.headBlob = blob; }
    else if (v.kind === 'route') this.route = v.line;
    else this.chunks.set(v.n, v);
    return true;
  }

  /** Stopped: nothing of the tour stays in the page. */
  private forget() { this.blobs.clear(); this.chunks.clear(); this.head = null; this.headBlob = ''; this.route = []; }
}
