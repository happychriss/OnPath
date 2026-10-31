# Handoff: island retry without reflection – Köln Dom routes in the browser like in the app

**Why.** Owner rule (`app = web`): same points, same route. A point that BRouter snaps onto an "island" (a way with
no connection to the rest of the network, e.g. a footway inside Köln cathedral) is snapped again to the next way by
`BRouterEngine.search`. That needs BRouter's island list `RoutingEngine.islandNodePairs` – read (BRouter's own retry
count) and written (the ways to skip) before matching. The field is `private` upstream, so the app used reflection;
TeaVM has none, so the browser got "no path found" and drew a straight line. Web parity run: Köln Dom → Neumarkt
(HIKE T3) app 1334 m, web 959 m straight (`web/test/plans.json` `knownDiff`).

## Main side (done)

- **One change to BRouter, in the jar:** `tools/routing/patch_brouter_jar.py` clears the `private` flag of
  `islandNodePairs` in brouter-core's `RoutingEngine.class` (package-private now). One byte of the class file
  (access_flags 0x0002 → 0x0000); no byte code, no other class, no other jar entry changes (checked entry by entry
  against the old jar). `tools/routing/build_brouter_jars.sh` runs it after every build, then `--check`.
- **Jars renamed** `brouter-*-1.7.11-beta.jar` → `brouter-*-1.7.11-beta-onpath1.jar` (a modified BRouter build says so;
  MIT). Only brouter-core's content changed; the other four are the same bytes under a new name.
- **`btools/router/OnPathHints.java`** (BRouter's package, already compiled by the web engine): new
  `islandRetries(engine)` (freeze count, -1 unknown) and `skipOnSnap(engine, long[][] ways)` (add + freeze, false = not
  done). `BRouterEngine.kt` calls them; **no reflection left** in either file – `SharedCoreRuleTest.webShared` now
  covers `routing/BRouterEngine.kt` and `OnPathHints.java` (and the reflection regex knows `getDeclared…` /
  `isAccessible` / `setAccessible` / `.getClass()`). `OnPathHintsTest.islandListWithoutReflection` reads and writes
  the list on a fresh engine.
- **App routes unchanged** on the JVM: `IslandTest`, `SnapTest`, `StopSkipJvmTest`, `SpurStopJvmTest` give the same
  output as before the change (diffed); Köln Dom → Neumarkt HIKE = 1334 m.

## Routing fingerprint changes (correct)

`RoutingFingerprint.BROUTER_VERSION` = `"1.7.11-beta-onpath1"` (`RoutingFingerprintTest` ties it to the jar names), so
every fingerprint `r1-…` changes once. That is right: the routing the web runs changed (island points now route). The
app's routes did not change, but plans saved before carry the old fingerprint, so the quiet "planned with other
routing" line may show once for plans saved by the other side until they are saved again. `PLANNER_VERSION` stays 1.

## Web steps (web-spike, after merging main)

1. Nothing in `web/engine/build.gradle.kts`: it takes every `app/libs/*.jar` and already compiles `OnPathHints.java`.
2. `node web/parity/check.mjs --update`: the five jar paths are new shared files (the old names are gone) – the check
   fails until updated.
3. `web/test/plans.json`: remove the `knownDiff` of "Köln Dom → Neumarkt (island point)" – it is a normal gate case
   now; expected the app's 1334 m, Dom snapped about 40 m away (50.941660, 6.958356).
4. Rebuild the engine; the Diagnostics line reads `BRouter 1.7.11-beta-onpath1`.
5. `docs/design/web-planner.md`: drop the "needs a BRouter patch" note for islands.
