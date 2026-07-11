package com.philpf.solarsystemwallpaper

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native reimplementation of the design handoff's Canvas2D reference: a live wallpaper
 * drawing the Sun, all 8 planets, and near-Earth asteroid (20137) Angeljorba using real
 * Keplerian orbital mechanics. See design_handoff_solar_system_wallpaper/README.md for
 * the full spec this ports (fixed camera, no network dependency, 30fps target).
 */
class SolarSystemWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SolarSystemEngine()

    private inner class SolarSystemEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val prefs = WallpaperPrefs(this@SolarSystemWallpaperService)
        private val handler = Handler(Looper.getMainLooper())
        private val density = this@SolarSystemWallpaperService.resources.displayMetrics.density

        private var visible = false
        private var xOffset = 0.5f
        private var azimuthDriftDeg = 0.0
        private var lastFrameElapsedRealtime = 0L
        private var acceleratedSimMillis = System.currentTimeMillis()

        private val bgPaint = Paint().apply { color = Color.parseColor("#171A24") }
        private val orbitPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); isAntiAlias = true }
        private val planetPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        private val labelPaint = Paint().apply { isAntiAlias = true; textSize = dp(11f) }
        private val sunGradientPaint = Paint().apply { isAntiAlias = true }
        private val sunCorePaint = Paint().apply { isAntiAlias = true }
        private val asteroidOrbitPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(1.3f); isAntiAlias = true }
        private val asteroidHaloPaint = Paint().apply { isAntiAlias = true }
        private val asteroidRingPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(1.4f); isAntiAlias = true }
        private val asteroidDotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        private val asteroidLabelPaint = Paint().apply { isAntiAlias = true; textSize = dp(12f) }
        private val reusablePath = Path()

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

        private fun dp(value: Float): Float = value * density

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

        private fun project(
            p: Vec3,
            centerX: Float,
            centerY: Float,
            maxRadiusPx: Float,
            azRad: Double,
            tiltRad: Double,
        ): FloatArray {
            val r3 = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
            val factor = if (r3 > 1e-9) dispScale(r3, maxRadiusPx) / r3 else 0.0
            val sx = p.x * factor
            val sy = p.y * factor
            val sz = p.z * factor
            val rx = sx * cos(azRad) - sy * sin(azRad)
            val ry = sx * sin(azRad) + sy * cos(azRad)
            val py = ry * cos(tiltRad) - sz * sin(tiltRad)
            return floatArrayOf((centerX + rx).toFloat(), (centerY - py).toFloat())
        }

        /** sqrt radial compression so outer planets stay on-screen, per design handoff §Orbital Mechanics step 8. */
        private fun dispScale(auR: Double, maxRadiusPx: Float): Double {
            val f = sqrt(auR) / sqrt(MAX_AU)
            return f * maxRadiusPx
        }

        private fun parseColorSafe(hex: String, fallback: Int): Int =
            try {
                Color.parseColor(hex)
            } catch (_: IllegalArgumentException) {
                fallback
            }

        private fun render(canvas: android.graphics.Canvas) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            if (w <= 0f || h <= 0f) return

            val now = SystemClock.elapsedRealtime()
            val dtSec = if (lastFrameElapsedRealtime == 0L) 0.0 else (now - lastFrameElapsedRealtime) / 1000.0
            lastFrameElapsedRealtime = now

            if (prefs.azimuthDrift) {
                azimuthDriftDeg = (azimuthDriftDeg + WallpaperPrefs.AZIMUTH_DRIFT_DEG_PER_SEC * dtSec) % 360.0
            }

            val simMillis: Long = if (prefs.realTime) {
                System.currentTimeMillis()
            } else {
                acceleratedSimMillis += (dtSec * WallpaperPrefs.ACCELERATED_DAYS_PER_SEC * 86400000.0).toLong()
                acceleratedSimMillis
            }

            val jd = jdFromUnixMillis(simMillis)
            val t = (jd - 2451545.0) / 36525.0

            val mono = prefs.monochrome
            val showPlanetLabels = prefs.showPlanetLabels
            val showAsteroidLabel = prefs.showAsteroidLabel
            val asteroidColorInt = if (mono) Color.WHITE else parseColorSafe(prefs.asteroidColor, Color.parseColor("#ffd166"))

            val parallaxDeg = if (prefs.parallax) ((xOffset - 0.5) * 2.0 * WallpaperPrefs.PARALLAX_MAX_DEG) else 0.0
            val azRad = (azimuthDriftDeg + parallaxDeg) * DEG_TO_RAD
            val tiltRad = FIXED_TILT_DEG * DEG_TO_RAD

            val centerX = w / 2f
            val centerY = h / 2f + minOf(w, h) * 0.02f
            val maxRadiusPx = minOf(w, h) * 0.46f

            val orbitColor = if (mono) Color.argb(77, 255, 255, 255) else Color.argb(89, 178, 181, 191)
            val planetLabelColor = if (mono) Color.argb(191, 255, 255, 255) else Color.argb(230, 209, 212, 222)

            for (p in PLANETS) {
                val el = planetElementsAt(p, t)
                val pts = orbitPath(el.a, el.e, el.i, el.om, el.w, 180)
                reusablePath.reset()
                pts.forEachIndexed { idx, pt ->
                    val s = project(pt, centerX, centerY, maxRadiusPx, azRad, tiltRad)
                    if (idx == 0) reusablePath.moveTo(s[0], s[1]) else reusablePath.lineTo(s[0], s[1])
                }
                reusablePath.close()
                orbitPaint.color = orbitColor
                canvas.drawPath(reusablePath, orbitPaint)

                val pos = positionFromElements(el.a, el.e, el.i, el.om, el.w, el.M)
                val s = project(pos, centerX, centerY, maxRadiusPx, azRad, tiltRad)
                planetPaint.color = if (mono) Color.rgb(240, 240, 240) else parseColorSafe(p.colorHex, Color.GRAY)
                canvas.drawCircle(s[0], s[1], dp(3.6f), planetPaint)
                if (showPlanetLabels) {
                    labelPaint.color = planetLabelColor
                    canvas.drawText(p.label, s[0] + dp(7f), s[1] - dp(6f), labelPaint)
                }
            }

            val sunPos = project(Vec3(0.0, 0.0, 0.0), centerX, centerY, maxRadiusPx, azRad, tiltRad)
            val sunOuter = dp(14f)
            sunGradientPaint.shader = RadialGradient(
                sunPos[0], sunPos[1], sunOuter,
                if (mono) intArrayOf(Color.WHITE, Color.TRANSPARENT) else intArrayOf(Color.parseColor("#fff3c4"), Color.argb(0, 255, 223, 128)),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(sunPos[0], sunPos[1], sunOuter, sunGradientPaint)
            sunCorePaint.color = if (mono) Color.WHITE else Color.parseColor("#ffe9a8")
            canvas.drawCircle(sunPos[0], sunPos[1], dp(5f), sunCorePaint)

            val ae = AsteroidAngeljorba
            val asteroidPts = orbitPath(ae.a, ae.e, ae.i, ae.om, ae.w, 220)
            reusablePath.reset()
            asteroidPts.forEachIndexed { idx, pt ->
                val s = project(pt, centerX, centerY, maxRadiusPx, azRad, tiltRad)
                if (idx == 0) reusablePath.moveTo(s[0], s[1]) else reusablePath.lineTo(s[0], s[1])
            }
            reusablePath.close()
            asteroidOrbitPaint.color = asteroidColorInt
            asteroidOrbitPaint.alpha = (0.55f * 255).toInt()
            canvas.drawPath(reusablePath, asteroidOrbitPaint)

            val n = (K_GAUSS * 180.0 / PI) / ae.a.pow(1.5)
            val mAnom = ae.ma + n * (jd - ae.epochJd)
            val pos = positionFromElements(ae.a, ae.e, ae.i, ae.om, ae.w, mAnom)
            val s = project(pos, centerX, centerY, maxRadiusPx, azRad, tiltRad)

            if (mono) {
                val haloR = dp(16f)
                asteroidHaloPaint.shader = RadialGradient(
                    s[0], s[1], haloR,
                    intArrayOf(Color.argb(140, 255, 255, 255), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(s[0], s[1], haloR, asteroidHaloPaint)
            }
            asteroidRingPaint.color = asteroidColorInt
            asteroidRingPaint.alpha = (0.5f * 255).toInt()
            canvas.drawCircle(s[0], s[1], dp(8f), asteroidRingPaint)
            asteroidDotPaint.color = asteroidColorInt
            canvas.drawCircle(s[0], s[1], dp(4f), asteroidDotPaint)
            if (showAsteroidLabel) {
                asteroidLabelPaint.color = asteroidColorInt
                canvas.drawText("Angeljorba", s[0] + dp(9f), s[1] - dp(8f), asteroidLabelPaint)
            }
        }
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 33L // ~30fps target, per design handoff
        private const val DEG_TO_RAD = PI / 180.0
        private const val FIXED_TILT_DEG = 22.0
        private const val MAX_AU = 31.0
    }
}
