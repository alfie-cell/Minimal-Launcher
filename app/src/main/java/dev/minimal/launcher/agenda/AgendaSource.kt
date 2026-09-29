package dev.minimal.launcher.agenda

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import android.util.Log
import dev.minimal.launcher.calendar.CalendarStore
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Upcoming events from every calendar on the device (CalendarContract), including synced feeds
 * such as Calendar Sync's Proton/Outlook subscriptions. Queries run off the main thread; results
 * and refreshes are delivered on the main thread while [start]ed.
 */
class AgendaSource(context: Context, private val onChanged: (List<AgendaItem>) -> Unit) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "agenda") }
    private var started = false
    private var generation = 0

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = requestReload(300)
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = requestReload(0)
    }

    private val reloadRunnable = Runnable { reloadNow() }

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Begin watching; loads immediately. Main thread only. */
    fun start() {
        if (started) return
        started = true
        try {
            appContext.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        } catch (e: SecurityException) {
            Log.w(TAG, "No calendar access for observer", e)
        }
        appContext.registerReceiver(
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        reloadNow()
    }

    /** Stop watching (e.g. while home is not visible). Main thread only. */
    fun stop() {
        if (!started) return
        started = false
        generation++
        main.removeCallbacks(reloadRunnable)
        appContext.contentResolver.unregisterContentObserver(observer)
        try {
            appContext.unregisterReceiver(timeReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun requestReload(delayMs: Long) {
        if (!started) return
        main.removeCallbacks(reloadRunnable)
        main.postDelayed(reloadRunnable, delayMs)
    }

    private fun reloadNow() {
        if (!started) return
        main.removeCallbacks(reloadRunnable)
        val gen = ++generation
        executor.execute {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val items = try {
                if (hasPermission()) AgendaFormat.upcoming(query(now), now, zone, MAX_ITEMS) else emptyList()
            } catch (t: Throwable) {
                Log.w(TAG, "Calendar query failed", t)
                emptyList()
            }
            val next = AgendaFormat.nextRefresh(items, now, zone)
            main.post {
                if (!started || gen != generation) return@post
                onChanged(items)
                // Re-evaluate when an event starts/ends or the day rolls over.
                main.postDelayed(reloadRunnable, (next - System.currentTimeMillis()).coerceAtLeast(1000) + 500)
            }
        }
    }

    // Start a day early so all-day events (UTC midnights) and in-progress events are included.
    private fun query(now: Long): List<AgendaItem> =
        CalendarStore.instances(appContext, now - DAY_MS, now + WINDOW_DAYS * DAY_MS, max = MAX_SCAN)

    private companion object {
        const val TAG = "AgendaSource"
        const val DAY_MS = 24L * 60 * 60 * 1000
        // Far enough that the next few events always show, even after a quiet spell.
        const val WINDOW_DAYS = 90L
        const val MAX_ITEMS = 50
        const val MAX_SCAN = 500
    }
}
