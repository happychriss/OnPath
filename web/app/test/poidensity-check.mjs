// The density limit for city POIs (src/poidensity.ts) against the app's own cases – app/src/test/…/cells/
// PoiDensityTest.kt + PoiTilesTest.denseCityPoisCarryTheirFirstZoom (owner 2026-11-13, central London). Plain Node:
// node test/poidensity-check.mjs [pois.geojson …] – with files, also how many POIs each cell holds back and how long it takes.
import fs from 'node:fs';
const D = await import('../src/poidensity.ts');
let bad = 0;
const ok = (cond, what) => { console.log((cond ? 'ok   ' : 'FAIL ') + what); if (!cond) bad++; };
const lat = 51.5136, lon = -0.1365; // Soho, London
/** [n] POIs of [kind] packed into ~40 m (one z16 tile is ~300 m wide here), pr rising with the index. */
const cluster = (n, kind, named = true, prBase = 0) => Array.from({ length: n }, (_, i) =>
  ({ mx: D.mercX(lon + (i % 10) * 0.00005), my: D.mercY(lat + Math.floor(i / 10) * 0.00005), kind, named, pr: prBase + i }));

{ // an Alpine village: every POI at its kind's zoom, as before
  const pts = [...cluster(10, 'restaurant'), ...cluster(6, 'cafe'), ...cluster(5, 'bar'), ...cluster(3, 'shop'), ...cluster(4, 'hut')];
  const z = D.firstZooms(pts);
  ok(pts.every((p, i) => z[i] === (D.KINDS[p.kind] ?? 0)), 'village keeps every POI at its kind\'s zoom');
}
{ // a big city: the 24 most notable from z12, the rest from z17
  const pts = cluster(200, 'restaurant');
  const z = D.firstZooms(pts);
  const top = new Set(pts.map((_, i) => i).sort((a, b) => pts[b].pr - pts[a].pr).slice(0, D.CAP));
  ok(pts.every((_, i) => z[i] === (top.has(i) ? 12 : D.ALL_FROM)), 'big city: the most notable first, everything from z17');
}
{ // named before unnamed, other kinds never limited
  const unnamed = cluster(30, 'cafe', false, 40_000), named = cluster(30, 'cafe', true);
  const others = [...cluster(50, 'toilets'), ...cluster(50, 'hut'), ...cluster(50, 'bus'), ...cluster(50, 'water')];
  const pts = [...unnamed, ...named, ...others];
  const z = D.firstZooms(pts);
  ok(pts.filter((p, i) => p.named && p.kind === 'cafe' && z[i] === 12).length === D.CAP, '24 of the 30 named win although every unnamed one has a higher "pr"');
  ok(unnamed.every((_, i) => z[i] === D.ALL_FROM), 'the unnamed ones wait for z17');
  ok(others.every((_, j) => z[unnamed.length + named.length + j] === 0), 'toilets, huts, bus, water never limited');
}
{ // kinds count only from the zoom they are drawn
  const rest = cluster(20, 'restaurant', true, 10_000), shops = cluster(30, 'shop');
  const z = D.firstZooms([...rest, ...shops]);
  ok(rest.every((_, i) => z[i] === 12), 'restaurants (higher pr) keep z12');
  ok(shops.filter((_, i) => z[rest.length + i] === 15).length === 4 && shops.filter((_, i) => z[rest.length + i] === D.ALL_FROM).length === 26,
    '4 shops fit beside the 20 restaurants, the other 26 wait for z17');
}
{ // never pops in and out while zooming
  const pts = Array.from({ length: 400 }, (_, i) => ({ mx: D.mercX(lon + (i % 20) * 0.0006), my: D.mercY(lat + Math.floor(i / 20) * 0.0004),
    kind: 'restaurant', named: true, pr: (i * 7919) % 1000 }));
  const z = D.firstZooms(pts);
  let fine = true;
  for (let zoom = 12; zoom <= 16; zoom++) {
    const n = 2 ** zoom, per = new Map();
    pts.forEach((p, i) => { if (z[i] <= zoom) { const k = `${Math.floor(p.mx * n)}/${Math.floor(p.my * n)}`; per.set(k, (per.get(k) ?? 0) + 1); } });
    for (const c of per.values()) if (c > D.CAP) fine = false;
  }
  ok(fine && z.some((v) => v === D.ALL_FROM) && z.some((v) => v === 12), 'at most 24 per tile at every zoom, some limited, some early');
}
{ // on GeoJSON features (PoiTiles.denseCityPoisCarryTheirFirstZoom): "dz" only on the ones drawn later; the hut never
  const feats = Array.from({ length: 60 }, (_, i) => ({ type: 'Feature', geometry: { type: 'Point', coordinates: [lon + (i % 10) * 0.00005, lat + Math.floor(i / 10) * 0.00005] },
    properties: { id: `r${i}`, kind: 'restaurant', name: `R${i}`, pr: i } }));
  feats.push({ type: 'Feature', geometry: { type: 'Point', coordinates: [lon, lat] }, properties: { id: 'h', kind: 'hut', name: 'Hütte' } });
  const n = D.applyDensity(feats);
  ok(n === 60 - D.CAP && feats.every((f, i) => i === 60 ? !('dz' in f.properties) : (i >= 60 - D.CAP ? !('dz' in f.properties) : f.properties.dz === D.ALL_FROM)),
    `features: "dz" = 17 on the 36 later ones, none on the top 24 and the hut (${n})`);
}
for (const file of process.argv.slice(2)) {
  const fc = JSON.parse(fs.readFileSync(file, 'utf8'));
  const t0 = performance.now();
  const n = D.applyDensity(fc.features);
  const limited = fc.features.filter((f) => f.properties?.kind in D.KINDS).length;
  console.log(`${file}: ${fc.features.length} POIs, ${limited} restaurants / cafés / bars / snack bars / shops, ${n} drawn only from z17 – ${Math.round(performance.now() - t0)} ms`);
}
process.exit(bad ? 1 : 0);
