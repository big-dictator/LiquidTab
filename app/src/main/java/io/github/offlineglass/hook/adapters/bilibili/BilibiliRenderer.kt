package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame

/** Domestic Bilibili's five-tab artwork, including its centre publish control. */
internal object BilibiliRenderer {
    private const val ACCENT = 0xFFFB7299.toInt()
    private const val VIEWPORT = 1024f
    private const val PUBLISH_INDEX = 2
    private const val ICON_SCALE = 1.15f
    private const val SPACING_DP = 2f
    private val labels = arrayOf("首页", "动态", "", "会员购", "我的")
    private val normalData = arrayOf(
        "M884.36,463.97v265.26c0,123.35 -92.77,224.95 -209.45,224.95h-325.82c-116.69,0 -209.45,-101.61 -209.45,-224.95V535.27a46.55,46.55 0,0 1,93.09 0v193.95C232.73,802.91 285.79,861.09 349.09,861.09h325.82c63.3,0 116.36,-58.18 116.36,-131.86V386.42l-279.27,-232.73 -389.12,324.24a46.55,46.55 0,1 1,-59.58 -71.49l418.91,-349.09a46.55,46.55 0,0 1,59.58 0l418.91,349.09a46.55,46.55 0,1 1,-59.58 71.49l-16.76,-13.96zM395.64,756.36a34.91,34.91 0,0 1,0 -69.82h232.73a34.91,34.91 0,0 1,0 69.82h-232.73z",
        "M303.66,463.13a268.52,268.52 0,0 1,-62.18 -172.22c0,-149.13 121.16,-269.96 270.52,-269.96 27,0 48.87,21.88 48.87,48.87v234.45A269.78,269.78 0,0 1,733.65 242.04c149.41,0 270.52,120.83 270.52,269.96 0,27 -21.88,48.87 -48.87,48.87h-234.96a268.52,268.52 0,0 1,62.18 172.22c0,149.13 -121.16,269.96 -270.52,269.96 -27,0 -48.87,-21.88 -48.87,-48.87v-234.45A269.78,269.78 0,0 1,290.35 781.96C140.94,781.96 19.83,661.13 19.83,512c0,-27 21.88,-48.87 48.87,-48.87h234.96zM339.22,290.91A172.36,172.36 0,0 0,463.13 456.15L463.13,125.67a172.36,172.36 0,0 0,-123.9 165.24zM684.78,733.09a172.36,172.36 0,0 0,-123.9 -165.24v330.47a172.36,172.36 0,0 0,123.9 -165.24zM733.65,339.78a172.78,172.78 0,0 0,-165.7 123.35h331.4a172.78,172.78 0,0 0,-165.7 -123.35zM290.35,684.22a172.78,172.78 0,0 0,165.7 -123.35h-331.4a172.78,172.78 0,0 0,165.7 123.35z",
        "M295.42,183.85C315.44,85.64 399.36,11.64 500.36,11.64c101,0 184.93,74.01 204.94,172.22h73.26a56.04,56.04 0,0 1,1.26 0c78.89,0 144.9,59.86 151.88,137.68l47.34,479.19c9.17,102.35 -66.93,192.56 -169.75,201.59 -5.49,0.47 -11.03,0.74 -16.52,0L231.24,1003.05C128.05,1003.05 44.22,919.92 44.22,817.2c0,-5.49 0.23,-10.98 0.79,-16.94L92.25,322A152.16,152.16 0,0 1,244.18 183.85h51.25zM367.38,183.85h265.96c-18.11,-59.58 -71.03,-102.4 -132.98,-102.4 -61.95,0 -114.87,42.82 -132.98,102.4zM290.91,281.6L244.18,281.6c-28.49,0 -52.08,21.41 -54.6,49.57L142.29,809.43a86.95,86.95 0,0 0,-0.33 7.77c0,48.59 39.89,88.11 89.27,88.11h561.52c2.65,0 5.31,-0.14 7.91,-0.33 49.2,-4.38 85.32,-47.2 81.04,-95.09L834.37,330.75a54.41,54.41 0,0 0,-55.81 -49.15L709.82,281.6v20.95a34.91,34.91 0,0 1,-69.82 0v-20.95h-279.27v20.95a34.91,34.91 0,0 1,-69.82 0v-20.95zM512,379.9L560.92,372.36v53.9L721.45,388.19L721.45,791.27h-47.38l-13.17,-351.14 -125.44,60.23L512,500.36L512,379.9zM567.67,535.27h64.33l-19.18,83.97L651.64,619.24L598.34,721.45v-76.57L558.55,644.89l9.12,-109.61zM294.03,407.6L477.14,372.36l11.59,256L438.55,628.36L438.55,445.35l-109.71,24.39v118.78L279.27,588.52l14.75,-180.92zM361.98,535.27h33.61L410.53,651.64 512,700.23l-16.8,67.77 -99.61,-67.77L395.59,768L349.09,768l12.89,-232.73z",
        "M298.03,209.45L198.52,118.92a34.91,34.91 0,1 1,46.96 -51.67L401.83,209.45h206.38l160.35,-142.43a34.91,34.91 0,1 1,46.36 52.13L713.31,209.45L768,209.45a232.73,232.73 0,0 1,232.73 232.73v290.91a232.73,232.73 0,0 1,-232.73 232.73h-512a232.73,232.73 0,0 1,-232.73 -232.73L23.27,442.18a232.73,232.73 0,0 1,232.73 -232.73h42.03zM256,302.55a139.64,139.64 0,0 0,-139.64 139.64v290.91a139.64,139.64 0,0 0,139.64 139.64h512a139.64,139.64 0,0 0,139.64 -139.64L907.64,442.18a139.64,139.64 0,0 0,-139.64 -139.64h-512zM232.73,604.72a34.91,34.91 0,0 1,0 -69.82h186.18a34.91,34.91 0,0 1,0 69.82L232.73,604.72zM605.09,605.98a34.91,34.91 0,1 1,0 -69.82h186.18a34.91,34.91 0,0 1,0 69.82h-186.18z",
    )
    private val selectedData = arrayOf(
        "M799.19,392.98L512,153.69l-389.12,324.24a46.55,46.55 0,1 1,-59.58 -71.49l418.91,-349.09a46.55,46.55 0,0 1,59.58 0l418.91,349.09a46.55,46.55 0,1 1,-59.58 71.49l-16.76,-13.96v311.81c0,123.35 -92.77,224.95 -209.45,224.95h-325.82c-116.69,0 -209.45,-101.61 -209.45,-224.95L139.64,581.82a46.55,46.55 0,0 1,93.09 0v193.95C232.73,849.45 285.79,907.64 349.09,907.64h325.82c63.3,0 116.36,-58.18 116.36,-131.86L791.27,418.91c0,-9.59 2.89,-18.53 7.91,-25.93zM597.74,681.38a34.91,34.91 0,1 1,61.25 33.61c-31.65,57.62 -82.11,87.92 -146.99,87.92s-115.34,-30.25 -146.99,-87.97a34.91,34.91 0,0 1,61.25 -33.51c19.41,35.37 46.55,51.67 85.74,51.67 39.24,0 66.33,-16.29 85.74,-51.67z",
        "M303.66,463.13a268.52,268.52 0,0 1,-62.18 -172.22c0,-149.13 121.16,-269.96 270.52,-269.96 27,0 48.87,21.88 48.87,48.87v234.45A269.78,269.78 0,0 1,733.65 242.04c149.41,0 270.52,120.83 270.52,269.96 0,27 -21.88,48.87 -48.87,48.87h-234.96a268.52,268.52 0,0 1,62.18 172.22c0,149.13 -121.16,269.96 -270.52,269.96 -27,0 -48.87,-21.88 -48.87,-48.87v-234.45A269.78,269.78 0,0 1,290.35 781.96C140.94,781.96 19.83,661.13 19.83,512c0,-27 21.88,-48.87 48.87,-48.87h234.96zM339.22,290.91A172.36,172.36 0,0 0,463.13 456.15L463.13,125.67a172.36,172.36 0,0 0,-123.9 165.24zM684.78,733.09a172.36,172.36 0,0 0,-123.9 -165.24v330.47a172.36,172.36 0,0 0,123.9 -165.24zM733.65,339.78a172.78,172.78 0,0 0,-165.7 123.35h331.4a172.78,172.78 0,0 0,-165.7 -123.35zM290.35,684.22a172.78,172.78 0,0 0,165.7 -123.35h-331.4a172.78,172.78 0,0 0,165.7 123.35z",
        "M775.59,161.65L221.7,161.65a24.67,24.67 0,0 0,-18.8 40.63,45.29 45.29,0 0,1 -12.94,69.03 26.39,26.39 0,0 0,12.61 49.62h597.41a25.51,25.51 0,0 0,25.23 -21.74,45.15 45.15,0 0,1 0.28,-4.56 25.51,25.51 0,0 0,-14.43 -22.25,45.29 45.29,0 0,1 -11.54,-73.77 21.41,21.41 0,0 0,-14.62 -36.96h-9.31zM916.06,299.24l54.27,482.16c8.84,101.79 -65.07,192.05 -165.66,201.17 -5.4,0.47 -10.8,0.74 -16.24,0L230.35,983.32c-101,0 -182.55,-83.32 -182.55,-185.58 0,-5.45 0.23,-10.89 0.7,-15.92l37.24,-482.26a116.97,116.97 0,0 1,25.97 -78.66,115.29 115.29,0 0,1 109.99,-149.83h563.2a111.94,111.94 0,0 1,104.96 150.81c17.73,21.74 27.09,49.34 26.21,77.36zM168.31,406.39l-29.56,382.88c-0.23,2.79 -0.37,5.63 -0.37,8.47 0,52.69 41.43,95 92.02,95h558.03c2.7,0 5.4,-0.09 8.05,-0.37 50.5,-4.56 88.16,-50.59 83.69,-101.93l-43.29,-384.93a115.94,115.94 0,0 1,-36.91 6L202.57,411.51c-11.92,0 -23.41,-1.82 -34.26,-5.12zM709.26,518.28v2.33c0,124.51 -91.09,226.91 -205.59,226.91s-205.59,-102.4 -205.59,-226.86v-2.37a33.98,33.98 0,1 1,67.96 0v2.33c0,88.62 62.56,158.95 137.63,158.95 75.08,0 137.68,-70.28 137.68,-158.91v-2.37a33.98,33.98 0,1 1,67.96 0z",
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

    fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        with(frame) {
            if (slotCount != 5 || width <= 0 || height <= 0) return
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
                if (index !in 0..4) continue
                val centerX = horizontalPadding + (visualIndex + 0.5f) * slotWidth
                canvas.save()
                canvas.scale(extraScale, extraScale, centerX, height / 2f)
                if (index == PUBLISH_INDEX) {
                    val half = 20f * density
                    val publishCenterY = height / 2f
                    val publishRect = RectF(centerX - half, publishCenterY - half, centerX + half, publishCenterY + half)
                    iconPaint.color = ACCENT
                    canvas.drawRoundRect(publishRect, 12f * density, 12f * density, iconPaint)
                    iconPaint.color = Color.WHITE
                    iconPaint.strokeWidth = 3.2f * density
                    iconPaint.strokeCap = Paint.Cap.ROUND
                    iconPaint.style = Paint.Style.STROKE
                    canvas.drawLine(centerX - 7f * density, publishCenterY, centerX + 7f * density, publishCenterY, iconPaint)
                    canvas.drawLine(centerX, publishCenterY - 7f * density, centerX, publishCenterY + 7f * density, iconPaint)
                    iconPaint.style = Paint.Style.FILL
                    canvas.restore()
                    continue
                }
                val pathIndex = if (index < PUBLISH_INDEX) index else index - 1
                val selected = index == selectedIndex
                val path = if (selected) selectedPaths[pathIndex] else normalPaths[pathIndex]
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
}
