// The plan sync's Kotlin in Wasm gives exactly the JVM's bytes (PlanSyncTest's frozen version-1 values): keys from a
// pairing code, and the frozen blob opens to the frozen plan. No browser, no map data. Usage: node test/sync-engine.mjs
// Node does not exit after loading the TeaVM Wasm GC module (even process.exit hangs – seen with the engine before the
// sync, too), so the checks run in a child process that is killed once it printed its last line.
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

const PAIR = 'AQEIDxYdJCsyOUBHTlVcY2pxeH-GjZSboqmwt77FzNPa';
const BLOB = 'AQUFBQUFBQUFBQUFBezwy_DS4VFukiv3lciLKrMNNEjd7xcqYoHVER5ZBI4rz0TPbKW5EUMZ1tFYUge1Y3M852rKXkBCQZmq1iHBROEndXRK7OMGVOO4YsmD_ALasLkzfJMGpmhFHoqWt-FC';
const k = JSON.parse(e.syncKeys(PAIR));
check(k.mailbox === '6_ppugL_I2cbDsZYhdOddA' && k.token === 'wHErkFiAjM3dUXVy18TJETV0arl4vvuSa21gZUBEpjE' && k.check === '395 514', 'keys = JVM (frozen)');
check(e.syncPairParse('https://plan.onpath.earth/#pair=' + PAIR) === PAIR, 'pairing link parses');
check(e.syncPairParse('https://plan.onpath.earth/#p=AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU') === '', 'a plan link is no pairing link');
const out = e.syncApply('', PAIR, 'CQkJCQkJCQkJCQkJCQkJCQ 7 ' + BLOB, 7);
const ledger = out.slice(0, out.lastIndexOf('#bad '));
check(out.endsWith('#bad 0'), 'frozen blob opens');
const list = JSON.parse(e.syncList(ledger, 'x'));
check(list.length === 1 && list[0].title === 'Waalweg' && list[0].plan.startsWith('AQUDAs'), 'frozen blob = plan "Waalweg"');
check(e.syncApply('', PAIR, 'CQkJCQkJCQkJCQkJCQkJCQ 7 ' + BLOB.slice(0, -2) + 'AA', 7).endsWith('#bad 1'), 'a changed blob is refused');
check(e.syncApply('', PAIR, 'AgICAgICAgICAgICAgICAg 7 ' + BLOB, 7).endsWith('#bad 1'), 'a blob moved to another item id is refused');
console.log(fails.length ? "FAILED" : "all ok");
}
