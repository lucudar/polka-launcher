package dev.lucudar.polka

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity

/**
 * Главный экран с 0.7 на обычных View (см. ViewHome.kt): на Mi TV Stick 4K это вдвое быстрее Compose.
 * Compose остался для меню, сетки выбора и настроек поверх.
 */
class MainActivity : ComponentActivity() {
    private lateinit var home: HomeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = HomeController(this)
        home.start()
    }

    /** Вернулись из приложения: пересобираем список (могли что-то поставить или удалить). */
    override fun onResume() {
        super.onResume()
        home.onResume()
    }

    /** Кнопка Home, когда мы уже на экране: в начало, фокус на первое избранное. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) home.onHome()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::home.isInitialized && home.throttle(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
