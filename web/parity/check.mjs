// Web ⇄ app parity check (docs/design/web-planner.md §14). The web never drifts silently from the app:
// - mirrors (web/parity.json): app logic the web re-implements. If the app file changed since the mirror was last
//   checked (git blob hash), this FAILS – look at the app change, adjust the web, then record it with --update.
// - shared: every app file the engine compiles as it is (web/engine/build.gradle.kts appFiles + appTests), the string
//   files, BRouter's jars and profiles. They reach the web by merging main; changes are listed as warnings, and lines
//   that matter for performance / memory (caches, limits, timeouts, buffers – the phone's memory work) are shown
//   added / removed, so the web side (Wasm heap, worker, prefetch) can be checked too.
// - watch: app files with plan behaviour the web neither compiles nor mirrors: changes are listed.
// Works on a shallow checkout (only hashes of the working tree, no history).
// Usage: node web/parity/check.mjs [--update [appPath …]] [--report out.md]
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const manifestFile = path.join(repo, 'web/parity.json');
const m = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
const args = process.argv.slice(2);
const update = args.includes('--update');
const only = update ? args.slice(args.indexOf('--update') + 1).filter((a) => !a.startsWith('--')) : [];
const reportAt = args.includes('--report') ? args[args.indexOf('--report') + 1] : null;

/** git's blob hash of a working-tree file (same as `git hash-object`), null when it is missing. */
function blob(rel) {
  const f = path.join(repo, rel);
  if (!fs.existsSync(f)) return null;
  const data = fs.readFileSync(f);
  return crypto.createHash('sha1').update(`blob ${data.length}\0`).update(data).digest('hex');
}

/** Lines that matter for speed / memory: caches, limits, timeouts, budgets, buffer sizes, heap. */
const PERF = /\b(cache|Cache|CACHE|LruCache|maxSize|MAX_[A-Z0-9_]*|[A-Z0-9_]*_(MS|BYTES|KB|MB|LIMIT|CAP|SIZE)\b|limit|Limit|LIMIT|memory|Memory|OutOfMemory|timeout|Timeout|TIMEOUT|budget|Budget|capacity|Xmx|heap|Heap|pool|Pool|ByteArray\(\d|maxRunningTime|nodesCache|useMemoryMapped|ensureCapacity|trimToSize|softReference|WeakReference|SoftReference)\b/;
function perfLines(rel) {
  const f = path.join(repo, rel);
  if (!fs.existsSync(f) || !/\.(kt|java|brf)$/.test(rel)) return [];
  return [...new Set(fs.readFileSync(f, 'utf8').split('\n').map((l) => l.trim()).filter((l) => l && PERF.test(l) && !l.startsWith('import ')))];
}

/** The app files the engine compiles: appFiles + appTests of web/engine/build.gradle.kts, strings, BRouter. */
function sharedFiles() {
  const g = fs.readFileSync(path.join(repo, 'web/engine/build.gradle.kts'), 'utf8');
  const list = (name) => [...(g.match(new RegExp(`val ${name} = listOf\\(([\\s\\S]*?)\\n\\)`))?.[1] ?? g.match(new RegExp(`val ${name} = listOf\\(([^)]*)\\)`))?.[1] ?? '')
    .matchAll(/"([^"]+\.kt)"/g)].map((x) => x[1]);
  const main = list('appFiles').map((f) => 'app/src/main/java/com/onpath/app/' + f);
  const tests = list('appTests').map((f) => 'app/src/test/java/com/onpath/app/' + f);
  const dir = (d, re) => fs.readdirSync(path.join(repo, d)).filter((f) => re.test(f)).sort().map((f) => `${d}/${f}`);
  return [...main, ...tests, 'app/src/main/java/btools/router/OnPathHints.java',
    ...dir('strings/src/commonMain/composeResources/values', /\.xml$/), ...dir('strings/src/commonMain/composeResources/values-de', /\.xml$/),
    ...dir('app/libs', /\.jar$/),
    ...dir('app/src/main/assets/brouter', /\.(brf|dat)$/), 'app/src/main/assets/style/f2/onpath.json'];
}

const errors = [], warnings = [], notes = [];
const shared = sharedFiles();
if (update) {
  const pick = (e) => !only.length || only.includes(e.app);
  for (const e of [...m.mirrors, ...m.watch]) if (pick(e)) e.blob = blob(e.app);
  if (!only.length) {
    m.shared = Object.fromEntries(shared.map((f) => [f, { blob: blob(f), perf: perfLines(f) }]));
  } else {
    for (const f of only) if (m.shared?.[f]) m.shared[f] = { blob: blob(f), perf: perfLines(f) };
  }
  fs.writeFileSync(manifestFile, JSON.stringify(m, null, 2) + '\n');
  console.log(`parity.json updated (${only.length ? only.join(', ') : 'everything'})`);
  process.exit(0);
}

for (const e of m.mirrors) {
  const now = blob(e.app);
  if (now == null) errors.push(`mirrored app file is gone: ${e.app} (${e.what}) – mirrored in ${e.web.join(', ')}`);
  else if (now !== e.blob) errors.push(`app changed since the web mirror was checked: ${e.app} – ${e.what} → check ${e.web.join(', ')}, then: node web/parity/check.mjs --update ${e.app}`);
}
for (const e of m.watch) {
  const now = blob(e.app);
  if (now !== e.blob) warnings.push(`app file with plan behaviour changed (not on the web): ${e.app} – ${e.why}`);
}
const known = m.shared ?? {};
for (const f of shared) {
  const k = known[f];
  if (!k) { errors.push(`new shared file not in parity.json: ${f} – run node web/parity/check.mjs --update`); continue; }
  if (blob(f) === k.blob) continue;
  const before = new Set(k.perf ?? []), now = perfLines(f), after = new Set(now);
  const added = now.filter((l) => !before.has(l)), removed = [...before].filter((l) => !after.has(l));
  if (added.length || removed.length) {
    warnings.push(`PERF/MEMORY-relevant change in shared code: ${f}\n` + [...removed.map((l) => `      - ${l}`), ...added.map((l) => `      + ${l}`)].join('\n'));
  } else notes.push(`shared code changed (compiled into the web as it is): ${f}`);
}
for (const f of Object.keys(known)) if (!shared.includes(f)) notes.push(`no longer shared: ${f} (run --update)`);

const gh = !!process.env.GITHUB_ACTIONS;
for (const e of errors) console.log(gh ? `::error title=web parity::${e}` : `ERROR ${e}`);
for (const w of warnings) console.log(gh ? `::warning title=web parity::${w.replace(/\n/g, '%0A')}` : `WARN  ${w}`);
for (const n of notes) console.log(`note  ${n}`);
if (reportAt) {
  const md = ['# Web ⇄ app parity report', '', `${errors.length} mirrored file(s) to check, ${warnings.length} warning(s), ${notes.length} shared change(s).`, '',
    '## Mirrors to adjust (CI fails until checked)', ...errors.map((e) => `- ${e}`), '', '## Warnings (perf / memory, unmirrored plan behaviour)',
    ...warnings.map((w) => `- ${w}`), '', '## Shared code changed (reaches the web by the merge)', ...notes.map((n) => `- ${n}`), ''].join('\n');
  fs.writeFileSync(reportAt, md);
}
console.log(errors.length ? `parity: ${errors.length} mirror(s) need a look (see above)` : `parity: ok (${warnings.length} warning(s), ${notes.length} note(s))`);
process.exit(errors.length ? 1 : 0);
