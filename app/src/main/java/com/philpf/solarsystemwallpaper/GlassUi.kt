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
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * "Liquid Glass"-style controls. Android has no system glass material, so it is built here:
 *
 * - [GlassPanel] re-draws the live scene behind itself (through [GlassBackdropView], offset to
 *   line up with the full-screen preview) and blurs + saturates it with a [RenderEffect] on
 *   Android 12+, so panels are genuinely frosted over the moving planets. Older versions fall
 *   back to a tinted translucent fill.
 * - A specular gradient edge and a soft top sheen give the rim-lit glass look.
 * - [GlassToggle], [GlassSegmentedControl] and the capsule buttons from [Glass] follow the same
 *   rounded, translucent language, tinted with the app's accent color.
 */
object Glass {
    const val TEXT_PRIMARY = 0xF2FFFFFF.toInt()
    const val TEXT_SECONDARY = 0x99FFFFFF.toInt()
    const val TEXT_ON_ACCENT = 0xFF14161E.toInt()
    const val HAIRLINE = 0x1AFFFFFF

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    /** Mixes [color] toward white by [amount] (0..1), keeping its alpha. */
    fun lighten(color: Int, amount: Float): Int {
        fun mix(c: Int) = (c + (255 - c) * amount).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)))
    }

    private fun capsule(context: Context, top: Int, bottom: Int, stroke: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(context, 100f)
            setStroke(dp(context, 1f).toInt(), stroke)
        }

    private fun ripple(context: Context, content: Drawable): Drawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(context, 100f)
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask)
    }

    fun glassButtonBackground(context: Context): Drawable =
        ripple(context, capsule(context, 0x2EFFFFFF, 0x12FFFFFF, 0x33FFFFFF))

    fun accentButtonBackground(context: Context, accent: Int): Drawable =
        ripple(context, capsule(context, lighten(accent, 0.30f), accent, 0x59FFFFFF))

    /** Styles a TextView as a capsule button; avoids AppCompat Button defaults (insets, shadows, caps). */
    fun styleButton(view: TextView, primary: Boolean, accent: Int) {
        val c = view.context
        view.background = if (primary) accentButtonBackground(c, accent) else glassButtonBackground(c)
        view.setTextColor(if (primary) TEXT_ON_ACCENT else TEXT_PRIMARY)
        view.typeface = medium
        view.gravity = Gravity.CENTER
        view.isClickable = true
        view.isFocusable = true
        view.minHeight = dp(c, 44f).toInt()
        val h = dp(c, 18f).toInt()
        view.setPadding(h, 0, h, 0)
    }

    /** Color swatch: a glossy sphere, ringed when selected. */
    fun swatch(context: Context, color: Int, selected: Boolean): Drawable {
        val fill = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(lighten(color, 0.35f), color),
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(context, 1f).toInt(), 0x4DFFFFFF)
        }
        if (!selected) return LayerDrawable(arrayOf(fill)).apply {
            val inset = dp(context, 3f).toInt()
            setLayerInset(0, inset, inset, inset, inset)
        }
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 2f).toInt(), TEXT_PRIMARY)
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

/** Draws the scene behind a [GlassPanel], blurred on Android 12+, plus the glass tint. */
class GlassBackdropView(context: Context) : View(context) {

    var source: SceneSource? = null

    private val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val ownLocation = IntArray(2)
    private val sourceLocation = IntArray(2)

    init {
        if (blurSupported) {
            val radius = Glass.dp(context, 26f)
            val saturate = ColorMatrix().apply { setSaturation(1.6f) }
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
            canvas.drawColor(0x47141826)
        } else {
            canvas.drawColor(0xC21A1E2B.toInt())
        }
    }
}

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
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Glass.dp(context, 1f)
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        borderPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0x80FFFFFF.toInt(), 0x1AFFFFFF, 0x0DFFFFFF, 0x4DFFFFFF),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, min(h.toFloat(), Glass.dp(context, 90f)),
            0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val result = super.drawChild(canvas, child, drawingTime)
        if (child === backdrop) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, sheenPaint)
        }
        return result
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val half = borderPaint.strokeWidth / 2f
        rect.set(half, half, width - half, height - half)
        canvas.drawRoundRect(rect, cornerRadiusPx - half, cornerRadiusPx - half, borderPaint)
    }
}

/** iOS-style switch. */
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
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Glass.dp(context, 1f)
        color = 0x2EFFFFFF
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(Glass.dp(context, 3f), 0f, Glass.dp(context, 1f), 0x40000000)
    }

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
        setMeasuredDimension(Glass.dp(context, 51f).toInt(), Glass.dp(context, 31f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        trackPaint.color = argb.evaluate(progress, 0x29FFFFFF, accent) as Int
        canvas.drawRoundRect(rect, r, r, trackPaint)
        if (progress < 0.99f) canvas.drawRoundRect(rect, r, r, trackStroke)
        val thumbR = r - Glass.dp(context, 2f)
        val cx = r + (w - 2 * r) * progress
        canvas.drawCircle(cx, r, thumbR, thumbPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isOn
    }
}

/** Capsule segmented control with a sliding accent pill. */
class GlassSegmentedControl(context: Context) : View(context) {

    var options: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var accent: Int = Color.parseColor(WallpaperPrefs.DEFAULT_ACCENT_HEX)
        set(value) {
            field = value
            invalidate()
        }

    var selectedIndex = 0
        private set

    var onSelect: ((Int) -> Unit)? = null

    private var pillPosition = 0f
    private var animator: ValueAnimator? = null
    private val argb = ArgbEvaluator()
    private val inset = Glass.dp(context, 3f)
    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Glass.dp(context, 1f)
        color = 0x24FFFFFF
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Glass.medium
        textAlign = Paint.Align.CENTER
    }
    private val baseTextSize = 13f * context.resources.displayMetrics.scaledDensity

    init {
        isClickable = true
        isFocusable = true
    }

    fun select(index: Int, animate: Boolean) {
        if (options.isEmpty()) return
        selectedIndex = index.coerceIn(0, options.size - 1)
        animator?.cancel()
        if (!animate) {
            pillPosition = selectedIndex.toFloat()
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(pillPosition, selectedIndex.toFloat()).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                pillPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Glass.dp(context, 36f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val n = options.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, trackPaint)
        canvas.drawRoundRect(rect, h / 2, h / 2, trackStroke)

        val segW = (w - 2 * inset) / n
        val pillLeft = inset + pillPosition * segW
        rect.set(pillLeft, inset, pillLeft + segW, h - inset)
        pillPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            Glass.lighten(accent, 0.30f), accent, Shader.TileMode.CLAMP,
        )
        val pr = (h - 2 * inset) / 2
        canvas.drawRoundRect(rect, pr, pr, pillPaint)

        for ((i, label) in options.withIndex()) {
            textPaint.textSize = baseTextSize
            val maxW = segW - Glass.dp(context, 8f)
            val measured = textPaint.measureText(label)
            if (measured > maxW) textPaint.textSize = baseTextSize * maxW / measured
            val closeness = (1f - abs(i - pillPosition)).coerceIn(0f, 1f)
            textPaint.color = argb.evaluate(closeness, Glass.TEXT_PRIMARY, Glass.TEXT_ON_ACCENT) as Int
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
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
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
