// Web ⇄ app parity check (docs/design/web-planner.md §14). The web never drifts silently from the app:
// - mirrors (web/parity.json): app logic the web re-implements. If the app file changed since the mirror was last
//   checked (git blob hash), this FAILS – look at the app change, adjust the web, then record it with --update.
// - shared: every file the engine compiles as it is (the :core folder minus stand-ins / excludes, the app files in app/web-shared-files.txt, its tests, the slices in state/web-ready.txt), the string
//   files, BRouter's jars and profiles. They reach the web by merging main; changes are listed as warnings, and lines
//   that matter for performance / memory (caches, limits, timeouts, buffers – the phone's memory work) are shown
//   added / removed, so the web side (Wasm heap, worker, prefetch) can be checked too.
// - watch: app files with plan behaviour the web neither compiles nor mirrors: changes are listed.
// - refs (2026-11-27, after the planner's search lost „Berlin“: search.ts built the Photon request itself, a copy of the
//   app's PhotonProvider no list knew of): every place in web/app/src that names app code ("the app's PhotonProvider…")
//   must be accounted for – either a mirror (the TS file in that app file's mirror) or a line in refs that says how the
//   page uses it ("engine: …" = it asks the engine, holds no copy; "look: …" = named for context only). A new mention
//   that is neither FAILS: decide whether it is a copy (→ mirror, then CI tracks it) or not (→ refs, with the reason).
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

/** Every *.kt below a folder (repo-relative, sorted). */
function walk(rel) {
  const out = [];
  if (!fs.existsSync(path.join(repo, rel))) return out;
  for (const e of fs.readdirSync(path.join(repo, rel), { withFileTypes: true })) {
    const r = `${rel}/${e.name}`;
    if (e.isDirectory()) out.push(...walk(r));
    else if (e.name.endsWith('.kt')) out.push(r);
  }
  return out.sort();
}

/**
 * The core files the engine compiles: the whole folder core/src/main/kotlin minus the files a stand-in replaces (the same path
 * below web/engine/src/main/kotlin/com/onpath/app/) minus web/engine/core-excludes.txt (web/engine/build.gradle.kts, the lock in
 * tools/dev/core_move.py and SharedFilesRuleTest compute the same set).
 */
function coreCompiled() {
  const base = 'core/src/main/kotlin/com/onpath/app/';
  const standIns = new Set(walk('web/engine/src/main/kotlin/com/onpath/app').map((f) => f.slice('web/engine/src/main/kotlin/com/onpath/app/'.length)));
  const excludes = new Set(fs.readFileSync(path.join(repo, 'web/engine/core-excludes.txt'), 'utf8').split('\n').map((l) => l.replace(/#.*/, '').trim()).filter(Boolean));
  return walk(base.slice(0, -1)).filter((f) => !standIns.has(f.slice(base.length)) && !excludes.has(f.slice(base.length)));
}

/** The files the engine compiles: the core folder (minus stand-ins / excludes), the app files app/web-shared-files.txt names ([files]), its tests ([tests]), strings, BRouter. */
function sharedFiles() {
  let section = '';
  const rows = fs.readFileSync(path.join(repo, 'app/web-shared-files.txt'), 'utf8').split('\n').map((l) => l.replace(/#.*/, '').trim()).filter(Boolean)
    .flatMap((l) => (l.startsWith('[') ? ((section = l), []) : [[section, l]]));
  // a listed file that is in none of the places is an error, never skipped (the engine build fails the same way)
  const home = (dirs, f) => {
    const hit = dirs.find((d) => fs.existsSync(path.join(repo, d + f)));
    if (!hit) throw new Error(`app/web-shared-files.txt lists ${f}, but it is in none of: ${dirs.join(', ')}`);
    return hit + f;
  };
  const main = rows.filter(([s]) => s === '[files]').map(([, f]) => home(['app/src/main/java/com/onpath/app/'], f));
  const tests = rows.filter(([s]) => s === '[tests]').map(([, f]) => home(['core/src/test/kotlin/com/onpath/app/', 'core/src/testkit/kotlin/com/onpath/app/', 'app/src/test/java/com/onpath/app/'], f));
  const dir = (d, re) => fs.readdirSync(path.join(repo, d)).filter((f) => re.test(f)).sort().map((f) => `${d}/${f}`);
  // the web-ready slices of :state (state/web-ready.txt – the engine compiles them as they are, the page drives them)
  const state = fs.readFileSync(path.join(repo, 'state/web-ready.txt'), 'utf8').split('\n').map((l) => l.replace(/#.*/, '').trim()).filter(Boolean)
    .map((f) => {
      const p = 'state/src/main/kotlin/com/onpath/app/' + f;
      if (!fs.existsSync(path.join(repo, p))) throw new Error(`state/web-ready.txt lists ${f}, but it is not in state/`);
      return p;
    });
  return [...coreCompiled(), ...main, ...tests, ...state, 'app/src/main/java/btools/router/OnPathHints.java',
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
    // a named file that is shared now (a new shared file) is recorded too, without touching the others
    for (const f of only) if (m.shared?.[f] || shared.includes(f)) (m.shared ??= {})[f] = { blob: blob(f), perf: perfLines(f) };
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

// ---- refs: the app code web/app/src names must be a mirror or a ref (see the head) ----
/** Every Kotlin type / object / top-level or @Composable function name (and file name) below app, core and state → its files. */
function kotlinNames() {
  const names = new Map();
  const add = (n, f) => { if (!names.has(n)) names.set(n, new Set()); names.get(n).add(f); };
  for (const root of ['app/src/main/java', 'core/src/main/kotlin', 'state/src/main/kotlin']) {
    for (const f of walk(root)) {
      add(path.basename(f, '.kt'), f);
      const text = fs.readFileSync(path.join(repo, f), 'utf8');
      for (const x of text.matchAll(/^[ \t]*(?:(?:private|internal|public|data|enum|sealed|abstract|open|value|inline|annotation)\s+)*(?:object|class|interface)\s+([A-Z]\w*)/gm)) add(x[1], f);
      for (const x of text.matchAll(/^(?:@Composable\s+)?(?:(?:private|internal)\s+)?fun\s+([A-Z]\w*)\s*\(/gm)) add(x[1], f);
    }
  }
  return names;
}
/** Every .ts below web/app/src (repo-relative, sorted). */
function tsFiles(rel) {
  const out = [];
  for (const e of fs.readdirSync(path.join(repo, rel), { withFileTypes: true })) {
    const r = `${rel}/${e.name}`;
    if (e.isDirectory()) { if (e.name !== 'gen') out.push(...tsFiles(r)); } else if (/\.ts$/.test(e.name) && !e.name.endsWith('.d.ts')) out.push(r);
  }
  return out.sort();
}
/** "the app's X", "as the app's ui/X.kt", "like the app's map/X" … → the named app code per TS file: { ts: Map(name → files) }. */
function appMentions(names = kotlinNames()) {
  const out = new Map();
  for (const ts of tsFiles('web/app/src')) {
    const text = fs.readFileSync(path.join(repo, ts), 'utf8');
    for (const x of text.matchAll(/\bapp's\s+(?:[a-z]+\/)*([A-Z]\w*)/g)) {
      const files = names.get(x[1]);
      if (!files) continue; // not app code (Settings, TalkBack, a dialog's title …)
      if (!out.has(ts)) out.set(ts, new Map());
      out.get(ts).set(x[1], [...files].sort());
    }
  }
  return out;
}
const refs = m.refs ?? {};
const mirrored = new Set(m.mirrors.flatMap((e) => e.web.map((w) => `${w} ${e.app}`)));
const mentions = appMentions();
for (const [ts, named] of mentions) {
  for (const [name, files] of named) {
    if (files.some((f) => mirrored.has(`${ts} ${f}`) || refs[ts]?.[f])) continue;
    errors.push(`${ts} names the app's ${name} (${files.join(' / ')}), but web/parity.json has no mirror and no ref for it – ` +
      `a copy of the app's logic: add a mirror (then CI tracks the app file); the page asks the engine or only names it: add ` +
      `"refs": { "${ts}": { "${files[0]}": "engine: …" | "look: …" } }`);
  }
}
for (const [ts, row] of Object.entries(refs)) {
  for (const f of Object.keys(row)) {
    const still = [...(mentions.get(ts)?.values() ?? [])].some((files) => files.includes(f));
    if (!still) notes.push(`ref no longer named in the page: ${ts} → ${f} (remove it from web/parity.json refs)`);
  }
}

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
console.log(errors.length ? `parity: ${errors.length} mirror(s) or ref(s) need a look (see above)` : `parity: ok (${warnings.length} warning(s), ${notes.length} note(s))`);
process.exit(errors.length ? 1 : 0);
