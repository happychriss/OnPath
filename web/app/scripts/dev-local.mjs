// The planner on this computer with local data, for looking at tour suggestions (src/suggest/, switched off elsewhere):
// starts a small file server for routing data + a map store (HTTP ranges, like the host) and then the Vite dev server,
// which passes /segments/ and /data/ to it and /poc/ to the tour-suggestions POC (tools/suggest-poc/serve.py).
//   node scripts/dev-local.mjs <segmentsDir> <mapStoreDir> [port=5199] [overviewDir with base.pmtiles + hillshade.pmtiles]
//   → http://localhost:5199/?suggest=1          (mechanism: the POC, method b; &suggestPoc=c, &suggestVia=ring)
// Needs the engine built once: tools/dev/gradle_slot.sh -- ./gradlew -q -p web/engine generateWasmGC copyWasmGCRuntime overviewStyle
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { serve } from '../test/serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const [segments, store, port = '5199', overview = ''] = process.argv.slice(2);
if (!segments || !store) { console.error('usage: node scripts/dev-local.mjs <segmentsDir> <mapStoreDir> [port]'); process.exit(2); }
const extra = overview ? Object.fromEntries(['base.pmtiles', 'hillshade.pmtiles'].map((f) => ['/overview/' + f, path.join(path.resolve(overview), f)])) : {};
const { url } = await serve({ dist: path.resolve(here, '../public'), segments: path.resolve(segments), dataDir: path.resolve(store), extra });
console.log('local data at ' + url);
const vite = spawn('npm', ['run', 'dev', '--', '--port', port, '--strictPort', '--host', '127.0.0.1'],
  { cwd: path.resolve(here, '..'), stdio: 'inherit', env: { ...process.env, DEV_DATA: url.replace(/\/$/, ''), ...(overview ? { DEV_OVERVIEW: '1' } : {}) } });
vite.on('exit', (c) => process.exit(c ?? 0));
