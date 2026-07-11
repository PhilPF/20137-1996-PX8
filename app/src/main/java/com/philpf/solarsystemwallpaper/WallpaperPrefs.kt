package com.philpf.solarsystemwallpaper

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Thin wrapper around the default SharedPreferences backing res/xml/settings_preferences.xml. */
class WallpaperPrefs(context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    val monochrome: Boolean get() = prefs.getBoolean(KEY_MONOCHROME, false)
    val showPlanetLabels: Boolean get() = prefs.getBoolean(KEY_SHOW_PLANET_LABELS, false)
    val showAsteroidLabel: Boolean get() = prefs.getBoolean(KEY_SHOW_ASTEROID_LABEL, false)
    val asteroidColor: String get() = prefs.getString(KEY_ASTEROID_COLOR, "#ffd166") ?: "#ffd166"
    val azimuthDrift: Boolean get() = prefs.getBoolean(KEY_AZIMUTH_DRIFT, true)
    val parallax: Boolean get() = prefs.getBoolean(KEY_PARALLAX, true)

    /** Base camera set via the in-app "Adjust view" screen; ambient drift/parallax are added on top of this. */
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

    private val isRealTime: Boolean get() = prefs.getString(KEY_TIME_MODE, "real_time") == "real_time"

    private val speedDaysPerSec: Double
        get() = prefs.getString(KEY_SPEED_DAYS_PER_SEC, null)?.toDoubleOrNull() ?: DEFAULT_SPEED_DAYS_PER_SEC

    /**
     * Current simulated wall-clock time, in Unix millis. Real-time mode just returns "now".
     * Custom mode extrapolates forward from a persisted (simulated-date, real-date) anchor pair
     * at [speedDaysPerSec], so the position is deterministic and stable across engine restarts
     * (screen off/on, wallpaper re-bind, etc.) without needing any in-memory state.
     */
    fun simulatedMillis(): Long {
        if (isRealTime) return System.currentTimeMillis()
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

    /** Re-anchors custom mode to start counting forward from the current real date/time. */
    fun resetCustomStartToNow() {
        val now = System.currentTimeMillis()
        setCustomStartDate(now)
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
