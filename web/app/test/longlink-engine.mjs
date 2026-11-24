// A long plan link must open on a freshly loaded page. BRouter-free, no browser. The engine's first calls run in Wasm's slow
// tier with big stack frames, where the Regex that finds the code in a link ran out of stack (RangeError, the page's „This plan
// link can't be read“). The child runs with a small stack and without the fast tier, so the failure does not depend on timing.
// Usage: node test/longlink-engine.mjs
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
  const read = (t) => { try { return JSON.parse(e.planParse(t)); } catch (x) { return String(x).slice(0, 70); } };
  const pts = '46.600000,11.200000;46.700000,11.300000';
  for (const n of [500, 1000, 1500, 2000]) {
    const code = e.planCodeTour(pts, 'HIKE', 3, '', '', '', 'd\t2\nl\t0\t0\nt\t' + 'x'.repeat(n));
    for (const [what, text] of [['link', 'https://plan.onpath.earth/#p=' + code], ['p=', 'p=' + code], ['bare code', code]]) {
      const r = read(text);
      check(r?.tour?.description?.length === n, `${what}, ${code.length} characters: ${typeof r === 'string' ? r : 'read'}`);
    }
  }
  // many points: the longest kind of plan a link holds today
  const many = Array.from({ length: 500 }, (_, i) => `${(46 + i * 0.00137).toFixed(6)},${(11 + (i % 97) * 0.00211).toFixed(6)}`).join(';');
  const big = e.planCode(many, 'HIKE', 3, '', '', '');
  const r = read('https://plan.onpath.earth/#p=' + big);
  check(r?.points?.length === 500, `link with 500 points, ${big.length} characters: ${typeof r === 'string' ? r : 'read'}`);
  console.log(fails.length ? 'FAILED' : 'all ok');
}
