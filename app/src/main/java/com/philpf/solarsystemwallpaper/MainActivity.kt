package com.philpf.solarsystemwallpaper

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Single-screen app: the live scene fills the screen edge-to-edge (drag to rotate, pinch to
 * zoom — the camera the wallpaper and widget use), with floating glass chrome on top:
 *
 * - top capsule: name, the simulated date being shown, and where the asteroid data came from
 *   (tap to refresh it from JPL);
 * - bottom dock: accent color, the options button and the primary "Set as Wallpaper" action;
 * - options sheet (above the dock): time, appearance, camera and data settings.
 *
 * The chrome fades while you drag the scene so it doesn't hide what you're positioning.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: WallpaperPrefs
    private lateinit var root: FrameLayout
    private lateinit var previewView: SolarSystemPreviewView
    private lateinit var hudPanel: GlassPanel
    private lateinit var dockPanel: GlassPanel
    private lateinit var optionsSheet: GlassPanel
    private lateinit var optionsScroll: MaxHeightScrollView
    private lateinit var optionsContent: LinearLayout
    private lateinit var dateText: TextView
    private lateinit var statusText: TextView
    private lateinit var swatchRow: LinearLayout
    private lateinit var optionsButton: FrameLayout
    private lateinit var optionsIcon: ImageView
    private lateinit var setWallpaperButton: TextView

    private val toggles = ArrayList<Pair<GlassToggle, () -> Boolean>>()
    private val secondaryButtons = ArrayList<TextView>()
    private lateinit var timeModeControl: GlassSegmentedControl
    private lateinit var speedControl: GlassSegmentedControl
    private lateinit var loopControl: GlassSegmentedControl
    private lateinit var customGroup: LinearLayout
    private lateinit var startDateButton: TextView

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
        dockPanel = findViewById(R.id.dockPanel)
        optionsSheet = findViewById(R.id.optionsSheet)
        optionsScroll = findViewById(R.id.optionsScroll)
        optionsContent = findViewById(R.id.optionsContent)
        dateText = findViewById(R.id.dateText)
        statusText = findViewById(R.id.statusText)
        swatchRow = findViewById(R.id.swatchRow)
        optionsButton = findViewById(R.id.optionsButton)
        optionsIcon = findViewById(R.id.optionsIcon)
        setWallpaperButton = findViewById(R.id.setWallpaperButton)

        listOf(hudPanel, dockPanel, optionsSheet).forEach { it.attachScene(previewView) }

        buildSwatches()
        buildOptions()

        optionsButton.background = RippleDrawable(
            ColorStateList.valueOf(0x40FFFFFF),
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x2EFFFFFF, 0x12FFFFFF)).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1f), 0x33FFFFFF)
            },
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            },
        )
        optionsButton.isClickable = true
        optionsButton.setOnClickListener { setSheetOpen(!sheetOpen) }
        setWallpaperButton.setOnClickListener { setLiveWallpaper() }
        findViewById<View>(R.id.hudContent).setOnClickListener { fetchAsteroidOrbit() }
        previewView.onInteraction = { active -> fadeChrome(active) }
        onBackPressedDispatcher.addCallback(this, backCallback)

        applyWindowInsets()
        dockPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { positionSheet() } }
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
                topMargin = dp(12f) + bars.top
                marginStart = dp(16f) + bars.left
                marginEnd = dp(16f) + bars.right
            }
            (dockPanel.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = dp(12f) + bars.bottom
                marginStart = dp(12f) + bars.left
                marginEnd = dp(12f) + bars.right
            }
            (optionsSheet.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = dp(12f) + bars.left
                marginEnd = dp(12f) + bars.right
            }
            hudPanel.requestLayout()
            dockPanel.requestLayout()
            root.post { positionSheet() }
            insets
        }
    }

    /** Keeps the options sheet just above the dock, capped so it never covers the top capsule. */
    private fun positionSheet() {
        if (root.height == 0) return
        val sheetLp = optionsSheet.layoutParams as FrameLayout.LayoutParams
        val dockLp = dockPanel.layoutParams as ViewGroup.MarginLayoutParams
        val bottom = dockPanel.height + dockLp.bottomMargin + dp(10f)
        if (sheetLp.bottomMargin != bottom) {
            sheetLp.bottomMargin = bottom
            optionsSheet.layoutParams = sheetLp
        }
        val hudBottom = (hudPanel.layoutParams as ViewGroup.MarginLayoutParams).topMargin + hudPanel.height
        val available = root.height - bottom - hudBottom - dp(12f)
        optionsScroll.maxHeightPx = max(dp(180f), min(available, (root.height * 0.62f).toInt()))
    }

    private fun setSheetOpen(open: Boolean) {
        if (open == sheetOpen) return
        sheetOpen = open
        backCallback.isEnabled = open
        optionsIcon.setImageResource(if (open) R.drawable.ic_close else R.drawable.ic_tune)
        optionsButton.contentDescription = getString(if (open) R.string.close_options else R.string.options)
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

    private fun fadeChrome(active: Boolean) {
        val target = if (active) 0.15f else 1f
        val duration = if (active) 160L else 320L
        hudPanel.animate().alpha(target).setDuration(duration).start()
        dockPanel.animate().alpha(target).setDuration(duration).start()
        if (sheetOpen) optionsSheet.animate().alpha(target).translationY(0f).setDuration(duration).start()
    }

    // ---- Building controls ------------------------------------------------------------------

    private fun buildSwatches() {
        swatchRow.removeAllViews()
        for ((idx, hex) in swatchValues.withIndex()) {
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { marginEnd = dp(4f) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    prefs.setAsteroidColor(hex)
                    refreshAll()
                }
            }
            swatch.tag = swatchNames.getOrElse(idx) { hex }
            swatchRow.addView(swatch)
        }
    }

    private fun buildOptions() {
        optionsContent.removeAllViews()

        sectionHeader(R.string.section_time, first = true)
        timeModeControl = segmented(listOf(getString(R.string.time_real), getString(R.string.time_custom))) { idx ->
            prefs.setTimeModeIsRealTime(idx == 0)
            refreshAll()
        }
        customGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        optionsContent.addView(customGroup, matchWrap())

        val startRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startDateButton = secondaryButton("") { pickStartDate() }
        startRow.addView(startDateButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val nowButton = secondaryButton(getString(R.string.now)) {
            prefs.setCustomStartDate(System.currentTimeMillis())
            refreshAll()
        }
        startRow.addView(
            nowButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8f) },
        )
        customGroup.addView(startRow, matchWrap(top = 12f))

        customGroup.addView(caption(R.string.speed_caption), matchWrap(top = 14f))
        speedControl = segmented(resources.getStringArray(R.array.speed_labels).toList(), customGroup) { idx ->
            prefs.setSpeedDaysPerSec(speedValues[idx])
            refreshAll()
        }
        customGroup.addView(caption(R.string.loop_caption), matchWrap(top = 14f))
        loopControl = segmented(resources.getStringArray(R.array.loop_labels).toList(), customGroup) { idx ->
            prefs.setLoopYears(loopValues[idx])
            refreshAll()
        }

        sectionHeader(R.string.section_appearance)
        toggleRow(R.string.toggle_monochrome, { prefs.monochrome }, { prefs.setMonochrome(it) })
        hairline()
        toggleRow(R.string.toggle_planet_labels, { prefs.showPlanetLabels }, { prefs.setShowPlanetLabels(it) })
        hairline()
        toggleRow(R.string.toggle_asteroid_label, { prefs.showAsteroidLabel }, { prefs.setShowAsteroidLabel(it) })

        sectionHeader(R.string.section_camera)
        toggleRow(R.string.toggle_drift, { prefs.azimuthDrift }, { prefs.setAzimuthDrift(it) })
        hairline()
        toggleRow(R.string.toggle_parallax, { prefs.parallax }, { prefs.setParallax(it) })
        optionsContent.addView(secondaryButton(getString(R.string.reset_camera_button)) { previewView.resetCamera() }, matchWrap(top = 12f))

        sectionHeader(R.string.section_data)
        optionsContent.addView(
            TextView(this).apply {
                text = getString(R.string.data_explainer)
                setTextColor(Glass.TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(0f, 1.15f)
            },
            matchWrap(),
        )
        optionsContent.addView(secondaryButton(getString(R.string.refresh_data)) { fetchAsteroidOrbit() }, matchWrap(top = 12f))
    }

    private fun matchWrap(top: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun sectionHeader(textRes: Int, first: Boolean = false) {
        val header = TextView(this).apply {
            text = getString(textRes).uppercase(Locale.getDefault())
            setTextColor(Glass.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            letterSpacing = 0.1f
            typeface = Glass.medium
        }
        optionsContent.addView(header, matchWrap(top = if (first) 14f else 24f).apply { bottomMargin = dp(10f) })
    }

    private fun caption(textRes: Int) = TextView(this).apply {
        text = getString(textRes)
        setTextColor(Glass.TEXT_SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
    }

    private fun hairline() {
        optionsContent.addView(
            View(this).apply { setBackgroundColor(Glass.HAIRLINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(0.6f))),
        )
    }

    private fun segmented(
        labels: List<String>,
        parent: LinearLayout = optionsContent,
        onSelect: (Int) -> Unit,
    ): GlassSegmentedControl {
        val control = GlassSegmentedControl(this).apply {
            options = labels
            this.onSelect = onSelect
        }
        parent.addView(control, matchWrap(top = 8f))
        return control
    }

    private fun toggleRow(labelRes: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val toggle = GlassToggle(this).apply {
            contentDescription = getString(labelRes)
            onChange = { value ->
                set(value)
                refreshAll()
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50f)
            isClickable = true
            setOnClickListener { toggle.performClick() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(
            TextView(this).apply {
                text = getString(labelRes)
                setTextColor(Glass.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(toggle)
        optionsContent.addView(row, matchWrap())
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
        Glass.styleButton(setWallpaperButton, primary = true, accent = accent)
        secondaryButtons.forEach { Glass.styleButton(it, primary = false, accent = accent) }

        val selected = prefs.asteroidColor
        for (i in 0 until swatchRow.childCount) {
            val swatch = swatchRow.getChildAt(i)
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
        timeModeControl.accent = accent
        if (timeModeControl.selectedIndex != (if (realTime) 0 else 1)) timeModeControl.select(if (realTime) 0 else 1, animate = false)
        speedControl.accent = accent
        val speedIdx = nearestIndex(speedValues, prefs.speedDaysPerSec)
        if (speedControl.selectedIndex != speedIdx) speedControl.select(speedIdx, animate = false)
        loopControl.accent = accent
        val loopIdx = nearestIndex(loopValues, prefs.loopYears)
        if (loopControl.selectedIndex != loopIdx) loopControl.select(loopIdx, animate = false)
        customGroup.visibility = if (realTime) View.GONE else View.VISIBLE
        startDateButton.text = getString(R.string.start_date) + "  " + startFormat.format(Date(prefs.customStartMillis))

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
            status == WallpaperPrefs.STATUS_LOADING && fetchInFlight ->
                getString(R.string.status_loading) to R.color.status_loading
            status == WallpaperPrefs.STATUS_OK && snapshot != null ->
                getString(R.string.status_ok, snapshot.label) to R.color.status_ok
            snapshot != null ->
                (snapshot.label + " · offline") to R.color.status_ok
            else -> getString(R.string.status_fallback) to R.color.status_fallback
        }
        statusText.text = "● $message"
        statusText.setTextColor(getColor(colorRes))
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
}
