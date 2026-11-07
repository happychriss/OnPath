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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RadioButtonChecked
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
import androidx.compose.ui.layout.layout

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
    /**
     * "Neu planen" in ☰ (owner 2026-11-06: "habe ich oben im Menü gesucht") – the same way as the sheet's ✕ pill (which
     * stays); null hides it (no plan points).
     */
    val onNewPlan: (() -> Unit)? = null,
    /**
     * "Ohne Plan aufzeichnen" (owner 2026-11-06, flow map H1: "Los geht's ohne Route" in the sheet was confusing – in ☰,
     * rarely needed): a plain recording; null hides it (a tour runs).
     */
    val onRecordWithoutPlan: (() -> Unit)? = null,
) {
    /**
     * The dot on ☰: only the browser sync's amber warning. Unsaved has no sign any more (owner 2026-11-06: nothing is
     * lost – a plan leaving the map goes under My routes → Zuletzt).
     */
    val dotWarn: Boolean get() = onPairBrowser != null && sync.warning != null
}

/**
 * Plan's top band (owner 2026-11-05, band b): a light blue band behind the search pill and ☰, so they do not float over
 * the map; at a large font the mode label gets its own line above the pill instead of covering its text. During a tour
 * the way back to Navigate is the green "Navigation ›" at the bottom of the plan (owner 2026-11-06, C).
 */
object PlanTopBand {
    /** The band's colour: paper (owner 2026-11-06 variant C – no light blue surfaces; was the plan pills' light blue). */
    val Color = OnPathColors.Paper
    /** The mode label's own line at a large font: its text line (labelSmall, 16 sp line) + 8 dp. */
    fun labelRow(fontScale: Float): androidx.compose.ui.unit.Dp = (16f * fontScale + 8f).dp

    /** From this font scale on the mode label would cover the search text: it gets its own line. */
    fun largeLabel(fontScale: Float): Boolean = fontScale >= 1.3f

    /** Bottom of the top bar below the status bar: one row (also during a tour), + the label line at a large font. */
    fun bottom(fontScale: Float): androidx.compose.ui.unit.Dp =
        TOP_BAR_BOTTOM + if (largeLabel(fontScale)) labelRow(fontScale) else 0.dp

    /** Where the mode label starts: over the search pill's left part. */
    val LABEL_START = 28.dp
}

/** [PlanTopBand.bottom] in dp for the current font. */
@Composable
fun planTopBarDp(): Int = PlanTopBand.bottom(androidx.compose.ui.platform.LocalDensity.current.fontScale).value.toInt()

/**
 * Plan mode top area on the light blue band: the search pill (only search) and one round ☰ app-menu button next to it:
 * Save route (with a plan) · My routes · Pair with browser · Settings; Phone setup first while a tour needs it. The
 * activity is a button on the map, the route actions (✕ Neu planen · Umkehren · Rundtour) live in the plan sheet (PlanPanel).
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
) {
    // during a tour the way back to Navigate is the green "Navigation ›" at the bottom (owner 2026-11-06, C) – no "←" here
    val row = @Composable {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // also while the plan is changed during a tour: search + ☰ as at home (owner 2026-11-01 turned down a green
            // "Plan ändern … Fertig" header)
            SearchBarPill(onSearch, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AppMenuButton(menu, menuInitiallyOpen)
        }
    }
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    // large font: the label in its own line above the pill (on the pill's edge it covered "Orte oder …")
    // the mode label (owner 2026-11-06: no "Nicht gespeichert" sign beside it any more – Zuletzt keeps every plan)
    val labels = @Composable { m: Modifier ->
        Row(m, verticalAlignment = Alignment.Top) { PlanModeLabel(editing, Modifier, offline, onOffline) }
    }
    if (PlanTopBand.largeLabel(fontScale)) {
        Column(modifier.height(PlanTopBand.bottom(fontScale))) {
            labels(Modifier.padding(start = PlanTopBand.LABEL_START, top = 6.dp))
            row()
        }
        return
    }
    Box(modifier) {
        row()
        // Plan is blue (owner 2026-11-03, design §3 version 3): a small blue label on the pill's top edge – "PLANEN",
        // during a tour "PLAN ÄNDERN"; the word helps in the sun and with colour blindness
        labels(Modifier.align(Alignment.TopStart).padding(start = PlanTopBand.LABEL_START))
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
    // always "PLANEN", also during a tour (owner 2026-11-06: one word for the plan mode; was "PLAN ÄNDERN")
    val mode = str(Res.string.plan_mode_label).uppercase()
    val style = MaterialTheme.typography.labelSmall
    if (offline == null) {
        Text(mode, style = style, fontWeight = FontWeight.Bold, color = OnPathColors.CardWhite, maxLines = 1,
            modifier = modifier.clearAndSetSemantics { }.background(OnPathColors.PlanAccent, CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp))
        return
    }
    val desc = str(when (offline) {
        OfflineReady.State.READY -> Res.string.plan_offline_desc_ready
        OfflineReady.State.LOADING -> Res.string.plan_offline_desc_loading
        OfflineReady.State.MISSING -> Res.string.plan_offline_desc_missing
    })
    Row(modifier.clip(CircleShape).background(OnPathColors.PlanAccent, CircleShape)
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
        modifier = modifier.height(56.dp).semantics { contentDescription = Txt.s(Res.string.plan_search_hint); role = Role.Button }
            .warmShadow(OnPathShadow.LOW, CircleShape),
        shape = CircleShape,
        color = OnPathColors.Paper, // paper on the paper band – the warm shadow lifts it (owner 2026-11-06 variant C)
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
        Surface(onClick = { menu.onOpened(); open = true }, shape = CircleShape, color = OnPathColors.Paper, modifier = Modifier.size(56.dp).semantics { contentDescription = Txt.s(Res.string.plan_menu) }.warmShadow(OnPathShadow.LOW, CircleShape)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Menu, contentDescription = null, tint = OnPathColors.PlanAccent) }
        }
        // amber dot: the browser sync needs a look (never for plain offline) – the ☰ item says why. Unsaved changes have
        // their own sign beside the mode label (owner 2026-11-06, #41 B), no dot
        val dot = if (menu.dotWarn) OnPathColors.Warn else null
        if (dot != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp)
            .size(12.dp).background(dot, CircleShape).border(2.dp, OnPathColors.CardWhite, CircleShape))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(0.dp, 4.dp)) {
            PlanMenuItems(menu, close = { open = false })
        }
    }
}

/** The items of the ☰ menu (also rendered on their own by the screenshot tests). */
@Composable
internal fun PlanMenuItems(menu: PlanMenu, close: () -> Unit) {
    val st = menu.state
    // no logo head (owner 2026-11-07 morning: in a drop-down every row is a button – "man drückt drauf und wundert
    // sich, dass nichts passiert"); the name is on the start surface and on Über OnPath
    menu.onPhoneSetup?.let { p ->
        MenuItem(Icons.Outlined.WarningAmber, str(Res.string.settings_page_phone), enabled = st.settings, tint = OnPathColors.Error) { close(); p() }
        HorizontalDivider()
    }
    // "Meine Routen" always first, Save route under it (owner 2026-11-01: otherwise one gets confused)
    MenuItem(Icons.Outlined.FolderOpen, str(Res.string.plan_menu_my_routes), enabled = st.manage) { close(); menu.onManage() }
    menu.onSaveRoute?.let { save ->
        MenuItem(Icons.Outlined.Save, str(Res.string.plan_save_route), enabled = st.save) { close(); save() }
    }
    // owner 2026-11-06: Meine Touren · Plan speichern · Neu planen · Ohne Plan aufzeichnen, then the rest
    menu.onNewPlan?.let { n -> MenuItem(Icons.Filled.Close, str(Res.string.plan_close_plan)) { close(); n() } }
    menu.onRecordWithoutPlan?.let { r -> MenuItem(Icons.Outlined.RadioButtonChecked, str(Res.string.plan_menu_record_without_plan)) { close(); r() } }
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


