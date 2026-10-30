# Handoff: the map look per activity in the browser planner (`map/ActivityLayers.kt`)

**Problem.** The browser planner always draws the Hike map look. The shipped style (`style/f2/onpath.json`,
`tools/make_style.py`) has the Hike layers visible and the bike layers hidden; only the app switched them per activity
(`MapController.setOverlay` / `setSacLimit`). Owner rule (2026-10-31): routes and the map look are always identical in
the browser and the app – share the code.

**Main side (done).** The rule is now one pure Kotlin file, `app/src/main/java/com/onpath/app/map/ActivityLayers.kt`
(no Android, no MapLibre, no reflection; only `routing/Activity` + `BRouterEngine.profileFile`, both already in the
engine's `appFiles`). `MapController` only applies it. Kept pure by `SharedCoreRuleTest.files`; locked by
`ActivityLayersTest` (same result as the former MapController code for 6 activities × T1–T5 on both styles).

API:
- `ActivityLayers.visibility(activity, styleLayerIds, routesShown = true): Map<String, Boolean>` – layer id → visible
  for every layer the activity switches, only ids present in `styleLayerIds` (pass the style's layer ids in style
  order). Contains: forbidden ways `access-no-{dash,sign}-{foot,bike,touring,mtb}[-service]`, `barrier-blocked-*`,
  `too-hard-*`, `greenway-*` (+ their `-town` parts), bike map `bike-lane-*`, `bike-oneway*`, `bike-both-ways`,
  `surface-loose-{path,track}`, `trails-{sac,mtb}-label`, every `*-bikeview` / `*-hikeview` layer and the base layer of
  each `-bikeview` twin, the route bands `routes-{hike,bike}{,-over,-major-low,-major,-major-label,-low,-regional}`.
- `ActivityLayers.hikeRouteFilters(sac): Map<String, String>` – `routes-hike` / `routes-hike-over` → filter as MapLibre
  JSON (T3 = what the style ships).
- `ActivityLayers.toJson(activity, sac, styleLayerIds, routesShown = true): String` – both in one object:
  `{"visibility":{"greenway-foot":false,…},"filters":{"routes-hike":[…],"routes-hike-over":[…]}}`. The app sets the
  filters whatever the activity (the bikes hide the band anyway) – do the same.

## What web-spike must do

1. **Merge main**, then add `"map/ActivityLayers.kt"` to `appFiles` in `web/engine/build.gradle.kts` (beside
   `map/Overview.kt`), and `"map/ActivityLayersTest.kt"` to `appTests` only if the engine's test classpath has MapLibre's
   `Expression` – it does not (the test compares with the former Expression code), so leave the test out; the app's CI
   runs it.
2. **Engine call** in `web/engine/src/main/kotlin/com/onpath/web/WebApi.kt`:
   ```kotlin
   /** The map look for the activity (the app's map/ActivityLayers): layer visibility + hiking band filters, JSON. */
   @JSExport fun activityLayers(activity: String, sac: Int, layerIds: String): String =
       com.onpath.app.map.ActivityLayers.toJson(Activity.valueOf(activity), sac, layerIds.split(',').filter { it.isNotEmpty() })
   ```
   (layer ids never contain a comma). Add `activityLayers(activity: string, sac: number, layerIds: string): string;` to
   `WasmEngine` and `'activityLayers'` to `CALLS` in `web/app/src/engine/worker.ts`.
3. **Apply it in `web/app/src/main.ts`**: one function, called in `map.on('load', …)` (after the style loaded, beside
   `applyMapContent()`) and at the end of `setActivity()` (activity or T level changed – the picker sets both):
   ```ts
   let lookAsked = 0;
   async function applyActivityLook() {
     const ask = ++lookAsked; // a newer activity wins (picker clicked twice quickly)
     const ids = map.getStyle().layers.map((l) => l.id).join(',');
     const j = JSON.parse(await engine.call('activityLayers', state.activity, state.sac, ids)) as
       { visibility: Record<string, boolean>; filters: Record<string, unknown> };
     if (ask !== lookAsked) return;
     for (const [id, on] of Object.entries(j.visibility)) if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', on ? 'visible' : 'none');
     for (const [id, f] of Object.entries(j.filters)) if (map.getLayer(id)) map.setFilter(id, f as maplibregl.FilterSpecification);
   }
   ```
   Errors → `problem('map', …)` (no silent catch). The map content panel (`mapcontent.ts`) touches only `poi-*`,
   `contour-*`, `hillshade*` – no overlap. The overview's copies (`<id>~overview`, `~under`) are never in the table
   (as in the app).
4. **Parity**: `node web/parity/check.mjs` will flag `map/MapController.kt` (its `setOverlay` / `setSacLimit` changed;
   the mirrored parts – arrows, layer order – did not) → `--update app/src/main/java/com/onpath/app/map/MapController.kt`.
   `ActivityLayers.kt` then shows up under `shared` (`--update`).
5. **Web smoke test** (e.g. `web/app/test/activity-look-smoke.mjs`, same frame as `overview-smoke.mjs`; add it to the
   `smokes` matrix in `.github/workflows/web-spike.yml` and to `merge-main.sh`'s list): open the page, wait for the
   style + engine, then via `window.__map`:
   - default / Hike T2: `greenway-foot` visible, `greenway-touring` + `bike-oneway-streets` hidden;
   - pick **Bike** (activity chip → Bike): `greenway-touring`, `surface-loose-path` and `bike-oneway-streets` visible,
     `greenway-foot` hidden, `routes-bike` visible, `routes-hike` hidden;
   - pick **Hike T4**: `map.getFilter('routes-hike')` contains `["<=",["coalesce",["get","sac"],0],4]` and
     `routes-hike-over` contains `[">",…,4]`; `greenway-foot` visible again;
   - reload with a remembered Bike: the bike look comes with the first `load` (no Hike flash left).

## Notes

- Format 1 styles (old map packages) are handled by the same call (`access-no-*-bike` for every bike activity there);
  the web serves f2 only.
- A future style change only needs `tools/make_style.py` + a main merge: the table follows the layer ids it is given.
