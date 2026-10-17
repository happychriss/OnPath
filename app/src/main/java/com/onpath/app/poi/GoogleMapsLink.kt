package com.onpath.app.poi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLEncoder
import java.util.Locale

/**
 * Pure URL builders (unit-tested). Google Maps is a link-out only: nothing from Google is
 * fetched, stored or displayed in the app.
 */
object PoiLinks {
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')

    /** https://www.google.com/maps/search/?api=1&query=<"name lat,lon" | "lat,lon"> */
    fun googleMapsUrl(name: String?, lat: Double, lon: Double): String {
        val ll = "${coord(lat)},${coord(lon)}"
        val q = name?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it $ll" } ?: ll
        return "https://www.google.com/maps/search/?api=1&query=${enc(q)}"
    }

    fun googleMapsUrl(poi: Poi): String = googleMapsUrl(poi.name, poi.lat, poi.lon)

    /** OSM wikipedia tag "de:Schloss Tirol" -> https://de.wikipedia.org/wiki/Schloss_Tirol. Full URLs pass through. */
    fun wikipediaUrl(tag: String?, defaultLang: String = "en"): String? {
        val t = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        val m = Regex("^([a-z]{2,3}(?:-[a-z]+)?):(.+)$").find(t)
        val (lang, title) = if (m != null) m.groupValues[1] to m.groupValues[2] else defaultLang to t
        val path = title.trim().replace(' ', '_').split('#', limit = 2).let { parts ->
            enc(parts[0]).replace("%2F", "/") + (parts.getOrNull(1)?.let { "#" + enc(it) } ?: "")
        }
        return "https://$lang.wikipedia.org/wiki/$path"
    }

    /** Display form of a wikipedia tag: "de:Schloss Tirol" -> "Schloss Tirol (de)". */
    fun wikipediaLabel(tag: String?): String? {
        val t = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = Regex("^([a-z]{2,3}(?:-[a-z]+)?):(.+)$").find(t) ?: return t
        return "${m.groupValues[2]} (${m.groupValues[1]})"
    }

    /** Adds https:// when the OSM website lacks a scheme; takes the first of several ";"-separated URLs. */
    fun websiteUrl(website: String?): String? {
        val w = website?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(w)) w else "https://$w"
    }

    /** Short display form of a website: no scheme, no "www.", no trailing slash. */
    fun websiteLabel(website: String?): String? = websiteUrl(website)
        ?.replace(Regex("^[a-zA-Z]+://"), "")?.removePrefix("www.")?.trimEnd('/')

    /** "+39 0473 123 456" -> "tel:+390473123456" (first number only). */
    fun phoneUri(phone: String?): String? {
        val p = phone?.split(';', ',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digits = p.filterIndexed { i, c -> c.isDigit() || (c == '+' && i == 0) }
        return if (digits.count { it.isDigit() } >= 3) "tel:$digits" else null
    }
}

/** Android intents for the links. All return false when nothing could handle them. */
object PoiIntents {

    /** Opens the Google Maps app if installed, otherwise the browser. */
    fun openInGoogleMaps(context: Context, poi: Poi): Boolean {
        val uri = Uri.parse(PoiLinks.googleMapsUrl(poi))
        val app = Intent(Intent.ACTION_VIEW, uri).setPackage(PoiLinks.GOOGLE_MAPS_PACKAGE)
        return start(context, app) || start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    fun openWebsite(context: Context, website: String?): Boolean =
        PoiLinks.websiteUrl(website)?.let { start(context, Intent(Intent.ACTION_VIEW, Uri.parse(it))) } ?: false

    fun openWikipedia(context: Context, tag: String?): Boolean =
        PoiLinks.wikipediaUrl(tag)?.let { start(context, Intent(Intent.ACTION_VIEW, Uri.parse(it))) } ?: false

    /** ACTION_DIAL: opens the dialer pre-filled, needs no permission. */
    fun dial(context: Context, phone: String?): Boolean =
        PoiLinks.phoneUri(phone)?.let { start(context, Intent(Intent.ACTION_DIAL, Uri.parse(it))) } ?: false

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        Log.w("OnPath", "cannot open link: $e")
        false
    }
}
