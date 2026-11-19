package dev.lucudar.polka

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/**
 * Слой поверх View-главного: меню плитки, сетка выбора, «Все приложения» сеткой и настройки.
 * Те же Compose-экраны, что и раньше. Пока ничего не открыто, слой пустой и не рисует ни кадра.
 */
@Composable
fun OverlayHost(c: HomeController) {
    val settings = c.settings
    val wp = settings.wallpaper
    val accent = settings.accent
    val solid = settings.solidGlass
    val calm = settings.reduceMotion
    val wallDim = settings.brightness.dim
    val lite = c.lite
    val look = remember(accent, wp, solid, calm, wallDim, lite) {
        Look(
            accent = accent.color,
            onAccent = accent.on,
            glassTint = Palette.glassBase.copy(alpha = wp.tint),
            glassDense = Palette.glassBase.copy(alpha = 0.82f),
            solidGlass = solid,
            solidTone = wp.solidTone,
            reduceMotion = calm,
            wallDim = wallDim,
            lite = lite,
        )
    }
    val density = LocalDensity.current
    val edgeSpec = remember(density, calm, lite) { EdgeSpec(with(density) { Layout.row.toPx() }, calm, lite) }

    CompositionLocalProvider(
        LocalBackdrop provides c.backdrop,
        LocalLook provides look,
        LocalBringIntoViewSpec provides edgeSpec,
    ) {
        Box(Modifier.fillMaxSize()) {
            val menu = c.menu
            if (menu != null) {
                BackHandler { c.closeMenu(menu.focusKey) }
                AppMenu(
                    target = menu,
                    isFavorite = menu.app.pkg in c.favorites,
                    canMove = menu.focusKey.startsWith("row:") || menu.app.pkg in c.favorites,
                    onAction = { action -> c.onMenuAction(menu, action) },
                )
            }

            val prefix = c.pickerFor
            if (prefix != null) {
                val isFav = prefix == "fav"
                val row = settings.rows.firstOrNull { "row:${it.id}" == prefix }
                AppPicker(
                    title = "Добавить в «${if (isFav) "Избранное" else row?.title.orEmpty()}»",
                    apps = c.visibleApps(),
                    selected = if (isFav) c.favorites.toSet() else row?.apps.orEmpty().toSet(),
                    onToggle = { pkg -> c.toggleInPicker(prefix, pkg) },
                    onClosed = { c.closePicker(prefix) },
                )
            }

            if (c.drawerOpen) {
                AppPicker(
                    title = "Все приложения",
                    apps = c.visibleApps(),
                    selected = null,
                    onToggle = { pkg -> c.launchFromDrawer(pkg) },
                    onClosed = { c.closeDrawer() },
                )
            }

            if (c.settingsOpen) {
                SettingsPanel(
                    settings = settings,
                    apps = c.apps.orEmpty(),
                    net = c.net,
                    weather = c.weather,
                    updater = c.updater,
                    startApp = c.panelApp,
                    startUpdate = c.panelUpdate,
                    onRefreshWeather = { c.refreshWeather() },
                    onCheckUpdate = { c.checkUpdate() },
                    onInstallUpdate = { c.installUpdate() },
                    onResetFavorites = { c.onResetFavorites() },
                    onResetStats = { c.onResetStats() },
                    onClosed = { c.closeSettings() },
                )
            }
        }
    }
}
