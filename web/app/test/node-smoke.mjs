// Quick check outside the browser: load the Wasm engine in Node, hand it profiles + rd5, compute one route.
// Usage: node node-smoke.mjs <segmentsDir>
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const gen = path.join(here, '../../engine/build/generated/teavm/wasm-gc');
await import(path.join(gen, 'onpath-web-engine.wasm-runtime.js'));
let t = Date.now();
const { exports: e } = await TeaVM.wasmGC.load(path.join(gen, 'onpath-web-engine.wasm'));
console.log('load ms', Date.now() - t);
e.init('/onpath');
const assets = path.join(here, '../../../app/src/main/assets/brouter');
for (const f of fs.readdirSync(assets)) e.putFile('assets/brouter/' + f, new Int8Array(fs.readFileSync(path.join(assets, f))));
t = Date.now();
e.putFile('segments/E10_N45.rd5', new Int8Array(fs.readFileSync(path.join(process.argv[2], 'E10_N45.rd5'))));
console.log('rd5 put ms', Date.now() - t);
for (let i = 0; i < 2; i++) {
  t = Date.now();
  const r = JSON.parse(e.route('46.4970,11.3580;46.6717,11.1500', 'BIKE_TREKKING', 3));
  console.log('route ms', Date.now() - t, r.error ?? `${r.distanceM} m, +${r.ascentM}, ${r.points.length} pts, time ${r.timeS}s`);
}
console.log(e.problems());
