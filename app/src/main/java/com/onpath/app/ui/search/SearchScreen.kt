package com.onpath.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.ui.semantics.Role
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
 * A place menu's rows ([PinMenuItems.of]) in the app: never „⌂ Als Zuhause festlegen“ – the app has no Zuhause (off since
 * 2026-11-19, removed 2026-11-28: a home kept before is a favourite now, SearchStore). The planner still shows it (its own
 * menu; the owner decides there).
 */
fun shownMenuRows(rows: List<PinMenuItems.Row>): List<PinMenuItems.Row> = rows.filter { it != PinMenuItems.Row.HOME }

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
 * Full-screen search (Plan mode only), Apple-style (owner 2026-11-27, variant A): a grey rounded field with 🔍 and
 * „Abbrechen“; under it the categories as one row of round coloured signs ([CATEGORY_ROW], the map's POI colours) –
 * always, while typing too (a filter on the typed text); favourites, recent searches and the hits in light rounded groups, every row
 * with its kind's coloured sign; ONE mixed result list – offline places, POIs, trails, addresses and online results,
 * best match first, each row saying "near your route" / "online"; and one fixed row above the keyboard that hands the
 * query to Google Maps („Google Maps ↗“).
 */
@Composable
fun SearchScreen(ui: SearchUi, actions: SearchActions, modifier: Modifier = Modifier, autoFocus: Boolean = true) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // expected: requestFocus throws if the field is not attached yet (e.g. screen closing); keyboard just stays closed
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // the page fills the status bar too – no map strip above the field
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(ui.query, actions, Modifier.weight(1f).focusRequester(focus)) { focusManager.clearFocus() }
                TextButton(onClick = actions.onBack) { Text(str(Res.string.common_cancel), style = MaterialTheme.typography.bodyLarge) }
            }
            // the categories always – while typing too: a category filters the typed text, a second tap undoes it, nothing to
            // clear first (owner 2026-11-27)
            CategoryRow(ui.category, actions.onCategory)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                // only the very first time (no index yet); later a new index is built while the old one answers
                if (ui.indexing) item { Preparing() }
                if (!ui.active) recentSection(ui, actions) else resultSections(ui, actions)
            }
            // one fixed row above the keyboard, whatever the hits (owner 2026-11-27): „Google Maps ↗“ with the query
            HorizontalDivider(color = OnPathColors.Line)
            GoogleMapsRow { actions.onGoogle(ui.query.trim()) }
        }
    }
}

/** The field: a grey rounded box (the theme's deeper sage), 🔍, the text, ⊗ to clear it – no underline, 48 dp high. */
@Composable
private fun SearchField(query: String, actions: SearchActions, modifier: Modifier, onSearch: () -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = query, onValueChange = actions.onQuery, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(OnPathColors.Green),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(OnPathColors.PaperDeep, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(str(Res.string.search_placeholder), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (query.isNotEmpty()) IconButton(onClick = actions.onClear, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Cancel, contentDescription = str(Res.string.search_clear_query),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        },
    )
}

/** The categories in the owner's order (2026-11-27): [SearchKinds.Category] itself – app row and planner chips alike. */
internal val CATEGORY_ROW = SearchKinds.Category.entries

/** A category's sign and colour: the map's colour of a kind it shows. */
private fun SearchKinds.Category.sign(): Pair<androidx.compose.ui.graphics.vector.ImageVector, Long> = when (this) {
    SearchKinds.Category.PLACES -> Icons.Filled.Place to SearchKinds.argb("place")
    SearchKinds.Category.SIGHTS -> Icons.Filled.Landscape to SearchKinds.argb("viewpoint")
    SearchKinds.Category.FOOD -> Icons.Filled.Restaurant to SearchKinds.argb("restaurant")
    SearchKinds.Category.WATER -> Icons.Filled.WaterDrop to SearchKinds.argb("water")
    SearchKinds.Category.TRANSIT -> Icons.Filled.Train to SearchKinds.argb("train")
    SearchKinds.Category.PARKING -> Icons.Filled.LocalParking to SearchKinds.argb("parking")
    SearchKinds.Category.SLEEP -> Icons.Filled.Hotel to SearchKinds.argb("hotel")
}

/**
 * One row of round coloured signs with a word under each (Apple Maps' „In der Nähe“): all seven side by side when each
 * gets ≥ 52 dp and the font is not enlarged, else a row to swipe – never a cut word. The chosen one: a green ring,
 * the others paler.
 */
@Composable
private fun CategoryRow(selected: SearchKinds.Category?, onPick: (SearchKinds.Category) -> Unit) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = CategoryWord.copy(fontWeight = FontWeight.Bold)
    val words = CATEGORY_ROW.map { it.label }
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        // side by side when every sign keeps its own width (≥ 40 dp, or its word's) with ≥ 2 dp between them – the long
        // word („Sehenswert“) borrows room from the short ones (fits 360 dp phones); else a row to swipe, never a cut word
        val widths = with(density) { words.map { maxOf(40.dp, measurer.measure(it, style).size.width.toDp() + 2.dp) } }
        val fits = widths.fold(0.dp) { a, w -> a + w } + 2.dp * (CATEGORY_ROW.size - 1) <= maxWidth
        if (fits) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CATEGORY_ROW.forEachIndexed { i, c -> CategorySign(c, selected, onPick, Modifier.width(widths[i])) }
        } else androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(CATEGORY_ROW) { c -> CategorySign(c, selected, onPick, Modifier.widthIn(min = 64.dp)) }
        }
    }
}

/** The word under a sign: the caption size, no extra letter spacing (the seven must fit a 360 dp phone). */
private val CategoryWord @Composable get() = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)

@Composable
private fun CategorySign(c: SearchKinds.Category, selected: SearchKinds.Category?, onPick: (SearchKinds.Category) -> Unit, modifier: Modifier) {
    val (icon, argb) = c.sign()
    val on = selected == c
    val dim = selected != null && !on
    Column(modifier.selectable(on, role = Role.Tab) { onPick(c) }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        // the ring may stand out of a narrow cell by 2 dp each side (requiredSize) – the gaps take it
        Box(Modifier.requiredSize(44.dp).then(if (on) Modifier.border(2.dp, OnPathColors.Green, CircleShape) else Modifier),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(38.dp).background(Color(argb).copy(alpha = if (dim) 0.45f else 1f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Text(c.label, style = CategoryWord, maxLines = 1, softWrap = false,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            color = if (on) OnPathColors.Green else MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Where a row sits in its rounded group: its shape (the first rounds the top, the last the bottom) – the groups of the
 * empty search and of the hits ([groupRow]).
 */
private fun groupShape(i: Int, n: Int): RoundedCornerShape {
    val top = if (i == 0) 16.dp else 0.dp
    val bottom = if (i == n - 1) 16.dp else 0.dp
    return RoundedCornerShape(top, top, bottom, bottom)
}

/** A row's frame in a light rounded group: 12 dp from the screen's edge, a fine line above every row but the first. */
@Composable
private fun groupRow(i: Int, n: Int): Modifier {
    val shape = groupShape(i, n)
    return Modifier.padding(horizontal = 12.dp).clip(shape).background(OnPathColors.CardPaper, shape)
}

@Composable
private fun GroupLine(i: Int) { if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp).padding(start = 58.dp), color = OnPathColors.Line) }

/**
 * The empty search (owner 2026-11-15 compact sketch; 2026-11-27 variant A): „Favoriten“ (at most five, „Alle Favoriten“ in its head), „Zuletzt gesucht“
 * with „Löschen“ – one-line rows of 48 dp in light groups, each with its kind's coloured sign. A tap opens the place's
 * menu like any search pick.
 */
private fun LazyListScope.recentSection(ui: SearchUi, actions: SearchActions) {
    // nothing kept yet: an empty list – no explaining text (owner 2026-11-27)
    if (ui.recent.isEmpty() && ui.favourites.isEmpty()) return
    if (ui.favourites.isNotEmpty()) {
        item {
            GroupHead(str(Res.string.search_favourites),
                if (ui.moreFavourites > 0) str(Res.string.search_all_favourites) to actions.onAllFavourites else null)
        }
        val n = ui.favourites.size
        itemsIndexed(ui.favourites) { i, h ->
            GroupLine(i)
            Box(groupRow(i, n)) { CompactRow(h, h.place.title(), compactLine(h), actions.onPick) { KindIcon(h.place, size = 30) } }
        }
    }
    if (ui.recent.isNotEmpty()) {
        item { GroupHead(str(Res.string.search_recent), str(Res.string.search_clear_recent) to actions.onClearRecent) }
        val n = ui.recent.size
        itemsIndexed(ui.recent) { i, h ->
            GroupLine(i)
            Box(groupRow(i, n)) {
                CompactRow(h, h.place.title(), compactLine(h), actions.onPick) {
                    // shared into OnPath (Google Maps → Share): the share sign instead of the kind (owner 2026-11-02)
                    if (h.place.shared) Box(Modifier.size(30.dp).background(OnPathColors.PaperDeep, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Share, contentDescription = str(Res.string.search_recent_shared),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    } else KindIcon(h.place, size = 30)
                }
            }
        }
    }
}

/** A group's head: the title (16, bold, sentence case – like Apple Maps) and an optional green word at the end. */
@Composable
private fun GroupHead(text: String, action: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
        action?.let { (word, onClick) -> TextButton(onClick = onClick) { Text(word) } }
    }
}

/** The grey part of a compact row: kind · height · distance (Zuhause: the place's name first). */
internal fun compactLine(h: SearchHit, withTitle: Boolean = false): String = buildList {
    if (withTitle) add(h.place.title()) else add(h.place.kindInfo.label)
    if (!withTitle) h.place.ele?.let { add("$it m") }
    add(Format.distance(h.distanceM))
}.joinToString(" · ")

/**
 * One line, ≥ 48 dp: the sign, [name] (16, medium) and the grey [line] (14) beside it – the grey part is cut first, the
 * name only when it alone does not fit.
 */
@Composable
private fun CompactRow(h: SearchHit, name: String, line: String, onPick: (Place) -> Unit, icon: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(h.place) }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { icon() }
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
    item { Spacer(Modifier.height(4.dp)) }
    // no item keys: two sources may carry the same id, and a duplicate key would crash the list
    itemsIndexed(results) { i, h ->
        GroupLine(i)
        Box(groupRow(i, results.size)) { ResultRow(h, actions.onPick) }
    }
    if (results.isEmpty() && !ui.indexing) {
        item {
            Hint(if (ui.query.isBlank()) str(Res.string.search_nothing_of_kind) else str(Res.string.search_no_match))
        }
    }
    if (ui.online.isNotEmpty()) {
        item {
            Text(str(Res.string.search_online_attribution), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
    } else if (ui.onlineState == OnlineState.LOADING) {
        item {
            Text(str(Res.string.search_searching_online), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
    }
}

/**
 * „Google Maps ↗“ (owner 2026-11-27): one row, fixed under the list and above the keyboard – hands the query to Google
 * Maps; green words in the middle (variant A), no button look, no way-back hint. The ↗ says it leaves the app.
 */
@Composable
private fun GoogleMapsRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Map, contentDescription = null, tint = OnPathColors.Green, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(str(Res.string.search_google_maps), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            color = OnPathColors.Green, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = OnPathColors.Green, modifier = Modifier.size(18.dp))
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
        Modifier.fillMaxWidth().clickable { onPick(h.place) }.heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 8.dp),
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
