// Cell math (docs/design/map-cells.md §2) – same formulas as tools/cells/cells.py, same test values (Meran).
import sources from "../../sources.json" with { type: "json" };

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

const inside = (b, r) => r[0] <= b[0] && r[1] <= b[1] && b[2] <= r[2] && b[3] <= r[3];

/** Source extract that covers the whole cell (tools/cloud/sources.json, same rule as job_build.py), or null. */
export function sourceFor(cell, list = sources) {
  const b = cellBbox(cell.z, cell.x, cell.y);
  return (list.sources || []).find((s) => (s.cover || []).some((r) => inside(b, r))) || null;
}
