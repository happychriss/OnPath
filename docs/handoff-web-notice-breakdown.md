# Handoff: routing-data notice and way-type / surface line, shared with the browser

**Why.** Owner rule (`app = web`): the plan sheet reads the same in the browser and the app. Two pieces of the
phone's plan sheet were mixed into Android / Compose code, so the web could not compile them:

- the plain notice while routing data is missing ("Map loading – straight line for now" / "Map loads when online –
  straight line for now"), inside `data/RoutingDataLoader.kt` (java.io.File, the downloader);
- the way-type / surface rules (labels, legend order, colours, the 3 % "Other" fold, the summary line
  "Radweg 92 % · Asphalt 98 %"), inside the Compose file `ui/RouteBreakdown.kt`.

**Main side (done, no behaviour change).** Both are now their own pure files, kept so by `SharedCoreRuleTest.files`
and `webShared` (no Android, no reflection, no java.security). `RoutingDataLoader.kt` keeps the I/O, `RouteBreakdown.kt`
only draws. Tests compare the new code with a frozen copy of the old one in English and German
(`data/RoutingDataNoticeTest`, `ui/BreakdownTest`); `routing/BreakdownRulesTest` is plain JUnit for the web. Paparazzi
images unchanged.

## Files for `web/engine/build.gradle.kts`

- `appFiles` += `"data/RoutingDataNotice.kt"`, `"routing/BreakdownRules.kt"` – they need only the generated
  `com.onpath.strings` stand-in (`Res.string.*`, `Txt`, `StringResource`) and `routing/RoutingContract.kt` (`Share`),
  both already there.
- `appTests` += `"data/RoutingDataNoticeTest.kt"` (uses `BRouterEngine.NO_DATA`, in `appFiles`) and
  `"routing/BreakdownRulesTest.kt"`. Not `ui/BreakdownTest.kt` – it uses Compose `Color` to prove the old app colours.

## API

```kotlin
// data/RoutingDataNotice.kt (package com.onpath.app.data)
object RoutingDataNotice {
    enum class Kind(val res: StringResource) {          // text keys: maps_routing_loading / maps_routing_not_yet
        LOADING, NOT_YET;  val text: String }
    val LOADING: String; val NOT_YET: String             // the two texts in the current language
    fun kind(loading: Boolean): Kind
    fun text(loading: Boolean): String
    fun isNotice(s: String?): Boolean                    // is this error line one of the two notices?
    fun kindForRoute(error: String?, missing: Boolean, loading: Boolean): Kind?   // null = no notice
    fun forRoute(error: String?, missing: Boolean, loading: Boolean): String?     // notice text, else error as is
}
```
`missing` = a 1° part under the route is not there yet (web: `Rd5Parts.forCorridor(points)` minus the parts the
engine has – what the engine's "parts a plan needs" call already returns); `loading` = the page is fetching them now
(false = offline). `error` = the plan's error text: a router sentence about missing data ("No routing data…",
`BRouterEngine.NO_DATA`, or anything with ".rd5") also becomes the plain notice – the user never sees a file name.
There is no "old" or "partial" kind in the app: missing parts are the only notice (an old rd5 shows in Diagnostics /
the sync fingerprint line, not here).

```kotlin
// routing/BreakdownRules.kt (package com.onpath.app.routing)
object BreakdownRules {
    class Entry(val key: String, val label: StringResource, val argb: Long) {
        val text: String        // label in the current language
        val hex: String         // "#RRGGBB" for CSS
    }
    enum class Kind { WAY, SURFACE }
    val WAY: List<Entry>        // cycleway, path, footway, track, quiet_road, road, busy_road, steps, ferry
    val SURFACE: List<Entry>    // asphalt, paved, gravel, unpaved, ground, natural_trail, alpine, unknown
    val OTHER: Entry            // "other" / unknown keys, #D5D5D5
    fun table(kind: Kind): List<Entry>
    fun find(kind: Kind, key: String): Entry?      // null = not in the table
    fun entry(kind: Kind, key: String): Entry      // what a bar / legend draws (unknown → OTHER)
    fun summaryLine(wayTypes: List<Share>, surfaces: List<Share>): String   // "Radweg 92 % · Asphalt 98 %", "" = none
    fun foldSmall(shares: List<Share>, minFraction: Double = 0.03): List<Share>  // < 3 % → one "other"
}
```
Colours are plain ARGB (the app draws `Color(entry.argb)`); they are not `OnPathColors` tokens – the route colours
are their own palette.

## What the web then shows

1. **Routing-data notice line** in the plan sheet (ANZEIGE: a grey line in place, no box; the app's `InfoLine`, with a
   spinner while `Kind.LOADING`) while the route is drawn as a straight line because parts are missing:
   the engine's `plan` JSON gets `"notice": "LOADING" | "NOT_YET" | null` from
   `RoutingDataNotice.kindForRoute(error, missing, loading)` plus `"noticeText"` (`kind.text`), and the page shows that
   line instead of the router's error text. Same wording as the phone, EN + DE from the same string keys.
2. **Way types and surfaces** in the plan sheet: one summary line „Radweg 92 % · Asphalt 98 %“ under the title
   "Way types & surfaces" (`plan_way_types_surfaces`), tap → two thin bars (`plan_way_types`, `plan_surfaces`) with a
   legend "label distance". The engine already sends `wayTypes` / `surfaces`; add `"breakdown"` to the plan JSON:
   `{"line": summaryLine(...), "ways": [[key, text, hex, meters], …], "surfaces": [...]}`, built from
   `foldSmall(shares).map { entry(kind, it.key) }` – the page draws only (bar widths ∝ meters, at least 1).

## Checks
Engine tests (the two `appTests` above), a parity case: one plan in Meran → the same summary line on the phone and in
the browser (EN + DE), and one plan into an area without loaded parts → the same notice line.
