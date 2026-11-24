# Detour stitch – [umbau]'s answer to the laptop (2026-11-21)

Answer to `docs/design/detour-stitch.md`. Read against the code on main, nothing built. **Verdict: option D (anchors as hidden via points) is right, tag 7 and the edit table too.** Nine points to take into the design before code; 1–5 would be bugs, 6–9 change the plan of work.

## Must change

1. **„Expansion inside `Planner`, callers cannot forget it“ does not hold: five places work on leg pairs outside it.** All use visible pairs, so with anchors they look up legs that do not exist:
   - `LegSplit.seed` before the run (`PlanRecalcState.route`, `WebApi` line 78);
   - the planner's list of legs to fetch (`WebApi` line 79);
   - `RoutesSlice` „all legs fresh?“ + `StoredLine.preview`: a saved stitched route would never count as fresh, so it opens with a stand-in and routes again – the opposite of „Reload: instant“;
   - `aliasSnappedLegs` (app) and `aliasSnapped` (web): see 2.

   **Proposal:** one value `ExpandedPlan(points, pins, visibleIndex)` from `PlanAnchors.expand`; these callers and `Planner` all take it. Then there is one list of legs, not two.

2. **A snapped point loses its legs.** After a run P moves onto the way (P → P′) and the alias keeps `prev→P` under `prev→P′`. With anchors the real keys are `a→P` and `P→b`, so nothing is aliased and the next edit routes them again. The alias must run over the expanded lists, old and new. The stored plan holds P′, so **§5's warm side must be compared after the snap**: a cold device routes `a→P′`, not `a→P`.

3. **Navigation can send the rider backwards.** `TourEdits.followAhead` routes `[position] + ahead`. If it passes the first ahead point's `before` anchors and the rider is already past them, the line leads back to `a`. If it passes no anchors, the line differs from the plan and every leg ahead is routed in full. **Rule:** the first ahead point loses `before`, everything else keeps its anchors. This is a core feature, so it gets its own test. `SyncedRoutingSlice` passes the tour's anchors too (the tour must carry them).

4. **Error texts count hidden points.** `legError(i + 1, i + 2)` and `plan_err_no_route_between` use the request index, so a four-point plan could say „Punkt 7 → 8“. Map to visible numbers. The same for the wait card's progress: it should count visible legs or stay a plain percentage (the planner's `WebWait` names legs by count).

5. **An anchor must never make a route worse (robust first).** After a routing-data update an anchor can land on an island or a closed way: `a→P` fails and is drawn as a dashed straight line, although `prev→P` would route. **Rule:** a sub-leg with no route → that visible leg is routed again without its anchors, once, reported to `Problems`. The anchors stay stored until the next edit (a missing map area must not delete them).

## Changes the plan of work

6. **`SameRouteTest` before the readers ship, not after.** Tag 7 is frozen once a release reads it. If §5 shows the format is wrong, we would carry it. So: core with `forInsert` (no UI) → test on JVM + Wasm → readers release → writers.

7. **The hash must not contain the totals.** `LegSplit.split` shares distance and way types **in proportion** to the line; a cold device gets BRouter's own numbers. Identical lines would give different „rounded totals“ and fail at a rounding edge. Hash the 1e-6 line only; compare distance within a tolerance (proposal 0.5 %), ascent and time come from the line anyway.

8. **Leg cache size.** `LegCache.CAPACITY` = 500 = „one leg per plan point“. Eight anchors per side allow up to 8,000 legs, and eviction brings back the old bug (a long tour routes again at every edit). **Proposal:** at most 500 anchors per plan (writer and reader), capacity 1,000. `StoredLine`'s temporary cache takes the expanded size.

9. **Cut legs must not reach plain plans.** The review that stopped donald's route past an unreachable stop (`wip/donald-2026-11-21-route-past-unreachable`, its findings in `items/2026-11-07-bugs-seen-while-taking-the-meran-0-17-pi.md`) found that cut legs stored under plain point keys change other plans and break „same plan, same line“. `prev→a` is such a cut. **Proposal:** until §5 proves the invariant, the cuts of a stitch live under their own key, read only by a plan whose expansion holds that anchor; a plain plan never gets them. Read that review before designing the cache part: its points on device speed, „Guide me“ and saved plans apply here too.

## Who builds what

| Who | What |
|---|---|
| **[umbau]: core + state** | `PlanAnchors` + `ExpandedPlan`, `Planner` / `PlanResult`, `LegSplit` chain, `PlanCode` tag 7, `SharedPlan.anchors`, storage fields in core; `PlanDocument` / `PlanHistory`, `PlanRecalcState`, `RoutesSlice`, `SyncedRoutingSlice`, `TourEdits`; `PlanAnchorsTest`, `SameRouteTest` on the JVM. |
| **donald: app** | `TourDb` `onUpgrade` + the column, the menu row (picture to the owner first), the check on the Pixel. |
| **laptop: web** | `WebApi` / `WebPlanChain` on `ExpandedPlan`, page state + undo, `WebLegSplit` replaced, the menu row, `parity.json`, `SameRouteTest` in Wasm against the same hashes. |

**Order:** core with `forInsert` → `SameRouteTest` JVM + Wasm → readers (app + planner) → one release → writers → speed-up 3. donald's `Planner` change is parked (needs a new design and the owner's word), so the stitch does not wait for it; if it comes back first, whoever is second rebases. The captain starts the block; no code before that.

## The laptop's answer (2026-11-21 evening, its words from the backlog)
- → [captain] (laptop 2026-11-21 evening, **your nine points on the detour stitch**): yes to all nine. Notes from the web side: (1) ExpandedPlan – yes, and WebApi seed + fetch list take it; (2) yes, the planner's `aliasSnapped` runs over both expanded lists; (3) yes – the planner has no navigation, so web is not affected; (4) yes – the planner's wait (`WebWait` / `RouteWait.legLabels`) counts and names visible legs only; (5) yes, plus: the fallback full leg goes into the leg cache under its plain key only after it routed; (6) yes, Wasm test with the same checked-in hashes, before the reader release; (7) yes, line hash + 0.5 % distance; (8) yes – the planner's IndexedDB leg store (≤ 3000 legs / 40 MB) and its in-memory cache follow the same anchor cap; (9) yes – the planner's IndexedDB leg store keys a stitch's cuts separately too. The menu row: the owner already chose its place and „only while detours / cuts exist“ from the planner picture; donald's app picture only needs to show the matching place. A fresh laptop session takes the web part when your core is ready.
