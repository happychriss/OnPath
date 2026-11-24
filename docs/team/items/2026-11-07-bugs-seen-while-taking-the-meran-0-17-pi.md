# [donald] Bugs seen while taking the Meran 0.17 pictures (2026-11-07, emulator, 0.17.3)

- **[donald] Bugs seen while taking the Meran 0.17 pictures (2026-11-07, emulator, 0.17.3)** – (1) done (8c3e02c3: the shortcut rule never jumps past a stop ahead); (2) (3) (4)
  (5) (8) done 2026-11-07 (donald-picture-bugs); (7) D10: T1 + Mutkopf
  (② only from T2) gives 30,3 km · 10 h 55 · ↑ 1950 m and "1 Abschnitt über T1" – the line runs east via Dorf Tirol
  towards the nearest T1 way under Mutkopf and back (out-and-back), and a T1 route should have no section above T1; not
  looked into. Shared code: (5) changed `ui/RouteWarnings.text` (first word capital) – the planner gets it by merging.
  Pictures: onpath.private/marketing/bilder/aktuell/.

**(7) measured 2026-11-21 (donald, real data, current code) – still there, an owner decision:** Vellau → Mutkopf → Leiteralm at T1 = **30.3 km**, ↑ 1882 m, 38 % of the line runs twice, ② stays 552 m off; at T2 the same plan is 10.8 km with nothing above T2. Cause: at T1 the route goes round the mountain to the nearest T1 way under ② and back. The sheet says so („② erst ab T2 erreichbar“), but the line is a long detour to a point one cannot reach. The „1 Abschnitt über T1“ (151 m each way, T2): the profile makes ways above the limit very expensive, not forbidden (`SAC_access_penalty`), and the sheet reports them honestly. **Open for the owner:** keep this line, or plan past a stop the level cannot reach (Vellau → Leiteralm direct, ② stays a red line + grey piece)? Shared planner code: same route in the app and the planner.

**Owner 2026-11-21 (donald's chat): „Am Punkt vorbei“.** A stop the chosen level cannot reach no longer pulls the route on a detour: Vellau → Leiteralm direct, ② stays in the plan with its red line „erst ab T2 erreichbar“. App and planner alike.

**2026-11-21, built and NOT merged:** `wip/donald-2026-11-21-route-past-unreachable` (53c53a6f1; real data 30.3 → 6.2 km). The big-model review stopped it: (1) cut legs stored under plain plan-point keys change other plans and break „same plan, same line“; (2) the decision depends on device speed / timeouts; (3) `PLANNER_VERSION` not raised; (4) „Guide me“ and fresh re-routes bring the detour back; (5) a passed stop can land on the wrong leg (days, stop order); (6) **a real tour can collapse**: car park → summit just above the level → bus stop 700 m away becomes a 900 m walk; (7) a night on such a stop; (8) saved plans route on load. Needs a new design and the owner's word on (6).

**Owner 2026-11-21: „Hinweis, du entscheidest“** – no automatic route past the stop; the route stays, the red line says clearly that the point makes a detour, he changes the level or deletes the point himself. The wip branch stays parked. Picture first.
