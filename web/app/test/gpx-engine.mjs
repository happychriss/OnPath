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

const far = JSON.parse(e.longRoute('46.67,11.16;52.05,11.16', 'HIKE'));
check(far.ask && far.title === 'This route is very long' && /days on foot\. Calculating and loading its maps takes long\.$/.test(far.text), `600 km on foot is asked first: "${far.text}"`);
check(JSON.parse(e.longRoute('46.67,11.16;46.69,11.15', 'HIKE')).ask === false, 'a day hike is not asked');
check(e.planCancel('edit', true, false) === 'UNDO' && e.planCancel('activity', false, false) === 'RESTORE_ACTIVITY', 'Cancel takes back the change (PlanStatus.onCancel)');

console.log(fails.length ? 'FAILED' : 'all ok');
}
