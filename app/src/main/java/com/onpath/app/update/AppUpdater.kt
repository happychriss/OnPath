package com.onpath.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.onpath.app.debug.Problems
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App updates straight on the phone (requirement F-OFF-06), from GitHub Releases.
 *
 * Every version is published by .github/workflows/release.yml as a release "vX.Y.Z" with the APK
 * attached. The app reads the latest release, shows the notes, downloads the APK and hands it to the
 * Android installer – it never installs without the user confirming in the system dialog.
 * All releases are signed with the same key, so the update installs in place and keeps tours/data.
 */
object AppUpdater {

    data class Release(
        val version: String,      // "0.4.1" (tag without the leading v)
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
    )

    // ------------------------------------------------------------------ pure logic (unit-tested)

    /** Parses the GitHub "latest release" JSON; null if it has no APK asset or is a draft. */
    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft")) return null
        val tag = o.optString("tag_name").removePrefix("v").ifBlank { return null }
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk")) {
                return Release(
                    version = tag,
                    notes = o.optString("body").trim(),
                    apkUrl = a.optString("browser_download_url"),
                    apkSize = a.optLong("size", -1),
                    pageUrl = o.optString("html_url"),
                )
            }
        }
        return null
    }

    /** True if [candidate] ("0.4.1") is newer than [current] ("0.4.0"). Non-numeric parts count as 0. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').take(3)
            .map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }.let { it + List(3 - it.size) { 0 } }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** Quiet automatic check at most once per day. */
    fun dueForAutoCheck(lastCheckMs: Long, nowMs: Long) = nowMs - lastCheckMs > 24L * 3600 * 1000

    // ------------------------------------------------------------------ network (background thread)

    /** Latest release from GitHub, or null if there is none with an APK. Throws on network errors. */
    fun fetchLatest(repo: String): Release? {
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OnPath-Android")
        }
        try {
            return when (conn.responseCode) {
                200 -> parseRelease(conn.inputStream.bufferedReader().use { it.readText() })
                404 -> null // no release published yet
                else -> error("GitHub answered ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the release APK into the app cache; returns the file. [onProgress] gets 0..100. */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit = {}): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only one update around
        val dest = File(dir, "onpath-${release.version}.apk")
        val tmp = File(dir, ".${dest.name}.part")
        var url = URL(release.apkUrl)
        var conn: HttpURLConnection
        // GitHub redirects to its file CDN; follow a few redirects manually (also across hosts).
        var hops = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "OnPath-Android")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val loc = conn.getHeaderField("Location") ?: error("Redirect without location")
                conn.disconnect(); url = URL(url, loc); continue
            }
            if (code != 200) { conn.disconnect(); error("Download failed ($code)") }
            break
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L; var last = -1
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                    }
                }
            }
            if (release.apkSize > 0 && tmp.length() != release.apkSize) error("Download incomplete – please try again.")
            if (!tmp.renameTo(dest)) error("Could not save the update.")
            return dest
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    // ------------------------------------------------------------------ installing

    /** Android must allow OnPath to install apps once ("Install unknown apps" for OnPath). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows OnPath to install updates. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the APK to the system installer (the user confirms there). */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Installed from Google Play: Play handles updates, so the in-app updater stays out of the way. */
    fun installedFromPlay(context: Context): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        installer == "com.android.vending"
    } catch (e: Exception) {
        Problems.warn("update", "Installer of the app unknown; in-app updates stay on", e)
        false
    }
}
