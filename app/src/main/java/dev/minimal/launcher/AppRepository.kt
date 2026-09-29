package dev.minimal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import java.text.Collator
import java.util.concurrent.Executors

/**
 * Source of truth for the list of launchable apps across all user profiles.
 * Loads on a background thread and publishes an immutable, sorted list on the main thread.
 */
class AppRepository(private val context: Context, private val icons: IconCache) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "app-loader") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var generation = 0

    /** Main thread only. */
    var apps: List<AppEntry> = emptyList()
        private set
    var loaded = false
        private set
    private var byKey: Map<String, AppEntry> = emptyMap()

    fun find(key: String): AppEntry? = byKey[key]

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun start() {
        launcherApps.registerCallback(callback, main)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_LOCALE_CHANGED) icons.clear()
                    requestReload()
                }
            },
            filter,
            Context.RECEIVER_NOT_EXPORTED,
        )
        reloadNow()
    }

    private val reloadRunnable = Runnable { reloadNow() }

    /** Coalesces bursts of package events into one reload. */
    fun requestReload(delayMs: Long = 150) {
        main.removeCallbacks(reloadRunnable)
        main.postDelayed(reloadRunnable, delayMs)
    }

    private fun reloadNow() {
        val gen = ++generation
        executor.execute {
            val list = try {
                query()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load apps", t)
                null
            }
            main.post {
                if (list == null || gen != generation) return@post
                apps = list
                byKey = list.associateBy { it.key }
                loaded = true
                listeners.toList().forEach { it() }
                icons.preload(list)
            }
        }
    }

    private fun query(): List<AppEntry> {
        val me = Process.myUserHandle()
        val out = ArrayList<AppEntry>()
        for (user in launcherApps.profiles) {
            val serial = userManager.getSerialNumberForUser(user)
            val activities = try {
                launcherApps.getActivityList(null, user)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list apps for $user", e)
                continue
            }
            for (info in activities) {
                val cn = info.componentName
                val label = info.label?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: cn.packageName
                out += AppEntry("${cn.flattenToShortString()}#$serial", info, label, user != me)
            }
        }
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        out.sortWith { a, b ->
            val c = collator.compare(a.label, b.label)
            if (c != 0) c else a.key.compareTo(b.key)
        }
        return out
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)

        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach { changed(it) }

        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach { changed(it) }

        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = requestReload()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = requestReload()

        private fun changed(packageName: String) {
            icons.evictPackage(packageName)
            requestReload()
        }
    }

    private companion object {
        const val TAG = "AppRepository"
    }
}
