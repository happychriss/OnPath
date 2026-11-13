// The switch and the choice of mechanism (docs/design/tour-suggestions.md "Planner framework").
//
// THE SWITCH IS OFF for everyone. Two ways to turn it on, both only in this one browser:
// 1. the debug setting "Tourvorschläge (Test)" (testsetting.ts, reached with ?debug in the address): on + the address
//    of the owner's computer at home + the password → the mechanism `remote` (remote.ts). This works on the live
//    address too – it is the owner's test on the road (2026-11-14). Switched off there, every key of the feature goes.
// 2. where the page runs on this computer (localhost / 127.0.0.1): ?suggest=1 in the address or localStorage
//    onpath.suggest = "1" → `poc` (dev build) / `ring` (the stand-in). Never on plan.onpath.earth.
// With it off the page reads these values and does nothing else – no menu row, no request, no storage key written.
//
// A new mechanism: write a SuggestProvider (types.ts) in its own file and add it to [providers] below. Nothing else
// changes – the wish, the cards, "Übernehmen" and the tests stay as they are.
import { pocProvider } from './poc';
import { ring } from './ring';
import { remoteProvider, testSetting, type RemoteTexts } from './remote';
import type { SuggestProvider } from './types';

const LOCAL = /^(localhost|127\.0\.0\.1|\[::1\])$/;
const stored = (k: string): string | null => { try { return localStorage.getItem(k); } catch { return null; } }; // expected: storage blocked

/** Tour suggestions are offered on this page. */
export function suggestEnabled(loc: Pick<Location, 'hostname' | 'search'> = location, read: (k: string) => string | null = stored): boolean {
  if (testSetting(read)) return true; // the debug setting: on, with an address – on any host
  if (!LOCAL.test(loc.hostname)) return false;
  const q = new URLSearchParams(loc.search).get('suggest');
  if (q != null) return q === '1';
  return read('onpath.suggest') === '1';
}

/** The `remote` mechanism's plain words, set by the page (ui.ts) – the registry itself has no texts. */
let remoteTexts: () => RemoteTexts = () => ({ unreachable: 'Rechner nicht erreichbar', password: 'Falsches Passwort', slow: 'Das dauert zu lange',
  blocked: 'Der Browser blockiert diese Adresse', busy: 'Der Rechner rechnet gerade', preparing: 'Die Karte wird vorbereitet' });
export function setRemoteTexts(f: () => RemoteTexts) { remoteTexts = f; }

/**
 * The mechanisms this page offers, the default first.
 * - `remote`: only with the debug setting on (remote.ts) – then it is the default, on every host.
 * - `poc`: only in a dev build on this computer (Vite dev server, which proxies /poc/ to the POC).
 * - `ring`: the in-browser stand-in, only on this computer (the built page on localhost: tests). Never on the live site.
 * - `core` (not built yet): the network search in the planner's own engine – see core-slot.ts; it goes first here.
 */
export function providers(search = location.search, hostname = location.hostname, read: (k: string) => string | null = stored): SuggestProvider[] {
  const list: SuggestProvider[] = [];
  if (testSetting(read)) list.push(remoteProvider(() => testSetting(read), () => remoteTexts()));
  if (LOCAL.test(hostname)) {
    if (import.meta.env.DEV) list.push(pocProvider(new URL('./poc/', location.href).href, pocMethod(search)));
    list.push(ring);
  }
  return list;
}

/** The POC's method for the `poc` mechanism: ?suggestPoc=a|b|c (default b; c once the POC has it). */
export function pocMethod(search = location.search): string {
  const m = (new URLSearchParams(search).get('suggestPoc') ?? stored('onpath.suggest.poc') ?? 'b').toLowerCase();
  return /^[a-z]$/.test(m) ? m : 'b';
}

/** The chosen mechanism: ?suggestVia=<id>, else localStorage onpath.suggest.via, else the first one. */
export function chosenProvider(list: SuggestProvider[] = providers(), search = location.search, read: (k: string) => string | null = stored): SuggestProvider {
  const want = new URLSearchParams(search).get('suggestVia') ?? read('onpath.suggest.via');
  return list.find((p) => p.id === want) ?? list[0];
}
