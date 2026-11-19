package dev.lucudar.polka

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser

/*
 * Главный экран на обычных View. Замер на стике: View-лаунчер рисует кадр за 9 мс против 21 мс
 * у Compose-версии, видеочипу нужно 6 мс. Здесь всё общее для View-части: размеры, шрифты,
 * значки путями Material, стекло шапки и капсула-кнопка.
 */

fun Context.px(dp: Float): Float = dp * resources.displayMetrics.density
fun Context.pxi(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
fun Context.px(dp: Dp): Float = px(dp.value)
fun Context.pxi(dp: Dp): Int = pxi(dp.value)

val Color.argb: Int get() = toArgb()

/** Шрифт Onest тех же трёх начертаний, что и в Compose-части. */
object Fonts {
    private var regular: Typeface? = null
    private var medium: Typeface? = null
    private var semibold: Typeface? = null

    fun regular(context: Context): Typeface =
        regular ?: (ResourcesCompat.getFont(context, R.font.onest_regular) ?: Typeface.DEFAULT).also { regular = it }

    fun medium(context: Context): Typeface =
        medium ?: (ResourcesCompat.getFont(context, R.font.onest_medium) ?: Typeface.DEFAULT).also { medium = it }

    fun semibold(context: Context): Typeface =
        semibold ?: (ResourcesCompat.getFont(context, R.font.onest_semibold) ?: Typeface.DEFAULT_BOLD).also { semibold = it }
}

/** Текст в стиле Typo: размер в sp, без лишних полей шрифта, в одну строку с многоточием. */
fun TextView.polkaText(sizeSp: Float, face: Typeface, color: Int, lines: Int = 1) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    typeface = face
    setTextColor(color)
    includeFontPadding = false
    maxLines = lines
    if (lines == 1) isSingleLine = true
    ellipsize = TextUtils.TruncateAt.END
}

/** Тень текста как legibility в Typo. Только в полном режиме: в лёгком её растеризует процессор. */
fun TextView.legibility(on: Boolean) {
    if (on) setShadowLayer(10f, 0f, 2f, 0x8C000000.toInt()) else setShadowLayer(0f, 0f, 0f, 0)
}

object Glyphs {
    const val SETTINGS = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
        "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96" +
        "c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84" +
        "c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33" +
        "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58" +
        "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61" +
        "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54" +
        "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54" +
        "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32" +
        "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94z" +
        "M12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"
    const val WIFI = "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9z" +
        "M9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0z" +
        "M5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z"
    const val APPS = "M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4z" +
        "M16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z"
    const val DOWNLOAD = "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"
    const val ADD = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
}

/** Значок Material путём 24 x 24, перекрашивается без пересоздания. */
class PathIcon(pathData: String, private val sizePx: Int) : Drawable() {
    private val path = PathParser.createPathFromPathData(pathData)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Своё имя для JVM: Drawable уже держит setTint(int) под ColorStateList-тонировку.
    var tint: Int
        @JvmName("getInk")
        get() = paint.color
        @JvmName("setInk")
        set(value) {
            if (paint.color != value) {
                paint.color = value
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = b.width() / 24f
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(s, s)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Стекло шапки: размытая копия обоев (480 x 270) натянута на экран и показана только под капсулой,
 * за одну операцию через BitmapShader. Поверх тон, блик и тонкая кромка. Позицию на экране
 * хозяин задаёт сам: шапка не двигается, считать её на каждом кадре незачем.
 */
class GlassDrawable(private val context: Context) : Drawable() {
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(1f)
        color = Palette.hairline.argb
    }
    private val rect = RectF()
    private var blurred: Bitmap? = null
    private var screenW = 0
    private var screenH = 0
    private var originX = 0f
    private var originY = 0f
    var radius = 0f
    var solid = false
    var lite = false

    var tint: Int
        @JvmName("getTone")
        get() = tintPaint.color
        @JvmName("setTone")
        set(value) {
            tintPaint.color = value
            invalidateSelf()
        }

    fun setBackdrop(bitmap: Bitmap?, width: Int, height: Int, x: Float, y: Float) {
        blurred = bitmap
        screenW = width
        screenH = height
        originX = x
        originY = y
        rebuildShader()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        glowPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.top + bounds.height() * 0.6f,
            0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        rebuildShader()
    }

    private fun rebuildShader() {
        val bmp = blurred
        if (bmp == null || screenW == 0 || screenH == 0) {
            backPaint.shader = null
            return
        }
        val m = Matrix().apply {
            setScale(screenW / bmp.width.toFloat(), screenH / bmp.height.toFloat())
            postTranslate(-originX + bounds.left, -originY + bounds.top)
        }
        backPaint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val r = if (radius > 0f) radius else rect.height() / 2f
        if (!solid && backPaint.shader != null) canvas.drawRoundRect(rect, r, r, backPaint)
        canvas.drawRoundRect(rect, r, r, tintPaint)
        if (!lite) canvas.drawRoundRect(rect, r, r, glowPaint)
        val inset = edgePaint.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, r - inset, r - inset, edgePaint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Заливка капсулы-кнопки: цвет меняется аниматором, перерисовывается только сама кнопка. */
class PillDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var color: Int
        get() = paint.color
        set(value) {
            if (paint.color != value) {
                paint.color = value
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        if (paint.alpha == 0) return
        rect.set(bounds)
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Кнопка шапки: капсула 48 dp. В фокусе залита акцентом с тёмным значком, «активная»
 * (открыта её панель или есть обновление) подсвечена акцентом на 22 %.
 */
class BarPillView(context: Context, glyph: String) : LinearLayout(context) {
    private val pill = PillDrawable()
    private val icon = PathIcon(glyph, context.pxi(24f))
    private val iconView = ImageView(context)
    val label = TextView(context)
    private var animator: ValueAnimator? = null
    private var fillNow = 0
    private var inkNow = Palette.text.argb
    var accent = Accent.Amber.color.argb
    var onAccent = Accent.Amber.on.argb
    var calm = false
    var onFocused: (() -> Unit)? = null

    var active = false
        set(value) {
            if (field != value) {
                field = value
                applyState(animate = true)
            }
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = context.pxi(48f)
        val pad = context.pxi(12f)
        setPadding(pad, 0, pad, 0)
        background = pill
        isFocusable = true
        isClickable = true
        iconView.setImageDrawable(icon)
        addView(iconView, LayoutParams(context.pxi(24f), context.pxi(24f)))
        label.polkaText(15f, Fonts.medium(context), Palette.text.argb)
        label.visibility = GONE
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.pxi(8f) })
        applyState(animate = false)
    }

    fun setIconSize(dp: Float) {
        iconView.layoutParams = LayoutParams(context.pxi(dp), context.pxi(dp))
    }

    fun showIcon(show: Boolean) {
        iconView.visibility = if (show) VISIBLE else GONE
        (label.layoutParams as LayoutParams).marginStart = if (show) context.pxi(8f) else 0
    }

    fun setText(text: String?) {
        label.text = text
        label.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyState(animate = true)
        if (gainFocus) onFocused?.invoke()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.94f else 1f
        if (calm) {
            scaleX = s
            scaleY = s
        } else {
            animate().scaleX(s).scaleY(s).setDuration(120).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun applyState(animate: Boolean) {
        val fill = when {
            isFocused -> accent
            active -> ColorUtils.setAlphaComponent(accent, (0.22f * 255).toInt())
            else -> 0
        }
        val ink = when {
            isFocused -> onAccent
            active -> accent
            else -> Palette.text.argb
        }
        animator?.cancel()
        if (!animate || calm) {
            setColors(fill, ink)
            return
        }
        val fromFill = fillNow
        val fromInk = inkNow
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                setColors(ColorUtils.blendARGB(fromFill, fill, t), ColorUtils.blendARGB(fromInk, ink, t))
            }
            start()
        }
    }

    private fun setColors(fill: Int, ink: Int) {
        fillNow = fill
        inkNow = ink
        pill.color = fill
        icon.tint = ink
        label.setTextColor(ink)
    }
}

fun isSelectKeyCode(code: Int): Boolean =
    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

fun View.visibleIf(show: Boolean) {
    visibility = if (show) View.VISIBLE else View.GONE
}
