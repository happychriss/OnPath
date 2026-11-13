import { defineConfig } from 'vite';
import { execSync } from 'node:child_process';

// One id per build (the commit + build time): the page knows its own, version.json says what is deployed – an open tab
// with an older page offers "Reload" (version.ts); the engine files are loaded with ?v=<id> (no old engine from a cache).
const sha = (process.env.GITHUB_SHA ?? (() => {
  try { return execSync('git rev-parse HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim(); }
  catch { return 'dev'; } // expected: built outside a git checkout
})()).slice(0, 12);
const BUILD_ID = `${sha}-${Date.now().toString(36)}`;

export default defineConfig({
  base: './',
  define: { __BUILD_ID__: JSON.stringify(BUILD_ID) },
  build: {
    target: 'es2022',
    rollupOptions: { input: { main: 'index.html', compare: 'compare.html' } },
  },
  worker: { format: 'es' },
  // dev server only: the tour-suggestions POC on this computer (tools/suggest-poc/serve.py) for the `poc` mechanism
  // (src/suggest/poc.ts) – a production build has neither this proxy nor that mechanism
  server: { proxy: {
    '/poc': { target: process.env.SUGGEST_POC ?? 'http://127.0.0.1:8790', changeOrigin: true, rewrite: (p) => p.replace(/^\/poc/, '') },
    // … and, with scripts/dev-local.mjs, routing data + a map store from this computer
    ...(process.env.DEV_DATA ? { '/segments': { target: process.env.DEV_DATA }, '/data': { target: process.env.DEV_DATA } } : {}),
    ...(process.env.DEV_DATA && process.env.DEV_OVERVIEW ? { '/overview': { target: process.env.DEV_DATA } } : {}),
  } },
  plugins: [{
    name: 'onpath-version',
    generateBundle() { this.emitFile({ type: 'asset', fileName: 'version.json', source: JSON.stringify({ build: BUILD_ID }) + '\n' }); },
  }],
});
