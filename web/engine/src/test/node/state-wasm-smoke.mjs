// The page's slices IN THE WASM (WebState.kt): the engine's JVM tests (WebStateTest) prove the behaviour, this proves
// that TeaVM's wasm-gc build runs the same – the exports are there (they are module exports only because WebState.kt is
// part of the main class, see its comment), the real RoutesState / SaveDialog work on the Compose stand-in, and a broken
// input comes back as {"error":…}, never as a trap. Also: two older exports still answer (the multifile class changed
// how every export is compiled).
// Usage, after `./gradlew -p web/engine generateWasmGC copyWasmGCRuntime`:   node web/engine/src/test/node/state-wasm-smoke.mjs
// (Node does not exit after loading the TeaVM Wasm GC module: the checks run in a child process, as web/app/test/lang-engine.mjs.)
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

if (process.argv[2] !== '--child') {
  // TeaVM's Wasm uses the newer exception opcodes: an older Node 22 (22.14) needs a V8 flag for them, a newer one has
  // them on (and may not know the flag any more) – so: first without, and once more with the flag when Node asks for it
  const FLAG = '--experimental-wasm-exnref';
  const run = (flags) => {
    const child = spawn(process.execPath, [...flags, fileURLToPath(import.meta.url), '--child'], { stdio: ['ignore', 'pipe', 'inherit'] });
    let out = '';
    const timer = setTimeout(() => { console.log('FAIL timeout'); child.kill('SIGKILL'); process.exit(1); }, 90_000);
    child.stdout.on('data', (d) => {
      out += d;
      const end = /^(all ok|FAILED)$/m.exec(out);
      if (!end) return;
      clearTimeout(timer); child.kill('SIGKILL');
      if (end[1] === 'FAILED' && !flags.length && out.includes(FLAG)) { run([FLAG]); return; }
      process.stdout.write(out);
      process.exit(end[1] === 'all ok' ? 0 : 1);
    });
    child.on('exit', (code, signal) => {
      if (!signal && !/^(all ok|FAILED)$/m.test(out)) { process.stdout.write(out); console.log('FAIL child ended early'); process.exit(1); }
    });
  };
  run([]);
} else {
  let bad = 0;
  const check = (ok, what, got) => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok || got === undefined ? '' : ' – got ' + got}`); if (!ok) bad++; };
  const same = (got, want, what) => check(got === want, what, got);
  try {
    const here = path.dirname(fileURLToPath(import.meta.url));
    const gen = path.join(here, '../../../build/generated/teavm/wasm-gc');
    await import(path.join(gen, 'onpath-web-engine.wasm-runtime.js'));
    const t0 = performance.now();
    const { exports: e } = await TeaVM.wasmGC.load(path.join(gen, 'onpath-web-engine.wasm'));
    console.log(`     engine loaded in ${Math.round(performance.now() - t0)} ms`);

    const names = ['routesStoreLoad', 'routesStoreTakeWrites', 'routesStoreMoved', 'routesStateJson', 'routesRecentRow', 'routesOpen', 'routesTab',
      'routesSortTap', 'routesShowSaved', 'routesMarkSaved', 'routesTakePlace', 'routesKeepDraft', 'routesAfterSaved', 'routesSaveDraft', 'routesToBin',
      'routesRestore', 'routesRenameDraft', 'routesTrimDrafts', 'saveDialogJson', 'saveDialogRequest', 'saveDialogRequestCopy', 'saveDialogProposed',
      'saveDialogCancel', 'saveDialogConfirm'];
    const missing = names.filter((n) => typeof e[n] !== 'function');
    check(missing.length === 0, `all ${names.length} exports of WebState.kt are module exports`, missing.join(', '));
    check(typeof e.planParse === 'function' && typeof e.waitTick === 'function', 'the exports of WebApi.kt are still there');

    const a = e.planCode('46.0,11.0;46.01,11.01', 'HIKE', 2, '', '', ''), b = e.planCode('46.02,11.0;46.03,11.0', 'HIKE', 2, '', '', '');
    const c = e.planCode('46.04,11.0;46.05,11.0', 'HIKE', 2, '', '', '');
    same(JSON.parse(e.planParse('p=' + a)).points.length, 2, 'an older export still answers (planParse)');

    const saved = { id: 's1', kind: 'plan', title: 'Kofel', plan: c, track: '', updatedAt: 100, device: 'd1', mine: true, dirty: false, onPhone: true };
    const draft = { id: 'r1', code: a, title: 'Route 7 Nov', points: '46.000000,11.000000;46.010000,11.010000', at: 200, later: { x: [1, 2.5, null, 'é\n'] } };
    same(e.routesStoreLoad(JSON.stringify({ saved: [saved], drafts: [draft], binned: [], recent: { kind: 'draft', id: 'r1' } })), '{"rows":2,"skipped":0}', 'load');
    let s = JSON.parse(e.routesStateJson());
    check(s.recent?.id === 'r1' && s.tours.length === 2 && s.tours[0].points === 2 && s.row?.place?.id === 'r1' && s.pendingWrites === 0, 'the state after the load', JSON.stringify(s));

    same(e.routesKeepDraft('r2', b, 'Route 8 Nov', '46.02,11;46.03,11', true, '', '', 5000), '{"id":"r2"}', 'the next plan is kept as a new draft');
    s = JSON.parse(e.routesStateJson());
    check(s.recent?.id === 'r2' && s.bin.length === 1 && s.bin[0].id === 'r1' && s.bin[0].deletedAt === 5000 && s.tours.map((t) => t.id).join() === 'r2,s1',
      'the one before is in the bin at the time passed in, the saved route untouched', JSON.stringify(s));
    const w = JSON.parse(e.routesStoreTakeWrites());
    same(w.map((x) => x.op).join(), 'insert,setDeleted,recent', 'the drained writes, in order');
    same(JSON.stringify(w[1].row), JSON.stringify({ ...draft, deletedAt: 5000 }), 'the page’s own object comes back whole, unknown fields untouched');
    same(e.routesStoreTakeWrites(), '[]', 'taken once');

    for (const bad of ['', '{', '[1]', '{"saved":[{"id":']) check('error' in JSON.parse(e.routesStoreLoad(bad)), `malformed JSON ${JSON.stringify(bad)} → an error JSON, no trap`);
    check('error' in JSON.parse(e.routesTakePlace('folder', 'x', 1)), 'a wrong argument → an error JSON');
    check(JSON.parse(e.problems()).some((p) => p.includes('routes')), 'reported to Problems');
    same(JSON.parse(e.routesStateJson()).tours.length, 2, 'the list stays as it was');

    same(e.saveDialogRequest(false, '', '', 'Route 31 Oct, 10:12', 'Vellau → Leiteralm'), '{"token":1}', 'Save opens the dialog');
    same(e.saveDialogProposed(1, 'Vellau → Leiteralm', 'Route 31 Oct, 10:12', 'Vellau → Leiteralm'), '{"ok":true}', 'the proposal arrives');
    same(e.saveDialogJson(), '{"open":true,"ask":{"kind":"NEW","name":"Vellau → Leiteralm (2)","routeId":null,"token":1}}', 'the dialog’s state');
    same(e.saveDialogConfirm('  Leiteralm loop ', ''), '{"write":{"kind":"NEW","name":"Leiteralm loop","routeId":null}}', 'Save decides the write');
    same(e.saveDialogJson(), '{"open":false,"ask":null}', 'the dialog is closed');
  } catch (err) {
    console.log('FAIL exception: ' + (err?.stack ?? err)); bad++;
  }
  console.log(bad ? 'FAILED' : 'all ok');
}
