package com.onpath.app.ui

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind

/**
 * The two tabs of My routes (owner 2026-10-30): **Planned** = saved plan-mode routes + imported GPX tracks (exactly
 * what Browser sync carries, `PlanSyncPass`), **Done** = recorded tours (demo tours too – they are recorded tours named
 * "Demo · …"). Planned is the default; the last chosen tab is kept while the app runs (`AppViewModel.routesTab`).
 */
enum class RoutesTab {
    PLANNED, DONE;

    companion object {
        fun of(kind: TourKind): RoutesTab = when (kind) {
            TourKind.PLANNED, TourKind.IMPORTED -> PLANNED
            TourKind.RECORDED -> DONE
        }

        /** The items of [tab], in the order given (My routes: newest first). */
        fun filter(tours: List<Tour>, tab: RoutesTab): List<Tour> = tours.filter { of(it.kind) == tab }
    }
}
