package dev.lucudar.polka

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private var homeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeScreen(resumeTick = resumeTick, homeTick = homeTick) }
        // Обои непрозрачные и на весь экран: чёрный фон окна под ними был лишним полноэкранным проходом.
        window.setBackgroundDrawable(null)
    }

    /** Вернулись из приложения: пересобираем список (могли что-то поставить или удалить). */
    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    /** Кнопка Home, когда мы уже на экране: в начало, фокус на первое избранное. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeTick++
    }
}
