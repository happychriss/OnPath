// GPX import and the long-route rule in Wasm = the JVM (docs/design/web-planner.md §15): every fixture in
// test/fixtures gives byte-for-byte the JSON WebImportTest wrote (build/jvm-gpx.json, `./gradlew -p web/engine test`
// first) – so the web's small SAX stand-in reads the files like the JDK's parser – and two points ~600 km apart get
// the app's question instead of a routing run. No browser, no map data. Usage: node test/gpx-engine.mjs
// (Node does not exit after loading the TeaVM Wasm GC module: the checks run in a child process, see sync-engine.mjs.)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
if (process.argv[2] !== '--child') {
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), '--child'], { stdio: ['ignore', 'pipe', 'inherit'] });
  let out = '';
  const timer = setTimeout(() => { console.log('FAIL timeout'); child.kill('SIGKILL'); process.exit(1); }, 60_000);
  child.stdout.on('data', (d) => {
    process.stdout.write(d); out += d;
    const end = /^(all ok|FAILED)$/m.exec(out);
    if (end) { clearTimeout(timer); child.kill('SIGKILL'); process.exit(end[1] === 'all ok' ? 0 : 1); }
  });
  child.on('exit', () => { if (!/^(all ok|FAILED)$/m.test(out)) { console.log('FAIL child ended early'); process.exit(1); } });
} else {
const here = path.dirname(fileURLToPath(import.meta.url));
const gen = path.join(here, '../../engine/build/generated/teavm/wasm-gc');
await import(path.join(gen, 'onpath-web-engine.wasm-runtime.js'));
const { exports: e } = await TeaVM.wasmGC.load(path.join(gen, 'onpath-web-engine.wasm'));
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };

const jvm = JSON.parse(fs.readFileSync(path.join(here, '../../engine/build/jvm-gpx.json'), 'utf8'));
for (const name of Object.keys(jvm).sort()) {
  const bytes = fs.readFileSync(path.join(here, 'fixtures', name));
  const wasm = e.gpxRead(new Int8Array(bytes.buffer, bytes.byteOffset, bytes.length), name, 'BIKE_TOURING', 4);
  const same = wasm === JSON.stringify(jvm[name]).replace(/\\u([0-9a-f]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16))) || JSON.stringify(JSON.parse(wasm)) === JSON.stringify(jvm[name]);
  check(same, `${name}: Wasm import = JVM (${JSON.parse(wasm).activity ?? JSON.parse(wasm).error})`);
  if (!same) console.log(' wasm:', wasm.slice(0, 400), '\n jvm: ', JSON.stringify(jvm[name]).slice(0, 400));
}
const nothing = JSON.parse(e.gpxRead(new Int8Array(new TextEncoder().encode('<gpx></gpx>')), 'x.gpx', 'HIKE', 2));
check(nothing.error === 'Could not import this file: No track, route or waypoint found in this file.', 'empty GPX: the app\'s plain error');

const far = JSON.parse(e.longRoute('46.67,11.16;52.05,11.16', 'HIKE', false));
check(far.ask && far.title === 'This route is very long' && /days on foot\. Calculating and loading its maps takes long\.$/.test(far.text), `600 km on foot is asked first: "${far.text}"`);
check(JSON.parse(e.longRoute('46.67,11.16;46.69,11.15', 'HIKE', false)).ask === false, 'a day hike is not asked');
check(JSON.parse(e.longRoute('46.67,11.16;52.05,11.16', 'HIKE', true)).ask === false, 'a multi-day tour is never asked');
e.historyRecord('46.67,11.16;46.69,11.15', '46.67,11.16;46.68,11.13;46.69,11.15');
const undone = JSON.parse(e.planCancel('edit', '46.67,11.16;46.68,11.13;46.69,11.15', '', 0, 'STOPPED'));
check(undone.what === 'UNDO' && undone.points === '46.670000,11.160000;46.690000,11.150000' &&
  JSON.parse(e.planCancel('activity', '46.67,11.16;46.69,11.15', 'HIKE', 3, 'STOPPED')).what === 'RESTORE_ACTIVITY',
  'Cancel takes back the change (the app\'s PlanEditState.cancelRun in the Wasm)');

// the run's head (the app's PlanRecalcState.begin + RouteRun in the Wasm): asked, „Plan anyway“ kept – also across a fresh worker
const longPlan = '46.67,11.16;52.05,11.16', dayHike = '46.67,11.16;46.69,11.15';
const ask = JSON.parse(e.runHead(longPlan, 'HIKE', 2, true));
check(ask.what === 'ASK' && ask.title === 'This route is very long' && ask.seconds > 86400, `the run head asks a very long plan first (${ask.what})`);
check(JSON.parse(e.runHead(longPlan, 'HIKE', 2, false)).what === 'ROUTE', 'a multi-day tour is routed without the question');
const day = JSON.parse(e.runHead(dayHike, 'HIKE', 2, true));
check(day.what === 'ROUTE' && day.rounds === 1 && day.gen > 0, `a day hike is routed at once (${JSON.stringify(day)})`);
e.runHead(longPlan, 'HIKE', 2, true);
e.runLongAnswer(true, longPlan);
check(JSON.parse(e.runHead(longPlan, 'HIKE', 2, true)).what === 'ROUTE', '„Plan anyway“: the same points are not asked again');
e.historyImport(e.historyExport()); // a fresh worker gets the history and the run's memory (client.ts restart)
check(JSON.parse(e.runHead(longPlan, 'HIKE', 2, true)).what === 'ROUTE', '„Plan anyway“ survives a fresh worker');
// „Weiter warten?“: the time limit, then wait on → the next run has one period more
const slow = JSON.parse(e.runHead(dayHike, 'HIKE', 2, true));
e.runEnd(slow.gen, 'slow', 1);
e.historyImport(e.historyExport()); // the open question survives a fresh worker too
e.runSlowAnswer(true);
const again = JSON.parse(e.runHead(dayHike, 'HIKE', 2, true));
check(again.what === 'ROUTE' && again.rounds === 2, `„Weiter warten“: the run again with 2 periods (${JSON.stringify(again)})`);
e.runEnd(again.gen, 'ok', 2);
check(JSON.parse(e.runHead(dayHike, 'HIKE', 2, true)).rounds === 1, 'the next run starts at one period again');
// Cancel of the question with nothing to take back (a plan opened): left as too long – „Plan anyway“ then plans it unasked
e.historyImport('#');
const other = '46.67,11.16;50.05,11.16';
check(JSON.parse(e.runHead(other, 'HIKE', 2, true)).what === 'ASK', 'a fresh plan is asked');
e.runLongAnswer(false, other);
check(JSON.parse(e.planCancel('other', other, '', 0, 'LONG')).what === 'KEEP_POINTS', 'Cancel of the question: the points stay');
e.runPlanAnyway(other);
check(JSON.parse(e.runHead(other, 'HIKE', 2, true)).what === 'ROUTE', '„Plan anyway“ of the not-routed line: routed without asking');

// the track panel's „Als Plan übernehmen“ = the app's TrackToPlanState (WebTrackToPlan): an exported plan GPX comes back with
// its own points, names and pins (no routing); the plan it replaces goes under Zuletzt only when it has a route's worth of points
const planLine = [[[46.67, 11.16], [46.675, 11.15], [46.68, 11.14], [46.685, 11.145], [46.69, 11.15]]];
const planWpts = [{ lat: 46.67, lon: 11.16, name: 'Start', type: null }, { lat: 46.68, lon: 11.14, name: null, type: 'pinned' },
  { lat: 46.69, lon: 11.15, name: 'Ziel', type: null }];
const back = JSON.parse(e.trackToPlan('46.60,11.10;46.61,11.11', JSON.stringify(planLine), JSON.stringify(planWpts), 'HIKE', 2));
check(back.points?.length === 3 && back.names?.[0] === 'Start' && back.names?.[2] === 'Ziel' && back.pinned?.[1] === true && back.pinned?.[0] === false &&
  back.keepDraft === true, `a plan GPX: its 3 points, names and the pin back; the replaced plan under Zuletzt (${JSON.stringify(back)})`);
check(JSON.parse(e.trackToPlan('', JSON.stringify(planLine), JSON.stringify(planWpts), 'HIKE', 2)).keepDraft === false,
  'an empty plan replaced by a track: nothing under Zuletzt');
const none = JSON.parse(e.trackToPlan('', JSON.stringify([[[46.67, 11.16]]]), '[]', 'HIKE', 2));
check(none.error !== undefined && none.points === undefined, `a one-point track: no plan (${JSON.stringify(none)})`);

// the kept places' line in the Wasm = the app's SearchLines (String.format(Locale.ROOT, "%.6f") under TeaVM)
const kept = e.recentAdd('', 'n9\tHütte\thut\t\t46.123456789\t-0.0001\t2100\t\tPOI\t\t\t');
check(kept === 'n9\tHütte\thut\t\t46.123457\t-0.000100\t2100\t\tPOI\t\t\t', `a kept place's line: six decimals, no locale (${JSON.stringify(kept)})`);
const twice = e.recentAdd(kept, 'n9\tHütte\thut\t\t46.123457\t-0.0001\t2100\t\tPOI\t\t\t');
check(twice.split('\n').length === 1, 'the same place twice in Zuletzt gesucht: once');

console.log(fails.length ? 'FAILED' : 'all ok');
}
