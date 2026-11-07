// Preview host only: binaries are published as base64 text, one file `<name>.b64.txt` or, when bigger than the host's
// per-file limit, numbered pieces `<name>.b64.0.txt`, `.1.txt`, … (each a whole number of base64 quads).
// Shared by the page (packed.ts) and the engine worker (no engine code here: a change rebuilds the page only).

/** Native decoder where the browser has one (Uint8Array.fromBase64), else atob – the native one is several times faster. */
export function decodeBase64(text: string): Uint8Array {
  const native = (Uint8Array as unknown as { fromBase64?: (s: string, o?: object) => Uint8Array }).fromBase64;
  if (native) return native(text.trim(), { lastChunkHandling: 'loose' });
  const bin = atob(text.replace(/\s+/g, ''));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function piece(url: string): Promise<Uint8Array | null> {
  const r = await fetch(url);
  return r.ok ? decodeBase64(await r.text()) : null;
}

/** How many numbered pieces are fetched at the same time (the count is not known before). */
const PARALLEL = 4;

/** The whole file, or null when neither the single file nor piece 0 exists. */
export async function fetchPacked(url: string): Promise<Uint8Array | null> {
  const one = await piece(url + '.b64.txt');
  if (one) return one;
  const parts: Uint8Array[] = [];
  for (let i = 0; ; i += PARALLEL) {
    const batch = await Promise.all(Array.from({ length: PARALLEL }, (_, k) => piece(`${url}.b64.${i + k}.txt`)));
    const end = batch.indexOf(null);
    parts.push(...(end < 0 ? batch : batch.slice(0, end)) as Uint8Array[]);
    if (end >= 0) break;
  }
  if (!parts.length) return null;
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}
