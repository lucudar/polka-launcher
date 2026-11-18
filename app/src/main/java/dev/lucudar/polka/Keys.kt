package dev.lucudar.polka

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import android.view.KeyEvent as NativeKeyEvent

fun isSelectKey(code: Int): Boolean =
    code == NativeKeyEvent.KEYCODE_DPAD_CENTER || code == NativeKeyEvent.KEYCODE_ENTER ||
        code == NativeKeyEvent.KEYCODE_NUMPAD_ENTER || code == NativeKeyEvent.KEYCODE_BUTTON_A

/**
 * OK на пульте: короткое нажатие = действие, удержание или кнопка «Меню» = меню.
 * Клик срабатывает только после своего KeyDown, поэтому отпускание OK после
 * удержания не нажимает пункт меню, который успел получить фокус.
 */
fun Modifier.selectKeys(
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onPressed: (Boolean) -> Unit = {},
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    this.onKeyEvent { event ->
        val native = event.nativeKeyEvent
        val code = native.keyCode
        when {
            isSelectKey(code) && event.type == KeyEventType.KeyDown -> {
                if (native.repeatCount == 0) {
                    pressed = true
                    longFired = false
                    onPressed(true)
                } else if (pressed && !longFired && onLongPress != null) {
                    longFired = true
                    onPressed(false)
                    onLongPress()
                }
                true
            }
            isSelectKey(code) && event.type == KeyEventType.KeyUp -> {
                val fire = pressed && !longFired
                pressed = false
                longFired = false
                onPressed(false)
                if (fire) onClick()
                true
            }
            code == NativeKeyEvent.KEYCODE_MENU && event.type == KeyEventType.KeyUp && onLongPress != null -> {
                onLongPress()
                true
            }
            else -> false
        }
    }
}

/** FocusRequester на каждую плитку по ключу «ряд:пакет», чтобы вернуть фокус после меню. */
class FocusRegistry {
    private val map = HashMap<String, FocusRequester>()

    operator fun get(key: String): FocusRequester = map.getOrPut(key) { FocusRequester() }

    fun request(key: String?): Boolean {
        val requester = key?.let { map[it] } ?: return false
        return runCatching { requester.requestFocus() }.isSuccess
    }

    suspend fun requestWithRetry(key: String?) {
        if (key == null) return
        withFrameNanos { }
        repeat(20) {
            if (request(key)) return
            delay(40)
        }
    }
}
