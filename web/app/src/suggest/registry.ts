// The switch and the choice of mechanism (docs/design/tour-suggestions.md "Planner framework").
//
// THE SWITCH IS OFF. It can be turned on only where the page runs on this computer (localhost / 127.0.0.1), with
// ?suggest=1 in the address or localStorage onpath.suggest = "1". On plan.onpath.earth it cannot be turned on at all:
// with it off the page reads these two values and does nothing else – no menu row, no request, no storage key written.
//
// A new mechanism: write a SuggestProvider (types.ts) in its own file and add it to [providers] below. Nothing else
// changes – the wish, the cards, "Übernehmen" and the tests stay as they are.
import { pocProvider } from './poc';
import { ring } from './ring';
import type { SuggestProvider } from './types';

const LOCAL = /^(localhost|127\.0\.0\.1|\[::1\])$/;
const stored = (k: string): string | null => { try { return localStorage.getItem(k); } catch { return null; } }; // expected: storage blocked

/** Tour suggestions are offered on this page. */
export function suggestEnabled(loc: Pick<Location, 'hostname' | 'search'> = location, read: (k: string) => string | null = stored): boolean {
  if (!LOCAL.test(loc.hostname)) return false;
  const q = new URLSearchParams(loc.search).get('suggest');
  if (q != null) return q === '1';
  return read('onpath.suggest') === '1';
}

/**
 * The mechanisms this build offers, the default first.
 * - `poc`: only in a dev build (Vite dev server, which proxies /poc/ to the POC on this computer).
 * - `ring`: the in-browser stand-in; the default only where `poc` does not exist (the built page on localhost: tests).
 * - `core` (not built yet): the network search in the planner's own engine – see core-slot.ts; it goes first here.
 */
export function providers(search = location.search): SuggestProvider[] {
  const list: SuggestProvider[] = [];
  if (import.meta.env.DEV) list.push(pocProvider(new URL('./poc/', location.href).href, pocMethod(search)));
  list.push(ring);
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
