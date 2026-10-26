// Tiny static server for the tests: the built page (web/app/dist), cases.json, and rd5 files with HTTP range
// support (like brouter.de / R2). No dependencies.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm',
  '.json': 'application/json', '.css': 'text/css', '.pmtiles': 'application/octet-stream' };

/** [dataDir] = a map store root (cells/f2/10/<x>/<y>/…, like the R2 bucket), served at /data/. */
export function serve({ dist, segments, mapDir = null, dataDir = null, extra = {}, port = 0 }) {
  const server = http.createServer((req, res) => {
    const url = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    let file;
    if (url.startsWith('/segments/')) file = path.join(segments, path.basename(url));
    else if (url.startsWith('/map/') && mapDir) file = path.join(mapDir, path.basename(url));
    else if (url.startsWith('/data/') && dataDir) file = path.join(dataDir, path.normalize(url.slice(6)));
    else if (extra[url]) file = extra[url];
    else file = path.join(dist, path.normalize(url === '/' ? '/index.html' : url));
    if (!file.startsWith(dist) && !file.startsWith(segments) && !(mapDir && file.startsWith(mapDir)) && !(dataDir && file.startsWith(dataDir)) && !Object.values(extra).includes(file)) { res.writeHead(403).end(); return; }
    fs.stat(file, (err, st) => {
      if (err || !st.isFile()) { res.writeHead(404).end(); return; }
      const head = { 'Content-Type': types[path.extname(file)] ?? 'application/octet-stream', 'Accept-Ranges': 'bytes' };
      const m = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range ?? '');
      if (m) {
        const start = Number(m[1]); const end = m[2] ? Math.min(Number(m[2]), st.size - 1) : st.size - 1;
        res.writeHead(206, { ...head, 'Content-Range': `bytes ${start}-${end}/${st.size}`, 'Content-Length': end - start + 1 });
        fs.createReadStream(file, { start, end }).pipe(res);
      } else {
        res.writeHead(200, { ...head, 'Content-Length': st.size });
        fs.createReadStream(file).pipe(res);
      }
    });
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}/` })));
}
