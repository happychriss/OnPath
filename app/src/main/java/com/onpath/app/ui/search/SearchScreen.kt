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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.onpath.app.poi.Poi
import com.onpath.app.search.Place
import com.onpath.app.search.SearchHit
import com.onpath.app.search.SearchKinds

/** Callbacks of the search view. */
data class SearchActions(
    val onQuery: (String) -> Unit = {},
    val onCategory: (SearchKinds.Category) -> Unit = {},
    val onClear: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onPick: (Place) -> Unit = {},
    val onGoogle: (String) -> Unit = {},
    val onClearRecent: () -> Unit = {},
)

/**
 * Full-screen search (Plan mode only): text field, category chips, results ("Near your route" first,
 * then the offline map, then online results), and a last row that hands the query to Google Maps.
 */
@Composable
fun SearchScreen(ui: SearchUi, actions: SearchActions, modifier: Modifier = Modifier, autoFocus: Boolean = true) {
    val de = Poi.isGerman()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                TextField(
                    value = ui.query, onValueChange = actions.onQuery,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Search places or addresses") },
                    singleLine = true,
                    trailingIcon = {
                        if (ui.query.isNotEmpty()) IconButton(onClick = actions.onClear) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
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
                        label = { Text(c.label(de)) }, modifier = Modifier.heightIn(min = 40.dp))
                }
            }
            Spacer(Modifier.size(4.dp))
            HorizontalDivider()
            if (ui.indexing) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (!ui.active) recentSection(ui, actions) else resultSections(ui, actions)
            }
        }
    }
}

private fun LazyListScope.recentSection(ui: SearchUi, actions: SearchActions) {
    if (ui.recent.isEmpty()) {
        item {
            Hint("Find huts, peaks, villages, trails and addresses. The offline map is searched first; " +
                "online results are added when you are connected.")
        }
        return
    }
    item {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onClearRecent) { Text("Clear") }
        }
    }
    items(ui.recent) { h -> ResultRow(h, actions.onPick, recent = true) }
}

private fun LazyListScope.resultSections(ui: SearchUi, actions: SearchActions) {
    val near = ui.hits.filter { it.nearRoute }
    val rest = ui.hits.filter { !it.nearRoute }
    if (near.isNotEmpty()) {
        item { Header("Near your route") }
        items(near) { ResultRow(it, actions.onPick) }
    }
    if (rest.isNotEmpty()) {
        if (near.isNotEmpty() || ui.online.isNotEmpty()) item { Header("Offline map") }
        items(rest) { ResultRow(it, actions.onPick) }
    }
    if (ui.hits.isEmpty() && !ui.indexing) {
        item {
            Hint(if (ui.query.isBlank()) "Nothing of this kind in the offline map."
            else "No match in the offline map – try online results or Google Maps.")
        }
    }
    if (ui.online.isNotEmpty()) {
        item { Header("Online · Photon, © OpenStreetMap") }
        items(ui.online) { ResultRow(it, actions.onPick) }
    } else if (ui.onlineState == OnlineState.LOADING) {
        item {
            Text("Searching online…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Text("Search “$q” in Google Maps", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Opens Google Maps – shops, opening times, reviews", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
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

/** "Mountain hut · 2 325 m · 3.4 km" */
fun resultSubtitle(h: SearchHit, german: Boolean = Poi.isGerman()): String = buildString {
    append(h.place.kindInfo.label(german))
    h.place.ele?.let { append(" · $it m") }
    append(" · ").append(Format.distance(h.distanceM))
}

@Composable
private fun ResultRow(h: SearchHit, onPick: (Place) -> Unit, recent: Boolean = false) {
    val de = Poi.isGerman()
    Row(
        Modifier.fillMaxWidth().clickable { onPick(h.place) }.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (recent) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else KindIcon(h.place)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(h.place.title(de), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(resultSubtitle(h, de), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            h.place.address?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
