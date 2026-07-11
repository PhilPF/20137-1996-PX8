package com.philpf.solarsystemwallpaper

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Lets the user drag/pinch to position the camera the way the HTML reference's interactive
 * preview did, then persists it as the wallpaper's fixed camera angle — live wallpapers can't
 * reliably receive touch themselves (see design handoff), so positioning has to happen here.
 */
class CameraPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_preview)

        val previewView = findViewById<SolarSystemPreviewView>(R.id.previewView)

        findViewById<Button>(R.id.saveCameraButton).setOnClickListener {
            previewView.saveCamera()
            Toast.makeText(this, R.string.camera_saved_toast, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.resetCameraButton).setOnClickListener {
            previewView.resetCamera()
        }
    }
}
