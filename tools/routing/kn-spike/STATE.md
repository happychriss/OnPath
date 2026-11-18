# kn-spike – state when stopped (2026-11-18, [umbau]'s helper; [umbau-hp] continues phase 5a)

About one third done: reader, codec and interpreter ported; nothing has routed, no numbers.
- Build: standalone KMP project, Kotlin 2.2.10, targets `jvm` + `linuxX64`, no dependencies; `-Pkn.opts="…"` for native options.
- Ported (6,928 Kotlin lines): `btools.util` + `btools.codec` (decode side; compiled on both targets), `btools.expressions`
  (full interpreter, `.brf` parser, `lookups.dat`) and `btools.mapaccess` (PhysicalFile, OsmFile, NodesCache, DirectWeaver,
  node/link model, WaypointMatcherImpl, turn restrictions) – these two and the merged tree were NEVER compiled.
  Known seams: `ByteDataReader.ab`, `faid`, `fapos`, `TagValueWrapper.data` are non-null in codec, mapaccess uses `ab!!`;
  `CacheNode` needs `hashCode()` / `equals()` for `LruMap`.
- Not started: `btools.router` (~3,000 Java lines: RoutingEngine from→to, OsmPath, StdPath, OsmPathElement, OsmTrack incl.
  buildMap, RoutingContext without nogos, StdModel, OsmNodeNamed, MessageData, ProfileCache; two passes → CompactLongMap /
  FrozenLongMap), the Java reference runner, the 10 pairs, fixtures.
- BRouter sources: github.com/abrensch/brouter commit `29898106` (1.7.11-beta, no tag); 109 of 115 classes compile
  byte-identical to `app/libs` (RoutingEngine differs by the onpath1 patch).
- Planned: tile `E10_N45.rd5`, profile `touring.brf`, the app's keyValues from `BRouterEngine.kt`, `turnInstructionMode=0`.
- Risks seen: `CheapRuler` cos table (libm vs Math.cos, last bit); `OsmNodesMap.cleanupPeninsulas` recursion (Java swallows
  StackOverflowError); HashMap order in `collectOutreachers`; size: ~9,500–10,000 lines for what a route needs.
