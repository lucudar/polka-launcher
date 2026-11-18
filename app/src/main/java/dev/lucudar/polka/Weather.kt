package dev.lucudar.polka

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Города для погоды. Ввод текста пультом мучителен, поэтому список, а не поиск.
 * Координаты центра города, точности до квартала погоде не нужно.
 */
enum class City(val title: String, val lat: Double, val lon: Double) {
    Moscow("Москва", 55.7558, 37.6173),
    Petersburg("Санкт-Петербург", 59.9311, 30.3609),
    Novosibirsk("Новосибирск", 55.0084, 82.9357),
    Yekaterinburg("Екатеринбург", 56.8389, 60.6057),
    Kazan("Казань", 55.7963, 49.1088),
    NizhnyNovgorod("Нижний Новгород", 56.2965, 43.9361),
    Chelyabinsk("Челябинск", 55.1644, 61.4368),
    Krasnoyarsk("Красноярск", 56.0153, 92.8932),
    Samara("Самара", 53.1959, 50.1002),
    Ufa("Уфа", 54.7388, 55.9721),
    Rostov("Ростов-на-Дону", 47.2357, 39.7015),
    Omsk("Омск", 54.9885, 73.3242),
    Krasnodar("Краснодар", 45.0355, 38.9753),
    Voronezh("Воронеж", 51.6720, 39.1843),
    Perm("Пермь", 58.0105, 56.2502),
    Volgograd("Волгоград", 48.7080, 44.5133),
    Saratov("Саратов", 51.5331, 46.0342),
    Tyumen("Тюмень", 57.1530, 65.5343),
    Sochi("Сочи", 43.5855, 39.7231),
    Kaliningrad("Калининград", 54.7104, 20.4522),
    Yaroslavl("Ярославль", 57.6261, 39.8845),
    Irkutsk("Иркутск", 52.2870, 104.3050),
    Khabarovsk("Хабаровск", 48.4802, 135.0719),
    Vladivostok("Владивосток", 43.1155, 131.8855),
    Minsk("Минск", 53.9006, 27.5590),
    Almaty("Алматы", 43.2220, 76.8512),
}

@Immutable
data class WeatherNow(val temp: Double, val code: Int, val city: City, val at: Long)

/** «+3°», «–5°», «0°»: минус коротким тире, как на табло; у шрифта нет отдельного знака минуса. */
val WeatherNow.tempText: String
    get() {
        val t = temp.roundToInt()
        return when {
            t > 0 -> "+$t°"
            t < 0 -> "\u2013${-t}°"
            else -> "0°"
        }
    }

/** Коды погоды WMO в одно короткое слово для шапки. */
val WeatherNow.word: String
    get() = when (code) {
        0 -> "ясно"
        1 -> "малооблачно"
        2 -> "облачно"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55, 56, 57 -> "морось"
        61, 63, 65, 66, 67 -> "дождь"
        71, 73, 75, 77, 85, 86 -> "снег"
        80, 81, 82 -> "ливень"
        95, 96, 99 -> "гроза"
        else -> ""
    }

fun WeatherNow.updatedText(): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

/**
 * Погода с Open-Meteo: без ключа и регистрации, один запрос раз в 30 минут, пока Полка на экране.
 * Последний ответ лежит в настройках, поэтому после включения стика шапка не пустая.
 */
@Stable
class WeatherModel(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("polka_weather", Context.MODE_PRIVATE)

    var now by mutableStateOf(read())
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    /** Для шапки: только свежая погода выбранного города, иначе ничего, а не вчерашние цифры. */
    fun current(city: City): WeatherNow? =
        now?.takeIf { it.city == city && System.currentTimeMillis() - it.at < STALE_MS }

    suspend fun refresh(city: City, force: Boolean) {
        val cached = now
        if (!force && cached != null && cached.city == city && System.currentTimeMillis() - cached.at < FRESH_MS) return
        if (loading) return
        loading = true
        try {
            val fresh = fetch(city)
            now = fresh
            failed = false
            write(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    private suspend fun fetch(city: City): WeatherNow = withContext(Dispatchers.IO) {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}&longitude=${city.lon}" +
                "&current=temperature_2m,weather_code&timezone=auto",
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", "Polka-Launcher")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).getJSONObject("current")
            WeatherNow(
                temp = current.getDouble("temperature_2m"),
                code = current.getInt("weather_code"),
                city = city,
                at = System.currentTimeMillis(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun read(): WeatherNow? {
        if (!prefs.contains("at")) return null
        val city = City.entries.firstOrNull { it.name == prefs.getString("city", null) } ?: return null
        return WeatherNow(
            temp = prefs.getFloat("temp", 0f).toDouble(),
            code = prefs.getInt("code", -1),
            city = city,
            at = prefs.getLong("at", 0L),
        )
    }

    private fun write(value: WeatherNow) {
        prefs.edit()
            .putFloat("temp", value.temp.toFloat())
            .putInt("code", value.code)
            .putString("city", value.city.name)
            .putLong("at", value.at)
            .apply()
    }

    private companion object {
        const val FRESH_MS = 30 * 60_000L
        const val STALE_MS = 3 * 3_600_000L
    }
}
