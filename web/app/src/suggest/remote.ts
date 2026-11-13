// Mechanism `remote` – FOR THE OWNER'S TEST ON THE ROAD (2026-11-14). The loops are computed by the tour-suggestions
// service on his computer at home (tools/suggest-poc/remote/remote_server.py), reached through his VPN; the address and
// the password come from the debug setting "Tourvorschläge (Test)" (testsetting.ts) and live only in this browser.
// OFF for everyone by default: without that setting this file does nothing – no request, no storage key.
//
// What leaves the browser when it is on: the wish (start point, activity, duration, taste) to the address set there,
// with the password in a header – nothing else, and to nobody else.
import type { Candidate, SuggestProvider, TourWish } from './types';
import { ProviderMessage } from './types';
import { pocBody, pocCandidate } from './poc';

/** The one storage key of the test setting. Present = switched on. Everything of the feature lives under this prefix. */
export const TEST_KEY = 'onpath.suggest.remote';
export const KEY_PREFIX = 'onpath.suggest';
/** How long one request may take before the page says "dauert zu lange" (the service's own limit is 90 s). */
export const REMOTE_TIMEOUT_MS = 120_000;

export interface TestSetting { url: string; token: string }

const read = (k: string): string | null => { try { return localStorage.getItem(k); } catch { return null; } }; // expected: storage blocked

/** The setting when it is switched on, else null. */
export function testSetting(get: (k: string) => string | null = read): TestSetting | null {
  const raw = get(TEST_KEY);
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Partial<TestSetting>;
    return typeof v.url === 'string' && v.url.trim() ? { url: v.url.trim(), token: typeof v.token === 'string' ? v.token : '' } : null;
  } catch { return null; } // expected: an old / broken value counts as off
}

export function saveTestSetting(s: TestSetting) {
  try { localStorage.setItem(TEST_KEY, JSON.stringify({ url: s.url.trim(), token: s.token.trim() })); } catch { /* expected: storage blocked – the setting cannot be kept */ }
}

/** Switched off: every key of the feature goes (the setting, the remembered wish, the dev choices) – nothing stays. */
export function clearTestSetting() {
  try { for (const k of Object.keys(localStorage)) if (k === KEY_PREFIX || k.startsWith(KEY_PREFIX + '.')) localStorage.removeItem(k); }
  catch { /* expected: storage blocked – nothing was stored either */ }
}

/** "donald.example.ts.net", "http://192.168.1.35:8780", "/suggest-remote/" → an absolute address ending with '/'. */
export function serviceBase(url: string, page: string = location.href): string {
  let u = url.trim();
  if (!/^[a-z]+:\/\//i.test(u) && !u.startsWith('/') && !u.startsWith('.')) u = 'https://' + u; // a bare name: https
  const abs = new URL(u, page).href;
  return abs.endsWith('/') ? abs : abs + '/';
}

/** A https page cannot call a http address (the browser blocks it) – except this computer itself. */
export function blockedByBrowser(base: string, page: string = location.href): boolean {
  const b = new URL(base), p = new URL(page);
  return p.protocol === 'https:' && b.protocol === 'http:' && !/^(localhost|127\.0\.0\.1|\[::1\])$/.test(b.hostname);
}

export type PingResult = 'ok' | 'password' | 'unreachable' | 'blocked' | 'slow';

/** "Verbindung prüfen": asks the service's /v1/ping with the password. Never throws. */
export async function pingRemote(s: TestSetting, fetcher: typeof fetch = fetch, timeoutMs = 10_000): Promise<PingResult> {
  let base: string;
  try { base = serviceBase(s.url); } catch { return 'unreachable'; } // expected: not an address
  if (blockedByBrowser(base)) return 'blocked';
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), timeoutMs);
  try {
    const r = await fetcher(base + 'v1/ping', { headers: { 'X-OnPath-Token': s.token }, signal: abort.signal, cache: 'no-store' });
    if (r.status === 401) return 'password';
    if (!r.ok) return 'unreachable';
    const d = (await r.json()) as { ok?: boolean };
    return d.ok ? 'ok' : 'unreachable';
  } catch (e) {
    return (e as Error)?.name === 'AbortError' ? 'slow' : 'unreachable'; // expected: VPN off, computer asleep, wrong address
  } finally { clearTimeout(timer); }
}

export interface RemoteTexts { unreachable: string; password: string; slow: string; blocked: string; busy: string; preparing: string }

interface RemoteAnswer {
  error?: string; message?: string; message_en?: string; preparing?: boolean;
  candidates?: Parameters<typeof pocCandidate>[0][];
}

export function remoteProvider(setting: () => TestSetting | null, texts: () => RemoteTexts, fetcher: typeof fetch = fetch): SuggestProvider {
  return {
    id: 'remote',
    devName: 'remote – der Rechner zu Hause',
    async run(wish: TourWish, ctx, emit: (c: Candidate) => void) {
      const s = setting();
      if (!s) return; // not switched on: nothing is asked
      const tx = texts();
      const base = serviceBase(s.url);
      if (blockedByBrowser(base)) throw new ProviderMessage(tx.blocked);
      // the service chooses the method itself (its owner's setting); the wish goes as the POC's few controls
      const { method: _m, ...body } = pocBody(wish, '');
      void _m;
      const abort = new AbortController();
      const stop = () => abort.abort();
      ctx.signal.addEventListener('abort', stop);
      let timedOut = false;
      const timer = setTimeout(() => { timedOut = true; abort.abort(); }, REMOTE_TIMEOUT_MS);
      let r: Response;
      try {
        r = await fetcher(base + 'v1/suggest', { method: 'POST', headers: { 'X-OnPath-Token': s.token, 'Content-Type': 'application/json' },
          body: JSON.stringify(body), signal: abort.signal, cache: 'no-store' });
      } catch (e) {
        if (ctx.signal.aborted) return; // cancelled or superseded: nothing to say
        throw new ProviderMessage(timedOut ? tx.slow : tx.unreachable, { cause: e });
      } finally { clearTimeout(timer); ctx.signal.removeEventListener('abort', stop); }
      if (r.status === 401) throw new ProviderMessage(tx.password);
      if (r.status === 429) throw new ProviderMessage(tx.busy);
      if (r.status === 504) throw new ProviderMessage(tx.slow);
      let d: RemoteAnswer;
      try { d = (await r.json()) as RemoteAnswer; } catch (e) { throw new ProviderMessage(tx.unreachable, { cause: e }); } // expected: not our service at that address
      const said = ctx.lang === 'de' ? d.message : d.message_en ?? d.message;
      if (d.preparing) throw new ProviderMessage(said ?? tx.preparing);
      if (d.error) { if (said) throw new ProviderMessage(said); throw new Error(`remote: ${d.error}`); }
      if (!r.ok) throw new ProviderMessage(tx.unreachable);
      for (const c of d.candidates ?? []) emit({ ...pocCandidate(c, ctx.lang), id: `remote-${c.id}` });
    },
  };
}
