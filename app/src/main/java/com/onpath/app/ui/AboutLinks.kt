package com.onpath.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.onpath.app.debug.Problems
import com.onpath.strings.*

/**
 * The website onpath.earth and the project's other addresses (owner 2026-11-08: the app had no way back to the site).
 * The site's pages follow the app language: German under /de/ with German slugs, English at the root (onpath.earth
 * build.py / footer).
 */
object OnPathLinks {
    const val MAIL = "info@onpath.earth"
    const val SOURCE = "https://github.com/happychriss/OnPath"
    private const val SITE = "https://onpath.earth"

    private fun de(lang: String) = lang == "de"
    fun website(lang: String = Txt.language()) = if (de(lang)) "$SITE/de/" else "$SITE/"
    fun help(lang: String = Txt.language()) = if (de(lang)) "$SITE/de/hilfe/" else "$SITE/help/"
    fun privacy(lang: String = Txt.language()) = if (de(lang)) "$SITE/de/datenschutz/" else "$SITE/privacy/"
    fun legal(lang: String = Txt.language()) = if (de(lang)) "$SITE/de/impressum/" else "$SITE/legal/"

    /**
     * The phone-setup help for the brand page's „Mehr Infos ›“ (owner 2026-11-08): the help topic "Akku und Sicherung"
     * / "Battery and backup" at its Samsung & co section (anchor `#samsung`, asked of cloud-web 2026-11-08; until the
     * section is live the page opens at its top).
     */
    fun phoneSetupHelp(lang: String = Txt.language()) =
        if (de(lang)) "$SITE/de/hilfe/akku-sicherung/#samsung" else "$SITE/help/battery-backup/#samsung"

    /** What a row shows under its title: the address without "https://" and the last "/". */
    fun shown(url: String) = url.removePrefix("https://").removeSuffix("/")

    /** A plain ACTION_VIEW (the phone's browser / mail app); false when no app takes it. */
    fun open(context: Context, uri: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Problems.warn("links", "No app opens $uri", e)
        false
    }

    /** Contact without a mail app: the address to the clipboard; false when that fails too. */
    fun copyMail(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OnPath", MAIL))
        true
    } catch (e: Exception) {
        Problems.warn("links", "Mail address not copied", e)
        false
    }
}

/** About → the links card under version and licence: help, website, contact, privacy notice, legal notice, source. */
@Composable
internal fun AboutLinksSection() {
    val context = LocalContext.current
    val help = OnPathLinks.help()
    val site = OnPathLinks.website()
    val privacy = OnPathLinks.privacy()
    val legal = OnPathLinks.legal()
    var copied by remember { mutableStateOf(false) }
    Section(str(Res.string.settings_links)) {
        LinkRow(Icons.AutoMirrored.Outlined.HelpOutline, str(Res.string.settings_link_help), OnPathLinks.shown(help)) {
            OnPathLinks.open(context, help)
        }
        LinkRow(Icons.Outlined.Language, str(Res.string.settings_link_website), OnPathLinks.shown(site)) {
            OnPathLinks.open(context, site)
        }
        // the address stays readable: without a mail app it goes to the clipboard, "Adresse kopiert" in its place
        LinkRow(Icons.Outlined.MailOutline, str(Res.string.settings_link_contact),
            if (copied) str(Res.string.settings_link_copied) else OnPathLinks.MAIL, accent = copied) {
            if (!OnPathLinks.open(context, "mailto:${OnPathLinks.MAIL}")) copied = OnPathLinks.copyMail(context)
        }
        LinkRow(Icons.Outlined.Lock, str(Res.string.settings_link_privacy), OnPathLinks.shown(privacy)) {
            OnPathLinks.open(context, privacy)
        }
        LinkRow(Icons.Outlined.Gavel, str(Res.string.settings_link_legal), OnPathLinks.shown(legal)) {
            OnPathLinks.open(context, legal)
        }
        LinkRow(Icons.Outlined.Code, str(Res.string.settings_link_source), OnPathLinks.shown(OnPathLinks.SOURCE)) {
            OnPathLinks.open(context, OnPathLinks.SOURCE)
        }
    }
}

/** Privacy page, last row: the full privacy notice on the website. */
@Composable
internal fun PrivacyNoticeLink() {
    val context = LocalContext.current
    val privacy = OnPathLinks.privacy()
    LinkRow(null, str(Res.string.settings_privacy_full), OnPathLinks.shown(privacy)) { OnPathLinks.open(context, privacy) }
}

/**
 * One link: icon, title, the address in small grey (green when [accent]: "Adresse kopiert"), ↗ (it leaves the app).
 * Same height and spacing as the topic rows.
 */
@Composable
private fun LinkRow(icon: ImageVector?, title: String, address: String, accent: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = OnPathColors.Green, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(address, style = MaterialTheme.typography.bodySmall,
                color = if (accent) OnPathColors.Green else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp))
    }
}
