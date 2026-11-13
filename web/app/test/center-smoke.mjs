// Every window the planner lays over the page sits in the middle (owner 2026-11-13: „Wenn du irgendwelche Bilder
// anzeigst oder Warnfenster anzeigst, achte darauf, dass sie wirklich mittig stehen und nicht irgendwie auf der Seite.“):
// each modal dialog – Share (QR), About, Keyboard shortcuts, Map content, Save's name, Meine Touren,
// Phone, pairing (QR), joining, the long-route question, "Planning from the tour…" – at 1280×800, 1920×1080 and
// 412×839 has equal margins left / right and top / bottom (±2 px; taller than the window: top = bottom), and a picture in
// it (a QR code) sits in the middle of the dialog. Not checked: what sits beside the plan
// sheet on purpose (place card, map notes) and the app's message box under the top bar (MessagePlacement).
// Usage: node test/center-smoke.mjs [segmentsDir] [screenshotDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, firefox, webkit } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleSync } from '../../host/src/sync.js';
import { fakeD1 } from '../../host/test/fake-d1.js';

const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (PlanCodeTest), "Waalweg"
const here = path.dirname(fileURLToPath(import.meta.url));
const segArg = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const segments = fs.existsSync(segArg) ? segArg : here;
const shots = process.argv[3] ? path.resolve(process.argv[3]) : null;
if (shots) fs.mkdirSync(shots, { recursive: true });
const env = { SYNC: fakeD1() };
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, sync: (r) => handleSync(r, env) });
// BROWSER=firefox|webkit (manual, not in CI), SIZES=1366x650,1536x864 (more windows to try)
const engine = { firefox, webkit }[process.env.BROWSER] ?? chromium;
const browser = await engine.launch(engine === chromium ? { args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] } : {});
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const TOL = 2;

/** The open dialog [sel]'s margins to the window and, for [img], the picture's margins inside the dialog (in the page). */
const measureFn = ([sel, img]) => {
  const d = document.querySelector(sel);
  if (!d) return null;
  const W = window.innerWidth, H = window.innerHeight, r = d.getBoundingClientRect();
  const out = { l: r.left, r: W - r.right, t: r.top, b: H - r.bottom, w: r.width, h: r.height, W, H };
  if (img) {
    const i = d.querySelector(img);
    if (i) {
      const q = i.getBoundingClientRect();
      const f = i.parentElement.getBoundingClientRect(); // and in the middle of its frame (the QR's white)
      out.img = { l: q.left - r.left, r: r.right - q.right, w: q.width, h: q.height, fl: q.left - f.left, fr: f.right - q.right, ft: q.top - f.top, fb: f.bottom - q.bottom };
      // a drawn route shape: its line in the middle of its picture
      const paths = [...i.querySelectorAll(':scope > path')];
      if (i.tagName.toLowerCase() === 'svg' && paths.length && i.classList.contains('routeshape')) {
        let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
        for (const p of paths) { const b = p.getBBox(); x0 = Math.min(x0, b.x); x1 = Math.max(x1, b.x + b.width); y0 = Math.min(y0, b.y); y1 = Math.max(y1, b.y + b.height); }
        const vb = i.viewBox.baseVal;
        out.shape = { l: x0, r: vb.width - x1, t: y0, b: vb.height - y1 };
      }
    }
  }
  return out;
};
const r1 = (v) => Math.round(v * 10) / 10;
function judge(tag, m, img) {
  if (!m) { check(false, `${tag}: the dialog is open`); return; }
  const tall = m.h >= m.H - 1;
  check(Math.abs(m.l - m.r) <= TOL && Math.abs(m.t - m.b) <= TOL && m.l >= 0 && (tall || m.t >= 0),
    `${tag}: in the middle of the window (left ${r1(m.l)} / right ${r1(m.r)}, top ${r1(m.t)} / bottom ${r1(m.b)}, ${r1(m.w)}×${r1(m.h)} in ${m.W}×${m.H})`);
  if (img) {
    check(!!m.img && m.img.w > 0 && Math.abs(m.img.l - m.img.r) <= TOL, `${tag}: the picture (${img}) in the middle of the dialog (${m.img ? `left ${r1(m.img.l)} / right ${r1(m.img.r)}` : 'missing'})`);
    check(!!m.img && Math.abs(m.img.fl - m.img.fr) <= TOL && Math.abs(m.img.ft - m.img.fb) <= TOL && m.img.ft >= -1 && m.img.fl >= -1,
      `${tag}: the picture in the middle of its frame, not sticking out (${m.img ? `left ${r1(m.img.fl)} / right ${r1(m.img.fr)}, top ${r1(m.img.ft)} / bottom ${r1(m.img.fb)}` : 'missing'})`);
    if (m.shape) check(Math.abs(m.shape.l - m.shape.r) <= TOL || Math.abs(m.shape.t - m.shape.b) <= TOL ? Math.abs(m.shape.l - m.shape.r) <= TOL + 1 && Math.abs(m.shape.t - m.shape.b) <= TOL + 1 : false,
      `${tag}: the route's shape in the middle of its picture (left ${r1(m.shape.l)} / right ${r1(m.shape.r)}, top ${r1(m.shape.t)} / bottom ${r1(m.shape.b)})`);
  }
}

/** Polls [fn] in the page until it is truthy. */
async function until(P, fn, arg = null, timeout = 30_000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await P.evaluate(fn, arg).catch(() => false)) return true; await new Promise((r) => setTimeout(r, 250)); }
  return false;
}

const SIZES = process.env.SIZES ? process.env.SIZES.split(',').map((x) => x.split('x').map(Number)) : [[1280, 800], [1920, 1080], [412, 839]];
for (const [W, H] of SIZES) {
  const size = `${W}×${H}`;
  const ctx = await browser.newContext({ viewport: { width: W, height: H }, locale: 'en' });
  const P = await ctx.newPage();
  P.on('pageerror', (e) => errors.push(String(e)));
  const opened = async (sel, img = null, name = sel) => {
    await P.waitForSelector(sel, { timeout: 30_000 }).catch(() => {});
    await P.waitForTimeout(350); // fonts settle
    judge(`${size} ${name}`, await P.evaluate(measureFn, [sel, img]), img);
    if (shots) await P.screenshot({ path: path.join(shots, `${name.replace(/[^a-z]+/gi, '-').replace(/^-|-$/g, '')}-${W}x${H}.png`) });
  };
  /** A plan bar entry: inline on a wide screen, behind Plan ▾ at phone width. */
  const menu = async (id) => { if (await P.isVisible('#menuBtn')) await P.click('#menuBtn'); await P.click(id); };

  await P.goto(url + 'index.html?lang=en#p=' + CODE);
  await P.waitForFunction(() => document.body.dataset.engine === 'ready' && document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 120_000 });
  await P.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 }).catch(() => {});
  await P.click('#appHintLine .x', { timeout: 2_000 }).catch(() => {});

  // Share: the QR code
  if (!(await P.isVisible('#share'))) await P.evaluate(() => document.getElementById('share').scrollIntoView());
  await P.click('#share');
  await opened('dialog.share[open]:has(.qr)', '.qr svg', 'share');
  await P.click('#closeShare');
  // About (credits) and the keyboard shortcuts
  await P.evaluate(() => document.getElementById('credits').click());
  await opened('dialog[open]:has(#closeCredits)', null, 'about');
  await P.click('#closeCredits');
  await P.evaluate(() => document.body.dispatchEvent(new KeyboardEvent('keydown', { key: '?', bubbles: true })));
  await opened('dialog.keys[open]', null, 'keys');
  await P.click('#closeKeys');
  // Map content (a large panel; full screen on a phone)
  await P.click('#mapContent');
  await opened('dialog.mapcontent[open]', null, 'mapcontent');
  await P.keyboard.press('Escape');
  // Save's name dialog
  await menu('#menuSavePlan');
  await opened('dialog.savename[open]', null, 'savename');
  await P.click('#saveCancel');
  // Phone, then pairing (its QR code)
  if (await P.isVisible('#menuBtn')) { await P.click('#menuBtn'); await P.click(await P.isVisible('#menuPhone') ? '#menuPhone' : '#syncBadge'); }
  else await P.click(await P.isVisible('#phoneMore') ? '#phoneMore' : '#syncBadge');
  const phone = await P.waitForSelector('dialog.phone[open]', { timeout: 10_000 }).catch(() => null);
  if (phone) {
    await opened('dialog.phone[open]', null, 'phone');
    await P.click('dialog.phone #pairPhone');
  }
  await opened('dialog.pair[open]:has(#pairLink)', '.qr svg', 'pair');
  await P.click('#closePair');
  // Meine Touren (no large overview in the planner, owner 2026-11-13): the route on the map goes to Recent with + New
  await menu('#newPlan');
  await menu('#menuPlans');
  await opened('dialog.plans[open]', null, 'plans');
  await P.keyboard.press('Escape');
  await P.waitForTimeout(300);
  // "Planning from the tour…" (the app's PlanFromTrackDialog): open only while the engine works – measured at once
  await P.setInputFiles('#gpxFile', path.join(here, 'fixtures/komoot-hike.gpx'));
  await P.waitForSelector('#planFromTrack:not([disabled])', { timeout: 120_000 }).catch(() => {});
  const busy = await P.evaluate(async (fn) => {
    const measure = new Function('return ' + fn)();
    document.getElementById('planFromTrack')?.click();
    let d = null;
    for (let i = 0; i < 400 && !d; i++) { d = document.querySelector('dialog.trackbusy[open]'); if (!d) await new Promise((r) => setTimeout(r, 0)); }
    const m = d ? measure(['dialog.trackbusy[open]', null]) : null;
    document.getElementById('cancelTrackPlan')?.click();
    return m;
  }, measureFn.toString());
  judge(`${size} trackbusy`, busy, null);
  await P.waitForTimeout(500);
  // the long-route question (a plan ~600 km long, asked before anything is fetched)
  const far = await P.evaluate(() => window.__engine.encode({ points: [[46.6700, 11.1600], [52.0500, 11.1600]], activity: 'HIKE', sac: 2, pinned: [], names: {}, title: null }));
  await P.goto(url + 'index.html?lang=en&x=1#p=' + far);
  await opened('dialog.longroute[open]', null, 'longroute');
  await P.click('#lrCancel');
  // joining another browser's phone (a pairing link opened here)
  await P.goto(url + 'index.html?lang=en&x=2#pair=AAAA');
  await opened('dialog.join[open]', null, 'join');
  await ctx.close();
}

await browser.close();
server.close();
check(errors.length === 0, `no page errors (${errors.slice(0, 3).join(' | ')})`);
console.log(fails.length ? `\n${fails.length} FAILED` : '\nall dialogs in the middle');
process.exit(fails.length ? 1 : 0);
