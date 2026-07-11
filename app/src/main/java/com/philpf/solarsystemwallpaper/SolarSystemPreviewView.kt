package com.philpf.solarsystemwallpaper

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Interactive live preview used by [CameraPreviewActivity]: single-finger drag rotates azimuth
 * and tilts the camera, pinch zooms — the same gesture mapping as the design handoff's
 * `setupCameraControls()` (drag sensitivity 0.35/0.25, tilt clamped 0-85deg, zoom clamped
 * 0.3-6x). Renders continuously via [SolarSystemRenderer] so planet motion stays visible while
 * the user positions the view.
 */
class SolarSystemPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val renderer = SolarSystemRenderer(resources.displayMetrics.density)
    private val prefs = WallpaperPrefs(context)

    var azimuthDeg = 0.0
        private set
    var tiltDeg = WallpaperPrefs.DEFAULT_TILT_DEG
        private set
    var zoom = 1.0
        private set

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(0.3, 6.0)
                invalidate()
                return true
            }
        },
    )

    private val frameRunnable = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    init {
        loadSavedCamera()
    }

    fun loadSavedCamera() {
        azimuthDeg = prefs.cameraAzimuthDeg
        tiltDeg = prefs.cameraTiltDeg
        zoom = prefs.cameraZoom
        invalidate()
    }

    fun saveCamera() {
        prefs.setCamera(azimuthDeg, tiltDeg, zoom)
    }

    fun resetCamera() {
        prefs.resetCamera()
        loadSavedCamera()
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
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    azimuthDeg += dx * 0.35
                    tiltDeg = (tiltDeg - dy * 0.25).coerceIn(0.0, 85.0)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = event.pointerCount <= 1
                if (event.pointerCount == 1) {
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val style = SolarSystemRenderer.Style(
            monochrome = prefs.monochrome,
            showPlanetLabels = prefs.showPlanetLabels,
            showAsteroidLabel = prefs.showAsteroidLabel,
            asteroidColorHex = prefs.asteroidColor,
        )
        val camera = SolarSystemRenderer.Camera(azimuthDeg, tiltDeg, zoom)
        renderer.render(canvas, width.toFloat(), height.toFloat(), prefs.simulatedMillis(), camera, style)
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 33L
    }
}
