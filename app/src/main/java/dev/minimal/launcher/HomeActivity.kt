package dev.minimal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.minimal.launcher.agenda.AgendaAdapter
import dev.minimal.launcher.calendar.AgendaActivity
import dev.minimal.launcher.calendar.EventActivity
import dev.minimal.launcher.reminders.ReminderScheduler
import dev.minimal.launcher.agenda.AgendaItem
import dev.minimal.launcher.agenda.AgendaSource
import dev.minimal.launcher.agenda.AgendaWords
import dev.minimal.launcher.search.Search
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class HomeActivity : Activity() {

    private val app get() = launcherApp

    private lateinit var root: View
    private lateinit var home: SwipeLayout
    private lateinit var favorites: LinearLayout
    private lateinit var homeHint: TextView
    private lateinit var menuAnchor: View
    private lateinit var drawer: SwipeLayout
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var noResults: TextView
    private lateinit var agenda: RecyclerView
    private lateinit var agendaHint: TextView
    private lateinit var agendaAdapter: AgendaAdapter
    private lateinit var agendaSource: AgendaSource
    private lateinit var adapter: AppAdapter

    /** True while the drawer is open or settling open. */
    private var drawerOpen = false
    private var dragUp = false
    /** Keyboard was requested while the window lacked focus (e.g. shade still closing). */
    private var keyboardPending = false
    /** Lost top-resumed state without pausing: how a gesture-nav home swipe looks when we're already home. */
    private var lostTopWithoutPause = false
    private var popup: PopupMenu? = null
    private var visibleApps: List<AppEntry> = emptyList()

    private val interpolator = DecelerateInterpolator(1.5f)
    private val flingVelocity by lazy { resources.displayMetrics.density * 800f }
    private val repoListener: () -> Unit = { onAppsChanged() }
    private val backCallback = OnBackInvokedCallback {
        if (drawerOpen) closeDrawer(animate = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_home)

        root = findViewById(R.id.root)
        home = findViewById(R.id.home)
        favorites = findViewById(R.id.favorites)
        homeHint = findViewById(R.id.home_hint)
        menuAnchor = findViewById(R.id.menu_anchor)
        drawer = findViewById(R.id.drawer)
        search = findViewById(R.id.search)
        list = findViewById(R.id.list)
        noResults = findViewById(R.id.no_results)
        agenda = findViewById(R.id.agenda)
        agendaHint = findViewById(R.id.agenda_hint)

        setupHome()
        setupAgenda()
        setupDrawer()
        applyInsets()

        // Always consume back: it closes the drawer, and is a no-op on the home screen.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback,
        )
        app.repo.addListener(repoListener)
        onAppsChanged()
    }

    override fun onDestroy() {
        app.repo.removeListener(repoListener)
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // Settings may have changed hidden apps or toggles.
        onAppsChanged()
        updateAgenda()
        // Cheap safety net: keep the reminder alarm in step with the calendar.
        if (app.prefs.remindersEnabled) ReminderScheduler.request(this)
    }

    override fun onStop() {
        super.onStop()
        // Coming back home always shows the home screen, never a stale drawer or menu.
        popup?.dismiss()
        if (drawer.visibility == View.VISIBLE) closeDrawer(animate = false)
        // Nothing to watch while home isn't visible; the list starts from the top next time.
        agendaSource.stop()
        agenda.scrollToPosition(0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) Log.d(TAG, "onNewIntent")
        // Home button pressed while already home.
        popup?.dismiss()
        if (drawer.visibility == View.VISIBLE) closeDrawer(animate = true)
        agenda.smoothScrollToPosition(0)
    }

    // ---- Home screen ----

    private fun setupHome() {
        val clock = findViewById<TextClock>(R.id.clock)
        val date = findViewById<TextClock>(R.id.date)
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        date.format12Hour = pattern
        date.format24Hour = pattern
        clock.setOnClickListener { Actions.openAlarms(this) }
        // The date opens Minimal's own agenda; the clock still opens alarms.
        date.setOnClickListener { startActivity(Intent(this, AgendaActivity::class.java)) }

        home.listener = object : SwipeLayout.Listener {
            // Drags that start on the events list scroll it, until it can't scroll that way.
            override fun canDrag(up: Boolean, downX: Float, downY: Float) =
                !drawerOpen && !agendaCanScroll(up, downX, downY)

            override fun onDragStart(up: Boolean) {
                if (BuildConfig.DEBUG) Log.d(TAG, "home dragStart up=$up")
                dragUp = up
                popup?.dismiss()
                if (up) showDrawerForDrag()
            }

            override fun onDrag(dy: Float) {
                if (dragUp) setDrawerOffset(drawerHeight() + dy)
            }

            override fun onDragEnd(dy: Float, velocityY: Float) {
                if (BuildConfig.DEBUG) Log.d(TAG, "home dragEnd up=$dragUp dy=$dy vy=$velocityY ty=${drawer.translationY} h=${drawerHeight()}")
                if (dragUp) {
                    // The last MOVE can lag the finger, and Android 14+ may report zero
                    // velocity on lift, so decide from the final touch position.
                    setDrawerOffset(drawerHeight() + dy)
                    val progress = 1f - drawer.translationY / drawerHeight()
                    settleDrawer(
                        when {
                            velocityY < -flingVelocity -> true
                            velocityY > flingVelocity -> false
                            else -> progress > 0.25f
                        }
                    )
                } else if (dy > resources.displayMetrics.density * 48 || velocityY > flingVelocity) {
                    Actions.expandNotifications(this@HomeActivity)
                }
            }

            override fun onLongPress(x: Float, y: Float) = showHomeMenu(x, y)
        }

        // Gestures are invisible to TalkBack; expose them as actions.
        home.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityAction(R.id.action_open_drawer, getString(R.string.open_apps)))
                info.addAction(AccessibilityAction(R.id.action_home_menu, getString(R.string.home_menu)))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
                when (action) {
                    R.id.action_open_drawer -> { openDrawer(); true }
                    R.id.action_home_menu -> { showHomeMenu(home.width / 2f, home.height / 2f); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
        }
    }

    /** Favourites that are installed right now, in dock order, capped at the dock size. */
    private fun dockEntries(): List<AppEntry> =
        app.prefs.favorites.mapNotNull { app.repo.find(it) }.take(Prefs.MAX_FAVORITES)

    // ---- Upcoming events ----

    private fun setupAgenda() {
        val words = AgendaWords(
            now = getString(R.string.agenda_now),
            tomorrow = getString(R.string.agenda_tomorrow),
            allDay = getString(R.string.agenda_all_day),
        )
        agendaAdapter = AgendaAdapter(words) { item ->
            startActivity(EventActivity.intent(this, item.eventId, item.begin, item.end))
        }
        agenda.layoutManager = LinearLayoutManager(this)
        agenda.adapter = agendaAdapter
        agenda.itemAnimator = null
        agendaSource = AgendaSource(this, ::showAgenda)

        agendaHint.setOnClickListener { requestCalendarAccess() }
        agendaHint.setOnLongClickListener {
            app.prefs.showAgenda = false
            Toast.makeText(this, R.string.agenda_hidden, Toast.LENGTH_SHORT).show()
            updateAgenda()
            true
        }
    }

    /** Applies the setting and permission state: events list, the grant hint, or nothing. */
    private fun updateAgenda() {
        if (!app.prefs.showAgenda) {
            agendaSource.stop()
            agenda.visibility = View.GONE
            agendaHint.visibility = View.GONE
        } else if (!agendaSource.hasPermission()) {
            agendaSource.stop()
            agenda.visibility = View.GONE
            agendaHint.visibility = View.VISIBLE
        } else {
            agendaHint.visibility = View.GONE
            agendaSource.start()
        }
    }

    private fun showAgenda(all: List<AgendaItem>) {
        // User-set row count; beyond it events either scroll in place or are hidden.
        val visibleRows = app.prefs.agendaRows
        val items = if (app.prefs.agendaScroll) all else all.take(visibleRows)
        agendaAdapter.submit(items)
        if (items.isEmpty()) {
            agenda.visibility = View.GONE
            return
        }
        val rows = items.size.coerceAtMost(visibleRows)
        val height = rows * resources.getDimensionPixelSize(R.dimen.agenda_row_height)
        if (agenda.layoutParams.height != height) {
            agenda.layoutParams = agenda.layoutParams.apply { this.height = height }
        }
        agenda.visibility = View.VISIBLE
    }

    private fun requestCalendarAccess() {
        val permission = android.Manifest.permission.READ_CALENDAR
        if (app.prefs.calendarAsked && !shouldShowRequestPermissionRationale(permission)) {
            // Denied with "don't ask again": the system won't show the dialog, so go to app settings.
            Actions.safeStart(
                this,
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null)),
            )
            return
        }
        app.prefs.calendarAsked = true
        requestPermissions(arrayOf(permission), REQUEST_CALENDAR)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALENDAR) updateAgenda()
    }

    /** True if a drag starting at this raw point is on the events list and the list can scroll that way. */
    private fun agendaCanScroll(up: Boolean, rawX: Float, rawY: Float): Boolean {
        if (agenda.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        agenda.getLocationOnScreen(loc)
        val inside = rawX >= loc[0] && rawX < loc[0] + agenda.width && rawY >= loc[1] && rawY < loc[1] + agenda.height
        return inside && agenda.canScrollVertically(if (up) 1 else -1)
    }

    private fun renderFavorites() {
        favorites.removeAllViews()
        val entries = dockEntries()
        val inflater = LayoutInflater.from(this)
        for (entry in entries) {
            val view = inflater.inflate(R.layout.item_favorite, favorites, false) as ImageView
            view.contentDescription = entry.label
            view.tooltipText = entry.label
            app.icons.load(entry) { bitmap -> view.setImageBitmap(bitmap) }
            view.setOnClickListener { Actions.launch(this, entry, it) }
            view.setOnLongClickListener { showFavoriteMenu(entry, it); true }
            favorites.addView(view)
        }
        homeHint.visibility = if (entries.isEmpty() && app.repo.loaded) View.VISIBLE else View.GONE
    }

    private fun showHomeMenu(x: Float, y: Float) {
        menuAnchor.x = x
        menuAnchor.y = y
        showPopup(menuAnchor) { menu ->
            menu.add(0, 1, 0, R.string.wallpaper)
            menu.add(0, 2, 1, R.string.launcher_settings)
            menu.add(0, 3, 2, R.string.system_settings)
            return@showPopup { id ->
                when (id) {
                    1 -> Actions.openWallpaperPicker(this)
                    2 -> Actions.safeStart(this, Intent(this, SettingsActivity::class.java))
                    3 -> Actions.openSystemSettings(this)
                }
            }
        }
    }

    private fun showFavoriteMenu(entry: AppEntry, anchor: View) {
        val dock = dockEntries()
        val index = dock.indexOf(entry)
        // "Left"/"right" are visual; in right-to-left layouts the list runs the other way.
        val rtl = favorites.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val leftNeighbour = dock.getOrNull(if (rtl) index + 1 else index - 1)
        val rightNeighbour = dock.getOrNull(if (rtl) index - 1 else index + 1)
        showPopup(anchor) { menu ->
            if (leftNeighbour != null) menu.add(0, 1, 0, R.string.move_left)
            if (rightNeighbour != null) menu.add(0, 2, 1, R.string.move_right)
            menu.add(0, 3, 2, R.string.remove_from_home)
            menu.add(0, 4, 3, R.string.app_info)
            return@showPopup { id ->
                when (id) {
                    1 -> leftNeighbour?.let { app.prefs.swapFavorites(entry.key, it.key) }
                    2 -> rightNeighbour?.let { app.prefs.swapFavorites(entry.key, it.key) }
                    3 -> app.prefs.removeFavorite(entry.key)
                    4 -> Actions.openAppInfo(this, entry)
                }
                renderFavorites()
            }
        }
    }

    // ---- Drawer ----

    private fun setupDrawer() {
        adapter = AppAdapter(app.icons, onClick = { entry, view -> Actions.launch(this, entry, view) }, onLongClick = ::showAppMenu)
        list.layoutManager = GridLayoutManager(this, spanCount())
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })

        // Placeholder is 20% smaller than typed text; EditText has no separate hint size, so span it.
        search.hint = SpannableString(getString(R.string.search_hint)).apply {
            setSpan(RelativeSizeSpan(0.8f), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })
        search.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) launchTopResult()
            enter
        }

        drawer.listener = object : SwipeLayout.Listener {
            override fun canDrag(up: Boolean, downX: Float, downY: Float) =
                !up && drawerOpen && !list.canScrollVertically(-1)

            override fun onDragStart(up: Boolean) {
                drawer.animate().cancel()
                hideKeyboard()
            }

            override fun onDrag(dy: Float) = setDrawerOffset(dy)

            override fun onDragEnd(dy: Float, velocityY: Float) {
                setDrawerOffset(dy)
                val close = velocityY > flingVelocity ||
                    (velocityY > -flingVelocity && drawer.translationY > drawerHeight() * 0.25f)
                settleDrawer(open = !close)
            }
        }
    }

    private fun launchTopResult() {
        if (search.text.isNullOrBlank()) return
        val entry = adapter.items.firstOrNull() ?: return
        Actions.launch(this, entry, list.findViewHolderForAdapterPosition(0)?.itemView)
    }

    private fun showAppMenu(entry: AppEntry, anchor: View) {
        val isFavorite = app.prefs.isFavorite(entry.key)
        showPopup(anchor, Gravity.END) { menu ->
            menu.add(0, 1, 0, if (isFavorite) R.string.remove_from_home else R.string.add_to_home)
            menu.add(0, 2, 1, R.string.app_info)
            if (entry.packageName != packageName) menu.add(0, 3, 2, R.string.hide_app)
            if (!Actions.isSystemApp(entry)) menu.add(0, 4, 3, R.string.uninstall)
            return@showPopup { id ->
                when (id) {
                    1 -> if (isFavorite) {
                        app.prefs.removeFavorite(entry.key)
                    } else if (!app.prefs.addFavorite(entry.key) { app.repo.find(it) != null }) {
                        Toast.makeText(this, resources.getQuantityString(R.plurals.home_full, Prefs.MAX_FAVORITES, Prefs.MAX_FAVORITES), Toast.LENGTH_SHORT).show()
                    }
                    2 -> Actions.openAppInfo(this, entry)
                    3 -> {
                        app.prefs.hidden = app.prefs.hidden + entry.key
                        Toast.makeText(this, R.string.app_hidden, Toast.LENGTH_SHORT).show()
                    }
                    4 -> Actions.uninstall(this, entry)
                }
                onAppsChanged()
            }
        }
    }

    private fun onAppsChanged() {
        val hidden = app.prefs.hidden
        visibleApps = if (hidden.isEmpty()) app.repo.apps else app.repo.apps.filter { it.key !in hidden }
        filter()
        renderFavorites()
    }

    private fun filter() {
        val query = search.text?.toString().orEmpty()
        val result = if (query.isBlank()) {
            visibleApps
        } else {
            Search.rank(visibleApps, query, { it.searchKey }, { app.prefs.launchCount(it.key) })
        }
        // While searching, results stack up from the bottom so the best match is right above
        // the search bar (one-handed reach). Browsing A-Z reads top-down as normal.
        (list.layoutManager as? GridLayoutManager)?.reverseLayout = query.isNotBlank()
        adapter.submit(result, highlightFirst = query.isNotBlank())
        noResults.visibility = if (query.isNotBlank() && result.isEmpty()) View.VISIBLE else View.GONE
        list.scrollToPosition(0)
    }

    /** Columns that fit at the target cell width: 4 on phones, more on wide screens. */
    private fun spanCount(): Int {
        val cellDp = resources.getDimension(R.dimen.drawer_cell_width) / resources.displayMetrics.density
        return (resources.configuration.screenWidthDp / cellDp).toInt().coerceIn(4, 6)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        (list.layoutManager as? GridLayoutManager)?.spanCount = spanCount()
    }

    private fun drawerHeight(): Float =
        (root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()

    private fun setDrawerOffset(offset: Float) {
        drawer.translationY = offset.coerceIn(0f, drawerHeight())
        syncHomeFade()
    }

    /** The drawer is translucent, so fade the clock and dock out as it rises; gone by ~2/3 open. */
    private fun syncHomeFade() {
        val open = if (drawer.visibility == View.VISIBLE) 1f - drawer.translationY / drawerHeight() else 0f
        home.alpha = (1f - open * 1.5f).coerceIn(0f, 1f)
    }

    private fun showDrawerForDrag() {
        drawer.animate().cancel()
        if (drawer.visibility != View.VISIBLE) {
            drawer.translationY = drawerHeight()
            drawer.visibility = View.VISIBLE
            drawer.requestApplyInsets()
        }
    }

    private fun openDrawer() {
        showDrawerForDrag()
        settleDrawer(open = true)
    }

    private fun settleDrawer(open: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "settleDrawer open=$open ty=${drawer.translationY}")
        val height = drawerHeight()
        val target = if (open) 0f else height
        val remaining = abs(drawer.translationY - target) / height
        drawerOpen = open
        if (!open) hideKeyboard()
        drawer.animate()
            .translationY(target)
            .setDuration((ANIM_MS * remaining).toLong().coerceAtLeast(60))
            .setInterpolator(interpolator)
            .setUpdateListener { syncHomeFade() }
            .withEndAction { if (open) onDrawerOpened() else onDrawerClosed() }
            .start()
    }

    private fun closeDrawer(animate: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "closeDrawer animate=$animate")
        if (animate) {
            settleDrawer(open = false)
        } else {
            drawer.animate().cancel()
            drawerOpen = false
            hideKeyboard()
            drawer.translationY = drawerHeight()
            onDrawerClosed()
        }
    }

    private fun onDrawerOpened() {
        if (!drawerOpen) return
        // Always focus search (hardware keyboards type straight in); only the soft keyboard is optional.
        search.requestFocus()
        if (app.prefs.autoKeyboard) showKeyboard()
    }

    private fun showKeyboard() {
        search.requestFocus()
        if (!hasWindowFocus()) {
            // The IME ignores requests from unfocused windows; retry in onWindowFocusChanged.
            keyboardPending = true
            return
        }
        keyboardPending = false
        search.post {
            if (drawerOpen) search.windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    override fun onPause() {
        super.onPause()
        lostTopWithoutPause = false
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (BuildConfig.DEBUG) Log.d(TAG, "topResumed=$isTopResumedActivity")
        // With gesture navigation and a third-party home app, a home swipe while we're already
        // home is often played as "return to the current app": no onNewIntent, no onPause, just a
        // brief loss of top-resumed state. Treat that like pressing Home. (Dialogs from other apps
        // pause us first; the notification shade doesn't change top-resumed state at all.)
        if (!isTopResumedActivity) {
            lostTopWithoutPause = true
        } else if (lostTopWithoutPause) {
            lostTopWithoutPause = false
            popup?.dismiss()
            if (drawerOpen) closeDrawer(animate = true)
            agenda.smoothScrollToPosition(0)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && keyboardPending && drawerOpen) showKeyboard()
    }

    private fun onDrawerClosed() {
        if (drawerOpen) return
        drawer.visibility = View.GONE
        home.alpha = 1f
        search.clearFocus()
        if (!search.text.isNullOrEmpty()) search.text.clear() else list.scrollToPosition(0)
    }

    private fun hideKeyboard() {
        keyboardPending = false
        search.windowInsetsController?.hide(WindowInsets.Type.ime())
    }

    // ---- Shared ----

    private fun showPopup(
        anchor: View,
        gravity: Int = Gravity.NO_GRAVITY,
        build: (android.view.Menu) -> (Int) -> Unit,
    ) {
        popup?.dismiss()
        val menu = PopupMenu(this, anchor, gravity)
        val onClick = build(menu.menu)
        menu.setOnMenuItemClickListener { item -> onClick(item.itemId); true }
        menu.setOnDismissListener { if (popup === it) popup = null }
        popup = menu
        menu.show()
    }

    private fun applyInsets() {
        val left = home.paddingLeft
        val top = home.paddingTop
        val right = home.paddingRight
        val bottom = home.paddingBottom
        home.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }

        // Search sits at the bottom, so the drawer content's bottom padding follows the keyboard.
        // The animation callback moves it with the keyboard frame by frame instead of jumping.
        val content = findViewById<View>(R.id.drawer_content)
        fun padFor(insets: WindowInsets) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            content.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
        }
        drawer.setOnApplyWindowInsetsListener { _, insets ->
            padFor(insets)
            insets
        }
        drawer.setWindowInsetsAnimationCallback(
            object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsets,
                    running: MutableList<WindowInsetsAnimation>,
                ): WindowInsets {
                    padFor(insets)
                    return insets
                }
            }
        )
    }

    private companion object {
        const val TAG = "Home"
        const val ANIM_MS = 220f
        const val REQUEST_CALENDAR = 1
    }
}
