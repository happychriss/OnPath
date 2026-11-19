# the planner on the shared slices – a proposal branch for items 1 and 3

- → [laptop] ([umbau]'s helper, 2026-11-19 night): branch **`wip/umbau-web-slices-2026-11-20`** – a PROPOSAL for items 1 and 3 of `2026-11-20-items-35-how-we-go.md`, engine side only. Not on `main`, nothing of `web/app/` touched. Take it, change it or drop it; `web/` is yours.

## What is on the branch (only `web/engine/**` + this file)

| File | What |
|---|---|
| `web/engine/build.gradle.kts` | item 1: reads `state/web-ready.txt` at configuration time (`pathList`, as `core-excludes.txt`) and adds the 29 files as sources of `compileKotlin`, like `app/web-shared-files.txt [files]`. A listed file that is missing, or whose path is also in `core/`, `app/` or a stand-in, fails the build with its name. `stateLeftOut` (a map path → reason) is the place for a listed file TeaVM cannot compile – **empty: all 29 compile**. |
| `web/engine/src/main/kotlin/androidx/compose/runtime/SnapshotState.kt`, `…/snapshots/SnapshotStateList.kt` | the Compose stand-in – written new (no stand-in on any `origin/wip/laptop-*`, `origin/web-spike` or `origin/umbau-webready-inputs`). The five names of `WebReadyRuleTest.runtimeNames` and the types they return (`State`, `MutableState`, `IntState`, `MutableIntState`, `SnapshotStateList`). A plain holder; its comment says what it does not do (no isolation, no observers, no policy). |
| `web/engine/src/main/kotlin/com/onpath/web/WebState.kt` | item 3: `WebJson` (reader = `SuggestSettings`' own, + a writer), `WebRoutesStore` (the store seam), `WebState` (one `RoutesState` + one `SaveDialog`), 24 `@JSExport` functions. The mapping TS function → export → slice member is the file's first comment. |
| `web/engine/src/main/kotlin/com/onpath/web/WebApi.kt` | **4 lines at the top** (`@file:JvmName("WebApiKt")`, `@file:JvmMultifileClass` + a comment) – see "the one thing I had to do to your file". |
| `web/engine/src/test/kotlin/com/onpath/web/StateSlicesTest.kt` | 4 JVM tests: the real `RoutesState` / `SaveDialog` from the compiled `:state` sources on an in-memory `RoutesStore`, same expected values as `RoutesFlowPropertyTest` (2) and `SaveFlowTest` (2). |
| `web/engine/src/test/kotlin/com/onpath/web/WebStateTest.kt` | 9 JVM tests of the exports: load → state JSON (one exact string) → operation → state JSON + drained writes; a load while writes wait; malformed JSON / wrong arguments / unknown rows → `{"error":…}` or `{"ok":false}`, never an exception; Zuletzt → Speichern with the id change; trim / rename / a restored saved route's twin; the save dialog. The clock is the `now` passed in. |
| `web/engine/src/test/node/state-wasm-smoke.mjs` | 22 checks in the REAL Wasm under Node: the 24 exports are module exports, the slices run there, broken input is an error JSON (no trap), two older exports still answer. Not wired into CI (`.github/` untouched). |

## How to try it

```
git fetch origin && git checkout wip/umbau-web-slices-2026-11-20
tools/dev/gradle_slot.sh -- ./gradlew -p web/engine test generateWasmGC copyWasmGCRuntime -Pkotlin.incremental=false
node web/engine/src/test/node/state-wasm-smoke.mjs        # "all ok" (Node 22.14 needs --experimental-wasm-exnref: the script retries with it)
```

## The Wasm size (`ls -l` / `gzip -9 | wc -c` of `build/generated/teavm/wasm-gc/onpath-web-engine.wasm`, donald)

| | bytes | gzip |
|---|---|---|
| `main` 06860e11d (before) | 4 308 269 | 1 273 732 |
| item 1 only (29 files compiled, nothing exported → TeaVM drops them all) | 4 308 269 | 1 273 732 |
| item 3 (the slices reachable through the exports) | 4 512 914 | 1 323 125 |
| **cost** | **+204 645 (+4.7 %)** | **+49 393 (+3.9 %)** |
| of it: the multifile class alone (measured: WebApi.kt with the two `@file:` lines, no WebState.kt) | +38 969 | +6 024 |

So the slices + their exports are ≈ +166 KB (+43 KB gzip); the stand-in itself is nothing measurable. I did not break the 166 KB down (my guess, not measured: `Tour` – a data class with 24 fields whose `copy` / `equals` / `toString` pull `MultiDay`, `RideRecord` … – and the string templates of the traces). One Wasm serves worker and page, so it is downloaded once.

## The one thing I had to do to your file (WebApi.kt), and the alternative

TeaVM exports only the `@JSExport` static methods of ITS MAIN CLASS as module exports (teavm-jso-impl 0.16.0, `WasmGCJsoCommonGenerator`: `entryPoint.equals(className)`; `JSAliasRenderer` the same for JS). `mainClass` is `WebApiKt`. Exports in another file compile, pass every JVM test and are **silently not exported**. To keep the exports in `WebState.kt` as you wanted, both files are now parts of one multifile class (`@file:JvmName("WebApiKt")` + `@file:JvmMultifileClass`). Proven in the Wasm (the smoke: all 24 new + old exports there and answering).
- Cost: +39 KB (+6 KB gzip) – each of the 140 exports (116 + 24) gets a delegating method in the facade class.
- **Alternative with no cost:** move the 24 one-line `@JSExport` functions from the end of `WebState.kt` to `WebApi.kt` (as the wait window does: logic in `WebWait.kt`, exports in `WebApi.kt`) and delete the two `@file:` lines in both files. Everything else stays. Your call – I took the variant you asked for.

## The exports (all: strings / numbers / booleans in, JSON out; a failure = `{"error":"…"}` + a line in `Problems`, never thrown)

A row is named as your `Place`: `kind` = `"draft"` (recent.ts) | `"saved"` (sync.ts) + `id`. `now` = `Date.now()`.

| Export | Replaces (TS) | Slice member | Answer |
|---|---|---|---|
| `routesStoreLoad(json)` | – (new: the seam) | – | `{"rows":n,"skipped":k}` |
| `routesStoreTakeWrites()` | `write()` / `localStorage.setItem(PLACE…)` of recent.ts | – | `[ops…]` |
| `routesStoreMoved(fromKind, fromId, toKind, toId)` | – (a draft saved gets a sync id) | – | `{"ok":…}` |
| `routesStateJson()` | `recentPlans()`, `binnedPlans()`, `zuletztPlace()`, `recentRow(null, …)` | `open`, `tab`, `sort`, `tours`, `justSavedId`, `recentId`; `LivePlanRows.of` | the whole state (shape: KDoc of `WebState.routesJson`) |
| `routesRecentRow(points, linkedKind, linkedId, code, unsaved)` | `recentRow(live, …)` | `LivePlanRows.of` (core) | the row or `null` |
| `routesTakePlace(kind, id, now)` | `takePlace(place, now)` | `takePlace` | `{"gone":n}` |
| `routesKeepDraft(newId, code, title, points, unsaved, savedKind, savedId, now)` | `keepRecent(…)` + the saved / bin / twin checks of main.ts `keepAsRecent` | `keepDraft` | `{"id":newId\|null}` |
| `routesAfterSaved(savedId, fromDraftId, code, now)` | `afterSaved(savedId, fromId, points, now)` | `afterSaved` | `{"gone":n}` |
| `routesSaveDraft(id, now)` | plans.ts `saveRecent` (Zuletzt → Speichern) | `saveDraft` + `markSaved` | `{"ok":…}` |
| `routesMarkSaved(kind, id)` | `host.setJustSaved(id)` | `markSaved` | `{"ok":true}` |
| `routesToBin(kind, id, now)` | `toBin(id, now)` | `toBin` | `{"ok":…}` |
| `routesRestore(kind, id, now)` | `restoreRecent(id)`, `savedRestored(id, twins)` (+ the twin search in plans.ts) | `restore` | `{"ok":…}` |
| `routesRenameDraft(id, dated, proposed)` | `renameRecentIf(id, from, to)` | `renameDraft` | `{"ok":…}` |
| `routesTrimDrafts(now)` | the trim part of `startUp(now)` | `trimDrafts` | `{"gone":n}` |
| `routesOpen(open)`, `routesTab(tab)`, `routesSortTap(key)`, `routesShowSaved(kind, id)` | page state of plans.ts | `open`, `tab`, `sort` (`RouteSort.tap`), `showSaved` | `{"ok":true}` |
| `saveDialogJson()` | – | `SaveDialog.ask` | `{"open":…,"ask":{kind,name,routeId,token}\|null}` |
| `saveDialogRequest(overwrite, savedName, savedId, dated, taken)` | `saveRoute`'s OVERWRITE / NEW + `nameDialog(false, first)` | `request` | `{"token":n}` |
| `saveDialogRequestCopy(savedId, name, taken)` | `copyRoute` + `nameDialog(true, first)` | `requestCopy` | `{"token":n}` |
| `saveDialogProposed(token, proposal, dated, taken)` | the `later` promise of `nameDialog` | `proposed` (+ `RouteRules.newRouteName`, as SaveSlice) | `{"ok":…}` |
| `saveDialogCancel()` | Cancel / Escape | `cancel` | `{"ok":true}` |
| `saveDialogConfirm(input, savedId)` | Save / Enter + `cleanName` | `confirm` → `SaveFlow.Write` | `{"write":{kind,name,routeId}\|null}` |

Stay TS (no member of the two slices does them): `renameRecent` (⋮ → Umbenennen), `daysLeft` / `BIN_DAYS` / the 30-day removal of `startUp`, the storage cap, `datedName`, `storedTitle`, `roomFor`, the dialog's DOM and its "typed" rule (do not call `saveDialogProposed` once the user typed), `then`.
Not exported though `RoutesState` has them: `load` / `beginLoad` / `show` (each export re-reads the list), `keepReplaced`, `openAsk`, `line`.

## The store seam and its limits (KDoc of `WebRoutesStore`)

`RoutesStore` is synchronous, your storage is not. So the engine holds a PRELOADED list and queues its writes:
1. `routesStoreLoad({"saved":[SavedPlan…],"drafts":[RecentPlan…],"binned":[BinnedRoute…],"recent":Place|null})` – your objects as you store them; once at the start and after every change of your storage.
2. the slice reads / writes that list at once;
3. `routesStoreTakeWrites()` → the ops in order (`insert`, `setDeleted`, `setDraft`, `delete`, `rename`, `recent`), each with `kind` + `id` and `row` = YOUR object with the one change applied – fields I do not read are carried through untouched (tested with nested unknown fields).

Fields read: SavedPlan `id, kind, title, plan, updatedAt`; RecentPlan `id, title, code, at, deletedAt`; BinnedRoute `id, kind, title, plan, at, deletedAt`. A row's points come from its plan CODE (`PlanCode.parse`) on both sides, never from `RecentPlan.points` – the same rounding as your `samePointsAs`. `Tour` fields the slice gets: `id` (a Long handle per (kind, id), internal), `kind`, `name`, `createdMs`, `state="done"`, `waypoints`, `draft`, `deletedMs`; the rest default.

Guaranteed: a write is visible to the slice at once; the queue keeps every write in order until taken; a load while writes wait is refused (`{"error":…,"pending":n}`) and loses nothing; the Zuletzt place and the "just saved" mark survive a load and an id change you announce (`routesStoreMoved`); a load never throws (a row without id is left out, a code that is no plan gives a row without points – both reported).
**Not** guaranteed: your storage has a write only after you applied it – a page closed between the call and the drain loses it (recent.ts today writes localStorage in the same call: do drain + apply in the same task for drafts; IndexedDB cannot); nothing checks that you applied a write; a storage change you do not load (phone sync, another tab) is not seen; nothing survives a page reload; `loadSegments` is empty.

**My answer to "does item 4 hold?" (Friday 12:00):** for what `RoutesState` does – yes, with this seam: every decision is made on the in-memory list, the page follows. It holds because the slice never needs a value FROM a write (ids of new drafts come from the page, `newId`). The one place it bites is Zuletzt → Speichern: `saveDraft` flips the draft in the engine, but your saved plan gets its id from `sync.save` later → `routesStoreMoved("draft", id, "saved", newId)` (tested); or keep today's order (save first, load, `routesAfterSaved`) and never call `routesSaveDraft`. It would NOT hold for a slice that must read back what the store wrote in the same call (none of the listed ones).

## NOT done

- The page: the new signatures in `waitengine.ts`' `Exports`, loading + draining, `recent.ts` and the save-dialog part of `plans.ts` out, the smokes (`zuletzt-smoke`, `routes-smoke`, `sort-smoke` …).
- `web/parity.json`: the two mirror entries for `RoutesState.kt` / `SaveDialog.kt` become shared when the page runs the slices – unchanged here. `node web/parity/check.mjs` says `parity: ok (4 warning(s), 24 note(s))` on the branch; its warnings / notes are about `app/` and `strings/` files, none about `web/engine/build.gradle.kts`, `WebApi.kt`, `state/web-ready.txt` or `state/`.
- CI: the Node smoke runs nowhere by itself.
- The other 27 listed files compile but nothing exports them (TeaVM drops them): only `RoutesState` + `SaveDialog` are proven in the Wasm.

## Where I was unsure or took a shortcut

1. **Behaviour changes the page takes over with the slice** (the app's rules, not today's recent.ts): a draft opened and changed becomes a NEW draft and the old one goes to the bin (`keepRecent(…, replaces)` updated it in place); a twin among the drafts takes the place without its `at` being bumped; `restore` of a saved route finds the twins itself, among the rows loaded.
2. **Folders.** The slice knows one list. Which saved plans you load (the open folder? + `savedFolder`?) decides what "a saved route with the same points" and "the saved route is still there" mean. A `recent` place naming a row that is not loaded is kept, the row then shows the newest draft or nothing (as `recentRow` today).
3. `recentId` cannot be set from outside (`RoutesState.kt:109-110`, `private set`): when a load brings another `recent`, `WebState.withRecent` builds a NEW `RoutesState` and copies `tab`, `open`, `sort`, `justSavedId` (no hook fires). Works, but it is a workaround.
4. JSON: the reader is `SuggestSettings`' (already in the Wasm; its error texts start with "settings:"), numbers are Doubles – ids must be strings, whole numbers are exact up to 2^53; what is carried through is value-identical, not byte-identical. `org.json` is NOT used (it would come into the Wasm).
5. `routesKeepDraft` with a code that has no readable points answers an error (not "nothing to keep") – so the page can keep the plan its own way; my choice (robust first).
6. `routesSaveDraft` also sets the "just saved" mark (the app does it after the save succeeded).
7. Tracks (`kind:"track"`) are rows of kind IMPORTED without points; the slice never compares them.
8. The traces `RoutesState` writes (`Problems.trace`, lines 122 / 146 / 149) land in the PAGE instance's `Problems` – drain `problems()` of that instance too, or they are only in the console.
9. `SnapshotStateList` is in the stand-in (needed as `mutableStateListOf`'s return type). Not tried: whether `PlanDocument.kt` compiles with it.
10. Stale handles: ids the page asks about once stay in a small map for the instance's life (no clean-up).
11. Builds here took 21–24 s each (donald, warm daemon), not minutes – all numbers above are from full `-Pkotlin.incremental=false` runs.

## What in `state/` would make this easier (for [umbau], nothing changed on this branch)

- `state/…/ui/routes/RoutesState.kt:109-110` – a way to take over a place read from storage without `takePlace`'s side effects (e.g. `fun adoptRecent(id: Long)`): point 3 above goes away.
- `state/…/ui/routes/RoutesState.kt:22-32` (`RoutesStore`) – works as it is with the seam; a `fun insertDraft(…): Long` in the port instead of `keepDraft`'s `insert` lambda (`:137`) would let the store make the row without the export closing over page arguments (small).
- `state/…/ui/routes/SaveDialog.kt:37` (`proposed(asked: SaveAsk, …)`) – identity of a `SaveAsk` cannot cross to the page; I hand out a token. A token / counter in `SaveDialog` itself would make that the slice's rule (tested there once).
- `core/…/domain/RouteRules.kt:56` (`SaveAsk.then: (() -> Unit)?`) – a lambda in state cannot be shown as JSON; the page keeps its own continuation. Fine today; a plain value would be web-ready in the full sense.
- The user's rename (⋮ → Umbenennen) and the recycle bin's days are in no listed file – `renameRecent`, `daysLeft`, the 30-day removal stay mirrors until they are.
- `state/src/test/…/WebReadyRuleTest.kt:23-26` – the stand-in now has `SnapshotStateList`; the rule can allow the import when you list `PlanDocument`.
