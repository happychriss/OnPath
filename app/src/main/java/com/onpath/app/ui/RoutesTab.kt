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

        /**
         * The search box of Meine Touren (owner 2026-11-06): every word of [query] is in the name – case and accents
         * do not matter ("koln" finds "Köln", "rund kofel" finds "Rund um den Kofel"); blank = everything.
         */
        fun search(tours: List<Tour>, query: String): List<Tour> {
            val words = fold(query).split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return tours
            return tours.filter { t -> fold(t.name).let { n -> words.all { it in n } } }
        }

        /** Lower case without accents; ß → ss, so "strasse" finds "Straße". */
        private fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace("ß", "ss")
    }
}
