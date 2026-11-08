package com.onpath.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.onpath.app.data.AtomicFiles
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
        /** The APK's sha256 from the release asset's `digest` (GitHub), checked before the installer gets it; null = unknown. */
        val apkSha256: String? = null,
        /**
         * The same APK through the API asset address (`.../releases/assets/<id>`, asked with `Accept:
         * application/octet-stream`; it redirects to the file). Field bug 2026-11-08: github.com answered 504 for every
         * `browser_download_url` while this address worked. Null = unknown, only [apkUrl] is used.
         */
        val apkApiUrl: String? = null,
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
                    apkSha256 = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                    apkApiUrl = a.optLong("id", -1).takeIf { it > 0 }?.let { id ->
                        a.optString("url").takeIf { it.startsWith("https://api.github.com/") && it.endsWith("/releases/assets/$id") }
                    },
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

    /** Where the APK is fetched from; [kind] ("api" / "browser") goes into Problems – never a token (the app has none). */
    internal data class Source(val kind: String, val url: String, val accept: String?)

    /**
     * The API asset address first, the browser address as fallback (field bug 2026-11-08). The API address goes first
     * because it is the host the update check has just reached, and it was the one that worked while github.com
     * answered 504 for every browser address; the fallback costs one quick request either way. It counts against
     * GitHub's unauthenticated limit (60 an hour per IP) – one check a day plus a few download tries.
     */
    internal fun sources(release: Release): List<Source> = listOfNotNull(
        release.apkApiUrl?.let { Source("api", it, "application/octet-stream") },
        Source("browser", release.apkUrl, null),
    )

    /** A source answered with a failed HTTP status. */
    internal class ServerAnswer(val code: Int, val kind: String) : java.io.IOException("$kind $code")

    /**
     * [download] into [dir] (unit-tested with a local server; [pauseMs] shortened there). Per source: a 5xx is tried
     * once more after a short pause, any other failed status goes straight to the next source; a broken connection
     * resumes up to [ATTEMPTS] times. The part file holds the same bytes for both sources, so the next one resumes it;
     * the sha256 is checked whichever source delivered.
     */
    internal fun downloadTo(dir: File, release: Release, onProgress: (Int) -> Unit = {}, pauseMs: Long = 2_000L): File {
        dir.mkdirs()
        val dest = File(dir, "onpath-${release.version}.apk")
        val tmp = File(dir, ".${dest.name}.part")
        // keep only this update (and its part file) around
        dir.listFiles()?.forEach { if (it != tmp) it.delete() }
        var lastError: Exception? = null
        val answers = mutableListOf<String>()
        for (src in sources(release)) {
            var retried5xx = false
            var attempt = 0
            while (attempt < ATTEMPTS) {
                attempt++
                try {
                    return downloadOnce(src, release, tmp, dest, onProgress)
                } catch (e: ServerAnswer) {
                    lastError = e
                    answers += "${src.kind} ${e.code}"
                    val retry = e.code in 500..599 && !retried5xx
                    Problems.warn("update", "Update download: ${src.kind} URL answered HTTP ${e.code}; " +
                        if (retry) "trying it once more" else "trying the next source")
                    if (!retry) break
                    retried5xx = true
                    Thread.sleep(pauseMs)
                } catch (e: java.io.IOException) {
                    lastError = e
                    Problems.info("update", "Update download interrupted (${src.kind} URL, try $attempt of $ATTEMPTS, " +
                        "${tmp.length() / 1_048_576} MB so far); resuming", e)
                    if (attempt < ATTEMPTS) Thread.sleep(pauseMs * attempt)
                }
            }
        }
        if (lastError is ServerAnswer)
            throw java.io.IOException("Download failed – GitHub answered ${answers.joinToString(", ")}. Try again later.", lastError)
        throw java.io.IOException("Download stopped – connection lost. Tap again to continue where it stopped.", lastError)
    }

    /**
     * One try ([com.onpath.app.net.HttpDownload]: resume, https-only redirects, fsync, size and – review Arch 6 – the
     * release's sha256, so a damaged or swapped file never reaches the installer).
     */
    private fun downloadOnce(src: Source, release: Release, tmp: File, dest: File, onProgress: (Int) -> Unit): File {
        var last = -1
        com.onpath.app.net.HttpDownload.resume(
            src.url, tmp, release.apkSize.takeIf { it > 0 }, release.apkSha256,
            open = { u -> (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "OnPath-Android")
                src.accept?.let { setRequestProperty("Accept", it) }
            } },
            failure = { code, _ -> ServerAnswer(code, src.kind) },
            onBytes = { done, total -> if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } } },
        )
        try { AtomicFiles.replace(tmp, dest) } catch (e: java.io.IOException) { throw IllegalStateException("Could not save the update.", e) }
        return dest
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
