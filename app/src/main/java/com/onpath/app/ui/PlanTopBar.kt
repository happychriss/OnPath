package com.onpath.app.ui

import com.onpath.app.domain.OfflineReady
import com.onpath.app.domain.RouteMenuState
import com.onpath.app.domain.RouteRules
import com.onpath.strings.*
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
    /**
     * "Neu planen" in ☰ (owner 2026-11-06: "habe ich oben im Menü gesucht") – the same way as the sheet's ✕ pill (which
     * stays); null hides it (no plan points).
     */
    val onNewPlan: (() -> Unit)? = null,
    /**
     * "Nur aufzeichnen" (owner 2026-11-06, flow map H1: "Los geht's ohne Route" in the sheet was confusing – in ☰,
     * rarely needed): a plain recording; null hides it (a tour runs).
     */
    val onRecordWithoutPlan: (() -> Unit)? = null,
    /**
     * "Ganz neu berechnen" in the plan group right after "Neu planen" (owner 2026-11-25, picture B): drops the plan's detour
     * points and routes it again; null hides it ([com.onpath.app.ui.plan.PlanEditState.recalcRow]: only a plan with such
     * points on its profile, no tour running).
     */
    val onRecalcFromScratch: (() -> Unit)? = null,
    /**
     * "? Hilfe" as the first item (owner 2026-11-08, father's feedback): the help on onpath.earth in the app language –
     * the same address as About's "Hilfe & erste Schritte" ([OnPathLinks.help]); null hides it.
     */
    val onHelp: (() -> Unit)? = null,
) {
    /**
     * The dot on ☰: only the browser sync's amber warning. Unsaved has no sign any more (owner 2026-11-06: nothing is
     * lost – a plan leaving the map goes under My routes → Zuletzt).
     */
    val dotWarn: Boolean get() = onPairBrowser != null && sync.warning != null
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
internal fun AppMenuButton(menu: PlanMenu, initiallyOpen: Boolean = false, square: Boolean = false) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        // square: the Daumen-Karte's ☰ in the card's top row (owner 2026-11-26); the menu opens upward over the thumb
        if (square) Surface(onClick = { menu.onOpened(); open = true }, shape = ThumbInner, color = OnPathColors.ThumbField,
            modifier = Modifier.size(48.dp).semantics { contentDescription = Txt.s(Res.string.plan_menu) }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Menu, contentDescription = null, tint = OnPathColors.PlanAccent) }
        }
        else Surface(onClick = { menu.onOpened(); open = true }, shape = CircleShape, color = OnPathColors.MapGround, modifier = Modifier.size(56.dp).semantics { contentDescription = Txt.s(Res.string.plan_menu) }.floatShadow(CircleShape)) {
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
    // help first (owner 2026-11-08: the father looked for a "?"), on its own above a divider
    menu.onHelp?.let { h ->
        MenuItem(Icons.AutoMirrored.Outlined.HelpOutline, str(Res.string.plan_menu_help)) { close(); h() }
        HorizontalDivider()
    }
    menu.onPhoneSetup?.let { p ->
        MenuItem(Icons.Outlined.WarningAmber, str(Res.string.settings_page_phone), enabled = st.settings, tint = OnPathColors.Error) { close(); p() }
        HorizontalDivider()
    }
    // "Meine Routen" always first, Save route under it (owner 2026-11-01: otherwise one gets confused)
    MenuItem(Icons.Outlined.FolderOpen, str(Res.string.plan_menu_my_routes), enabled = st.manage) { close(); menu.onManage() }
    menu.onSaveRoute?.let { save ->
        MenuItem(Icons.Outlined.Save, str(Res.string.plan_save_route), enabled = st.save) { close(); save() }
    }
    // owner 2026-11-06: Meine Touren · Plan speichern · Neu planen · Nur aufzeichnen, then the rest
    menu.onNewPlan?.let { n -> MenuItem(Icons.Filled.Close, str(Res.string.plan_close_plan)) { close(); n() } }
    menu.onRecalcFromScratch?.let { r -> MenuItem(Icons.Outlined.Route, str(Res.string.plan_menu_recalc)) { close(); r() } }
    menu.onRecordWithoutPlan?.let { r -> MenuItem(Icons.Outlined.RadioButtonChecked, str(Res.string.plan_menu_record_without_plan)) { close(); r() } }
    HorizontalDivider()
    menu.onPairBrowser?.let { p -> SyncMenuItem(menu.sync, enabled = st.settings) { close(); p() } }
    MenuItem(Icons.Outlined.Settings, str(Res.string.common_settings), enabled = st.settings) { close(); menu.onSettings() }
    menu.onUpdates?.let { u -> MenuItem(Icons.Outlined.SystemUpdate, str(Res.string.plan_menu_check_updates), enabled = st.updates) { close(); u() } }
}

/**
 * One plain menu line (owner 2026-11-07: "einfach ein Menüeintrag"): "Mit Browser verbinden" / "Browser-Verbindung"
 * ([SyncMenuUi.labelPaired]), no subtitle, no ✓. The state shows only in the leading icon: a problem worth a look makes
 * the same sync icon amber (and the ☰ dot) – the screen reader hears the reason. A tap opens the pairing / Browser sync,
 * where the status stands ("Verbunden seit …", "Zuletzt abgeglichen", Trennen).
 */
@Composable
private fun SyncMenuItem(ui: SyncMenuUi, enabled: Boolean, onClick: () -> Unit) {
    val warn = ui.warning != null
    val label = str(if (ui.labelPaired) Res.string.sync_menu_paired else Res.string.sync_menu_pair)
    val said = if (warn && ui.line != null) str(Res.string.sync_menu_attention, ui.line) else label
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled, onClick = onClick,
        leadingIcon = { Icon(Icons.Outlined.Sync, contentDescription = null, tint = if (warn) OnPathColors.Warn else LocalContentColor.current) },
        modifier = Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = said },
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


