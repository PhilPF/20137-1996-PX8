package com.philpf.solarsystemwallpaper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot

/**
 * Interactive live preview backing the app's main screen: single-finger drag rotates azimuth
 * and tilts the camera, pinch zooms — the same gesture mapping as the design handoff's
 * `setupCameraControls()` (drag sensitivity 0.35/0.25, tilt clamped 0-85deg). Zoom goes to 12x
 * (the reference stopped at 6x) so the inner planets can be seen with linear distances. The
 * camera auto-persists whenever a gesture ends.
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

    /** Called with true when a drag/pinch starts and false when it ends (not for plain taps). */
    var onInteraction: ((Boolean) -> Unit)? = null

    /** A tap (no drag or pinch) at view coordinates, with the id of the body under it, if any. */
    var onTap: ((x: Float, y: Float, bodyId: String?) -> Unit)? = null

    /** Body to ring in the preview (the one whose info card is open); not shown on the wallpaper. */
    var highlightId: String? = null
        set(value) {
            field = value
            invalidateFrame()
        }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    /** True once the gesture moved past the touch slop or became a pinch: it's no longer a tap. */
    private var gestureActive = false

    private var cameraAnimator: ValueAnimator? = null
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
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
        val style = prefs.renderStyle()
        val camera = SolarSystemRenderer.Camera(azimuthDeg, tiltDeg, zoom)
        renderer.render(
            canvas, width.toFloat(), height.toFloat(), frameSimMillis, camera, style, prefs::asteroidOrbitAt,
            prefs.renderExtras(), highlightId,
        )
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

    /** Glides back to the saved view (or the original default), then persists it. */
    fun goToHomeView() {
        val target = prefs.homeView
        val fromAz = azimuthDeg
        val fromTilt = tiltDeg
        val fromZoom = zoom
        // Turn the short way round.
        val dAz = ((target.azimuthDeg - fromAz) % 360.0 + 540.0) % 360.0 - 180.0
        cameraAnimator?.cancel()
        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val t = (it.animatedValue as Float).toDouble()
                azimuthDeg = fromAz + dAz * t
                tiltDeg = fromTilt + (target.tiltDeg - fromTilt) * t
                // Zoom eases in log space so in and out feel the same.
                zoom = fromZoom * Math.pow(target.zoom / fromZoom, t)
                invalidateFrame()
                if (t >= 1.0) saveCamera()
            }
            start()
        }
    }

    fun saveCurrentAsHomeView() = prefs.saveView(azimuthDeg, tiltDeg, zoom)

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

    private fun startGesture() {
        if (gestureActive) return
        gestureActive = true
        onInteraction?.invoke(true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) startGesture()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cameraAnimator?.cancel()
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                dragging = true
                gestureActive = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive && hypot(event.x - downX, event.y - downY) > touchSlop) startGesture()
                if (gestureActive && dragging && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    azimuthDeg += dx * 0.35
                    tiltDeg = (tiltDeg - dy * 0.25).coerceIn(0.0, 85.0)
                    invalidateFrame()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = false
                startGesture()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The finger that stays down becomes the drag pointer again.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                dragging = true
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                if (gestureActive) {
                    saveCamera()
                    gestureActive = false
                    onInteraction?.invoke(false)
                } else if (event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()) {
                    performClick()
                    val slop = resources.displayMetrics.density * 14f
                    onTap?.invoke(event.x, event.y, renderer.bodyAt(event.x, event.y, slop))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (gestureActive) {
                    saveCamera()
                    gestureActive = false
                    onInteraction?.invoke(false)
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderScene(canvas)
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 33L
        private const val MIN_ZOOM = 0.3
        private const val MAX_ZOOM = 12.0
    }
}
