package dev.lucudar.polka

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

@Immutable
data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val isSystem: Boolean,
    val installedAt: Long,
)

private class Visual(val stamp: Long, val label: String, val banner: ImageBitmap?, val icon: ImageBitmap?)

/**
 * Список приложений: сначала ТВ-приложения (баннер 320 x 180), потом обычные.
 * Картинки кешируются по lastUpdateTime, поэтому возврат на главный экран почти бесплатный.
 */
class AppRepository(context: Context) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val cache = HashMap<String, Visual>()
    private val mutex = Mutex()

    suspend fun load(): List<AppEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, Pair<ResolveInfo, Boolean>>()
            query(Intent.CATEGORY_LEANBACK_LAUNCHER).forEach { found.putIfAbsent(it.activityInfo.packageName, it to true) }
            query(Intent.CATEGORY_LAUNCHER).forEach { found.putIfAbsent(it.activityInfo.packageName, it to false) }
            found.remove(appContext.packageName)
            cache.keys.retainAll(found.keys)
            val collator = Collator.getInstance(Locale.getDefault())
            found.map { (pkg, value) -> entry(pkg, value.first, value.second) }
                .sortedWith(Comparator { a, b -> collator.compare(a.label, b.label) })
        }
    }

    @Suppress("DEPRECATION")
    private fun query(category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    @Suppress("DEPRECATION")
    private fun entry(pkg: String, info: ResolveInfo, tv: Boolean): AppEntry {
        val activity = info.activityInfo
        val app = activity.applicationInfo
        val packageInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val stamp = packageInfo?.lastUpdateTime ?: 0L
        val visual = cache[pkg]?.takeIf { it.stamp == stamp } ?: run {
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            val bannerDrawable = if (tv) runCatching { activity.loadBanner(pm) ?: app.loadBanner(pm) }.getOrNull() else null
            val banner = bannerDrawable?.render(BANNER_W, BANNER_H)
            val icon = if (banner == null) runCatching { activity.loadIcon(pm) }.getOrNull()?.render(ICON_PX, ICON_PX) else null
            Visual(stamp, label, banner, icon).also { cache[pkg] = it }
        }
        return AppEntry(
            pkg = pkg,
            label = visual.label,
            banner = visual.banner,
            icon = visual.icon,
            isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            installedAt = packageInfo?.firstInstallTime ?: 0L,
        )
    }

    private fun Drawable.render(w: Int, h: Int): ImageBitmap? =
        runCatching { toBitmap(w, h, Bitmap.Config.ARGB_8888).asImageBitmap() }.getOrNull()

    private companion object {
        const val BANNER_W = 320
        const val BANNER_H = 180
        const val ICON_PX = 128
    }
}

/** Избранное: порядок пакетов в SharedPreferences. Без DataStore, чтобы не тащить зависимость. */
class Favorites(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("polka", Context.MODE_PRIVATE)

    fun read(): List<String> =
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun write(list: List<String>) {
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun clear() = write(emptyList())

    /** Первый запуск: кладём в избранное то, что почти точно нужно. Возвращает null, если уже сеяли. */
    fun seedOnce(installed: Set<String>): List<String>? {
        if (prefs.getBoolean(SEEDED, false)) return null
        val seed = SEED.filter { it in installed }.take(5)
        prefs.edit().putBoolean(SEEDED, true).putString(KEY, seed.joinToString("\n")).apply()
        return seed
    }

    private companion object {
        const val KEY = "favorites"
        const val SEEDED = "seeded"
        val SEED = listOf(
            "com.teamsmart.videomanager.tv",
            "com.liskovsoft.smarttubetv.beta",
            "com.google.android.youtube.tv",
        )
    }
}
