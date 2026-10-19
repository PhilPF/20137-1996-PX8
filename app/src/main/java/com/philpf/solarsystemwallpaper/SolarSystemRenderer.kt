package com.philpf.solarsystemwallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Draws the scene, shared by the live wallpaper, the in-app preview and the widget so all three
 * stay identical. Orbit lines are drawn first; then every body (planets, Sun, asteroid) is
 * sorted by its depth from [ViewProjection] and drawn back to front, so whatever is nearer the
 * viewer overlaps what is behind it.
 */
class SolarSystemRenderer(private val density: Float) {

    data class Camera(val azimuthDeg: Double, val tiltDeg: Double, val zoom: Double)

    data class Style(
        val monochrome: Boolean,
        val showPlanetLabels: Boolean,
        val showAsteroidLabel: Boolean,
        val asteroidColorHex: String,
        /** Sizes reflect real radii (compressed so Mercury stays visible and the Sun doesn't swamp the view). */
        val relativeSizes: Boolean = false,
    )

    private class Body(var kind: Int = 0, var index: Int = 0, var x: Float = 0f, var y: Float = 0f, var depth: Float = 0f)

    private val bgPaint = Paint()
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private val pt = FloatArray(3)
    private val bodies = List(PLANETS.size + 2) { Body() }
    private val drawOrder = ArrayList<Body>(bodies.size)

    private var bgCacheKey = ""

    private fun dp(value: Float): Float = value * density

    private fun planetRadius(key: String, relative: Boolean): Float {
        if (!relative) return dp(3.6f)
        val km = RADIUS_KM[key] ?: EARTH_RADIUS_KM
        return dp((3.0 * (km / EARTH_RADIUS_KM).pow(0.42)).toFloat().coerceAtLeast(1.9f))
    }

    /**
     * Draws the scene at [simMillis]. [asteroidAt] returns the asteroid's osculating elements to
     * propagate from for a given Julian Date (the nearest epoch of the stored JPL table).
     */
    fun render(
        canvas: Canvas,
        width: Float,
        height: Float,
        simMillis: Long,
        camera: Camera,
        style: Style,
        asteroidAt: (Double) -> AsteroidOrbit,
    ) {
        val w = width
        val h = height
        if (w <= 0f || h <= 0f) return

        val mono = style.monochrome
        val centerX = w / 2f
        val centerY = h / 2f + minOf(w, h) * 0.02f
        drawBackground(canvas, w, h, centerX, centerY, mono)

        val jd = jdFromUnixMillis(simMillis)
        val t = centuriesSinceJ2000(jd)
        val asteroidColorInt = if (mono) Color.WHITE else parseColorSafe(style.asteroidColorHex, Color.parseColor("#ffd166"))
        val projection = ViewProjection(
            centerX, centerY, (minOf(w, h) * 0.46 * camera.zoom).toFloat(), camera.azimuthDeg, camera.tiltDeg,
        )

        // Orbit lines, beneath every body.
        orbitPaint.color = if (mono) Color.argb(77, 255, 255, 255) else Color.argb(89, 178, 181, 191)
        val planetEls = PLANETS.map { planetElementsAt(it, t) }
        for (el in planetEls) {
            tracePath(projection, orbitPath(el.a, el.e, el.i, el.om, el.w, 180))
            canvas.drawPath(reusablePath, orbitPaint)
        }
        val ae = asteroidAt(jd)
        tracePath(projection, orbitPath(ae.a, ae.e, ae.i, ae.om, ae.w, 220))
        asteroidOrbitPaint.color = asteroidColorInt
        asteroidOrbitPaint.alpha = (0.55f * 255).toInt()
        canvas.drawPath(reusablePath, asteroidOrbitPaint)

        // Bodies, far to near.
        drawOrder.clear()
        for ((i, el) in planetEls.withIndex()) {
            projection.project(positionFromElements(el.a, el.e, el.i, el.om, el.w, el.M), pt)
            drawOrder.add(bodies[i].set(KIND_PLANET, i))
        }
        projection.project(Vec3(0.0, 0.0, 0.0), pt)
        drawOrder.add(bodies[PLANETS.size].set(KIND_SUN, 0))
        projection.project(asteroidPositionAt(ae, jd), pt)
        drawOrder.add(bodies[PLANETS.size + 1].set(KIND_ASTEROID, 0))
        drawOrder.sortBy { it.depth }

        for (b in drawOrder) {
            when (b.kind) {
                KIND_PLANET -> drawPlanet(canvas, b, style)
                KIND_SUN -> drawSun(canvas, b, mono, style.relativeSizes)
                KIND_ASTEROID -> drawAsteroid(canvas, b, asteroidColorInt, style)
            }
        }
    }

    private fun Body.set(kind: Int, index: Int): Body {
        this.kind = kind
        this.index = index
        x = pt[0]
        y = pt[1]
        depth = pt[2]
        return this
    }

    private fun tracePath(projection: ViewProjection, points: List<Vec3>) {
        reusablePath.reset()
        for ((idx, p) in points.withIndex()) {
            projection.project(p, pt)
            if (idx == 0) reusablePath.moveTo(pt[0], pt[1]) else reusablePath.lineTo(pt[0], pt[1])
        }
        reusablePath.close()
    }

    /** Deep navy with a slightly lighter centre and a faint warm haze around the Sun. */
    private fun drawBackground(canvas: Canvas, w: Float, h: Float, cx: Float, cy: Float, mono: Boolean) {
        val key = "$w:$h:$mono"
        if (key != bgCacheKey) {
            bgCacheKey = key
            val reach = hypot(w, h) * 0.62f
            bgPaint.shader = RadialGradient(
                cx, cy, reach,
                if (mono) intArrayOf(0xFF22242A.toInt(), 0xFF17181D.toInt(), 0xFF101114.toInt())
                else intArrayOf(0xFF232A3D.toInt(), 0xFF171A24.toInt(), 0xFF0F1118.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            hazePaint.shader = RadialGradient(
                cx, cy, dp(150f),
                if (mono) intArrayOf(0x1FFFFFFF, 0x00FFFFFF) else intArrayOf(0x26FFC97A, 0x00FFC97A),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawCircle(cx, cy, dp(150f), hazePaint)
    }

    private fun drawPlanet(canvas: Canvas, b: Body, style: Style) {
        val p = PLANETS[b.index]
        val r = planetRadius(p.key, style.relativeSizes)
        planetPaint.color = if (style.monochrome) Color.rgb(240, 240, 240) else parseColorSafe(p.colorHex, Color.GRAY)
        canvas.drawCircle(b.x, b.y, r, planetPaint)
        if (style.showPlanetLabels) {
            labelPaint.color = if (style.monochrome) Color.argb(191, 255, 255, 255) else Color.argb(230, 209, 212, 222)
            canvas.drawText(p.label, b.x + r + dp(3.5f), b.y - r - dp(2f), labelPaint)
        }
    }

    private fun drawSun(canvas: Canvas, b: Body, mono: Boolean, relative: Boolean) {
        val core = dp(if (relative) 13f else 5f)
        val glow = dp(if (relative) 34f else 14f)
        sunGradientPaint.shader = RadialGradient(
            b.x, b.y, glow,
            if (mono) intArrayOf(Color.WHITE, Color.TRANSPARENT) else intArrayOf(Color.parseColor("#fff3c4"), Color.argb(0, 255, 223, 128)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(b.x, b.y, glow, sunGradientPaint)
        sunCorePaint.color = if (mono) Color.WHITE else Color.parseColor("#ffe9a8")
        canvas.drawCircle(b.x, b.y, core, sunCorePaint)
    }

    private fun drawAsteroid(canvas: Canvas, b: Body, color: Int, style: Style) {
        if (style.monochrome) {
            val haloR = dp(16f)
            asteroidHaloPaint.shader = RadialGradient(
                b.x, b.y, haloR,
                intArrayOf(Color.argb(140, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(b.x, b.y, haloR, asteroidHaloPaint)
        }
        asteroidRingPaint.color = color
        asteroidRingPaint.alpha = (0.5f * 255).toInt()
        canvas.drawCircle(b.x, b.y, dp(8f), asteroidRingPaint)
        asteroidDotPaint.color = color
        canvas.drawCircle(b.x, b.y, dp(4f), asteroidDotPaint)
        if (style.showAsteroidLabel) {
            asteroidLabelPaint.color = color
            canvas.drawText("Angeljorba", b.x + dp(9f), b.y - dp(8f), asteroidLabelPaint)
        }
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int =
        try {
            Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            fallback
        }

    companion object {
        private const val KIND_PLANET = 0
        private const val KIND_SUN = 1
        private const val KIND_ASTEROID = 2
        private const val EARTH_RADIUS_KM = 6371.0
        private val RADIUS_KM = mapOf(
            "mercury" to 2439.7, "venus" to 6051.8, "earth" to 6371.0, "mars" to 3389.5,
            "jupiter" to 69911.0, "saturn" to 58232.0, "uranus" to 25362.0, "neptune" to 24622.0,
        )
    }
}
