// The page's language and texts (docs/design/web-planner.md §18; CLAUDE.md "UI texts EN + DE", docs/i18n.md). Texts
// come from src/gen/texts.ts (scripts/gen-strings.mjs): the app's own strings where the page shows what the app shows,
// the web's own (web/strings) for the rest – both in every language folder. The engine writes its texts (plan sheet,
// warnings, menus) in the same language (EngineClient.configure → Txt.setLanguage).
// Language: ?lang=de in the address (not remembered), else the header's switch (remembered in this browser), else the
// browser's language (de* → German), else English.
import { LANGS, TEXTS } from './gen/texts';

export type Lang = (typeof LANGS)[number];
const STORE = 'onpath.lang';
const known = (l: string | null | undefined): Lang | null => {
  const s = (l ?? '').toLowerCase().slice(0, 2);
  return (LANGS as readonly string[]).includes(s) ? (s as Lang) : null;
};

function choose(): Lang {
  const fromUrl = known(new URLSearchParams(location.search).get('lang'));
  if (fromUrl) return fromUrl;
  try { const s = known(localStorage.getItem(STORE)); if (s) return s; } catch { /* expected: storage blocked */ }
  for (const l of navigator.languages?.length ? navigator.languages : [navigator.language]) { const k = known(l); if (k) return k; }
  return 'en';
}

export const lang: Lang = choose();
export const languages: readonly Lang[] = LANGS;
document.documentElement.lang = lang;

/** XmlStrings.format's rule: `%N$s` / `%N$d` → the N-th argument as text, nothing else touched. */
export function fill(raw: string, ...args: (string | number)[]): string {
  return raw.replace(/%(\d)\$[sd]/g, (m, n) => (args[Number(n) - 1] != null ? String(args[Number(n) - 1]) : m));
}

const reported = new Set<string>();
function raw(key: string): string | [string | null, string] | undefined {
  const v = TEXTS[lang]?.[key] ?? TEXTS.en[key];
  if (v == null && !reported.has(key)) { reported.add(key); console.warn(`text '${key}' missing`); }
  return v;
}

/** A text in the page's language, placeholders filled. */
export function t(key: string, ...args: (string | number)[]): string {
  const v = raw(key);
  return fill(v == null ? key : typeof v === 'string' ? v : v[1], ...args);
}

/** A plural text: `one` for 1 (when the language has it), else `other`. */
export function tp(key: string, n: number, ...args: (string | number)[]): string {
  const v = raw(key);
  const s = v == null ? key : typeof v === 'string' ? v : (n === 1 ? v[0] : null) ?? v[1];
  return fill(s, ...args);
}

/** The switch in the header: remembered, then the page reloads (the plan stays – it is in the address). */
export function switchLanguage(l: Lang) {
  try { localStorage.setItem(STORE, l); } catch { /* expected: storage blocked – the switch then lasts for this load only */ }
  const u = new URL(location.href);
  u.searchParams.delete('lang');
  if (u.href === location.href) location.reload(); // the same address with a #plan would only jump, not reload
  else location.replace(u.href);
}

/** A number for the user: number(12.46, 1) = "12.5" / "12,5" (the app's Format.number). */
export function number(v: number, decimals: number): string {
  return new Intl.NumberFormat(lang === 'en' ? 'en-US' : lang, { minimumFractionDigits: decimals, maximumFractionDigits: decimals, useGrouping: false }).format(v);
}

/** The app's Format.distance: "850 m", "12.5 km" / "12,5 km", "123 km". */
export function distance(m: number): string {
  return m < 1000 ? `${Math.trunc(m)} m` : m < 100_000 ? `${number(m / 1000, 1)} km` : `${Math.trunc(m / 1000)} km`;
}

/** Locale tag for dates and times in the page's language. */
export const locale = lang === 'en' ? 'en-GB' : lang;
