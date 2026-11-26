# Plain words: routing data, SAC scale, error texts (owner 2026-11-26, later)

**(owner)** From the UI check against VISION.md (thread „App-Oberfläche gegen Vision prüfen“, 2026-11-26). The owner
agreed these need doing, **later, not now**. Each text change is shown to the owner first (VISION: a UI text he chose
stays; a change is a question).

1. **„Routing-Daten“ → „Karte und Wege“** – the Plan texts already say „Karte und Wege“. Still technical:
   `msg_routing_data_*` (strings_messages: ready, stopped, offline, present, busy), `maps_check_routing`,
   `wait_step_loading_map_routing` / `wait_step_loading_routing` („lädt Karte + Routendaten“), `plan_err_profile*`
   („Routing-Profil“), `routes_data_download_paths`. Shown e.g. when map/way data for an area loads or fails, and in
   the route wait window.
2. **Explain the SAC scale** – „T3 – schwerer als dein T2“ (`plan_spot_above_t`), „Wandern (T%d)“ (`nav_who_hike`),
   `plan_reach_*`. Words first (e.g. Bergweg / Alpinweg), the T number small after it, and a short explanation where
   the hiker picks his level.
3. **Error texts** – seven map server errors (`maps_err_*`) say things the hiker cannot act on („unbrauchbare
   Dateiliste“). One plain line („Karte lädt gerade nicht – OnPath versucht es weiter.“), details to Diagnostics.
   Same for file extensions in user texts (`maps_import_not_map_data`: .pmtiles / .rd5 / …, `routes_missing_adb`:
   USB debugging).

Owner 2026-11-26, **stays as it is for now:** the outdoor colours (green is fine for a hiking app; he wants examples
before any change), no dark mode, English texts in Settings → Diagnostics.
