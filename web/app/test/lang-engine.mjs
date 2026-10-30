// The engine's texts follow the page's language (docs/design/web-planner.md §18): one Wasm, both tables of the app's
// strings (values/ + values-de/), numbers as in the app (data/Format: "12,5 km" in German).
// Usage: node test/lang-engine.mjs <segmentsDir>
// (Node does not exit after loading the TeaVM Wasm GC module: the checks run in a child process, see sync-engine.mjs.)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
if (process.argv[3] !== '--child') {
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), process.argv[2] ?? '', '--child'], { stdio: ['ignore', 'pipe', 'inherit'] });
  let out = '';
  const timer = setTimeout(() => { console.log('FAIL timeout'); child.kill('SIGKILL'); process.exit(1); }, 90_000);
  child.stdout.on('data', (d) => {
    process.stdout.write(d); out += d;
    const end = /^(all ok|FAILED)$/m.exec(out);
    if (end) { clearTimeout(timer); child.kill('SIGKILL'); process.exit(end[1] === 'all ok' ? 0 : 1); }
  });
  child.on('exit', () => { if (!/^(all ok|FAILED)$/m.test(out)) { console.log('FAIL child ended early'); process.exit(1); } });
} else {
  let bad = 0;
  const check = (ok, what) => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`); if (!ok) bad++; };
  try {
    const here = path.dirname(fileURLToPath(import.meta.url));
    const gen = path.join(here, '../../engine/build/generated/teavm/wasm-gc');
    await import(path.join(gen, 'onpath-web-engine.wasm-runtime.js'));
    const { exports: e } = await TeaVM.wasmGC.load(path.join(gen, 'onpath-web-engine.wasm'));
    e.init('/onpath');
    const assets = path.join(here, '../../../app/src/main/assets/brouter');
    for (const f of fs.readdirSync(assets)) e.putFile('assets/brouter/' + f, new Int8Array(fs.readFileSync(path.join(assets, f))));
    e.putFile('segments/E10_N45.rd5', new Int8Array(fs.readFileSync(path.join(process.argv[2], 'E10_N45.rd5'))));
    const route = '46.6717,11.1500;46.6870,11.1760'; // Algund → Vellau
    const plan = () => JSON.parse(e.plan(route, 'HIKE', 2, ''));

    check(e.setLanguage('de') === 'de', 'German set');
    const de = plan();
    check(JSON.parse(e.activities()).find((a) => a.key === 'HIKE').label === 'Wandern', 'activity names in German (Wandern)');
    check(/^\d+,\d km$|^\d+ m$/.test(de.texts.distance), `German decimal comma: ${de.texts.distance}`);
    check(/max\. /.test(de.texts.climb), `climb line in German: ${de.texts.climb}`);
    check(['Leicht', 'Mittel', 'Schwer'].some((w) => de.difficulty.startsWith(w)), `difficulty in German: ${de.difficulty}`);
    check(JSON.parse(e.mapTexts()).plan_calculating !== 'Calculating the route…', 'map texts in German');
    check(e.setLanguage('xx') === 'en', 'an unknown language → English');
    const en = plan();
    check(JSON.parse(e.activities()).find((a) => a.key === 'HIKE').label === 'Hike', 'English again (Hike)');
    check(/^\d+\.\d km$|^\d+ m$/.test(en.texts.distance), `English decimal point: ${en.texts.distance}`);
    check(JSON.parse(e.mapTexts()).plan_calculating === 'Calculating the route…', 'map texts in English');
    check(en.distanceM === de.distanceM, 'the same route in both languages, only the words differ');
  } catch (err) { console.log('FAIL ' + (err?.stack ?? err)); bad++; }
  console.log(bad ? 'FAILED' : 'all ok');
}
