package com.philpf.solarsystemwallpaper

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Interactive live preview backing the app's main screen: single-finger drag rotates azimuth
 * and tilts the camera, pinch zooms — the same gesture mapping as the design handoff's
 * `setupCameraControls()` (drag sensitivity 0.35/0.25, tilt clamped 0-85deg, zoom clamped
 * 0.3-6x). The camera auto-persists whenever a gesture ends.
 *
 * Also the [SceneSource] for the glass panels laid over it: each frame it invalidates the
 * registered backdrops, which re-render the same frame (same camera, same simulated instant)
 * blurred behind their panel.
 */
class SolarSystemPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), SceneSource {

    private val renderer = SolarSystemRenderer(resources.displayMetrics.density)
    private val prefs = WallpaperPrefs(context)
    private val backdrops = ArrayList<View>()

    var azimuthDeg = 0.0
        private set
    var tiltDeg = WallpaperPrefs.DEFAULT_TILT_DEG
        private set
    var zoom = 1.0
        private set

    /** Simulated instant of the frame being shown, shared with the backdrops. */
    var frameSimMillis: Long = System.currentTimeMillis()
        private set

    /** Called with true when a drag/pinch starts and false when it ends. */
    var onInteraction: ((Boolean) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(0.3, 6.0)
                invalidateFrame()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                saveCamera()
            }
        },
    )

    private val frameRunnable = object : Runnable {
        override fun run() {
            frameSimMillis = prefs.simulatedMillis()
            invalidateFrame()
            postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    init {
        loadSavedCamera()
    }

    override val sceneView: View get() = this

    override fun registerBackdrop(view: View) {
        if (view !in backdrops) backdrops.add(view)
    }

    override fun renderScene(canvas: Canvas) {
        val style = SolarSystemRenderer.Style(
            monochrome = prefs.monochrome,
            showPlanetLabels = prefs.showPlanetLabels,
            showAsteroidLabel = prefs.showAsteroidLabel,
            asteroidColorHex = prefs.asteroidColor,
        )
        val camera = SolarSystemRenderer.Camera(azimuthDeg, tiltDeg, zoom)
        renderer.render(canvas, width.toFloat(), height.toFloat(), frameSimMillis, camera, style, prefs::asteroidOrbitAt)
    }

    private fun invalidateFrame() {
        invalidate()
        for (b in backdrops) b.invalidate()
    }

    fun loadSavedCamera() {
        azimuthDeg = prefs.cameraAzimuthDeg
        tiltDeg = prefs.cameraTiltDeg
        zoom = prefs.cameraZoom
        invalidateFrame()
    }

    fun saveCamera() {
        prefs.setCamera(azimuthDeg, tiltDeg, zoom)
    }

    fun resetCamera() {
        prefs.resetCamera()
        loadSavedCamera()
    }

    /** Re-reads settings/time immediately instead of waiting for the next frame tick. */
    fun refreshNow() {
        frameSimMillis = prefs.simulatedMillis()
        invalidateFrame()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(frameRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
                onInteraction?.invoke(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    azimuthDeg += dx * 0.35
                    tiltDeg = (tiltDeg - dy * 0.25).coerceIn(0.0, 85.0)
                    invalidateFrame()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_POINTER_UP -> {
                // The finger that stays down becomes the drag pointer again.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                dragging = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                saveCamera()
                onInteraction?.invoke(false)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderScene(canvas)
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 33L
    }
}
