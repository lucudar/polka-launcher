package dev.lucudar.polka

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout as TextLayout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** Внешний вид плиток, общий для всех рядов. Меняется в настройках, ряды перерисовываются. */
data class TileStyle(
    val accent: Int,
    val onAccent: Int,
    val labels: TileLabels,
    val lite: Boolean,
    val calm: Boolean,
)

/** Что лежит в ряду: приложение, «+» или подсказка в пустом ряду. */
sealed class ShelfItem(val id: String) {
    class App(val app: AppEntry) : ShelfItem(app.pkg)
    object Add : ShelfItem("+")
    class Hint(val text: String) : ShelfItem("hint")
}

/** Действия ряда: хозяин (главный экран) решает, что делать. */
interface ShelfHost {
    val moving: String?
    fun onTileFocused(key: String)
    fun onTileClick(prefix: String, app: AppEntry)
    fun onTileMenu(prefix: String, app: AppEntry, anchor: View)
    fun onTileMove(prefix: String, app: AppEntry, delta: Int)
    fun onMoveDone()
    fun onAdd(prefix: String)
}

/**
 * Плитка: один View, рисует себя сам. Баннер скруглённым прямоугольником через BitmapShader
 * (масштаб как ContentScale.Crop), без маски и слоя. Фокус: масштаб через свойства RenderNode
 * (их меняет RenderThread, интерфейс не пересобирается) и обводка с прозрачностью по аниматору.
 */
@SuppressLint("ViewConstructor")
class TileView(context: Context) : View(context) {
    private val radius = context.px(Radius.tile)
    private val ringW = context.px(2f)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringW
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(1.5f)
    }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium(context)
        textSize = context.px(15f) * resources.configuration.fontScale
        color = Palette.text.argb
    }
    private val rect = RectF()
    private val iconRect = Rect()
    private var app: AppEntry? = null
    private var banner: Bitmap? = null
    private var icon: Bitmap? = null
    private var textLayout: StaticLayout? = null
    private var isAdd = false
    private var style = TileStyle(0, 0, TileLabels.Focused, lite = true, calm = false)
    private var plus: PathIcon? = null
    private var glow = 0f
    private var glowAnim: ValueAnimator? = null
    var moving = false
        set(value) {
            if (field != value) {
                field = value
                applyFocus(animate = true)
            }
        }

    init {
        isFocusable = true
        isClickable = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    fun bindApp(entry: AppEntry, tileStyle: TileStyle) {
        isAdd = false
        app = entry
        style = tileStyle
        banner = entry.banner?.asAndroidBitmap()
        icon = entry.icon?.asAndroidBitmap()
        textLayout = null
        contentDescription = entry.label
        rebuildFace()
        applyFocus(animate = false)
        invalidate()
    }

    fun bindAdd(tileStyle: TileStyle) {
        isAdd = true
        app = null
        style = tileStyle
        banner = null
        icon = null
        textLayout = null
        contentDescription = "Добавить приложения"
        if (plus == null) plus = PathIcon(Glyphs.ADD, context.pxi(32f))
        facePaint.shader = null
        applyFocus(animate = false)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildFace()
        textLayout = null
    }

    private fun rebuildFace() {
        val bmp = banner
        if (bmp == null || width == 0 || height == 0) {
            facePaint.shader = null
            return
        }
        val s = maxOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((width - bmp.width * s) / 2f, (height - bmp.height * s) / 2f)
        }
        facePaint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        when {
            isAdd -> {
                fillPaint.color = ColorUtils.blendARGB(
                    ColorUtils.setAlphaComponent(Palette.tile.argb, (0.45f * 255).toInt()),
                    style.accent,
                    glow,
                )
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                if (glow < 0.99f) {
                    // Сплошная кромка, не пунктир: пунктир строился процессором на каждом кадре.
                    edgePaint.color = ColorUtils.setAlphaComponent(Palette.textSecondary.argb, ((1f - glow) * 0.55f * 255).toInt())
                    val i = edgePaint.strokeWidth / 2f
                    rect.inset(i, i)
                    canvas.drawRoundRect(rect, radius - i, radius - i, edgePaint)
                    rect.set(0f, 0f, w, h)
                }
                plus?.let { p ->
                    val s = context.pxi(32f)
                    p.tint = ColorUtils.blendARGB(Palette.textSecondary.argb, style.onAccent, glow)
                    p.setBounds(((w - s) / 2).toInt(), ((h - s) / 2).toInt(), ((w + s) / 2).toInt(), ((h + s) / 2).toInt())
                    p.draw(canvas)
                }
            }
            facePaint.shader != null -> canvas.drawRoundRect(rect, radius, radius, facePaint)
            else -> {
                fillPaint.color = Palette.tile.argb
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                drawIconFace(canvas, w, h)
            }
        }
        if (!isAdd && glow > 0.01f) {
            ringPaint.color = ColorUtils.setAlphaComponent(style.accent, (glow * 255).toInt())
            val i = ringW / 2f
            rect.set(i, i, w - i, h - i)
            canvas.drawRoundRect(rect, radius - i, radius - i, ringPaint)
        }
    }

    /** Без ТВ-баннера: при подписях под плиткой только иконка, иначе иконка и имя на плитке. */
    private fun drawIconFace(canvas: Canvas, w: Float, h: Float) {
        val bmp = icon
        val entry = app ?: return
        if (style.labels != TileLabels.Off) {
            if (bmp != null) {
                val s = (h * 0.5f).toInt()
                iconRect.set(((w - s) / 2).toInt(), ((h - s) / 2).toInt(), ((w + s) / 2).toInt(), ((h + s) / 2).toInt())
                canvas.drawBitmap(bmp, null, iconRect, iconPaint)
            }
            return
        }
        val pad = context.px(Space.m)
        var x = pad
        if (bmp != null) {
            val s = (h * 0.44f).toInt()
            iconRect.set(x.toInt(), ((h - s) / 2).toInt(), x.toInt() + s, ((h + s) / 2).toInt())
            canvas.drawBitmap(bmp, null, iconRect, iconPaint)
            x += s + context.px(Space.s)
        }
        val avail = (w - x - pad).toInt().coerceAtLeast(1)
        val layout = textLayout?.takeIf { it.width == avail } ?: StaticLayout.Builder
            .obtain(entry.label, 0, entry.label.length, textPaint, avail)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(TextLayout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
            .also { textLayout = it }
        val save = canvas.save()
        canvas.translate(x, (h - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyFocus(animate = true)
    }

    private var pressedNow = false

    fun setPressedLook(pressed: Boolean) {
        if (pressedNow == pressed) return
        pressedNow = pressed
        applyFocus(animate = true)
    }

    fun applyFocus(animate: Boolean) {
        val on = isFocused || moving
        val target = when {
            moving -> 1.14f
            pressedNow -> 1.04f
            on -> 1.1f
            else -> 1f
        }
        val glowTarget = if (on) 1f else 0f
        val lift = if (style.lite) 0f else context.px(if (moving) 28f else 18f)
        if (!animate || style.calm) {
            animate().cancel()
            scaleX = target
            scaleY = target
            setGlow(glowTarget, lift)
            return
        }
        // Масштаб: свойство RenderNode, лёгкий перелёт в фокусе, без него при отпускании.
        // Лёгкий режим: короче и без перелёта, на каждое нажатие вдвое меньше кадров анимации.
        val bounce = on && !pressedNow && !style.lite
        animate().scaleX(target).scaleY(target)
            .setDuration(if (style.lite) 120 else if (bounce) 220 else 140)
            .setInterpolator(if (bounce) OVERSHOOT else DECELERATE)
            .start()
        glowAnim?.cancel()
        val from = glow
        if (from == glowTarget) {
            setGlow(glowTarget, lift)
            return
        }
        glowAnim = ValueAnimator.ofFloat(from, glowTarget).apply {
            duration = if (style.lite) 110 else 160
            interpolator = DECELERATE
            addUpdateListener { setGlow(it.animatedValue as Float, lift) }
            start()
        }
    }

    private fun setGlow(value: Float, lift: Float) {
        glow = value
        elevation = value * lift
        invalidate()
    }

    private companion object {
        val OVERSHOOT: Interpolator = OvershootInterpolator(1.4f)
        val DECELERATE: Interpolator = DecelerateInterpolator(1.5f)
    }
}

/** Ячейка ряда: плитка и подпись под ней. Место под подпись занято всегда: ряд не прыгает. */
@SuppressLint("ViewConstructor")
class TileCell(context: Context) : LinearLayout(context) {
    val tile = TileView(context)
    val label = TextView(context)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(tile)
        label.polkaText(15f, Fonts.medium(context), Palette.text.argb)
        label.gravity = Gravity.CENTER_HORIZONTAL
        addView(label)
    }

    fun setSize(w: Int, h: Int, labels: TileLabels) {
        tile.layoutParams = LayoutParams(w, h)
        val showLabel = labels != TileLabels.Off
        label.visibleIf(showLabel)
        label.layoutParams = LayoutParams(w, context.pxi(20f)).apply { topMargin = context.pxi(8f) }
    }
}

/** Подсказка в пустом ряду: тёмная подложка, текст читается на любых обоях. */
@SuppressLint("ViewConstructor")
class HintCell(context: Context) : FrameLayout(context) {
    val text = TextView(context)

    init {
        val pill = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = context.px(Radius.tile)
            setColor(Palette.hintFill.argb)
            setStroke(context.pxi(1f), Palette.hairline.argb)
        }
        text.background = pill
        text.polkaText(18f, Fonts.medium(context), Palette.text.argb, lines = 2)
        text.maxWidth = context.pxi(620f)
        val ph = context.pxi(Space.xl)
        val pv = context.pxi(Space.l)
        text.setPadding(ph, pv, ph, pv)
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    }
}

private class Holder(val view: View) : RecyclerView.ViewHolder(view)

/**
 * Адаптер ряда. Список сравнивается DiffUtil в фоне: перестановка и добавление плиток
 * анимируются, а фокус остаётся на той же плитке, потому что её View просто переезжает.
 */
class ShelfAdapter(
    private val prefix: String,
    private val host: ShelfHost,
) : ListAdapter<ShelfItem, RecyclerView.ViewHolder>(Diff) {
    var tileW = 0
    var tileH = 0
    var style = TileStyle(0, 0, TileLabels.Focused, lite = true, calm = false)
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    init {
        setHasStableIds(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun resize(w: Int, h: Int) {
        if (w != tileW || h != tileH) {
            tileW = w
            tileH = h
            notifyDataSetChanged()
        }
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ShelfItem.App -> 0
        ShelfItem.Add -> 1
        is ShelfItem.Hint -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view: View = if (viewType == 2) HintCell(parent.context) else TileCell(parent.context)
        view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ShelfItem.Hint -> {
                val cell = holder.itemView as HintCell
                cell.text.text = item.text
                cell.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, tileH)
            }
            ShelfItem.Add -> bindAdd(holder.itemView as TileCell)
            is ShelfItem.App -> bindApp(holder.itemView as TileCell, item.app)
        }
    }

    private fun bindApp(cell: TileCell, app: AppEntry) {
        val key = "$prefix:${app.pkg}"
        cell.setSize(tileW, tileH, style.labels)
        cell.tile.bindApp(app, style)
        cell.tile.moving = host.moving == key
        cell.label.text = app.label
        cell.label.legibility(!style.lite)
        cell.label.alpha = labelAlpha(cell.tile.isFocused)
        cell.tile.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
            if (has) host.onTileFocused(key)
            fadeLabel(cell, has || cell.tile.moving)
        }
        cell.tile.setOnKeyListener { _, code, event -> handleKey(cell, app, key, code, event) }
    }

    private fun bindAdd(cell: TileCell) {
        val key = "$prefix:+"
        cell.setSize(tileW, tileH, style.labels)
        cell.tile.bindAdd(style)
        cell.tile.moving = false
        cell.label.text = "Добавить"
        cell.label.legibility(!style.lite)
        cell.label.alpha = labelAlpha(cell.tile.isFocused)
        cell.tile.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
            if (has) host.onTileFocused(key)
            fadeLabel(cell, has)
        }
        cell.tile.setOnKeyListener { _, code, event ->
            if (!isSelectKeyCode(code)) return@setOnKeyListener false
            when (event.action) {
                KeyEvent.ACTION_DOWN -> cell.tile.setPressedLook(true)
                KeyEvent.ACTION_UP -> {
                    cell.tile.setPressedLook(false)
                    if (!event.isCanceled) host.onAdd(prefix)
                }
            }
            true
        }
    }

    private fun labelAlpha(active: Boolean): Float = when (style.labels) {
        TileLabels.Always -> 1f
        TileLabels.Focused -> if (active) 1f else 0f
        TileLabels.Off -> 0f
    }

    private fun fadeLabel(cell: TileCell, active: Boolean) {
        val target = labelAlpha(active)
        if (style.calm || style.lite) cell.label.alpha = target else cell.label.animate().alpha(target).setDuration(160).start()
    }

    /** OK: короткое нажатие открывает, долгое (0,5 с) меню. В режиме перестановки стрелки двигают плитку. */
    private fun handleKey(cell: TileCell, app: AppEntry, key: String, code: Int, event: KeyEvent): Boolean {
        val moving = host.moving == key
        if (moving) {
            val arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> host.onTileMove(prefix, app, -1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> host.onTileMove(prefix, app, 1)
                }
            } else if (event.action == KeyEvent.ACTION_UP && isSelectKeyCode(code)) {
                host.onMoveDone()
            }
            return arrow || isSelectKeyCode(code)
        }
        if (!isSelectKeyCode(code)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    cell.tile.setPressedLook(true)
                    longFired = false
                } else if (!longFired && event.eventTime - event.downTime >= LONG_PRESS_MS) {
                    longFired = true
                    cell.tile.setPressedLook(false)
                    host.onTileMenu(prefix, app, cell.tile)
                }
            }
            KeyEvent.ACTION_UP -> {
                cell.tile.setPressedLook(false)
                if (!longFired && !event.isCanceled) {
                    if (event.eventTime - event.downTime >= LONG_PRESS_MS) host.onTileMenu(prefix, app, cell.tile) else host.onTileClick(prefix, app)
                }
                longFired = false
            }
        }
        return true
    }

    private var longFired = false

    private object Diff : DiffUtil.ItemCallback<ShelfItem>() {
        override fun areItemsTheSame(oldItem: ShelfItem, newItem: ShelfItem): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ShelfItem, newItem: ShelfItem): Boolean = when {
            oldItem is ShelfItem.App && newItem is ShelfItem.App -> oldItem.app == newItem.app
            oldItem is ShelfItem.Hint && newItem is ShelfItem.Hint -> oldItem.text == newItem.text
            else -> oldItem === newItem
        }
    }

    private companion object {
        const val LONG_PRESS_MS = 500L
    }
}

/**
 * Прокрутка ряда к фокусу. Лёгкий режим: страницами. Пока плитка видна целиком, ряд стоит;
 * у края перелистывается так, чтобы она встала в первый слот. Полный: фокус всегда в первом слоте.
 */
class ShelfLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {
    var paging = true
    var calm = false
    private val ease: Interpolator = DecelerateInterpolator(1.8f)

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        val left = getDecoratedLeft(child)
        val right = getDecoratedRight(child)
        val lead = paddingLeft
        val trail = width - paddingRight
        val dx = when {
            !paging -> left - lead
            left >= lead && right <= trail -> 0
            right > trail -> left - lead
            else -> right - trail
        }
        if (dx == 0) return false
        if (immediate || calm) parent.scrollBy(dx, 0) else parent.smoothScrollBy(dx, 0, ease, if (paging) 240 else 220)
        return true
    }

    // Всегда пересчитывать прокрутку, даже если ряд ещё едет: быстрые нажатия не теряются.
    override fun onRequestChildFocus(parent: RecyclerView, state: RecyclerView.State, child: View, focused: View?): Boolean = false
}

/** Отступ 16 dp между плитками ряда. */
class TileGap(private val gap: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val pos = parent.getChildAdapterPosition(view)
        val count = parent.adapter?.itemCount ?: 0
        outRect.set(0, 0, if (pos in 0 until count - 1) gap else 0, 0)
    }
}
