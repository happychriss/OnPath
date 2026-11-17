// A multi-day tour beside the plan's points (docs/design/multi-day-tours.md §2): the number of days, the limits per day,
// the night marks by point index (Übernachtung / Hotel) and the description. The engine (WebDays.kt, the app's shared
// PlanDays + PlanCode) owns every rule; the page keeps this object and hands it over as a small text (tourText).

export interface Hotel { name: string | null; address: string | null; link: string | null }
export interface Night { hotel: Hotel | null }
export interface Tour {
  days: number;
  limits: { timeMin: number | null; ascentM: number | null };
  /** By plan point index (as text, the JSON's keys). */
  nights: Record<string, Night>;
  description: string | null;
}

const esc = (s: string | null | undefined) => (s ?? '').replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/\t/g, '\\t');

/** WebDays.parseTour's text: `d N`, `l minutes ascent`, `n index O|H name address link`, `t description`. */
export function tourText(t: Tour | null | undefined): string {
  if (!t) return '';
  const out = [`d\t${t.days}`, `l\t${t.limits.timeMin ?? 0}\t${t.limits.ascentM ?? 0}`];
  for (const [i, n] of Object.entries(t.nights)) {
    out.push(n.hotel ? `n\t${i}\tH\t${esc(n.hotel.name)}\t${esc(n.hotel.address)}\t${esc(n.hotel.link)}` : `n\t${i}\tO`);
  }
  if (t.description) out.push(`t\t${esc(t.description)}`);
  return out.join('\n');
}
