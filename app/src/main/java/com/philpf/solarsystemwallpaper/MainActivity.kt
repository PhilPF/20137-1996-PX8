package com.philpf.solarsystemwallpaper

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Single-screen app: the live scene fills the screen edge-to-edge (drag to rotate, pinch to
 * zoom — the camera the wallpaper and widget use), with floating glass chrome on top:
 *
 * - top capsule: name, a dot showing the asteroid data status, and the simulated date
 *   (tap it for the data details);
 * - bottom tab bar: Time, Style, View, Data. Tapping a tab opens its options in a sheet above
 *   the bar; tapping it again closes the sheet. Adding the widget and setting the live
 *   wallpaper live in the View tab.
 *
 * The chrome fades while you drag the scene so it doesn't hide what you're positioning.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: WallpaperPrefs
    private lateinit var root: FrameLayout
    private lateinit var previewView: SolarSystemPreviewView
    private lateinit var hudPanel: GlassPanel
    private lateinit var tabBarPanel: GlassPanel
    private lateinit var optionsSheet: GlassPanel
    private lateinit var tabBarContainer: FrameLayout
    private lateinit var optionsScroll: MaxHeightScrollView
    private lateinit var optionsContent: LinearLayout
    private lateinit var dateText: TextView
    private lateinit var statusDot: View

    private lateinit var tabBar: GlassSegmentedControl
    private val pages = ArrayList<LinearLayout>()
    private var currentTab = TAB_TIME

    private val toggles = ArrayList<Pair<GlassToggle, () -> Boolean>>()
    private val secondaryButtons = ArrayList<TextView>()
    private val accentTexts = ArrayList<TextView>()
    private lateinit var sizeSlider: GlassSlider
    private lateinit var sizeValue: TextView
    private lateinit var distanceControl: GlassSegmentedControl
    private lateinit var distanceNote: TextView
    private lateinit var goHomeLabel: TextView
    private lateinit var forgetViewRow: View
    private lateinit var forgetViewDivider: View
    private lateinit var timeModeControl: GlassSegmentedControl
    private lateinit var speedControl: GlassSegmentedControl
    private lateinit var loopControl: GlassSegmentedControl
    private lateinit var realTimeGroup: View
    private lateinit var customGroup: LinearLayout
    private lateinit var startDateValue: TextView
    private lateinit var swatchRow: LinearLayout
    private lateinit var dataStatusDot: View
    private lateinit var dataStatusValue: TextView
    private lateinit var dataSourceValue: TextView
    private lateinit var dataUpdatedValue: TextView

    private var sheetOpen = false
    private var fetchInFlight = false
    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            refreshDate()
            handler.postDelayed(this, 250)
        }
    }
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSheetOpen(false)
    }

    private val dateFormat = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault())
    private val startFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
    private val updatedFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    private val speedValues by lazy { resources.getStringArray(R.array.speed_values).map { it.toDouble() } }
    private val loopValues by lazy { resources.getStringArray(R.array.loop_values).map { it.toDouble() } }
    private val swatchValues by lazy { resources.getStringArray(R.array.asteroid_color_values) }
    private val swatchNames by lazy { resources.getStringArray(R.array.asteroid_color_entries) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        prefs = WallpaperPrefs(this)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        previewView = findViewById(R.id.previewView)
        hudPanel = findViewById(R.id.hudPanel)
        tabBarPanel = findViewById(R.id.tabBarPanel)
        optionsSheet = findViewById(R.id.optionsSheet)
        tabBarContainer = findViewById(R.id.tabBarContainer)
        optionsScroll = findViewById(R.id.optionsScroll)
        optionsContent = findViewById(R.id.optionsContent)
        dateText = findViewById(R.id.dateText)
        statusDot = findViewById(R.id.statusDot)

        listOf(hudPanel, tabBarPanel, optionsSheet).forEach { it.attachScene(previewView) }

        buildOptions()
        showTab(currentTab, animate = false)
        tabBar.select(-1, animate = false)

        findViewById<View>(R.id.hudContent).setOnClickListener {
            if (sheetOpen && currentTab == TAB_DATA) setSheetOpen(false) else openTab(TAB_DATA)
        }
        previewView.onInteraction = { active -> fadeChrome(active) }
        onBackPressedDispatcher.addCallback(this, backCallback)

        applyWindowInsets()
        tabBarPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { positionSheet() } }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { positionSheet() } }

        refreshAll()
        if (shouldAutoFetch()) fetchAsteroidOrbit()
    }

    override fun onResume() {
        super.onResume()
        previewView.loadSavedCamera()
        refreshAll()
        handler.post(clockTick)
    }

    override fun onPause() {
        handler.removeCallbacks(clockTick)
        super.onPause()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun currentAccent(): Int =
        try {
            Color.parseColor(prefs.asteroidColor)
        } catch (_: IllegalArgumentException) {
            Color.parseColor(WallpaperPrefs.DEFAULT_ACCENT_HEX)
        }

    // ---- Layout -------------------------------------------------------------------------------

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (hudPanel.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = dp(10f) + bars.top
                marginStart = dp(16f) + bars.left
                marginEnd = dp(16f) + bars.right
            }
            (tabBarPanel.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = dp(12f) + bars.bottom
                marginStart = dp(12f) + bars.left
                marginEnd = dp(12f) + bars.right
            }
            (optionsSheet.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = dp(12f) + bars.left
                marginEnd = dp(12f) + bars.right
            }
            hudPanel.requestLayout()
            tabBarPanel.requestLayout()
            root.post { positionSheet() }
            insets
        }
    }

    /** Keeps the options sheet just above the tab bar, capped so it never covers the top capsule. */
    private fun positionSheet() {
        if (root.height == 0) return
        val sheetLp = optionsSheet.layoutParams as FrameLayout.LayoutParams
        val barLp = tabBarPanel.layoutParams as ViewGroup.MarginLayoutParams
        val bottom = tabBarPanel.height + barLp.bottomMargin + dp(10f)
        if (sheetLp.bottomMargin != bottom) {
            sheetLp.bottomMargin = bottom
            optionsSheet.layoutParams = sheetLp
        }
        val hudBottom = (hudPanel.layoutParams as ViewGroup.MarginLayoutParams).topMargin + hudPanel.height
        val available = min(root.height - bottom - hudBottom - dp(12f), (root.height * 0.62f).toInt())
        optionsScroll.maxHeightPx = max(dp(160f), available)
    }

    private fun setSheetOpen(open: Boolean) {
        if (open == sheetOpen) return
        sheetOpen = open
        backCallback.isEnabled = open
        tabBar.select(if (open) currentTab else -1, animate = true)
        optionsSheet.animate().cancel()
        if (open) {
            positionSheet()
            optionsSheet.visibility = View.VISIBLE
            optionsSheet.alpha = 0f
            optionsSheet.translationY = dp(24f).toFloat()
            optionsSheet.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        } else {
            optionsSheet.animate().alpha(0f).translationY(dp(24f).toFloat()).setDuration(180)
                .withEndAction { optionsSheet.visibility = View.GONE }.start()
        }
    }

    /** Opens the sheet on [index], or switches to it if the sheet is already open. */
    private fun openTab(index: Int) {
        if (sheetOpen) {
            showTab(index, animate = true)
        } else {
            showTab(index, animate = false)
            setSheetOpen(true)
        }
    }

    private fun showTab(index: Int, animate: Boolean) {
        currentTab = index
        // While closed the bar shows no selection; setSheetOpen() fades the pill in.
        if (sheetOpen && tabBar.selectedIndex != index) tabBar.select(index, animate)
        if (animate && sheetOpen) {
            TransitionManager.beginDelayedTransition(
                root,
                ChangeBounds().setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).addTarget(optionsSheet),
            )
        }
        for ((i, page) in pages.withIndex()) page.visibility = if (i == index) View.VISIBLE else View.GONE
        optionsScroll.scrollTo(0, 0)
        if (animate) {
            val page = pages[index]
            page.alpha = 0f
            page.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun fadeChrome(active: Boolean) {
        val target = if (active) 0.15f else 1f
        val duration = if (active) 160L else 320L
        hudPanel.animate().alpha(target).setDuration(duration).start()
        tabBarPanel.animate().alpha(target).setDuration(duration).start()
        if (sheetOpen) optionsSheet.animate().alpha(target).translationY(0f).setDuration(duration).start()
    }

    // ---- Building the options -----------------------------------------------------------------

    private fun buildOptions() {
        tabBar = GlassSegmentedControl(this).apply {
            options = listOf(R.string.tab_time, R.string.tab_style, R.string.tab_view, R.string.tab_data).map { getString(it) }
            allowNone = true
            heightDp = 46f
            textSizeSp = 14.5f
            onSelect = { openTab(it) }
            onReselect = { setSheetOpen(false) }
        }
        tabBarContainer.addView(tabBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        optionsContent.removeAllViews()
        pages.clear()
        repeat(4) {
            val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pages.add(page)
            optionsContent.addView(page, matchWrap())
        }
        buildTimePage(pages[TAB_TIME])
        buildStylePage(pages[TAB_STYLE])
        buildViewPage(pages[TAB_VIEW])
        buildDataPage(pages[TAB_DATA])
    }

    private fun buildTimePage(page: LinearLayout) {
        timeModeControl = segmented(page, listOf(getString(R.string.time_real), getString(R.string.time_custom)), top = 10f) { idx ->
            prefs.setTimeModeIsRealTime(idx == 0)
            refreshAll()
        }
        realTimeGroup = footnote(page, R.string.real_time_note)

        customGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(customGroup, matchWrap())

        val startGroup = group(customGroup, top = 14f)
        startDateValue = valueText()
        valueRow(startGroup, getString(R.string.start_date), startDateValue) { pickStartDate() }
        divider(startGroup)
        actionRow(startGroup, getString(R.string.start_from_now)) {
            prefs.setCustomStartDate(System.currentTimeMillis())
            refreshAll()
        }

        caption(customGroup, R.string.speed_caption)
        speedControl = segmented(customGroup, resources.getStringArray(R.array.speed_labels).toList()) { idx ->
            prefs.setSpeedDaysPerSec(speedValues[idx])
            refreshAll()
        }
        caption(customGroup, R.string.loop_caption)
        loopControl = segmented(customGroup, resources.getStringArray(R.array.loop_labels).toList()) { idx ->
            prefs.setLoopYears(loopValues[idx])
            refreshAll()
        }
        footnote(customGroup, R.string.custom_note)
    }

    private fun buildStylePage(page: LinearLayout) {
        caption(page, R.string.accent_caption, top = 10f)
        val swatchGroup = group(page)
        swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        swatchGroup.addView(swatchRow, matchWrap())
        for ((idx, hex) in swatchValues.withIndex()) {
            val cell = FrameLayout(this)
            val swatch = View(this).apply {
                isClickable = true
                isFocusable = true
                tag = swatchNames.getOrElse(idx) { hex }
                setOnClickListener {
                    prefs.setAsteroidColor(hex)
                    refreshAll()
                }
            }
            cell.addView(swatch, FrameLayout.LayoutParams(dp(38f), dp(38f), Gravity.CENTER))
            swatchRow.addView(cell, LinearLayout.LayoutParams(0, dp(44f), 1f))
        }

        caption(page, R.string.size_caption)
        val sizes = group(page)
        sizeValue = valueText()
        valueRow(sizes, getString(R.string.body_size), sizeValue)
        sizeSlider = GlassSlider(this).apply {
            contentDescription = getString(R.string.body_size)
            onChange = { v ->
                prefs.setBodyScale(sliderToScale(v), redrawWidgets = false)
                sizeValue.text = getString(R.string.body_size_value, prefs.bodyScale)
                previewView.refreshNow()
            }
            onRelease = { v -> prefs.setBodyScale(sliderToScale(v), redrawWidgets = true) }
        }
        sizes.addView(sizeSlider, matchWrap().apply {
            marginStart = dp(10f)
            marginEnd = dp(8f)
            bottomMargin = dp(8f)
        })
        divider(sizes)
        toggleRow(sizes, R.string.toggle_relative_sizes, R.string.toggle_relative_sizes_note, { prefs.relativeSizes }) {
            prefs.setRelativeSizes(it)
        }

        val rows = group(page, top = 14f)
        toggleRow(rows, R.string.toggle_planet_labels, null, { prefs.showPlanetLabels }) { prefs.setShowPlanetLabels(it) }
        divider(rows)
        toggleRow(rows, R.string.toggle_asteroid_label, null, { prefs.showAsteroidLabel }) { prefs.setShowAsteroidLabel(it) }
        divider(rows)
        toggleRow(rows, R.string.toggle_monochrome, null, { prefs.monochrome }) { prefs.setMonochrome(it) }
    }

    private fun buildViewPage(page: LinearLayout) {
        caption(page, R.string.distance_caption, top = 10f)
        val scales = listOf(DistanceScale.LINEAR, DistanceScale.SQRT, DistanceScale.LOG)
        distanceControl = segmented(
            page,
            listOf(getString(R.string.scale_linear), getString(R.string.scale_sqrt), getString(R.string.scale_log)),
        ) { idx ->
            prefs.setDistanceScale(scales[idx])
            refreshAll()
        }
        distanceNote = footnote(page, R.string.scale_sqrt_note)

        caption(page, R.string.camera_caption)
        val camera = group(page)
        goHomeLabel = actionRow(camera, getString(R.string.go_to_saved_view)) { previewView.goToHomeView() }
        divider(camera)
        actionRow(camera, getString(R.string.save_view)) {
            previewView.saveCurrentAsHomeView()
            Toast.makeText(this, getString(R.string.view_saved), Toast.LENGTH_SHORT).show()
            refreshAll()
        }
        forgetViewDivider = divider(camera)
        val forgetLabel = actionRow(camera, getString(R.string.forget_saved_view), accent = false) {
            prefs.forgetSavedView()
            refreshAll()
        }
        forgetViewRow = forgetLabel.parent as View
        footnote(page, R.string.camera_note)

        caption(page, R.string.home_caption)
        val home = group(page)
        actionRow(home, getString(R.string.add_widget)) { addWidget() }
        divider(home)
        actionRow(home, getString(R.string.set_wallpaper)) { setLiveWallpaper() }

        caption(page, R.string.motion_caption)
        val rows = group(page)
        toggleRow(rows, R.string.toggle_drift, R.string.toggle_drift_note, { prefs.azimuthDrift }) { prefs.setAzimuthDrift(it) }
        divider(rows)
        toggleRow(rows, R.string.toggle_parallax, R.string.toggle_parallax_note, { prefs.parallax }) { prefs.setParallax(it) }
    }

    private fun sliderToScale(v: Float): Float {
        val min = WallpaperPrefs.MIN_BODY_SCALE
        val max = WallpaperPrefs.MAX_BODY_SCALE
        val scale = (min * Math.pow((max / min).toDouble(), v.toDouble())).toFloat()
        return if (abs(scale - 1f) < 0.04f) 1f else scale // Easy to land exactly on 1x.
    }

    private fun scaleToSlider(scale: Float): Float {
        val min = WallpaperPrefs.MIN_BODY_SCALE
        val max = WallpaperPrefs.MAX_BODY_SCALE
        return (ln((scale / min).toDouble()) / ln((max / min).toDouble())).toFloat()
    }

    private fun buildDataPage(page: LinearLayout) {
        val info = group(page, top = 10f)
        dataStatusDot = View(this)
        dataStatusValue = valueText()
        val statusRow = row(info)
        statusRow.addView(rowTitle(getString(R.string.data_status)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(dataStatusDot, LinearLayout.LayoutParams(dp(7f), dp(7f)).apply { marginEnd = dp(8f) })
        statusRow.addView(dataStatusValue)
        divider(info)
        dataSourceValue = valueText()
        valueRow(info, getString(R.string.data_source), dataSourceValue)
        divider(info)
        dataUpdatedValue = valueText()
        valueRow(info, getString(R.string.data_updated), dataUpdatedValue)

        page.addView(secondaryButton(getString(R.string.refresh_data)) { fetchAsteroidOrbit() }, matchWrap(top = 14f))
        footnote(page, R.string.data_explainer)
    }

    // ---- Building blocks ----------------------------------------------------------------------

    private fun matchWrap(top: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    /** Rounded translucent block holding a few rows. */
    private fun group(parent: LinearLayout, top: Float = 0f): LinearLayout {
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.groupBackground(this@MainActivity)
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        }
        parent.addView(g, matchWrap(top))
        return g
    }

    private fun row(group: LinearLayout, onClick: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50f)
            setPadding(dp(10f), dp(6f), dp(8f), dp(6f))
            if (onClick != null) {
                background = Glass.rowBackground(this@MainActivity)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        group.addView(r, matchWrap())
        return r
    }

    private fun rowTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Glass.TEXT_PRIMARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
    }

    private fun valueText() = TextView(this).apply {
        setTextColor(Glass.TEXT_SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        gravity = Gravity.END
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun valueRow(group: LinearLayout, title: String, value: TextView, onClick: (() -> Unit)? = null) {
        val r = row(group, onClick)
        r.addView(rowTitle(title), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16f) })
    }

    private fun divider(group: LinearLayout): View {
        val line = View(this).apply { setBackgroundColor(Glass.HAIRLINE) }
        group.addView(
            line,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(0.6f))).apply {
                marginStart = dp(10f)
                marginEnd = dp(4f)
            },
        )
        return line
    }

    /** Tappable row whose title is the action, tinted with the accent unless [accent] is false. */
    private fun actionRow(group: LinearLayout, title: String, accent: Boolean = true, onClick: () -> Unit): TextView {
        val label = rowTitle(title)
        if (accent) accentTexts.add(label) else label.setTextColor(Glass.TEXT_SECONDARY)
        row(group, onClick).addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return label
    }

    private fun caption(parent: LinearLayout, textRes: Int, top: Float = 16f) {
        parent.addView(
            TextView(this).apply {
                text = getString(textRes)
                setTextColor(Glass.TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            },
            matchWrap(top).apply {
                bottomMargin = dp(8f)
                marginStart = dp(6f)
            },
        )
    }

    private fun footnote(parent: LinearLayout, textRes: Int): TextView {
        val t = TextView(this).apply {
            text = getString(textRes)
            setTextColor(Glass.TEXT_TERTIARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.15f)
        }
        parent.addView(t, matchWrap(top = 10f).apply {
            marginStart = dp(6f)
            marginEnd = dp(6f)
        })
        return t
    }

    private fun segmented(
        parent: LinearLayout,
        labels: List<String>,
        top: Float = 0f,
        onSelect: (Int) -> Unit,
    ): GlassSegmentedControl {
        val control = GlassSegmentedControl(this).apply {
            options = labels
            this.onSelect = onSelect
        }
        parent.addView(control, matchWrap(top))
        return control
    }

    private fun toggleRow(group: LinearLayout, titleRes: Int, noteRes: Int?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val toggle = GlassToggle(this).apply {
            contentDescription = getString(titleRes)
            onChange = { value ->
                set(value)
                refreshAll()
            }
        }
        val r = row(group, onClick = { toggle.performClick() })
        r.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        texts.addView(rowTitle(getString(titleRes)))
        if (noteRes != null) {
            texts.addView(
                TextView(this).apply {
                    text = getString(noteRes)
                    setTextColor(Glass.TEXT_TERTIARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                },
                matchWrap(top = 1f),
            )
        }
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12f) })
        r.addView(toggle)
        toggles.add(toggle to get)
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener { onClick() }
            secondaryButtons.add(this)
        }

    // ---- State -> UI --------------------------------------------------------------------------

    private fun refreshAll() {
        val accent = currentAccent()
        secondaryButtons.forEach { Glass.styleButton(it, primary = false, accent = accent) }
        accentTexts.forEach { it.setTextColor(accent) }

        val selected = prefs.asteroidColor
        for (i in 0 until swatchRow.childCount) {
            val swatch = (swatchRow.getChildAt(i) as ViewGroup).getChildAt(0)
            val hex = swatchValues[i]
            val isSelected = hex.equals(selected, ignoreCase = true)
            swatch.background = Glass.swatch(this, Color.parseColor(hex), isSelected)
            val name = swatch.tag as String
            swatch.contentDescription = if (isSelected) getString(R.string.color_selected, name) else name
        }

        for ((toggle, get) in toggles) {
            toggle.accent = accent
            if (toggle.isOn != get()) toggle.setOn(get(), animate = false)
        }

        val realTime = prefs.timeModeIsRealTime
        val modeIdx = if (realTime) 0 else 1
        if (timeModeControl.selectedIndex != modeIdx) timeModeControl.select(modeIdx, animate = false)
        val speedIdx = nearestIndex(speedValues, prefs.speedDaysPerSec)
        if (speedControl.selectedIndex != speedIdx) speedControl.select(speedIdx, animate = false)
        val loopIdx = nearestIndex(loopValues, prefs.loopYears)
        if (loopControl.selectedIndex != loopIdx) loopControl.select(loopIdx, animate = false)
        val customVisibility = if (realTime) View.GONE else View.VISIBLE
        if (customGroup.visibility != customVisibility) {
            if (sheetOpen) {
                TransitionManager.beginDelayedTransition(
                    root,
                    ChangeBounds().setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).addTarget(optionsSheet),
                )
            }
            customGroup.visibility = customVisibility
        }
        realTimeGroup.visibility = if (realTime) View.VISIBLE else View.GONE
        startDateValue.text = startFormat.format(Date(prefs.customStartMillis))

        sizeSlider.accent = accent
        sizeSlider.updateValue(scaleToSlider(prefs.bodyScale))
        sizeValue.text = getString(R.string.body_size_value, prefs.bodyScale)

        val scale = prefs.distanceScale
        val scaleIdx = listOf(DistanceScale.LINEAR, DistanceScale.SQRT, DistanceScale.LOG).indexOf(scale)
        if (distanceControl.selectedIndex != scaleIdx) distanceControl.select(scaleIdx, animate = false)
        distanceNote.setText(
            when (scale) {
                DistanceScale.LINEAR -> R.string.scale_linear_note
                DistanceScale.SQRT -> R.string.scale_sqrt_note
                DistanceScale.LOG -> R.string.scale_log_note
            },
        )

        val hasSavedView = prefs.savedView != null
        goHomeLabel.setText(if (hasSavedView) R.string.go_to_saved_view else R.string.go_to_default_view)
        forgetViewRow.visibility = if (hasSavedView) View.VISIBLE else View.GONE
        forgetViewDivider.visibility = forgetViewRow.visibility

        refreshStatus()
        refreshDate()
        previewView.refreshNow()
    }

    private fun nearestIndex(values: List<Double>, value: Double): Int =
        values.indices.minByOrNull { abs(values[it] - value) } ?: 0

    private fun refreshDate() {
        val sim = prefs.simulatedMillis()
        val jd = jdFromUnixMillis(sim)
        val approximate = !isWithinPlanetTheory(centuriesSinceJ2000(jd)) || !AsteroidEphemeris.isReliableAt(this, jd)
        val text = SpannableStringBuilder(dateFormat.format(Date(sim)))
        if (approximate) {
            val start = text.length
            text.append("   ").append(getString(R.string.approximate_suffix))
            text.setSpan(ForegroundColorSpan(getColor(R.color.status_fallback)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (dateText.text.toString() != text.toString()) dateText.text = text
    }

    private fun refreshStatus() {
        val snapshot = AsteroidEphemeris.snapshot(this)
        val status = prefs.asteroidStatus
        val (message, colorRes) = when {
            status == WallpaperPrefs.STATUS_LOADING && fetchInFlight -> getString(R.string.status_loading) to R.color.status_loading
            status == WallpaperPrefs.STATUS_OK && snapshot != null -> getString(R.string.status_ok) to R.color.status_ok
            snapshot != null -> getString(R.string.status_offline) to R.color.status_ok
            else -> getString(R.string.status_fallback) to R.color.status_fallback
        }
        val color = getColor(colorRes)
        statusDot.background = Glass.dot(color)
        statusDot.contentDescription = message
        dataStatusDot.background = Glass.dot(color)
        dataStatusValue.text = message
        dataSourceValue.text = snapshot?.label ?: getString(R.string.data_builtin_source)
        dataUpdatedValue.text = snapshot?.let { updatedFormat.format(Date(it.modified)) } ?: getString(R.string.data_never)
    }

    // ---- Actions ------------------------------------------------------------------------------

    /** Auto-fetch on open unless a Horizons table was fetched in the last week. */
    private fun shouldAutoFetch(): Boolean {
        val snapshot = AsteroidEphemeris.snapshot(this) ?: return true
        if (!snapshot.isTable) return true
        return System.currentTimeMillis() - snapshot.modified > 7L * 24 * 3600 * 1000
    }

    private fun fetchAsteroidOrbit() {
        if (fetchInFlight) return
        fetchInFlight = true
        prefs.setAsteroidFetchLoading()
        refreshStatus()
        Thread {
            when (val result = AsteroidOrbitFetcher.fetch()) {
                is AsteroidFetchResult.Success -> prefs.setAsteroidData(result.rows, result.label)
                is AsteroidFetchResult.Failure -> prefs.setAsteroidFetchFailed(result.reason)
            }
            runOnUiThread {
                fetchInFlight = false
                if (isDestroyed) return@runOnUiThread
                refreshStatus()
                refreshDate()
                previewView.refreshNow()
            }
        }.start()
    }

    private fun pickStartDate() {
        val minCal = Calendar.getInstance().apply { set(1000, Calendar.JANUARY, 1, 0, 0, 0) }
        val maxCal = Calendar.getInstance().apply { set(2999, Calendar.DECEMBER, 31, 23, 59, 0) }
        val initial = prefs.customStartMillis.coerceIn(minCal.timeInMillis, maxCal.timeInMillis)
        val calendar = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        prefs.setCustomStartDate(calendar.timeInMillis)
                        refreshAll()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).apply {
            datePicker.minDate = minCal.timeInMillis
            datePicker.maxDate = maxCal.timeInMillis
        }.show()
    }

    /** Asks the launcher to place the widget (Android 8+ launchers that support it). */
    private fun addWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, SolarSystemWidgetProvider::class.java)
        val requested = manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(provider, null, null)
        if (!requested) Toast.makeText(this, getString(R.string.add_widget_fallback), Toast.LENGTH_LONG).show()
    }

    private fun setLiveWallpaper() {
        val component = ComponentName(this, SolarSystemWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.set_wallpaper_fallback), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAB_TIME = 0
        private const val TAB_STYLE = 1
        private const val TAB_VIEW = 2
        private const val TAB_DATA = 3
    }
}
