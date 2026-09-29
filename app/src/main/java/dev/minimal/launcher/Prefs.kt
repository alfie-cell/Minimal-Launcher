package dev.minimal.launcher

import android.content.Context
import dev.minimal.launcher.reminders.CalendarReminderSettings
import dev.minimal.launcher.reminders.ReminderOverride
import org.json.JSONArray
import org.json.JSONObject

/** Small key/value store. App keys are [AppEntry.key], so they survive app updates. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val counts = context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE)
    private val overrides = context.getSharedPreferences("reminder_overrides", Context.MODE_PRIVATE)

    /** Ordered list of home-screen apps. */
    var favorites: List<String>
        get() = sp.getString(KEY_FAVORITES, null)
            ?.split('\n')
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        set(value) = sp.edit().putString(KEY_FAVORITES, value.distinct().joinToString("\n")).apply()

    fun isFavorite(key: String) = key in favorites

    /** Adds [key] unless the dock already shows [MAX_FAVORITES] installed apps. */
    fun addFavorite(key: String, isInstalled: (String) -> Boolean): Boolean {
        val current = favorites
        if (key in current) return true
        if (current.count(isInstalled) >= MAX_FAVORITES) return false
        favorites = current + key
        return true
    }

    fun removeFavorite(key: String) {
        favorites = favorites - key
    }

    /** Swaps two favourites' positions (used to move an icon past its visible neighbour). */
    fun swapFavorites(a: String, b: String) {
        val list = favorites.toMutableList()
        val i = list.indexOf(a)
        val j = list.indexOf(b)
        if (i < 0 || j < 0) return
        list[i] = b
        list[j] = a
        favorites = list
    }

    var hidden: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN, null)?.toSet().orEmpty()
        set(value) = sp.edit().putStringSet(KEY_HIDDEN, HashSet(value)).apply()

    fun launchCount(key: String): Int = counts.getInt(key, 0)

    fun recordLaunch(key: String) {
        counts.edit().putInt(key, launchCount(key) + 1).apply()
    }

    var showAgenda: Boolean
        get() = sp.getBoolean(KEY_SHOW_AGENDA, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_AGENDA, value).apply()

    /** Upcoming-event rows shown under the date before scrolling (or hiding, see [agendaScroll]). */
    var agendaRows: Int
        get() = sp.getInt(KEY_AGENDA_ROWS, DEFAULT_AGENDA_ROWS).coerceIn(1, MAX_AGENDA_ROWS)
        set(value) = sp.edit().putInt(KEY_AGENDA_ROWS, value.coerceIn(1, MAX_AGENDA_ROWS)).apply()

    /** true: more events scroll in place; false: only [agendaRows] are shown. */
    var agendaScroll: Boolean
        get() = sp.getBoolean(KEY_AGENDA_SCROLL, true)
        set(value) = sp.edit().putBoolean(KEY_AGENDA_SCROLL, value).apply()

    // ---- Event reminders ----

    var remindersEnabled: Boolean
        get() = sp.getBoolean(KEY_REMINDERS, false)
        set(value) = sp.edit().putBoolean(KEY_REMINDERS, value).apply()

    /** Per-calendar defaults; unset calendars use [CalendarReminderSettings.DEFAULT]. */
    fun reminderSettings(calendarId: Long): CalendarReminderSettings {
        val d = CalendarReminderSettings.DEFAULT
        return CalendarReminderSettings(
            timedMinutes = intList("rem_timed_list_$calendarId", "rem_timed_$calendarId", d.timedMinutes),
            allDayOffsets = intList("rem_allday_list_$calendarId", "rem_allday_$calendarId", d.allDayOffsets),
            // null = default notification sound, "" = silent, else a ringtone URI.
            soundUri = sp.getString("rem_sound_$calendarId", null),
        )
    }

    fun setReminderSettings(calendarId: Long, settings: CalendarReminderSettings) {
        sp.edit()
            .putString("rem_timed_list_$calendarId", settings.timedMinutes.joinToString(","))
            .putString("rem_allday_list_$calendarId", settings.allDayOffsets.joinToString(","))
            .remove("rem_timed_$calendarId")
            .remove("rem_allday_$calendarId")
            .apply {
                if (settings.soundUri == null) remove("rem_sound_$calendarId")
                else putString("rem_sound_$calendarId", settings.soundUri)
            }
            .apply()
    }

    /** Comma list; falls back to the single-value key used by the first reminders release. */
    private fun intList(key: String, legacyKey: String, default: List<Int>): List<Int> {
        sp.getString(key, null)?.let { v -> return v.split(',').mapNotNull { it.trim().toIntOrNull() } }
        if (sp.contains(legacyKey)) {
            val v = sp.getInt(legacyKey, NONE)
            return if (v == NONE) emptyList() else listOf(v)
        }
        return default
    }

    /** User overrides for one occurrence or a series, keyed by [ReminderPlanner] keys. */
    fun reminderOverride(key: String): ReminderOverride? = overrides.getString(key, null)?.let(::decodeOverride)

    fun allReminderOverrides(): Map<String, ReminderOverride> =
        overrides.all.mapNotNull { (k, v) -> (v as? String)?.let(::decodeOverride)?.let { k to it } }.toMap()

    fun setReminderOverride(key: String, value: ReminderOverride?) {
        if (value == null || value.isEmpty) overrides.edit().remove(key).apply()
        else overrides.edit().putString(key, encodeOverride(value)).apply()
    }

    private fun encodeOverride(o: ReminderOverride): String = JSONObject().apply {
        o.minutes?.let { put("m", JSONArray(it)) }
        o.soundUri?.let { put("s", it) }
    }.toString()

    private fun decodeOverride(json: String): ReminderOverride? = try {
        val o = JSONObject(json)
        val minutes = o.optJSONArray("m")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        ReminderOverride(minutes, if (o.has("s")) o.getString("s") else null)
    } catch (_: Exception) {
        null
    }

    /** Whether the calendar permission has been requested before (to detect "don't ask again"). */
    var calendarAsked: Boolean
        get() = sp.getBoolean(KEY_CALENDAR_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_CALENDAR_ASKED, value).apply()

    var autoKeyboard: Boolean
        get() = sp.getBoolean(KEY_AUTO_KEYBOARD, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_KEYBOARD, value).apply()

    companion object {
        const val MAX_FAVORITES = 5
        const val DEFAULT_AGENDA_ROWS = 3
        const val MAX_AGENDA_ROWS = 6
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_AUTO_KEYBOARD = "auto_keyboard"
        private const val KEY_SHOW_AGENDA = "show_agenda"
        private const val KEY_CALENDAR_ASKED = "calendar_asked"
        private const val KEY_AGENDA_ROWS = "agenda_rows"
        private const val KEY_AGENDA_SCROLL = "agenda_scroll"
        private const val KEY_REMINDERS = "reminders_enabled"
        private const val NONE = Int.MIN_VALUE
    }
}
