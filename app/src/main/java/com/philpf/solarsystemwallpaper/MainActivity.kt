package com.philpf.solarsystemwallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher entry point. Live wallpapers otherwise only surface through the OS's own
 * Settings > Wallpaper flow, which some OEM launchers/wallpaper pickers don't expose —
 * this jumps straight to the system live-wallpaper preview/set screen via the framework
 * intent, bypassing any OEM picker UI entirely.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.setWallpaperButton).setOnClickListener { setLiveWallpaper() }
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
