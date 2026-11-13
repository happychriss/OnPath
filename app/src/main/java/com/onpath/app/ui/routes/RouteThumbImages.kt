package com.onpath.app.ui.routes

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.map.RouteThumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * My routes' map pictures as the rows read them (owner 2026-11-13, L1): [cached] at once from memory, [load] off the main
 * thread; null = no picture (yet) – the row draws its line on paper as before.
 */
interface RouteThumbSource {
    fun cached(t: Tour): ImageBitmap?
    suspend fun load(t: Tour): ImageBitmap?
}

/**
 * The app's pictures: the files of [RouteThumbs], decoded on the IO dispatcher once and kept (by uuid and the file's
 * time – a picture made anew is read again). The list never makes a picture; [com.onpath.app.map.ThumbMaker] does.
 */
class FileThumbSource(private val thumbs: RouteThumbs) : RouteThumbSource {
    /** Kept by bytes, not by count ([MAX_BYTES]: about 60 pictures of 84 dp at 2.6 density). */
    private val lru = object : android.util.LruCache<String, Pair<Long, ImageBitmap>>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Pair<Long, ImageBitmap>): Int = bytesOf(value.second)
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        /** A decoded picture's memory (ARGB, 4 bytes a pixel), at least 1. */
        fun bytesOf(b: ImageBitmap): Int = (b.width.toLong() * b.height * 4).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun cached(t: Tour): ImageBitmap? = t.uuid.takeIf { it.isNotEmpty() }?.let { lru.get(it)?.second }

    override suspend fun load(t: Tour): ImageBitmap? {
        if (t.uuid.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val f = thumbs.file(t.uuid)
            val at = f.lastModified()
            if (at == 0L) { lru.remove(t.uuid); return@withContext null }
            lru.get(t.uuid)?.takeIf { it.first == at }?.let { return@withContext it.second }
            try {
                BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { lru.put(t.uuid, at to it) }
            } catch (e: Exception) {
                Problems.info("routes", "Map picture of a route unreadable – its row keeps the drawn line", e); null
            }
        }
    }
}

/** A row's map picture: from memory at once, then read (again after [version] moved on – one was made); null = none. */
@Composable
internal fun rememberThumb(t: Tour, source: RouteThumbSource?, version: Int): ImageBitmap? {
    if (source == null || t.uuid.isEmpty()) return null
    var pic by remember(t.uuid) { mutableStateOf(source.cached(t)) }
    LaunchedEffect(t.uuid, version) { pic = source.load(t) }
    return pic
}
