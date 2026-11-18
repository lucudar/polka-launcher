package dev.lucudar.polka

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Плитка в сетке выбора: 5 колонок в панели 864 dp. (864 - 2 * 12 - 2 * 12 - 4 * 16) / 5 = 150. */
private val PickW = 150.dp
private val PickH = 84.dp

/**
 * Быстрое добавление: «+» в конце ряда открывает сетку всех приложений. OK добавляет или убирает
 * сразу, ряд за стеклом меняется на глазах. «Назад» закрывает, фокус возвращается на «+».
 */
@Composable
fun AppPicker(
    title: String,
    apps: List<AppEntry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClosed: () -> Unit,
) {
    val look = LocalLook.current
    val density = LocalDensity.current
    val closed by rememberUpdatedState(onClosed)
    val appear = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val spec = remember(look.reduceMotion, density) {
        EdgeSpec(with(density) { (PickH + Space.xxl).toPx() }, look.reduceMotion)
    }

    LaunchedEffect(Unit) {
        if (look.reduceMotion) appear.snapTo(1f) else appear.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 520f))
    }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        if (!look.reduceMotion) appear.animateTo(0f, tween(150, easing = Ease))
        closed()
    }
    BackHandler(enabled = !closing) { closing = true }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
                .background(Palette.dim),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val t = appear.value
                    alpha = t.coerceIn(0f, 1f)
                    scaleX = 0.94f + 0.06f * t
                    scaleY = 0.94f + 0.06f * t
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .padding(horizontal = Layout.safeH, vertical = Layout.safeTop)
                .fillMaxWidth()
                .heightIn(max = 484.dp)
                .glass(RoundedCornerShape(Radius.panel), dense = true)
                .padding(Space.m),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    title,
                    style = Typo.panelTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    if (selected.isEmpty()) "пока пусто" else "в ряду: ${apps.count { it.pkg in selected }}",
                    style = Typo.smallSecondary,
                )
            }
            BasicText(
                "OK добавляет или убирает, «Назад» закрывает",
                style = Typo.smallSecondary,
                modifier = Modifier.padding(start = Space.l, top = Space.xs, bottom = Space.s),
            )
            CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    contentPadding = PaddingValues(Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    itemsIndexed(apps, key = { _, app -> app.pkg }) { index, app ->
                        PickTile(
                            app = app,
                            checked = app.pkg in selected,
                            enabled = !closing,
                            requester = if (index == 0) first else null,
                            onClick = { onToggle(app.pkg) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickTile(
    app: AppEntry,
    checked: Boolean,
    enabled: Boolean,
    requester: FocusRequester?,
    onClick: () -> Unit,
) {
    val look = LocalLook.current
    val shape = RoundedCornerShape(Radius.tile)
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        when {
            pressed -> 0.97f
            focused -> 1.06f
            else -> 1f
        },
        pop(),
        label = "pickScale",
    )
    val glow = animateFloatAsState(if (focused) 1f else 0f, motion(160), label = "pickGlow")
    val mark = animateFloatAsState(if (checked) 1f else 0f, pop(), label = "pickMark")
    val accent = look.accent

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                modifier = Modifier
                    .size(PickW, PickH)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        shadowElevation = glow.value * 14.dp.toPx()
                        this.shape = shape
                        clip = true
                        ambientShadowColor = Palette.shadow
                        spotShadowColor = Palette.shadow
                    }
                    .background(Palette.tile)
                    .drawWithCache {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        val ring = Stroke(4.dp.toPx())
                        val soft = Stroke(3.dp.toPx())
                        onDrawWithContent {
                            drawContent()
                            // Уже в ряду: тонкая акцентная кромка. В фокусе: полная.
                            val a = maxOf(glow.value, mark.value.coerceIn(0f, 1f) * 0.55f)
                            if (a > 0.01f) drawOutline(outline, accent.copy(alpha = a), style = if (glow.value > 0.5f) ring else soft)
                        }
                    }
                    .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .selectKeys(onClick = onClick, onPressed = { pressed = it })
                    .focusable(enabled = enabled),
                contentAlignment = Alignment.Center,
            ) {
                val banner = app.banner
                val icon = app.icon
                when {
                    banner != null -> Image(
                        bitmap = banner,
                        contentDescription = app.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    icon != null -> Image(bitmap = icon, contentDescription = app.label, modifier = Modifier.size(44.dp))
                    else -> BasicText(app.label, style = Typo.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Галочка в углу: вырастает пружиной, когда приложение попало в ряд.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(24.dp)
                    .graphicsLayer {
                        val m = mark.value
                        alpha = m.coerceIn(0f, 1f)
                        scaleX = m
                        scaleY = m
                    }
                    .background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    Icons.Filled.Check,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(look.onAccent),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(Space.s))
        BasicText(
            app.label,
            style = Typo.small.copy(color = if (focused) Palette.text else Palette.textSecondary, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(PickW),
        )
    }
}
