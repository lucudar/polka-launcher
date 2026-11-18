package dev.lucudar.polka

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Заставка Полки: обои из настроек, крупные часы и дата. Фон медленно приближается и отдаляется,
 * блок с часами раз в минуту чуть смещается, чтобы на экране ничего не выгорало.
 * При «Без анимаций» фон стоит, а часы переставляются без плавности.
 */
class PolkaDream : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: LauncherSettings
    private var image: ImageView? = null
    private var block: View? = null
    private var clock: TextView? = null
    private var date: TextView? = null
    private var zoom: ObjectAnimator? = null

    private val tick = object : Runnable {
        override fun run() {
            updateText()
            drift()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        settings = LauncherSettings(this)
        setContentView(buildContent())
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        updateText()
        handler.postDelayed(tick, 60_000L - System.currentTimeMillis() % 60_000L)
        if (!settings.reduceMotion) startZoom()
    }

    override fun onDreamingStopped() {
        handler.removeCallbacksAndMessages(null)
        zoom?.cancel()
        zoom = null
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        zoom?.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildContent(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(settings.wallpaper.full)
            alpha = 1f - settings.brightness.dim
        }
        root.addView(image, FrameLayout.LayoutParams(match, match))

        // Затемнение книзу сильнее, чем на главном: часы крупные и висят дольше.
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(190, 0, 0, 0)),
            )
        }
        root.addView(scrim, FrameLayout.LayoutParams(match, match))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            typeface = ResourcesCompat.getFont(this@PolkaDream, R.font.onest_semibold)
            textSize = 112f
            setTextColor(Color.rgb(0xF3, 0xF5, 0xF7))
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            setShadowLayer(dp(16).toFloat(), 0f, dp(2).toFloat(), Color.argb(110, 0, 0, 0))
        }
        date = TextView(this).apply {
            typeface = ResourcesCompat.getFont(this@PolkaDream, R.font.onest_regular)
            textSize = 28f
            setTextColor(Color.rgb(0xC3, 0xCA, 0xD2))
            setShadowLayer(dp(12).toFloat(), 0f, dp(2).toFloat(), Color.argb(110, 0, 0, 0))
            setPadding(dp(4), dp(8), 0, 0)
        }
        column.addView(clock, LinearLayout.LayoutParams(wrap, wrap))
        column.addView(date, LinearLayout.LayoutParams(wrap, wrap))
        block = column
        root.addView(
            column,
            FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(72), 0, 0, dp(64))
            },
        )
        return root
    }

    private fun updateText() {
        val now = LocalDateTime.now()
        clock?.text = now.clockText(this, settings.clock)
        date?.text = if (settings.showDate) now.dateText() else ""
        date?.visibility = if (settings.showDate) View.VISIBLE else View.GONE
    }

    /** Смещение до 40 dp от исходного места: заметно для пикселей, незаметно для глаза. */
    private fun drift() {
        val view = block ?: return
        val x = Random.nextInt(-dp(40), dp(40) + 1).toFloat()
        val y = Random.nextInt(-dp(28), dp(8) + 1).toFloat()
        if (settings.reduceMotion) {
            view.translationX = x
            view.translationY = y
        } else {
            view.animate().translationX(x).translationY(y).setDuration(2_000L).start()
        }
    }

    private fun startZoom() {
        val view = image ?: return
        zoom = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
        ).apply {
            duration = 45_000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}
