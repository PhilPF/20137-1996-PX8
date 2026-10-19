package com.philpf.solarsystemwallpaper

import android.content.Context
import android.content.SharedPreferences

/**
 * Wrapper around the app's SharedPreferences (same default file historically used by
 * androidx.preference, `<packageName>_preferences`). Every setter that changes what the scene
 * looks like also redraws any home-screen widgets.
 */
class WallpaperPrefs(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("${appContext.packageName}_preferences", Context.MODE_PRIVATE)

    private fun notifyWidgets() = SolarSystemWidgetProvider.requestUpdate(appContext)

    // ---- Appearance -------------------------------------------------------------------------

    val monochrome: Boolean get() = prefs.getBoolean(KEY_MONOCHROME, false)
    val showPlanetLabels: Boolean get() = prefs.getBoolean(KEY_SHOW_PLANET_LABELS, false)
    val showAsteroidLabel: Boolean get() = prefs.getBoolean(KEY_SHOW_ASTEROID_LABEL, false)

    /** Also the app's own UI accent color, so asteroid rendering and app chrome always match. */
    val asteroidColor: String get() = prefs.getString(KEY_ASTEROID_COLOR, DEFAULT_ACCENT_HEX) ?: DEFAULT_ACCENT_HEX
    val azimuthDrift: Boolean get() = prefs.getBoolean(KEY_AZIMUTH_DRIFT, true)
    val parallax: Boolean get() = prefs.getBoolean(KEY_PARALLAX, true)

    fun setMonochrome(value: Boolean) = putBoolean(KEY_MONOCHROME, value, redrawWidgets = true)
    fun setShowPlanetLabels(value: Boolean) = putBoolean(KEY_SHOW_PLANET_LABELS, value, redrawWidgets = true)
    fun setShowAsteroidLabel(value: Boolean) = putBoolean(KEY_SHOW_ASTEROID_LABEL, value, redrawWidgets = true)
    fun setAzimuthDrift(value: Boolean) = putBoolean(KEY_AZIMUTH_DRIFT, value, redrawWidgets = false)
    fun setParallax(value: Boolean) = putBoolean(KEY_PARALLAX, value, redrawWidgets = false)

    fun setAsteroidColor(hex: String) {
        prefs.edit().putString(KEY_ASTEROID_COLOR, hex).apply()
        notifyWidgets()
    }

    private fun putBoolean(key: String, value: Boolean, redrawWidgets: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        if (redrawWidgets) notifyWidgets()
    }

    // ---- Camera -------------------------------------------------------------------------------

    /** Base camera set by dragging/pinching the preview; ambient drift/parallax are added on top. */
    val cameraAzimuthDeg: Double get() = prefs.getFloat(KEY_CAMERA_AZIMUTH, 0f).toDouble()
    val cameraTiltDeg: Double get() = prefs.getFloat(KEY_CAMERA_TILT, DEFAULT_TILT_DEG.toFloat()).toDouble()
    val cameraZoom: Double get() = prefs.getFloat(KEY_CAMERA_ZOOM, 1f).toDouble()

    fun setCamera(azimuthDeg: Double, tiltDeg: Double, zoom: Double) {
        prefs.edit()
            .putFloat(KEY_CAMERA_AZIMUTH, azimuthDeg.toFloat())
            .putFloat(KEY_CAMERA_TILT, tiltDeg.toFloat())
            .putFloat(KEY_CAMERA_ZOOM, zoom.toFloat())
            .apply()
        notifyWidgets()
    }

    fun resetCamera() = setCamera(0.0, DEFAULT_TILT_DEG, 1.0)

    // ---- Time ---------------------------------------------------------------------------------

    val timeModeIsRealTime: Boolean get() = prefs.getString(KEY_TIME_MODE, "real_time") == "real_time"
    val speedDaysPerSec: Double
        get() = prefs.getString(KEY_SPEED_DAYS_PER_SEC, null)?.toDoubleOrNull() ?: DEFAULT_SPEED_DAYS_PER_SEC
    val loopYears: Double
        get() = prefs.getString(KEY_LOOP_YEARS, null)?.toDoubleOrNull() ?: DEFAULT_LOOP_YEARS
    private val loopDays: Double get() = loopYears * 365.25

    /** The date Custom mode starts from (and loops back to). */
    val customStartMillis: Long get() = customAnchor().second

    /** Current simulated time in Unix millis; see [SimulationClock]. */
    fun simulatedMillis(): Long {
        if (timeModeIsRealTime) return System.currentTimeMillis()
        val (anchorWall, start) = customAnchor()
        return SimulationClock.simulatedMillis(
            System.currentTimeMillis(), false, anchorWall, start, speedDaysPerSec, loopDays,
        )
    }

    /** Switching to Custom restarts playback from the start date. */
    fun setTimeModeIsRealTime(value: Boolean) {
        val edit = prefs.edit().putString(KEY_TIME_MODE, if (value) "real_time" else "custom")
        if (!value) {
            val start = customAnchor().second
            edit.putLong(KEY_ANCHOR_WALL_MILLIS, System.currentTimeMillis()).putLong(KEY_ANCHOR_SIM_MILLIS, start)
        }
        edit.apply()
        notifyWidgets()
    }

    fun setSpeedDaysPerSec(value: Double) = changeClock(newSpeed = value, newLoopYears = loopYears)

    fun setLoopYears(value: Double) = changeClock(newSpeed = speedDaysPerSec, newLoopYears = value)

    /** Changes speed/loop while keeping the simulated date exactly where it is. */
    private fun changeClock(newSpeed: Double, newLoopYears: Double) {
        val (anchorWall, start) = customAnchor()
        val newAnchor = SimulationClock.reanchor(
            System.currentTimeMillis(), anchorWall, speedDaysPerSec, loopDays, newSpeed, newLoopYears * 365.25,
        )
        prefs.edit()
            .putString(KEY_SPEED_DAYS_PER_SEC, newSpeed.toString())
            .putString(KEY_LOOP_YEARS, newLoopYears.toString())
            .putLong(KEY_ANCHOR_WALL_MILLIS, newAnchor)
            .putLong(KEY_ANCHOR_SIM_MILLIS, start)
            .apply()
        notifyWidgets()
    }

    /** Restarts Custom playback from [startMillis] as of now. */
    fun setCustomStartDate(startMillis: Long) {
        prefs.edit()
            .putLong(KEY_ANCHOR_WALL_MILLIS, System.currentTimeMillis())
            .putLong(KEY_ANCHOR_SIM_MILLIS, startMillis)
            .apply()
        notifyWidgets()
    }

    private fun customAnchor(): Pair<Long, Long> {
        val wall = prefs.getLong(KEY_ANCHOR_WALL_MILLIS, -1L)
        val sim = prefs.getLong(KEY_ANCHOR_SIM_MILLIS, -1L)
        if (wall != -1L && sim != -1L) return wall to sim
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_ANCHOR_WALL_MILLIS, now).putLong(KEY_ANCHOR_SIM_MILLIS, now).apply()
        return now to now
    }

    // ---- Asteroid data ------------------------------------------------------------------------

    /** Elements to propagate from for a given Julian Date (nearest epoch in the stored table). */
    fun asteroidOrbitAt(jd: Double): AsteroidOrbit = AsteroidEphemeris.orbitAt(appContext, jd)

    fun setAsteroidData(rows: List<AsteroidOrbit>, label: String) {
        AsteroidEphemeris.save(appContext, label, rows)
        prefs.edit()
            .putString(KEY_ASTEROID_STATUS, STATUS_OK)
            .putString(KEY_ASTEROID_STATUS_DETAIL, label)
            .apply()
        notifyWidgets()
    }

    fun setAsteroidFetchFailed(reason: String) {
        prefs.edit()
            .putString(KEY_ASTEROID_STATUS, STATUS_FALLBACK)
            .putString(KEY_ASTEROID_STATUS_DETAIL, reason)
            .apply()
    }

    fun setAsteroidFetchLoading() {
        prefs.edit().putString(KEY_ASTEROID_STATUS, STATUS_LOADING).apply()
    }

    /** [STATUS_LOADING], [STATUS_OK], or [STATUS_FALLBACK] (default, before any fetch attempt). */
    val asteroidStatus: String get() = prefs.getString(KEY_ASTEROID_STATUS, STATUS_FALLBACK) ?: STATUS_FALLBACK
    val asteroidStatusDetail: String get() = prefs.getString(KEY_ASTEROID_STATUS_DETAIL, "") ?: ""

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val KEY_MONOCHROME = "pref_monochrome"
        const val KEY_SHOW_PLANET_LABELS = "pref_show_planet_labels"
        const val KEY_SHOW_ASTEROID_LABEL = "pref_show_asteroid_label"
        const val KEY_ASTEROID_COLOR = "pref_asteroid_color"
        const val KEY_TIME_MODE = "pref_time_mode"
        const val KEY_SPEED_DAYS_PER_SEC = "pref_speed_days_per_sec"
        const val KEY_LOOP_YEARS = "pref_loop_years"
        const val KEY_ANCHOR_WALL_MILLIS = "pref_custom_anchor_wall_millis"
        const val KEY_ANCHOR_SIM_MILLIS = "pref_custom_anchor_sim_millis"
        const val KEY_AZIMUTH_DRIFT = "pref_azimuth_drift"
        const val KEY_PARALLAX = "pref_parallax"
        const val KEY_CAMERA_AZIMUTH = "pref_camera_azimuth"
        const val KEY_CAMERA_TILT = "pref_camera_tilt"
        const val KEY_CAMERA_ZOOM = "pref_camera_zoom"
        const val KEY_ASTEROID_STATUS = "pref_asteroid_status"
        const val KEY_ASTEROID_STATUS_DETAIL = "pref_asteroid_status_detail"

        const val STATUS_LOADING = "loading"
        const val STATUS_OK = "ok"
        const val STATUS_FALLBACK = "fallback"

        const val DEFAULT_ACCENT_HEX = "#ffd166"

        /** Default custom-mode rate, matching the design handoff's recommended ambient speed. */
        const val DEFAULT_SPEED_DAYS_PER_SEC = 30.0

        /** Custom mode loops back to its start date after this many years. */
        const val DEFAULT_LOOP_YEARS = 10.0

        /** Default fixed camera tilt, per the design handoff's recommended default (22-23deg). */
        const val DEFAULT_TILT_DEG = 22.0

        /** Full 360° ambient camera rotations take this long when azimuth drift is enabled. */
        const val AZIMUTH_DRIFT_DEG_PER_SEC = 360.0 / (2 * 60 * 60)

        /** Max azimuth offset (degrees) applied by home-screen swipe parallax. */
        const val PARALLAX_MAX_DEG = 15.0
    }
}
