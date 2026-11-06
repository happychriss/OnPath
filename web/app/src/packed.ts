// Preview build only (VITE_PACKED=1): the private claude.ai preview host serves only common web file types, so the
// binary files (PMTiles areas, glyph PBFs, the rd5) are published as base64 text (<name>.b64.txt) and decoded here.
// The normal build reads the files directly (HTTP range); nothing else changes.
import { PMTiles, type Protocol, type Source, type RangeResponse } from 'pmtiles';
import * as maplibregl from 'maplibre-gl';

export const PACKED = import.meta.env.VITE_PACKED === '1';

import { fetchPacked as fetchMaybe } from './b64';

async function fetchPacked(url: string): Promise<Uint8Array> {
  const b = await fetchMaybe(url);
  if (!b) throw new Error(`${url}: not on the preview host`);
  return b;
}

/** A whole PMTiles file held in memory, as pmtiles' Source. */
export class MemorySource implements Source {
  private bytes: Promise<Uint8Array>;
  constructor(private url: string) { this.bytes = fetchPacked(url); }
  getKey() { return this.url; }
  async getBytes(offset: number, length: number): Promise<RangeResponse> {
    const b = await this.bytes;
    return { data: b.slice(offset, offset + length).buffer };
  }
}

/** Registers the packed area files and the glyph protocol; returns whether a packed file exists. */
export async function usePacked(protocol: Protocol, url: string): Promise<boolean> {
  const ok = await fetch(url + '.b64.txt', { method: 'HEAD' }).then((r) => r.ok).catch(() => false);
  if (ok) protocol.add(new PMTiles(new MemorySource(url)));
  return ok;
}

let glyphsAdded = false;
/** Glyph URL template for the style: packed://… fetched and decoded by this protocol. */
export function packedGlyphs(base: string): string {
  if (!glyphsAdded) {
    maplibregl.addProtocol('packed', async (params) => ({ data: (await fetchPacked(params.url.replace('packed://', ''))).buffer }));
    glyphsAdded = true;
  }
  return 'packed://' + base + 'fonts/{fontstack}/{range}.pbf';
}
