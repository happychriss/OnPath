// SameRouteTest, the Wasm part (docs/design/detour-stitch.md §5, §8): every plan code of
// core/src/test/resources/same-route/*.sha, routed cold by the planner's engine, must give the JVM's RouteHash exactly
// (sha256 of the line, tools/routing/jvm-test/SameRouteTest.kt) and its distance within 0.5 %. The whole 5° tile is
// loaded, so the test compares the router and the plan code, not the download of the parts.
// The hashes hold only for the routing data named in same-route/DATA.txt: any other E10_N45.rd5 is refused (exit 2).
// Build: tools/dev/gradle_slot.sh -- ./gradlew -q -p web/engine generateWasmGC copyWasmGCRuntime (no npm install needed)
// Usage: node --max-old-space-size=4096 test/same-route-wasm.mjs <segmentsDir>   (Node 22 or newer; the V8 flags it needs are set below)
// Env: TOURS=a,b  LIMIT=<cases per tour>  NEIGHBOURS=1 (also load the tiles around E10_N45 found in the folder)
//      DUMP=<dir> (write each case's line text there)
//      SHA_DIR=<dir> (the .sha files and their DATA.txt there instead of core/src/test/resources/same-route – the weekly CI
//      run makes fresh ones with the JVM on the current brouter.de tile, .github/workflows/same-route.yml)
// Exit: 0 all equal, 1 a case differs, 2 refused (no folder, or not the recorded data).
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import v8 from 'node:v8';
import { fileURLToPath } from 'node:url';

// Node 22's Wasm tier-up holds gigabytes outside the JS heap on this module, even with nothing routed (measured on the
// longest tour: over 9 GB with it, 2.4 GB without, the same time and the same hashes). It changes how the code is compiled,
// not what it computes. Node 22 also needs exnref switched on for TeaVM's module.
v8.setFlagsFromString('--no-wasm-dynamic-tiering');
if (Number(process.versions.node.split('.')[0]) < 24) v8.setFlagsFromString('--experimental-wasm-exnref');
const here = path.dirname(fileURLToPath(import.meta.url));
if (!process.argv[2]) { console.log('REFUSED: give the segments folder as the argument'); process.exit(2); }
const segments = path.resolve(process.argv[2]);
const shaDir = process.env.SHA_DIR ? path.resolve(process.env.SHA_DIR) : path.join(here, '../../../core/src/test/resources/same-route');
const tile = 'E10_N45.rd5';
const wanted = /^sha256:\s*([0-9a-f]{64})\s*$/m.exec(fs.readFileSync(path.join(shaDir, 'DATA.txt'), 'utf8'))?.[1];
if (!wanted) { console.log('REFUSED: same-route/DATA.txt names no sha256'); process.exit(2); }
if (!fs.existsSync(path.join(segments, tile))) { console.log(`REFUSED: no ${tile} in ${segments}`); process.exit(2); }
const digest = crypto.createHash('sha256');
for await (const c of fs.createReadStream(path.join(segments, tile))) digest.update(c);
const have = digest.digest('hex');
if (have !== wanted) { console.log(`REFUSED: ${tile} is not the data the hashes were made with (sha256 ${have}, DATA.txt ${wanted})`); process.exit(2); }
console.log(`data ok: ${tile} sha256 ${have}`);
const gen = path.join(here, '../../engine/build/generated/teavm/wasm-gc');
await import(path.join(gen, 'onpath-web-engine.wasm-runtime.js'));
const { exports: e } = await TeaVM.wasmGC.load(path.join(gen, 'onpath-web-engine.wasm'));
e.init('/onpath');
const assets = path.join(here, '../../../app/src/main/assets/brouter');
for (const f of fs.readdirSync(assets)) e.putFile('assets/brouter/' + f, new Int8Array(fs.readFileSync(path.join(assets, f))));

const view = (b) => new Int8Array(b.buffer, b.byteOffset, b.length);
/** A whole tile through the page's calls (head, tail, every part). */
function loadTile(name) {
  const fd = fs.openSync(path.join(segments, name), 'r');
  const size = fs.fstatSync(fd).size;
  const read = (from, to) => { const b = Buffer.alloc(to - from + 1); fs.readSync(fd, b, 0, b.length, from); return b; };
  const head = read(0, 199);
  const v = new DataView(head.buffer, head.byteOffset, 200);
  const ends = [...Array(25).keys()].map((i) => Number(v.getBigUint64(i * 8) & 0xffffffffffffn));
  e.putLayout(name, view(head), view(read(ends[24], size - 1)));
  for (let i = 0; i < 25; i++) {
    const start = i === 0 ? 200 : ends[i - 1];
    if (ends[i] > start && !e.putPart(name, i, view(read(start, ends[i] - 1)))) throw new Error(`${name} part ${i} does not fit`);
  }
  fs.closeSync(fd);
}
loadTile(tile);
const around = ['E5_N40', 'E10_N40', 'E15_N40', 'E5_N45', 'E15_N45', 'E5_N50', 'E10_N50', 'E15_N50'].map((t) => t + '.rd5').filter((t) => fs.existsSync(path.join(segments, t)));
if (process.env.NEIGHBOURS) for (const t of around) loadTile(t);
console.log(`tiles loaded: ${[tile, ...(process.env.NEIGHBOURS ? around : [])].join(' ')}${!process.env.NEIGHBOURS && around.length ? ` (in the folder, not loaded: ${around.join(' ')})` : ''}`);
e.writeParts();

const metres = (s) => new Float64Array(new BigInt64Array([BigInt.asIntN(64, [...s.replace('-', '')].reduce((n, c) => n * 36n + BigInt(parseInt(c, 36)), 0n) * (s[0] === '-' ? -1n : 1n))]).buffer)[0];
const only = process.env.TOURS ? new Set(process.env.TOURS.split(',')) : null;
const limit = Number(process.env.LIMIT ?? Infinity);
let n = 0, red = 0;
const t0 = Date.now();
for (const f of fs.readdirSync(shaDir).filter((x) => x.endsWith('.sha')).sort()) {
  const tour = f.slice(0, -4);
  if (only && !only.has(tour)) continue;
  for (const line of fs.readFileSync(path.join(shaDir, f), 'utf8').split('\n').filter(Boolean).slice(0, limit)) {
    const [name, code, hash, dist] = line.split('\t');
    const out = e.sameRouteCase(code);
    const nl = out.indexOf('\n');
    const [state, bits] = out.slice(0, nl).split('\t');
    const h = crypto.createHash('sha256').update(out.slice(nl + 1), 'utf8').digest('hex');
    const m = metres(bits), d = Number(dist);
    if (process.env.DUMP) fs.writeFileSync(`${process.env.DUMP}/wasm-${tour}-${name}.txt`, out.slice(nl + 1));
    const good = state === 'clean' && h === hash && d > 0 && Math.abs(m - d) / d <= 0.005;
    n++; if (!good) red++;
    console.log(`${good ? 'GREEN' : 'RED'} ${tour} ${name}: ${state}, hash ${h === hash ? 'equal' : 'DIFFERENT'}, ${Math.round(m)} m (file ${d} m) [${Math.round((Date.now() - t0) / 1000)} s]`);
  }
}
console.log(`same-route wasm: ${n} cases, ${red} red, peak memory ${(process.resourceUsage().maxRSS / 1e6).toFixed(2)} GB`);
console.log(n > 0 && red === 0 ? 'all ok' : 'FAILED');
process.exit(n > 0 && red === 0 ? 0 : 1);
