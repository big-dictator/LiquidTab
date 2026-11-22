package io.github.offlineglass.hook.adapters.x

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser

/**
 * X 的底栏是全屏 ComposeView 内的 Compose 组件，无法通过 View.draw()
 * 捕获。这里用 X 官方矢量资源（res/drawable，资源名未混淆）的
 * pathData 自绘 5 个 tab 图标：未选中描边形（viewport 24）、选中
 * 填充形（Grok 为 42 viewport 的独立 filled 资源），配色跟随 X 自身
 * 的 light/dark 规范——X 不使用 accent 蓝高亮选中 tab。
 */
internal object XRenderer {
    const val TAB_COUNT = 5
    const val GROK_INDEX = 2
    private const val VIEWPORT = 24f
    private const val GROK_FILLED_VIEWPORT = 42f
    private const val ICON_DP = 25f
    private const val DARK_UNSELECTED = 0xFF71767B.toInt()
    private const val DARK_SELECTED = 0xFFE7E9EA.toInt()
    private const val LIGHT_UNSELECTED = 0xFF536471.toInt()
    private const val LIGHT_SELECTED = 0xFF0F1419.toInt()

    private val normalData = arrayOf(
        // home
        "M20 9.83782C20 9.33632 19.7493 8.86796 19.332 8.58978L12.832 4.25677C12.3282 3.92087 11.6718 3.92087 11.168 4.25677L4.66797 8.58978C4.25069 8.86796 4.00002 9.33632 4 9.83782V18.4999C4 19.3284 4.67157 19.9999 5.5 19.9999H8.5V16.4999C8.5 14.5669 10.067 12.9999 12 12.9999C13.933 12.9999 15.5 14.5669 15.5 16.4999V19.9999H18.5C19.3284 19.9999 20 19.3284 20 18.4999V9.83782ZM22 18.4999C22 20.4329 20.433 21.9999 18.5 21.9999H13.5V16.4999C13.5 15.6715 12.8284 14.9999 12 14.9999C11.1716 14.9999 10.5 15.6715 10.5 16.4999V21.9999H5.5C3.567 21.9999 2 20.4329 2 18.4999V9.83782C2.00002 8.66761 2.58492 7.57483 3.55859 6.92571L10.0586 2.59271C11.2342 1.80894 12.7658 1.80894 13.9414 2.59271L20.4414 6.92571C21.4151 7.57483 22 8.66761 22 9.83782V18.4999Z",
        // search
        "M10.75 1.75C15.7206 1.75 19.75 5.77944 19.75 10.75C19.75 12.8745 19.0111 14.827 17.7803 16.3662L22.207 20.793L20.793 22.207L16.3662 17.7803C14.827 19.0111 12.8745 19.75 10.75 19.75C5.77944 19.75 1.75 15.7206 1.75 10.75C1.75 5.77944 5.77944 1.75 10.75 1.75ZM10.75 3.75C6.88401 3.75 3.75 6.88401 3.75 10.75C3.75 14.616 6.88401 17.75 10.75 17.75C12.6833 17.75 14.4323 16.968 15.7002 15.7002C16.968 14.4323 17.75 12.6833 17.75 10.75C17.75 6.88401 14.616 3.75 10.75 3.75Z",
        // grok
        "M18 4.1H6C4.95066 4.1 4.1 4.95066 4.1 6V18C4.1 19.0493 4.95066 19.9 6 19.9H18C19.0493 19.9 19.9 19.0493 19.9 18V6C19.9 4.95066 19.0493 4.1 18 4.1ZM6 2H18C20.2091 2 22 3.79086 22 6V18C22 20.2091 20.2091 22 18 22H6C3.79086 22 2 20.2091 2 18V6C2 3.79086 3.79086 2 6 2ZM6.68002 17.7999L14.7884 6.21991H17.3198L9.21144 17.7999H6.68002Z",
        // notifications
        "M11.9961 2C16.0541 2.00023 19.4796 5.01658 19.9932 9.04199L21.1357 18H16.8994C16.4361 20.2822 14.4189 22 12 22C9.58108 22 7.5639 20.2822 7.10059 18H2.86523L3.99707 9.05078C4.50665 5.02131 7.93449 2 11.9961 2ZM9.17383 18C9.58594 19.1647 10.6941 20 12 20C13.3059 20 14.4141 19.1647 14.8262 18H9.17383ZM11.9961 4C8.94204 4 6.3646 6.27185 5.98145 9.30176L5.13477 16H18.8643L18.0088 9.29492C17.6225 6.26824 15.0473 4.00023 11.9961 4Z",
        // messages
        "M20.7004 11.7002C20.7003 7.22055 16.8556 3.5 12.0012 3.5C7.14681 3.50009 3.303 7.2206 3.30292 11.7002V11.7148L3.30194 11.7295C3.28234 12.3963 3.39287 12.9535 3.55292 13.4961C3.63524 13.7752 3.72808 14.0415 3.82831 14.335C3.92566 14.62 4.03016 14.9299 4.11639 15.2529C4.29333 15.9158 4.39994 16.654 4.27167 17.5244C4.186 18.1057 3.99753 18.7155 3.68964 19.3789C4.95427 19.7541 6.23958 19.4319 7.70331 18.7803L8.15839 18.5771L8.59491 18.8193C9.66443 19.4128 10.5124 19.8994 12.0012 19.8994C16.8556 19.8994 20.7002 16.1797 20.7004 11.7002ZM22.7004 11.7002C22.7002 17.3825 17.8593 21.8994 12.0012 21.8994C10.2165 21.8994 9.04164 21.3451 8.05194 20.8047C6.17501 21.573 4.03118 22.0047 1.80585 20.7305L0.921082 20.2246L1.44452 19.3496C1.98467 18.446 2.21412 17.7687 2.29315 17.2324C2.37066 16.706 2.31283 16.252 2.18378 15.7686C2.11706 15.5187 2.03319 15.2668 1.93573 14.9814C1.84106 14.7042 1.73206 14.3908 1.63495 14.0615C1.4361 13.3874 1.27526 12.612 1.30292 11.6709H1.30389C1.32058 6.00245 6.153 1.50009 12.0012 1.5C17.8595 1.5 22.7003 6.01792 22.7004 11.7002Z",
    )
    private val selectedData = arrayOf(
        // home (filled)
        "M10.0586 2.59271C11.2342 1.80894 12.7658 1.80894 13.9414 2.59271L20.4414 6.92571C21.4151 7.57483 22 8.66763 22 9.83782V18.4999C22 20.4329 20.433 21.9999 18.5 21.9999H14.25V16.7499C14.25 15.5073 13.2426 14.4999 12 14.4999C10.7574 14.4999 9.75 15.5073 9.75 16.7499V21.9999H5.5C3.56703 21.9999 2.00004 20.4329 2 18.4999V9.83782C2.00002 8.66763 2.58494 7.57483 3.55859 6.92571L10.0586 2.59271Z",
        // search (filled)
        "M10.75 1.5C15.8586 1.5 20 5.64137 20 10.75C20 12.853 19.2953 14.7921 18.1133 16.3457L22.3838 20.6162L20.6162 22.3838L16.3457 18.1133C14.7921 19.2953 12.853 20 10.75 20C5.64137 20 1.5 15.8586 1.5 10.75C1.5 5.64137 5.64137 1.5 10.75 1.5ZM10.75 4C7.02208 4 4 7.02208 4 10.75C4 14.4779 7.02208 17.5 10.75 17.5C12.6143 17.5 14.3006 16.7463 15.5234 15.5234C16.7463 14.3006 17.5 12.6143 17.5 10.75C17.5 7.02208 14.4779 4 10.75 4Z",
        // grok (filled) is GROK_FILLED_DATA, drawn at its own 42-viewport scale
        "",
        // notifications (filled, two subpaths)
        "M11.9993 2C16.0576 2.00031 19.4837 5.01663 19.9973 9.04199L21.0286 17.125H2.97778L3.99927 9.05078C4.50889 5.02131 7.9373 2 11.9993 2ZM15.9846 18.375C15.7956 20.4083 14.0849 22 12.0022 22C9.91952 22 8.20884 20.4083 8.01978 18.375H15.9846Z",
        // messages (filled)
        "M12.001 1.5C17.8593 1.5 22.7001 6.01792 22.7002 11.7002C22.7 17.3825 17.8591 21.8994 12.001 21.8994C10.2163 21.8994 9.04145 21.3451 8.05176 20.8047C6.17483 21.573 4.03099 22.0047 1.80566 20.7305L0.920898 20.2246L1.44434 19.3496C1.98449 18.446 2.21394 17.7687 2.29297 17.2324C2.37048 16.706 2.31264 16.252 2.18359 15.7686C2.11687 15.5187 2.033 15.2668 1.93555 14.9814C1.84088 14.7042 1.73188 14.3908 1.63477 14.0615C1.43592 13.3874 1.27508 12.612 1.30273 11.6709C1.31942 6.00245 6.15281 1.50009 12.001 1.5Z",
    )
    private const val GROK_FILLED_DATA =
        "M8,0C3.582,0 0,3.582 0,8v26c0,4.418 3.582,8 8,8h26c4.418,0 8,-3.582 8,-8L42,8c0,-4.418 -3.582,-8 -8,-8L8,0zM27.997,17.35l-11.1,8.19 15.9,-15.963v0.015L37.391,5c-0.082,0.117 -0.165,0.23 -0.248,0.345 -3.49,4.804 -5.194,7.153 -3.826,13.03l-0.009,-0.008c0.943,4.001 -0.065,8.438 -3.322,11.693 -4.106,4.107 -10.677,5.02 -16.087,1.324l3.772,-1.745c3.454,1.355 7.232,0.76 9.947,-1.954 2.716,-2.714 3.325,-6.666 1.96,-9.956 -0.259,-0.623 -1.037,-0.78 -1.58,-0.378zM14.705,14.776c-3.314,3.31 -3.983,9.047 -0.1,12.755l-0.003,0.003L4,37c0.663,-0.913 1.485,-1.776 2.306,-2.639l0.04,-0.042c2.346,-2.464 4.67,-4.906 3.25,-8.357 -1.903,-4.622 -0.795,-10.038 2.73,-13.56 3.664,-3.66 9.06,-4.583 13.568,-2.729 0.998,0.37 1.867,0.897 2.545,1.387l-3.764,1.737c-3.505,-1.47 -7.52,-0.47 -9.97,1.98z"

    private val normalPaths: List<Path> by lazy(LazyThreadSafetyMode.NONE) {
        normalData.map { PathParser.createPathFromPathData(it) }
    }
    private val selectedPaths: List<Path> by lazy(LazyThreadSafetyMode.NONE) {
        selectedData.map { data ->
            if (data.isEmpty()) Path() else PathParser.createPathFromPathData(data)
        }
    }
    private val grokFilledPath: Path by lazy(LazyThreadSafetyMode.NONE) {
        PathParser.createPathFromPathData(GROK_FILLED_DATA)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun draw(
        canvas: Canvas,
        extraScale: Float,
        slotCount: Int,
        width: Int,
        height: Int,
        slotWidth: Float,
        density: Float,
        iconScale: Float,
        selectedIndex: Int,
        enabledIndices: List<Int>,
        darkGlass: Boolean,
        collapse: Float = 0f,
        expandedWidth: Int = width,
        collapsedSize: Int = height,
        leadingAtEdge: Boolean = false,
    ) {
        if (slotCount != TAB_COUNT || width <= 0 || height <= 0 || slotWidth <= 0f) return
        val iconSize = ICON_DP * density * iconScale
        val centerY = height / 2f
        // slotWidth 由宿主 tabWidthPx 计算时扣了 8dp 余量，从 0 起铺会把
        // 余量全堆到右侧、图标组整体左偏 4dp；余量两侧均分保证图标组
        // 相对底栏上下左右整体居中。
        val groupLeft = (width - slotWidth * slotCount) / 2f
        for ((visualIndex, index) in enabledIndices.withIndex()) {
            if (index !in 0 until slotCount) continue
            val selected = index == selectedIndex
            val p = collapse.coerceIn(0f, 1f)
            val opacity = if (selected) 1f else (1f - p) * (1f - p)
            if (opacity <= 0.001f) continue
            val centerX = if (leadingAtEdge && selected && visualIndex == 0)
                XChromeCollapsePolicy.leadingCenter(expandedWidth.toFloat(), collapsedSize.toFloat(),
                    4f * density, slotCount, p)
                else if (p > 0f) XChromeCollapsePolicy.navigationCenter(
                expandedWidth.toFloat(), collapsedSize.toFloat(), 4f * density,
                visualIndex.toFloat(), slotCount, p)
                else groupLeft + (visualIndex + 0.5f) * slotWidth
            canvas.save()
            val sizeScale = extraScale * XChromeCollapsePolicy.mix(1f, if (selected) 0.88f else 0.3f, p)
            canvas.scale(sizeScale, sizeScale, centerX, centerY)
            iconPaint.color = when {
                darkGlass && selected -> DARK_SELECTED
                darkGlass -> DARK_UNSELECTED
                selected -> LIGHT_SELECTED
                else -> LIGHT_UNSELECTED
            }
            iconPaint.alpha = (android.graphics.Color.alpha(iconPaint.color) * opacity).toInt()
            canvas.save()
            canvas.translate(centerX - iconSize / 2f, centerY - iconSize / 2f)
            if (selected && index == GROK_INDEX) {
                canvas.scale(iconSize / GROK_FILLED_VIEWPORT, iconSize / GROK_FILLED_VIEWPORT)
                canvas.drawPath(grokFilledPath, iconPaint)
            } else {
                canvas.scale(iconSize / VIEWPORT, iconSize / VIEWPORT)
                canvas.drawPath(if (selected) selectedPaths[index] else normalPaths[index], iconPaint)
            }
            canvas.restore()
            canvas.restore()
        }
    }
}
