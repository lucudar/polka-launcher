package dev.lucudar.polka

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Обои: полная картинка 1920 x 1080, размытая копия 480 x 270 для стекла и превью для настроек.
 * Скрим, плотность стекла и сплошной тон подобраны под каждую картинку замером контраста:
 * заголовок над небом от 3,7:1, дата на стекле от 4,5:1 даже в самой светлой точке.
 */
enum class Wallpaper(
    val title: String,
    val full: Int,
    val blur: Int,
    val thumb: Int,
    val scrimTop: Float,
    val scrimBottom: Float,
    val tint: Float,
    val solidTone: Color,
) {
    Lake("Озеро", R.drawable.wp_lake, R.drawable.wp_lake_blur, R.drawable.wp_lake_thumb, 0.22f, 0.69f, 0.60f, Color(0xFF19232E)),
    Dunes("Дюны", R.drawable.wp_dunes, R.drawable.wp_dunes_blur, R.drawable.wp_dunes_thumb, 0.24f, 0.69f, 0.62f, Color(0xFF212225)),
    Ridges("Горы", R.drawable.wp_ridges, R.drawable.wp_ridges_blur, R.drawable.wp_ridges_thumb, 0.32f, 0.72f, 0.70f, Color(0xFF20262C)),
    Aurora("Сияние", R.drawable.wp_aurora, R.drawable.wp_aurora_blur, R.drawable.wp_aurora_thumb, 0.18f, 0.66f, 0.60f, Color(0xFF142028)),
}

/** Цвет выделения. Все пять светлые, поэтому текст на них тёмный, тонированный тем же оттенком: от 8:1. */
enum class Accent(val title: String, val color: Color, val on: Color) {
    Amber("Янтарь", Color(0xFFF2A93B), Color(0xFF1A1206)),
    Mint("Мята", Color(0xFF5ED3B1), Color(0xFF06201A)),
    Ice("Лёд", Color(0xFF8CC8FF), Color(0xFF0A1A2B)),
    Coral("Коралл", Color(0xFFFF8F7A), Color(0xFF2A0E08)),
    Linen("Лён", Color(0xFFE8E1D2), Color(0xFF1C1A15)),
}

enum class ClockMode(val title: String) {
    Auto("Как в системе"),
    H24("24 часа"),
    H12("12 часов"),
}

enum class SortMode(val title: String) {
    Alpha("По алфавиту"),
    Frequent("Сначала частые"),
    Newest("Сначала новые"),
}

/** Размер плиток: избранное крупнее, все приложения мельче, пропорция баннера 16:9 сохраняется. */
enum class TileSize(val title: String, val favW: Dp, val favH: Dp, val allW: Dp, val allH: Dp) {
    Compact("Компактные", 160.dp, 90.dp, 128.dp, 72.dp),
    Normal("Обычные", 184.dp, 104.dp, 144.dp, 81.dp),
    Large("Крупные", 208.dp, 117.dp, 168.dp, 95.dp),
}

enum class TileLabels(val title: String) {
    Off("Нет"),
    Focused("В фокусе"),
    Always("Всегда"),
}

/** Яркость обоев поверх подобранного скрима: вечером на большом экране 100 % бывает много. */
enum class WallBrightness(val title: String, val dim: Float) {
    Full("100\u00A0%", 0f),
    Soft("75\u00A0%", 0.25f),
    Low("50\u00A0%", 0.5f),
}

/**
 * Эффекты. «Лёгкие» убирают то, что дорого для слабого GPU: тени плиток и панелей, плавную смену
 * обоев, растягивание панели. Движение (масштаб, прокрутка) остаётся. «Авто»: лёгкие на стиках
 * с памятью меньше 3 ГБ, как Mi TV Stick 4K.
 */
enum class Effects(val title: String) {
    Auto("авто"),
    Full("полные"),
    Lite("лёгкие"),
}

/** Шаг по кругу для настроек с несколькими значениями: OK и стрелки вправо/влево. */
inline fun <reified E : Enum<E>> E.step(delta: Int): E {
    val values = enumValues<E>()
    return values[(ordinal + delta).mod(values.size)]
}

/** Свой ряд на главном: название из готового списка и приложения в порядке добавления. */
@Immutable
data class AppRow(val id: String, val title: String, val apps: List<String>)

/** Названия рядов. Набирать текст пультом мучительно, поэтому выбор из готовых. */
val ROW_NAMES = listOf(
    "Кино", "Сериалы", "Музыка", "Игры", "Спорт", "Детям",
    "ТВ-каналы", "Новости", "Обучение", "Инструменты", "Разное",
)

/**
 * Настройки лаунчера. Каждое поле одновременно Compose-состояние и запись в SharedPreferences,
 * поэтому выбор в панели сразу виден на экране и переживает перезапуск.
 */
@Stable
class LauncherSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("polka", Context.MODE_PRIVATE)

    var wallpaper by mutableStateOf(prefs.readEnum(K_WALLPAPER, Wallpaper.Lake))
        private set
    var accent by mutableStateOf(prefs.readEnum(K_ACCENT, Accent.Amber))
        private set
    var clock by mutableStateOf(prefs.readEnum(K_CLOCK, ClockMode.Auto))
        private set
    var sort by mutableStateOf(prefs.readEnum(K_SORT, SortMode.Alpha))
        private set
    var showDate by mutableStateOf(prefs.getBoolean(K_DATE, true))
        private set
    var solidGlass by mutableStateOf(prefs.getBoolean(K_SOLID, false))
        private set
    var reduceMotion by mutableStateOf(prefs.getBoolean(K_MOTION, false))
        private set
    var hidden by mutableStateOf(prefs.readSet(K_HIDDEN))
        private set
    var tileSize by mutableStateOf(prefs.readEnum(K_TILES, TileSize.Normal))
        private set
    var labels by mutableStateOf(prefs.readEnum(K_LABELS, TileLabels.Off))
        private set
    var brightness by mutableStateOf(prefs.readEnum(K_BRIGHTNESS, WallBrightness.Full))
        private set

    /** Пакет, который открывается сразу после включения стика, или null. */
    var autostart by mutableStateOf<String?>(prefs.getString(K_AUTOSTART, null))
        private set

    var rows by mutableStateOf(prefs.readRows(K_ROWS))
        private set
    var weatherOn by mutableStateOf(prefs.getBoolean(K_WEATHER, false))
        private set
    var city by mutableStateOf(prefs.readEnum(K_CITY, City.Moscow))
        private set
    var autoUpdate by mutableStateOf(prefs.getBoolean(K_AUTO_UPDATE, true))
        private set

    // Главный экран: что показывать. По умолчанию всё как раньше.
    var showFavorites by mutableStateOf(prefs.getBoolean(K_SHOW_FAVS, true))
        private set
    var showAllApps by mutableStateOf(prefs.getBoolean(K_SHOW_ALL, true))
        private set
    var rowTitles by mutableStateOf(prefs.getBoolean(K_ROW_TITLES, true))
        private set
    var addTiles by mutableStateOf(prefs.getBoolean(K_ADD_TILES, true))
        private set
    var showWifi by mutableStateOf(prefs.getBoolean(K_WIFI, true))
        private set
    var effects by mutableStateOf(prefs.readEnum(K_EFFECTS, Effects.Auto))
        private set

    fun updateShowFavorites(value: Boolean) {
        showFavorites = value
        prefs.edit().putBoolean(K_SHOW_FAVS, value).apply()
    }

    fun updateShowAllApps(value: Boolean) {
        showAllApps = value
        prefs.edit().putBoolean(K_SHOW_ALL, value).apply()
    }

    fun updateRowTitles(value: Boolean) {
        rowTitles = value
        prefs.edit().putBoolean(K_ROW_TITLES, value).apply()
    }

    fun updateAddTiles(value: Boolean) {
        addTiles = value
        prefs.edit().putBoolean(K_ADD_TILES, value).apply()
    }

    fun updateShowWifi(value: Boolean) {
        showWifi = value
        prefs.edit().putBoolean(K_WIFI, value).apply()
    }

    fun updateEffects(value: Effects) {
        effects = value
        prefs.edit().putString(K_EFFECTS, value.name).apply()
    }

    /** Загрузка, на которой автозапуск уже сработал: перезапуск Полки в ту же загрузку его не повторит. */
    val handledBoot: Int
        get() = prefs.getInt(K_BOOT, -1)

    fun updateWallpaper(value: Wallpaper) {
        wallpaper = value
        prefs.edit().putString(K_WALLPAPER, value.name).apply()
    }

    fun updateAccent(value: Accent) {
        accent = value
        prefs.edit().putString(K_ACCENT, value.name).apply()
    }

    fun updateClock(value: ClockMode) {
        clock = value
        prefs.edit().putString(K_CLOCK, value.name).apply()
    }

    fun updateSort(value: SortMode) {
        sort = value
        prefs.edit().putString(K_SORT, value.name).apply()
    }

    fun updateShowDate(value: Boolean) {
        showDate = value
        prefs.edit().putBoolean(K_DATE, value).apply()
    }

    fun updateSolidGlass(value: Boolean) {
        solidGlass = value
        prefs.edit().putBoolean(K_SOLID, value).apply()
    }

    fun updateReduceMotion(value: Boolean) {
        reduceMotion = value
        prefs.edit().putBoolean(K_MOTION, value).apply()
    }

    fun updateTileSize(value: TileSize) {
        tileSize = value
        prefs.edit().putString(K_TILES, value.name).apply()
    }

    fun updateLabels(value: TileLabels) {
        labels = value
        prefs.edit().putString(K_LABELS, value.name).apply()
    }

    fun updateBrightness(value: WallBrightness) {
        brightness = value
        prefs.edit().putString(K_BRIGHTNESS, value.name).apply()
    }

    /**
     * Выбор в настройках сразу помечает текущую загрузку обработанной: иначе приложение
     * открылось бы само при первом же перезапуске Полки, не дожидаясь следующего включения.
     */
    fun updateAutostart(pkg: String?, currentBoot: Int?) {
        autostart = pkg
        val editor = prefs.edit()
        if (pkg == null) editor.remove(K_AUTOSTART) else editor.putString(K_AUTOSTART, pkg)
        editor.apply()
        if (currentBoot != null) markBootHandled(currentBoot)
    }

    fun updateWeatherOn(value: Boolean) {
        weatherOn = value
        prefs.edit().putBoolean(K_WEATHER, value).apply()
    }

    fun updateCity(value: City) {
        city = value
        prefs.edit().putString(K_CITY, value.name).apply()
    }

    fun updateAutoUpdate(value: Boolean) {
        autoUpdate = value
        prefs.edit().putBoolean(K_AUTO_UPDATE, value).apply()
    }

    /** Новый ряд встаёт последним среди своих, перед «Всеми приложениями». Возвращает его id. */
    fun addRow(title: String, apps: List<String> = emptyList()): String {
        val id = "r" + System.currentTimeMillis().toString(36)
        updateRows(rows + AppRow(id, title, apps))
        return id
    }

    fun renameRow(id: String, title: String) = updateRows(rows.map { if (it.id == id) it.copy(title = title) else it })

    fun deleteRow(id: String) = updateRows(rows.filter { it.id != id })

    fun moveRow(id: String, delta: Int) {
        val i = rows.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in rows.indices) return
        updateRows(rows.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun toggleInRow(id: String, pkg: String) = updateRows(
        rows.map { row ->
            if (row.id != id) row else row.copy(apps = if (pkg in row.apps) row.apps - pkg else row.apps + pkg)
        },
    )

    /** Перестановка внутри ряда: шаг через невидимые (скрытые и удалённые) приложения. */
    fun moveInRow(id: String, pkg: String, delta: Int, shown: Set<String>) {
        val row = rows.firstOrNull { it.id == id } ?: return
        val list = row.apps
        val i = list.indexOf(pkg)
        if (i < 0) return
        var j = i + delta
        while (j in list.indices && list[j] !in shown) j += delta
        if (j !in list.indices) return
        val next = list.toMutableList().apply { add(j, removeAt(i)) }
        updateRows(rows.map { if (it.id == id) it.copy(apps = next) else it })
    }

    private fun updateRows(value: List<AppRow>) {
        rows = value
        prefs.edit().putString(K_ROWS, value.toJson()).apply()
    }

    fun markBootHandled(boot: Int) {
        prefs.edit().putInt(K_BOOT, boot).apply()
    }

    fun hide(pkg: String) = updateHidden(hidden + pkg)

    fun unhide(pkg: String) = updateHidden(hidden - pkg)

    private fun updateHidden(value: Set<String>) {
        hidden = value
        prefs.edit().putString(K_HIDDEN, value.joinToString("\n")).apply()
    }

    /** Всё, кроме избранного: обои, цвет, часы, плитки, порядок, скрытые приложения, автозапуск. */
    fun resetAll() {
        prefs.edit()
            .remove(K_WALLPAPER).remove(K_ACCENT).remove(K_CLOCK).remove(K_SORT)
            .remove(K_DATE).remove(K_SOLID).remove(K_MOTION).remove(K_HIDDEN)
            .remove(K_TILES).remove(K_LABELS).remove(K_BRIGHTNESS).remove(K_AUTOSTART)
            .remove(K_SHOW_FAVS).remove(K_SHOW_ALL).remove(K_ROW_TITLES).remove(K_ADD_TILES)
            .remove(K_WIFI).remove(K_EFFECTS)
            .apply()
        showFavorites = true
        showAllApps = true
        rowTitles = true
        addTiles = true
        showWifi = true
        effects = Effects.Auto
        wallpaper = Wallpaper.Lake
        accent = Accent.Amber
        clock = ClockMode.Auto
        sort = SortMode.Alpha
        showDate = true
        solidGlass = false
        reduceMotion = false
        hidden = emptySet()
        tileSize = TileSize.Normal
        labels = TileLabels.Off
        brightness = WallBrightness.Full
        autostart = null
    }

    private companion object {
        const val K_WALLPAPER = "wallpaper"
        const val K_ACCENT = "accent"
        const val K_CLOCK = "clock"
        const val K_SORT = "sort"
        const val K_DATE = "show_date"
        const val K_SOLID = "solid_glass"
        const val K_MOTION = "reduce_motion"
        const val K_HIDDEN = "hidden"
        const val K_TILES = "tile_size"
        const val K_LABELS = "tile_labels"
        const val K_BRIGHTNESS = "wall_brightness"
        const val K_AUTOSTART = "autostart"
        const val K_BOOT = "autostart_boot"
        const val K_ROWS = "rows"
        const val K_WEATHER = "weather_on"
        const val K_CITY = "weather_city"
        const val K_AUTO_UPDATE = "auto_update"
        const val K_SHOW_FAVS = "show_favorites"
        const val K_SHOW_ALL = "show_all_apps"
        const val K_ROW_TITLES = "row_titles"
        const val K_ADD_TILES = "add_tiles"
        const val K_WIFI = "show_wifi"
        const val K_EFFECTS = "effects"
    }
}

/** Счётчик запусков для порядка «Сначала частые». Хранится только на стике, никуда не уходит. */
class LaunchStats(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("polka_launches", Context.MODE_PRIVATE)

    fun record(pkg: String) {
        prefs.edit().putInt(pkg, prefs.getInt(pkg, 0) + 1).apply()
    }

    fun snapshot(): Map<String, Int> = buildMap<String, Int> {
        prefs.all.forEach { (key, value) -> if (value is Int) put(key, value) }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

private inline fun <reified E : Enum<E>> SharedPreferences.readEnum(key: String, default: E): E {
    val name = getString(key, null) ?: return default
    return enumValues<E>().firstOrNull { it.name == name } ?: default
}

private fun SharedPreferences.readSet(key: String): Set<String> =
    getString(key, null)?.split('\n')?.filterTo(LinkedHashSet()) { it.isNotBlank() } ?: emptySet()

private fun List<AppRow>.toJson(): String = JSONArray().also { array ->
    forEach { row ->
        array.put(
            JSONObject()
                .put("id", row.id)
                .put("title", row.title)
                .put("apps", JSONArray(row.apps)),
        )
    }
}.toString()

private fun SharedPreferences.readRows(key: String): List<AppRow> = runCatching {
    val array = JSONArray(getString(key, null) ?: return emptyList())
    (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val apps = o.optJSONArray("apps")
        AppRow(
            id = o.getString("id"),
            title = o.getString("title"),
            apps = if (apps == null) emptyList() else (0 until apps.length()).map { apps.getString(it) },
        )
    }
}.getOrDefault(emptyList())
