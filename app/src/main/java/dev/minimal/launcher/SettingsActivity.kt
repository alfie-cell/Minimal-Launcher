package dev.minimal.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.minimal.launcher.calendar.CalendarSettingsActivity

class SettingsActivity : Activity() {

    private val prefs get() = launcherApp.prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.view.View>(R.id.row_default_home).setOnClickListener { requestHomeRole() }
        findViewById<android.view.View>(R.id.row_calendar).setOnClickListener {
            startActivity(Intent(this, CalendarSettingsActivity::class.java))
        }

        findViewById<Switch>(R.id.switch_agenda).apply {
            isChecked = prefs.showAgenda
            setOnCheckedChangeListener { _, checked -> prefs.showAgenda = checked }
        }
        findViewById<Switch>(R.id.switch_keyboard).apply {
            isChecked = prefs.autoKeyboard
            setOnCheckedChangeListener { _, checked -> prefs.autoKeyboard = checked }
        }
        findViewById<Switch>(R.id.switch_agenda_scroll).apply {
            isChecked = prefs.agendaScroll
            setOnCheckedChangeListener { _, checked -> prefs.agendaScroll = checked; refresh() }
        }
        findViewById<android.view.View>(R.id.row_agenda_rows).setOnClickListener { chooseAgendaRows() }
        findViewById<android.view.View>(R.id.row_hidden).setOnClickListener { showHiddenApps() }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        findViewById<TextView>(R.id.version_summary).text = version.orEmpty()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val rows = prefs.agendaRows
        findViewById<TextView>(R.id.agenda_rows_summary).text = resources.getQuantityString(
            if (prefs.agendaScroll) R.plurals.pref_agenda_rows_summary else R.plurals.pref_agenda_rows_summary_noscroll,
            rows, rows,
        )
        findViewById<TextView>(R.id.default_home_summary).setText(
            if (isDefaultHome()) R.string.pref_default_home_on else R.string.pref_default_home_off
        )
        val count = prefs.hidden.size
        findViewById<TextView>(R.id.hidden_summary).text =
            if (count == 0) getString(R.string.pref_hidden_none) else resources.getQuantityString(R.plurals.pref_hidden_count, count, count)
    }

    private fun isDefaultHome(): Boolean = try {
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION") // startActivityForResult is fine on a plain Activity.
    private fun requestHomeRole() {
        try {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
                return
            }
        } catch (e: Exception) {
            // fall through to system settings
        }
        Actions.safeStart(this, Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun chooseAgendaRows() {
        val options = (1..Prefs.MAX_AGENDA_ROWS).map { it.toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_agenda_rows)
            .setSingleChoiceItems(options, prefs.agendaRows - 1) { dialog, which ->
                prefs.agendaRows = which + 1
                refresh()
                dialog.dismiss()
            }
            .show()
    }

    private fun showHiddenApps() {
        val repo = launcherApp.repo
        val keys = prefs.hidden.toList()
            .map { it to (repo.find(it)?.label ?: it.substringBefore('/')) }
            .sortedBy { it.second.lowercase() }
        if (keys.isEmpty()) {
            Toast.makeText(this, R.string.pref_hidden_none, Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(keys.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_apps_hint)
            .setMultiChoiceItems(keys.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                prefs.hidden = keys.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                refresh()
            }
            .show()
    }
}
