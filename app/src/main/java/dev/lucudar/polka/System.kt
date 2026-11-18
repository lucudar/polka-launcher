package dev.lucudar.polka

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.UserHandle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Время, которое обновляется ровно на границе минуты, а не раз в секунду. */
@Composable
fun rememberNow(refreshKey: Any): State<LocalDateTime> {
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(refreshKey) {
        while (true) {
            val t = LocalDateTime.now()
            now.value = t
            val untilNextMinute = 60_000L - (t.second * 1_000L + t.nano / 1_000_000L)
            delay(untilNextMinute.coerceAtLeast(250L))
        }
    }
    return now
}

fun LocalDateTime.clockText(context: Context, mode: ClockMode): String {
    val h24 = when (mode) {
        ClockMode.Auto -> DateFormat.is24HourFormat(context)
        ClockMode.H24 -> true
        ClockMode.H12 -> false
    }
    return format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm"))
}

/** «среда, 18 ноября»: месяц в родительном падеже даёт сама локаль. */
fun LocalDateTime.dateText(): String =
    format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))

enum class Net(val title: String) {
    Wifi("Wi-Fi"),
    Ethernet("Кабель"),
    Other("Онлайн"),
    None("Нет сети"),
}

private fun NetworkCapabilities.toNet(): Net = when {
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Net.Wifi
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Net.Ethernet
    else -> Net.Other
}

private fun ConnectivityManager.currentNet(): Net =
    getNetworkCapabilities(activeNetwork)?.toNet() ?: Net.None

@Composable
fun rememberNetwork(): State<Net> {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(ConnectivityManager::class.java) }
    val state = remember { mutableStateOf(cm?.currentNet() ?: Net.None) }
    DisposableEffect(cm) {
        val manager = cm ?: return@DisposableEffect onDispose { }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                state.value = networkCapabilities.toNet()
            }

            override fun onLost(network: Network) {
                state.value = Net.None
            }
        }
        val registered = runCatching {
            manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        }.isSuccess
        onDispose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
    return state
}

/**
 * Поставили, удалили или обновили приложение: счётчик растёт, и главный экран пересобирает список
 * сразу, а не при следующем возврате на экран.
 */
@Composable
fun rememberPackageChanges(): State<Int> {
    val context = LocalContext.current
    val tick = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val launcher = context.getSystemService(LauncherApps::class.java)
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
                tick.intValue++
            }

            override fun onPackageAdded(packageName: String?, user: UserHandle?) {
                tick.intValue++
            }

            override fun onPackageChanged(packageName: String?, user: UserHandle?) {
                tick.intValue++
            }

            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
                tick.intValue++
            }

            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
                tick.intValue++
            }
        }
        val registered = launcher != null && runCatching {
            launcher.registerCallback(callback, Handler(Looper.getMainLooper()))
        }.isSuccess
        onDispose {
            if (registered) runCatching { launcher?.unregisterCallback(callback) }
        }
    }
    return tick
}

fun Context.launchApp(pkg: String): Boolean {
    val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
        ?: packageManager.getLaunchIntentForPackage(pkg)
        ?: return false
    return safeStart(intent)
}

/**
 * Системные экраны. У каждого запасной путь: если прошивка не знает конкретный экран,
 * откроются общие настройки Android, а не ошибка.
 */
enum class SystemScreen(val actions: List<String>) {
    Internet(listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)),
    Bluetooth(listOf(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS)),
    Display(listOf(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)),
    Sound(listOf(Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_SETTINGS)),
    Apps(listOf(Settings.ACTION_APPLICATION_SETTINGS, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_SETTINGS)),
    Storage(listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_SETTINGS)),
    Screensaver(listOf(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_SETTINGS)),
    DateTime(listOf(Settings.ACTION_DATE_SETTINGS, Settings.ACTION_SETTINGS)),
    All(listOf(Settings.ACTION_SETTINGS)),
    Home(listOf(Settings.ACTION_HOME_SETTINGS)),
}

/** Номер загрузки устройства: автозапуск срабатывает один раз за включение, а не при каждом перезапуске Полки. */
fun Context.bootCount(): Int? =
    runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

fun Context.openSystemScreen(screen: SystemScreen): Boolean =
    screen.actions.any { safeStart(Intent(it)) }

fun Context.openAppInfo(pkg: String): Boolean =
    safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

fun Context.uninstallApp(pkg: String): Boolean =
    safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))

/** Полка ли сейчас открывается по кнопке «Домой». */
@Suppress("DEPRECATION")
fun Context.isDefaultHome(): Boolean {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = runCatching { packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
    return resolved?.activityInfo?.packageName == packageName
}

@Suppress("DEPRECATION")
fun Context.versionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

class StorageInfo(val free: Long, val total: Long)

fun storageInfo(): StorageInfo? = runCatching {
    val stat = StatFs(Environment.getDataDirectory().path)
    StorageInfo(stat.availableBytes, stat.totalBytes)
}.getOrNull()

/** Десятичные гигабайты, как в системных настройках: «3,1 ГБ» с неразрывным пробелом. */
fun formatGb(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f\u00A0ГБ", bytes / 1_000_000_000.0)

/** 1 приложение, 2 приложения, 5 приложений, 21 приложение. */
fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

fun appsCount(n: Int): String = "$n\u00A0${plural(n, "приложение", "приложения", "приложений")}"

fun Context.safeStart(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}
