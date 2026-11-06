// The place card belongs to its point (owner 2026-10-30: "the overlay should be at the point where I add it, like in
// the app"). Wide screen: a callout above the point – or below / right / left when there is no room – with a small tail
// to it, moving with the map. Phone width: the bottom card (CSS) and the point in the free map above it.
// Opening moves the camera so point and card are clear of the plan sheet, the top bar and the map controls.
import type * as maplibregl from 'maplibre-gl';

export type Side = 'above' | 'below' | 'right' | 'left';
export interface Box { x: number; y: number; w: number; h: number }

/** Room between the point's centre and the card's edge: the search ring's radius (13) + the tail (8) + air. */
export const GAP = 24;
const MARGIN = 8; // to the map's edge
const TAIL_END = 22; // the tail stays this far from the card's corners (rounded 18 px)

/** Where the card goes for a point at [px, py] (map pixels): the first side that fits in [w × h] and covers none of
 *  [blocked]; none fits → the side that covers least. Also the tail's position along the card's edge. */
export function placeCard(px: number, py: number, cw: number, ch: number, w: number, h: number, blocked: Box[]):
  { side: Side; x: number; y: number; tail: number } {
  const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));
  const cand = (side: Side) => {
    let x: number, y: number;
    if (side === 'above' || side === 'below') {
      x = clamp(px - cw / 2, MARGIN, w - MARGIN - cw);
      y = side === 'above' ? py - GAP - ch : py + GAP;
    } else {
      y = clamp(py - ch / 2, MARGIN, h - MARGIN - ch);
      x = side === 'right' ? px + GAP : px - GAP - cw;
    }
    const along = side === 'above' || side === 'below' ? px - x : py - y;
    const len = side === 'above' || side === 'below' ? cw : ch;
    const reach = along >= TAIL_END && along <= len - TAIL_END; // the tail can still point at the point
    const out = Math.max(0, MARGIN - x) + Math.max(0, x + cw - (w - MARGIN)) + Math.max(0, MARGIN - y) + Math.max(0, y + ch - (h - MARGIN));
    const cover = blocked.reduce((s, b) => s + Math.max(0, Math.min(x + cw, b.x + b.w) - Math.max(x, b.x)) * Math.max(0, Math.min(y + ch, b.y + b.h) - Math.max(y, b.y)), 0);
    return { side, x, y, tail: clamp(along, TAIL_END, len - TAIL_END), bad: out * Math.max(cw, ch) + cover + (reach ? 0 : 1) };
  };
  const all = (['above', 'below', 'right', 'left'] as Side[]).map(cand);
  const best = all.find((c) => c.bad === 0) ?? all.reduce((a, b) => (b.bad < a.bad ? b : a));
  return { side: best.side, x: Math.round(best.x), y: Math.round(best.y), tail: Math.round(best.tail) };
}

/** The camera offset for an easeTo / flyTo that puts the centre at [tx, ty] of a [w × h] map. Used instead of the
 *  `padding` option: maplibre keeps a move's padding for every later move, so a centre given afterwards (a search
 *  pick, the points list, the position) landed off the middle (owner 2026-10-30, the not-centred search result). */
export function offsetTo(tx: number, ty: number, w: number, h: number): [number, number] {
  return [Math.round(tx - w / 2), Math.round(ty - h / 2)];
}

/** The offset that centres a move in the room [p] leaves (the same as maplibre's `padding`, without keeping it). */
export function paddingOffset(map: maplibregl.Map, room: maplibregl.PaddingOptions): [number, number] {
  const c = map.getContainer(), p = full(room);
  return offsetTo(p.left + (c.clientWidth - p.left - p.right) / 2, p.top + (c.clientHeight - p.top - p.bottom) / 2, c.clientWidth, c.clientHeight);
}

const full = (p: maplibregl.PaddingOptions) => ({ top: p.top ?? 0, bottom: p.bottom ?? 0, left: p.left ?? 0, right: p.right ?? 0 });

export const NO_PADDING: maplibregl.PaddingOptions = { top: 0, bottom: 0, left: 0, right: 0 };

/** easeTo / flyTo options that centre the move in the room [p] leaves and clear any padding an older move left. */
export function roomFor(map: maplibregl.Map, p: maplibregl.PaddingOptions): { offset: [number, number]; padding: maplibregl.PaddingOptions } {
  return { offset: paddingOffset(map, p), padding: NO_PADDING };
}

/** Where the point should land when the card opens: wide → the card above it, both in the middle of the free room;
 *  phone → the middle of the map above the bottom card ([p] already holds the card's height). */
export function pointTarget(room: maplibregl.PaddingOptions, w: number, h: number, ch: number, wide: boolean): [number, number] {
  const p = full(room);
  const fw = Math.max(0, w - p.left - p.right), fh = Math.max(0, h - p.top - p.bottom);
  const tx = p.left + fw / 2;
  if (!wide) return [tx, p.top + fh / 2];
  const block = ch + GAP + 14; // card, gap, the lower half of the ring
  return [tx, p.top + Math.max(0, (fh - block) / 2) + ch + GAP];
}

/** Map-pixel boxes of the elements the card must not cover (inside [stage]). */
export function boxesOf(stage: HTMLElement, sel: string[]): Box[] {
  const s = stage.getBoundingClientRect();
  return sel.flatMap((q) => [...stage.querySelectorAll<HTMLElement>(q)]).filter((e) => !e.hidden && e.offsetParent !== null)
    .map((e) => { const r = e.getBoundingClientRect(); return { x: r.left - s.left, y: r.top - s.top, w: r.width, h: r.height }; })
    .filter((b) => b.w > 0 && b.h > 0);
}
