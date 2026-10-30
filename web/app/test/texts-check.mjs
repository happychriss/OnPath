// No hard-coded user texts in the page's TypeScript, and every web text in every language (docs/design/web-planner.md
// §18; CLAUDE.md "UI texts EN + DE"). Plain Node, no browser: node test/texts-check.mjs
// 1. web/strings: each language folder has every English key, with the same placeholders (%1$s / %2$d …).
// 2. src/**/*.ts (comments removed): no text in markup (>Words<), in title / aria-label / placeholder attributes, or in
//    label / title / sub / textContent / status(…) / say(…) / error values – those go through t() / tp() (i18n.ts).
//    Internal errors (throw new Error(…), worker failures) stay English like the app's problem log.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../../..');
let bad = 0;
const fail = (m) => { bad++; console.log('FAIL ' + m); };

// ---- 1. web/strings ----
const root = path.join(repo, 'web/strings');
const read = (dir) => {
  const o = {};
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.xml'))) {
    const xml = fs.readFileSync(path.join(dir, f), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
    for (const m of xml.matchAll(/<string\s+name="([a-z0-9_]+)"\s*>([\s\S]*?)<\/string>/g)) o[m[1]] = m[2];
  }
  return o;
};
const en = read(path.join(root, 'values'));
const ph = (s) => [...s.matchAll(/%\d\$[sd]/g)].map((m) => m[0]).sort().join(' ');
for (const d of fs.readdirSync(root).filter((x) => x.startsWith('values-'))) {
  const other = read(path.join(root, d));
  for (const [k, v] of Object.entries(en)) {
    if (!(k in other)) fail(`${d}: '${k}' missing`);
    else if (ph(v) !== ph(other[k])) fail(`${d}: '${k}' placeholders differ (${ph(v)} vs ${ph(other[k])})`);
  }
  for (const k of Object.keys(other)) if (!(k in en)) fail(`${d}: '${k}' has no English text`);
}
for (const [k, v] of Object.entries(en)) if (!k.startsWith('web_')) fail(`'${k}': web keys start with web_`); else if (/\\'/.test(v)) fail(`'${k}': write ' plainly`);
// the app's words (owner 2026-10-30, app leads): a saved / shown thing is a route ("My routes" / "Meine Routen"), the
// phone is paired / verbunden and unpaired / getrennt – not "plans" / "Pläne" / "koppeln" (the verb "plan" is fine)
const notAppWords = { values: /\b(my|saved|synced|your|the) plans?\b(?! link)|\bplans (stay|sync|stop)/i, 'values-de': /Plän|[Kk]oppel|[Kk]opplung/ };
for (const [d, re] of Object.entries(notAppWords)) {
  for (const [k, v] of Object.entries(read(path.join(root, d)))) if (re.test(v)) fail(`${d}: '${k}' uses a word the app does not ("${v.match(re)[0]}") – use the app's (docs/design/web-planner.md §18)`);
}

// ---- 2. no texts in the TypeScript ----
const src = path.join(repo, 'web/app/src');
const files = [];
const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) {
  const p = path.join(d, e.name);
  if (e.isDirectory()) { if (e.name !== 'gen') walk(p); } else if (p.endsWith('.ts') && !p.endsWith('.d.ts')) files.push(p);
} };
walk(src);
// words that are not user texts: names, units, symbols
const OK = /^(OnPath|OpenStreetMap|© OpenStreetMap|Google|GPX|T\d|km|m|ms|MB|SIL Open Font License 1\.1|Edge|Firefox|Chrome|Safari|Android|iOS|Windows|macOS|Linux|EN|DE)$/;
/** [s] without its ${…} parts (nested braces too) and entities. */
function words(s) {
  let out = '', depth = 0;
  for (let i = 0; i < s.length; i++) {
    if (depth === 0 && s[i] === '$' && s[i + 1] === '{') { depth = 1; i++; continue; }
    if (depth > 0) { if (s[i] === '{') depth++; else if (s[i] === '}') depth--; continue; }
    out += s[i];
  }
  return (depth > 0 ? '' : out).replace(/&[a-z]+;/g, ' ').trim();
}
const isText = (s) => { const w = words(s); return /[A-Za-zÄÖÜäöü]{2,}/.test(w) && !OK.test(w) && !/^[a-z0-9_.\-#/:]+$/.test(w); };
for (const f of files) {
  const rel = path.relative(repo, f);
  const code = fs.readFileSync(f, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:'"`\\])\/\/[^\n]*/g, '$1');
  const lines = code.split('\n');
  lines.forEach((line, i) => {
    const at = `${rel}:${i + 1}`;
    if (/console\.(log|info|warn|error|debug)\(|failAll\(/.test(line)) return; // logs and internal failures stay English
    for (const m of line.matchAll(/<\/?[a-z][a-z0-9]*(?:\s[^<>]*)?>([^<>`'"]*?)(?=<)/g)) if (isText(m[1])) fail(`${at}: text in markup "${m[1].trim()}"`);
    for (const m of line.matchAll(/\b(title|aria-label|placeholder|alt)="([^"]*)"/g)) if (isText(m[2])) fail(`${at}: ${m[1]}="${m[2]}"`);
    for (const m of line.matchAll(/\b(label|title|sub|textContent|placeholder|error|message|line|button|text)\s*[:=]\s*(['"`])((?:(?!\2).)*)\2/g)) {
      if (isText(m[3])) fail(`${at}: ${m[1]} = "${m[3]}"`);
    }
    for (const m of line.matchAll(/\b(status|say|onProblem|Gone|prompt|confirm|alert)\(\s*(['"`])((?:(?!\2).)*)\2/g)) {
      if (isText(m[3])) fail(`${at}: ${m[1]}("${m[3]}")`);
    }
  });
}
if (bad) { console.log(`${bad} problem(s)`); process.exit(1); }
console.log(`texts ok: ${Object.keys(en).length} web keys in every language, no hard-coded texts in ${files.length} TypeScript files`);
