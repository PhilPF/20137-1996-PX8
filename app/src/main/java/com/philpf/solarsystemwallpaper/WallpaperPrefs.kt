package com.philpf.solarsystemwallpaper

import android.content.Context
import android.content.SharedPreferences

/**
 * Wrapper around the app's SharedPreferences (same default file historically used by
 * androidx.preference, `<packageName>_preferences`, kept for continuity even though the
 * settings UI is no longer a Preference screen).
 */
class WallpaperPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    val monochrome: Boolean get() = prefs.getBoolean(KEY_MONOCHROME, false)
    val showPlanetLabels: Boolean get() = prefs.getBoolean(KEY_SHOW_PLANET_LABELS, false)
    val showAsteroidLabel: Boolean get() = prefs.getBoolean(KEY_SHOW_ASTEROID_LABEL, false)

    /** Also used as the app's own UI accent color, so asteroid rendering and app chrome always match. */
    val asteroidColor: String get() = prefs.getString(KEY_ASTEROID_COLOR, DEFAULT_ACCENT_HEX) ?: DEFAULT_ACCENT_HEX
    val azimuthDrift: Boolean get() = prefs.getBoolean(KEY_AZIMUTH_DRIFT, true)
    val parallax: Boolean get() = prefs.getBoolean(KEY_PARALLAX, true)
    val timeModeIsRealTime: Boolean get() = prefs.getString(KEY_TIME_MODE, "real_time") == "real_time"
    val speedDaysPerSec: Double
        get() = prefs.getString(KEY_SPEED_DAYS_PER_SEC, null)?.toDoubleOrNull() ?: DEFAULT_SPEED_DAYS_PER_SEC

    fun setMonochrome(value: Boolean) = prefs.edit().putBoolean(KEY_MONOCHROME, value).apply()
    fun setShowPlanetLabels(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_PLANET_LABELS, value).apply()
    fun setShowAsteroidLabel(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_ASTEROID_LABEL, value).apply()
    fun setAzimuthDrift(value: Boolean) = prefs.edit().putBoolean(KEY_AZIMUTH_DRIFT, value).apply()
    fun setParallax(value: Boolean) = prefs.edit().putBoolean(KEY_PARALLAX, value).apply()
    fun setAsteroidColor(hex: String) = prefs.edit().putString(KEY_ASTEROID_COLOR, hex).apply()
    fun setTimeModeIsRealTime(value: Boolean) =
        prefs.edit().putString(KEY_TIME_MODE, if (value) "real_time" else "custom").apply()
    fun setSpeedDaysPerSec(value: Double) = prefs.edit().putString(KEY_SPEED_DAYS_PER_SEC, value.toString()).apply()

    /** Base camera set by dragging/pinching the preview; ambient drift/parallax are added on top of this. */
    val cameraAzimuthDeg: Double get() = prefs.getFloat(KEY_CAMERA_AZIMUTH, 0f).toDouble()
    val cameraTiltDeg: Double get() = prefs.getFloat(KEY_CAMERA_TILT, DEFAULT_TILT_DEG.toFloat()).toDouble()
    val cameraZoom: Double get() = prefs.getFloat(KEY_CAMERA_ZOOM, 1f).toDouble()

    fun setCamera(azimuthDeg: Double, tiltDeg: Double, zoom: Double) {
        prefs.edit()
            .putFloat(KEY_CAMERA_AZIMUTH, azimuthDeg.toFloat())
            .putFloat(KEY_CAMERA_TILT, tiltDeg.toFloat())
            .putFloat(KEY_CAMERA_ZOOM, zoom.toFloat())
            .apply()
    }

    fun resetCamera() = setCamera(0.0, DEFAULT_TILT_DEG, 1.0)

    /**
     * Current simulated wall-clock time, in Unix millis. Real-time mode just returns "now".
     * Custom mode extrapolates forward from a persisted (simulated-date, real-date) anchor pair
     * at [speedDaysPerSec], so the position is deterministic and stable across engine restarts
     * (screen off/on, wallpaper re-bind, etc.) without needing any in-memory state.
     */
    fun simulatedMillis(): Long {
        if (timeModeIsRealTime) return System.currentTimeMillis()
        val (anchorWallMillis, anchorSimMillis) = customAnchor()
        val elapsedRealSeconds = (System.currentTimeMillis() - anchorWallMillis) / 1000.0
        val simElapsedMillis = (elapsedRealSeconds * speedDaysPerSec * 86400000.0).toLong()
        return anchorSimMillis + simElapsedMillis
    }

    /** Re-anchors custom mode so it starts counting forward from [startMillis] as of now. */
    fun setCustomStartDate(startMillis: Long) {
        prefs.edit()
            .putLong(KEY_ANCHOR_WALL_MILLIS, System.currentTimeMillis())
            .putLong(KEY_ANCHOR_SIM_MILLIS, startMillis)
            .apply()
    }

    private fun customAnchor(): Pair<Long, Long> {
        val wall = prefs.getLong(KEY_ANCHOR_WALL_MILLIS, -1L)
        val sim = prefs.getLong(KEY_ANCHOR_SIM_MILLIS, -1L)
        if (wall != -1L && sim != -1L) return wall to sim
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_ANCHOR_WALL_MILLIS, now).putLong(KEY_ANCHOR_SIM_MILLIS, now).apply()
        return now to now
    }

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
        const val KEY_ANCHOR_WALL_MILLIS = "pref_custom_anchor_wall_millis"
        const val KEY_ANCHOR_SIM_MILLIS = "pref_custom_anchor_sim_millis"
        const val KEY_AZIMUTH_DRIFT = "pref_azimuth_drift"
        const val KEY_PARALLAX = "pref_parallax"
        const val KEY_CAMERA_AZIMUTH = "pref_camera_azimuth"
        const val KEY_CAMERA_TILT = "pref_camera_tilt"
        const val KEY_CAMERA_ZOOM = "pref_camera_zoom"

        const val DEFAULT_ACCENT_HEX = "#ffd166"

        /** Default custom-mode rate, matching the design handoff's recommended ambient speed. */
        const val DEFAULT_SPEED_DAYS_PER_SEC = 30.0

        /** Default fixed camera tilt, per the design handoff's recommended default (22-23deg). */
        const val DEFAULT_TILT_DEG = 22.0

        /** Full 360° ambient camera rotations take this long when azimuth drift is enabled. */
        const val AZIMUTH_DRIFT_DEG_PER_SEC = 360.0 / (2 * 60 * 60)

        /** Max azimuth offset (degrees) applied by home-screen swipe parallax. */
        const val PARALLAX_MAX_DEG = 15.0
    }
}
