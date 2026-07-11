package com.philpf.solarsystemwallpaper

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Single-screen app: a full-bleed live preview (drag to rotate, pinch to zoom, same as the HTML
 * reference's camera controls) with every option overlaid directly on top, in the spirit of the
 * reference's own control bar — adapted into stacked rows since a phone doesn't have the desktop
 * width for one long flex row. There's no separate settings/preview screens; this is the app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: WallpaperPrefs
    private lateinit var previewView: SolarSystemPreviewView
    private lateinit var titleText: TextView
    private lateinit var toggleRow: LinearLayout
    private lateinit var swatchRow: LinearLayout
    private lateinit var speedRow: LinearLayout
    private lateinit var customControls: LinearLayout
    private lateinit var realTimeChip: Button
    private lateinit var customChip: Button
    private lateinit var startDateButton: Button
    private lateinit var resetViewButton: Button
    private lateinit var setWallpaperButton: Button

    private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    private data class ToggleSpec(val labelRes: Int, val get: () -> Boolean, val set: (Boolean) -> Unit)

    private val toggleSpecs by lazy {
        listOf(
            ToggleSpec(R.string.chip_monochrome, { prefs.monochrome }, { prefs.setMonochrome(it) }),
            ToggleSpec(R.string.chip_planet_labels, { prefs.showPlanetLabels }, { prefs.setShowPlanetLabels(it) }),
            ToggleSpec(R.string.chip_asteroid_label, { prefs.showAsteroidLabel }, { prefs.setShowAsteroidLabel(it) }),
            ToggleSpec(R.string.chip_drift, { prefs.azimuthDrift }, { prefs.setAzimuthDrift(it) }),
            ToggleSpec(R.string.chip_parallax, { prefs.parallax }, { prefs.setParallax(it) }),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = WallpaperPrefs(this)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        titleText = findViewById(R.id.titleText)
        toggleRow = findViewById(R.id.toggleRow)
        swatchRow = findViewById(R.id.swatchRow)
        speedRow = findViewById(R.id.speedRow)
        customControls = findViewById(R.id.customControls)
        realTimeChip = findViewById(R.id.realTimeChip)
        customChip = findViewById(R.id.customChip)
        startDateButton = findViewById(R.id.startDateButton)
        resetViewButton = findViewById(R.id.resetViewButton)
        setWallpaperButton = findViewById(R.id.setWallpaperButton)

        buildToggleChips()
        buildSwatches()
        buildSpeedChips()

        realTimeChip.setOnClickListener { setTimeMode(realTime = true) }
        customChip.setOnClickListener { setTimeMode(realTime = false) }
        startDateButton.setOnClickListener {
            showDateTimePicker(prefs.simulatedMillis()) { picked ->
                prefs.setCustomStartDate(picked)
                refreshTimeControls()
            }
        }
        resetViewButton.setOnClickListener { previewView.resetCamera() }
        setWallpaperButton.setOnClickListener { setLiveWallpaper() }

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        previewView.loadSavedCamera()
        refreshAll()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun currentAccentColor(): Int =
        try {
            Color.parseColor(prefs.asteroidColor)
        } catch (_: IllegalArgumentException) {
            Color.parseColor(WallpaperPrefs.DEFAULT_ACCENT_HEX)
        }

    private fun pillDrawable(fillColor: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6f).toFloat()
            setColor(fillColor)
            strokeColor?.let { setStroke(dp(1f), it) }
        }

    private fun circleDrawable(fillColor: Int, strokeColor: Int?, strokeWidthDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            strokeColor?.let { setStroke(dp(strokeWidthDp), it) }
        }

    private fun styleChip(button: Button, active: Boolean, accent: Int) {
        if (active) {
            button.background = pillDrawable(accent)
            button.setTextColor(backgroundColor())
        } else {
            button.background = pillDrawable(panelColor(), accent)
            button.setTextColor(foregroundColor())
        }
    }

    private fun foregroundColor(): Int = getColor(R.color.foreground)
    private fun backgroundColor(): Int = getColor(R.color.background)
    private fun panelColor(): Int = getColor(R.color.chrome_panel_bg)

    private fun buildToggleChips() {
        toggleRow.removeAllViews()
        toggleSpecs.forEach { spec ->
            val button = Button(this).apply {
                text = getString(spec.labelRes)
                textSize = 12.5f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8f) }
                setOnClickListener {
                    spec.set(!spec.get())
                    refreshAll()
                }
            }
            toggleRow.addView(button)
        }
    }

    private fun buildSwatches() {
        swatchRow.removeAllViews()
        val entries = resources.getStringArray(R.array.asteroid_color_entries)
        val values = resources.getStringArray(R.array.asteroid_color_values)
        values.forEachIndexed { idx, hex ->
            val swatch = android.view.View(this).apply {
                contentDescription = entries.getOrElse(idx) { hex }
                layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply {
                    marginEnd = dp(10f)
                }
                setOnClickListener {
                    prefs.setAsteroidColor(hex)
                    refreshAll()
                }
            }
            swatchRow.addView(swatch)
        }
    }

    private fun buildSpeedChips() {
        speedRow.removeAllViews()
        val labels = resources.getStringArray(R.array.speed_chip_labels)
        val values = resources.getStringArray(R.array.speed_values)
        values.forEachIndexed { idx, value ->
            val button = Button(this).apply {
                text = labels.getOrElse(idx) { value }
                textSize = 12.5f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8f) }
                setOnClickListener {
                    prefs.setSpeedDaysPerSec(value.toDouble())
                    refreshAll()
                }
            }
            speedRow.addView(button)
        }
    }

    private fun setTimeMode(realTime: Boolean) {
        prefs.setTimeModeIsRealTime(realTime)
        refreshAll()
    }

    private fun refreshAll() {
        val accent = currentAccentColor()

        titleText.setTextColor(accent)

        for (i in 0 until toggleRow.childCount) {
            val button = toggleRow.getChildAt(i) as Button
            styleChip(button, toggleSpecs[i].get(), accent)
        }

        val selectedHex = prefs.asteroidColor
        val values = resources.getStringArray(R.array.asteroid_color_values)
        for (i in 0 until swatchRow.childCount) {
            val swatch = swatchRow.getChildAt(i)
            val hex = values.getOrElse(i) { WallpaperPrefs.DEFAULT_ACCENT_HEX }
            val isSelected = hex.equals(selectedHex, ignoreCase = true)
            val fill = try { Color.parseColor(hex) } catch (_: IllegalArgumentException) { Color.GRAY }
            swatch.background = circleDrawable(fill, if (isSelected) foregroundColor() else null, 2f)
        }

        styleChip(realTimeChip, prefs.timeModeIsRealTime, accent)
        styleChip(customChip, !prefs.timeModeIsRealTime, accent)

        val speedValues = resources.getStringArray(R.array.speed_values)
        for (i in 0 until speedRow.childCount) {
            val button = speedRow.getChildAt(i) as Button
            val value = speedValues.getOrElse(i) { "0" }.toDoubleOrNull() ?: 0.0
            styleChip(button, !prefs.timeModeIsRealTime && prefs.speedDaysPerSec == value, accent)
        }

        setWallpaperButton.background = pillDrawable(accent)
        setWallpaperButton.setTextColor(backgroundColor())
        resetViewButton.background = pillDrawable(panelColor(), accent)
        resetViewButton.setTextColor(foregroundColor())

        refreshTimeControls()
    }

    private fun refreshTimeControls() {
        customControls.visibility = if (prefs.timeModeIsRealTime) android.view.View.GONE else android.view.View.VISIBLE
        startDateButton.text = dateFormat.format(Date(prefs.simulatedMillis()))
        startDateButton.background = pillDrawable(panelColor(), currentAccentColor())
        startDateButton.setTextColor(foregroundColor())
    }

    private fun showDateTimePicker(initialMillis: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initialMillis }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        onPicked(calendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
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
