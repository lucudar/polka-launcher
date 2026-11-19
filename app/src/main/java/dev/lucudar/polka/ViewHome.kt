package dev.lucudar.polka

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.content.pm.LauncherApps
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.roundToInt

private const val K_SETTINGS = "settings"
private const val K_NET = "net"
private const val K_UPDATE = "update"
private const val K_APPS = "apps"
private const val K_ADD = "+"

private val ARROWS = intArrayOf(
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
)

private fun arrangeApps(all: List<AppEntry>, hidden: Set<String>, sort: SortMode, counts: Map<String, Int>): List<AppEntry> {
    val visible = all.filter { it.pkg !in hidden }
    return when (sort) {
        SortMode.Alpha -> visible
        SortMode.Frequent -> visible.sortedByDescending { counts[it.pkg] ?: 0 }
        SortMode.Newest -> visible.sortedByDescending { it.installedAt }
    }
}

private fun pickFavorites(visible: List<AppEntry>, favorites: List<String>): List<AppEntry> {
    val byPkg = visible.associateBy { it.pkg }
    return favorites.mapNotNull { byPkg[it] }
}

/** Всё, от чего зависит картинка главного экрана. Меняется что-то из этого: перерисовываем. */
private data class HomeModel(
    val apps: List<AppEntry>?,
    val favorites: List<String>,
    val counts: Map<String, Int>,
    val hidden: Set<String>,
    val sort: SortMode,
    val rows: List<AppRow>,
    val showFavorites: Boolean,
    val showAll: Boolean,
    val titles: Boolean,
    val adds: Boolean,
    val size: TileSize,
    val labels: TileLabels,
    val accent: Accent,
    val lite: Boolean,
    val calm: Boolean,
    val wallpaper: Wallpaper,
    val dim: Float,
    val solid: Boolean,
    val clock: ClockMode,
    val showDate: Boolean,
    val weather: WeatherNow?,
    val update: String?,
    val showWifi: Boolean,
    val net: Net,
    val settingsOpen: Boolean,
)

private data class ShelfSpec(val prefix: String, val title: String, val items: List<ShelfItem>, val w: Int, val h: Int)

/**
 * Главный экран на View. Логика та же, что была в Compose-версии (HomeScreen), но плитки, ряды и шапка
 * теперь обычные View: прокрутка ряда сдвигает готовые View, а не пересобирает интерфейс каждый кадр.
 * Меню, сетка выбора и настройки остались на Compose поверх: их открывают редко, и пока они закрыты,
 * слой пустой и ничего не стоит.
 */
class HomeController(private val activity: MainActivity) : ShelfHost {
    private val context: Context = activity
    val repository = AppRepository(context)
    val store = Favorites(context)
    val settings = LauncherSettings(context)
    val stats = LaunchStats(context)
    val weather = WeatherModel(context)
    val updater = Updater(context)
    val backdrop = Backdrop()
    private val weak = context.isWeakDevice()

    var apps by mutableStateOf<List<AppEntry>?>(null)
    var favorites by mutableStateOf(store.read())
    var counts by mutableStateOf(stats.snapshot())
    var net by mutableStateOf(Net.None)
    var menu by mutableStateOf<MenuTarget?>(null)
    var settingsOpen by mutableStateOf(false)
    var pickerFor by mutableStateOf<String?>(null)
    var drawerOpen by mutableStateOf(false)
    var panelApp by mutableStateOf<String?>(null)
    var panelUpdate by mutableStateOf(false)
    private var panelReturn: String? = null
    override var moving by mutableStateOf<String?>(null)
    private var lastFocus: String? = null
    private var lastRepeat = 0L

    val lite: Boolean
        get() = when (settings.effects) {
            Effects.Auto -> weak
            Effects.Full -> false
            Effects.Lite -> true
        }

    private val main = Handler(Looper.getMainLooper())
    private val root = FrameLayout(context)
    private val content = LinearLayout(context)
    private val topBar = TopBarView(context)
    private val rowsHost = RowsHost(context)
    private val toast = TextView(context)
    private val overlay = ComposeView(context)
    private val shelves = LinkedHashMap<String, ShelfView>()
    private var model: HomeModel? = null
    private var visible: List<AppEntry> = emptyList()
    private var favList: List<AppEntry> = emptyList()
    private var rowApps: List<Pair<AppRow, List<AppEntry>>> = emptyList()
    private var bakedKey: Triple<Wallpaper, Float, Boolean>? = null
    private var blurredFor: Wallpaper? = null
    private var blurred: Bitmap? = null
    private var bakeJob: Job? = null
    private var loadedOnce = false
    private var toastText: String? = null
    private val hideToast = Runnable { setToast(null) }

    fun start() {
        buildViews()
        activity.setContentView(root)
        watchNetwork()
        watchPackages()
        activity.onBackPressedDispatcher.addCallback(activity) {
            // Лаунчер не закрывается по «Назад»: кнопка только выходит из режима перестановки.
            if (moving != null) moving = null
        }
        activity.lifecycleScope.launch {
            snapshotFlow { readModel() }.collect { render(it) }
        }
        activity.lifecycleScope.launch {
            snapshotFlow { moving }.collect { key ->
                shelves.values.forEach { it.setMoving(key) }
                showPill()
            }
        }
        activity.lifecycleScope.launch {
            snapshotFlow { settings.weatherOn to settings.city }.collectLatest { (on, city) ->
                if (!on) return@collectLatest
                while (true) {
                    weather.refresh(city, force = false)
                    delay(10 * 60_000L)
                }
            }
        }
        tickClock()
    }

    // ---------- Жизненный цикл ----------

    fun onResume() {
        tickClock()
        reload()
        if (settings.autoUpdate && updater.due()) {
            activity.lifecycleScope.launch {
                delay(5_000)
                updater.check()
            }
        }
        if (settings.weatherOn) activity.lifecycleScope.launch { weather.refresh(settings.city, force = false) }
    }

    /** Home на главном экране: всё закрыть, ряды в начало, фокус на первую плитку. */
    fun onHome() {
        menu = null
        settingsOpen = false
        pickerFor = null
        drawerOpen = false
        moving = null
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        counts = stats.snapshot()
        shelves.values.forEach { it.scrollToStart() }
        rowsHost.reset()
        val loaded = apps ?: return
        focusKey(entryKey(arrangeApps(loaded, settings.hidden, settings.sort, counts)))
    }

    /** Зажатая стрелка: повторы чаще шага отбрасываем, иначе шаги копятся и ряд «подвисает». */
    fun throttle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount == 0 || event.keyCode !in ARROWS) return false
        val gap = if (lite) 110L else 75L
        if (event.eventTime - lastRepeat < gap) return true
        lastRepeat = event.eventTime
        return false
    }

    private fun reload() {
        activity.lifecycleScope.launch {
            val loaded = repository.load()
            val seeded = store.seedOnce(loaded.mapTo(HashSet()) { it.pkg })
            if (seeded != null) favorites = seeded
            val firstLoad = !loadedOnce
            loadedOnce = true
            apps = loaded
            if (firstLoad) runAutostart(loaded)
            val shown = arrangeApps(loaded, settings.hidden, settings.sort, counts)
            val shownPkgs = shown.mapTo(HashSet()) { it.pkg }
            val gone = when (val f = lastFocus) {
                null -> true
                K_SETTINGS, K_NET, K_UPDATE, K_APPS -> !keyAlive(f)
                else -> f.substringAfterLast(':') != K_ADD && f.substringAfterLast(':') !in shownPkgs
            }
            if (!overlayOpen() && (firstLoad || gone)) {
                // Дать рядам принять новый список, потом ставить фокус.
                main.post { focusKey(entryKey(shown)) }
            }
        }
    }

    private fun runAutostart(loaded: List<AppEntry>) {
        val target = settings.autostart ?: return
        if (loaded.none { it.pkg == target }) return
        val boot = context.bootCount() ?: return
        if (boot == settings.handledBoot) return
        settings.markBootHandled(boot)
        if (context.launchApp(target)) stats.record(target)
    }

    private fun tickClock() {
        main.removeCallbacks(clockTick)
        clockTick.run()
    }

    private val clockTick = object : Runnable {
        override fun run() {
            val now = LocalDateTime.now()
            topBar.clock.text = now.clockText(context, settings.clock)
            topBar.date.text = now.dateText()
            val ms = System.currentTimeMillis()
            main.postDelayed(this, 60_000L - ms % 60_000L + 50L)
        }
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        net = cm.getNetworkCapabilities(cm.activeNetwork)?.let(::toNet) ?: Net.None
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        net = toNet(caps)
                    }

                    override fun onLost(network: Network) {
                        net = Net.None
                    }
                },
                main,
            )
        }
    }

    private fun toNet(caps: NetworkCapabilities): Net = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Net.Wifi
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Net.Ethernet
        else -> Net.Other
    }

    private fun watchPackages() {
        val launcher = context.getSystemService(LauncherApps::class.java) ?: return
        runCatching {
            launcher.registerCallback(
                object : LauncherApps.Callback() {
                    override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reload()
                    override fun onPackageAdded(packageName: String?, user: UserHandle?) = reload()
                    override fun onPackageChanged(packageName: String?, user: UserHandle?) = reload()
                    override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
                    override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
                },
                main,
            )
        }
    }

    // ---------- Модель и отрисовка ----------

    private fun readModel() = HomeModel(
        apps = apps,
        favorites = favorites,
        counts = counts,
        hidden = settings.hidden,
        sort = settings.sort,
        rows = settings.rows,
        showFavorites = settings.showFavorites,
        showAll = settings.showAllApps,
        titles = settings.rowTitles,
        adds = settings.addTiles,
        size = settings.tileSize,
        labels = settings.labels,
        accent = settings.accent,
        lite = lite,
        calm = settings.reduceMotion,
        wallpaper = settings.wallpaper,
        dim = settings.brightness.dim,
        solid = settings.solidGlass,
        clock = settings.clock,
        showDate = settings.showDate,
        weather = if (settings.weatherOn) weather.current(settings.city) else null,
        update = updater.available?.version,
        showWifi = settings.showWifi,
        net = net,
        settingsOpen = settingsOpen,
    )

    private fun render(m: HomeModel) {
        val previous = model
        model = m
        if (previous == null || previous.wallpaper != m.wallpaper || previous.dim != m.dim || previous.lite != m.lite) {
            updateWallpaper(m)
        }
        if (previous?.clock != m.clock) tickClock()
        renderTopBar(m)
        renderShelves(m)
    }

    private fun updateWallpaper(m: HomeModel) {
        val key = Triple(m.wallpaper, m.dim, m.lite)
        if (bakedKey == key) return
        bakedKey = key
        bakeJob?.cancel()
        val res = context.resources
        bakeJob = activity.lifecycleScope.launch {
            val needBlur = blurredFor != m.wallpaper
            val result = withContext(Dispatchers.Default) {
                val baked = runCatching { bakeWallpaperBitmap(res, m.wallpaper, m.dim, half = m.lite) }.getOrNull()
                val blur = if (needBlur) runCatching { decodePlain(res, m.wallpaper.blur) }.getOrNull() else null
                baked to blur
            }
            result.second?.let {
                blurred = it
                blurredFor = m.wallpaper
                backdrop.blurred = it.asImageBitmap()
                topBar.setBackdrop(it)
            }
            result.first?.let { bmp ->
                // Обои фоном окна: DecorView рисует их одной картинкой, растянутой на экран с фильтрацией.
                activity.window.setBackgroundDrawable(BitmapDrawable(res, bmp).apply { isFilterBitmap = true })
            }
        }
    }

    private fun renderTopBar(m: HomeModel) {
        val accent = m.accent.color.argb
        val on = m.accent.on.argb
        topBar.applyLook(accent, on, m.calm, m.lite, m.solid, m.wallpaper)
        topBar.date.visibleIf(m.showDate)
        topBar.divider.visibleIf(m.showDate)
        val w = m.weather
        topBar.temp.visibleIf(w != null)
        topBar.word.visibleIf(w != null && w.word.isNotEmpty())
        if (w != null) {
            topBar.temp.text = w.tempText
            topBar.word.text = w.word
        }
        topBar.update.visibleIf(m.update != null)
        topBar.update.active = true
        topBar.update.setText(m.update?.let { "Обновить до\u00A0$it" })
        topBar.apps.visibleIf(!m.showAll)
        topBar.net.visibleIf(m.showWifi)
        topBar.net.showIcon(m.net == Net.Wifi)
        topBar.net.setText(if (m.net == Net.Wifi) null else m.net.title)
        topBar.gear.active = m.settingsOpen
    }

    private fun renderShelves(m: HomeModel) {
        val all = m.apps
        val ctx = context
        val favW = ctx.pxi(m.size.favW)
        val favH = ctx.pxi(m.size.favH)
        val allW = ctx.pxi(m.size.allW)
        val allH = ctx.pxi(m.size.allH)
        val style = TileStyle(m.accent.color.argb, m.accent.on.argb, m.labels, m.lite, m.calm)
        if (all == null) {
            rowsHost.column.removeAllViews()
            shelves.clear()
            return
        }
        visible = arrangeApps(all, m.hidden, m.sort, m.counts)
        favList = pickFavorites(visible, m.favorites)
        val byPkg = visible.associateBy { it.pkg }
        rowApps = m.rows.map { row -> row to row.apps.mapNotNull { byPkg[it] } }

        val specs = buildList<ShelfSpec> {
            if (m.showFavorites) {
                val items = buildList<ShelfItem> {
                    favList.forEach { add(ShelfItem.App(it)) }
                    if (m.adds) add(ShelfItem.Add)
                    if (favList.isEmpty()) {
                        add(ShelfItem.Hint(if (m.adds) "Нажми «+» или зажми OK на\u00A0любом приложении" else "Зажми OK на\u00A0любом приложении"))
                    }
                }
                add(ShelfSpec("fav", "Избранное", items, favW, favH))
            }
            rowApps.forEach { (row, list) ->
                if (list.isEmpty() && !m.adds) return@forEach
                val items = buildList<ShelfItem> {
                    list.forEach { add(ShelfItem.App(it)) }
                    if (m.adds) add(ShelfItem.Add)
                    if (list.isEmpty()) add(ShelfItem.Hint("Пустой ряд: нажми «+» и\u00A0выбери приложения"))
                }
                add(ShelfSpec("row:${row.id}", row.title, items, allW, allH))
            }
            if (m.showAll) {
                val items = buildList<ShelfItem> {
                    visible.forEach { add(ShelfItem.App(it)) }
                    if (visible.isEmpty()) add(ShelfItem.Hint("Все приложения скрыты. Вернуть их можно в\u00A0настройках, раздел «Главный экран»"))
                }
                add(ShelfSpec("all", "Все приложения", items, allW, allH))
            }
            if (isEmpty()) {
                add(ShelfSpec("empty", "", listOf(ShelfItem.Hint("Главный экран пуст. Приложения в\u00A0шапке, кнопка с\u00A0сеткой")), allW, allH))
            }
        }

        val wanted = specs.mapTo(HashSet()) { it.prefix }
        shelves.keys.filter { it !in wanted }.forEach { prefix ->
            shelves.remove(prefix)?.let { rowsHost.column.removeView(it) }
        }
        specs.forEachIndexed { index, spec ->
            val shelf = shelves.getOrPut(spec.prefix) { ShelfView(ctx, spec.prefix, this) }
            if (rowsHost.column.getChildAt(index) !== shelf) {
                (shelf.parent as? ViewGroup)?.removeView(shelf)
                rowsHost.column.addView(shelf, index)
            }
            shelf.update(spec.title, m.titles, spec.items, spec.w, spec.h, style, gapTop = if (index == 0) 0 else ctx.pxi(Space.xl))
        }
        rowsHost.calm = m.calm
        // Без своих рядов экран не прокручивается: воздух под шапкой как раньше, по высоте экрана.
        val standard = m.rows.isEmpty() && m.showFavorites && m.showAll && m.titles
        val labelSpace = if (m.labels == TileLabels.Off) 0f else Layout.labelSpace.value
        val fixed = Layout.safeTop.value + Layout.barHeight.value + Layout.shelfChrome.value * 2 + Space.xl.value +
            m.size.favH.value + m.size.allH.value + labelSpace * 2 + Layout.safeBottom.value
        val screenDp = context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density
        val gap = if (!standard) Space.xl.value else (screenDp - fixed).coerceIn(Space.l.value, Space.xxxl.value)
        (rowsHost.layoutParams as? LinearLayout.LayoutParams)?.let {
            val px = ctx.pxi(gap)
            if (it.topMargin != px) {
                it.topMargin = px
                rowsHost.requestLayout()
            }
        }
    }

    // ---------- Фокус ----------

    private fun listFor(prefix: String): List<AppEntry> = when {
        prefix == "fav" -> favList
        prefix.startsWith("row:") -> rowApps.firstOrNull { "row:${it.first.id}" == prefix }?.second.orEmpty()
        else -> visible
    }

    private fun keyAlive(key: String): Boolean {
        if (key == K_SETTINGS) return true
        if (key == K_NET) return settings.showWifi
        if (key == K_UPDATE) return updater.available != null
        if (key == K_APPS) return !settings.showAllApps
        val prefix = key.substringBeforeLast(':')
        val tail = key.substringAfterLast(':')
        if (tail == K_ADD) return shelves.containsKey(prefix) && settings.addTiles
        return listFor(prefix).any { it.pkg == tail }
    }

    /** Куда ставить фокус при старте и по Home: первое избранное, первый непустой ряд, все приложения. */
    private fun entryKey(shown: List<AppEntry>): String {
        val shownPkgs = shown.mapTo(HashSet()) { it.pkg }
        if (settings.showFavorites) pickFavorites(shown, favorites).firstOrNull()?.let { return "fav:${it.pkg}" }
        settings.rows.firstNotNullOfOrNull { row -> row.apps.firstOrNull { it in shownPkgs }?.let { "row:${row.id}:$it" } }
            ?.let { return it }
        if (settings.showAllApps) shown.firstOrNull()?.let { return "all:${it.pkg}" }
        if (settings.addTiles) {
            if (settings.showFavorites) return "fav:$K_ADD"
            settings.rows.firstOrNull()?.let { return "row:${it.id}:$K_ADD" }
        }
        return if (settings.showAllApps) K_SETTINGS else K_APPS
    }

    private fun focusKey(key: String?) {
        if (key == null) return
        when (key) {
            K_SETTINGS -> topBar.gear.requestFocus()
            K_NET -> topBar.net.requestFocus()
            K_UPDATE -> topBar.update.requestFocus()
            K_APPS -> topBar.apps.requestFocus()
            else -> {
                val prefix = key.substringBeforeLast(':')
                val shelf = shelves[prefix]
                if (shelf == null) {
                    topBar.gear.requestFocus()
                } else {
                    shelf.focusItem(key.substringAfterLast(':')) { ok -> if (!ok) topBar.gear.requestFocus() }
                }
            }
        }
    }

    // ---------- Действия ряда (ShelfHost) ----------

    override fun onTileFocused(key: String) {
        lastFocus = key
    }

    override fun onTileClick(prefix: String, app: AppEntry) = openApp(app)

    override fun onTileMenu(prefix: String, app: AppEntry, anchor: View) {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val w = anchor.width * anchor.scaleX
        val h = anchor.height * anchor.scaleY
        // getLocationInWindow учитывает масштаб: левый верхний угол уже смещён, ширину домножаем сами.
        val rect = Rect(loc[0].toFloat(), loc[1].toFloat(), loc[0] + w, loc[1] + h)
        openOverlay { menu = MenuTarget(app, "$prefix:${app.pkg}", rect) }
    }

    override fun onTileMove(prefix: String, app: AppEntry, delta: Int) {
        val pkg = app.pkg
        when {
            prefix == "fav" -> moveFavorite(pkg, delta)
            prefix.startsWith("row:") -> settings.moveInRow(prefix.removePrefix("row:"), pkg, delta, visible.mapTo(HashSet()) { it.pkg })
        }
        shelves[prefix]?.keepVisible(pkg)
    }

    override fun onMoveDone() {
        moving = null
    }

    override fun onAdd(prefix: String) {
        openOverlay { pickerFor = prefix }
    }

    private fun openApp(app: AppEntry) {
        if (context.launchApp(app.pkg)) stats.record(app.pkg) else setToast("Не получилось открыть «${app.label}»")
    }

    private fun moveFavorite(pkg: String, delta: Int) {
        val shown = visible.mapTo(HashSet()) { it.pkg }
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

    // ---------- Оверлеи на Compose ----------

    fun openSettings(fromUpdate: Boolean) {
        panelApp = null
        panelReturn = if (fromUpdate) K_UPDATE else null
        panelUpdate = fromUpdate
        openOverlay { settingsOpen = true }
    }

    fun openDrawer() = openOverlay { drawerOpen = true }

    fun openNetwork() {
        if (!context.openSystemScreen(SystemScreen.Internet)) setToast("Не получилось открыть настройки сети")
    }

    private fun overlayOpen() = menu != null || settingsOpen || pickerFor != null || drawerOpen

    /** Фокус уходит в слой Compose, ряды и шапка его не отдают, пока слой открыт. */
    private fun openOverlay(change: () -> Unit) {
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        Snapshot.withMutableSnapshot { change() }
        overlay.requestFocus()
    }

    /** Слой закрылся: фокус обратно на плитку или кнопку, с которой пришли. */
    fun overlayClosed(restore: String?) {
        if (overlayOpen()) return
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val key = restore?.takeIf { keyAlive(it) } ?: lastFocus?.takeIf { keyAlive(it) } ?: K_SETTINGS
        main.post { focusKey(key) }
    }

    fun closeMenu(restore: String?) {
        menu = null
        overlayClosed(restore)
    }

    fun closeSettings() {
        settingsOpen = false
        val back = panelReturn
        panelApp = null
        panelReturn = null
        panelUpdate = false
        overlayClosed(back ?: K_SETTINGS)
    }

    fun closePicker(prefix: String) {
        pickerFor = null
        overlayClosed("$prefix:$K_ADD")
    }

    fun closeDrawer() {
        drawerOpen = false
        overlayClosed(K_APPS)
    }

    fun toggleInPicker(prefix: String, pkg: String) {
        if (prefix == "fav") {
            val next = if (pkg in favorites) favorites - pkg else favorites + pkg
            favorites = next
            store.write(next)
        } else {
            settings.toggleInRow(prefix.removePrefix("row:"), pkg)
        }
    }

    fun launchFromDrawer(pkg: String) {
        visible.firstOrNull { it.pkg == pkg }?.let { openApp(it) }
    }

    fun visibleApps(): List<AppEntry> = visible

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
                menu = null
                panelApp = target.app.pkg
                panelReturn = target.focusKey
                panelUpdate = false
                settingsOpen = true
            }
            MenuAction.Hide -> hideApp(target)
            MenuAction.Info -> {
                closeMenu(target.focusKey)
                if (!context.openAppInfo(target.app.pkg)) setToast("Не получилось открыть сведения о приложении")
            }
            MenuAction.Uninstall -> {
                closeMenu(target.focusKey)
                if (!context.uninstallApp(target.app.pkg)) setToast("Не получилось удалить «${target.app.label}»")
            }
        }
    }

    private fun toggleFavorite(target: MenuTarget) {
        val pkg = target.app.pkg
        val before = favList.map { it.pkg }
        val next = if (pkg in favorites) favorites - pkg else favorites + pkg
        favorites = next
        store.write(next)
        val key = when {
            !target.focusKey.startsWith("fav:") -> target.focusKey
            pkg in next -> "fav:$pkg"
            else -> {
                val i = before.indexOf(pkg)
                (before.getOrNull(i + 1) ?: before.getOrNull(i - 1))?.let { "fav:$it" } ?: "all:$pkg"
            }
        }
        closeMenu(key)
    }

    private fun hideApp(target: MenuTarget) {
        val pkg = target.app.pkg
        val prefix = target.focusKey.substringBeforeLast(':')
        val row = listFor(prefix)
        val i = row.indexOfFirst { it.pkg == pkg }
        val neighbor = (row.getOrNull(i + 1) ?: row.getOrNull(i - 1))?.takeIf { it.pkg != pkg }
        settings.hide(pkg)
        setToast("Скрыли «${target.app.label}». Вернуть можно в\u00A0настройках")
        val key = neighbor?.let { "$prefix:${it.pkg}" } ?: run {
            val rest = visible.filter { it.pkg != pkg }
            entryKey(rest)
        }
        closeMenu(key)
    }

    fun onResetFavorites() {
        favorites = emptyList()
        store.clear()
    }

    fun onResetStats() {
        stats.clear()
        counts = emptyMap()
    }

    fun refreshWeather() {
        activity.lifecycleScope.launch { weather.refresh(settings.city, force = true) }
    }

    fun checkUpdate() {
        activity.lifecycleScope.launch { updater.check() }
    }

    fun installUpdate() {
        activity.lifecycleScope.launch { updater.downloadAndInstall() }
    }

    // ---------- Подсказки внизу ----------

    private fun setToast(text: String?) {
        toastText = text
        main.removeCallbacks(hideToast)
        if (text != null) main.postDelayed(hideToast, 3_000)
        showPill()
    }

    private fun showPill() {
        val text = if (moving != null) "Двигай стрелками, OK, чтобы закончить" else toastText
        if (text != null) {
            toast.text = text
            if (toast.visibility != View.VISIBLE) {
                toast.alpha = 0f
                toast.visibility = View.VISIBLE
            }
            toast.animate().alpha(1f).setDuration(160).start()
        } else if (toast.visibility == View.VISIBLE) {
            toast.animate().alpha(0f).setDuration(160).withEndAction { toast.visibility = View.GONE }.start()
        }
    }

    // ---------- Дерево View ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViews() {
        val ctx = context
        content.orientation = LinearLayout.VERTICAL
        content.clipChildren = false
        content.clipToPadding = false
        content.setPadding(0, ctx.pxi(Layout.safeTop), 0, 0)
        content.addView(
            topBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.pxi(Layout.barHeight)).apply {
                marginStart = ctx.pxi(Layout.safeH)
                marginEnd = ctx.pxi(Layout.safeH)
            },
        )
        content.addView(
            rowsHost,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ctx.pxi(Space.xl) },
        )
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        toast.polkaText(18f, Fonts.medium(ctx), Palette.text.argb, lines = 2)
        toast.background = GradientDrawable().apply {
            cornerRadius = ctx.px(40f)
            setColor(Palette.glassBase.copy(alpha = 0.86f).argb)
            setStroke(ctx.pxi(1f), Palette.hairline.argb)
        }
        val ph = ctx.pxi(Space.xl)
        val pv = ctx.pxi(Space.m)
        toast.setPadding(ph, pv, ph, pv)
        toast.visibility = View.GONE
        root.addView(
            toast,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = ctx.pxi(Space.xxxl) },
        )

        overlay.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.setContent { OverlayHost(this@HomeController) }
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            backdrop.rootSize = IntSize(v.width, v.height)
            topBar.rootSize(v.width, v.height)
        }

        topBar.gear.setOnClickListener { openSettings(fromUpdate = false) }
        topBar.update.setOnClickListener { openSettings(fromUpdate = true) }
        topBar.apps.setOnClickListener { openDrawer() }
        topBar.net.setOnClickListener { openNetwork() }
        topBar.gear.onFocused = { lastFocus = K_SETTINGS }
        topBar.update.onFocused = { lastFocus = K_UPDATE }
        topBar.apps.onFocused = { lastFocus = K_APPS }
        topBar.net.onFocused = { lastFocus = K_NET }
    }
}

/** Шапка: часы, дата, погода и кнопки в одной стеклянной капсуле. */
@SuppressLint("ViewConstructor")
class TopBarView(context: Context) : LinearLayout(context) {
    private val glass = GlassDrawable(context)
    val clock = TextView(context)
    val divider = View(context)
    val date = TextView(context)
    val temp = TextView(context)
    val word = TextView(context)
    val update = BarPillView(context, Glyphs.DOWNLOAD)
    val apps = BarPillView(context, Glyphs.APPS)
    val net = BarPillView(context, Glyphs.WIFI)
    val gear = BarPillView(context, Glyphs.SETTINGS)
    private var rootW = 0
    private var rootH = 0
    private var blurred: Bitmap? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = glass
        setPadding(context.pxi(Space.xxl), 0, context.pxi(Space.s), 0)
        clock.polkaText(32f, Fonts.semibold(context), Palette.text.argb)
        clock.fontFeatureSettings = "tnum"
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        divider.setBackgroundColor(Palette.hairline.argb)
        addView(
            divider,
            LayoutParams(context.pxi(1f), context.pxi(24f)).apply {
                marginStart = context.pxi(Space.l)
                marginEnd = context.pxi(Space.l)
            },
        )
        date.polkaText(18f, Fonts.regular(context), Palette.textSecondary.argb)
        addView(date, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        temp.polkaText(18f, Fonts.regular(context), Palette.text.argb)
        addView(temp, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        word.polkaText(18f, Fonts.regular(context), Palette.textSecondary.argb)
        addView(word, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.pxi(Space.s) })
        addView(View(context), LayoutParams(context.pxi(Space.l), 1))
        update.setIconSize(20f)
        listOf(update, apps, net, gear).forEachIndexed { i, b ->
            addView(
                b,
                LayoutParams(LayoutParams.WRAP_CONTENT, context.pxi(48f)).apply { if (i > 0) marginStart = context.pxi(Space.xs) },
            )
        }
    }

    fun applyLook(accent: Int, onAccent: Int, calm: Boolean, lite: Boolean, solid: Boolean, wallpaper: Wallpaper) {
        listOf(update, apps, net, gear).forEach {
            if (it.accent != accent || it.onAccent != onAccent || it.calm != calm) {
                it.accent = accent
                it.onAccent = onAccent
                it.calm = calm
                it.applyState(animate = false)
            }
        }
        glass.lite = lite
        glass.solid = solid
        glass.tint = if (solid) wallpaper.solidTone.argb else Palette.glassBase.copy(alpha = wallpaper.tint).argb
    }

    fun setBackdrop(bitmap: Bitmap) {
        blurred = bitmap
        place()
    }

    fun rootSize(w: Int, h: Int) {
        rootW = w
        rootH = h
        place()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) place()
    }

    private fun place() {
        if (rootW == 0 || width == 0) return
        val loc = IntArray(2)
        getLocationInWindow(loc)
        glass.setBackdrop(blurred, rootW, rootH, loc[0].toFloat(), loc[1].toFloat())
    }
}

/**
 * Ряды по вертикали. Ряд в фокусе встаёт к верху области одним движением translationY:
 * это свойство RenderNode, перекладки нет. Сам список рядов меряется без ограничения по высоте.
 */
@SuppressLint("ViewConstructor")
class RowsHost(context: Context) : FrameLayout(context) {
    val column = LinearLayout(context)
    var calm = false
    private var target = 0f

    init {
        clipChildren = true
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        column.clipToPadding = false
        column.setPadding(0, 0, 0, context.pxi(Layout.safeBottom))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        column.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        column.layout(0, 0, column.measuredWidth, column.measuredHeight)
        // Ряд удалили или он стал короче: не оставлять пустоту внизу.
        val max = (column.measuredHeight - height).coerceAtLeast(0).toFloat()
        if (-target > max) move(-max, animate = false)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        var v: View? = focused
        while (v != null && v.parent !== column) v = v.parent as? View
        if (v != null) reveal(v.top)
    }

    private fun reveal(top: Int) {
        val max = (column.measuredHeight - height).coerceAtLeast(0)
        move(-top.coerceIn(0, max).toFloat(), animate = !calm)
    }

    private fun move(y: Float, animate: Boolean) {
        if (y == target) return
        target = y
        if (animate) {
            column.animate().translationY(y).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else {
            column.animate().cancel()
            column.translationY = y
        }
    }

    fun reset() = move(0f, animate = false)
}

/** Ряд: заголовок и горизонтальный список плиток. */
@SuppressLint("ViewConstructor")
class ShelfView(context: Context, private val prefix: String, host: ShelfHost) : LinearLayout(context) {
    private val title = TextView(context)
    private val list = RecyclerView(context)
    private val adapter = ShelfAdapter(prefix, host)
    private val manager = ShelfLayoutManager(context)
    private val animator = DefaultItemAnimator().apply {
        supportsChangeAnimations = false
        moveDuration = 180
        addDuration = 160
        removeDuration = 120
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        title.polkaText(22f, Fonts.semibold(context), Palette.text.argb)
        title.setPadding(context.pxi(Layout.safeH), 0, context.pxi(Layout.safeH), 0)
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, context.pxi(28f)))
        list.layoutManager = manager
        list.adapter = adapter
        list.addItemDecoration(TileGap(context.pxi(Space.l)))
        list.clipToPadding = false
        list.clipChildren = false
        list.isFocusable = false
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.setItemViewCacheSize(6)
        list.itemAnimator = animator
        val safeH = context.pxi(Layout.safeH)
        list.setPadding(safeH, context.pxi(Space.m), safeH, context.pxi(Space.l))
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun update(text: String, showTitle: Boolean, items: List<ShelfItem>, w: Int, h: Int, style: TileStyle, gapTop: Int) {
        title.text = text
        title.visibleIf(showTitle && text.isNotEmpty())
        title.legibility(!style.lite)
        manager.paging = style.lite
        manager.calm = style.calm
        list.itemAnimator = if (style.calm) null else animator
        adapter.resize(w, h)
        adapter.style = style
        adapter.submitList(items)
        (layoutParams as? MarginLayoutParams)?.let {
            if (it.topMargin != gapTop) {
                it.topMargin = gapTop
                requestLayout()
            }
        }
    }

    fun scrollToStart() {
        list.stopScroll()
        list.scrollToPosition(0)
    }

    /** Фокус на плитку по хвосту ключа. Список мог ещё не доехать из DiffUtil: пробуем несколько кадров. */
    fun focusItem(id: String, attempts: Int = 15, done: (Boolean) -> Unit) {
        val index = adapter.currentList.indexOfFirst { it.id == id }
        if (index >= 0) {
            val cell = list.findViewHolderForAdapterPosition(index)?.itemView as? TileCell
            if (cell != null && cell.isAttachedToWindow && cell.tile.requestFocus()) {
                done(true)
                return
            }
            if (cell == null) list.scrollToPosition(index)
        }
        if (attempts <= 0) {
            done(false)
            return
        }
        list.postDelayed({ focusItem(id, attempts - 1, done) }, 40)
    }

    fun setMoving(key: String?) {
        for (i in 0 until list.childCount) {
            val cell = list.getChildAt(i) as? TileCell ?: continue
            val pos = list.getChildAdapterPosition(cell)
            val item = adapter.currentList.getOrNull(pos) as? ShelfItem.App ?: continue
            cell.tile.moving = key == "$prefix:${item.app.pkg}"
        }
    }

    /** После перестановки: плитка в фокусе должна остаться на экране. */
    fun keepVisible(pkg: String) {
        list.post {
            val index = adapter.currentList.indexOfFirst { it.id == pkg }
            val view = list.findViewHolderForAdapterPosition(index)?.itemView ?: return@post
            view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), false)
        }
    }
}

/** Обои вместе со скримом и затемнением в одну картинку. В лёгком режиме вдвое меньше по сторонам. */
fun bakeWallpaperBitmap(resources: Resources, wp: Wallpaper, dim: Float, half: Boolean): Bitmap {
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inMutable = true
        if (half) inSampleSize = 2
    }
    val bitmap = BitmapFactory.decodeResource(resources, wp.full, options)
    val canvas = android.graphics.Canvas(bitmap)
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    fun black(alpha: Float) = android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0)
    val paint = Paint().apply {
        isDither = true
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
    return bitmap
}

private fun decodePlain(resources: Resources, id: Int): Bitmap =
    BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })
