// Tour-suggestions POC: pictures of a saved run (run.py --png).  node shot.js <base url> <run id> <out dir>
// overview.png = the chosen routes; route-<id>.png = one chosen route coloured by way kind.
const { chromium } = require('playwright');
(async () => {
  const [base, run, out] = process.argv.slice(2);
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1500, height: 1000 }, deviceScaleFactor: 1 });
  const open = async (q, file) => {
    await page.goto(`${base}/?run=${run}${q}`);
    await page.waitForFunction(() => document.body.dataset.ready === '1', null, { timeout: 15000 });
    await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {}); // expected: slow tiles – shoot anyway
    await page.waitForTimeout(600);
    await page.screenshot({ path: `${out}/${file}` });
    console.log(`${out}/${file}`);
  };
  await open('', 'overview.png');
  const r = await (await fetch(`${base}/run/${run}`)).json();
  for (const c of r.candidates.filter(c => c.chosen)) await open(`&sel=${c.id}&only=${c.id}`, `route-${c.id}.png`);
  await browser.close();
})().catch(e => { console.error('shot failed: ' + e.message); process.exit(1); });
