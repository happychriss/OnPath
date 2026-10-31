// Plan parity, app ⇄ web (owner 2026-10-31: routes and plans always identical – shared Kotlin, parity tests, no checks
// by hand). Every case of web/test/plans.json runs twice:
//  - app: ./gradlew -p web/engine parityJvm → build/parity-app.json – the app's own path on the JVM (BRouterEngine on
//    the full rd5, PlanPoints for the edits, PlanResult.plan with the app's default pace = AppViewModel.recalc);
//  - web: here, the Wasm engine in Node through the page's calls (WebApi edit + plan), the rd5 in 1° parts like the
//    browser (partsNeeded → head, tail, parts).
// Compared: the line and the plan points (≤ 1 m), distance (±1 m), time (±1 s), ascent / descent, the legs that are
// straight, the gaps and their red line, the route warnings (identical), the difficulty and the profile. A case with
// "knownDiff" (a reason) is shown but does not fail the run; every other difference does.
// Usage: node test/plan-parity.mjs <segmentsDir> [appResults]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
// Node does not exit after loading the TeaVM Wasm GC module (and keeps allocating – seen on donald): the checks run in
// a child process that this one ends when it printed its last line, as lang-engine.mjs / sync-engine.mjs do.
if (process.argv[4] !== '--child') {
  const child = spawn(process.execPath, ['--max-old-space-size=3072', fileURLToPath(import.meta.url), process.argv[2] ?? '',
    process.argv[3] ?? '', '--child'], { stdio: ['ignore', 'pipe', 'inherit'], env: process.env });
  let out = '';
  const timer = setTimeout(() => { console.log('FAIL timeout'); child.kill('SIGKILL'); process.exit(1); }, 300_000);
  child.stdout.on('data', (d) => {
    process.stdout.write(d); out += d;
    const end = /^(all ok|FAILED)$/m.exec(out);
    if (end) { clearTimeout(timer); child.kill('SIGKILL'); process.exit(end[1] === 'all ok' ? 0 : 1); }
  });
  child.on('exit', () => { if (!/^(all ok|FAILED)$/m.test(out)) { console.log('FAIL child ended early'); process.exit(1); } });
} else {

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] || '/home/user/build/routing-test/segments');
const appFile = path.resolve(process.argv[3] || path.join(here, '../../engine/build/parity-app.json'));
const cases = JSON.parse(fs.readFileSync(path.join(here, '../../test/plans.json'), 'utf8'));
const app = JSON.parse(fs.readFileSync(appFile, 'utf8'));
if (app.length !== cases.length) { console.log(`FAIL ${appFile}: ${app.length} results for ${cases.length} cases – run parityJvm again`); console.log('FAILED'); process.exitCode = 1; }

const gen = path.join(here, '../../engine/build/generated/teavm/wasm-gc');
await import(path.join(gen, 'onpath-web-engine.wasm-runtime.js'));
const { exports: e } = await TeaVM.wasmGC.load(path.join(gen, 'onpath-web-engine.wasm'));
e.init('/onpath');
const assets = path.join(here, '../../../app/src/main/assets/brouter');
for (const f of fs.readdirSync(assets)) e.putFile('assets/brouter/' + f, new Int8Array(fs.readFileSync(path.join(assets, f))));

// the browser's rd5 path (engine/worker.ts layout / ensureParts), the bytes read from the file instead of by HTTP range
const fds = new Map();
const bytes = (tile, from, to) => {
  const f = path.join(segments, tile);
  if (!fds.has(tile)) fds.set(tile, fs.existsSync(f) ? fs.openSync(f, 'r') : null);
  const fd = fds.get(tile);
  if (fd == null) return null;
  const size = fs.fstatSync(fd).size, end = Math.min(to, size - 1);
  const b = Buffer.alloc(Math.max(0, end - from + 1));
  fs.readSync(fd, b, 0, b.length, from);
  return { b, size };
};
const layouts = new Map();
const layout = (tile) => {
  if (!layouts.has(tile)) {
    const head = bytes(tile, 0, 199);
    if (!head) { layouts.set(tile, null); return null; }
    const v = new DataView(head.b.buffer, head.b.byteOffset, 200);
    const ends = [...Array(25).keys()].map((i) => Number(v.getBigUint64(i * 8) & 0xffffffffffffn));
    const tail = bytes(tile, ends[24], head.size - 1);
    e.putLayout(tile, new Int8Array(head.b.buffer, head.b.byteOffset, 200), new Int8Array(tail.b.buffer, tail.b.byteOffset, tail.b.length));
    layouts.set(tile, ends);
  }
  return layouts.get(tile);
};
const ensureParts = (pts) => {
  for (const [tile, i] of JSON.parse(e.partsNeeded(pts))) {
    const ends = layout(tile);
    if (!ends) continue;
    const start = i === 0 ? 200 : ends[i - 1];
    if (ends[i] <= start) continue;
    const p = bytes(tile, start, ends[i] - 1);
    if (!e.putPart(tile, i, new Int8Array(p.b.buffer, p.b.byteOffset, p.b.length))) throw new Error(`${tile} part ${i} does not fit`);
  }
  e.writeParts();
};

const metres = (a, b) => {
  const k = Math.PI / 180, x = (b[1] - a[1]) * k * Math.cos(((a[0] + b[0]) / 2) * k), y = (b[0] - a[0]) * k;
  return Math.sqrt(x * x + y * y) * 6371000;
};
const maxDev = (p, q) => (p.length === q.length ? Math.max(0, ...p.map((x, i) => metres(x, q[i]))) : Infinity);

/** The web's plan JSON in the comparison's terms (ParityRun.kt writes the app's side the same way). */
function webSummary(j) {
  const line = j.legs.flatMap((l) => l.points.map(([la, lo]) => [la, lo]));
  return {
    error: j.error ?? null, distanceM: j.distanceM, ascentM: j.ascentM, descentM: j.descentM, timeS: j.timeS,
    points: j.points ?? [], moved: !!j.moved, line, straight: j.legs.map((l) => l.straight),
    gaps: (j.gaps?.gaps ?? []).map((g) => g.index), gapText: j.gaps?.text ?? null,
    warning: j.warning ?? null, spots: (j.spots ?? []).map((s) => s.title), difficulty: j.difficulty, because: j.because ?? null,
    profile: j.profile ? { n: j.profile.dist.length, min: Math.min(...j.profile.ele), max: Math.max(...j.profile.ele),
      total: j.profile.dist.at(-1), steep: j.profile.steep } : null,
  };
}

let gate = 0;
const rows = [];
const only = process.env.ONLY ? new RegExp(process.env.ONLY) : null; // a subset by name (memory checks)
cases.forEach((c, k) => {
  if (only && !only.test(c.name)) return;
  let pts = c.points;
  for (const [op, i, to] of c.edits ?? []) pts = e.edit(op, pts, i, to ?? 0);
  const names = Object.entries(c.names ?? {}).map(([i, n]) => `${i}\t${n}`).join('\n');
  ensureParts(pts);
  const t0 = Date.now();
  const w = webSummary(JSON.parse(e.plan(pts, c.activity, c.sac, (c.pinned ?? []).join(','), names)));
  const ms = Date.now() - t0;
  const a = app[k];
  const diffs = [];
  const same = (what, x, y) => { if (JSON.stringify(x) !== JSON.stringify(y)) diffs.push(`${what}: app ${JSON.stringify(x)} web ${JSON.stringify(y)}`); };
  same('error', a.error, w.error);
  if (Math.abs(a.distanceM - w.distanceM) > 1) diffs.push(`distance: app ${a.distanceM} web ${w.distanceM}`);
  if (Math.abs(a.timeS - w.timeS) > 1) diffs.push(`time: app ${a.timeS} s web ${w.timeS} s`);
  if (Math.abs(a.ascentM - w.ascentM) > 1 || Math.abs(a.descentM - w.descentM) > 1) diffs.push(`climb: app +${a.ascentM}/-${a.descentM} web +${w.ascentM}/-${w.descentM}`);
  const lineDev = maxDev(a.line, w.line), stopDev = maxDev(a.points, w.points);
  if (lineDev > 1) diffs.push(`line: ${a.line.length} vs ${w.line.length} points, max ${lineDev.toFixed(1)} m apart`);
  if (stopDev > 1) diffs.push(`plan points: max ${stopDev.toFixed(1)} m apart (app ${JSON.stringify(a.points)} web ${JSON.stringify(w.points)})`);
  same('moved', a.moved, w.moved); same('straight legs', a.straight, w.straight);
  same('gaps', a.gaps, w.gaps); same('gap line', a.gapText, w.gapText);
  same('warning', a.warning, w.warning); same('spots', a.spots, w.spots);
  same('difficulty', a.difficulty, w.difficulty); same('because', a.because, w.because);
  if (!a.profile !== !w.profile) diffs.push(`profile: app ${a.profile ? 'yes' : 'none'} web ${w.profile ? 'yes' : 'none'}`);
  else if (a.profile && (a.profile.n !== w.profile.n || Math.abs(a.profile.min - w.profile.min) > 0.1 || Math.abs(a.profile.max - w.profile.max) > 0.1 ||
    Math.abs(a.profile.total - w.profile.total) > 1 || JSON.stringify(a.profile.steep) !== JSON.stringify(w.profile.steep))) diffs.push('profile differs');
  const known = !!c.knownDiff;
  const verdict = diffs.length === 0 ? (known ? 'same (known-diff listed – drop it?)' : 'same') : known ? 'KNOWN-DIFF' : 'DIFF';
  if (diffs.length && !known) gate++;
  rows.push({ case: c.name, verdict, km: a.error ? '–' : `${(a.distanceM / 1000).toFixed(3)} / ${(w.distanceM / 1000).toFixed(3)}`,
    time: a.error ? '–' : `${a.timeS} / ${w.timeS}`, moved: `${a.moved}/${w.moved}`, gaps: `${a.gaps}|${w.gaps}`, webMs: ms });
  for (const d of diffs) console.log(`   ${c.name}: ${d}`);
  if (known && diffs.length) console.log(`   known-diff, why: ${c.knownDiff}`);
});
console.table(rows);
console.log(gate ? `plan parity: ${gate} case(s) differ (gate)` : `plan parity: every gated case the same (${cases.length} cases, ${cases.filter((c) => c.knownDiff).length} known-diff)`);
console.log(gate ? 'FAILED' : 'all ok');
}
