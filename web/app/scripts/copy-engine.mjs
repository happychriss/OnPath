// Puts the files the page needs into public/: the Wasm engine (web/engine build) and the BRouter profiles from the
// app's assets – copied at build time, never committed, so they always come from the same commit as the app.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../../..');
const pub = path.resolve(here, '../public');
const gen = path.join(repo, 'web/engine/build/generated/teavm/wasm-gc');
fs.mkdirSync(path.join(pub, 'engine'), { recursive: true });
for (const f of ['onpath-web-engine.wasm', 'onpath-web-engine.wasm-runtime.js']) {
  const src = path.join(gen, f);
  if (!fs.existsSync(src)) { console.error(`missing ${src} – run: ./gradlew -p web/engine generateWasmGC copyWasmGCRuntime`); process.exit(1); }
  fs.copyFileSync(src, path.join(pub, 'engine', f));
}
fs.mkdirSync(path.join(pub, 'brouter'), { recursive: true });
const assets = path.join(repo, 'app/src/main/assets/brouter');
const profiles = fs.readdirSync(assets).filter((f) => f.endsWith('.brf') || f === 'lookups.dat');
for (const f of profiles) fs.copyFileSync(path.join(assets, f), path.join(pub, 'brouter', f));
fs.writeFileSync(path.join(pub, 'brouter', 'index.json'), JSON.stringify(profiles));
console.log('engine + profiles copied to public/');

// The app's map style (format 2) and its glyphs + sprite, unchanged; the page points the URLs at the web paths.
const a = path.join(repo, 'app/src/main/assets');
fs.mkdirSync(path.join(pub, 'style'), { recursive: true });
fs.copyFileSync(path.join(a, 'style/f2/onpath.json'), path.join(pub, 'style/onpath.json'));
fs.cpSync(path.join(a, 'fonts'), path.join(pub, 'fonts'), { recursive: true });
fs.cpSync(path.join(a, 'sprites'), path.join(pub, 'sprites'), { recursive: true });

// The world overview below the cells (docs/design/web-planner.md §16): the style with the app's Overview.merge applied
// (web/engine task overviewStyle) and – when ONPATH_OVERVIEW_DIR points at the APK's overview files (CI downloads
// them from the GitHub release the app's release workflow uses) – the files themselves. Without them the page draws
// the plain style (nothing below z10), as before.
const ovStyle = path.join(repo, 'web/engine/build/style/onpath-overview.json');
if (fs.existsSync(ovStyle)) fs.copyFileSync(ovStyle, path.join(pub, 'style/onpath-overview.json'));
else console.warn('no overview style – run: ./gradlew -p web/engine overviewStyle');
// the same merged for region packages (z7–9, map-cells.md §20; overview.ts prefers it)
const ovPackages = ovStyle.replace(/\.json$/, '-packages.json');
if (fs.existsSync(ovPackages)) fs.copyFileSync(ovPackages, path.join(pub, 'style/onpath-overview-packages.json'));
else console.warn('no region-package overview style – run: ./gradlew -p web/engine overviewStyle');
const ovDir = process.env.ONPATH_OVERVIEW_DIR;
if (ovDir) {
  fs.mkdirSync(path.join(pub, 'overview'), { recursive: true });
  for (const f of ['base.pmtiles', 'hillshade.pmtiles']) fs.copyFileSync(path.join(ovDir, f), path.join(pub, 'overview', f));
  console.log('overview files copied from ' + ovDir);
}
