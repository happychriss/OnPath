package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.search.Place
import com.onpath.app.search.SavedPlaces

/**
 * Where the kept places live (rebuild phase 2 piece 12): the phone's store (`SearchStore` in :app, its file / preferences
 * format untouched), the backup and the browser sync. Every member is ONE call the view model's adapter passes on with
 * exactly the old arguments.
 */
interface PlacesStore {
    /** Zuhause and the favourites as stored now; never throws (an unreadable file reads as nothing kept, reported). */
    fun saved(): SavedPlaces
    /**
     * [change] made on what is stored now – read, change, write in one step, alone among every writer in the process – and
     * the new places returned. Throws when they could not be read or written (nothing changed then).
     */
    fun update(change: (SavedPlaces) -> SavedPlaces): SavedPlaces
    /** The app's backup is told the places changed (`Backups.placesChanged`). */
    fun backupChanged()
    /** The paired browser planner is told (`PlanSync.changed("kept places")`). */
    fun syncChanged()
}

/** What [KeptPlacesSlice] asks about the found place (the search's pin) – the search's state stays in the search flow. */
interface KeptPlacesHost {
    /** The found place on the map (its pin), null = none. */
    fun searchMark(): Place?
    /** [poi] is the found place's pin. */
    fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean
}

/**
 * The kept places: Zuhause + favourites (owner 2026-11-15, „Orte merken“; core feature C19; [SavedPlaces]). Read once from the
 * phone; every change is made on what is stored at that moment, saved at once, backed up and sent to a paired browser. The
 * rules are [SavedPlaces] (:core). Constructed with the view model (no work).
 */
class KeptPlacesSlice(private val store: PlacesStore, private val host: KeptPlacesHost) {
    /**
     * Zuhause and the favourite places as last read or written here, null = read again at the next use (a restored backup or
     * a browser sync may have brought places – those two set it to null).
     */
    var savedPlaces by mutableStateOf<SavedPlaces?>(null)

    private fun saved(): SavedPlaces = savedPlaces ?: store.saved().also { savedPlaces = it }

    /** The place a held menu is about: the found place itself (address, kind) for its pin, else the map's POI. */
    private fun placeOf(poi: com.onpath.app.poi.Poi): Place =
        host.searchMark()?.takeIf { host.isCandidate(poi) } ?: Place.fromPoi(poi)

    fun isFavouritePlace(poi: com.onpath.app.poi.Poi): Boolean = saved().isFavourite(placeOf(poi))
    fun isHomePlace(poi: com.onpath.app.poi.Poi): Boolean = saved().isHome(placeOf(poi))

    /** Whether [p] is kept – a named route point's „Merken“ / „Gemerkt“ (owner 2026-11-26, [Place.ofRoutePoint]). */
    fun isFavourite(p: Place): Boolean = saved().isFavourite(p)

    /** „☆ Merken“ / „★ Gemerkt“ in a held place's menu: one tap sets / removes; the menu stays and shows the new state. */
    fun toggleFavouritePlace(poi: com.onpath.app.poi.Poi) {
        val p = placeOf(poi)
        setFavourite(p, !saved().isFavourite(p))
    }

    /**
     * The place [p] is / is no longer a favourite – the target state, not a toggle (the planner's rows say what they
     * want; a row drawn before a sync brought the same change then changes nothing).
     */
    fun setFavourite(p: Place, on: Boolean) {
        keepPlaces { s -> if (s.isFavourite(p) != on) s.toggleFavourite(p) else s }
        Problems.trace("search", "Place menu: \"${p.title()}\" ${if (on) "kept as a favourite" else "no longer a favourite"}")
    }

    /** Zuhause is [p] (replaces the old home); null = no home any more. The target state, as [setFavourite]. */
    fun setHome(p: Place?) {
        keepPlaces { s -> s.withHome(p) }
        Problems.trace("search", if (p != null) "Place menu: \"${p.title()}\" set as home" else "Place menu: home removed")
    }

    /** „⌂ Als Zuhause festlegen“ (replaces the old home) / „⌂ Zuhause entfernen“ on the place that is home. */
    fun toggleHomePlace(poi: com.onpath.app.poi.Poi) {
        val p = placeOf(poi)
        val remove = saved().isHome(p)
        keepPlaces { s -> s.withHome(if (remove) null else p) }
        Problems.trace("search", "Place menu: \"${p.title()}\" ${if (remove) "no longer home" else "set as home"}")
    }

    /**
     * One change of the kept places, made on what is stored now (read, change, write in one step – the browser sync may
     * have brought places since this screen read them; web-sync.md §5.4); then backed up and sent to a paired browser.
     */
    private fun keepPlaces(change: (SavedPlaces) -> SavedPlaces) {
        savedPlaces = try { store.update(change = change) } catch (e: Exception) {
            Problems.error("search", "Favourite places / Zuhause could not be saved", e); return
        }
        store.backupChanged()
        store.syncChanged()
    }
}
