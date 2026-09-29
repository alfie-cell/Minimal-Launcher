package dev.minimal.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import dev.minimal.launcher.agenda.AgendaItem

/** Every outward action is wrapped so a misbehaving app or OEM quirk can never crash home. */
object Actions {
    private const val TAG = "Actions"

    fun launch(activity: Activity, entry: AppEntry, source: View?): Boolean {
        val userManager = activity.getSystemService(UserManager::class.java)
        try {
            if (entry.isWork && userManager.isQuietModeEnabled(entry.user)) {
                // Asks the system to un-pause work apps (may show a confirmation).
                userManager.requestQuietModeEnabled(false, entry.user)
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Quiet mode check failed", e)
        }

        var bounds: Rect? = null
        var options: android.os.Bundle? = null
        if (source != null && source.isAttachedToWindow) {
            val loc = IntArray(2)
            source.getLocationOnScreen(loc)
            bounds = Rect(loc[0], loc[1], loc[0] + source.width, loc[1] + source.height)
            options = ActivityOptions
                .makeClipRevealAnimation(source, 0, 0, source.width, source.height)
                .toBundle()
        }
        return try {
            activity.getSystemService(LauncherApps::class.java)
                .startMainActivity(entry.component, entry.user, bounds, options)
            activity.launcherApp.prefs.recordLaunch(entry.key)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Launch failed for ${entry.key}", e)
            Toast.makeText(activity, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            activity.launcherApp.repo.requestReload()
            false
        }
    }

    fun openAppInfo(context: Context, entry: AppEntry) {
        try {
            context.getSystemService(LauncherApps::class.java)
                .startAppDetailsActivity(entry.component, entry.user, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "App info failed", e)
            Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    fun uninstall(context: Context, entry: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null))
            .putExtra(Intent.EXTRA_USER, entry.user)
        safeStart(context, intent)
    }

    fun isSystemApp(entry: AppEntry): Boolean =
        entry.info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

    fun openAlarms(context: Context) = safeStart(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))

    fun openCalendar(context: Context) = safeStart(
        context,
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
    )

    /** Opens one occurrence of an event in the user's calendar app. */
    fun openCalendarEvent(context: Context, item: AgendaItem) = safeStart(
        context,
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, item.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, item.end),
    )

    fun openWallpaperPicker(context: Context) =
        safeStart(context, Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), null))

    fun openSystemSettings(context: Context) = safeStart(context, Intent(Settings.ACTION_SETTINGS))

    fun safeStart(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot start $intent", e)
        Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        false
    }

    /** Uses the long-standing (non-SDK) StatusBarManager method; silently no-ops if blocked. */
    @SuppressLint("WrongConstant")
    fun expandNotifications(context: Context) {
        try {
            val service = context.getSystemService("statusbar") ?: return
            service.javaClass.getMethod("expandNotificationsPanel").invoke(service)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot expand notifications", t)
        }
    }
}
