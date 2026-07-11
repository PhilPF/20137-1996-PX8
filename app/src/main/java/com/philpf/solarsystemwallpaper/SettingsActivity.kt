package com.philpf.solarsystemwallpaper

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Referenced by res/xml/wallpaper.xml's android:settingsActivity — some OS wallpaper pickers'
 * settings gear icon launches this component directly. All options now live on the single main
 * screen, so this just redirects there instead of duplicating a settings UI.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
