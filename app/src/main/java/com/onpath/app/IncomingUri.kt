package com.onpath.app

import com.onpath.app.debug.Problems
import java.io.File

/**
 * Which "open with" / share URIs OnPath imports (review Arch 12): `content:` always; `file:` only outside OnPath's own
 * private folders – another app must not make OnPath "import" (read) its own files (the tour database, the sync
 * pairing). Nothing else (an `https:` page without a plan link) reaches the GPX import. Pure.
 */
object IncomingUri {
    fun importable(scheme: String?, path: String?, privateDirs: List<File>): Boolean = when (scheme?.lowercase()) {
        "content" -> true
        "file" -> {
            // a path that cannot be resolved is refused (safe side) and reported (audit 2026-11-07)
            val p = path?.let { runCatching { File(it).canonicalPath }
                .onFailure { e -> Problems.warn("intent", "Shared file path could not be resolved – not imported", e) }.getOrNull() }
            p != null && privateDirs.none { d ->
                val dir = runCatching { d.canonicalPath }.getOrElse { e ->
                    Problems.warn("intent", "Own folder path could not be resolved – file not imported", e); null }
                dir == null || p == dir || p.startsWith(dir + File.separator)
            }
        }
        else -> false
    }
}
