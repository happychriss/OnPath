// Cell math (docs/design/map-cells.md §2) – same formulas as tools/cells/cells.py, same test values (Meran).

export const CELL_Z = 10;

export function tileLon(x, z) {
  return (x / 2 ** z) * 360 - 180;
}

export function tileLat(y, z) {
  return (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / 2 ** z))) * 180) / Math.PI;
}

/** [west, south, east, north] of cell z/x/y. */
export function cellBbox(z, x, y) {
  return [tileLon(x, z), tileLat(y + 1, z), tileLon(x + 1, z), tileLat(y, z)];
}

/** "10-543-361" or "10/543/361" → {z, x, y, id} or null. */
export function parseCellId(s) {
  const m = /^(\d{1,2})[-/](\d{1,4})[-/](\d{1,4})$/.exec(String(s ?? "").trim());
  if (!m) return null;
  const [z, x, y] = m.slice(1).map(Number);
  if (z !== CELL_Z || x >= 2 ** z || y >= 2 ** z) return null;
  return { z, x, y, id: `${z}-${x}-${y}` };
}

/** z7 block of a cell (8 × 8 cells, ~250 km): the doorman's guess whether a source extract is already cached
 *  there (map-hosting.md §Source data) – used only for the ETA. */
export const blockOf = (c) => `${c.x >> 3}-${c.y >> 3}`;
