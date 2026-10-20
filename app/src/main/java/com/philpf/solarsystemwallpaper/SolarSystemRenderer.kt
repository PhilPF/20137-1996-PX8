package com.philpf.solarsystemwallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        /** Multiplies the size of the Sun, planets and asteroid marker. */
        val bodyScale: Float = 1f,
        val distanceScale: DistanceScale = DistanceScale.SQRT,
    )

    private class Body(
        var kind: Int = 0,
        var index: Int = 0,
        var x: Float = 0f,
        var y: Float = 0f,
        var depth: Float = 0f,
        var sunDistanceAu: Double = 0.0,
    )

    private class Hit(val id: String, val x: Float, val y: Float, val radius: Float)

    /** A user-added object; [orbitAt] returns null until its data has been fetched. */
    class Extra(val obj: ExtraObject, val orbitAt: (Double) -> AsteroidOrbit?)

    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bgRect = RectF()
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
    private val bodies = List(PLANETS.size + 2 + ExtraObjects.MAX_OBJECTS) { Body() }
    private val extraOrbits = ArrayList<Pair<Extra, AsteroidOrbit>>()
    private val hits = ArrayList<Hit>()
    private val extraOrbitPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(0.9f); isAntiAlias = true }
    private val extraPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val tailPaint = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; isAntiAlias = true }
    private val highlightPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); isAntiAlias = true }
    private val drawOrder = ArrayList<Body>(bodies.size)

    private var bgCacheKey = ""
    private var bgBitmap: Bitmap? = null

    private fun dp(value: Float): Float = value * density

    private fun planetRadius(key: String, style: Style): Float {
        val base = if (style.relativeSizes) {
            val km = BodyFacts.PLANET_RADIUS_KM[key] ?: EARTH_RADIUS_KM
            (3.0 * (km / EARTH_RADIUS_KM).pow(0.42)).toFloat().coerceAtLeast(1.9f)
        } else {
            3.6f
        }
        return dp((base * style.bodyScale).coerceAtLeast(1.2f))
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
        extras: List<Extra> = emptyList(),
        highlightId: String? = null,
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

        // Extra objects that have data (and a closed orbit); the view widens to fit far ones.
        extraOrbits.clear()
        for (x in extras.take(ExtraObjects.MAX_OBJECTS)) {
            val o = x.orbitAt(jd) ?: continue
            if (o.e < 0.995 && o.a > 0) extraOrbits.add(x to o)
        }
        val maxAu = ExtraObjects.fitMaxAu(extraOrbits.map { (_, o) -> o.a * (1 + o.e) })

        val projection = ViewProjection(
            centerX, centerY, (minOf(w, h) * 0.46 * camera.zoom).toFloat(), camera.azimuthDeg, camera.tiltDeg,
            maxAu = maxAu,
            scale = style.distanceScale,
        )

        // Orbit lines, beneath every body.
        orbitPaint.color = if (mono) Color.argb(77, 255, 255, 255) else Color.argb(89, 178, 181, 191)
        val planetEls = PLANETS.map { planetElementsAt(it, t) }
        for (el in planetEls) {
            tracePath(projection, orbitPath(el.a, el.e, el.i, el.om, el.w, 180))
            canvas.drawPath(reusablePath, orbitPaint)
        }
        for ((x, o) in extraOrbits) {
            tracePath(projection, orbitPath(o.a, o.e, o.i, o.om, o.w, 240))
            extraOrbitPaint.color = if (mono) Color.WHITE else parseColorSafe(x.obj.colorHex, Color.LTGRAY)
            extraOrbitPaint.alpha = if (mono) 56 else 77
            canvas.drawPath(reusablePath, extraOrbitPaint)
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
        for ((i, xo) in extraOrbits.withIndex()) {
            val pos = asteroidPositionAt(xo.second, jd)
            projection.project(pos, pt)
            drawOrder.add(bodies[PLANETS.size + 2 + i].set(KIND_EXTRA, i).also { it.sunDistanceAu = BodyFacts.length(pos) })
        }
        drawOrder.sortBy { it.depth }

        hits.clear()
        for (b in drawOrder) {
            val r = when (b.kind) {
                KIND_PLANET -> drawPlanet(canvas, b, style)
                KIND_SUN -> drawSun(canvas, b, style)
                KIND_ASTEROID -> drawAsteroid(canvas, b, asteroidColorInt, style)
                else -> drawExtra(canvas, b, extraOrbits[b.index].first, style, centerX, centerY)
            }
            // Nearest bodies last, so they win ties when hit-testing from the end.
            hits.add(Hit(idOf(b), b.x, b.y, r))
        }
        if (highlightId != null) {
            hits.firstOrNull { it.id == highlightId }?.let { hit ->
                highlightPaint.color = if (mono) Color.WHITE else asteroidColorInt
                canvas.drawCircle(hit.x, hit.y, hit.radius + dp(5f), highlightPaint)
            }
        }
    }

    private fun idOf(b: Body): String = when (b.kind) {
        KIND_PLANET -> PLANETS[b.index].key
        KIND_SUN -> ID_SUN
        KIND_ASTEROID -> ID_ASTEROID
        else -> EXTRA_PREFIX + extraOrbits[b.index].first.obj.id
    }

    /**
     * The body drawn nearest to ([x], [y]) in the last frame, within [slopPx] of its edge, or
     * null. Ids: planet keys, [ID_SUN], [ID_ASTEROID], or [EXTRA_PREFIX] + extra object id.
     */
    fun bodyAt(x: Float, y: Float, slopPx: Float): String? {
        var best: Hit? = null
        var bestScore = Float.MAX_VALUE
        for (hit in hits) {
            val d = hypot(hit.x - x, hit.y - y) - hit.radius
            if (d <= slopPx && d <= bestScore) {
                best = hit
                bestScore = d
            }
        }
        return best?.id
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

    /**
     * Deep navy, slightly lighter towards the centre, with a faint warm haze around the Sun.
     *
     * Computed pixel by pixel (cached until the size changes) instead of with gradient shaders:
     * the colors are so close together that an 8-bit gradient shows visible rings. Each pixel gets
     * a little random noise before rounding (dithering), which hides the steps. Large screens are
     * rendered at half resolution and scaled up smoothly, which keeps the dither.
     */
    private fun drawBackground(canvas: Canvas, w: Float, h: Float, cx: Float, cy: Float, mono: Boolean) {
        val key = "$w:$h:$cx:$cy:$mono"
        if (key != bgCacheKey || bgBitmap == null) {
            bgCacheKey = key
            // Not recycled: a frame still being drawn may reference the old one; GC frees it.
            bgBitmap = buildBackground(w, h, cx, cy, mono)
        }
        bgRect.set(0f, 0f, w, h)
        canvas.drawBitmap(bgBitmap!!, null, bgRect, bgPaint)
    }

    private fun buildBackground(w: Float, h: Float, cx: Float, cy: Float, mono: Boolean): Bitmap {
        val step = if (max(w, h) > 1400f) 2f else 1f
        val bw = ceil(w / step).toInt().coerceAtLeast(1)
        val bh = ceil(h / step).toInt().coerceAtLeast(1)
        val inner = if (mono) floatArrayOf(34f, 36f, 42f) else floatArrayOf(35f, 42f, 61f)
        val mid = if (mono) floatArrayOf(23f, 24f, 29f) else floatArrayOf(23f, 26f, 36f)
        val outer = if (mono) floatArrayOf(16f, 17f, 20f) else floatArrayOf(15f, 17f, 24f)
        val haze = if (mono) floatArrayOf(255f, 255f, 255f) else floatArrayOf(255f, 201f, 122f)
        val hazeStrength = if (mono) 0.09f else 0.11f
        val reach = hypot(w, h) * 0.62f
        val hazeR = dp(170f)
        var seed = 0x2545F491
        val pixels = IntArray(bw * bh)
        val c = FloatArray(3)
        for (y in 0 until bh) {
            val py = (y + 0.5f) * step - cy
            for (x in 0 until bw) {
                val px = (x + 0.5f) * step - cx
                val d = sqrt(px * px + py * py)
                val t = (d / reach).coerceIn(0f, 1f)
                // Two smooth segments: centre -> mid by 55% of the reach, mid -> edge after that.
                if (t < 0.55f) mix(inner, mid, smooth(t / 0.55f), c) else mix(mid, outer, smooth((t - 0.55f) / 0.45f), c)
                val hz = (1f - d / hazeR).coerceIn(0f, 1f)
                val ha = hazeStrength * hz * hz
                var argb = 0xFF shl 24
                for (ch in 0..2) {
                    seed = seed xor (seed shl 13)
                    seed = seed xor (seed ushr 17)
                    seed = seed xor (seed shl 5)
                    val noise = (seed and 0xFFFF) / 65535f - 0.5f
                    val v = (c[ch] + (haze[ch] - c[ch]) * ha + noise).roundToInt().coerceIn(0, 255)
                    argb = argb or (v shl (16 - 8 * ch))
                }
                pixels[y * bw + x] = argb
            }
        }
        return Bitmap.createBitmap(pixels, bw, bh, Bitmap.Config.ARGB_8888)
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun mix(a: FloatArray, b: FloatArray, t: Float, out: FloatArray) {
        for (i in 0..2) out[i] = a[i] + (b[i] - a[i]) * t
    }

    private fun drawPlanet(canvas: Canvas, b: Body, style: Style): Float {
        val p = PLANETS[b.index]
        val r = planetRadius(p.key, style)
        planetPaint.color = if (style.monochrome) Color.rgb(240, 240, 240) else parseColorSafe(p.colorHex, Color.GRAY)
        canvas.drawCircle(b.x, b.y, r, planetPaint)
        if (style.showPlanetLabels) {
            labelPaint.color = if (style.monochrome) Color.argb(191, 255, 255, 255) else Color.argb(230, 209, 212, 222)
            canvas.drawText(p.label, b.x + r + dp(3.5f), b.y - r - dp(2f), labelPaint)
        }
        return r
    }

    private fun drawSun(canvas: Canvas, b: Body, style: Style): Float {
        val mono = style.monochrome
        val core = dp((if (style.relativeSizes) 13f else 5f) * style.bodyScale).coerceAtLeast(dp(2f))
        val glow = core * (if (style.relativeSizes) 2.6f else 2.8f)
        sunGradientPaint.shader = RadialGradient(
            b.x, b.y, glow,
            if (mono) intArrayOf(Color.WHITE, Color.TRANSPARENT) else intArrayOf(Color.parseColor("#fff3c4"), Color.argb(0, 255, 223, 128)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(b.x, b.y, glow, sunGradientPaint)
        sunCorePaint.color = if (mono) Color.WHITE else Color.parseColor("#ffe9a8")
        canvas.drawCircle(b.x, b.y, core, sunCorePaint)
        return core
    }

    private fun drawAsteroid(canvas: Canvas, b: Body, color: Int, style: Style): Float {
        val dotR = dp(4f * style.bodyScale).coerceAtLeast(dp(1.6f))
        val ringR = dp(8f * style.bodyScale).coerceAtLeast(dotR + dp(2.5f))
        if (style.monochrome) {
            val haloR = ringR * 2f
            asteroidHaloPaint.shader = RadialGradient(
                b.x, b.y, haloR,
                intArrayOf(Color.argb(140, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(b.x, b.y, haloR, asteroidHaloPaint)
        }
        asteroidRingPaint.color = color
        asteroidRingPaint.alpha = (0.5f * 255).toInt()
        canvas.drawCircle(b.x, b.y, ringR, asteroidRingPaint)
        asteroidDotPaint.color = color
        canvas.drawCircle(b.x, b.y, dotR, asteroidDotPaint)
        if (style.showAsteroidLabel) {
            asteroidLabelPaint.color = color
            canvas.drawText("Angeljorba", b.x + ringR + dp(1f), b.y - ringR, asteroidLabelPaint)
        }
        return ringR
    }

    /** Small dot; comets also get a short tail pointing away from the Sun when within ~5 AU. */
    private fun drawExtra(canvas: Canvas, b: Body, extra: Extra, style: Style, sunX: Float, sunY: Float): Float {
        val o = extra.obj
        val color = if (style.monochrome) Color.rgb(230, 230, 230) else parseColorSafe(o.colorHex, Color.LTGRAY)
        val base = if (o.kind == ExtraKind.DWARF_PLANET) 3.0f else 2.4f
        val r = dp(base * style.bodyScale).coerceAtLeast(dp(1.4f))
        if (o.kind == ExtraKind.COMET) {
            val activity = ((5.0 - b.sunDistanceAu) / 4.0).coerceIn(0.0, 1.0).toFloat()
            val dx = b.x - sunX
            val dy = b.y - sunY
            val len = hypot(dx, dy)
            if (activity > 0f && len > 1f) {
                val tail = dp(26f) * activity * style.bodyScale.coerceAtLeast(0.6f)
                tailPaint.color = color
                tailPaint.alpha = (150 * activity).toInt()
                tailPaint.strokeWidth = r * 1.2f
                canvas.drawLine(b.x, b.y, b.x + dx / len * tail, b.y + dy / len * tail, tailPaint)
            }
        }
        extraPaint.color = color
        canvas.drawCircle(b.x, b.y, r, extraPaint)
        if (style.showPlanetLabels) {
            labelPaint.color = if (style.monochrome) Color.argb(191, 255, 255, 255) else Color.argb(230, 209, 212, 222)
            canvas.drawText(o.name, b.x + r + dp(3.5f), b.y - r - dp(2f), labelPaint)
        }
        return r
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
        private const val KIND_EXTRA = 3
        const val ID_SUN = "sun"
        const val ID_ASTEROID = "angeljorba"
        const val EXTRA_PREFIX = "extra:"
        private const val EARTH_RADIUS_KM = 6371.0
    }
}
