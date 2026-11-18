package dev.lucudar.polka

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import android.view.KeyEvent as NativeKeyEvent

enum class MenuAction { ToggleFavorite, Move, Hide, Info, Uninstall }

/**
 * Меню плитки: стеклянный поповер, который вырастает из плитки (снизу от верхнего ряда,
 * сверху от нижнего). Внутри всё плоское: стекло на стекле не кладём.
 */
@Composable
fun AppMenu(target: MenuTarget, isFavorite: Boolean, onAction: (MenuAction) -> Unit) {
    val look = LocalLook.current
    val items = buildList {
        add(MenuAction.ToggleFavorite to (if (isFavorite) "Убрать из избранного" else "В избранное"))
        if (isFavorite) add(MenuAction.Move to "Переставить")
        add(MenuAction.Hide to "Скрыть")
        add(MenuAction.Info to "О приложении")
        if (!target.app.isSystem) add(MenuAction.Uninstall to "Удалить")
    }
    val first = remember { FocusRequester() }
    val appear = remember { Animatable(0f) }
    val rootHeight = LocalBackdrop.current?.rootSize?.height ?: 0
    val placeBelow = rootHeight == 0 || target.anchor.center.y < rootHeight * 0.5f

    LaunchedEffect(target) {
        launch {
            if (look.reduceMotion) appear.snapTo(1f)
            else appear.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
        }
        repeat(20) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.dim)) {
        Column(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val gap = Space.m.roundToPx()
                    val margin = Layout.safeH.roundToPx()
                    val a = target.anchor
                    val rawY = if (placeBelow) a.bottom.roundToInt() + gap else a.top.roundToInt() - gap - placeable.height
                    val maxY = (constraints.maxHeight - placeable.height - margin / 2).coerceAtLeast(margin / 2)
                    val y = rawY.coerceIn(margin / 2, maxY)
                    val rawX = (a.center.x - placeable.width / 2f).roundToInt()
                    val maxX = (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin)
                    val x = rawX.coerceIn(margin, maxX)
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, y) }
                }
                .graphicsLayer {
                    val t = appear.value
                    alpha = t
                    scaleX = 0.92f + 0.08f * t
                    scaleY = 0.92f + 0.08f * t
                    transformOrigin = TransformOrigin(0.5f, if (placeBelow) 0f else 1f)
                }
                .width(Layout.menuWidth)
                .glass(RoundedCornerShape(Radius.menu), dense = true)
                // Влево/вправо из меню не выпускаем; вверх/вниз уйти некуда: плитки на это время выключены.
                .onPreviewKeyEvent { event ->
                    val code = event.nativeKeyEvent.keyCode
                    code == NativeKeyEvent.KEYCODE_DPAD_LEFT || code == NativeKeyEvent.KEYCODE_DPAD_RIGHT
                }
                .padding(Space.s),
        ) {
            BasicText(
                target.app.label,
                style = Typo.smallSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m),
            )
            items.forEachIndexed { index, (action, label) ->
                MenuItem(label = label, requester = if (index == 0) first else null) { onAction(action) }
            }
        }
    }
}

/** Пункт в фокусе залит акцентом с тёмным текстом: с трёх метров это читается лучше подсветки. */
@Composable
private fun MenuItem(label: String, requester: FocusRequester?, onClick: () -> Unit) {
    val look = LocalLook.current
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) look.accent else Color.Transparent, motion(140), label = "menuFill")
    val color by animateColorAsState(if (focused) look.onAccent else Palette.text, motion(140), label = "menuText")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(Radius.menuItem))
            .background(fill)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .selectKeys(onClick = onClick)
            .focusable()
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(label, style = Typo.body.copy(color = color))
    }
}
