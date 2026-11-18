package dev.lucudar.polka

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Иконки из Material Icons (Apache 2.0) прямо путями: в material-icons-core нужных нет
 * или они помечены устаревшими, а тащить extended ради трёх иконок дорого.
 */
private fun materialIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(path),
        fill = SolidColor(Color.White),
    ).build()

val WifiIcon: ImageVector by lazy {
    materialIcon(
        "Wifi",
        "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9z" +
            "M9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0z" +
            "M5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
    )
}

val CheckIcon: ImageVector by lazy {
    materialIcon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
}

val ChevronLeftIcon: ImageVector by lazy {
    materialIcon("ChevronLeft", "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z")
}

val ChevronIcon: ImageVector by lazy {
    materialIcon("Chevron", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
}
