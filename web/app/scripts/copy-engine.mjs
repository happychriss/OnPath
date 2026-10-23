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
