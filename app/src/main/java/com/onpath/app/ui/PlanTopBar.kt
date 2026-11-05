package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * App-level actions in the ☰ menu next to the search bar (owner: the search bar only searches; everything about
 * the current route lives in the route sheet). Each item is enabled only when it makes sense ([state]).
 */
class PlanMenu(
    val state: RouteMenuState = RouteRules.menu(hasRoute = false, pointCount = 0, savedCount = 0, navigating = false, recording = false),
    val onManage: () -> Unit = {},
    val onSettings: () -> Unit = {},
    /** Called when the menu opens (refresh the saved-routes count). */
    val onOpened: () -> Unit = {},
    /** Null hides "Check for updates" (updates switched off / not available in this build). */
    val onUpdates: (() -> Unit)? = null,
    /** "Pair with browser" / "Paired with browser ✓" (plan sync with the web planner); null hides it. */
    val onPairBrowser: (() -> Unit)? = null,
    /** What the sync item shows; a [SyncMenuUi.warning] also puts the amber dot on ☰ (owner 2026-10-30). */
    val sync: SyncMenuUi = SyncMenuUi(),
    /**
     * "Phone setup" as the first item, red with ⚠ (owner 2026-10-30): only while a tour needs something from the phone
     * (SettingsSummary.phoneNeeded); null hides it.
     */
    val onPhoneSetup: (() -> Unit)? = null,
    /**
     * "Save route" on top (owner 2026-10-31: the one place of Save – the sheet has none): whenever there is a plan
     * (RouteRules.saveInMenu); it opens Save's name dialog. Null hides it.
     */
    val onSaveRoute: (() -> Unit)? = null,
    /** The plan has unsaved changes (RouteRules.saveAtHand): a small green dot on ☰ (an amber sync dot wins). */
    val unsaved: Boolean = false,
)

/**
 * How the plan's top bar looks: the search pill's and ☰'s fill, a ring round the mode label. The app uses the default;
 * only the screenshot proposals (owner 2026-11-05: a band behind the top bar) set another.
 */
data class TopBarLook(val pill: androidx.compose.ui.graphics.Color = OnPathColors.PlanTint,
                      val labelRing: androidx.compose.ui.graphics.Color? = null)

val LocalTopBarLook = androidx.compose.runtime.staticCompositionLocalOf { TopBarLook() }

/**
 * Plan mode top area: the search pill (only search) and one round ☰ app-menu button next to it: Save route (with a
 * plan) · My routes · Start recording · Pair with browser · Settings; Phone setup first while a tour needs it. The
 * activity and the route actions (Close plan · Reverse · Round trip) live in the route sheet (PlanPanel); Save is here.
 */
@Composable
fun PlanTopBar(
    onSearch: () -> Unit,
    menu: PlanMenu,
    modifier: Modifier = Modifier,
    menuInitiallyOpen: Boolean = false,
    /** During a tour ("Plan ändern"): the mode label says so instead of "Planen". */
    editing: Boolean = false,
    /** The route's offline state ("Ohne Netz ✓ / ⟳ / ⚠" in the label, owner 2026-11-03); null = no route. */
    offline: OfflineReady.State? = null,
    /** A tap on the label: explain the offline state (a line in the plan sheet). */
    onOffline: () -> Unit = {},
    /**
     * Proposal 2026-11-05 (owner OK for pictures, not built yet): during a tour "‹ Zur Tour" top left (Android's up
     * navigation) with the mode label beside it, the search pill + ☰ in a row under it; null = today.
     */
    onBackToTour: (() -> Unit)? = null,
) {
    if (editing && onBackToTour != null) {
        Column(modifier.padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(onClick = onBackToTour,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 4.dp, end = 12.dp),
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = OnPathColors.PlanAccent, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(str(Res.string.plan_back_to_tour), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = OnPathColors.PlanAccent, maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.weight(1f))
                PlanModeLabel(true, Modifier, offline, onOffline)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchBarPill(onSearch, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                AppMenuButton(menu, menuInitiallyOpen)
            }
        }
        return
    }
    Box(modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // also while the route is changed during a tour: search + ☰ as at home (owner 2026-11-01 turned down a green
            // "Plan ändern … Fertig" header)
            SearchBarPill(onSearch, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AppMenuButton(menu, menuInitiallyOpen)
        }
        // Plan is blue (owner 2026-11-03, design §3 version 3): a small blue label on the pill's top edge – "PLANEN",
        // during a tour "ROUTE ÄNDERN"; the word helps in the sun and with colour blindness
        PlanModeLabel(editing, Modifier.align(Alignment.TopStart).padding(start = 28.dp), offline, onOffline)
    }
}

/**
 * "PLANEN" / "ROUTE ÄNDERN" in white on [OnPathColors.PlanAccent] (white 5.7:1), over the search pill's top edge. With a
 * route it also says whether it works without a network (owner 2026-11-03, comparison card 3): "PLANEN · Ohne Netz ✓"
 * (green), "⟳" (loading), "⚠" (amber: something missing, nothing loading) – a tap explains it ([OfflineReady.explain]).
 */
@Composable
internal fun PlanModeLabel(editing: Boolean, modifier: Modifier = Modifier, offline: OfflineReady.State? = null,
                           onOffline: () -> Unit = {}) {
    val mode = str(if (editing) Res.string.plan_slide_change_route else Res.string.plan_mode_label).uppercase()
    val style = MaterialTheme.typography.labelSmall
    if (offline == null) {
        Text(mode, style = style, fontWeight = FontWeight.Bold, color = OnPathColors.CardWhite, maxLines = 1,
            modifier = modifier.clearAndSetSemantics { }.labelRing().background(OnPathColors.PlanAccent, CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp))
        return
    }
    val desc = str(when (offline) {
        OfflineReady.State.READY -> Res.string.plan_offline_desc_ready
        OfflineReady.State.LOADING -> Res.string.plan_offline_desc_loading
        OfflineReady.State.MISSING -> Res.string.plan_offline_desc_missing
    })
    Row(modifier.labelRing().clip(CircleShape).background(OnPathColors.PlanAccent, CircleShape)
        .clickable(role = Role.Button, onClick = onOffline)
        .clearAndSetSemantics { contentDescription = "$mode · $desc"; role = Role.Button }
        .padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$mode · ${OfflineReady.pillWord}", style = style, fontWeight = FontWeight.Bold, color = OnPathColors.CardWhite,
            maxLines = 1)
        Spacer(Modifier.width(3.dp))
        val (icon, tint) = when (offline) {
            OfflineReady.State.READY -> Icons.Filled.Check to OnPathColors.OnPlanGood
            OfflineReady.State.LOADING -> Icons.Filled.Sync to OnPathColors.CardWhite
            OfflineReady.State.MISSING -> Icons.Filled.Warning to OnPathColors.OnPlanWarn
        }
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun SearchBarPill(onSearch: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onSearch,
        modifier = modifier.height(56.dp).semantics { contentDescription = Txt.s(Res.string.plan_search_hint); role = Role.Button },
        shape = CircleShape,
        color = LocalTopBarLook.current.pill, // Plan is blue (owner 2026-11-03)
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = OnPathColors.PlanAccent)
            Spacer(Modifier.width(12.dp))
            Text(str(Res.string.plan_search_hint), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AppMenuButton(menu: PlanMenu, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        Surface(onClick = { menu.onOpened(); open = true }, shape = CircleShape, color = LocalTopBarLook.current.pill,
            shadowElevation = 3.dp, modifier = Modifier.size(56.dp).semantics { contentDescription = Txt.s(Res.string.plan_menu) }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Menu, contentDescription = null, tint = OnPathColors.PlanAccent) }
        }
        // amber dot: the browser sync needs a look (never for plain offline) – the ☰ item says why; else a green dot while
        // the plan has unsaved changes (Save route is the top item)
        val dot = if (menu.onPairBrowser != null && menu.sync.warning != null) OnPathColors.Warn
            else if (menu.unsaved && menu.onSaveRoute != null) OnPathColors.Green else null
        if (dot != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp)
            .size(12.dp).background(dot, CircleShape).border(2.dp, LocalTopBarLook.current.pill, CircleShape))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(0.dp, 4.dp)) {
            PlanMenuItems(menu, close = { open = false })
        }
    }
}

/** The items of the ☰ menu (also rendered on their own by the screenshot tests). */
@Composable
internal fun PlanMenuItems(menu: PlanMenu, close: () -> Unit) {
    val st = menu.state
    menu.onPhoneSetup?.let { p ->
        MenuItem(Icons.Outlined.WarningAmber, str(Res.string.settings_page_phone), enabled = st.settings, tint = OnPathColors.Error) { close(); p() }
        HorizontalDivider()
    }
    // "Meine Routen" always first, Save route under it (owner 2026-11-01: otherwise one gets confused)
    MenuItem(Icons.Outlined.FolderOpen, str(Res.string.plan_menu_my_routes), enabled = st.manage) { close(); menu.onManage() }
    menu.onSaveRoute?.let { save ->
        MenuItem(Icons.Outlined.Save, str(Res.string.plan_save_route), enabled = st.save) { close(); save() }
    }
    // no "Aufzeichnung starten" here (owner 2026-11-02, onpath.private#15): the green start button in Plan records
    // without a route ("Aufnahme starten · ‹Aktivität›", PlanStart.RECORD_ONLY)
    HorizontalDivider()
    menu.onPairBrowser?.let { p -> SyncMenuItem(menu.sync, enabled = st.settings) { close(); p() } }
    MenuItem(Icons.Outlined.Settings, str(Res.string.common_settings), enabled = st.settings) { close(); menu.onSettings() }
    menu.onUpdates?.let { u -> MenuItem(Icons.Outlined.SystemUpdate, str(Res.string.plan_menu_check_updates), enabled = st.updates) { close(); u() } }
}

/**
 * "Pair with browser" (unpaired in the browser: + grey "Unpaired in the browser"); paired: "Paired with browser ✓" + grey
 * "<device> ↔ <browser> · synced 1 min ago"; the browser not seen yet: no ✓ + grey "Waiting for the browser …"; a
 * problem: amber ⚠ + why.
 */
@Composable
private fun SyncMenuItem(ui: SyncMenuUi, enabled: Boolean, onClick: () -> Unit) {
    val warn = ui.warning != null
    val label = if (!ui.paired) str(Res.string.sync_menu_pair) else str(Res.string.sync_menu_paired) + if (warn || ui.waiting) "" else " ✓"
    DropdownMenuItem(
        text = {
            Column {
                Text(label)
                ui.line?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (warn) OnPathColors.Warn else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        enabled = enabled, onClick = onClick,
        leadingIcon = { Icon(if (warn) Icons.Outlined.WarningAmber else Icons.Outlined.Sync, contentDescription = null) },
        colors = if (!warn) MenuDefaults.itemColors() else MenuDefaults.itemColors(textColor = OnPathColors.Warn, leadingIconColor = OnPathColors.Warn),
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

@Composable
internal fun MenuItem(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) }, enabled = enabled, onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
        colors = if (tint == null) MenuDefaults.itemColors() else MenuDefaults.itemColors(textColor = tint, leadingIconColor = tint),
        modifier = Modifier.heightIn(min = 48.dp),
    )
}


/**
 * Plan is blue (owner 2026-11-03, design page §3 version 3 "deutlich" – Plan only, Navigate unchanged): a soft blue edge
 * at the screen's left, right and bottom ([OnPathColors.PlanEdge]); no band at the top (owner 2026-11-03: "die
 * dunkelblaue Leiste oben … kann wieder weg"). Only drawn – no pointer input, the map under it takes every touch.
 */
@Composable
fun PlanFrame(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.fillMaxSize()) {
        val edge = PLAN_EDGE.toPx()
        val clear = androidx.compose.ui.graphics.Color.Transparent
        val e = OnPathColors.PlanEdge
        // the soft edge: from the screen's left, right and bottom edges inwards (nothing at the top)
        drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(e, clear), 0f, edge),
            size = androidx.compose.ui.geometry.Size(edge, size.height))
        drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(clear, e), size.width - edge, size.width),
            topLeft = androidx.compose.ui.geometry.Offset(size.width - edge, 0f), size = androidx.compose.ui.geometry.Size(edge, size.height))
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(clear, e), size.height - edge, size.height),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - edge), size = androidx.compose.ui.geometry.Size(size.width, edge))
    }
}

/** Width of the soft blue edge. */
private val PLAN_EDGE = 14.dp

/** The mode label's ring ([TopBarLook.labelRing]): none in the app. */
@Composable
private fun Modifier.labelRing(): Modifier =
    LocalTopBarLook.current.labelRing?.let { this.border(1.5.dp, it, CircleShape) } ?: this
