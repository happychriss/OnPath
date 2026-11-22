// A damaged or made-up pairing link must get the page's plain "not a code", never an error that leaves the dialog without a word.
// No browser. The child runs with a small stack and without Wasm's fast tier, where the Regex that found the code in a link ran
// out of stack (RangeError), so the failure does not depend on timing. Usage: node test/pairlink-engine.mjs
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
if (process.argv[2] !== '--child') {
  const child = spawn(process.execPath, ['--liftoff-only', '--stack-size=492', fileURLToPath(import.meta.url), '--child'], { stdio: ['ignore', 'pipe', 'inherit'] });
  let out = '';
  const timer = setTimeout(() => { console.log('FAIL timeout'); child.kill('SIGKILL'); process.exit(1); }, 90_000);
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
  const read = (t) => { try { return e.syncPairParse(t); } catch (x) { return 'THROW ' + String(x).slice(0, 60); } };
  const PAIR = 'AQEIDxYdJCsyOUBHTlVcY2pxeH-GjZSboqmwt77FzNPa';
  check(read('https://plan.onpath.earth/#pair=' + PAIR) === PAIR, 'a real pairing link is read');
  for (const n of [300, 700, 1500, 3900]) {
    const r = read('https://plan.onpath.earth/#pair=' + 'x'.repeat(n));
    check(r === '', `a made-up link of ${n} characters: not a code (${r === '' ? 'empty' : r})`);
  }
  // a long damaged code that still holds a whole valid start: read as before, by the format's rule
  const long = read('#pair=' + PAIR + 'A'.repeat(1500 - (PAIR.length + 1500) % 4));
  check(long === PAIR, `a longer code with the same secret is read as before (${long.slice(0, 50)})`);
  console.log(fails.length ? 'FAILED' : 'all ok');
}
