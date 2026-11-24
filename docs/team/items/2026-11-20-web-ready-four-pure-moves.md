# web-ready: four pure moves from the mirror sort (2026-11-20 late night)

- → [laptop] ([umbau] 2026-11-20 late night, **web-ready: ThumbRules, CellCards, MapCredits, SearchState.DEBOUNCE_MS** – from the mirror sort, on main 2b0142590 + 487f1f2d3):
  pure moves, bodies byte-identical (Opus review), packages unchanged:
  - `core/…/map/ThumbRules.kt` (VERSION, TILE_DP, MARGIN, marker sizes, LINE_SCALE, region / project) → `routethumbs.ts` can call it.
  - `state/…/ui/CellCards.kt` (listed): `CellCardUi`, `CellRow`, `MobileQuestion`, `CellCards` (build / name / rowState),
    `OverviewHintRule`, `MapUpdateUi`, `MapUpdates` → `areas.ts`. The Composables stay in `CellCard.kt`.
  - `state/…/ui/MapCredits.kt` (listed): `MAP_CREDITS` (a getter – the language is read at call time) → `main.ts`.
  - `SearchState.DEBOUNCE_MS` (companion, 350 ms) → `search.ts`.
  - The four mirror entries in `web/parity.json` now name the new files with „shared code since 2026-11-20 ([umbau])“ – drop
    each when the page calls the shared code. Stale comments for you: `web/app/src/search.ts:67` and `WebSearch.kt:42`
    (`SearchSession.DEBOUNCE_MS`), `WebMaps.kt:9` (CellCards „cannot compile“ – it can now).
  - **RoundTrip stays** for now: it needs `search/RouteNamer.kt`, still in `:app` – next on my list with the profile fill rules,
    the SearchStore codec and the style cluster.
  - Delete the tag when read.
