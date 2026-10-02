package io.github.offlineglass.hook.adapters.bilibili_in

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.PathParser

/** International Bilibili owns its vector artwork and tab layout independently of the CN app. */
internal object BilibiliInRenderer {
    private const val VIEWPORT = 1024f
    private const val ICON_SCALE = 1.15f
    private const val SPACING_DP = 2f
    private const val ACCENT = 0xFFFB7299.toInt()
    private val labels = arrayOf("首页", "关注", "消息", "我的")
    private val normalData = arrayOf(
        "M884.36,463.97v265.26c0,123.35 -92.77,224.95 -209.45,224.95h-325.82c-116.69,0 -209.45,-101.61 -209.45,-224.95V535.27a46.55,46.55 0,0 1,93.09 0v193.95C232.73,802.91 285.79,861.09 349.09,861.09h325.82c63.3,0 116.36,-58.18 116.36,-131.86V386.42l-279.27,-232.73 -389.12,324.24a46.55,46.55 0,1 1,-59.58 -71.49l418.91,-349.09a46.55,46.55 0,0 1,59.58 0l418.91,349.09a46.55,46.55 0,1 1,-59.58 71.49l-16.76,-13.96zM395.64,756.36a34.91,34.91 0,0 1,0 -69.82h232.73a34.91,34.91 0,0 1,0 69.82h-232.73z",
        "M303.66,463.13a268.52,268.52 0,0 1,-62.18 -172.22c0,-149.13 121.16,-269.96 270.52,-269.96 27,0 48.87,21.88 48.87,48.87v234.45A269.78,269.78 0,0 1,733.65 242.04c149.41,0 270.52,120.83 270.52,269.96 0,27 -21.88,48.87 -48.87,48.87h-234.96a268.52,268.52 0,0 1,62.18 172.22c0,149.13 -121.16,269.96 -270.52,269.96 -27,0 -48.87,-21.88 -48.87,-48.87v-234.45A269.78,269.78 0,0 1,290.35 781.96C140.94,781.96 19.83,661.13 19.83,512c0,-27 21.88,-48.87 48.87,-48.87h234.96zM339.22,290.91A172.36,172.36 0,0 0,463.13 456.15L463.13,125.67a172.36,172.36 0,0 0,-123.9 165.24zM684.78,733.09a172.36,172.36 0,0 0,-123.9 -165.24v330.47a172.36,172.36 0,0 0,123.9 -165.24zM733.65,339.78a172.78,172.78 0,0 0,-165.7 123.35h331.4a172.78,172.78 0,0 0,-165.7 -123.35zM290.35,684.22a172.78,172.78 0,0 0,165.7 -123.35h-331.4a172.78,172.78 0,0 0,165.7 123.35z",
        "M236.62,183.85H787.38a139.64,139.64 0,0 1,139.64 139.64V677.94a139.64,139.64 0,0 1,-139.64 139.64H558.55L349.09,977.31V817.58H236.62a139.64,139.64 0,0 1,-139.64 -139.64V323.49a139.64,139.64 0,0 1,139.64 -139.64zM329.03,488.73a55.85,55.85 0,0 0,111.7 0a55.85,55.85 0,0 0,-111.7 0zM583.27,488.73a55.85,55.85 0,0 0,111.7 0a55.85,55.85 0,0 0,-111.7 0z",
        "M298.03,209.45L198.52,118.92a34.91,34.91 0,1 1,46.96 -51.67L401.83,209.45h206.38l160.35,-142.43a34.91,34.91 0,1 1,46.36 52.13L713.31,209.45L768,209.45a232.73,232.73 0,0 1,232.73 232.73v290.91a232.73,232.73 0,0 1,-232.73 232.73h-512a232.73,232.73 0,0 1,-232.73 -232.73L23.27,442.18a232.73,232.73 0,0 1,232.73 -232.73h42.03zM256,302.55a139.64,139.64 0,0 0,-139.64 139.64v290.91a139.64,139.64 0,0 0,139.64 139.64h512a139.64,139.64 0,0 0,139.64 -139.64L907.64,442.18a139.64,139.64 0,0 0,-139.64 -139.64h-512zM232.73,604.72a34.91,34.91 0,0 1,0 -69.82h186.18a34.91,34.91 0,0 1,0 69.82L232.73,604.72zM605.09,605.98a34.91,34.91 0,1 1,0 -69.82h186.18a34.91,34.91 0,0 1,0 69.82h-186.18z",
    )
    private val selectedData = arrayOf(
        "M799.19,392.98L512,153.69l-389.12,324.24a46.55,46.55 0,1 1,-59.58 -71.49l418.91,-349.09a46.55,46.55 0,0 1,59.58 0l418.91,349.09a46.55,46.55 0,1 1,-59.58 71.49l-16.76,-13.96v311.81c0,123.35 -92.77,224.95 -209.45,224.95h-325.82c-116.69,0 -209.45,-101.61 -209.45,-224.95L139.64,581.82a46.55,46.55 0,0 1,93.09 0v193.95C232.73,849.45 285.79,907.64 349.09,907.64h325.82c63.3,0 116.36,-58.18 116.36,-131.86L791.27,418.91c0,-9.59 2.89,-18.53 7.91,-25.93zM597.74,681.38a34.91,34.91 0,1 1,61.25 33.61c-31.65,57.62 -82.11,87.92 -146.99,87.92s-115.34,-30.25 -146.99,-87.97a34.91,34.91 0,0 1,61.25 -33.51c19.41,35.37 46.55,51.67 85.74,51.67 39.24,0 66.33,-16.29 85.74,-51.67z",
        "M303.66,463.13a268.52,268.52 0,0 1,-62.18 -172.22c0,-149.13 121.16,-269.96 270.52,-269.96 27,0 48.87,21.88 48.87,48.87v234.45A269.78,269.78 0,0 1,733.65 242.04c149.41,0 270.52,120.83 270.52,269.96 0,27 -21.88,48.87 -48.87,48.87h-234.96a268.52,268.52 0,0 1,62.18 172.22c0,149.13 -121.16,269.96 -270.52,269.96 -27,0 -48.87,-21.88 -48.87,-48.87v-234.45A269.78,269.78 0,0 1,290.35 781.96C140.94,781.96 19.83,661.13 19.83,512c0,-27 21.88,-48.87 48.87,-48.87h234.96zM339.22,290.91A172.36,172.36 0,0 0,463.13 456.15L463.13,125.67a172.36,172.36 0,0 0,-123.9 165.24zM684.78,733.09a172.36,172.36 0,0 0,-123.9 -165.24v330.47a172.36,172.36 0,0 0,123.9 -165.24zM733.65,339.78a172.78,172.78 0,0 0,-165.7 123.35h331.4a172.78,172.78 0,0 0,-165.7 -123.35zM290.35,684.22a172.78,172.78 0,0 0,165.7 -123.35h-331.4a172.78,172.78 0,0 0,165.7 123.35z",
        "M236.62,183.85H787.38a139.64,139.64 0,0 1,139.64 139.64V677.94a139.64,139.64 0,0 1,-139.64 139.64H558.55L349.09,977.31V817.58H236.62a139.64,139.64 0,0 1,-139.64 -139.64V323.49a139.64,139.64 0,0 1,139.64 -139.64zM329.03,488.73a55.85,55.85 0,0 0,111.7 0a55.85,55.85 0,0 0,-111.7 0zM583.27,488.73a55.85,55.85 0,0 0,111.7 0a55.85,55.85 0,0 0,-111.7 0z",
        "M298.03,209.45L198.52,118.92a34.91,34.91 0,1 1,46.96 -51.67L401.83,209.45h206.38l160.35,-142.43a34.91,34.91 0,1 1,46.36 52.13L713.31,209.45L768,209.45a232.73,232.73 0,0 1,232.73 232.73v290.91a232.73,232.73 0,0 1,-232.73 232.73h-512a232.73,232.73 0,0 1,-232.73 -232.73L23.27,442.18a232.73,232.73 0,0 1,232.73 -232.73h42.03zM348.72,512a34.91,34.91 0,0 1,69.82 0v175.57a34.91,34.91 0,1 1,-69.82 0L348.72,512zM627.99,512a34.91,34.91 0,0 1,69.82 0v175.57a34.91,34.91 0,1 1,-69.82 0L627.99,512zM256,302.55a139.64,139.64 0,0 0,-139.64 139.64v290.91a139.64,139.64 0,0 0,139.64 139.64h512a139.64,139.64 0,0 0,139.64 -139.64L907.64,442.18a139.64,139.64 0,0 0,-139.64 -139.64h-512z",
    )
    private val normalPaths: List<Path> by lazy(LazyThreadSafetyMode.NONE) {
        normalData.map { PathParser.createPathFromPathData(it) ?: Path() }
    }
    private val selectedPaths: List<Path> by lazy(LazyThreadSafetyMode.NONE) {
        selectedData.map { PathParser.createPathFromPathData(it) ?: Path() }
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun draw(
        canvas: Canvas,
        extraScale: Float,
        slotCount: Int,
        width: Int,
        height: Int,
        slotWidth: Float,
        density: Float,
        iconScale: Float,
        textSize: Float,
        scaledDensity: Float,
        selectedIndex: Int,
        enabledIndices: List<Int>,
        iconOnly: Boolean,
        darkGlass: Boolean,
    ) {
        if (slotCount != 4 || width <= 0 || height <= 0) return
        val horizontalPadding = 4f * density
        val iconSize = 23f * density * iconScale * ICON_SCALE
        val baseIconCenterY = (25f - SPACING_DP) * density
        val baseLabelBaseline = (48f + SPACING_DP) * density
        val unselectedColor = if (darkGlass) Color.rgb(205, 203, 210) else Color.rgb(44, 44, 44)
        labelPaint.textSize = textSize * scaledDensity
        val groupTop = baseIconCenterY - iconSize / 2f
        val groupBottom = baseLabelBaseline + labelPaint.fontMetrics.bottom
        val groupOffsetY = height / 2f - (groupTop + groupBottom) / 2f
        val iconCenterY = baseIconCenterY + groupOffsetY
        val labelBaseline = baseLabelBaseline + groupOffsetY
        for ((visualIndex, index) in enabledIndices.withIndex()) {
            if (index !in 0..3) continue
            val centerX = horizontalPadding + (visualIndex + 0.5f) * slotWidth
            canvas.save()
            canvas.scale(extraScale, extraScale, centerX, height / 2f)
            val selected = index == selectedIndex
            val path = if (selected) selectedPaths[index] else normalPaths[index]
            iconPaint.color = if (selected) ACCENT else unselectedColor
            canvas.save()
            canvas.translate(centerX - iconSize / 2f, iconCenterY - iconSize / 2f)
            canvas.scale(iconSize / VIEWPORT, iconSize / VIEWPORT)
            canvas.drawPath(path, iconPaint)
            canvas.restore()
            if (!iconOnly) {
                labelPaint.color = if (selected) ACCENT else unselectedColor
                canvas.drawText(labels[index], centerX, labelBaseline, labelPaint)
            }
            canvas.restore()
        }
    }
}
