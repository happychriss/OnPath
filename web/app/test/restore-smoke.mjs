// "Deinen alten Plan wiederherstellen?" on the page (owner 2026-11-24, the app's RestoreAsk + answerRestore; spec
// docs/team/items/2026-11-24-restore-replaced-plan.md): a plan REPLACED outside Neu planen (a Zuletzt / saved plan opened, a
// plan link, a track's plan) asks – FRAGE, the app's words, Keep · Restore, until answered; Restore puts the old plan back
// (the plan on the map goes under Zuletzt, no second question), Keep leaves everything; the box goes when the old entry
// leaves My routes (the app's RestoreAsk.box on the page's list). Neu planen / the first plan on an empty map ask nothing.
// Usage: node test/restore-smoke.mjs [segmentsDir]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const A = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Meran → Vellau
const C = 'AQACAujkuQSU6YcBi2X0MAMCBwBBbGd1bmQCBQFMYW5h'; // Algund → Lana
const C_PTS = 'AujkuQSU6YcB', A_PTS = 'B1dhYWx3ZWcC'; // where the plans' points sit in the page's address
const here = path.dirname(fileURLToPath(import.meta.url));
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };

const segArg = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const segments = fs.existsSync(segArg) ? segArg : here;
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const errors = [];
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, locale: 'en-GB' });
await ctx.addInitScript(({ C }) => {
  if (sessionStorage.getItem('seeded')) return;
  sessionStorage.setItem('seeded', '1');
  localStorage.setItem('onpath.recent', JSON.stringify([{ id: 'old', code: C, title: 'Old Lana', points: '46.66,11.13;46.6,11.14', at: Date.now() - 3600_000 }]));
  localStorage.setItem('onpath.zuletztTrimmed', '1');
}, { C });
const page = await ctx.newPage();
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const ready = () => page.waitForFunction(() => document.body.dataset.engine === 'ready' && document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 120_000 });
const planned = () => page.waitForFunction(() => !!document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 });
const SEL = '.msgcard.frage[data-id="restore"]';
const box = () => page.evaluate((sel) => { const c = document.querySelector(sel);
  return c ? { title: c.querySelector('.msgtitle').textContent, detail: c.querySelector('.msgdetail')?.textContent, buttons: [...c.querySelectorAll('button')].map((b) => b.textContent), edit: !!c.querySelector('button.primary.edit') } : null; }, SEL);
const waitBox = (on) => page.waitForFunction(([sel, on]) => !!document.querySelector(sel) === on, [SEL, on], { timeout: 15_000 }).then(() => true, () => false);
const hash = () => page.evaluate(() => location.hash);
const store = () => page.evaluate(() => { const l = JSON.parse(localStorage.getItem('onpath.recent') ?? '[]');
  return { drafts: l.filter((r) => r.deletedAt == null).map((r) => r.title), bin: l.filter((r) => r.deletedAt != null).map((r) => r.title) }; });
const openList = async () => { await page.click('#menuPlans'); await page.waitForSelector('dialog.plans[open] #planList'); await page.waitForTimeout(150); };

await page.goto(url + 'index.html?lang=en');
await ready();
// the first plan on an empty map replaces nothing: no question
await openList();
await page.click('#planList li.draft .open');
await planned();
check(!(await box()), 'a plan opened on an empty map: no question');

// a plan link replaces "Old Lana" (opened from Zuletzt, unchanged): the question, the app's words
await page.evaluate((c) => { location.hash = 'p=' + c; }, A);
await planned();
check(await waitBox(true), 'a plan link over a plan: the box shows');
const b = await box();
check(b?.title === 'Restore your old plan?' && b.detail === '“Old Lana” is kept safe.' && b.buttons.join() === 'Keep,Restore' && b.edit,
  `the app's words: title, “name” is kept safe, Keep · Restore (blue) (${JSON.stringify(b)})`);
check((await hash()).includes(A_PTS), 'the link plan is on the map');
// Restore: the old plan comes back, the link plan goes under Zuletzt, nothing asks again
await page.click(`${SEL} button.primary`);
await page.waitForFunction((p) => location.hash.includes(p), C_PTS, { timeout: 60_000 });
await planned();
await page.waitForTimeout(500);
check(!(await box()), 'Restore: the box is gone and asks nothing again (the way back is no replacement)');
const s = await store();
check(s.drafts.length === 1 && s.drafts[0] !== 'Old Lana', `Restore: the link plan is the Zuletzt plan, nothing lost (${JSON.stringify(s)})`);

// replaced again, Keep: nothing changes, the box goes
await page.evaluate((c) => { location.hash = 'p=' + c; }, A);
await planned();
check(await waitBox(true), 'replaced again: the box shows');
// the poll must not redraw the box every tick (a click spanning a redraw is lost): it is drawn once for the same facts
await page.evaluate(() => { window.__redraws = 0; new MutationObserver(() => { window.__redraws++; }).observe(document.getElementById('msgcards'), { childList: true, subtree: true }); });
await page.waitForTimeout(6_500);
const redraws = await page.evaluate(() => window.__redraws);
check(redraws === 0, `the box is not redrawn by the poll while nothing changed (${redraws} redraws in 6.5 s)`);
const before = await store();
await page.click(`${SEL} button:first-child`);
await page.waitForTimeout(300);
await page.waitForFunction((p) => location.hash.includes(p), A_PTS, { timeout: 10_000 }).catch(() => {});
check(!(await box()) && (await hash()).includes(A_PTS), 'Keep: the box goes, the new plan stays on the map');
check(JSON.stringify(await store()) === JSON.stringify(before), 'Keep: My routes is as the replacement left it');

// the old entry leaves My routes: the question goes with it
await page.evaluate((c) => { location.hash = 'p=' + c; }, C);
await planned();
check(await waitBox(true), 'replaced once more: the box shows');
// Neu planen keeps the plan on the map as the one Zuletzt plan, the old entry goes to the bin (the app's one-plan rule):
// the question goes with it, and Neu planen asks nothing itself
await page.click('#newPlan');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 });
check(await waitBox(false), 'Neu planen: the old entry left My routes (bin) – the question is gone with it, none asked');

const importHike = async () => {
  let btn = '#menuImportGpx';
  if (await page.isVisible('#menuBtn')) await page.click('#menuBtn'); else { await page.click('#menuPlans'); btn = '#plansImportGpx'; }
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click(btn)]);
  await chooser.setFiles(path.join(here, 'fixtures/komoot-hike.gpx'));
  await page.waitForSelector('#trackPanel', { timeout: 30_000 });
};
const activity = () => page.evaluate(() => window.__state.activity + ' T' + window.__state.sac);

// a track's plan replaces a Bike plan: the GPX import switched the profile to Hike T2 – Restore brings the Bike plan back
// with ITS profile, not the track's (the app's TrackToPlanState: leavingProfile)
const BIKE = await page.evaluate(() => window.__engine.encode({ points: [[46.6773, 11.1302], [46.66, 11.14]], activity: 'BIKE_TOURING', sac: 2, pinned: [], names: {}, title: null }));
await page.evaluate((c) => { location.hash = 'p=' + c; }, BIKE);
await planned();
check((await activity()) === 'BIKE_TOURING T2', `a Bike plan on the map (${await activity()})`);
await importHike();
check((await activity()) === 'HIKE T2', `the Komoot track switched the profile to Hike T2 (${await activity()})`);
await page.click('#planFromTrack');
const disc = await page.waitForSelector('dialog.discard[open]', { timeout: 1_500 }).catch(() => null);
if (disc) await page.click('dialog.discard #discardOk');
await page.waitForFunction(() => !document.getElementById('trackPanel'), null, { timeout: 120_000 });
await planned();
check(await waitBox(true), "a track's plan over the Bike plan: the box shows");
await page.click(`${SEL} button.primary`);
const bikeBack = await page.waitForFunction(() => window.__state.activity === 'BIKE_TOURING', null, { timeout: 60_000 }).then(() => true, () => false);
check(bikeBack, `Restore after a track's plan: the old plan's own profile, Bike (${await activity()})`);
await planned();
const dr = await page.evaluate(() => JSON.parse(localStorage.getItem('onpath.recent')).filter((r) => r.deletedAt == null).map((r) => r.code));
const kept = dr.length ? await page.evaluate((c) => window.__engine.parse('p=' + c), dr[0]) : null;
check(kept?.activity === 'HIKE', `the track's plan went under Zuletzt with its own profile, Hike (${kept?.activity})`);

// a saved route whose points are the plan on the map when it is restored: still applies its profile and keeps the plan
await page.click('#newPlan');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 });
const REV = await page.evaluate(async (a) => { const sh = await window.__engine.parse('p=' + a);
  return window.__engine.encode({ ...sh, points: [...sh.points].reverse(), activity: 'BIKE_TOURING', sac: 2, pinned: [], names: {}, title: null }); }, A);
await page.evaluate((c) => { location.hash = 'p=' + c; }, REV);
await planned();
await page.click('#menuSavePlan');
await page.waitForSelector('dialog.savename[open] #saveTitle', { timeout: 10_000 });
await page.fill('#saveTitle', 'Reversed');
await page.click('#saveOk');
await page.waitForFunction(async () => (await window.__sync.list()).some((p) => p.title === 'Reversed'), null, { timeout: 10_000 });
await page.waitForFunction(() => !document.getElementById('savedLine').hidden, null, { timeout: 10_000 });
await page.evaluate((c) => { location.hash = 'p=' + c; }, A);
await planned();
check(await waitBox(true), 'a saved route as it is, replaced by a link: the box shows');
check((await box())?.detail === '“Reversed” is saved.', `… “name” is saved (${(await box())?.detail})`);
await page.click('#reverse'); // the plan on the map now has exactly the saved route's points
await planned();
await page.click(`${SEL} button.primary`);
const sameBack = await page.waitForFunction(() => window.__state.activity === 'BIKE_TOURING', null, { timeout: 60_000 }).then(() => true, () => false);
check(sameBack, `Restore with the same points on the map: the saved route's profile applies (${await activity()})`);
await planned();
const sid = await page.evaluate(() => window.__state.savedId);
check(!!sid, 'Restore with the same points: the saved route is the plan on the map again');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `restore-smoke: ${fails.length} FAILED` : 'restore-smoke: all ok');
process.exit(fails.length ? 1 : 0);
