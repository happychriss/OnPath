package com.onpath.app.poi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.onpath.app.debug.Problems
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

    /**
     * The two forms of one "Open in Google Maps" link: [appUri] for the Google Maps app (a `geo:` intent),
     * [webUrl] for the browser when the app is missing or disabled.
     */
    data class MapsLink(val appUri: String, val webUrl: String)

    /**
     * Link to [name] at [lat],[lon] in Google Maps – built offline from our own data, nothing is fetched.
     *
     * Why not `maps/search/?api=1&query=<name lat,lon>`: that `query` is one free-text
     * string. Google either reads the coordinates and drops the name (bare pin, no business card) or searches the
     * whole text, and the documented search URL has **no location-bias parameter** – it ranks by the visible map /
     * the phone's own location. A German OSM name that Google lists under its Italian name, or a common name
     * ("Gasthof Sonne", "Kirche St. Peter"), then lands somewhere else. A place ID (`query_place_id`) would be exact,
     * but needs an online Places API call, which OnPath does not make.
     *
     * Instead:
     * - **Named** → `geo:<lat>,<lon>?z=16&q=<name>`: the Android Maps-intents doc says a `geo:lat,lng?q=` search
     *   finds results *near the given coordinate* ("bias the results of the search by specifying a coordinate"),
     *   and shows the place (with its card) when there is a single match. z=16 keeps the search local (the doc:
     *   z=10 searches city-wide). If Google has nothing under that name, the map at least opens on the right spot.
     *   Web fallback: `maps/search/<name>/@<lat>,<lon>,16z` – the search path with a viewport, which biases
     *   the browser search the same way (not part of the documented `api=1` set, but Google's own URL form).
     * - **Unnamed** (route point, unnamed viewpoint, bench …) → `geo:0,0?q=<lat>,<lon>(<label>)`: an exact pin, with
     *   the optional [label] (e.g. the POI kind) under it; nearby business cards are one tap away. No text search,
     *   so it can never jump. Web: the documented `api=1&query=<lat>,<lon>`.
     *
     * Sources: developer.android.com/guide/components/google-maps-intents,
     * developers.google.com/maps/documentation/urls/get-started.
     */
    fun googleMaps(name: String?, lat: Double, lon: Double, label: String? = null): MapsLink {
        val ll = "${coord(lat)},${coord(lon)}"
        val n = name?.let(::clean)
        return if (n != null) {
            MapsLink("geo:$ll?z=16&q=${enc(n)}", "https://www.google.com/maps/search/${enc(n)}/@$ll,16z")
        } else {
            // Parentheses delimit the label; inside it they would cut it short.
            val l = label?.let(::clean)?.replace('(', ' ')?.replace(')', ' ')?.let(::clean)
            MapsLink("geo:0,0?q=$ll" + (l?.let { "(${enc(it)})" } ?: ""),
                "https://www.google.com/maps/search/?api=1&query=${enc(ll)}")
        }
    }

    /** POIs: the name searches near the point; an unnamed POI gets an exact pin labelled with its kind. */
    fun googleMaps(poi: Poi): MapsLink = googleMaps(poi.name, poi.lat, poi.lon, label = poi.kindInfo.label)

    private fun clean(s: String): String? = s.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }

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

    fun openInGoogleMaps(context: Context, poi: Poi): Boolean = openGoogleMaps(context, PoiLinks.googleMaps(poi))

    /** The Google Maps app with the `geo:` form if installed, otherwise the browser with the web form. */
    fun openGoogleMaps(context: Context, link: PoiLinks.MapsLink): Boolean {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(link.appUri)).setPackage(PoiLinks.GOOGLE_MAPS_PACKAGE)
        return start(context, app) || start(context, Intent(Intent.ACTION_VIEW, Uri.parse(link.webUrl)))
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
        Problems.info("poi", "No app on the phone opens this link (${intent.action})")
        false
    } catch (e: Exception) {
        Problems.warn("poi", "Link could not be opened", e)
        false
    }
}
