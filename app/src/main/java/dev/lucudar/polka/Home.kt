package dev.lucudar.polka

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.roundToInt
import android.view.KeyEvent as NativeKeyEvent

private const val SETTINGS_KEY = "settings"
private const val NET_KEY = "net"

/** Хвост ключа для плитки «+» в конце ряда: fav:+ или row:id:+. */
private const val ADD = "+"

data class MenuTarget(val app: AppEntry, val focusKey: String, val anchor: Rect)

/** Что видно на главном: без скрытых, в выбранном порядке. Сортировка стабильная: при равных запусках остаётся алфавит. */
private fun arrange(all: List<AppEntry>, hidden: Set<String>, sort: SortMode, counts: Map<String, Int>): List<AppEntry> {
    val visible = all.filter { it.pkg !in hidden }
    return when (sort) {
        SortMode.Alpha -> visible
        SortMode.Frequent -> visible.sortedByDescending { counts[it.pkg] ?: 0 }
        SortMode.Newest -> visible.sortedByDescending { it.installedAt }
    }
}

private fun favoriteApps(visible: List<AppEntry>, favorites: List<String>): List<AppEntry> {
    val byPkg = visible.associateBy { it.pkg }
    return favorites.mapNotNull { byPkg[it] }
}

private fun firstKey(visible: List<AppEntry>, favs: List<AppEntry>): String? =
    favs.firstOrNull()?.let { "fav:${it.pkg}" } ?: visible.firstOrNull()?.let { "all:${it.pkg}" }

@Composable
fun HomeScreen(resumeTick: Int, homeTick: Int) {
    val context = LocalContext.current
    val repository = remember { AppRepository(context) }
    val store = remember { Favorites(context) }
    val settings = remember { LauncherSettings(context) }
    val stats = remember { LaunchStats(context) }
    val registry = remember { FocusRegistry() }
    val favState = rememberLazyListState()
    val allState = rememberLazyListState()

    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var favorites by remember { mutableStateOf(store.read()) }
    // Порядок «Сначала частые» берёт снимок счётчиков при старте и по кнопке Home,
    // чтобы ряд не перетасовывался под рукой после каждого запуска.
    var counts by remember { mutableStateOf(stats.snapshot()) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Ключ плитки, которую сейчас переставляют: fav:pkg или row:id:pkg.
    var moving by remember { mutableStateOf<String?>(null) }
    // Панель настроек, открытая из меню плитки: сразу на выборе рядов и с возвратом к плитке.
    var panelApp by remember { mutableStateOf<String?>(null) }
    var panelReturn by remember { mutableStateOf<String?>(null) }
    val weather = remember { WeatherModel(context) }
    val updater = remember { Updater(context) }
    val scope = rememberCoroutineScope()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    // Вертикаль ведём сами: ряд в фокусе встаёт к верху области, одной пружиной.
    val shift = remember { Animatable(0f) }
    var viewH by remember { mutableIntStateOf(0) }
    var contentH by remember { mutableIntStateOf(0) }
    // Быстрое добавление: префикс ряда, для которого открыта сетка (fav или row:id).
    var pickerFor by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var restoreFocus by remember { mutableStateOf<String?>(null) }
    var lastFocus by remember { mutableStateOf<String?>(null) }

    val wp = settings.wallpaper
    val blurred = ImageBitmap.imageResource(wp.blur)
    val backdrop = remember { Backdrop() }
    SideEffect { backdrop.blurred = blurred }
    val now by rememberNow(resumeTick)
    val net by rememberNetwork()
    val packageTick by rememberPackageChanges()

    val accent = settings.accent
    val solid = settings.solidGlass
    val calm = settings.reduceMotion
    val wallDim = settings.brightness.dim
    val look = remember(accent, wp, solid, calm, wallDim) {
        Look(
            accent = accent.color,
            onAccent = accent.on,
            glassTint = Palette.glassBase.copy(alpha = wp.tint),
            glassDense = Palette.glassBase.copy(alpha = 0.82f),
            solidGlass = solid,
            solidTone = wp.solidTone,
            reduceMotion = calm,
            wallDim = wallDim,
        )
    }

    val all = apps
    val hidden = settings.hidden
    val sort = settings.sort
    val visible = remember(all, hidden, sort, counts) { all?.let { arrange(it, hidden, sort, counts) } }
    val favList = remember(visible, favorites) { if (visible == null) emptyList() else favoriteApps(visible, favorites) }
    val customRows = settings.rows
    val rowApps = remember(visible, customRows) {
        if (visible == null) {
            emptyList()
        } else {
            val byPkg = visible.associateBy { it.pkg }
            // Пустой ряд тоже виден: в нём сразу «+», чтобы наполнить.
            customRows.map { row -> row to row.apps.mapNotNull { byPkg[it] } }
        }
    }
    val interactive = menu == null && !settingsOpen && pickerFor == null

    fun listFor(prefix: String): List<AppEntry> = when {
        prefix == "fav" -> favList
        prefix.startsWith("row:") -> rowApps.firstOrNull { "row:${it.first.id}" == prefix }?.second.orEmpty()
        else -> visible.orEmpty()
    }

    fun keyAlive(key: String): Boolean {
        if (key == SETTINGS_KEY || key == NET_KEY) return true
        val prefix = key.substringBeforeLast(':')
        val tail = key.substringAfterLast(':')
        if (tail == ADD) return prefix == "fav" || rowApps.any { "row:${it.first.id}" == prefix }
        return listFor(prefix).any { it.pkg == tail }
    }

    /** Ряд получил фокус: верх ряда к верху области, но не дальше конца списка. */
    fun reveal(top: Int) {
        val max = (contentH - viewH).coerceAtLeast(0)
        val target = top.coerceIn(0, max).toFloat()
        if (shift.targetValue == target) return
        scope.launch {
            if (calm) shift.snapTo(target) else shift.animateTo(target, spring(dampingRatio = 1f, stiffness = 380f))
        }
    }

    // Первый показ после включения стика: открыть выбранное приложение, ровно один раз за загрузку.
    fun runAutostart(loaded: List<AppEntry>) {
        val target = settings.autostart ?: return
        if (loaded.none { it.pkg == target }) return
        val boot = context.bootCount() ?: return
        if (boot == settings.handledBoot) return
        settings.markBootHandled(boot)
        if (context.launchApp(target)) stats.record(target)
    }

    // Возврат из приложения или изменение пакетов: свежий список; фокус трогаем, только если он пропал.
    LaunchedEffect(resumeTick, packageTick) {
        if (resumeTick == 0) return@LaunchedEffect // первый onResume придёт сразу после onCreate
        val loaded = repository.load()
        val seeded = store.seedOnce(loaded.mapTo(HashSet()) { it.pkg })
        if (seeded != null) favorites = seeded
        val firstLoad = apps == null
        apps = loaded
        if (firstLoad) runAutostart(loaded)
        val shown = arrange(loaded, settings.hidden, settings.sort, counts)
        val shownPkgs = shown.mapTo(HashSet()) { it.pkg }
        val focusGone = when (val f = lastFocus) {
            null -> true
            SETTINGS_KEY, NET_KEY -> false
            else -> f.substringAfterLast(':') != ADD && f.substringAfterLast(':') !in shownPkgs
        }
        if (menu == null && !settingsOpen && pickerFor == null && (firstLoad || focusGone)) {
            registry.requestWithRetry(firstKey(shown, favoriteApps(shown, favorites)))
        }
    }

    // Home на главном экране: всё закрыть, в начало рядов, фокус на первую плитку.
    LaunchedEffect(homeTick) {
        if (homeTick == 0) return@LaunchedEffect
        menu = null
        settingsOpen = false
        pickerFor = null
        moving = null
        val fresh = stats.snapshot()
        counts = fresh
        runCatching { favState.scrollToItem(0) }
        runCatching { allState.scrollToItem(0) }
        rowStates.values.forEach { runCatching { it.scrollToItem(0) } }
        shift.snapTo(0f)
        val loaded = apps ?: return@LaunchedEffect
        val shown = arrange(loaded, settings.hidden, settings.sort, fresh)
        registry.requestWithRetry(firstKey(shown, favoriteApps(shown, favorites)))
    }

    // Ряд удалили или он стал короче: не оставлять пустоту внизу.
    LaunchedEffect(contentH, viewH) {
        val max = (contentH - viewH).coerceAtLeast(0).toFloat()
        if (shift.value > max) shift.snapTo(max)
    }

    LaunchedEffect(restoreFocus) {
        val key = restoreFocus ?: return@LaunchedEffect
        registry.requestWithRetry(key)
        restoreFocus = null
    }

    // Погода: свежесть проверяем раз в 10 минут, в сеть идём не чаще раза в 30.
    LaunchedEffect(settings.weatherOn, settings.city, resumeTick) {
        if (!settings.weatherOn) return@LaunchedEffect
        while (true) {
            weather.refresh(settings.city, force = false)
            delay(10 * 60_000L)
        }
    }

    // Обновления: не чаще раза в 12 часов и только пока Полка на экране.
    LaunchedEffect(resumeTick) {
        if (resumeTick == 0 || !settings.autoUpdate || !updater.due()) return@LaunchedEffect
        delay(5_000)
        updater.check()
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3_000)
            toast = null
        }
    }

    // Лаунчер не закрывается по «Назад»: кнопка только закрывает меню или режим перестановки.
    // У панели настроек свой обработчик, он зарегистрирован позже и срабатывает первым.
    BackHandler(enabled = true) {
        when {
            menu != null -> {
                restoreFocus = menu?.focusKey
                menu = null
            }
            moving != null -> {
                moving = null
            }
            else -> Unit
        }
    }

    fun closeMenu(focusKey: String?) {
        menu = null
        restoreFocus = focusKey
    }

    fun openApp(app: AppEntry) {
        if (context.launchApp(app.pkg)) stats.record(app.pkg) else toast = "Не получилось открыть «${app.label}»"
    }

    fun toggleFavorite(target: MenuTarget) {
        val pkg = target.app.pkg
        val visibleBefore = favList.map { it.pkg }
        val next = if (pkg in favorites) favorites - pkg else favorites + pkg
        favorites = next
        store.write(next)
        val focusKey = when {
            !target.focusKey.startsWith("fav:") -> target.focusKey
            pkg in next -> "fav:$pkg"
            else -> {
                val i = visibleBefore.indexOf(pkg)
                val neighbor = visibleBefore.getOrNull(i + 1) ?: visibleBefore.getOrNull(i - 1)
                neighbor?.let { "fav:$it" } ?: "all:$pkg"
            }
        }
        closeMenu(focusKey)
    }

    fun moveFavorite(pkg: String, delta: Int) {
        val shown = visible?.mapTo(HashSet()) { it.pkg } ?: return
        val list = favorites
        val i = list.indexOf(pkg)
        if (i < 0) return
        var j = i + delta
        while (j in list.indices && list[j] !in shown) j += delta
        if (j !in list.indices) return
        val next = list.toMutableList().apply { add(j, removeAt(i)) }
        favorites = next
        store.write(next)
    }

    /** Стрелки в режиме перестановки: в избранном или в своём ряду, смотря откуда начали. */
    fun moveTile(key: String, delta: Int) {
        val pkg = key.substringAfterLast(':')
        val prefix = key.substringBeforeLast(':')
        when {
            prefix == "fav" -> moveFavorite(pkg, delta)
            prefix.startsWith("row:") -> {
                val shown = visible?.mapTo(HashSet()) { it.pkg } ?: return
                settings.moveInRow(prefix.removePrefix("row:"), pkg, delta, shown)
            }
        }
    }

    /** Скрыть с главного: фокус переходит к соседу по ряду, чтобы не улететь в начало. */
    fun hideApp(target: MenuTarget) {
        val pkg = target.app.pkg
        val prefix = target.focusKey.substringBeforeLast(':')
        val row = listFor(prefix)
        val i = row.indexOfFirst { it.pkg == pkg }
        val neighbor = (row.getOrNull(i + 1) ?: row.getOrNull(i - 1))?.takeIf { it.pkg != pkg }
        settings.hide(pkg)
        toast = "Скрыли «${target.app.label}». Вернуть можно в\u00A0настройках"
        val focusKey = neighbor?.let { "$prefix:${it.pkg}" } ?: run {
            val rest = visible.orEmpty().filter { it.pkg != pkg }
            firstKey(rest, favoriteApps(rest, favorites))
        }
        closeMenu(focusKey ?: SETTINGS_KEY)
    }

    fun onMenuAction(target: MenuTarget, action: MenuAction) {
        when (action) {
            MenuAction.ToggleFavorite -> toggleFavorite(target)
            MenuAction.Move -> {
                // Из «Всех приложений» двигаем копию в избранном: туда же и фокус.
                val key = if (target.focusKey.startsWith("all:")) "fav:${target.app.pkg}" else target.focusKey
                closeMenu(key)
                moving = key
            }
            MenuAction.Rows -> {
                closeMenu(null)
                panelApp = target.app.pkg
                panelReturn = target.focusKey
                settingsOpen = true
            }
            MenuAction.Hide -> hideApp(target)
            MenuAction.Info -> {
                closeMenu(target.focusKey)
                if (!context.openAppInfo(target.app.pkg)) toast = "Не получилось открыть сведения о приложении"
            }
            MenuAction.Uninstall -> {
                closeMenu(target.focusKey)
                if (!context.uninstallApp(target.app.pkg)) toast = "Не получилось удалить «${target.app.label}»"
            }
        }
    }

    // Списки в панелях (настройки и прочее): своё правило вместо ТВ-пивота на 30 %, см. Scroll.kt.
    val density = LocalDensity.current
    val edgeSpec = remember(density, calm) { EdgeSpec(with(density) { Layout.row.toPx() }, calm) }

    CompositionLocalProvider(
        LocalBackdrop provides backdrop,
        LocalLook provides look,
        LocalBringIntoViewSpec provides edgeSpec,
    ) {
        Box(Modifier.fillMaxSize().onSizeChanged { backdrop.rootSize = it }) {
            // Обои запечены вместе со скримом и затемнением в одну картинку: на S905Y4 это один
            // полноэкранный проход за кадр вместо трёх. Пока печётся, видна размытая копия.
            val baked = rememberBakedWallpaper(wp, wallDim)
            Crossfade(targetState = baked, animationSpec = motion(360), label = "wallpaper") { picture ->
                Image(
                    bitmap = picture ?: blurred,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val tiles = settings.tileSize
            val labels = settings.labels
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Крупные плитки с подписями не влезут в 540 dp с прежним воздухом под шапкой:
                // зазор сжимается от 48 до 16 dp, чтобы второй ряд не уехал за безопасное поле.
                val labelSpace = if (labels == TileLabels.Off) 0.dp else Layout.labelSpace
                val fixed = Layout.safeTop + Layout.barHeight + Layout.shelfChrome * 2 + Space.xl +
                    tiles.favH + tiles.allH + labelSpace * 2 + Layout.safeBottom
                // Со своими рядами экран всё равно прокручивается: воздух под шапкой обычный.
                val gap = if (customRows.isNotEmpty()) Space.xl else (maxHeight - fixed).coerceIn(Space.l, Space.xxxl)
                Column(Modifier.fillMaxSize().padding(top = Layout.safeTop)) {
                    TopBar(
                        clock = now.clockText(context, settings.clock),
                        date = if (settings.showDate) now.dateText() else null,
                        net = net,
                        netRequester = registry[NET_KEY],
                        settingsRequester = registry[SETTINGS_KEY],
                        interactive = interactive,
                        settingsActive = settingsOpen,
                        onFocused = { lastFocus = it },
                        onNetwork = {
                            if (!context.openSystemScreen(SystemScreen.Internet)) toast = "Не получилось открыть настройки сети"
                        },
                        weather = if (settings.weatherOn) weather.current(settings.city) else null,
                        updateBadge = updater.available != null,
                        onSettings = {
                            panelApp = null
                            panelReturn = null
                            settingsOpen = true
                        },
                        modifier = Modifier
                            .padding(horizontal = Layout.safeH)
                            .fillMaxWidth()
                            .height(Layout.barHeight),
                    )
                    Spacer(Modifier.height(gap))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds()
                            .onSizeChanged { viewH = it.height },
                    ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, -shift.value.roundToInt()) }
                            .wrapContentHeight(align = Alignment.Top, unbounded = true)
                            .onSizeChanged { contentH = it.height },
                    ) {
                    if (visible == null) {
                        ShelfTitle("Избранное")
                        SkeletonRow(tiles.favW, tiles.favH, 5, labelSpace)
                        Spacer(Modifier.height(Space.xl))
                        ShelfTitle("Все приложения")
                        SkeletonRow(tiles.allW, tiles.allH, 6, labelSpace)
                    } else {
                        Shelf(
                            title = "Избранное",
                            apps = favList,
                            keyPrefix = "fav",
                            tileWidth = tiles.favW,
                            tileHeight = tiles.favH,
                            labels = labels,
                            state = favState,
                            registry = registry,
                            interactive = interactive,
                            moving = moving,
                            onFocused = { lastFocus = it },
                            onLaunch = { openApp(it) },
                            onMenu = { menu = it },
                            onMove = { pkg, delta -> moveTile("fav:$pkg", delta) },
                            onMoveDone = { moving = null },
                            onReveal = { reveal(it) },
                            onAdd = { pickerFor = "fav" },
                            empty = { ShelfHint("Нажми «+» или зажми OK на\u00A0любом приложении ниже") },
                        )
                        rowApps.forEach { (row, list) ->
                            key(row.id) {
                                val prefix = "row:${row.id}"
                                Spacer(Modifier.height(Space.xl))
                                Shelf(
                                    title = row.title,
                                    apps = list,
                                    keyPrefix = prefix,
                                    tileWidth = tiles.allW,
                                    tileHeight = tiles.allH,
                                    labels = labels,
                                    state = rowStates.getOrPut(row.id) { LazyListState() },
                                    registry = registry,
                                    interactive = interactive,
                                    moving = moving,
                                    onFocused = { lastFocus = it },
                                    onLaunch = { openApp(it) },
                                    onMenu = { menu = it },
                                    onMove = { pkg, delta -> moveTile("$prefix:$pkg", delta) },
                                    onMoveDone = { moving = null },
                                    onReveal = { reveal(it) },
                                    onAdd = { pickerFor = prefix },
                                    empty = { ShelfHint("Пустой ряд: нажми «+» и\u00A0выбери приложения") },
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.xl))
                        Shelf(
                            title = "Все приложения",
                            apps = visible,
                            keyPrefix = "all",
                            tileWidth = tiles.allW,
                            tileHeight = tiles.allH,
                            labels = labels,
                            state = allState,
                            registry = registry,
                            interactive = interactive,
                            moving = null,
                            onFocused = { lastFocus = it },
                            onLaunch = { openApp(it) },
                            onMenu = { menu = it },
                            onReveal = { reveal(it) },
                            empty = { ShelfHint("Все приложения скрыты. Вернуть их можно в\u00A0настройках, раздел «Главный экран»") },
                        )
                        Spacer(Modifier.height(Layout.safeBottom))
                    }
                    }
                    }
                }
            }

            val pill = if (moving != null) "Двигай стрелками, OK, чтобы закончить" else toast
            if (pill != null) {
                GlassPill(pill, Modifier.align(Alignment.BottomCenter).padding(bottom = Space.xxxl))
            }

            menu?.let { target ->
                AppMenu(
                    target = target,
                    isFavorite = target.app.pkg in favorites,
                    canMove = target.focusKey.startsWith("row:") || target.app.pkg in favorites,
                    onAction = { action -> onMenuAction(target, action) },
                )
            }

            pickerFor?.let { prefix ->
                val isFav = prefix == "fav"
                val rowId = prefix.removePrefix("row:")
                val row = customRows.firstOrNull { it.id == rowId }
                if (isFav || row != null) {
                    AppPicker(
                        title = "Добавить в «${if (isFav) "Избранное" else row?.title.orEmpty()}»",
                        apps = visible.orEmpty(),
                        selected = if (isFav) favorites.toSet() else row?.apps.orEmpty().toSet(),
                        onToggle = { pkg ->
                            if (isFav) {
                                val next = if (pkg in favorites) favorites - pkg else favorites + pkg
                                favorites = next
                                store.write(next)
                            } else {
                                settings.toggleInRow(rowId, pkg)
                            }
                        },
                        onClosed = {
                            pickerFor = null
                            restoreFocus = "$prefix:$ADD"
                        },
                    )
                } else {
                    LaunchedEffect(prefix) { pickerFor = null }
                }
            }

            if (settingsOpen) {
                SettingsPanel(
                    settings = settings,
                    apps = all.orEmpty(),
                    net = net,
                    weather = weather,
                    updater = updater,
                    startApp = panelApp,
                    onRefreshWeather = { scope.launch { weather.refresh(settings.city, force = true) } },
                    onCheckUpdate = { scope.launch { updater.check() } },
                    onInstallUpdate = { scope.launch { updater.downloadAndInstall() } },
                    onResetFavorites = {
                        favorites = emptyList()
                        store.clear()
                    },
                    onResetStats = {
                        stats.clear()
                        counts = emptyMap()
                    },
                    onClosed = {
                        settingsOpen = false
                        restoreFocus = panelReturn?.takeIf { keyAlive(it) } ?: SETTINGS_KEY
                        panelApp = null
                        panelReturn = null
                    },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    clock: String,
    date: String?,
    net: Net,
    netRequester: FocusRequester,
    settingsRequester: FocusRequester,
    interactive: Boolean,
    settingsActive: Boolean,
    weather: WeatherNow?,
    updateBadge: Boolean,
    onFocused: (String) -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Капсула 64 dp, кнопки 48 dp с отступом 8: радиус кнопки 24 = 32 - 8.
    Row(
        modifier = modifier
            .glass(CircleShape)
            .padding(start = Space.xxl, end = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(clock, style = Typo.clock)
        if (date != null) {
            Box(
                Modifier
                    .padding(horizontal = Space.l)
                    .width(1.dp)
                    .height(24.dp)
                    .background(Palette.hairline),
            )
            BasicText(
                date,
                style = Typo.date,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (weather != null) {
            BasicText(weather.tempText, style = Typo.date.copy(color = Palette.text), maxLines = 1)
            if (weather.word.isNotEmpty()) {
                Spacer(Modifier.width(Space.s))
                BasicText(weather.word, style = Typo.date, maxLines = 1)
            }
            Spacer(Modifier.width(Space.l))
        }
        // Значок сети тоже кнопка: Wi-Fi на стике настраивают чаще всего остального.
        BarButton(netRequester, interactive, active = false, onFocused = { onFocused(NET_KEY) }, onClick = onNetwork) { tint ->
            if (net == Net.Wifi) {
                Image(
                    WifiIcon,
                    contentDescription = "Wi-Fi, настройки сети",
                    colorFilter = ColorFilter.tint(tint),
                    modifier = Modifier.size(24.dp),
                )
            } else {
                BasicText(net.title, style = Typo.small.copy(color = tint))
            }
        }
        Spacer(Modifier.width(Space.xs))
        BarButton(
            settingsRequester,
            interactive,
            active = settingsActive,
            badge = updateBadge,
            onFocused = { onFocused(SETTINGS_KEY) },
            onClick = onSettings,
        ) { tint ->
            Image(
                Icons.Filled.Settings,
                contentDescription = "Настройки",
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Кнопка в шапке: капсула 48 dp. В фокусе залита акцентом, пока открыта её панель, подсвечена
 * как выбранная. Ширина по содержимому: значок или короткое слово вроде «Кабель».
 */
@Composable
private fun BarButton(
    requester: FocusRequester,
    interactive: Boolean,
    active: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    badge: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val look = LocalLook.current
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        when {
            focused -> look.accent
            active -> look.accent.copy(alpha = 0.22f)
            else -> Color.Transparent
        },
        motion(160),
        label = "barFill",
    )
    val tint by animateColorAsState(
        when {
            focused -> look.onAccent
            active -> look.accent
            else -> Palette.text
        },
        motion(160),
        label = "barTint",
    )
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, motion(120), label = "barScale")
    Box(
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fill)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .selectKeys(onClick = onClick, onPressed = { pressed = it })
            .focusable(enabled = interactive)
            .padding(horizontal = Space.m),
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
        // Точка «есть обновление» на шестерёнке: заметно, но не кричит.
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = 8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (focused) look.onAccent else look.accent),
            )
        }
    }
}

@Composable
private fun ShelfTitle(text: String) {
    BasicText(text, style = Typo.title, modifier = Modifier.padding(start = Layout.safeH))
}

@Composable
private fun Shelf(
    title: String,
    apps: List<AppEntry>,
    keyPrefix: String,
    tileWidth: Dp,
    tileHeight: Dp,
    labels: TileLabels,
    state: LazyListState,
    registry: FocusRegistry,
    interactive: Boolean,
    moving: String?,
    onFocused: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (MenuTarget) -> Unit,
    onReveal: (Int) -> Unit,
    onMove: (String, Int) -> Unit = { _, _ -> },
    onMoveDone: () -> Unit = {},
    onAdd: (() -> Unit)? = null,
    empty: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val calm = LocalLook.current.reduceMotion
    // Верх ряда внутри списка: не state, нужен только в момент, когда ряд получает фокус.
    val top = remember { IntArray(1) }
    val spec = remember(density, calm) { PinnedSpec(with(density) { Layout.safeH.toPx() }, calm) }
    val slot = tileHeight + if (labels == TileLabels.Off) 0.dp else Layout.labelSpace
    Column(
        Modifier
            .onPlaced { top[0] = it.positionInParent().y.roundToInt() }
            .onFocusChanged { if (it.hasFocus) onReveal(top[0]) },
    ) {
        ShelfTitle(title)
        if (apps.isEmpty() && onAdd == null) {
            Box(
                modifier = Modifier
                    .padding(start = Layout.safeH, top = Space.m, bottom = Space.l)
                    .height(slot),
                contentAlignment = Alignment.CenterStart,
            ) { empty() }
        } else {
            // Вертикальный запас: плитка в фокусе растёт на 10 % и отбрасывает тень.
            CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                LazyRow(
                    state = state,
                    contentPadding = PaddingValues(start = Layout.safeH, end = Layout.safeH, top = Space.m, bottom = Space.l),
                    horizontalArrangement = Arrangement.spacedBy(Space.l),
                ) {
                    itemsIndexed(apps, key = { _, app -> app.pkg }) { index, app ->
                        val key = "$keyPrefix:${app.pkg}"
                        AppTile(
                            app = app,
                            index = index,
                            width = tileWidth,
                            height = tileHeight,
                            labels = labels,
                            focusRequester = registry[key],
                            interactive = interactive,
                            isMoving = moving == key,
                            onFocused = { onFocused(key) },
                            onClick = { onLaunch(app) },
                            onMenu = { rect -> onMenu(MenuTarget(app, key, rect)) },
                            onMove = { delta -> onMove(app.pkg, delta) },
                            onMoveDone = onMoveDone,
                            // Перестановка и добавление: соседи разъезжаются, а не прыгают.
                            modifier = if (calm) Modifier else Modifier.animateItem(),
                        )
                    }
                    if (onAdd != null) {
                        item(key = ADD) {
                            val key = "$keyPrefix:$ADD"
                            AddTile(
                                width = tileWidth,
                                height = tileHeight,
                                labels = labels,
                                requester = registry[key],
                                interactive = interactive && moving == null,
                                onFocused = { onFocused(key) },
                                onClick = onAdd,
                                modifier = if (calm) Modifier else Modifier.animateItem(),
                            )
                        }
                    }
                    if (apps.isEmpty()) {
                        item(key = "hint") {
                            Box(Modifier.height(tileHeight), contentAlignment = Alignment.CenterStart) { empty() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(
    app: AppEntry,
    index: Int,
    width: Dp,
    height: Dp,
    labels: TileLabels,
    focusRequester: FocusRequester,
    interactive: Boolean,
    isMoving: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: (Rect) -> Unit,
    onMove: (Int) -> Unit,
    onMoveDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    // Координаты нужны только меню: храним без state, прокрутка ряда не пишет снапшот каждый кадр.
    val place = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val shape = RoundedCornerShape(Radius.tile)
    // Все анимации плитки читаются только при отрисовке: фокус не пересобирает плитку 10 кадров подряд.
    val scale = animateFloatAsState(
        targetValue = when {
            isMoving -> 1.14f
            pressed -> 1.04f
            focused -> 1.1f
            else -> 1f
        },
        animationSpec = pop(),
        label = "tileScale",
    )
    val glow = animateFloatAsState(if (focused || isMoving) 1f else 0f, motion(180), label = "tileGlow")
    val lift = if (isMoving) 28.dp else 18.dp
    val accent = look.accent

    // В режиме перестановки ряд сам едет за плиткой.
    LaunchedEffect(index, isMoving) {
        if (isMoving) runCatching { bringIntoView.bringIntoView() }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width, height)
                .onPlaced { place[0] = it }
                .bringIntoViewRequester(bringIntoView)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    shadowElevation = glow.value * lift.toPx()
                    this.shape = shape
                    clip = true
                    ambientShadowColor = Palette.shadow
                    spotShadowColor = Palette.shadow
                }
                .background(Palette.tile)
                .drawWithCache {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    // Половина обводки уходит под обрезку слоя: видно ровно 2 dp внутри плитки.
                    val ring = Stroke(4.dp.toPx())
                    onDrawWithContent {
                        drawContent()
                        val a = glow.value
                        if (a > 0.01f) drawOutline(outline, accent.copy(alpha = a), style = ring)
                    }
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .onPreviewKeyEvent { event ->
                    if (!isMoving) return@onPreviewKeyEvent false
                    val code = event.nativeKeyEvent.keyCode
                    val down = event.type == KeyEventType.KeyDown
                    when {
                        code == NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (down) onMove(-1)
                            true
                        }
                        code == NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (down) onMove(1)
                            true
                        }
                        code == NativeKeyEvent.KEYCODE_DPAD_UP || code == NativeKeyEvent.KEYCODE_DPAD_DOWN -> true
                        isSelectKey(code) -> {
                            if (!down) onMoveDone()
                            true
                        }
                        else -> false
                    }
                }
                .selectKeys(
                    onClick = onClick,
                    onLongPress = { onMenu(place[0]?.takeIf { it.isAttached }?.boundsInRoot() ?: Rect.Zero) },
                    onPressed = { pressed = it },
                )
                .focusable(enabled = interactive),
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
                // Подписи включены: у приложения с телефона только иконка, имя будет под плиткой, как у всех.
                labels != TileLabels.Off && icon != null -> Image(
                    bitmap = icon,
                    contentDescription = app.label,
                    modifier = Modifier.size(height * 0.5f),
                )
                else -> {
                    // Нет ТВ-баннера (обычное приложение с телефона): иконка + подпись на сплошной плитке.
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        if (icon != null) {
                            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(height * 0.44f))
                        }
                        BasicText(app.label, style = Typo.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        TileLabel(app.label, width, labels, focused || isMoving)
    }
}

/** Подпись под плиткой: место занято всегда, поэтому ряд не прыгает, когда она проявляется в фокусе. */
@Composable
private fun TileLabel(text: String, width: Dp, labels: TileLabels, active: Boolean) {
    if (labels == TileLabels.Off) return
    val shown = labels == TileLabels.Always || active
    val labelAlpha = animateFloatAsState(if (shown) 1f else 0f, motion(160), label = "tileLabel")
    BasicText(
        text,
        style = Typo.tileLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = Space.s)
            .width(width)
            .graphicsLayer { alpha = labelAlpha.value },
    )
}

/**
 * «+» в конце ряда: пустой слот с пунктирной кромкой. В фокусе заливается акцентом,
 * OK открывает сетку выбора приложений для этого ряда.
 */
@Composable
private fun AddTile(
    width: Dp,
    height: Dp,
    labels: TileLabels,
    requester: FocusRequester,
    interactive: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.tile)
    val scale = animateFloatAsState(
        when {
            pressed -> 1.02f
            focused -> 1.1f
            else -> 1f
        },
        pop(),
        label = "addScale",
    )
    val glow = animateFloatAsState(if (focused) 1f else 0f, motion(180), label = "addGlow")
    val tint by animateColorAsState(if (focused) look.onAccent else Palette.textSecondary, motion(180), label = "addTint")
    val accent = look.accent

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width, height)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    shadowElevation = glow.value * 18.dp.toPx()
                    this.shape = shape
                    clip = true
                    ambientShadowColor = Palette.shadow
                    spotShadowColor = Palette.shadow
                }
                .drawWithCache {
                    val inset = 1.5.dp.toPx()
                    val dash = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    )
                    val corner = CornerRadius(Radius.tile.toPx() - inset)
                    val inner = Size(size.width - inset * 2, size.height - inset * 2)
                    onDrawBehind {
                        val g = glow.value
                        drawRect(Palette.tile.copy(alpha = 0.45f))
                        if (g > 0.01f) drawRect(accent.copy(alpha = g))
                        if (g < 0.99f) {
                            drawRoundRect(
                                color = Palette.textSecondary.copy(alpha = 0.6f * (1f - g)),
                                topLeft = Offset(inset, inset),
                                size = inner,
                                cornerRadius = corner,
                                style = dash,
                            )
                        }
                    }
                }
                .focusRequester(requester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .selectKeys(onClick = onClick, onPressed = { pressed = it })
                .focusable(enabled = interactive),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                Icons.Filled.Add,
                contentDescription = "Добавить приложения",
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(32.dp),
            )
        }
        TileLabel("Добавить", width, labels, focused)
    }
}

/** Обои + скрим + затемнение одной картинкой, в фоне. null, пока первая ещё печётся. */
@Composable
private fun rememberBakedWallpaper(wp: Wallpaper, dim: Float): ImageBitmap? {
    val resources = LocalContext.current.resources
    var baked by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(wp, dim) {
        val next = withContext(Dispatchers.Default) { runCatching { bakeWallpaper(resources, wp, dim) }.getOrNull() }
        if (next != null) baked = next
    }
    return baked
}

/**
 * Скрим ровный сверху и плавно темнее книзу. Сила подобрана под каждую картинку, чтобы заголовок
 * над небом держал от 3,7:1 при норме 3:1 для крупного текста. «Яркость обоев» ложится поверх.
 */
private fun bakeWallpaper(resources: Resources, wp: Wallpaper, dim: Float): ImageBitmap {
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inMutable = true
    }
    val bitmap = BitmapFactory.decodeResource(resources, wp.full, options)
    val canvas = android.graphics.Canvas(bitmap)
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    fun black(alpha: Float) = android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0)
    val paint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(black(wp.scrimTop), black(wp.scrimTop), black(wp.scrimBottom)),
            floatArrayOf(0f, 0.12f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, w, h, paint)
    if (dim > 0f) canvas.drawColor(black(dim))
    bitmap.prepareToDraw()
    return bitmap.asImageBitmap()
}

@Composable
private fun SkeletonRow(width: Dp, height: Dp, count: Int, labelSpace: Dp) {
    Row(
        modifier = Modifier.padding(start = Layout.safeH, top = Space.m, bottom = Space.l + labelSpace),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        repeat(count) {
            Box(
                Modifier
                    .size(width, height)
                    .clip(RoundedCornerShape(Radius.tile))
                    .background(Palette.skeleton),
            )
        }
    }
}

/** Подсказка в пустом ряду. Сплошная тёмная подложка: текст читается на любых обоях. */
@Composable
private fun ShelfHint(text: String) {
    val shape = RoundedCornerShape(Radius.tile)
    Box(
        modifier = Modifier
            .widthIn(max = 620.dp)
            .clip(shape)
            .background(Palette.hintFill)
            .border(1.dp, Palette.hairline, shape)
            .padding(horizontal = Space.xl, vertical = Space.l),
    ) {
        BasicText(text, style = Typo.body)
    }
}

@Composable
private fun GlassPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .glass(CircleShape, dense = true)
            .padding(horizontal = Space.xl, vertical = Space.m),
    ) {
        BasicText(text, style = Typo.body)
    }
}
