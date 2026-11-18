package dev.lucudar.polka

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import android.view.KeyEvent as NativeKeyEvent

/** Страницы панели. Вложенность не глубже трёх: настройки, раздел, список скрытых. */
private enum class Page(val title: String) {
    Main("Настройки"),
    Walls("Обои"),
    Colors("Цвет выделения"),
    Shelves("Главный экран"),
    Hidden("Скрытые приложения"),
    Startup("При включении"),
    Comfort("Прозрачность и анимации"),
    System("Настройки Android"),
    About("О Полке"),
    Rows("Ряды"),
    RowNew("Новый ряд"),
    RowDetail("Ряд"),
    RowApps("Приложения в ряду"),
    AppRows("Добавить в ряд"),
    Weather("Погода"),
    Cities("Город"),
    Dream("Заставка"),
    Update("Обновление"),
}

/** Общее для всех строк панели: куда регистрировать фокус и можно ли сейчас нажимать. */
private class RowEnv(val registry: FocusRegistry, val enabled: Boolean)

/**
 * Панель настроек: стеклянный поповер, который вырастает из шестерёнки вниз и влево.
 * Главный экран остаётся виден слева, поэтому обои и цвет выделения меняются вживую,
 * прямо за панелью. Внутри всё плоское, строка в фокусе залита акцентом.
 * «Назад» возвращает на шаг, с первой страницы закрывает панель и отдаёт фокус шестерёнке.
 */
@Composable
fun SettingsPanel(
    settings: LauncherSettings,
    apps: List<AppEntry>,
    net: Net,
    weather: WeatherModel,
    updater: Updater,
    startApp: String?,
    onRefreshWeather: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onResetFavorites: () -> Unit,
    onResetStats: () -> Unit,
    onClosed: () -> Unit,
) {
    val context = LocalContext.current
    val look = LocalLook.current
    val closed by rememberUpdatedState(onClosed)
    val registry = remember { FocusRegistry() }
    val appear = remember { Animatable(0f) }
    val pageIn = remember { Animatable(1f) }
    // Из меню плитки панель открывается сразу на рядах этого приложения, «Назад» её закрывает.
    var stack by remember { mutableStateOf(listOf(if (startApp != null) Page.AppRows else Page.Main)) }
    var returnKeys by remember { mutableStateOf(emptyList<String>()) }
    var focusKey by remember {
        mutableStateOf<String?>(
            if (startApp != null) settings.rows.firstOrNull()?.let { "approws:${it.id}" } ?: "approws:new" else "main:walls",
        )
    }
    var rowId by remember { mutableStateOf<String?>(null) }
    var navTick by remember { mutableIntStateOf(0) }
    var focusTick by remember { mutableIntStateOf(0) }
    var direction by remember { mutableIntStateOf(1) }
    var closing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val page = stack.last()
    val env = remember(closing) { RowEnv(registry, enabled = !closing) }

    LaunchedEffect(Unit) {
        if (look.reduceMotion) appear.snapTo(1f)
        else appear.animateTo(1f, tween(240, easing = Ease))
    }
    LaunchedEffect(navTick, focusTick) {
        registry.requestWithRetry(focusKey)
    }
    // Вход на страницу: короткий сдвиг со стороны, откуда пришли (вглубь справа, назад слева).
    LaunchedEffect(navTick) {
        if (navTick == 0 || look.reduceMotion) return@LaunchedEffect
        pageIn.snapTo(0f)
        pageIn.animateTo(1f, tween(220, easing = Ease))
    }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        if (!look.reduceMotion) appear.animateTo(0f, tween(160, easing = Ease))
        closed()
    }
    LaunchedEffect(status) {
        if (status != null) {
            delay(3_000)
            status = null
        }
    }

    fun initialKey(target: Page): String? = when (target) {
        Page.Main -> "main:walls"
        Page.Walls -> "wall:${settings.wallpaper.name}"
        Page.Colors -> "color:${settings.accent.name}"
        Page.Shelves -> "home:clock"
        Page.Hidden -> apps.firstOrNull { it.pkg in settings.hidden }?.let { "hidden:${it.pkg}" }
        Page.Startup -> apps.firstOrNull { it.pkg == settings.autostart }?.let { "startup:${it.pkg}" } ?: "startup:none"
        Page.Comfort -> "comfort:glass"
        Page.System -> "sys:net"
        Page.About -> "about:update"
        Page.Rows -> settings.rows.firstOrNull()?.let { "rows:${it.id}" } ?: "rows:new"
        Page.RowNew -> ROW_NAMES.firstOrNull { name -> settings.rows.none { it.title == name } }?.let { "newrow:$it" }
        Page.RowDetail -> "row:apps"
        Page.RowApps -> apps.firstOrNull { it.pkg !in settings.hidden }?.let { "rowapp:${it.pkg}" }
        Page.AppRows -> settings.rows.firstOrNull()?.let { "approws:${it.id}" } ?: "approws:new"
        Page.Weather -> "weather:on"
        Page.Cities -> "city:${settings.city.name}"
        Page.Dream -> "dream:open"
        Page.Update -> if (updater.available != null) "update:install" else "update:check"
    }

    fun push(target: Page, from: String) {
        returnKeys = returnKeys + from
        stack = stack + target
        focusKey = initialKey(target)
        direction = 1
        navTick++
    }

    fun pop() {
        if (stack.size <= 1) {
            closing = true
            return
        }
        focusKey = returnKeys.lastOrNull()
        returnKeys = returnKeys.dropLast(1)
        stack = stack.dropLast(1)
        direction = -1
        navTick++
    }

    /** Заменить верхнюю страницу: «Новый ряд» после выбора названия сразу становится этим рядом. */
    fun replaceTop(target: Page, from: String) {
        stack = stack.dropLast(1) + target
        returnKeys = returnKeys.dropLast(1) + from
        focusKey = initialKey(target)
        direction = 1
        navTick++
    }

    fun moveFocus(key: String) {
        focusKey = key
        focusTick++
    }

    fun openScreen(screen: SystemScreen) {
        if (!context.openSystemScreen(screen)) status = "Не получилось открыть настройки Android"
    }

    BackHandler(enabled = !closing) { pop() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value }
                .background(Palette.dimLight),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = Layout.safeTop + Layout.barHeight + Space.m, end = Layout.safeH, bottom = Layout.safeTop)
                .graphicsLayer {
                    val t = appear.value
                    alpha = t
                    scaleX = 0.96f + 0.04f * t
                    scaleY = 0.96f + 0.04f * t
                    transformOrigin = TransformOrigin(1f, 0f)
                }
                .width(Layout.panelWidth)
                // Панель по высоте содержимого: короткий раздел не тащит за собой пустое стекло.
                .animateContentSize(growth())
                .glass(RoundedCornerShape(Radius.panel), dense = true)
                .padding(Space.m),
        ) {
            val currentRow = settings.rows.firstOrNull { it.id == rowId }
            val header = when (page) {
                Page.RowDetail -> currentRow?.title ?: page.title
                Page.RowApps -> currentRow?.let { "«${it.title}»: приложения" } ?: page.title
                Page.AppRows -> apps.firstOrNull { it.pkg == startApp }?.let { "Ряды для «${it.label}»" } ?: page.title
                else -> page.title
            }
            BasicText(
                header,
                style = Typo.panelTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.s, bottom = Space.m),
            )
            key(page) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .graphicsLayer {
                            val t = pageIn.value
                            alpha = t
                            translationX = (1f - t) * 24.dp.toPx() * direction
                        }
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (page) {
                        Page.Main -> MainPage(
                            env = env,
                            settings = settings,
                            startupTitle = apps.firstOrNull { it.pkg == settings.autostart }?.label ?: "ничего",
                            weatherValue = if (!settings.weatherOn) {
                                "выключена"
                            } else {
                                weather.current(settings.city)?.tempText ?: "включена"
                            },
                            hasUpdate = updater.available != null,
                            onPush = { target, from -> push(target, from) },
                        )
                        Page.Walls -> WallsPage(env, settings)
                        Page.Colors -> ColorsPage(env, settings)
                        Page.Shelves -> ShelvesPage(
                            env = env,
                            settings = settings,
                            hiddenCount = apps.count { it.pkg in settings.hidden },
                            onPush = { target, from -> push(target, from) },
                        )
                        Page.Hidden -> HiddenPage(
                            env = env,
                            settings = settings,
                            apps = apps,
                            onFocus = { moveFocus(it) },
                            onEmptied = { pop() },
                        )
                        Page.Startup -> StartupPage(env, settings, apps)
                        Page.Comfort -> ComfortPage(env, settings)
                        Page.System -> SystemPage(env, net, onOpen = { openScreen(it) })
                        Page.About -> AboutPage(
                            env = env,
                            settings = settings,
                            apps = apps,
                            onResetFavorites = onResetFavorites,
                            onResetStats = onResetStats,
                            onStatus = { status = it },
                            updateValue = when (val s = updater.state) {
                                is UpdateState.Available -> "есть ${s.version}"
                                is UpdateState.Downloading -> "${s.percent}\u00A0%"
                                is UpdateState.Latest -> "последняя"
                                else -> null
                            },
                            onPush = { target, from -> push(target, from) },
                        )
                        Page.Rows -> RowsPage(
                            env = env,
                            settings = settings,
                            apps = apps,
                            onOpenRow = { id ->
                                rowId = id
                                push(Page.RowDetail, "rows:$id")
                            },
                            onNewRow = { push(Page.RowNew, "rows:new") },
                        )
                        Page.RowNew -> RowNewPage(env, settings) { name ->
                            val app = startApp
                            if (app != null && stack.getOrNull(stack.size - 2) == Page.AppRows) {
                                // Из меню плитки: ряд сразу с этим приложением, назад к списку рядов.
                                val id = settings.addRow(name, listOf(app))
                                pop()
                                focusKey = "approws:$id"
                            } else {
                                val id = settings.addRow(name)
                                rowId = id
                                replaceTop(Page.RowDetail, "rows:$id")
                            }
                        }
                        Page.RowDetail -> RowDetailPage(
                            env = env,
                            settings = settings,
                            row = currentRow,
                            apps = apps,
                            onApps = { push(Page.RowApps, "row:apps") },
                            onDeleted = {
                                pop()
                                focusKey = settings.rows.firstOrNull()?.let { "rows:${it.id}" } ?: "rows:new"
                            },
                        )
                        Page.RowApps -> RowAppsPage(env, settings, currentRow, apps)
                        Page.AppRows -> AppRowsPage(env, settings, startApp.orEmpty()) {
                            push(Page.RowNew, "approws:new")
                        }
                        Page.Weather -> WeatherPage(
                            env = env,
                            settings = settings,
                            weather = weather,
                            onCities = { push(Page.Cities, "weather:city") },
                            onRefresh = onRefreshWeather,
                        )
                        Page.Cities -> CitiesPage(env, settings)
                        Page.Dream -> DreamPage(env, onOpen = { openScreen(SystemScreen.Screensaver) })
                        Page.Update -> UpdatePage(
                            env = env,
                            settings = settings,
                            updater = updater,
                            onCheck = onCheckUpdate,
                            onInstall = onInstallUpdate,
                        )
                    }
                }
            }
            val message = status
            if (message != null) {
                BasicText(
                    message,
                    style = Typo.smallSecondary,
                    modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
                )
            }
        }
    }
}

/**
 * Первая страница: всё про Полку разделами, системные экраны Android отдельным разделом.
 * Шеврон значит «дальше внутри панели», строка без шеврона открывает системный экран.
 */
@Composable
private fun MainPage(
    env: RowEnv,
    settings: LauncherSettings,
    startupTitle: String,
    weatherValue: String,
    hasUpdate: Boolean,
    onPush: (Page, String) -> Unit,
) {
    PanelRow(env, "main:walls", "Обои", value = settings.wallpaper.title, chevron = true) {
        onPush(Page.Walls, "main:walls")
    }
    PanelRow(
        env, "main:color", "Цвет выделения",
        value = settings.accent.title, valueSwatch = settings.accent.color, chevron = true,
    ) {
        onPush(Page.Colors, "main:color")
    }
    PanelRow(env, "main:home", "Главный экран", chevron = true) {
        onPush(Page.Shelves, "main:home")
    }
    PanelRow(
        env, "main:rows", "Ряды",
        value = if (settings.rows.isEmpty()) "нет" else settings.rows.size.toString(), chevron = true,
    ) {
        onPush(Page.Rows, "main:rows")
    }
    PanelRow(env, "main:weather", "Погода", value = weatherValue, chevron = true) {
        onPush(Page.Weather, "main:weather")
    }
    PanelRow(env, "main:dream", "Заставка", chevron = true) {
        onPush(Page.Dream, "main:dream")
    }
    PanelRow(env, "main:startup", "При включении", value = startupTitle, chevron = true) {
        onPush(Page.Startup, "main:startup")
    }
    PanelRow(env, "main:comfort", "Прозрачность и анимации", chevron = true) {
        onPush(Page.Comfort, "main:comfort")
    }
    PanelRow(env, "main:system", "Настройки Android", chevron = true) {
        onPush(Page.System, "main:system")
    }
    PanelRow(env, "main:about", "О Полке", value = if (hasUpdate) "есть обновление" else null, chevron = true) {
        onPush(Page.About, "main:about")
    }
}

/** Обои меняются сразу по OK: за панелью видно результат, стекло подхватывает новую картинку. */
@Composable
private fun WallsPage(env: RowEnv, settings: LauncherSettings) {
    Wallpaper.entries.chunked(2).forEach { pair ->
        Row(
            modifier = Modifier.padding(bottom = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            pair.forEach { wallpaper ->
                WallThumb(env, wallpaper, selected = settings.wallpaper == wallpaper) {
                    settings.updateWallpaper(wallpaper)
                }
            }
        }
    }
    PanelRow(
        env, "wall:brightness", "Яркость обоев",
        value = settings.brightness.title,
        onStep = { settings.updateBrightness(settings.brightness.step(it)) },
    ) {
        settings.updateBrightness(settings.brightness.step(1))
    }
}

@Composable
private fun WallThumb(env: RowEnv, wallpaper: Wallpaper, selected: Boolean, onClick: () -> Unit) {
    val look = LocalLook.current
    var focused by remember { mutableStateOf(false) }
    val ring by animateColorAsState(if (focused) look.accent else Color.Transparent, motion(140), label = "thumbRing")
    val shape = RoundedCornerShape(Radius.thumb)
    Column(Modifier.width(Layout.thumbW)) {
        Box(
            modifier = Modifier
                .size(Layout.thumbW, Layout.thumbH)
                .clip(shape)
                .focusRequester(env.registry["wall:${wallpaper.name}"])
                .onFocusChanged { focused = it.isFocused }
                .selectKeys(onClick = onClick)
                .focusable(enabled = env.enabled),
        ) {
            Image(
                bitmap = ImageBitmap.imageResource(wallpaper.thumb),
                contentDescription = wallpaper.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().border(3.dp, ring, shape))
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.s)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(look.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        CheckIcon,
                        contentDescription = "Выбрано",
                        colorFilter = ColorFilter.tint(look.onAccent),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        BasicText(
            wallpaper.title,
            style = Typo.small,
            modifier = Modifier.padding(start = Space.xs, top = Space.s),
        )
    }
}

@Composable
private fun ColorsPage(env: RowEnv, settings: LauncherSettings) {
    Accent.entries.forEach { accent ->
        PanelRow(env, "color:${accent.name}", accent.title, swatch = accent.color, selected = settings.accent == accent) {
            settings.updateAccent(accent)
        }
    }
}

/** Значения с вариантами листаются OK или стрелками вправо и влево, без отдельного списка. */
@Composable
private fun ShelvesPage(
    env: RowEnv,
    settings: LauncherSettings,
    hiddenCount: Int,
    onPush: (Page, String) -> Unit,
) {
    PanelRow(
        env, "home:clock", "Формат времени",
        value = settings.clock.title,
        onStep = { settings.updateClock(settings.clock.step(it)) },
    ) {
        settings.updateClock(settings.clock.step(1))
    }
    PanelRow(env, "home:date", "Показывать дату", toggle = settings.showDate) {
        settings.updateShowDate(!settings.showDate)
    }
    PanelRow(
        env, "home:size", "Размер плиток",
        value = settings.tileSize.title,
        onStep = { settings.updateTileSize(settings.tileSize.step(it)) },
    ) {
        settings.updateTileSize(settings.tileSize.step(1))
    }
    PanelRow(
        env, "home:labels", "Подписи плиток",
        value = settings.labels.title,
        onStep = { settings.updateLabels(settings.labels.step(it)) },
    ) {
        settings.updateLabels(settings.labels.step(1))
    }
    PanelRow(
        env, "home:sort", "Порядок приложений",
        value = settings.sort.title,
        onStep = { settings.updateSort(settings.sort.step(it)) },
    ) {
        settings.updateSort(settings.sort.step(1))
    }
    PanelRow(
        env, "home:hidden", "Скрытые приложения",
        value = if (hiddenCount == 0) "нет" else hiddenCount.toString(),
        chevron = true,
    ) {
        onPush(Page.Hidden, "home:hidden")
    }
}

@Composable
private fun HiddenPage(
    env: RowEnv,
    settings: LauncherSettings,
    apps: List<AppEntry>,
    onFocus: (String) -> Unit,
    onEmptied: () -> Unit,
) {
    val hiddenApps = apps.filter { it.pkg in settings.hidden }
    if (hiddenApps.isEmpty()) {
        BasicText(
            "Здесь пусто. Чтобы убрать приложение с\u00A0главного экрана, зажми OK на\u00A0плитке и\u00A0выбери «Скрыть».",
            style = Typo.smallSecondary,
            modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
        )
        return
    }
    BasicText(
        "OK возвращает приложение на\u00A0главный экран",
        style = Typo.smallSecondary,
        modifier = Modifier.padding(start = Space.l, end = Space.l, bottom = Space.s),
    )
    hiddenApps.forEachIndexed { index, app ->
        PanelRow(env, "hidden:${app.pkg}", app.label, leading = { AppGlyph(app) }) {
            val next = hiddenApps.getOrNull(index + 1) ?: hiddenApps.getOrNull(index - 1)
            settings.unhide(app.pkg)
            if (next != null) onFocus("hidden:${next.pkg}") else onEmptied()
        }
    }
}

@Composable
private fun AppGlyph(app: AppEntry) {
    val banner = app.banner
    val icon = app.icon
    Box(Modifier.size(width = 48.dp, height = 27.dp), contentAlignment = Alignment.Center) {
        if (banner != null) {
            Image(
                bitmap = banner,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
            )
        } else if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
    Spacer(Modifier.width(Space.m))
}

/**
 * Автозапуск: стик включился, и сразу открылось то, ради чего его включали. Срабатывает
 * один раз за включение, «Домой» возвращает на Полку как обычно.
 */
@Composable
private fun StartupPage(env: RowEnv, settings: LauncherSettings, apps: List<AppEntry>) {
    val context = LocalContext.current
    val current = settings.autostart?.takeIf { pkg -> apps.any { it.pkg == pkg } }
    BasicText(
        "Сразу после включения стика откроется выбранное приложение. «Домой» вернёт на\u00A0Полку.",
        style = Typo.smallSecondary,
        modifier = Modifier.padding(start = Space.l, end = Space.l, bottom = Space.s),
    )
    PanelRow(env, "startup:none", "Ничего не открывать", selected = current == null) {
        settings.updateAutostart(null, context.bootCount())
    }
    apps.forEach { app ->
        PanelRow(env, "startup:${app.pkg}", app.label, leading = { AppGlyph(app) }, selected = current == app.pkg) {
            settings.updateAutostart(app.pkg, context.bootCount())
        }
    }
}

/** Системные экраны Android. Если прошивка не знает какой-то экран, откроются общие настройки. */
@Composable
private fun SystemPage(env: RowEnv, net: Net, onOpen: (SystemScreen) -> Unit) {
    val storage = remember { storageInfo() }
    PanelRow(env, "sys:net", "Сеть и интернет", value = net.title) { onOpen(SystemScreen.Internet) }
    PanelRow(env, "sys:bt", "Пульты и Bluetooth") { onOpen(SystemScreen.Bluetooth) }
    PanelRow(env, "sys:display", "Экран") { onOpen(SystemScreen.Display) }
    PanelRow(env, "sys:sound", "Звук") { onOpen(SystemScreen.Sound) }
    PanelRow(env, "sys:apps", "Приложения") { onOpen(SystemScreen.Apps) }
    PanelRow(
        env, "sys:storage", "Хранилище",
        value = storage?.let { "свободно ${formatGb(it.free)}" },
    ) { onOpen(SystemScreen.Storage) }
    PanelRow(env, "sys:dream", "Заставка") { onOpen(SystemScreen.Screensaver) }
    PanelRow(env, "sys:date", "Дата и время") { onOpen(SystemScreen.DateTime) }
    PanelRow(env, "sys:all", "Все настройки") { onOpen(SystemScreen.All) }
}

@Composable
private fun ComfortPage(env: RowEnv, settings: LauncherSettings) {
    PanelRow(
        env, "comfort:glass", "Уменьшить прозрачность",
        description = "Панели станут сплошными",
        toggle = settings.solidGlass,
    ) {
        settings.updateSolidGlass(!settings.solidGlass)
    }
    PanelRow(
        env, "comfort:motion", "Без анимаций",
        description = "Всё меняется мгновенно",
        toggle = settings.reduceMotion,
    ) {
        settings.updateReduceMotion(!settings.reduceMotion)
    }
}

@Composable
private fun AboutPage(
    env: RowEnv,
    settings: LauncherSettings,
    apps: List<AppEntry>,
    onResetFavorites: () -> Unit,
    onResetStats: () -> Unit,
    onStatus: (String) -> Unit,
    updateValue: String?,
    onPush: (Page, String) -> Unit,
) {
    val context = LocalContext.current
    val version = remember { context.versionName() }
    val storage = remember { storageInfo() }
    val isHome = remember { context.isDefaultHome() }
    var homeHint by remember { mutableStateOf(false) }
    val shown = apps.count { it.pkg !in settings.hidden }
    Column(Modifier.padding(start = Space.l, end = Space.l, bottom = Space.m)) {
        BasicText("Полка $version", style = Typo.body)
        Spacer(Modifier.height(Space.xs))
        if (storage != null) {
            BasicText("Свободно ${formatGb(storage.free)} из\u00A0${formatGb(storage.total)}", style = Typo.smallSecondary)
        }
        BasicText("На главном экране ${appsCount(shown)}", style = Typo.smallSecondary)
        if (isHome) {
            BasicText("Открывается по\u00A0кнопке «Домой»", style = Typo.smallSecondary)
        }
    }
    PanelRow(env, "about:update", "Обновление", value = updateValue, chevron = true) {
        onPush(Page.Update, "about:update")
    }
    if (!isHome) {
        PanelRow(env, "about:home", "Сделать главным экраном") {
            if (!context.openSystemScreen(SystemScreen.Home)) homeHint = true
        }
        if (homeHint) {
            BasicText(
                "На этой прошивке нет такого экрана. С\u00A0компьютера: " +
                    "adb shell cmd package set-home-activity dev.lucudar.polka/.MainActivity",
                style = Typo.smallSecondary,
                modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
            )
        }
    }
    ConfirmRow(env, "about:favs", "Очистить избранное") {
        onResetFavorites()
        onStatus("Избранное очищено")
    }
    ConfirmRow(env, "about:reset", "Сбросить настройки") {
        settings.resetAll()
        onResetStats()
        onStatus("Настройки сброшены")
    }
}

/** Сброс в два нажатия: первое заряжает строку (красная заливка), второе выполняет. Три секунды или уход фокуса отменяют. */
@Composable
private fun ConfirmRow(env: RowEnv, key: String, label: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    PanelRow(
        env, key,
        label = if (armed) "Нажми OK ещё раз" else label,
        danger = armed,
        onFocusChange = { if (!it) armed = false },
    ) {
        if (armed) {
            armed = false
            onConfirm()
        } else {
            armed = true
        }
    }
}

@Composable
private fun PanelRow(
    env: RowEnv,
    key: String,
    label: String,
    value: String? = null,
    valueSwatch: Color? = null,
    description: String? = null,
    chevron: Boolean = false,
    toggle: Boolean? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    swatch: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    onStep: ((Int) -> Unit)? = null,
    onFocusChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    val look = LocalLook.current
    var focused by remember { mutableStateOf(false) }
    // Заливка читается при отрисовке: смена фокуса не пересобирает строку 9 кадров подряд.
    val fill = animateColorAsState(
        when {
            focused && danger -> Palette.danger
            focused -> look.accent
            else -> Color.Transparent
        },
        motion(140),
        label = "rowFill",
    )
    val ink = when {
        focused && danger -> Palette.onDanger
        focused -> look.onAccent
        danger -> Palette.danger
        else -> Palette.text
    }
    val inkSecondary = if (focused) ink.copy(alpha = 0.75f) else Palette.textSecondary
    val edge = if (focused) ink.copy(alpha = 0.5f) else Palette.hairline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (description != null) Layout.rowTall else Layout.row)
            .clip(RoundedCornerShape(Radius.row))
            .drawBehind { drawRect(fill.value) }
            .focusRequester(env.registry[key])
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }
            .onPreviewKeyEvent { event ->
                val step = onStep ?: return@onPreviewKeyEvent false
                val delta = when (event.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> -1
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) step(delta)
                true
            }
            .selectKeys(onClick = onClick)
            .focusable(enabled = env.enabled)
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(2.dp, edge, CircleShape),
            )
            Spacer(Modifier.width(Space.m))
        }
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            BasicText(label, style = Typo.body.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                BasicText(description, style = Typo.small.copy(color = inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(Space.m))
            // Значение, которое листается стрелками, в фокусе показывает стрелки по бокам.
            // Место под них занято всегда, чтобы значение не прыгало при переходе фокуса.
            val arrows = if (focused) inkSecondary else Color.Transparent
            if (onStep != null) {
                Image(ChevronLeftIcon, contentDescription = null, colorFilter = ColorFilter.tint(arrows), modifier = Modifier.size(20.dp))
            }
            if (valueSwatch != null) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(valueSwatch)
                        .border(1.dp, edge, CircleShape),
                )
                Spacer(Modifier.width(Space.s))
            }
            BasicText(
                value,
                style = Typo.small.copy(color = inkSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 168.dp),
            )
            if (onStep != null) {
                Image(ChevronIcon, contentDescription = null, colorFilter = ColorFilter.tint(arrows), modifier = Modifier.size(20.dp))
            }
        }
        if (toggle != null) {
            Spacer(Modifier.width(Space.m))
            Toggle(on = toggle, focused = focused)
        }
        if (selected) {
            Spacer(Modifier.width(Space.m))
            Image(
                CheckIcon,
                contentDescription = "Выбрано",
                colorFilter = ColorFilter.tint(if (focused) ink else look.accent),
                modifier = Modifier.size(22.dp),
            )
        }
        if (chevron) {
            Spacer(Modifier.width(Space.s))
            Image(
                ChevronIcon,
                contentDescription = null,
                colorFilter = ColorFilter.tint(inkSecondary),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Переключатель. На строке в фокусе цвета меняются местами, иначе акцент растворился бы в заливке. */
@Composable
private fun Toggle(on: Boolean, focused: Boolean) {
    val look = LocalLook.current
    val track = when {
        on && focused -> look.onAccent
        on -> look.accent
        focused -> look.onAccent.copy(alpha = 0.25f)
        else -> Palette.toggleOff
    }
    val knob = when {
        on && focused -> look.accent
        on -> look.onAccent
        focused -> look.onAccent
        else -> Palette.text
    }
    val shift by animateDpAsState(if (on) 20.dp else 0.dp, motion(160), label = "toggle")
    Box(
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(CircleShape)
            .background(track)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = shift)
                .size(18.dp)
                .clip(CircleShape)
                .background(knob),
        )
    }
}

@Composable
private fun PanelHint(text: String) {
    BasicText(
        text,
        style = Typo.smallSecondary,
        modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.xs, bottom = Space.s),
    )
}

/** Свои ряды: между «Избранным» и «Всеми приложениями», в этом порядке. */
@Composable
private fun RowsPage(
    env: RowEnv,
    settings: LauncherSettings,
    apps: List<AppEntry>,
    onOpenRow: (String) -> Unit,
    onNewRow: () -> Unit,
) {
    val shown = apps.filter { it.pkg !in settings.hidden }.mapTo(HashSet()) { it.pkg }
    if (settings.rows.isEmpty()) {
        PanelHint("Свои ряды встают между «Избранным» и\u00A0«Всеми приложениями». Например, «Кино» или\u00A0«Музыка».")
    }
    settings.rows.forEach { row ->
        val count = row.apps.count { it in shown }
        PanelRow(
            env, "rows:${row.id}", row.title,
            value = if (count == 0) "пусто" else appsCount(count), chevron = true,
        ) {
            onOpenRow(row.id)
        }
    }
    PanelRow(env, "rows:new", "Новый ряд", chevron = true) { onNewRow() }
}

@Composable
private fun RowNewPage(env: RowEnv, settings: LauncherSettings, onPick: (String) -> Unit) {
    val free = ROW_NAMES.filter { name -> settings.rows.none { it.title == name } }
    if (free.isEmpty()) {
        PanelHint("Все названия уже заняты. Удали лишний ряд или переименуй его.")
        return
    }
    free.forEach { name ->
        PanelRow(env, "newrow:$name", name) { onPick(name) }
    }
}

@Composable
private fun RowDetailPage(
    env: RowEnv,
    settings: LauncherSettings,
    row: AppRow?,
    apps: List<AppEntry>,
    onApps: () -> Unit,
    onDeleted: () -> Unit,
) {
    if (row == null) return
    val shown = apps.filter { it.pkg !in settings.hidden }.mapTo(HashSet()) { it.pkg }
    val count = row.apps.count { it in shown }
    val rows = settings.rows
    val index = rows.indexOfFirst { it.id == row.id }
    // Название листается по свободным именам: два ряда «Кино» только запутают.
    val names = ROW_NAMES.filter { name -> name == row.title || rows.none { it.title == name } }
    fun rename(delta: Int) {
        val i = names.indexOf(row.title).coerceAtLeast(0)
        settings.renameRow(row.id, names[(i + delta).mod(names.size)])
    }
    PanelRow(
        env, "row:apps", "Приложения",
        value = if (count == 0) "пусто" else appsCount(count), chevron = true,
    ) {
        onApps()
    }
    PanelRow(env, "row:name", "Название", value = row.title, onStep = { rename(it) }) { rename(1) }
    if (rows.size > 1) {
        PanelRow(
            env, "row:place", "Место среди рядов",
            value = "${index + 1} из\u00A0${rows.size}",
            onStep = { settings.moveRow(row.id, it) },
        ) {
            settings.moveRow(row.id, if (index == rows.lastIndex) -index else 1)
        }
    }
    ConfirmRow(env, "row:delete", "Удалить ряд") {
        settings.deleteRow(row.id)
        onDeleted()
    }
    PanelHint("Порядок плиток внутри ряда: зажми OK на\u00A0плитке и\u00A0выбери «Переставить».")
}

@Composable
private fun RowAppsPage(env: RowEnv, settings: LauncherSettings, row: AppRow?, apps: List<AppEntry>) {
    if (row == null) return
    PanelHint("OK добавляет приложение в\u00A0ряд или убирает из\u00A0него")
    apps.filter { it.pkg !in settings.hidden }.forEach { app ->
        PanelRow(
            env, "rowapp:${app.pkg}", app.label,
            leading = { AppGlyph(app) },
            selected = app.pkg in row.apps,
        ) {
            settings.toggleInRow(row.id, app.pkg)
        }
    }
}

/** Открывается из меню плитки: в какие ряды положить это приложение. */
@Composable
private fun AppRowsPage(env: RowEnv, settings: LauncherSettings, pkg: String, onNewRow: () -> Unit) {
    if (settings.rows.isEmpty()) {
        PanelHint("Рядов пока нет. Создай первый, и\u00A0приложение сразу в\u00A0него попадёт.")
    }
    settings.rows.forEach { row ->
        PanelRow(env, "approws:${row.id}", row.title, selected = pkg in row.apps) {
            settings.toggleInRow(row.id, pkg)
        }
    }
    PanelRow(env, "approws:new", "Новый ряд", chevron = true) { onNewRow() }
}

@Composable
private fun WeatherPage(
    env: RowEnv,
    settings: LauncherSettings,
    weather: WeatherModel,
    onCities: () -> Unit,
    onRefresh: () -> Unit,
) {
    PanelRow(env, "weather:on", "Показывать погоду", toggle = settings.weatherOn) {
        settings.updateWeatherOn(!settings.weatherOn)
    }
    PanelRow(env, "weather:city", "Город", value = settings.city.title, chevron = true) { onCities() }
    PanelRow(env, "weather:refresh", "Обновить сейчас", value = if (weather.loading) "загружаю" else null) {
        if (!weather.loading) onRefresh()
    }
    val now = weather.now?.takeIf { it.city == settings.city }
    PanelHint(
        when {
            now != null -> "Сейчас " + listOf(now.tempText, now.word).filter { it.isNotEmpty() }.joinToString(" ") +
                ", обновлено в\u00A0${now.updatedText()}"
            weather.failed -> "Не получилось загрузить погоду. Проверь интернет"
            else -> "Погода появится в\u00A0шапке рядом с\u00A0часами"
        },
    )
    PanelHint("Данные Open-Meteo.com, запрос не\u00A0чаще раза в\u00A030 минут")
}

@Composable
private fun CitiesPage(env: RowEnv, settings: LauncherSettings) {
    City.entries.forEach { city ->
        PanelRow(env, "city:${city.name}", city.title, selected = settings.city == city) {
            settings.updateCity(city)
        }
    }
}

@Composable
private fun DreamPage(env: RowEnv, onOpen: () -> Unit) {
    PanelHint(
        "Крупные часы поверх обоев Полки. Фон медленно движется, а\u00A0часы раз в\u00A0минуту чуть смещаются, " +
            "чтобы экран не\u00A0выгорал.",
    )
    PanelRow(env, "dream:open", "Выбрать заставку") { onOpen() }
    PanelHint("В настройках Android выбери «Полка». Обои, формат времени и\u00A0дата берутся отсюда.")
}

@Composable
private fun UpdatePage(
    env: RowEnv,
    settings: LauncherSettings,
    updater: Updater,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    val state = updater.state
    val line = when (state) {
        UpdateState.Idle -> "Ещё не проверяли"
        UpdateState.Checking -> "Проверяю GitHub"
        is UpdateState.Latest -> "Это последняя версия"
        is UpdateState.Available -> "Доступна версия ${state.version}"
        is UpdateState.Downloading -> "Скачиваю ${state.version}: ${state.percent}\u00A0%"
        is UpdateState.Installing -> "Открываю установщик"
        is UpdateState.Failed -> state.message
    }
    Column(Modifier.padding(start = Space.l, end = Space.l, bottom = Space.m)) {
        BasicText("Установлена ${updater.installed}", style = Typo.body)
        Spacer(Modifier.height(Space.xs))
        BasicText(line, style = Typo.smallSecondary)
        if (state is UpdateState.Available && state.notes.isNotEmpty()) {
            BasicText(state.notes, style = Typo.smallSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val installMessage = InstallStatus.message
        if (installMessage != null) BasicText(installMessage, style = Typo.smallSecondary)
    }
    // Строка установки не пропадает во время скачивания, чтобы фокус не улетел в пустоту.
    val target = when (state) {
        is UpdateState.Available -> state.version
        is UpdateState.Downloading -> state.version
        is UpdateState.Installing -> state.version
        else -> null
    }
    if (target != null) {
        PanelRow(
            env, "update:install", "Скачать и установить $target",
            value = (state as? UpdateState.Downloading)?.let { "${it.percent}\u00A0%" },
        ) {
            if (!updater.busy) onInstall()
        }
    }
    PanelRow(
        env, "update:check", "Проверить сейчас",
        value = if (state == UpdateState.Checking) "проверяю" else null,
    ) {
        if (!updater.busy) onCheck()
    }
    PanelRow(
        env, "update:auto", "Проверять автоматически",
        description = "Раз в\u00A012 часов, пока Полка на\u00A0экране",
        toggle = settings.autoUpdate,
    ) {
        settings.updateAutoUpdate(!settings.autoUpdate)
    }
    PanelHint("Новые версии берутся из\u00A0релизов github.com/$UPDATE_REPO")
}
