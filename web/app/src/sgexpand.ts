// A round-trip card's text block (the app's round-trip cards, variant A, F-PLAN-29 – owner 2026-11-28): name, numbers and
// description in one line each, cut with „⌄ mehr“ where they do not fit; a click on the chosen card's text shows the whole
// text and „⌃ weniger“ closes it again. One card open at a time; a new choice or a redraw closes it.

/** The lines that may be cut (one line each while closed). */
const LINES = '.sgname, .sgstats, .sglt';

/** [el] has a line that does not fit (scrollWidth > clientWidth) – or is open (then it was cut). */
export function isCut(el: HTMLElement): boolean {
  if (el.classList.contains('open')) return true;
  return [...el.querySelectorAll<HTMLElement>(LINES)].some((x) => x.scrollWidth > x.clientWidth + 1);
}

/** The open state of a row of cards; [more] / [less] are the marks' words („mehr“ / „weniger“), [moreLabel] the button's name. */
export function expander(cards: () => HTMLElement[], words: { more: string; less: string; moreLabel: string }) {
  let open = -1;
  const draw = () => cards().forEach((el, k) => {
    const on = k === open;
    el.classList.toggle('open', on);
    const cut = on || isCut(el);
    el.classList.toggle('cut', cut);
    const m = el.querySelector<HTMLButtonElement>('.sgmore');
    if (!m) return;
    m.hidden = !cut;
    m.textContent = on ? `⌃ ${words.less}` : `⌄ ${words.more}`;
    m.setAttribute('aria-expanded', String(on));
    m.setAttribute('aria-label', on ? words.less : words.moreLabel);
  });
  return {
    get open() { return open; },
    /** Measure again (after layout / a resize): which cards show „⌄ mehr“. */
    measure: draw,
    /** Open card [i] (−1 = none). */
    set(i: number) { open = i; draw(); },
    toggle(i: number) { open = open === i ? -1 : i; draw(); },
  };
}
