package com.onpath.app.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import com.onpath.app.ui.routes.FavouriteStar
import com.onpath.strings.*

/**
 * „Favoriten“ of the empty search (at most five, „Alle Favoriten ›“ in its head): one-line rows, each with the gold ★ of
 * My routes at the end (owner 2026-11-28 15:10). A tap on the star removes the favourite – the row stays with an empty
 * star until the search is left, a second tap keeps it again ([SearchState.favouriteTapped]); a tap on the rest of the
 * row picks the place.
 */
internal fun LazyListScope.favouritesGroup(ui: SearchUi, actions: SearchActions) {
    if (ui.favourites.isEmpty()) return
    item {
        GroupHead(str(Res.string.search_favourites),
            if (ui.moreFavourites > 0) str(Res.string.search_all_favourites) to actions.onAllFavourites else null)
    }
    val n = ui.favourites.size
    itemsIndexed(ui.favourites) { i, h ->
        GroupLine(i)
        val title = h.place.title()
        Box(groupRow(i, n)) {
            CompactRow(h, title, compactLine(h), actions.onPick,
                trailing = { FavouriteStar(ui.starred(h.place), title) { on -> actions.onFavourite(h.place, on) } }) {
                KindIcon(h.place, size = 30)
            }
        }
    }
}
