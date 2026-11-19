package dev.lucudar.polka

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.BringIntoViewSpec

/*
 * Прокрутка к фокусу. На ТВ Compose по умолчанию держит фокус на 30 % ширины (PivotBringIntoViewSpec).
 * В 0.4 это и давало дрожь: вертикальный список тянул к 30 % плитку, а ряд просил показать себя
 * целиком, и две цели спорили каждый кадр. Теперь у каждого списка одно правило и одна пружина.
 */

/** Критическое затухание: доезжает быстро и без перелёта. «Без анимаций»: почти мгновенно. */
fun scrollSpring(reduceMotion: Boolean, lite: Boolean = false): AnimationSpec<Float> = when {
    reduceMotion -> spring(dampingRatio = 1f, stiffness = 20_000f)
    // Лёгкий режим: ряд доезжает быстрее, тяжёлых кадров прокрутки меньше.
    lite -> spring(dampingRatio = 1f, stiffness = 700f)
    else -> spring(dampingRatio = 1f, stiffness = 420f)
}

/** Ряд плиток: фокус стоит в первом слоте, ряд едет под ним, как в Google TV. */
class PinnedSpec(private val pivot: Float, reduceMotion: Boolean, lite: Boolean = false) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = scrollSpring(reduceMotion, lite)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - pivot
}

/**
 * Лёгкий режим: ряд листается страницами. Пока плитка целиком на экране, ряд стоит, двигается
 * только фокус: такой кадр стоит стику около 14 мс. Дошли до края, и ряд одним движением
 * перелистывается так, что плитка в фокусе встаёт в первый слот (назад: в последний).
 * Замер 0.6.1: тормозили именно кадры прокрутки, а с «пином» ряд ехал на каждое нажатие.
 */
class PageSpec(private val edge: Float, reduceMotion: Boolean, lite: Boolean = true) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = scrollSpring(reduceMotion, lite)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            size > containerSize - 2 * edge -> offset - edge
            end > containerSize - edge + 0.5f -> offset - edge
            offset < edge - 0.5f -> end - (containerSize - edge)
            else -> 0f
        }
    }
}

/** Списки в панелях и сетка выбора: едем, только когда пункт подходит к краю, с полем под соседа. */
class EdgeSpec(private val margin: Float, reduceMotion: Boolean, lite: Boolean = false) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = scrollSpring(reduceMotion, lite)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val m = margin.coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
        val start = offset - m
        val end = offset + size + m - containerSize
        return when {
            start < 0f -> start
            end > 0f -> end.coerceAtMost(start)
            else -> 0f
        }
    }
}
