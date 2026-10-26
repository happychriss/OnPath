// Preview host only: binaries are published as base64 text, one file `<name>.b64.txt` or, when bigger than the host's
// per-file limit, numbered pieces `<name>.b64.0.txt`, `.1.txt`, … (each a whole number of base64 quads).
// Shared by the page (packed.ts) and the engine worker.

export function decodeBase64(text: string): Uint8Array {
  const bin = atob(text.replace(/\s+/g, ''));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** The whole file, or null when neither the single file nor piece 0 exists. */
export async function fetchPacked(url: string): Promise<Uint8Array | null> {
  const one = await fetch(url + '.b64.txt');
  if (one.ok) return decodeBase64(await one.text());
  const parts: Uint8Array[] = [];
  for (let i = 0; ; i++) {
    const r = await fetch(`${url}.b64.${i}.txt`);
    if (!r.ok) break;
    parts.push(decodeBase64(await r.text()));
  }
  if (!parts.length) return null;
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}
