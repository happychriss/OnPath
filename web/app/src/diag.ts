// The page's problem log and diagnostics (docs/design/web-planner.md §17; the app: debug/Problems + Settings →
// Diagnostics export). Every recovered failure goes here once (deduped by area + message, counted, first / last time),
// also to the console once. Long main-thread blocks (> 1 s, Chrome's long-task timing) are logged too – a frozen
// page leaves a trace. The owner sends it from the live page: `window.__diag()` in the console (copies the report to
// the clipboard and returns it), or `?debug` in the address → a small "Copy diagnostics" button on the map.
// English only (CLAUDE.md: diagnostics stay English). No plan, no hash, no position beyond the rounded map view.

export interface Problem { area: string; message: string; detail: string; count: number; first: string; last: string; level: 'warn' | 'error' }

const MAX = 100;
/** The ?debug button – diagnostics stay English (CLAUDE.md), like the app's Developer tools. */
const DEBUG_TEXT = { copy: 'Copy diagnostics', copied: 'Copied – paste it into a message', failed: 'Copy failed – run __diag() in the console' };
const problems = new Map<string, Problem>();
/** Extra sections for the report (map view, cells), registered by main.ts. */
const sections: (() => string)[] = [];

/** Logs a recovered failure once per area + message (later repeats only count). */
export function problem(area: string, message: string, e?: unknown, level: 'warn' | 'error' = 'warn') {
  const k = `${area}|${message}`;
  const now = new Date().toISOString();
  const p = problems.get(k);
  if (p) { p.count++; p.last = now; return; }
  if (problems.size >= MAX) problems.delete(problems.keys().next().value!);
  const detail = e == null ? '' : e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  problems.set(k, { area, message, detail: detail.slice(0, 300), count: 1, first: now, last: now, level });
  console.warn(`[${area}] ${message}`, e ?? ''); // warn, not error: the page recovered (the smokes fail on console errors)
}

export function problemList(): Problem[] { return [...problems.values()]; }

export function addDiagSection(f: () => string) { sections.push(f); }

/** The report: page, browser, problems, the registered sections. */
export function diagText(): string {
  const out = [
    `OnPath planner diagnostics ${new Date().toISOString()}`,
    `page: ${location.origin}${location.pathname}${location.search}`,
    `browser: ${navigator.userAgent}`,
    `screen: ${innerWidth}x${innerHeight} @${devicePixelRatio}, visible: ${document.visibilityState}, online: ${navigator.onLine}`,
  ];
  for (const f of sections) { try { out.push(f()); } catch (e) { out.push(`(section failed: ${String(e)})`); } }
  out.push(`problems (${problems.size}):`);
  for (const p of problems.values()) out.push(`  ${p.level.toUpperCase()} [${p.area}] ${p.message} ×${p.count} (${p.first} … ${p.last})${p.detail ? ' – ' + p.detail : ''}`);
  return out.join('\n');
}

async function copy(text: string): Promise<boolean> {
  try { await navigator.clipboard.writeText(text); return true; } catch (e) {
    console.info('clipboard not available – the report is returned instead', e); // expected: no focus / permission
    return false;
  }
}

/** Starts the long-task watch, global error capture, `window.__diag()` and the `?debug` button. */
export function installDiag(debug: boolean) {
  addEventListener('error', (e) => problem('page', 'uncaught error', e.error ?? e.message, 'error'));
  addEventListener('unhandledrejection', (e) => problem('page', 'unhandled rejection', e.reason, 'error'));
  try {
    new PerformanceObserver((l) => {
      for (const t of l.getEntries()) if (t.duration > 1000) problem('page', `main thread blocked ${(t.duration / 1000).toFixed(1)} s`, `at ${Math.round(t.startTime)} ms`, 'error');
    }).observe({ type: 'longtask', buffered: true });
  } catch { /* expected: a browser without long-task timing (Firefox, Safari) */ }
  (window as unknown as { __diag: () => string }).__diag = () => { const t = diagText(); void copy(t); return t; };
  if (!debug) return;
  const b = document.createElement('button');
  b.id = 'diagCopy';
  b.textContent = DEBUG_TEXT.copy;
  b.style.cssText = 'position:fixed;z-index:50;right:12px;bottom:96px;padding:6px 10px;border-radius:10px;border:1px solid #888;background:#fff;color:#222;font:12px sans-serif';
  b.onclick = async () => { b.textContent = (await copy(diagText())) ? DEBUG_TEXT.copied : DEBUG_TEXT.failed; };
  document.body.append(b);
}
