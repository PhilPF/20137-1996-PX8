package com.philpf.solarsystemwallpaper

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * Native reimplementation of the design handoff's Canvas2D reference: a live wallpaper
 * drawing the Sun, all 8 planets, and near-Earth asteroid (20137) Angeljorba using real
 * Keplerian orbital mechanics. See design_handoff_solar_system_wallpaper/README.md for
 * the full spec this ports (fixed camera, no network dependency, 30fps target).
 *
 * Rendering itself lives in [SolarSystemRenderer], shared with the in-app camera preview
 * screen so both stay pixel-identical; this class only owns the wallpaper lifecycle, the
 * frame loop, and the ambient camera drift/parallax added on top of the user's saved camera.
 */
class SolarSystemWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SolarSystemEngine()

    private inner class SolarSystemEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val prefs = WallpaperPrefs(this@SolarSystemWallpaperService)
        private val renderer = SolarSystemRenderer(this@SolarSystemWallpaperService.resources.displayMetrics.density)
        private val handler = Handler(Looper.getMainLooper())

        private var visible = false
        private var xOffset = 0.5f
        private var azimuthDriftDeg = 0.0
        private var lastFrameElapsedRealtime = 0L

        private val drawRunnable = Runnable { draw() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.registerListener(this)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                lastFrameElapsedRealtime = SystemClock.elapsedRealtime()
                handler.post(drawRunnable)
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            this.xOffset = xOffset
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunnable)
        }

        override fun onDestroy() {
            prefs.unregisterListener(this)
            handler.removeCallbacks(drawRunnable)
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            // Settings are read live in draw(); nothing to cache.
        }

        private fun draw() {
            val holder = surfaceHolder
            var canvas: android.graphics.Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: IllegalArgumentException) {
                        // Surface was destroyed concurrently; nothing to post.
                    }
                }
            }
            if (visible) handler.postDelayed(drawRunnable, FRAME_INTERVAL_MS)
        }

        private fun render(canvas: android.graphics.Canvas) {
            val now = SystemClock.elapsedRealtime()
            val dtSec = if (lastFrameElapsedRealtime == 0L) 0.0 else (now - lastFrameElapsedRealtime) / 1000.0
            lastFrameElapsedRealtime = now

            if (prefs.azimuthDrift) {
                azimuthDriftDeg = (azimuthDriftDeg + WallpaperPrefs.AZIMUTH_DRIFT_DEG_PER_SEC * dtSec) % 360.0
            }
            val parallaxDeg = if (prefs.parallax) ((xOffset - 0.5) * 2.0 * WallpaperPrefs.PARALLAX_MAX_DEG) else 0.0

            val camera = SolarSystemRenderer.Camera(
                azimuthDeg = prefs.cameraAzimuthDeg + azimuthDriftDeg + parallaxDeg,
                tiltDeg = prefs.cameraTiltDeg,
                zoom = prefs.cameraZoom,
            )
            val style = SolarSystemRenderer.Style(
                monochrome = prefs.monochrome,
                showPlanetLabels = prefs.showPlanetLabels,
                showAsteroidLabel = prefs.showAsteroidLabel,
                asteroidColorHex = prefs.asteroidColor,
            )
            renderer.render(
                canvas, canvas.width.toFloat(), canvas.height.toFloat(),
                prefs.simulatedMillis(), camera, style, prefs.asteroidOrbit,
            )
        }
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 33L // ~30fps target, per design handoff
    }
}
