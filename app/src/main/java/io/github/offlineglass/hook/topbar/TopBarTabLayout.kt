package io.github.offlineglass.hook.topbar

import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.roundToInt
import kotlin.math.ceil

/** Immutable slot proportions. Drawing, hit tests and lens geometry share these edges. */
internal class TopBarTabLayout private constructor(private val edges: FloatArray,
    val desiredWidth: Float, val preferredWidths: List<Float>, private val contentWidth: Float) {
    val size get() = edges.size - 1
    fun wrapWidth(available: Int): Int = minOf(ceil(desiredWidth).toInt(), available.coerceAtLeast(1))
    // Don't shrink text just because the optional breathing room cannot fit.
    fun fitScale(width: Int): Float = (width / contentWidth).coerceIn(.01f, 1f)
    fun fraction(left: Float): Float {
        val position = (left * size).coerceIn(0f, size.toFloat())
        val index = position.toInt().coerceAtMost(size - 1)
        return edges[index] + (edges[index + 1] - edges[index]) * (position - index)
    }
    fun edge(index: Int, width: Int, pad: Float): Float =
        pad + (width - 2 * pad).coerceAtLeast(1f) * edges[index]

    fun slotPixels(index: Int, width: Int, pad: Float): Int =
        edge(index + 1, width, pad).roundToInt() - edge(index, width, pad).roundToInt()

    fun center(index: Int, width: Int, pad: Float): Float =
        (edge(index, width, pad) + edge(index + 1, width, pad)) / 2f

    fun toUniform(x: Float, width: Int, pad: Float): Float {
        val available = (width - 2 * pad).coerceAtLeast(1f)
        val u = ((x - pad) / available).coerceIn(0f, 1f)
        var i = 0
        while (i < size - 1 && u >= edges[i + 1]) i++
        return pad + available / size * (i + (u - edges[i]) / (edges[i + 1] - edges[i]))
    }

    fun indicator(center: Float, slot: Float, width: Int, pad: Float): Pair<Float, Float> {
        val position = ((center - pad) / slot - .5f).coerceIn(0f, (size - 1).toFloat())
        val index = position.toInt()
        val next = (index + 1).coerceAtMost(size - 1)
        val mix = position - index
        val span = (width - 2 * pad).coerceAtLeast(1f)
        val a = edges[index + 1] - edges[index]
        val b = edges[next + 1] - edges[next]
        val from = edges[index] + a * .5f
        val to = edges[next] + b * .5f
        return (pad + span * (from + (to - from) * mix)) to (span * (a + (b - a) * mix))
    }

    fun sameSlots(other: TopBarTabLayout): Boolean = desiredWidth == other.desiredWidth &&
        contentWidth == other.contentWidth && edges.contentEquals(other.edges)

    companion object {
        const val TITLE_SLOT_WIDTH_SCALE = 1.2f
        internal fun titleSlotWidth(textWidth: Float, density: Float): Float =
            maxOf(36f * density, textWidth + 16f * density)

        /** Null titles are icon/action slots, not text measured as empty space. */
        fun measure(paint: Paint, titles: List<String?>, density: Float, textPx: Float): TopBarTabLayout {
            paint.textSize = textPx
            val widths = FloatArray(titles.size) { i ->
                val text = titles[i]
                if (text == null) 32f * density else {
                    paint.typeface = Typeface.DEFAULT
                    val normal = paint.measureText(text)
                    paint.typeface = Typeface.DEFAULT_BOLD
                    titleSlotWidth(maxOf(normal, paint.measureText(text)), density)
                }
            }
            val preferred = FloatArray(widths.size) { index ->
                widths[index] * if (titles[index] == null) 1f else TITLE_SLOT_WIDTH_SCALE
            }
            return fromWidths(preferred, 4f * density, widths.sum())
        }

        internal fun fromWidths(widths: FloatArray, pad: Float, minimumContentSpan: Float = widths.sum()): TopBarTabLayout {
            require(widths.isNotEmpty() && widths.all { it.isFinite() && it > 0f })
            val total = widths.sum()
            val edges = FloatArray(widths.size + 1)
            for (i in widths.indices) edges[i + 1] = edges[i] + widths[i] / total
            edges[edges.lastIndex] = 1f
            return TopBarTabLayout(edges, total + 2f * pad, widths.toList(), minimumContentSpan + 2f * pad)
        }
    }
}

internal class TopBarTabMeasure {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var titles = emptyList<String?>()
    private var density = 0f
    private var textPx = 0f
    private var layout: TopBarTabLayout? = null

    fun update(titles: List<String?>, density: Float, textPx: Float): TopBarTabLayout? {
        if (titles.isEmpty()) return null
        if (this.titles != titles || this.density != density || this.textPx != textPx) {
            this.titles = titles.toList()
            this.density = density
            this.textPx = textPx
            layout = TopBarTabLayout.measure(paint, titles, density, textPx)
        }
        return layout
    }
}
