package io.github.offlineglass.hook.adapters.home_glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.setBottomBarSquircle
import kotlin.math.roundToInt

internal class HomePanelHalo(context: Context) : View(context) {
        private data class Entry(val node: RenderNode, var signature: String = "")
        private val entries = Array(4) { Entry(RenderNode("BiliHomeHalo$it")) }
        private val shape = Path()
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var hosts: List<GlassHostLayout> = emptyList()
        private var smoothing = .5f
        private var isDark = false
        private var enabled = false
        private var bindingSignature = Long.MIN_VALUE
        private val matrixValues = FloatArray(9)

        init { isClickable = false }

        fun bind(hosts: List<GlassHostLayout>, smoothing: Float, dark: Boolean, enabled: Boolean) {
            var signature = smoothing.toBits().toLong()
            signature = signature * 31 + if (dark) 1 else 0
            signature = signature * 31 + if (enabled) 1 else 0
            hosts.forEach { host ->
                signature = signature * 31 + host.left
                signature = signature * 31 + host.top
                signature = signature * 31 + host.width
                signature = signature * 31 + host.height
                signature = signature * 31 + host.visibility
                host.matrix.getValues(matrixValues)
                matrixValues.forEach { signature = signature * 31 + it.toBits() }
            }
            this.hosts = hosts
            this.smoothing = smoothing
            this.isDark = dark
            this.enabled = enabled
            visibility = if (enabled && dark) View.VISIBLE else View.GONE
            if (bindingSignature != signature) { bindingSignature = signature; invalidate() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!enabled || !isDark || !canvas.isHardwareAccelerated) return
            val density = resources.displayMetrics.density
            hosts.take(4).forEachIndexed { index, host ->
                if (!host.isShown || host.width <= 0 || host.height <= 0) return@forEachIndexed
                val w = host.width; val h = host.height
                val entry = entries[index]
                val key = "$w/$h/$smoothing/$isDark"
                if (entry.signature != key) {
                    val pad = (24f * density).roundToInt()
                    entry.node.setPosition(-pad, -pad, w + pad, h + pad)
                    val recording = entry.node.beginRecording(w + pad * 2, h + pad * 2)
                    recording.translate(pad.toFloat(), pad.toFloat())
                    rect.set(1.25f * density, 1.25f * density, w - 1.25f * density, h - 1.25f * density)
                    shape.setBottomBarSquircle(rect, minOf(w, h) * .47f, smoothing)
                    paint.style = Paint.Style.FILL
                    paint.color = 0x52FFFFFF
                    recording.drawPath(shape, paint)
                    entry.node.endRecording()
                    val blur = 8f * density
                    entry.node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
                    entry.signature = key
                }
                val save = canvas.save()
                canvas.translate(host.left.toFloat(), host.top.toFloat())
                canvas.concat(host.matrix)
                // Keep the glow outside the button silhouette, so it cannot tint the glass interior.
                rect.set(0f, 0f, w.toFloat(), h.toFloat())
                shape.setBottomBarSquircle(rect, minOf(w, h) * .5f, smoothing)
                canvas.clipOutPath(shape)
                canvas.drawRenderNode(entry.node)
                canvas.restoreToCount(save)
            }
        }
    }

