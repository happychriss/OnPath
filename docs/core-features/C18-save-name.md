# C18 – Save asks for the name


Part of the core features (`docs/core-features.md`): every core feature has JVM regression tests; a field bug first gets a failing test, then the fix.


| # | Core feature | Field bugs guarded against | Regression tests |
| --- | --- | --- | --- |
| C18 | **Save asks for the name** (owner 2026-10-31, replaces "save at once") – every Save (☰ "Save route" – the top item whenever there is a plan, a green dot on ☰ while unsaved; "Replace current plan?" → Save first) opens a small dialog: the name prefilled and fully selected, Cancel · Save. A new plan → a new route (proposal "Vellau → Leiteralm", else "Route 31 Oct, 10:12"; never the name of another planned route – " (2)"); a saved route → overwritten, renamed when the name changed, still one route; a blank name keeps the proposal / old name; Cancel saves nothing (Save first then loads nothing). My routes → ⋮ → Save as copy = the same dialog with "<name> (2)", a copy of the stored route. The sheet's "Saved as “Name”" is a plain line; My routes → ⋮ → Rename stays. No ⋯ in the sheet (0.13.5): the action row ✕ Close plan ("Discard changes?" only when unsaved) · ⇅ Reverse · ↻ Round trip. Tour end / arrival saving unchanged | – | `SaveDialogTest`, `RouteRulesTest`, `RouteRulesTest.newRouteName*` / `copyNameIsTheNextNumber` / `saveModeOverwriteOnlyForAnExistingSavedRoute` / `cleanName`, `PlanLinkTest`; screenshots `ScreenshotTest.planSaveDialog` / `planSaveCopyDialog` / `planSaved` / `planChangedNotSaved` / `PlanMenuGalleryTest.planMenuStates` / `planDiscardAsk` / `routesItemMenuPlanned` (EN; DE menu rows in `RoutesMenusGalleryGermanTest.routesActivityMenuAndItemMenuPlanned`, DE at the largest font in `SlideLabelLargeFontGermanTest`, largest font `PlanChipLargeFontTest.planSaveDialog` EN + DE) |
