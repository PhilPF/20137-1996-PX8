package com.philpf.solarsystemwallpaper

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * Flat, translucent "Liquid Glass"-style controls. Android has no system glass material, so:
 *
 * - [GlassPanel] re-draws the live scene behind itself (through [GlassBackdropView], offset to
 *   line up with the full-screen preview) and blurs it with a [RenderEffect] on Android 12+, then
 *   lays a faint white gradient and a thin light rim over it. Older versions get a translucent fill.
 * - Controls are flat: translucent white fills, no gradients, shadows or bevels. The accent color
 *   is used sparingly (switches that are on, the primary button, the selected swatch).
 */
object Glass {
    const val TEXT_PRIMARY = 0xF5FFFFFF.toInt()
    const val TEXT_SECONDARY = 0xA6FFFFFF.toInt()
    const val TEXT_TERTIARY = 0x70FFFFFF
    const val TEXT_ON_ACCENT = 0xFF101219.toInt()
    const val HAIRLINE = 0x1AFFFFFF

    /** Fill for controls sitting on a panel (segmented track, secondary buttons, switch off). */
    const val CONTROL_FILL = 0x1AFFFFFF

    /** Fill for grouped rows inside a panel. */
    const val GROUP_FILL = 0x0FFFFFFF

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    private fun ripple(context: Context, content: Drawable): Drawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(context, 100f)
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
    }

    private fun flat(context: Context, color: Int, radiusDp: Float = 100f): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp)
            setColor(color)
        }

    fun primaryButtonBackground(context: Context, accent: Int): Drawable = ripple(context, flat(context, accent))

    fun secondaryButtonBackground(context: Context): Drawable = ripple(context, flat(context, CONTROL_FILL))

    /** Rounded block that groups rows inside a panel (like iOS inset grouped lists). */
    fun groupBackground(context: Context): Drawable = flat(context, GROUP_FILL, radiusDp = 18f)

    /** Ripple for a tappable row inside a group. */
    fun rowBackground(context: Context): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, flat(context, Color.WHITE, radiusDp = 12f))

    fun dot(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Styles a TextView as a flat capsule button; avoids AppCompat Button defaults (insets, shadows, caps). */
    fun styleButton(view: TextView, primary: Boolean, accent: Int) {
        val c = view.context
        view.background = if (primary) primaryButtonBackground(c, accent) else secondaryButtonBackground(c)
        view.setTextColor(if (primary) TEXT_ON_ACCENT else TEXT_PRIMARY)
        view.typeface = medium
        view.gravity = Gravity.CENTER
        view.isClickable = true
        view.isFocusable = true
        view.minHeight = dp(c, 44f).toInt()
        val h = dp(c, 18f).toInt()
        view.setPadding(h, 0, h, 0)
    }

    /** Flat color dot; the selected one gets a thin white ring around it. */
    fun swatch(context: Context, color: Int, selected: Boolean): Drawable {
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        if (!selected) return LayerDrawable(arrayOf(fill)).apply {
            val inset = dp(context, 5f).toInt()
            setLayerInset(0, inset, inset, inset, inset)
        }
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 1.6f).toInt(), TEXT_PRIMARY)
        }
        return LayerDrawable(arrayOf(ring, fill)).apply {
            val inset = dp(context, 4.5f).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
    }
}

/** A view that draws the scene, so glass panels can show a blurred copy of what's behind them. */
interface SceneSource {
    val sceneView: View
    fun renderScene(canvas: Canvas)
    fun registerBackdrop(view: View)
}

/** Draws the scene behind a [GlassPanel], blurred on Android 12+. */
class GlassBackdropView(context: Context) : View(context) {

    var source: SceneSource? = null

    private val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val ownLocation = IntArray(2)
    private val sourceLocation = IntArray(2)

    init {
        if (blurSupported) {
            val radius = Glass.dp(context, 22f)
            // A touch of extra saturation keeps colors behind the glass from going grey.
            val saturate = ColorMatrix().apply { setSaturation(1.25f) }
            setRenderEffect(
                RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate)),
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
                ),
            )
        }
    }

    // Takes no space of its own: a match_parent child measured AT_MOST would otherwise report the
    // whole available area and inflate a wrap_content panel. GlassPanel sizes it exactly instead.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun exactOrZero(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(exactOrZero(widthMeasureSpec), exactOrZero(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val src = source
        if (blurSupported && src != null) {
            getLocationInWindow(ownLocation)
            src.sceneView.getLocationInWindow(sourceLocation)
            canvas.save()
            canvas.translate(
                (sourceLocation[0] - ownLocation[0]).toFloat(),
                (sourceLocation[1] - ownLocation[1]).toFloat(),
            )
            src.renderScene(canvas)
            canvas.restore()
        } else {
            canvas.drawColor(0xD91B1F2B.toInt())
        }
    }
}

/** Frosted glass container: blurred scene + faint white gradient + thin light rim. */
class GlassPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var cornerRadiusPx: Float = Glass.dp(context, 28f)
        set(value) {
            field = value
            invalidateOutline()
            invalidate()
        }

    private val backdrop = GlassBackdropView(context)
    private val rect = RectF()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Glass.dp(context, 1f)
    }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassPanel)
        cornerRadiusPx = a.getDimension(R.styleable.GlassPanel_glassCornerRadius, cornerRadiusPx)
        a.recycle()
        addView(backdrop, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
        clipToOutline = true
        setWillNotDraw(false)
        // Consume touches on the panel's empty areas so they don't fall through and drag the scene.
        isClickable = true
    }

    fun attachScene(source: SceneSource) {
        backdrop.source = source
        source.registerBackdrop(backdrop)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        backdrop.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Keep the backdrop covering the panel even while its bounds are animated (no layout pass then).
        backdrop.layout(0, 0, w, h)
        val fh = h.toFloat()
        tintPaint.shader = LinearGradient(
            0f, 0f, 0f, fh,
            intArrayOf(0x24FFFFFF, 0x12FFFFFF, 0x0AFFFFFF),
            floatArrayOf(0f, min(1f, Glass.dp(context, 70f) / fh.coerceAtLeast(1f)), 1f),
            Shader.TileMode.CLAMP,
        )
        rimPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), fh,
            intArrayOf(0x4DFFFFFF, 0x14FFFFFF, 0x0AFFFFFF, 0x2EFFFFFF),
            floatArrayOf(0f, 0.4f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val result = super.drawChild(canvas, child, drawingTime)
        if (child === backdrop) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, tintPaint)
        }
        return result
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val half = rimPaint.strokeWidth / 2f
        rect.set(half, half, width - half, height - half)
        canvas.drawRoundRect(rect, cornerRadiusPx - half, cornerRadiusPx - half, rimPaint)
    }
}

/** Flat switch: translucent track that fills with the accent when on, white capsule thumb. */
class GlassToggle(context: Context) : View(context) {

    var accent: Int = Color.parseColor(WallpaperPrefs.DEFAULT_ACCENT_HEX)
        set(value) {
            field = value
            invalidate()
        }

    var isOn = false
        private set

    var onChange: ((Boolean) -> Unit)? = null

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val argb = ArgbEvaluator()
    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            setOn(!isOn, animate = true)
            onChange?.invoke(isOn)
        }
    }

    fun setOn(value: Boolean, animate: Boolean) {
        isOn = value
        val target = if (value) 1f else 0f
        animator?.cancel()
        if (!animate) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Glass.dp(context, 54f).toInt(), Glass.dp(context, 30f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        trackPaint.color = argb.evaluate(progress, 0x29FFFFFF, accent) as Int
        canvas.drawRoundRect(rect, h / 2, h / 2, trackPaint)

        val inset = Glass.dp(context, 3f)
        val thumbH = h - 2 * inset
        val thumbW = thumbH * 1.3f
        val left = inset + (w - 2 * inset - thumbW) * progress
        rect.set(left, inset, left + thumbW, inset + thumbH)
        thumbPaint.alpha = (0xE6 + (0xFF - 0xE6) * progress).toInt()
        canvas.drawRoundRect(rect, thumbH / 2, thumbH / 2, thumbPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isOn
    }
}

/**
 * Flat capsule segmented control: translucent track, a lighter glass pill slides to the selection.
 * With [allowNone] it can also have nothing selected (index -1), which the bottom tab bar uses
 * while the options sheet is closed; tapping the selected segment then calls [onReselect].
 */
class GlassSegmentedControl(context: Context) : View(context) {

    var options: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var heightDp = 36f
        set(value) {
            field = value
            requestLayout()
        }

    var textSizeSp = 13.5f
        set(value) {
            field = value
            invalidate()
        }

    var allowNone = false

    var selectedIndex = 0
        private set

    var onSelect: ((Int) -> Unit)? = null
    var onReselect: ((Int) -> Unit)? = null

    private var pillPosition = 0f
    private var pillAlpha = 1f
    private var animator: ValueAnimator? = null
    private val argb = ArgbEvaluator()
    private val inset = Glass.dp(context, 3f)
    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.CONTROL_FILL }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Glass.dp(context, 0.8f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Glass.medium
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        isFocusable = true
    }

    fun select(index: Int, animate: Boolean) {
        if (options.isEmpty()) return
        val target = if (allowNone && index < 0) -1 else index.coerceIn(0, options.size - 1)
        val from = selectedIndex
        selectedIndex = target
        animator?.cancel()
        // Appearing from "none": start at the target and fade in instead of sliding across.
        if (from < 0 && target >= 0) pillPosition = target.toFloat()
        val toPos = if (target >= 0) target.toFloat() else pillPosition
        val toAlpha = if (target >= 0) 1f else 0f
        if (!animate) {
            pillPosition = toPos
            pillAlpha = toAlpha
            invalidate()
            return
        }
        val startPos = pillPosition
        val startAlpha = pillAlpha
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                pillPosition = startPos + (toPos - startPos) * t
                pillAlpha = startAlpha + (toAlpha - startAlpha) * t
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Glass.dp(context, heightDp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val n = options.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, trackPaint)

        val segW = (w - 2 * inset) / n
        if (pillAlpha > 0.01f) {
            val pillLeft = inset + pillPosition * segW
            rect.set(pillLeft, inset, pillLeft + segW, h - inset)
            val pr = (h - 2 * inset) / 2
            pillPaint.color = Color.argb((0x2E * pillAlpha).toInt(), 255, 255, 255)
            pillRim.color = Color.argb((0x24 * pillAlpha).toInt(), 255, 255, 255)
            canvas.drawRoundRect(rect, pr, pr, pillPaint)
            canvas.drawRoundRect(rect, pr, pr, pillRim)
        }

        val baseTextSize = textSizeSp * resources.displayMetrics.scaledDensity
        for ((i, label) in options.withIndex()) {
            textPaint.textSize = baseTextSize
            val maxW = segW - Glass.dp(context, 8f)
            val measured = textPaint.measureText(label)
            if (measured > maxW) textPaint.textSize = baseTextSize * maxW / measured
            val closeness = (1f - abs(i - pillPosition)).coerceIn(0f, 1f) * pillAlpha
            textPaint.color = argb.evaluate(closeness, Glass.TEXT_SECONDARY, Glass.TEXT_PRIMARY) as Int
            val cx = inset + segW * (i + 0.5f)
            val cy = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, cx, cy, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (options.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val segW = (width - 2 * inset) / options.size
                val index = ((event.x - inset) / segW).toInt().coerceIn(0, options.size - 1)
                performClick()
                if (index != selectedIndex) {
                    select(index, animate = true)
                    onSelect?.invoke(index)
                } else {
                    onReselect?.invoke(index)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}

/**
 * Flat slider: thin translucent track filled with the accent up to a white capsule thumb.
 * [onChange] fires while dragging (for live preview), [onRelease] once the finger lifts.
 */
class GlassSlider(context: Context) : View(context) {

    var accent: Int = Color.parseColor(WallpaperPrefs.DEFAULT_ACCENT_HEX)
        set(value) {
            field = value
            invalidate()
        }

    /** 0..1 */
    var value = 0.5f
        private set

    var onChange: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null

    /** True when a finger starts dragging the slider, false when it lifts. */
    var onTrackingChanged: ((Boolean) -> Unit)? = null

    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x29FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbW = Glass.dp(context, 34f)
    private val thumbH = Glass.dp(context, 22f)
    private val trackH = Glass.dp(context, 6f)

    init {
        isClickable = true
        isFocusable = true
    }

    fun updateValue(v: Float) {
        value = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Glass.dp(context, 36f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cy = height / 2f
        val travel = w - thumbW
        val thumbLeft = travel * value
        rect.set(0f, cy - trackH / 2, w, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, trackPaint)
        fillPaint.color = accent
        rect.set(0f, cy - trackH / 2, thumbLeft + thumbW / 2, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, fillPaint)
        rect.set(thumbLeft, cy - thumbH / 2, thumbLeft + thumbW, cy + thumbH / 2)
        canvas.drawRoundRect(rect, thumbH / 2, thumbH / 2, thumbPaint)
    }

    private fun valueAt(x: Float): Float = ((x - thumbW / 2) / (width - thumbW).coerceAtLeast(1f)).coerceIn(0f, 1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep the surrounding ScrollView from stealing horizontal drags.
                parent?.requestDisallowInterceptTouchEvent(true)
                onTrackingChanged?.invoke(true)
                updateValue(valueAt(event.x))
                onChange?.invoke(value)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateValue(valueAt(event.x))
                onChange?.invoke(value)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                onRelease?.invoke(value)
                onTrackingChanged?.invoke(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, value)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val delta = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 0.05f
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -0.05f
            else -> return super.performAccessibilityAction(action, arguments)
        }
        updateValue(value + delta)
        onChange?.invoke(value)
        onRelease?.invoke(value)
        return true
    }
}

/** ScrollView with a height cap (ScrollView has no working `maxHeight`). */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else min(size, maxHeightPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}
