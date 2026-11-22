package io.github.offlineglass.hook.adapters.bilibili_ambient


import android.app.Activity
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.*
import android.os.SystemClock


import android.view.*

import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Native render capability + Android lifecycle; no private player symbols or renderer replacement. */
internal class BilibiliVideoAmbientEngine(private val targetPackage: String, private val isEnabled: (io.github.offlineglass.config.GlassConfig) -> Boolean, private val diagnosticFile: String) {
    private val DETAILS = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    private val sessions = WeakHashMap<Activity, Session>()
    private val foreground = WeakHashMap<Activity, Boolean>()

    fun install(loader: ClassLoader) {
        val layer = loader.loadClass("tv.danmaku.render.core.IVideoRenderLayer")
        check(layer.isInterface && layer.getMethod("getView").returnType == View::class.java &&
            layer.getMethod("getBounds").returnType == Rect::class.java &&
            layer.getMethod("getVideoWidth").returnType == Int::class.javaPrimitiveType &&
            layer.getMethod("getVideoHeight").returnType == Int::class.javaPrimitiveType)
        for (event in listOf("onPostResume", "onPause", "onDestroy")) {
            XposedBridge.hookAllMethods(Activity::class.java, event, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.packageName != targetPackage || activity.javaClass.name != DETAILS) return
                    when (event) {
                        "onPostResume" -> {
                            foreground[activity] = true
                            HookConfigReader.readFreshAsync(activity, activity.packageName) { config ->
                                if (foreground[activity] != true || activity.isDestroyed) return@readFreshAsync
                                if (config?.enabled == true && isEnabled(config)) {
                                    sessions.getOrPut(activity) { Session(activity, layer, diagnosticFile) }.resume()
                                } else sessions.remove(activity)?.dispose()
                            }
                        }
                        "onPause" -> { foreground[activity] = false; sessions[activity]?.pause() }
                        else -> { foreground.remove(activity); sessions.remove(activity)?.dispose() }
                    }
                }
            })
        }
    }

    private class Session(val activity: Activity, val layer: Class<*>, val diagnosticFile: String) : ViewTreeObserver.OnGlobalLayoutListener {
        private val decor = activity.window.decorView as ViewGroup
        private var source: View? = null
        private var active = false
        private var disposed = false
        private var listening = false
        private var lastBind = -1000L
        private var colorOwned = false
        private var originalColor = 0
        private var originalContrast = false
        private var diagnostic = ""
        private val visible = Rect()
        private val renderBounds = layer.getMethod("getBounds")
        private val surfaceAmbient = BilibiliAmbientSurface { onGlobalLayout() }
        private var thumbnailTickScheduled = false
        private val thumbnailTick = Runnable {
            thumbnailTickScheduled = false
            onGlobalLayout()
        }
        private val surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { decor.post { onGlobalLayout() } }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                decor.post { onGlobalLayout() }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceAmbient.close()
                releaseColor()
            }
        }
        private val strip = object : View(activity) {
            private val scene = RenderNode("Bilibili status ambient")
            private var blurRadius = -1f
            private val downsample = .25f
            private val thumbnailPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            private val thumbnailDestination = RectF()
            private var current: Bitmap? = null
            private var outgoing: Bitmap? = null
            private var blend = 1f
            private var transition: ValueAnimator? = null

            fun present(frame: Bitmap?) {
                if (current === frame) return
                transition?.cancel()
                outgoing = if (frame != null) current else null
                current = frame
                blend = 1f
                if (outgoing != null) {
                    blend = 0f
                    transition = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 420L
                        addUpdateListener {
                            blend = it.animatedValue as Float
                            if (blend == 1f) outgoing = null
                            invalidate()
                        }
                        start()
                    }
                } else transition = null
                invalidate()
            }

            init {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            override fun onDraw(canvas: Canvas) {
                if (!canvas.isHardwareAccelerated) return
                val frame = current
                val texture = source as? TextureView
                val parent = texture?.parent as? ViewGroup
                val crop = texture?.let(::fittedBounds)
                if (frame == null && (texture == null || parent == null || crop == null)) return
                val radius = AMBIENT_BLUR_DP * resources.displayMetrics.density * downsample
                if (radius != blurRadius) {
                    blurRadius = radius
                    scene.setRenderEffect(RenderEffect.createColorFilterEffect(
                        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.65f) }),
                        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)))
                }
                val pad = if (frame == null) (radius * 2).toInt() else 0
                val thumbnailWidth = kotlin.math.ceil(width * downsample).toInt()
                val thumbnailHeight = kotlin.math.ceil(height * downsample).toInt()
                scene.setPosition(-pad, -pad, thumbnailWidth + pad, thumbnailHeight + pad)
                val recording = scene.beginRecording()
                try {
                    if (frame != null) {
                        recording.translate(pad.toFloat(), pad + height * downsample)
                        recording.scale(1f, -1f)
                        thumbnailDestination.set(0f, 0f, width * downsample, height * downsample)
                        thumbnailPaint.alpha = 255
                        outgoing?.let { recording.drawBitmap(it, null, thumbnailDestination, thumbnailPaint) }
                        thumbnailPaint.alpha = (255 * blend).toInt()
                        recording.drawBitmap(frame, null, thumbnailDestination, thumbnailPaint)
                        thumbnailPaint.alpha = 255
                    } else if (texture != null && parent != null && crop != null) {
                        val scale = maxOf(width.toFloat() / crop.width(), height.toFloat() / crop.height())
                        recording.translate(pad + width * downsample / 2f, pad + height * downsample)
                        recording.scale(scale * downsample, -scale * downsample)
                        recording.translate(-crop.exactCenterX() - texture.left, -crop.top.toFloat() - texture.top)
                        recording.clipRect(crop.left + texture.left, crop.top + texture.top,
                            crop.right + texture.left, crop.bottom + texture.top)
                        parent.draw(recording)
                    }
                } finally {
                    scene.endRecording()
                }
                val saved = canvas.save()
                canvas.clipRect(0, 0, width, height)
                canvas.scale(1f / downsample, 1f / downsample)
                canvas.drawRenderNode(scene)
                canvas.restoreToCount(saved)
                val darkIcons = activity.window.insetsController?.systemBarsAppearance
                    ?.and(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) ==
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                canvas.drawColor(if (darkIcons) 0x99ffffff.toInt() else 0x66000000)
            }

            fun release() {
                present(null)
                scene.discardDisplayList()
            }
        }

        private fun fittedBounds(texture: TextureView): Rect? {
            if (!layer.isInstance(texture) || !texture.isAvailable || !texture.isShown) return null
            val parent = texture.parent as? ViewGroup ?: return null
            if (parent.childCount != 1 || parent.getChildAt(0) !== texture) return null
            return runCatching { (renderBounds.invoke(texture) as? Rect)?.let(::Rect) }.getOrNull()
                ?.takeIf { it.intersect(0, 0, texture.width, texture.height) && !it.isEmpty }
        }
        fun resume() {
            if (disposed || active) return
            active = true
            decor.overlay.add(strip)
            if (!listening) { decor.viewTreeObserver.addOnGlobalLayoutListener(this); listening = true }
            onGlobalLayout()
        }
        override fun onGlobalLayout() {
            if (!active) return
            val top = decor.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            val normal = top > 0 && activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                !io.github.offlineglass.hook.adapters.bilibili.BilibiliDetailWindowMode.windowed(activity) &&
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0

            if (!normal) {
                cancelThumbnailTick()
                surfaceAmbient.close()
                strip.release()
                strip.visibility = View.GONE
                releaseColor()
                return
            }
            if (source?.let { it.isAttachedToWindow && it.isShown && layer.isInstance(it) } != true) bindSource()
            val texture = source as? TextureView
            val textureReady = texture != null && fittedBounds(texture) != null
            val surface = source as? SurfaceView
            val crop = surface?.let { view ->
                runCatching { (renderBounds.invoke(view) as? Rect)?.let(::Rect) }.getOrNull()
                    ?.takeIf { it.intersect(0, 0, view.width, view.height) }
            }
            val validSurface = surface != null && crop != null && surface.holder.surface.isValid
            val surfaceReady = if (validSurface) {
                surfaceAmbient.update(surface!!, crop!!, decor.width, top)
            } else {
                surfaceAmbient.close()
                false
            }
            if (validSurface && surfaceAmbient.canRetry && !thumbnailTickScheduled) {
                thumbnailTickScheduled = decor.postDelayed(thumbnailTick, AMBIENT_UPDATE_MS)
            } else if (!validSurface || !surfaceAmbient.canRetry) cancelThumbnailTick()
            if (surface != null) report(when {
                surfaceReady -> "Surface GPU thumbnail: ${surfaceAmbient.bitmap!!.width}x${surfaceAmbient.bitmap!!.height}, ${AMBIENT_UPDATE_MS}ms"
                surfaceAmbient.failure != null -> "Surface compositor unavailable: ${surfaceAmbient.failure}"
                else -> "Surface compositor awaiting valid source or blur capability"
            })
            val ready = textureReady || surfaceReady
            strip.visibility = if (ready) View.VISIBLE else View.GONE
            strip.present(surfaceAmbient.bitmap)
            if (!ready) {
                releaseColor()
                return
            }
            if (!colorOwned) {
                originalColor = activity.window.statusBarColor
                originalContrast = activity.window.isStatusBarContrastEnforced
                colorOwned = true
            }
            if (activity.window.statusBarColor != Color.TRANSPARENT) activity.window.statusBarColor = Color.TRANSPARENT
            if (activity.window.isStatusBarContrastEnforced) activity.window.isStatusBarContrastEnforced = false
            val resized = strip.width != decor.width || strip.height != top
            if (resized) strip.layout(0, 0, decor.width, top)
            if (textureReady || resized) strip.invalidate()
        }
        private fun bindSource() {
            val now = SystemClock.uptimeMillis()
            if (now - lastBind < 500L) return
            lastBind = now
            val container = decor.findViewById<View>(activity.resources.getIdentifier("video_container", "id", activity.packageName)) ?: return
            val candidates = ArrayList<View>()
            fun walk(view: View) {
                if ((view is SurfaceView || view is TextureView) && view.isShown && view.getGlobalVisibleRect(visible)) candidates += view
                else if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(container)
            val next = BilibiliAmbientSources.unique(candidates, layer)
            if (source === next) return
            (source as? SurfaceView)?.holder?.removeCallback(surfaceCallback)
            surfaceAmbient.close()
            source = next
            (next as? SurfaceView)?.holder?.addCallback(surfaceCallback)
            report(when (next) {
                is TextureView -> "bound native texture for GPU ambient"
                is SurfaceView -> "bound native Surface for compositor ambient"
                else -> "source unavailable or ambiguous: candidates=${candidates.size}"
            })
        }
        private fun report(value: String) {
            if (diagnostic == value) return
            diagnostic = value
            runCatching { java.io.File(activity.getExternalFilesDir(null), diagnosticFile).writeText(value) }
        }
        private fun releaseColor() {
            if (!colorOwned) return
            if (activity.window.statusBarColor == Color.TRANSPARENT) activity.window.statusBarColor = originalColor
            activity.window.isStatusBarContrastEnforced = originalContrast
            colorOwned = false
        }
        private fun cancelThumbnailTick() {
            decor.removeCallbacks(thumbnailTick)
            thumbnailTickScheduled = false
        }
        fun pause() {
            active = false
            cancelThumbnailTick()
            if (listening && decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(this)
            listening = false
            decor.overlay.remove(strip)
            strip.release()
            surfaceAmbient.close()
            releaseColor()
            (source as? SurfaceView)?.holder?.removeCallback(surfaceCallback)
            source = null
            lastBind = -1000L
        }
        fun dispose() { pause(); disposed = true }
    }
}

