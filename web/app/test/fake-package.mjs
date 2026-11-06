// A tiny region package for the browser tests (map-cells.md §20): cells/f2/7/<x>/<y>/current.json and
// <version>/hillshade.pmtiles with one solid-colour PNG for every tile z7–9 under the z7 tile – same layout as the R2
// bucket. No base / overlays files (404 → empty tiles), so it only proves where the page reads and draws a package.
// writeFakeCell: a z10 cell (map-cells.md §19) the same way – base / overlays / contours with one vector tile (a water
// polygon over the whole tile) for every tile z10–14, terrain a flat Terrarium PNG, an empty pois.geojson.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { zxyToTileId } from 'pmtiles';

/** Writes the package of z7 tile [x],[y] under [root]; [rgb] = its relief colour. Returns its box [w, s, e, n]. */
export function writeFakePackage(root, x, y, rgb = [255, 0, 255], version = 'test-s1') {
  const dir = path.join(root, 'cells/f2/7', String(x), String(y));
  fs.mkdirSync(path.join(dir, version), { recursive: true });
  fs.writeFileSync(path.join(dir, 'current.json'), JSON.stringify({ version }));
  fs.writeFileSync(path.join(dir, version, 'hillshade.pmtiles'), pmtiles(x, y, png(rgb)));
  return [lon(x), lat(y + 1), lon(x + 1), lat(y)];
}

/** Writes version [version] of the z10 cell [x],[y] under [root] (current.json points at it when [current]). */
export function writeFakeCell(root, x, y, version = 'test-s1', current = true) {
  const dir = path.join(root, 'cells/f2/10', String(x), String(y));
  fs.mkdirSync(path.join(dir, version), { recursive: true });
  if (current) fs.writeFileSync(path.join(dir, 'current.json'), JSON.stringify({ version }));
  const water = mvt('water');
  for (const l of ['base', 'overlays', 'contours']) fs.writeFileSync(path.join(dir, version, l + '.pmtiles'), pmtiles(x, y, water, 10, 14, 1));
  fs.writeFileSync(path.join(dir, version, 'terrain.pmtiles'), pmtiles(x, y, png([128, 0, 0]), 10, 13, 2));
  fs.writeFileSync(path.join(dir, version, 'pois.geojson'), JSON.stringify({ type: 'FeatureCollection', features: [] }));
}

const lon = (x, z = 7) => (x / 2 ** z) * 360 - 180;
const lat = (y, z = 7) => (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / 2 ** z))) * 180) / Math.PI;

/** A Mapbox vector tile with one layer [name] holding one polygon over the whole tile (no properties). */
function mvt(name) {
  const v = (n, out) => { while (n >= 128) { out.push((n % 128) | 128); n = Math.floor(n / 128); } out.push(n); return out; };
  const zz = (n) => (n << 1) ^ (n >> 31);
  const geo = [9, 0, 0, 26, zz(4096), 0, 0, zz(4096), zz(-4096), 0, 15]; // MoveTo, LineTo ×3, ClosePath (clockwise)
  const g = geo.reduce((o, n) => v(n, o), []);
  const feature = [0x18, 3, 0x22, ...v(g.length, []), ...g]; // type POLYGON, packed geometry
  const nm = [...Buffer.from(name)];
  const layer = [0x78, 2, 0x0a, ...v(nm.length, []), ...nm, 0x12, ...v(feature.length, []), ...feature, 0x28, ...v(4096, [])];
  return Buffer.from([0x1a, ...v(layer.length, []), ...layer]);
}

/** PMTiles v3, uncompressed, every tile [z0]–[z1] under (x, y) at zoom [z0] pointing at the same bytes; [type] 1 MVT, 2 PNG. */
function pmtiles(x, y, tile, z0 = 7, z1 = 9, type = 2) {
  const ids = [];
  for (let z = z0; z <= z1; z++) {
    const k = 2 ** (z - z0);
    for (let i = 0; i < k; i++) for (let j = 0; j < k; j++) ids.push(zxyToTileId(z, x * k + i, y * k + j));
  }
  ids.sort((a, b) => a - b);
  const entries = []; // run-length: consecutive ids share one entry
  for (const id of ids) {
    const last = entries.at(-1);
    if (last && last.id + last.run === id) last.run++;
    else entries.push({ id, run: 1 });
  }
  const dir = [];
  const v = (n) => { while (n >= 128) { dir.push((n % 128) | 128); n = Math.floor(n / 128); } dir.push(n); };
  v(entries.length);
  let prev = 0;
  for (const e of entries) { v(e.id - prev); prev = e.id; }
  for (const e of entries) v(e.run);
  for (const e of entries) v(tile.length);
  for (let i = 0; i < entries.length; i++) v(1); // every entry at offset 0 (not right after the previous one) → 0 + 1
  const root = Buffer.from(dir);
  const meta = Buffer.from('{}');
  const h = Buffer.alloc(127);
  h.write('PMTiles', 0, 'ascii'); h[7] = 3;
  const u64 = (at, n) => h.writeBigUInt64LE(BigInt(n), at);
  u64(8, 127); u64(16, root.length); u64(24, 127 + root.length); u64(32, meta.length);
  u64(40, 127 + root.length + meta.length); u64(48, 0);
  u64(56, 127 + root.length + meta.length); u64(64, tile.length);
  u64(72, ids.length); u64(80, entries.length); u64(88, 1);
  h[96] = 0; h[97] = 1; h[98] = 1; h[99] = type; h[100] = z0; h[101] = z1;
  const [w, s, e, n] = [lon(x, z0), lat(y + 1, z0), lon(x + 1, z0), lat(y, z0)];
  h.writeInt32LE(Math.round(w * 1e7), 102); h.writeInt32LE(Math.round(s * 1e7), 106);
  h.writeInt32LE(Math.round(e * 1e7), 110); h.writeInt32LE(Math.round(n * 1e7), 114);
  h[118] = z0 + 1; h.writeInt32LE(Math.round(((w + e) / 2) * 1e7), 119); h.writeInt32LE(Math.round(((s + n) / 2) * 1e7), 123);
  return Buffer.concat([h, root, meta, tile]);
}

/** A 256 px solid RGB PNG. */
function png([r, g, b]) {
  const row = Buffer.alloc(1 + 256 * 3);
  for (let i = 0; i < 256; i++) { row[1 + i * 3] = r; row[2 + i * 3] = g; row[3 + i * 3] = b; }
  const raw = Buffer.concat(Array.from({ length: 256 }, () => row));
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(256, 0); ihdr.writeUInt32BE(256, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

function crc32(buf) {
  let c = ~0;
  for (const byte of buf) { c ^= byte; for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1; }
  return ~c >>> 0;
}
