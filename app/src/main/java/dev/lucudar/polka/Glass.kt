package dev.lucudar.polka

import androidx.compose.foundation.border
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Стекло без живого размытия. Обои размыты один раз заранее (480 x 270), панель рисует свой кусок
 * этой копии, выровненный по экрану. Для S905Y4 это бесплатно: ни одного blur-прохода за кадр,
 * только drawImage с обрезкой. Обои сменились: меняется и копия, стекло перерисуется само.
 */
class Backdrop {
    var blurred by mutableStateOf<ImageBitmap?>(null)
    var rootSize by mutableStateOf(IntSize.Zero)
}

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * Слои снизу вверх: тень, размытые обои, тон, блик сверху, волосяная кромка.
 * dense: плотнее для меню, панели настроек и подсказок, где под стеклом мелкий текст.
 * «Уменьшить прозрачность»: вместо обоев сплошной тон, подобранный под текущую картинку.
 */
fun Modifier.glass(
    shape: Shape,
    dense: Boolean = false,
    elevation: Dp = 20.dp,
): Modifier = composed {
    val backdrop = LocalBackdrop.current
    val look = LocalLook.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        // Тень панели на Mali-G31 стоит заметно: в лёгком режиме её нет, кромка и тон держат форму.
        .then(
            if (look.lite) Modifier else Modifier.shadow(elevation, shape, clip = false, ambientColor = Palette.shadow, spotColor = Palette.shadow),
        )
        .clip(shape)
        .drawBehind {
            if (look.solidGlass) {
                drawRect(look.solidTone)
            } else {
                val b = backdrop
                val image = b?.blurred
                if (b != null && image != null && b.rootSize.width > 0) {
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt()),
                        dstSize = b.rootSize,
                        filterQuality = FilterQuality.Low,
                    )
                }
                // «Яркость обоев» ниже 100 %: стекло показывает ту же приглушённую картинку, что и фон.
                if (look.wallDim > 0f) drawRect(Color.Black.copy(alpha = look.wallDim))
                drawRect(if (dense) look.glassDense else look.glassTint)
            }
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.5f to Color.Transparent))
        }
        .border(1.dp, Palette.hairline, shape)
}
