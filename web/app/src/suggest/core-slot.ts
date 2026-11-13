// The slot for the REAL mechanism (not built): the network search of tools/suggest-poc (variant B / C), written once
// in the shared Kotlin core and compiled into the planner's engine like the router – then the same code runs in the
// app. Only types and the plan live here; nothing in this file runs.
//
// Where it plugs in
//   shared Kotlin (later, pure – no Android):  app/src/main/java/com/onpath/app/suggest/
//       TourWish · Candidate · SuggestEngine (interface: one implementation per mechanism) · WayNet (the way network
//       read from BRouter's node cache) · LoopScore · Destinations (interface, filled from outside)
//       → listed in app/web-shared-files.txt, so web/engine compiles it (TeaVM) and the app can use it unchanged.
//   web/engine/…/WebApi.kt:                    suggestStart(wishJson) · suggestNext() · suggestCancel()
//       (pull, one candidate per call: the one engine worker stays answerable between candidates and a newer request
//        or Abbrechen stops the run – the worker cannot be interrupted inside a call)
//   web/app/src/engine/worker.ts + types.ts:   one request type 'suggest' that forwards those three calls
//   web/app/src/suggest/core.ts:               a SuggestProvider `core` that maps CoreCandidate → Candidate; one line in
//       registry.ts puts it first.
//
// What the core needs from outside (it reads no map files itself in the browser):
//   - the way network: from the routing-data parts the planner already fetches (worker.ts ensureParts) – the core asks
//     for the parts of a circle around the start (like Rd5Parts.forCorridor), the worker fetches them first;
//   - destinations (known routes with rank, trail names, lakes, protected areas): read by the PAGE from the map areas it
//     loads anyway (cells.ts, pmtiles: overlays `routes` / `trailnames`, base `water` / `park`) and handed in as
//     CoreDestinations before suggestStart – on the phone the app reads the same files;
//   - AI region tips: an optional small file per map area from the map server (region-tips.json next to the cell
//     files); handed in the same way, used only when wish.tips is on. Missing file = no tips, never an error;
//   - weights: ONE file shared with the POC (tools/suggest-poc/weights.json today) shipped with the engine.
//
// Open before it is built (measure first, Hike only): does reading BRouter's node cache work after TeaVM, how long
// does the search take in the browser, how much memory does a bike network (≈ 200 000 nodes) need. The profile copies
// with the main-road cost are separate profile files – ordinary plans keep their profiles and their routing fingerprint.
import type { TourWish } from './types';

/** What the page hands the core before a run (per map area in reach). */
export interface CoreDestinations {
  routes: { kind: 'hike' | 'bike' | 'mtb'; name: string; rank: 1 | 2 | 3 | 4; line: [number, number][] }[];
  trails: { name: string; line: [number, number][] }[];
  waters: { name: string; shore: [number, number][] }[];
  reserves: { name: string; ring: [number, number][] }[];
  views: { name: string; at: [number, number] }[];
  /** AI region tips (optional): ids of the entries above that a local would recommend, with a rank 1–3. */
  tips?: { ref: string; rank: 1 | 2 | 3 }[];
}

/** One candidate as the core returns it (JSON from WebApi.suggestNext; null = no more). */
export interface CoreCandidate {
  id: string;
  title: string;
  reasons: string[];
  hints: string[];
  /** The loop as plan points (the core already knows which few points make the router follow it). */
  points: [number, number][];
}

/** The three calls of the engine (WebApi), as the worker would forward them. */
export interface CoreSuggestApi {
  putDestinations(area: string, d: CoreDestinations): Promise<void>;
  /** The routing-data parts the run needs (the worker fetches them before start). */
  partsNeeded(wish: TourWish): Promise<string[]>;
  start(wish: TourWish): Promise<void>;
  next(): Promise<CoreCandidate | null>;
  cancel(): Promise<void>;
}
