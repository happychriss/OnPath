// Planner day rows sketch (owner 2026-11-21): today / A / B at phone width and at the side panel's width.
// Static HTML with the REAL web/app/src/{manrope,style,multiday}.css and the row markup of multiday.ts overview().
// Run from the repo root:  NODE_PATH=<dir with playwright> node docs/sketches/2026-11-21-day-rows/src/render.cjs
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const here = __dirname;
const root = path.resolve(here, '../../../..');
const webSrc = path.join(root, 'web/app/src');
const out = path.resolve(here, '../pics');
fs.mkdirSync(out, { recursive: true });

// the icon paths come from the planner's own icons.ts
const icons = fs.readFileSync(path.join(webSrc, 'icons.ts'), 'utf8');
const P = (n) => new RegExp(`^  ${n}: '([^']+)'`, 'm').exec(icons)[1];
const icon = (n, s) => `<svg class="ic" width="${s}" height="${s}" viewBox="0 0 24 24" aria-hidden="true"><path d="${P(n)}"/></svg>`;
const endSymbol = (kind, size) => {
  const cls = kind === 'PROPOSAL' ? 'grey' : kind === 'OVERNIGHT' ? 'blue' : 'green';
  return `<span class="daysym ${cls}" style="width:${size}px;height:${size}px">${icon(kind === 'HOTEL' ? 'bed' : 'moon', Math.round(size * 0.62))}</span>`;
};
const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

// the same data as the app pictures (null = no place near that end); km as the planner prints them (Format.distance, German)
const days = [
  { n: 1, from: 'München', to: 'Bad Tölz', km: '92,0 km', up: '640 m', time: '6 h 20 min', kind: 'PROPOSAL', over: false },
  { n: 2, from: 'Bad Tölz', to: 'Gasthof Krone', km: '96,0 km', up: '1420 m', time: '7 h 10 min', kind: 'HOTEL', over: true, hotel: 'Gasthof Krone' },
  { n: 3, from: 'Gasthof Krone', to: 'Brixen', km: '79,0 km', up: '980 m', time: '5 h 40 min', kind: 'OVERNIGHT', over: false },
  { n: 4, from: 'Brixen', to: null, km: '95,0 km', up: '420 m', time: '6 h 20 min', kind: 'PROPOSAL', over: false },
  { n: 5, from: null, to: 'Bassano', km: '101 km', up: '1120 m', time: '7 h 30 min', kind: 'PROPOSAL', over: false },
  { n: 6, from: 'Bassano', to: 'Venedig', km: '101 km', up: '220 m', time: '6 h 50 min', kind: null, over: false },
];
const along = ['km 362', 'km 362'];   // days_km_at text of the unnamed ends (day 4's end = day 5's start)
const SELECTED = 4;

function row(d, v) {
  const sel = d.n === SELECTED;
  const sym = d.kind ? endSymbol(d.kind, 22) : `<span class="daysym goal">${icon('flag', 14)}</span>`;
  const cls = `today${d.over ? ' over' : ''}${sel ? ' sel' : ''}`;
  if (v === 'today') {
    const title = `${d.from ?? along[0]} – ${d.to ?? along[1]}`;
    const endText = d.kind === 'PROPOSAL' ? 'Nacht noch offen' : d.kind === 'OVERNIGHT' ? 'Übernachtung' : d.kind === 'HOTEL' ? 'Hotel: ' + d.hotel : '';
    const over = d.over ? `Über der Grenze: ↑ ${d.up}` : '';
    return `<li><button class="${cls}" data-day="${d.n}" aria-pressed="${sel}">
          <span class="dn">${d.n}</span><span class="txt"><b>${esc(title)}</b><span class="muted small">${esc(`${d.km} · ↑ ${d.up} · ${d.time}`)}</span>
          ${over ? `<span class="overline small">${esc(over)}</span>` : endText ? `<span class="muted small">${esc(endText)}</span>` : ''}</span>
          ${sym}</button></li>`;
  }
  // A / B: „Tag n“, the day's km and ↑ m; the grey „von Ort – nach Ort“ line only when both places are known
  const names = d.from && d.to ? `<span class="nm muted small">${esc(`${d.from} – ${d.to}`)}</span>` : '';
  return `<li><button class="${cls}" data-day="${d.n}" aria-pressed="${sel}">
          <span class="txt"><span class="l1"><b>Tag ${d.n}</b><span class="vals">${esc(`${d.km} · ↑ ${d.up}`)}</span></span>${names}</span>
          ${sym}</button></li>`;
}

// the smallest CSS change: .dn goes, the first line is a flex row, A pushes the values right, the name line is cut with „…“
const variantCss = (v) => v === 'today' ? '' : `
.touroverview .today .l1 { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.touroverview .today .l1 b { white-space: nowrap; }
.touroverview .today .vals { white-space: nowrap; font-size: 13px; font-weight: 600; color: var(--muted); }
.touroverview .today.over .vals { color: var(--warn); }
.touroverview .today .nm { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
${v === 'a' ? '.touroverview .today .vals { margin-left: auto; }' : ''}`;

function page(v) {
  const rel = (f) => 'file://' + path.join(webSrc, f);
  return `<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="${rel('fonts/manrope.css')}"><link rel="stylesheet" href="${rel('style.css')}"><link rel="stylesheet" href="${rel('multiday.css')}">
<style>
html, body { height: auto; } body { padding: 0; background: #d9dccf; }
#sheet { position: static !important; max-height: none !important; margin: 0; }
.wrap { padding: 12px; } @media (max-width: 700px) { .wrap { padding: 0; } }
${variantCss(v)}
</style></head><body><div class="wrap"><div id="sheet">
<div id="tourOverview" class="touroverview">
  <div class="tohead"><button class="toname">München – Venedig</button>
    <span class="stepper"><button class="iconbtn" aria-label="weniger">−</button><b>6 Tage</b><button class="iconbtn" aria-label="mehr">+</button></span></div>
  <div class="tototals muted">564 km · ↑ 6480 m · 34 h 30 min</div>
  <ol class="todays">${days.map((d) => row(d, v)).join('')}</ol>
</div></div></div></body></html>`;
}

(async () => {
  const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
  const sizes = { phone: { width: 390, height: 900 }, desktop: { width: 1000, height: 900 } };
  for (const v of ['today', 'a', 'b']) {
    const html = path.join(here, `rows-${v}.html`);
    fs.writeFileSync(html, page(v));
    for (const [name, vp] of Object.entries(sizes)) {
      const ctx = await browser.newContext({ viewport: vp, deviceScaleFactor: 2, locale: 'de-DE' });
      const pg = await ctx.newPage();
      await pg.goto('file://' + html);
      await pg.evaluate(() => document.fonts.ready);
      const el = await pg.$(name === 'phone' ? '#sheet' : '#sheet');
      const box = await (await pg.$('.wrap')).boundingBox();
      await pg.screenshot({ path: path.join(out, `planer-${name}-${v === 'today' ? 'heute' : v}.png`), clip: { x: 0, y: 0, width: name === 'phone' ? 390 : 384 + 24, height: Math.ceil(box.height) } });
      await ctx.close();
    }
  }
  await browser.close();
})();
