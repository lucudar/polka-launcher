package dev.lucudar.polka

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Откуда Полка берёт обновления: релизы публичного репозитория, их собирает GitHub Actions. */
const val UPDATE_REPO = "lucudar/polka-launcher"

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Latest(val version: String) : UpdateState
    data class Available(val version: String, val apkUrl: String, val notes: String) : UpdateState
    data class Downloading(val version: String, val percent: Int) : UpdateState
    data class Installing(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Самообновление: раз в 12 часов, пока Полка на экране, сверяет версию с последним релизом
 * на GitHub. Ставит через системный установщик: подтверждение всё равно за человеком.
 */
@Stable
class Updater(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("polka_update", Context.MODE_PRIVATE)

    val installed: String = context.versionName()

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    val available: UpdateState.Available?
        get() = state as? UpdateState.Available

    val busy: Boolean
        get() = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing

    fun due(): Boolean = System.currentTimeMillis() - prefs.getLong(K_CHECKED, 0L) > CHECK_EVERY_MS

    suspend fun check() {
        if (busy) return
        state = UpdateState.Checking
        state = try {
            val release = latestRelease()
            prefs.edit().putLong(K_CHECKED, System.currentTimeMillis()).apply()
            when {
                release == null -> UpdateState.Latest(installed)
                isNewer(release.version, installed) && release.apkUrl != null ->
                    UpdateState.Available(release.version, release.apkUrl, release.notes)
                else -> UpdateState.Latest(release.version)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.Failed("Не получилось связаться с GitHub. Проверь интернет и попробуй ещё раз")
        }
    }

    suspend fun downloadAndInstall() {
        val target = available ?: return
        // На Android 8+ установку из приложения разрешают отдельно, один раз.
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            if (!context.safeStart(intent)) {
                state = UpdateState.Failed("Разреши Полке установку приложений: Настройки Android, раздел приложений, особый доступ")
            }
            return
        }
        state = UpdateState.Downloading(target.version, 0)
        try {
            val file = download(target.apkUrl) { percent -> state = UpdateState.Downloading(target.version, percent) }
            state = UpdateState.Installing(target.version)
            InstallStatus.message = null
            withContext(Dispatchers.IO) { install(file) }
            // Дальше системное окно. Удачная установка перезапустит Полку, отмена вернёт кнопку.
            state = target
        } catch (e: CancellationException) {
            state = target
            throw e
        } catch (e: Exception) {
            state = UpdateState.Failed("Не получилось скачать обновление. Попробуй ещё раз")
        }
    }

    private class Release(val version: String, val apkUrl: String?, val notes: String)

    private suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Polka-Launcher")
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null // релизов ещё нет
                else -> error("HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apk = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                        break
                    }
                }
            }
            Release(
                version = json.optString("tag_name").removePrefix("v"),
                apkUrl = apk,
                notes = json.optString("body").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString("\n"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "polka.apk")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Polka-Launcher")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            var done = 0L
            var shown = -1
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            file
        } finally {
            connection.disconnect()
        }
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("polka", 0, file.length()).use { output ->
                file.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private companion object {
        const val K_CHECKED = "checked_at"
        const val CHECK_EVERY_MS = 12 * 3_600_000L
    }
}

/** 0.4.0 против 0.3.12: сравнение по числам, хвосты вроде «-beta» не учитываются. */
fun isNewer(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.split('.', '-').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Последнее сообщение установщика для панели «Обновление». */
object InstallStatus {
    var message by mutableStateOf<String?>(null)
}

/** Ответ системного установщика: показать подтверждение или запомнить, почему не вышло. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.confirmIntent() ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(confirm) }.isFailure) {
                    InstallStatus.message = "Не получилось открыть установщик"
                }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallStatus.message = null // Полка перезапустится уже новой
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.message = "Установку отменили"
            else -> InstallStatus.message = "Установщик отказал: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестная ошибка")
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.confirmIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
    }
