package com.onpath.app.ui.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.onpath.app.debug.Problems
import com.onpath.app.poi.PoiLinks
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.GoogleSearchLink
import com.onpath.app.ui.AppViewModel

/** Full-screen search view while [AppViewModel.searchOpen] (Plan mode). Back closes it. */
@Composable
fun SearchHost(vm: AppViewModel) {
    if (!vm.searchOpen) return
    val ctx = LocalContext.current
    val s = vm.search
    BackHandler { vm.closeSearch() }
    SearchScreen(
        s.ui,
        SearchActions(
            // a plan link pasted into the box opens the plan (A-005); anything else is searched
            onQuery = { q -> if (!vm.pastedPlanLink(q)) s.onQuery(q) }, onCategory = s::onCategory, onClear = s::clear,
            onBack = vm::closeSearch, onPick = vm::pickSearchResult,
            onGoogle = { q -> openGoogle(ctx, GoogleSearchLink.url(q)) },
            onClearRecent = s::clearRecent,
        ),
        modifier = Modifier.statusBarsPadding(),
    )
}

/** The place card for the POI / search result in [AppViewModel.planPoi] (Plan mode). */
@Composable
fun PlaceCardHost(vm: AppViewModel) {
    val place = vm.cardPlace ?: return
    val ctx = LocalContext.current
    // Marker for the place while its card is open (search results may have no map symbol).
    DisposableEffect(place.id) {
        val p = GeoPoint(place.lat, place.lon)
        if (place.source != com.onpath.app.search.Place.Source.POI) vm.highlight = p
        onDispose { if (vm.highlight == p) vm.highlight = null }
    }
    PlaceCard(
        vm.placeCardUi(place),
        PlaceCardActions(
            onAdd = { vm.addPlace(place, AppViewModel.PlaceRole.SMART) },
            onRemove = { vm.removePlace(place) },
            onStart = { vm.addPlace(place, AppViewModel.PlaceRole.START) },
            onDestination = { vm.addPlace(place, AppViewModel.PlaceRole.DESTINATION) },
            onGoogle = { com.onpath.app.poi.PoiIntents.openGoogleMaps(ctx, place.poi?.let(PoiLinks::googleMaps)
                ?: PoiLinks.googleMaps(place.name, place.lat, place.lon, label = place.title())) },
            onClose = vm::closePlaceCard,
        ),
    )
}

/** Google Maps app if installed, else the browser. Link-out only. */
fun openGoogle(context: Context, url: String) {
    val uri = Uri.parse(url)
    fun start(i: Intent) = try {
        if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i); true
    } catch (_: ActivityNotFoundException) { false // expected: Google Maps app not installed → try the browser
    } catch (e: Exception) { Problems.warn("search", "Google Maps link could not be opened", e); false }
    if (!(start(Intent(Intent.ACTION_VIEW, uri).setPackage(PoiLinks.GOOGLE_MAPS_PACKAGE)) || start(Intent(Intent.ACTION_VIEW, uri))))
        Problems.warn("search", "No app (Google Maps or browser) could open the Google Maps link")
}
