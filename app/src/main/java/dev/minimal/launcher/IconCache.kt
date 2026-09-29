package dev.minimal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Memory cache of app icons rendered to bitmaps at drawer size.
 * Loading happens off the main thread; callbacks run on the main thread.
 */
class IconCache(context: Context) {
    private val appContext = context.applicationContext
    private val sizePx = context.resources.getDimensionPixelSize(R.dimen.icon_render_size)
    private val density = context.resources.displayMetrics.densityDpi
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "icon-loader").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Main thread only: keys being loaded, with the callbacks waiting for them. */
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()

    fun peek(key: String): Bitmap? = cache.get(key)

    /** Main thread only. [onLoaded] is called on the main thread, possibly synchronously. */
    fun load(entry: AppEntry, onLoaded: (Bitmap) -> Unit) {
        cache.get(entry.key)?.let { onLoaded(it); return }
        pending[entry.key]?.let { it += onLoaded; return }
        pending[entry.key] = mutableListOf(onLoaded)
        executor.execute {
            val bitmap = render(entry)
            main.post {
                val waiting = pending.remove(entry.key) ?: return@post
                if (bitmap != null) {
                    cache.put(entry.key, bitmap)
                    waiting.forEach { it(bitmap) }
                }
            }
        }
    }

    /** Warms the cache so the drawer never shows blank icons. Main thread only. */
    fun preload(entries: List<AppEntry>) {
        for (e in entries) {
            if (cache.get(e.key) == null && e.key !in pending) load(e) {}
        }
    }

    fun evictPackage(packageName: String) {
        val prefix = "$packageName/"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    fun clear() = cache.evictAll()

    private fun render(entry: AppEntry): Bitmap? = try {
        val drawable = try {
            entry.info.getBadgedIcon(density)
        } catch (e: Exception) {
            appContext.packageManager.defaultActivityIcon
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(it))
        }
    } catch (t: Throwable) {
        Log.w("IconCache", "Icon failed for ${entry.key}", t)
        null
    }
}
