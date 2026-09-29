package dev.minimal.launcher

import android.app.Application
import android.content.Context
import android.os.StrictMode
import dev.minimal.launcher.reminders.ReminderScheduler

class LauncherApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var icons: IconCache
        private set
    lateinit var repo: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
        prefs = Prefs(this)
        icons = IconCache(this)
        repo = AppRepository(this, icons)
        repo.start()
        // Process (re)start, e.g. after boot on ROMs that delay BOOT_COMPLETED: re-arm reminders.
        if (prefs.remindersEnabled) ReminderScheduler.request(this)
    }
}

val Context.launcherApp: LauncherApp
    get() = applicationContext as LauncherApp
