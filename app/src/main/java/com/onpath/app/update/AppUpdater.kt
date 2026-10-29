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

    /**
     * Downloads the release APK into the app cache; returns the file. [onProgress] gets 0..100.
     * A broken connection (e.g. "Software caused connection abort" on mobile data) resumes where it
     * stopped (HTTP Range) – up to [ATTEMPTS] tries with a short pause, and the part file is kept for the next tap.
     */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit = {}): File =
        downloadTo(File(context.cacheDir, "updates"), release, onProgress)

    /** [download] into [dir] (unit-tested with a local server; [pauseMs] shortened there). */
    internal fun downloadTo(dir: File, release: Release, onProgress: (Int) -> Unit = {}, pauseMs: Long = 2_000L): File {
        dir.mkdirs()
        val dest = File(dir, "onpath-${release.version}.apk")
        val tmp = File(dir, ".${dest.name}.part")
        // keep only this update (and its part file) around
        dir.listFiles()?.forEach { if (it != tmp) it.delete() }
        var lastError: Exception? = null
        for (attempt in 1..ATTEMPTS) {
            try {
                return downloadOnce(release, tmp, dest, onProgress)
            } catch (e: java.io.IOException) {
                lastError = e
                Problems.info("update", "Update download interrupted (try $attempt of $ATTEMPTS, " +
                    "${tmp.length() / 1_048_576} MB so far); resuming", e)
                if (attempt < ATTEMPTS) Thread.sleep(pauseMs * attempt)
            }
        }
        throw java.io.IOException("Download stopped – connection lost. Tap again to continue where it stopped.", lastError)
    }

    private fun downloadOnce(release: Release, tmp: File, dest: File, onProgress: (Int) -> Unit): File {
        var have = if (tmp.isFile) tmp.length() else 0L
        if (release.apkSize > 0 && have > release.apkSize) { tmp.delete(); have = 0 }
        var url = URL(release.apkUrl)
        var conn: HttpURLConnection
        // GitHub redirects to its file CDN; follow a few redirects manually (also across hosts).
        var hops = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "OnPath-Android")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val loc = conn.getHeaderField("Location") ?: error("Redirect without location")
                conn.disconnect(); url = URL(url, loc); continue
            }
            if (code == 416 && release.apkSize > 0 && have == release.apkSize) { conn.disconnect(); break } // already complete
            if (code != 200 && code != 206) { conn.disconnect(); error("Download failed ($code)") }
            if (code == 200) have = 0 // the server ignored the range: start over
            break
        }
        try {
            if (!(release.apkSize > 0 && have == release.apkSize)) {
                val total = release.apkSize.takeIf { it > 0 } ?: (conn.contentLengthLong.takeIf { it > 0 }?.plus(have) ?: -1)
                conn.inputStream.use { inp ->
                    java.io.FileOutputStream(tmp, have > 0).use { out ->
                        val buf = ByteArray(128 * 1024)
                        var done = have; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && tmp.length() != release.apkSize) throw java.io.IOException("Download incomplete")
            if (!tmp.renameTo(dest)) error("Could not save the update.")
            return dest
        } finally {
            conn.disconnect()
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

/** Tries per update download (each resumes where the last stopped). */
private const val ATTEMPTS = 4
