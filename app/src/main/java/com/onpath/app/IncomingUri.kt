package com.onpath.app

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
            val p = path?.let { runCatching { File(it).canonicalPath }.getOrNull() }
            p != null && privateDirs.none { d ->
                val dir = runCatching { d.canonicalPath }.getOrDefault(d.path)
                p == dir || p.startsWith(dir + File.separator)
            }
        }
        else -> false
    }
}
