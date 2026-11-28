package com.onpath.app.ui

import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource

/**
 * The pages of Settings (owner 2026-11-27: six topics on the start list instead of eleven). The start list ([top]) is
 * "Your settings" – Plan, Map, Navigation, Backup & browser – then the smaller "App" group – Privacy, About OnPath.
 *
 * The older topics keep their names so every caller that opens one directly keeps working:
 * - a **sub page** has a [parent] and opens as its own page; back returns to the parent (Map → Points of interest,
 *   Map → Offline maps, Navigation → Phone setup, About → Diagnostics, About → Legal & credits);
 * - an **alias** shows its [landing] page, which holds it as a section (Pace → Plan, Backup / Browser sync → Backup &
 *   browser, Updates → About); back from it returns to the list.
 *
 * [SYNC] is the pairing window „Mit Browser verbinden“ (owner 2026-11-28 10:54, A-018): its own page with only the browser
 * part (app ui/PairWindow.kt); every pairing path opens it. Not on the start list yet (the topic row waits for the owner's pick).
 */
enum class SettingsPage(private val titleRes: StringResource, val app: Boolean = false) {
    /** "Planen": your pace + how strongly routes follow marked trails. */
    PLAN(Res.string.settings_page_plan),
    PACE(Res.string.settings_page_pace),
    MAP(Res.string.settings_page_map),
    POI(Res.string.settings_page_poi),
    OFFLINE_MAPS(Res.string.settings_page_offline_maps),
    NAVIGATION(Res.string.settings_page_navigation),
    /** Phone setup (location, notifications, vibration, battery …): a row on the Navigation page. */
    PHONE(Res.string.settings_page_phone),
    /** "Sicherung & Browser": the GPX backup and the plan sync with the web planner (A-007). */
    BACKUP_SYNC(Res.string.settings_page_backup_sync),
    BACKUP(Res.string.settings_page_backup),
    /** The pairing window „Mit Browser verbinden“ (A-018): only the browser part, opened by every pairing path. */
    SYNC(Res.string.settings_page_sync),
    PRIVACY(Res.string.settings_page_privacy, app = true),
    /** "Über OnPath": version (7 taps → developer tools), update check, Diagnostics row, links, credits, licences. */
    ABOUT(Res.string.settings_page_about, app = true),
    UPDATES(Res.string.settings_page_updates, app = true),
    DIAGNOSTICS(Res.string.settings_page_diagnostics, app = true),
    /** "Rechtliches & Quellen": website, contact, privacy notice, legal notice, source, credits, licences. */
    LEGAL(Res.string.settings_page_legal, app = true);

    /** Topic name in the app's language (strings_settings.xml). */
    val title: String get() = Txt.s(titleRes)

    /** The page that is drawn for this one: itself, or – for an alias – the page that holds it as a section. */
    val landing: SettingsPage get() = when (this) {
        PACE -> PLAN
        BACKUP -> BACKUP_SYNC
        UPDATES -> ABOUT
        else -> this
    }

    /** A sub page's page (back returns there); null = a start-list page or an alias. */
    val parent: SettingsPage? get() = when (this) {
        POI, OFFLINE_MAPS -> MAP
        PHONE -> NAVIGATION
        DIAGNOSTICS, LEGAL -> ABOUT
        else -> null
    }

    /** One of the six on the start list. */
    val top: Boolean get() = parent == null && landing == this && this != SYNC

    /** This page shows the browser sync (the pairing window, or Backup & browser that still holds it as a section). */
    val showsSync: Boolean get() = landing == SYNC || landing == BACKUP_SYNC

    /** Where back goes from this page: the parent page, or null = the list. */
    val up: SettingsPage? get() = landing.parent
}
