package com.onpath.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.domain.PinMenuItems
import com.onpath.app.search.Place
import com.onpath.app.search.SearchHit
import com.onpath.app.search.SearchKinds
import com.onpath.app.ui.OnPathColors
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.onpath.strings.*

/**
 * „⌂ Zuhause“ shown – OFF for now (owner 2026-11-19, via the captain: „so wichtig ist das Zuhause eigentlich auch nicht.
 * Können wir das erstmal abschalten, bis wir eine saubere Lösung haben“). false: no Zuhause row in the empty search
 * ([shownHome]) and no „Als Zuhause festlegen“ / „Zuhause entfernen“ in a place's menu ([shownMenuRows]), so nothing
 * leads to the hidden feature. The stored home place, its backup and its browser sync stay untouched, „★ Gemerkt“ stays.
 * true brings both rows back.
 */
const val HOME_ROW_SHOWN = false

/** The Zuhause row of the empty search: none while [HOME_ROW_SHOWN] is off (the stored home stays). */
internal fun shownHome(ui: SearchUi): SearchHit? = if (HOME_ROW_SHOWN) ui.home else null

/** A place menu's rows ([PinMenuItems.of]) without „⌂ Als Zuhause festlegen“ while [HOME_ROW_SHOWN] is off. */
fun shownMenuRows(rows: List<PinMenuItems.Row>): List<PinMenuItems.Row> =
    if (HOME_ROW_SHOWN) rows else rows.filter { it != PinMenuItems.Row.HOME }

/** Callbacks of the search view. */
data class SearchActions(
    val onQuery: (String) -> Unit = {},
    val onCategory: (SearchKinds.Category) -> Unit = {},
    val onClear: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onPick: (Place) -> Unit = {},
    val onGoogle: (String) -> Unit = {},
    val onClearRecent: () -> Unit = {},
    /** „Alle Favoriten ›“. */
    val onAllFavourites: () -> Unit = {},
)

/**
 * Full-screen search (Plan mode only): text field, category chips, ONE mixed result list – offline places, POIs,
 * trails, addresses and online results, best match first; each row keeps its kind icon and says
 * "near your route" / "online" – and a last row that hands the query to Google Maps.
 */
@Composable
fun SearchScreen(ui: SearchUi, actions: SearchActions, modifier: Modifier = Modifier, autoFocus: Boolean = true) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // expected: requestFocus throws if the field is not attached yet (e.g. screen closing); keyboard just stays closed
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(Res.string.common_back))
                }
                TextField(
                    value = ui.query, onValueChange = actions.onQuery,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text(str(Res.string.search_placeholder)) },
                    singleLine = true,
                    trailingIcon = {
                        if (ui.query.isNotEmpty()) IconButton(onClick = actions.onClear) { Icon(Icons.Filled.Close, contentDescription = str(Res.string.search_clear_query)) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchKinds.Category.entries) { c ->
                    FilterChip(selected = ui.category == c, onClick = { actions.onCategory(c) },
                        label = { Text(c.label) }, modifier = Modifier.heightIn(min = 40.dp))
                }
            }
            Spacer(Modifier.size(4.dp))
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                // only the very first time (no index yet); later a new index is built while the old one answers
                if (ui.indexing) item { Preparing() }
                if (!ui.active) recentSection(ui, actions) else resultSections(ui, actions)
            }
        }
    }
}

/**
 * The empty search (owner 2026-11-15, compact sketch LqFT7h7KhEmKRdGJUVtrG9): „⌂ Zuhause“ (one row, only when set and
 * [HOME_ROW_SHOWN] – off since 2026-11-19),
 * „★ FAVORITEN“ (at most five, then „Alle Favoriten ›“), „ZULETZT GESUCHT“ – one-line rows of 48 dp, small upper-case
 * heads. A tap opens the place's menu like any search pick.
 */
private fun LazyListScope.recentSection(ui: SearchUi, actions: SearchActions) {
    val home = shownHome(ui)
    if (ui.recent.isEmpty() && home == null && ui.favourites.isEmpty()) {
        item {
            Hint(str(Res.string.search_intro))
        }
        return
    }
    home?.let { h ->
        item {
            CompactRow(h, str(Res.string.search_home), compactLine(h, withTitle = true), actions.onPick) {
                Icon(Icons.Outlined.Home, null, tint = OnPathColors.ActionEdit)
            }
            HorizontalDivider()
        }
    }
    if (ui.favourites.isNotEmpty()) {
        item {
            SmallHead(str(Res.string.search_favourites), star = true)
        }
        items(ui.favourites) { h ->
            CompactRow(h, h.place.title(), compactLine(h), actions.onPick) {
                Icon(Icons.Filled.Star, null, tint = OnPathColors.Favourite)
            }
        }
        if (ui.moreFavourites > 0) item {
            TextButton(onClick = actions.onAllFavourites, modifier = Modifier.padding(start = 4.dp).heightIn(min = 48.dp)) {
                Text(str(Res.string.search_all_favourites))
            }
        }
    }
    if (ui.recent.isNotEmpty()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SmallHead(str(Res.string.search_recent), padded = false) }
                TextButton(onClick = actions.onClearRecent) { Text(str(Res.string.search_clear_recent)) }
            }
        }
        items(ui.recent) { h ->
            CompactRow(h, h.place.title(), compactLine(h), actions.onPick) {
                // shared into OnPath (Google Maps → Share): the share sign instead of the clock (owner 2026-11-02)
                if (h.place.shared) Icon(Icons.Outlined.Share, contentDescription = str(Res.string.search_recent_shared),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A small upper-case head (12 sp, like My routes' ZULETZT), with the gold star for the favourites. */
@Composable
private fun SmallHead(text: String, star: Boolean = false, padded: Boolean = true) {
    Row((if (padded) Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        if (star) { Icon(Icons.Filled.Star, null, tint = OnPathColors.Favourite, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
    }
}

/** The grey part of a compact row: kind · height · distance (Zuhause: the place's name first). */
internal fun compactLine(h: SearchHit, withTitle: Boolean = false): String = buildList {
    if (withTitle) add(h.place.title()) else add(h.place.kindInfo.label)
    if (!withTitle) h.place.ele?.let { add("$it m") }
    add(Format.distance(h.distanceM))
}.joinToString(" · ")

/**
 * One line, ≥ 48 dp: [name] (16, medium) and the grey [line] (14) beside it – the grey part is cut first, the name only
 * when it alone does not fit.
 */
@Composable
private fun CompactRow(h: SearchHit, name: String, line: String, onPick: (Place) -> Unit, icon: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(h.place) }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(16.dp))
        // the name is measured first (unweighted), the grey part takes what is left
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

private fun LazyListScope.resultSections(ui: SearchUi, actions: SearchActions) {
    val results = ui.results
    // no item keys: two sources may carry the same id, and a duplicate key would crash the list
    items(results) { ResultRow(it, actions.onPick) }
    if (results.isEmpty() && !ui.indexing) {
        item {
            Hint(if (ui.query.isBlank()) str(Res.string.search_nothing_of_kind) else str(Res.string.search_no_match))
        }
    }
    if (ui.online.isNotEmpty()) {
        item {
            Text(str(Res.string.search_online_attribution), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    } else if (ui.onlineState == OnlineState.LOADING) {
        item {
            Text(str(Res.string.search_searching_online), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
    val q = ui.query.trim()
    if (q.isNotEmpty()) {
        item {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { actions.onGoogle(q) }.heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Map, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(str(Res.string.search_in_google, q), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(str(Res.string.search_google_line), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * The very first offline index is being built (no previous one to answer): spinner + grey text where the user is
 * waiting (INFO look, progress = spinner). Stays until the index is ready.
 */
@Composable
private fun Preparing() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(16.dp))
        Text(str(Res.string.search_preparing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Round kind icon in the POI group colour. */
@Composable
fun KindIcon(place: Place, size: Int = 36) {
    val k = place.kindInfo
    Box(Modifier.size(size.dp).background(Color(SearchKinds.argb(place.kind)), CircleShape), contentAlignment = Alignment.Center) {
        Text(k.glyph, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1,
            fontSize = if (k.glyph.length > 1) (size * 0.3).sp else (size * 0.42).sp)
    }
}

/** "Mountain hut · 2 325 m · 3.4 km", "Address · Lana · 1.2 km" (municipality of a village address), "… · online". */
fun resultSubtitle(h: SearchHit): String = buildString {
    append(h.place.kindInfo.label)
    h.place.ele?.let { append(" · $it m") }
    h.place.municipality?.let { append(" · ").append(it) }
    append(" · ").append(Format.distance(h.distanceM))
    if (h.nearRoute) append(" · ").append(Txt.s(Res.string.search_near_route))
    if (h.place.source == Place.Source.ONLINE) append(" · ").append(Txt.s(Res.string.search_online))
}

@Composable
private fun ResultRow(h: SearchHit, onPick: (Place) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(h.place) }.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindIcon(h.place)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(h.place.title(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(resultSubtitle(h), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            h.place.address?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
