// SameRouteTest, the Wasm part (docs/design/detour-stitch.md §5, §8): every plan code of
// core/src/test/resources/same-route/*.sha, routed cold by the planner's engine, must give the JVM's RouteHash exactly
// (sha256 of the line, tools/routing/jvm-test/SameRouteTest.kt) and its distance within 0.5 %. The whole 1° tile is
// loaded, so the test compares the router and the plan code, not the download of the parts.
// Usage: node --max-old-space-size=4096 test/same-route-wasm.mjs <segmentsDir>   Env: TOURS=a,b  LIMIT=<cases per tour>
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] || '/home/user/build/routing-test/segments');
const shaDir = path.join(here, '../../../core/src/test/resources/same-route');
const gen = path.join(here, '../../engine/build/generated/teavm/wasm-gc');
await import(path.join(gen, 'onpath-web-engine.wasm-runtime.js'));
const { exports: e } = await TeaVM.wasmGC.load(path.join(gen, 'onpath-web-engine.wasm'));
e.init('/onpath');
const assets = path.join(here, '../../../app/src/main/assets/brouter');
for (const f of fs.readdirSync(assets)) e.putFile('assets/brouter/' + f, new Int8Array(fs.readFileSync(path.join(assets, f))));

const tile = 'E10_N45.rd5';
const fd = fs.openSync(path.join(segments, tile), 'r');
const size = fs.fstatSync(fd).size;
const read = (from, to) => { const b = Buffer.alloc(to - from + 1); fs.readSync(fd, b, 0, b.length, from); return b; };
const view = (b) => new Int8Array(b.buffer, b.byteOffset, b.length);
const head = read(0, 199);
const v = new DataView(head.buffer, head.byteOffset, 200);
const ends = [...Array(25).keys()].map((i) => Number(v.getBigUint64(i * 8) & 0xffffffffffffn));
e.putLayout(tile, view(head), view(read(ends[24], size - 1)));
for (let i = 0; i < 25; i++) {
  const start = i === 0 ? 200 : ends[i - 1];
  if (ends[i] > start && !e.putPart(tile, i, view(read(start, ends[i] - 1)))) throw new Error(`part ${i} does not fit`);
}
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
console.log(`same-route wasm: ${n} cases, ${red} red`);
console.log(n > 0 && red === 0 ? 'all ok' : 'FAILED');
process.exit(n > 0 && red === 0 ? 0 : 1);
