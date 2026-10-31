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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
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
    /** Free recording without a route (location permission is checked by the caller). */
    val onStartRecording: () -> Unit = {},
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
     * "Save route" on top (owner 2026-10-30: he looked for Save in ☰): only while the plan has unsaved changes
     * (RouteRules.saveAtHand) – the same action as the sheet's Save; null hides it.
     */
    val onSaveRoute: (() -> Unit)? = null,
)

/**
 * Plan mode top area: the search pill (only search) and one round ☰ app-menu button next to it (My routes, Start
 * recording, Settings, Updates; Phone setup first while a tour needs it, then Save route while the plan is unsaved).
 * The activity and all route actions live in the route sheet (PlanPanel) – Save route is the one also here.
 */
@Composable
fun PlanTopBar(
    onSearch: () -> Unit,
    menu: PlanMenu,
    modifier: Modifier = Modifier,
    menuInitiallyOpen: Boolean = false,
) {
    Row(modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SearchBarPill(onSearch, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        AppMenuButton(menu, menuInitiallyOpen)
    }
}

@Composable
private fun SearchBarPill(onSearch: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onSearch,
        modifier = modifier.height(56.dp).semantics { contentDescription = Txt.s(Res.string.plan_search_hint); role = Role.Button },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Surface(onClick = { menu.onOpened(); open = true }, shape = CircleShape, color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp, modifier = Modifier.size(56.dp).semantics { contentDescription = Txt.s(Res.string.plan_menu) }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Menu, contentDescription = null) }
        }
        // amber dot: the browser sync needs a look (never for plain offline) – the ☰ item says why
        if (menu.onPairBrowser != null && menu.sync.warning != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp)
            .size(12.dp).background(OnPathColors.Warn, CircleShape).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape))
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
    menu.onSaveRoute?.let { save ->
        MenuItem(Icons.Outlined.Save, str(Res.string.plan_save_route), enabled = st.save) { close(); save() }
        HorizontalDivider()
    }
    MenuItem(Icons.Outlined.FolderOpen, str(Res.string.plan_menu_my_routes), enabled = st.manage) { close(); menu.onManage() }
    MenuItem(Icons.Filled.FiberManualRecord, str(Res.string.plan_menu_start_recording), enabled = st.startRecording) { close(); menu.onStartRecording() }
    HorizontalDivider()
    menu.onPairBrowser?.let { p -> SyncMenuItem(menu.sync, enabled = st.settings) { close(); p() } }
    MenuItem(Icons.Outlined.Settings, str(Res.string.common_settings), enabled = st.settings) { close(); menu.onSettings() }
    menu.onUpdates?.let { u -> MenuItem(Icons.Outlined.SystemUpdate, str(Res.string.plan_menu_check_updates), enabled = st.updates) { close(); u() } }
}

/**
 * "Pair with browser" (unpaired in the browser: + grey "Unpaired in the browser"); paired: "Paired with browser ✓" + grey
 * "<device> ↔ <browser> · synced 1 min ago"; a problem: amber ⚠ + why.
 */
@Composable
private fun SyncMenuItem(ui: SyncMenuUi, enabled: Boolean, onClick: () -> Unit) {
    val warn = ui.warning != null
    val label = if (!ui.paired) str(Res.string.sync_menu_pair) else str(Res.string.sync_menu_paired) + if (warn) "" else " ✓"
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

