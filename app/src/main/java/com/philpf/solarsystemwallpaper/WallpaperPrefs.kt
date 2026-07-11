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
    val realTime: Boolean get() = prefs.getString(KEY_TIME_MODE, "real_time") == "real_time"
    val azimuthDrift: Boolean get() = prefs.getBoolean(KEY_AZIMUTH_DRIFT, true)
    val parallax: Boolean get() = prefs.getBoolean(KEY_PARALLAX, true)

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
        const val KEY_AZIMUTH_DRIFT = "pref_azimuth_drift"
        const val KEY_PARALLAX = "pref_parallax"

        /** Accelerated time-drive rate used when time mode is not real-time (see design handoff). */
        const val ACCELERATED_DAYS_PER_SEC = 30.0

        /** Full 360° ambient camera rotations take this long when azimuth drift is enabled. */
        const val AZIMUTH_DRIFT_DEG_PER_SEC = 360.0 / (2 * 60 * 60)

        /** Max azimuth offset (degrees) applied by home-screen swipe parallax. */
        const val PARALLAX_MAX_DEG = 15.0
    }
}
