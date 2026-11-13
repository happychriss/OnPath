// The one message box, as the app's ui/MessageCard (docs/design/ui-style.md "The box: MessageCard"): a white card under
// the top bar with a 4 px coloured left edge – FRAGE (a question: blue edge, two verb buttons, until answered), HINWEIS
// (must know: one button – OK or the one way out; grey edge, amber ⚠ attention, red ⚠ error; never goes by itself).
// One box at a time (the app's Messages.pick: a question first, then the oldest); the others wait and come next.
// ANZEIGE (a state shown in place) is no box – lines in the sheet, the map strip.

export type MessageKind = 'frage' | 'hinweis' | 'hinweis-warn' | 'hinweis-error';
export interface MessageButton { label: string; act: () => void }
export interface Message {
  /** One message per id: posting the same id again replaces it (e.g. "new version" asked on every focus). */
  id: string;
  kind: MessageKind;
  title: string;
  detail?: string;
  /** Last = the primary one. A HINWEIS has exactly one, a FRAGE two. */
  buttons: MessageButton[];
  /** A wait the user started (the route being calculated): a spinner before the title; its one button is the way out. */
  busy?: boolean;
  /** The primary button's colour by what it does (the app's ButtonTone, colour rule 2026-11-06): 'edit' = it plans
   * (blue), else walnut. The web never goes to a navigation, so there is no green. */
  tone?: 'edit' | 'neutral';
  /** Where the box's centre goes (page pixels) instead of 12 px under the top bar – e.g. the middle of the free map. */
  at?: () => { x: number; y: number };
}

const waiting: Message[] = [];
let host: HTMLElement | null = null;

/** The app's Messages.pick: a question (FRAGE) wins; else the first one posted. */
export function pick(ms: Message[]): Message | null {
  return ms.find((m) => m.kind === 'frage') ?? ms[0] ?? null;
}

/** Shows [m] (or keeps it waiting behind the box on screen). */
export function post(m: Message) {
  const i = waiting.findIndex((x) => x.id === m.id);
  if (i >= 0) waiting[i] = m; else waiting.push(m);
  draw();
}

/** Takes message [id] away (answered, or its state is gone). */
export function dismiss(id: string) {
  const i = waiting.findIndex((x) => x.id === id);
  if (i >= 0) waiting.splice(i, 1);
  draw();
}

/** Called after every change with the message on screen (onewait.ts: the route's wait box is a wait). */
let listener: (m: Message | null) => void = () => {};
export function onShown(f: (m: Message | null) => void) { listener = f; f(pick(waiting)); }

/** The message on screen now (for the browser tests). */
export function shown(): Message | null { return pick(waiting); }

function draw() {
  if (!host) {
    host = document.createElement('div');
    host.id = 'msgcards';
    document.body.append(host);
  }
  const m = pick(waiting);
  host.replaceChildren();
  host.hidden = !m;
  listener(m);
  if (!m) return;
  // 12 px under the top element (the app's MessagePlacement): the search bar over the map, else the header
  const top = document.querySelector('.topbar') ?? document.getElementById('header');
  const at = m.at?.();
  host.classList.toggle('at', !!at);
  host.style.left = at ? `${Math.round(at.x)}px` : '';
  host.style.top = at ? `${Math.round(at.y)}px` : `${Math.round((top?.getBoundingClientRect().bottom ?? 56) + 12)}px`;
  const card = document.createElement('div');
  card.className = `msgcard ${m.kind}${m.busy ? ' busy' : ''}`;
  card.dataset.id = m.id;
  card.setAttribute('role', m.kind === 'frage' ? 'alertdialog' : 'alert');
  const text = document.createElement('div');
  text.className = 'msgtext';
  const title = document.createElement('div');
  title.className = 'msgtitle';
  if (m.kind === 'hinweis-warn' || m.kind === 'hinweis-error') title.append(Object.assign(document.createElement('span'), { className: 'msgwarn', textContent: '⚠ ' }));
  if (m.busy) title.append(Object.assign(document.createElement('span'), { className: 'spin msgspin', ariaHidden: 'true' }));
  title.append(m.title);
  text.append(title);
  if (m.detail) text.append(Object.assign(document.createElement('div'), { className: 'msgdetail', textContent: m.detail }));
  const row = document.createElement('div');
  row.className = 'msgbuttons';
  m.buttons.forEach((b, k) => {
    const btn = document.createElement('button');
    btn.className = k === m.buttons.length - 1 ? `msgbtn primary${m.tone === 'edit' ? ' edit' : ''}` : `msgbtn${m.tone === 'edit' ? ' edit' : ''}`;
    btn.textContent = b.label;
    btn.onclick = () => { dismiss(m.id); b.act(); };
    row.append(btn);
  });
  card.append(text, row);
  card.classList.toggle('one', m.buttons.length === 1); // one button: beside the text (the app's layout)
  host.append(card);
}
