# What of `:state` the planner can take (status 2026-11-19, for [laptop])

`state/web-ready.txt` is the list; `WebReadyRuleTest` checks it on every gate. Numbers below come from the commands at the end.

## 1. The files of `:state` by class (62 files, 8955 lines)

| Class | Files | Lines | Which |
|---|---|---|---|
| **Listed now** (you may compile them as they are) | 29 | 1730 | see §3 |
| **One step away** | 1 | 146 | `ui/plan/PlanDocument.kt`: its only blocker is the type name `SnapshotStateList` (line 7 + line 36, `points`). Step: your Compose stand-in gets `class SnapshotStateList<T> : MutableList<T>` (+ `mutableStateListOf` returning it, which it has to anyway), and the rule test allows `androidx.compose.runtime.snapshots.SnapshotStateList`. It is the key file: 7 of the 13 slices below take a `PlanDocument`. |
| **Needs a cut** (coroutines inside) | 21 | 6629 | the 13 named: `PlanEditSlice` (host `PlanEditHost` 30 members, + `PointNames` 1), `PlaceAddSlice` (`PlaceAddHost` 21, `HoldRouter` 1), `PlaceMenusSlice` (`PlaceMenusHost` 10, `PlaceLookup` 1), `PlanRecalcSlice` (`PlanRecalcHost` 10, `MapDataPort` 6, `RouteSearchProgress` 1), `PlanRouteSlice` (`PlanRouteHost` 11, `RouterPort` 5, `TimesStore` 4, `PlanRoutePrefs` 2), `RecentPlans` (`RecentPlansHost` 9), `SaveSlice` (`SaveHost` 18), `RoutesSlice` (`RoutesHost` 34), `RoutesGoSlice` (no host interface of its own), `MultiDaySlice` (`MultiDayHost` 13), `SuggestSlice` (`SuggestHost` 6, two `suspend` members), `PlanLinkFlow` (`PlanLinkHost` 11), `MapViewSlice` (`MapViewHost` 17); and 8 more: `SettingsSlice`, `sync/SyncDriver`, `sync/SyncedRoutingSlice`, `tour/ActivityCheckSlice`, `tour/TourEdits`, `tour/TourEndSlice`, `tour/TourFollow`, `tour/TourSession` (the last five are the tour recording: not a planner feature). Other slices they need (from their constructors): `PlanDocument` (PlanEdit, PlaceAdd, PlaceMenus, MultiDay, Routes, RoutesGo, Save), `PlanRunSlice` (PlanEdit, PlaceAdd, PlaceMenus – listed), `PlanEditSlice` (PlaceAdd), `PlaceAddSlice` (PlaceMenus), `RoutesState` (listed: RecentPlans, Routes, RoutesGo, Save), `RecentPlans` (Save), `SaveSlice` (Routes, RoutesGo), `PlanRouteSlice` (MultiDay, Routes, RoutesGo), `ToursStore` (MultiDay, Routes, RoutesGo – a port, unlisted for now), `TourController` (Routes, RoutesGo – tour recording). Host member counts: command in §5. |
| **Not a planner feature** (tour recording, developer tools, phone-only) | 11 | 450 | `ui/ActivityCheckUi`, `ui/EndAsk`, `ui/DemoRules`, `ui/DevToolsUnlock`, `debug/FrameStats`, `data/RoutingDataCheck`, `ui/tour/TourController`, `ui/tour/TourRecorderAdapter`, `ui/tour/TourRows` (they pass the rule test; left unlisted on purpose), and the two ports of unlisted slices `ui/routes/SaveStore`, `ui/routes/ToursStore` (list them together with `SaveSlice` / `RoutesSlice`). |

(21 + 1 + 29 + 11 = 62.)

## 2. What the rule test checks, and what it cannot see

Checks on every listed file (`state/src/test/kotlin/com/onpath/app/ui/WebReadyRuleTest.kt`):
1. the file exists; 2. imports only `com.onpath.app.*`, `com.onpath.strings.*`, `java.*`, `kotlin.*`, `org.json.*`,
`org.jetbrains.compose.resources.StringResource` / `PluralStringResource`, and the five Compose names (`mutableStateOf`, `getValue`,
`setValue`, `mutableStateListOf`, `mutableIntStateOf`); no `kotlinx.coroutines`, no other `androidx.*`;
3. no `suspend`, `Thread`, `java.util.concurrent` (but `.atomic.` is fine: core files the engine compiles use it), `synchronized(` /
`@Synchronized` / `@Volatile`, `java.io.File`, `System.currentTimeMillis()` / `System.nanoTime()` called directly (a default argument
`System::currentTimeMillis` of a clock lambda, as `RoutesState` has, is allowed: it is injectable);
4. closure: every `:state` type / function a listed file names (same package or imported) is declared in a listed file;
5. every `:core` file it names is compiled by the planner: not in `web/engine/core-excludes.txt` (empty today), not replaced by a
stand-in in `web/engine/src/main/kotlin/com/onpath/app/` – except `debug/Problems.kt`, where each `Problems.<member>` used must be
declared by the stand-in.

It cannot see: names inside string templates (`"${x.y}"`), a wildcard import of `:state` / `:core` (that fails: import by name),
two top-level declarations of one name in one package (first wins), the type behind an inferred value (`x.foo()` counts as a member
call; only extension functions match after a dot), a member of a stand-in other than `Problems` (whether your `StringResource`
has what a file calls), and whether TeaVM really compiles a file (nothing here builds the engine). The test is also not a proof that
the Compose stand-in is enough: it only allows the five names.

Also done: `:state:test` now has `web-ready.txt`, `core-excludes.txt` and the engine's stand-in folder as inputs (Gradle used to skip the test
when only the list changed).

## 3. Listed now (29 files, 1730 lines) and the ports you implement

Slices:
- `ui/routes/RoutesState.kt` (260): port `RoutesStore` (7 members: listTours, listDeleted, setDraft, setDeleted, loadSegments, deleteForGood, rename); a clock lambda `() -> Long`; two hooks it calls (`onClosed`, `onRecent`).
- `ui/routes/SaveDialog.kt` (50): none.
- `ui/plan/KeptPlacesSlice.kt` (85): `PlacesStore` (4: saved, update, backupChanged, syncChanged), `KeptPlacesHost` (2: searchMark, isCandidate).
- `ui/NoticeBoard.kt` (51): is the `Say` port itself; three lambdas (routesOpen, settingsOpen, mapShown).
- `ui/MultiDayFlow.kt` (294): `MultiDayPrefs` (5 properties), lambda `linkedId`; uses `org.json`.
- `ui/plan/PlanRunSlice.kt` (121): `PlanRunHost` (6: points, clearRoute, noCorridor, loadDeferredMaps, recalc, cancelRun) and `Cancellable` (1: cancel) for its `routeJob` handle. It no longer names `kotlinx.coroutines.Job`: the app sets the handle with `JobHandle(job)` (in `PlanRecalcSlice.kt`), you set your own. The run itself (`recalc`) is in `PlanRecalcSlice` (coroutines): you write that part as the page's own code and call `routeEvent` / `perform`.

State types: `NoticeTypes`, `ShownTrack`, `TrackThen`, `SyncNote`, `TimeDetailsUi`, `search/PlaceCardUi`, `MultiDayUi`, `SettingsPage`.
Ports (`ui/Ports.kt`: `Say` 1, `Camera` 3, `Position` 1, `Clock` 2, `Cancellable` 1; `ui/MultiDayPorts.kt`: `MultiDayPrefs` 5 properties, `EndNamer` 1).
Rules: `WholeRoute`, `LongRouteCancelCamera`, `ImportRules`, `LoadNames`, `PlanGps`, `PlanSize`, `ExpandGesture`, `MapControlsEnabled`, `MessagePlacement`, `MapDimens`, `MapSizes`, `OverviewPillPlace`, `SheetRise` (the last six are screen-layout rules of the Compose app: listed because they pass and `WholeRoute` needs `MapDimens`; you may leave them out).

## 4. What you / we do next

- You (`web/`): the Compose stand-in; if it gets `SnapshotStateList` (§1), tell the captain / state owner and `PlanDocument` is listed in one commit. Until then the 7 slices that take a `PlanDocument` stay out even after their cut.
- Cutting the coroutine slices (a later piece, `state/`): each host above is the list of calls you implement; `suspend` members (SuggestHost) and `withContext` blocks are what the cut has to turn into ports with a callback or plain state + a reducer.

## 5. The numbers, by command (run in `state/`)

```
R=src/main/kotlin/com/onpath/app
find $R -name '*.kt' | wc -l                                  # 62 files
find $R -name '*.kt' -print0 | xargs -0 cat | wc -l           # 8955 lines
grep -v '^#' web-ready.txt | grep -v '^$' | wc -l             # 29 listed
grep -rlE 'kotlinx.coroutines|\bsuspend\b' $R | wc -l         # 21 with coroutines
# members of a host: count the `fun` / `val` / `var` lines inside its interface, e.g.
awk '/^interface PlanEditHost/{c=1;next} c&&/^}/{exit} c&&/^    (fun|val|var|suspend fun) /{n++} END{print n}' $R/ui/plan/PlanEditSlice.kt
```

## 6. The cut for slices with a coroutine: `XState` + `XSlice : XState` (2026-11-20, first one built: place menus)

The 13 planner-feature slices are not listed because of a few lines of background work each, not because of their
state. The cut, the same for each – **no call site changes, in the app or in a flow test**:

- `XState.kt` (listed, `open class`): everything but the coroutines – the state, the decisions, and for each piece of
  background work two or three plain members: **what is to do** (`…Todo()`), **the blocking work on its input**
  (`find…(todo, port)`), **what a finished run changes** (`…Found(todo, result)`). Its host interface `XPorts` has no
  `suspend` member.
- `XSlice.kt` (app only, not listed): `class XSlice(…, io, scope) : XState(…)` with the `Job`, `launchSafe`,
  `withContext` – it calls the three members in the old order. Its host `XHost : XPorts` adds the suspending members.
  Name, constructor and members are the old ones, so `AppViewModel`, the screens and the flow tests stay as they are.
- The planner constructs `XState` and drives the work itself (its own async on the page): `todo` → its lookup → `found`.

Built: `ui/plan/PlaceMenusState.kt` (was `PlaceMenusSlice.kt`; the mirror entry in `parity.json` follows the file) +
`PlaceMenusSlice.kt` (45 lines: the pinned POI kinds' lookup). **Not listed yet** – the closure, measured by a trial
listing with `WebReadyRuleTest`:

| File | What still stands in the way | Cut |
| --- | --- | --- |
| `PlaceMenusState.kt` | takes a `PlaceAddSlice` | lists as soon as `PlaceAddState` exists (type in its constructor changes) |
| `PlaceAddSlice.kt` (480 lines) | builds `HoldPaths(scope, holdWorker)` itself (2 coroutine imports); takes a `PlanEditSlice` | `PlaceAddState(…, holdPaths: HoldPaths)`; `PlaceAddSlice` builds the `HoldPaths` and passes it up |
| `PlanEditSlice.kt` | `refreshPointNames` (one coroutine, `suspend fun pointNames()` in the host) – nothing else, its own closure is clean | as place menus: `pointNamesTodo()` / `findPointNames` / `pointNamesFound` |

So the three list together after two more small cuts (order: `PlanEditState`, `PlaceAddState`, then the three lines in
`web-ready.txt`). `HoldPaths` is `:core` (the planner compiles it; it uses coroutines – [laptop]: say if TeaVM cannot
take it, then `PlaceAddState` asks a small `HoldMeasure` port instead).
`PlanRunSlice` (listed) needs no cut: its host's `recalc()` is a plain call – the planner's host implements it with
its own run (`PlanRunHost`, 6 members); what is missing is on the page side, not in `state/`.

**2026-11-20 evening: built and listed.** `PlanEditState.kt`, `PlaceAddState.kt`, `PlaceMenusState.kt` are in
`state/web-ready.txt` (ports 29 / 21 / 9 members); the app's `PlanEditSlice` / `PlaceAddSlice` / `PlaceMenusSlice`
extend them with the coroutines only. The engine's `test generateWasmGC` is green – but nothing calls the three classes
yet, so two things are untested in the browser build until the page drives them: `cause::class.simpleName`
(`PlanEditState`) and core's `HoldPaths` (coroutines) behind `PlaceAddState`.

## 7. `SaveSlice` / `RoutesSlice` – the trial listing (2026-11-20 evening): a different size than §6

Trial: the four files of `ui/routes/` added to `web-ready.txt`, `WebReadyRuleTest` run, list restored. Result:

| File | Coroutine use | Unlisted `:state` files it needs |
| --- | --- | --- |
| `SaveSlice.kt` (383 lines) | `withContext` / `withTimeoutOrNull` INSIDE its flows (`private suspend fun insertPlanned`, the name proposal with a time limit, `suspend fun proposeName` in the host) | `SaveStore.kt` (`SaveStore`, `PlannedRow` – plain, can simply be listed) |
| `RecentPlans.kt` (cut 2026-11-20 evening → `RecentPlansState`, listed) | `keep`'s name job (`withTimeoutOrNull`), save / delete / restore each a `launch` + `withContext(io)`; host `suspend fun proposeName` | – |
| `RoutesSlice.kt` (358 lines) | `private suspend fun loadStoredLine`, host `suspend fun share` | `ToursStore.kt` (plain), **`PlanRouteSlice`**, **`TourController`** |
| `RoutesGoSlice.kt` | `snapshotFlow` + `first`, `withTimeoutOrNull`, two `suspend fun` | `ToursStore.kt`, **`PlanRouteSlice`**, **`PlanLinkFlow`**, **`TourController`** |

So this is not „one lookup out“: in these slices the background steps ARE the flow (write on the IO thread, wait for a
name at most n seconds, then go on on the main thread), and `RoutesSlice` / `RoutesGoSlice` hang on the tour and on the
route run. The cut that fits is the second form of §2.2 – **plain state + a step function per stage**: `XState` holds
the state and one plain member per stage (`begin…(): Request?`, `…Written(result)`, `…Named(name?)`); the app's `XSlice`
strings the stages together with its coroutines, the planner with its promises. Order by what the planner gains:
1. `SaveSlice` (+ list `SaveStore.kt`): the planner's Save already runs `SaveDialog` + `RouteRules.saveMode`; the flow
   around it (request → name → write → after-saved → Zuletzt) is what `plans.ts` still mirrors.
2. `RecentPlans`: the same stages for keep / save / delete / restore (the planner calls `RoutesState` directly today).
3. `RoutesSlice` / `RoutesGoSlice` only after ports for the tour and the route run (`TourController`, `PlanRouteSlice`
   behind interfaces) – the planner has no tour; most of `RoutesGoSlice` („Los geht's“) is not its feature at all.
Each of 1–2 is a piece of its own: design by the lead (which stages, what each returns), a helper builds, one review.

### 7.1 The cut for `SaveSlice` (design, 2026-11-20 evening): two ports instead of stages

Read through, every coroutine in `SaveSlice` is one of two shapes: (a) „do this blocking write off the main thread, then
go on here with its result“ (`withContext(io) { … }` inside a `launchSafe`), (b) „ask for the place name, at most
`nameWaitMs`“. So the slice needs no stage objects – it needs two ports (in `ui/Ports.kt`, listed):

```kotlin
/** Blocking work off the caller's thread, its result back on it. The app: launchSafe(area, what) + withContext(io). */
interface Background {
    /** Runs [work] (blocking; may throw – the failure is reported under [what] and [then] is not called), then [then] with its result on the caller's thread; [always] after either. */
    fun <T> run(what: String, work: () -> T, always: () -> Unit = {}, then: (T) -> Unit)
}
/** The place name proposed for a plan, at most [maxMs] late; null = none in time. [got] on the caller's thread, exactly once. */
fun interface NameProposal { fun ask(points: List<GeoPoint>, route: PlannedRoute?, maxMs: Long, got: (String?) -> Unit) }
```

- `SaveState` (listed): today's `SaveSlice` body with `scope.launchSafe { … withContext(io) { X } … }` rewritten as
  `background.run(what, { X }) { result -> … }`, nested where a flow has two or three writes in a row (`overwriteSaved`:
  read → replace → maybe insert). Same order of writes, same thread for each, same `saving` guard (`always = { saving = false }`).
- `SaveSlice(plan, routes, recent, store, host, scope, io, nameWaitMs) : SaveState(…, CoroutineBackground(scope, io), …)`
  – the old constructor, so `AppViewModel` and `SaveFlowTest` stay as they are. `CoroutineBackground` (not listed) is the
  one place with `launchSafe` / `withContext`; `RecentPlans` and the later slices use the same two ports.
- The planner: `Background` that runs `work` at once on its in-memory store and calls `then` (as it does for
  `RoutesState` today), `NameProposal` from its own name lookup.
- Risk: C18. `SaveFlowTest` untouched and green is the proof (it drives two manual executors: a write moved to the wrong
  thread or run early shows); `CorePlanE2ETest` on GitHub before main; one review that compares order and threads flow by flow.

**Built 2026-11-20 night:** `SaveState.kt` + `SaveStore.kt` listed; the ports as designed, with two additions the flows
needed – `NameProposal.ask(what, …, always, got)` (the failure text and the release of the `saving` guard) and a small
`Recent` interface in `SaveState` in place of `RecentPlans`. `CoroutineBackground` starts a step called from inside a
`then` undispatched (the old code's inline `withContext` – no extra hop). A flow's synchronous start is guarded
(`guardedStart`: a throw is reported, `saving` released – review finding, `SaveGuardTest`). `CorePlanE2ETest` green.

**Built 2026-11-20 evening (fresh [umbau]):** `RecentPlansState.kt` listed – Zuletzt and the recycle bin (keep, keepReplaced,
saveDraft, deleteDraft, trimOnce, bin / openBin, restore, deleteForever) on the same two ports, host `RecentPlansPorts`
(9 members, blocking, no `suspend`), rows as the top-level `DraftRow`; it implements `SaveState`'s `Recent`, so the planner
passes it to `SaveState` as it is. The app's `RecentPlans : RecentPlansState` keeps the old constructor, `RecentPlansHost`,
`RecentPlans.Leaving` / `.DraftRow` (copied field by field – two `DraftRow`s in the package, the nested one is the app's);
`CoroutineNameProposal` is the one name wait for Save and Zuletzt. Changed on purpose: `trimOnce`'s tail (`trimmed = true`,
the list read again) runs on the main thread, not on io. `keep` still writes its row before it returns (`RecentPlansTest`).

**Built 2026-11-20 evening, the laptop's cut of Routes (not the whole slices):** the planner calls only a track made a plan
and the open rules, so two small listed states, the slices delegate (callers unchanged):
- `TrackToPlanState.kt` – `planFromShown` / `planFromLine` / `cancelPlanFromTrack` / `busy` on `TrackFitter.fit(track, act,
  level, cancel: RouteCancel, got)` (got = `TrackToPlan.Result?` on the caller's thread; the app: `CoroutineTrackFitter` in
  `RoutesSlice.kt`, the fit on `planRoute.threads.background`) and `TrackToPlanPorts : Say` (8).
- `RoutesOpenState.kt` – `navigating`, `requestOpen`, `undoNewPlan`, `openSharedPlan` and the top-level `unlessUnsaved` on
  `RoutesOpenPorts` (21: mode, openTour, the tour's new-plan note, Undo, activity / T level, the link opened, the camera
  hold). A planner without a tour: `touring = false`, `newPlanUndo = null`.
- Tours, the route run, export, thumbs, sync marks and share stay the app's (`RoutesSlice` / `RoutesGoSlice`, unlisted).

**Built 2026-11-20 evening, the laptop's (1): the route run in stages – `PlanRecalcState.kt` listed** (`PlanRecalcSlice` keeps
only the coroutines: debounce, slow timer, the wait bar's poll, the threads). Stages, in the order the driver calls them:
`begin(): Run?` (the head: resets, the long-route ask via `RouteRun.Event.Recalc`, the stored line shown, the `RouteCancel`
stored in `planRun.routeCancel`) → companion `route(run, engine, legs, cancel, progress, onPlanner): PlanResult` and
`findReach(…)` (blocking, on the routing thread; read nothing of the page – the planner's worker calls them) →
`progress(gen, done, total)` / `share(gen, share)` / `slow(gen)` while it runs → `legsRouted()` → (the app: `postRoute` off the
main thread) → `taken(run, result, reach, tries, post, active)` (Returned / Superseded, the trace, snap, take or fail, what
the tour does next, Done). Ports: `PlanRecalcPorts` (20), `PlanRecalcHost` (10), `MapDataPort` (6), `Background`. `Run` holds
no cancel, lambda or port; note for a codec: `PlannedRoute.points` and three `PlanResult` fields are `by lazy` caches –
encode the constructor fields, the lazies follow. Text tests that pinned the moved lines read `PlanRecalcState.kt` now.

**Built 2026-11-20 evening, the laptop's (2): the search – `SearchState.kt` listed** (`ui/search`, `:state`). First a pure
move: `search/OfflineIndex.kt` (`SearchQuery`, `SearchHit`, `SearchRanking`), `PlaceIndex.kt`, `OnlineSearch.kt`
(`OnlineRanking`, `PhotonProvider`) and `AddressSearch.kt` went from `:app` to `:core` as whole files (byte-identical;
`AndroidAddressDb` stays in `:app`); the web engine already compiled them via `app/web-shared-files.txt`, now via the core
folder. Then the cut: `SearchUi` / `SearchContext` / `OnlineState` and the decisions – `onQuery`, `onCategory`, `clear`,
`remember`, `clearRecent`, `showAllFavourites`, `startLists`, `resolve`, the list that keeps shown rows (`shownList`), and
`run()` as stages `begin(): Search?` → (offline: the index + address search, then `offlineKeep` / `carried`) →
`offlineDone`; (online, after `DEBOUNCE_MS`, 3+ letters) `onlineLoading` → `searchCentre` / provider / `onlineAnswer` →
`onlineDone` (generation checks where they were). Ports `SearchPorts` (5: saved, recent, addRecent, clearRecent,
indexedPlace). `SearchSession : SearchState` keeps the index, the address files, the provider and the coroutines.
