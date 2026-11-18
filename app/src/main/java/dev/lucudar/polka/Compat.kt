package dev.lucudar.polka

/** Запасной фокус после «Скрыть»: первое избранное или первое из всех приложений. */
internal fun firstKey(visible: List<AppEntry>, favs: List<AppEntry>): String? =
    favs.firstOrNull()?.let { "fav:${it.pkg}" } ?: visible.firstOrNull()?.let { "all:${it.pkg}" }
