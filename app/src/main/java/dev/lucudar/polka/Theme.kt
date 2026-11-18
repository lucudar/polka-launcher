package dev.lucudar.polka

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Отступы по шкале 4/8. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

/** Безопасные поля телевизора (overscan) и размеры. Экран 1080p = 960 x 540 dp. */
object Layout {
    val safeH = 48.dp
    val safeTop = 28.dp
    val safeBottom = 24.dp
    val barHeight = 64.dp
    val shelfChrome = 56.dp // заголовок ряда 28 + отступ сверху 12 + снизу 16
    val labelSpace = 28.dp // подпись под плиткой: зазор 8 + строка 20
    val menuWidth = 300.dp
    val panelWidth = 400.dp
    val row = 48.dp
    val rowTall = 64.dp
    val thumbW = 182.dp // (400 - 2 * 12 - 12) / 2: две превьюшки в ряд внутри панели
    val thumbH = 102.dp
}

/** Нейтрали и служебные цвета. Акцент выбирают в настройках, он приходит через LocalLook. */
object Palette {
    val text = Color(0xFFF3F5F7)
    val textSecondary = Color(0xFFC3CAD2)
    val glassBase = Color(0xFF0C1219)
    val hairline = Color(0x26FFFFFF)
    val tile = Color(0xFF1B222A)
    val skeleton = Color(0x591B222A)
    val shadow = Color(0x80000000)
    val dim = Color(0x40000000)
    val dimLight = Color(0x29000000)
    val hintFill = Color(0x66000000)
    val danger = Color(0xFFFF8A80)
    val onDanger = Color(0xFF2A0B08)
    val toggleOff = Color(0x38FFFFFF)
}

object Radius {
    val tile = 14.dp
    val menu = 22.dp
    val menuItem = 14.dp // 22 - 8 (внутренний отступ меню): скругления концентричны
    val panel = 28.dp
    val row = 16.dp // 28 - 12 (внутренний отступ панели)
    val thumb = 16.dp
}

/**
 * Всё, что меняется в настройках и нужно при отрисовке: акцент, тон стекла под текущие обои,
 * «уменьшить прозрачность» и «без анимаций».
 */
@Immutable
data class Look(
    val accent: Color,
    val onAccent: Color,
    val glassTint: Color,
    val glassDense: Color,
    val solidGlass: Boolean,
    val solidTone: Color,
    val reduceMotion: Boolean,
    val wallDim: Float,
    /** Лёгкие эффекты: без теней и без анимации размеров, см. [Effects]. */
    val lite: Boolean = false,
)

val LocalLook = staticCompositionLocalOf {
    Look(
        accent = Accent.Amber.color,
        onAccent = Accent.Amber.on,
        glassTint = Palette.glassBase.copy(alpha = 0.60f),
        glassDense = Palette.glassBase.copy(alpha = 0.82f),
        solidGlass = false,
        solidTone = Wallpaper.Lake.solidTone,
        reduceMotion = false,
        wallDim = 0f,
    )
}

/**
 * Плавный выход без затяжного хвоста: быстро стартует и мягко садится.
 * FastOutSlowIn раскачивался слишком долго, на пульте это читалось как задержка.
 */
val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Те же состояния, но при «без анимаций» они меняются мгновенно. */
@Composable
fun <T> motion(durationMillis: Int): FiniteAnimationSpec<T> =
    if (LocalLook.current.reduceMotion) snap() else tween(durationMillis, easing = Ease)

/** Масштаб плитки в фокусе: пружина с лёгким перелётом, плитка «встаёт», а не плывёт. */
@Composable
fun pop(): FiniteAnimationSpec<Float> =
    when {
        LocalLook.current.reduceMotion -> snap()
        // Лёгкий режим: жёстче и почти без перелёта, анимация укладывается в меньшее число кадров.
        LocalLook.current.lite -> spring(dampingRatio = 0.82f, stiffness = 900f)
        else -> spring(dampingRatio = 0.68f, stiffness = 560f)
    }

/** Размер панели: критическое затухание, без дрожи в конце. */
@Composable
fun growth(): FiniteAnimationSpec<IntSize> =
    if (LocalLook.current.reduceMotion || LocalLook.current.lite) {
        snap()
    } else {
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)
    }

/** Onest: гротеск, который рисовали под кириллицу. Три статичных начертания, ~50 КБ каждое. */
val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
)

private val legibility = Shadow(color = Color(0x8C000000), offset = Offset(0f, 2f), blurRadius = 10f)

/** Четыре размера: 32 / 22 / 18 / 15. Иерархия размером, вес почти не меняется. */
object Typo {
    val clock = TextStyle(
        fontFamily = Onest, color = Palette.text, fontSize = 32.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum",
    )
    val title = TextStyle(
        fontFamily = Onest, color = Palette.text, fontSize = 22.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold, shadow = legibility,
    )

    /** Заголовок внутри стеклянной панели: тень там не нужна, фон уже спокойный. */
    val panelTitle = TextStyle(
        fontFamily = Onest, color = Palette.text, fontSize = 22.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val body = TextStyle(
        fontFamily = Onest, color = Palette.text, fontSize = 18.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
    )
    val date = body.copy(color = Palette.textSecondary, fontWeight = FontWeight.Normal)
    val small = TextStyle(
        fontFamily = Onest, color = Palette.text, fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    )
    val smallSecondary = small.copy(color = Palette.textSecondary)

    /** Подпись под плиткой лежит прямо на обоях, поэтому с той же тенью, что заголовки рядов. */
    val tileLabel = TextStyle(
        fontFamily = Onest, color = Palette.text, fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium, shadow = legibility, textAlign = TextAlign.Center,
    )

    /*
     * Те же стили без тени для лёгкого режима. Размытая тень текста на Skia GL растеризуется
     * процессором в маску, а на стике это заметная доля «Slow issue draw commands».
     * Подписи и так лежат на затемнённом скримом низу обоев.
     */
    val titleFlat = title.copy(shadow = null)
    val tileLabelFlat = tileLabel.copy(shadow = null)
}
