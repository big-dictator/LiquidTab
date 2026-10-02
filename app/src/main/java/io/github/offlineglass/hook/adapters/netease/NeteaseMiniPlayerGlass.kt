package io.github.offlineglass.hook.adapters.netease

import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.offlineglass.hook.BloomTiltTracker
import io.github.offlineglass.hook.setBottomBarSquircle
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Lightweight material/highlight skin for NetEase's native mini-player content. */

internal fun neteaseSceneBaseColor(source: View, fallback: Int): Int {

    // A transparent pager does not include the window/ancestor background in

    // its display list. Flatten that background into the *optical input*, not

    // onto the screen: otherwise sharp original text leaks through refraction.

    var ancestor = source.parent as? View

    repeat(16) {

        val view = ancestor ?: return Color.rgb(Color.red(fallback), Color.green(fallback), Color.blue(fallback))

        val color = (view.background as? ColorDrawable)?.color

        if (color != null && Color.alpha(color) == 255) return color

        ancestor = view.parent as? View

    }

    return Color.rgb(Color.red(fallback), Color.green(fallback), Color.blue(fallback))

}



/** Only views explicitly adopted by the NetEase player are protected. */

internal object NeteasePlayerBackgroundGuard {

    private class Owned(drawable: Drawable?, host: View) {

        val drawable = WeakReference(drawable)

        val host = WeakReference(host)

    }

    private val ownedViews = java.util.WeakHashMap<View, Owned>()

    private var installed = false



    fun own(view: View, drawable: Drawable?, host: View) {

        val old = ownedViews[view]

        if (old == null || old.drawable.get() !== drawable || old.host.get() !== host) {

            ownedViews[view] = Owned(drawable, host)

        }

    }



    fun install() {

        if (installed) return

        installed = runCatching {

            XposedBridge.hookAllMethods(

                View::class.java, "setBackgroundDrawable",

                object : XC_MethodHook() {

                    override fun beforeHookedMethod(param: MethodHookParam) {

                        val view = param.thisObject as? View ?: return

                        val owned = ownedViews[view] ?: return

                        if (owned.host.get()?.isAttachedToWindow != true) {

                            ownedViews.remove(view)

                            return

                        }

                        // Preserve the glass itself (or no native plate) without

                        // suppressing layout, child drawing, or click dispatch.

                        param.args[0] = owned.drawable.get()

                    }

                },

            )

            true

        }.getOrDefault(false)

    }

}

/** Keep only the adopted mini-player wrapper opaque during NetEase rebinds. */
internal object NeteasePlayerAlphaGuard {
    private val owners = java.util.WeakHashMap<View, WeakReference<View>>()
    private val bars = java.util.WeakHashMap<View, WeakReference<io.github.offlineglass.hook.GlassHostLayout>>()
    private var installed = false

    fun own(view: View, host: View) {
        owners[view] = WeakReference(host)
    }

    fun ownBar(view: View, host: io.github.offlineglass.hook.GlassHostLayout) {
        bars[view] = WeakReference(host)
    }

    fun install() {
        if (installed) return
        installed = runCatching {
            XposedBridge.hookAllMethods(View::class.java, "setAlpha", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val host = owners[view]?.get() ?: return
                    if (!host.isAttachedToWindow || !view.isAttachedToWindow) {
                        owners.remove(view)
                        return
                    }
                    param.args[0] = 1f
                }
            })
            XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val host = bars[view]?.get() ?: return
                    if (!host.isAttachedToWindow || !view.isAttachedToWindow) {
                        bars.remove(view)
                        return
                    }
                    if (host.selectedIndex == 0 && host.neteaseRuntime.heartbeatActive) {
                        param.args[0] = View.GONE
                    }
                }
            })
            true
        }.getOrDefault(false)
    }
}



internal class NeteaseMiniPlayerGlassDrawable(

    shaderSource: String,

    private val bloomShader: RuntimeShader?,

) : Drawable() {

    private val opticalNode = RenderNode("NeteasePlayerOptics")

    private val lens = runCatching { RuntimeShader(shaderSource) }.getOrNull()

    private val sourceLocation = IntArray(2)

    private val targetLocation = IntArray(2)

    private var opticalSignature = ""

    private var opticalSceneSignature = ""

    private var displayDensity = 1f

    private val renderNodeField = runCatching {

        View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }

    }.getOrNull()



    // Diagnostics: capture exactly what drawOptics costs during scrolling so a

    // frame hitch can be attributed to re-record churn vs. pure blur replay.

    private var diagDraws = 0L

    private var diagReRecords = 0L

    private var diagOpticsNs = 0L

    private var diagOpticsMax = 0L

    private var lastDiagLogMs = 0L

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {

        style = Paint.Style.STROKE

    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private val lightingRect = RectF()

    private val lightingPath = Path()

    private val rect = RectF()

    private val path = Path()

    private var fillColor = Color.TRANSPARENT

    private var dark = false

    private var radiusPercent = 50f

    private var smoothing = 0.5f

    private var outlineStrength = 0.5f

    private var scene: View? = null

    private var blurTarget: View? = null

    private var blurRadius = 0f

    private var liquidGlassEnabled = false



    /** Re-arms the backdrop scene after NetEase rebinds the mini player. */

    fun prepareScene(

        scene: View?,

        target: View,

        blurRadius: Float,

        liquidGlassEnabled: Boolean,

        density: Float,

    ) {

        val changed = this.scene !== scene || this.blurTarget !== target ||

            abs(this.blurRadius - blurRadius) >= 0.001f ||

            this.liquidGlassEnabled != liquidGlassEnabled ||

            abs(this.displayDensity - density) >= 0.001f

        this.scene = scene

        this.blurTarget = target

        this.blurRadius = blurRadius

        this.liquidGlassEnabled = liquidGlassEnabled

        this.displayDensity = density

        if (changed) {

            opticalSceneSignature = ""

            invalidateSelf()

        }

    }



    private var opticsDiagLogged = false



    private fun drawOptics(canvas: Canvas) {

        if (!canvas.isHardwareAccelerated) {

            logOpticsSkip("no hw acceleration")

            return

        }

        val source = scene ?: return

        val target = blurTarget ?: return

        if (!source.isShown || !target.isShown) {

            logOpticsSkip("scene or target not shown")

            return

        }

        val sourceNode = runCatching { renderNodeField?.get(source) as? RenderNode }.getOrNull() ?: run {

            logOpticsSkip("no render node")

            return

        }

        if (!sourceNode.hasDisplayList()) {

            logOpticsSkip("no display list for ${source.javaClass.name}")

            return

        }

        // Anchor on the source's own window position, not its parent's: scenes

        // offset inside a taller parent (e.g. MyRecentPlayActivity's pager at

        // top=449) were sampled 449px too high, rendering the capsule flat.

        source.getLocationInWindow(sourceLocation)

        target.getLocationInWindow(targetLocation)

        val pad = io.github.offlineglass.rendering.NativeBackdropBlur.padding(blurRadius, 26f * displayDensity).roundToInt()

        val w = bounds.width()

        val h = bounds.height()

        if (w <= 0 || h <= 0) return

        val dx = sourceLocation[0] - targetLocation[0] + pad

        val dy = sourceLocation[1] - targetLocation[1] + pad

        logOpticsOk("sampling ${source.javaClass.name} dx=$dx dy=$dy")

        val baseColor = neteaseSceneBaseColor(source, fillColor)

        val sourceIdentity = System.identityHashCode(sourceNode)

        val signature = "$w/$h/$pad/$blurRadius/$radiusPercent/$liquidGlassEnabled"

        if (signature != opticalSignature) {

            var effect: RenderEffect? = io.github.offlineglass.rendering.NativeBackdropBlur.effect(blurRadius)

            lens?.takeIf { liquidGlassEnabled }?.let { shader ->

                shader.setFloatUniform("size", w.toFloat(), h.toFloat())

                shader.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())

                val corner = min(w, h) * radiusPercent / 100f

                shader.setFloatUniform("cornerRadii", corner, corner, corner, corner)

                shader.setFloatUniform("refractionHeight", min(h / 2f, 24f * displayDensity))

                shader.setFloatUniform("refractionAmount", -24f * displayDensity)

                shader.setFloatUniform("depthEffect", 0f)

                val refraction = RenderEffect.createRuntimeShaderEffect(shader, "content")

                effect = effect?.let { RenderEffect.createChainEffect(refraction, it) } ?: refraction

            }

            opticalNode.setRenderEffect(effect)

            opticalSignature = signature

        }

        // A RenderNode command retains a live reference to its child node, so

        // scrolling updates the sampled pixels without rebuilding this wrapper.

        // Re-recording the entire search pager for every mini-player draw made

        // the search list pay for a second scene traversal on every frame. Keep

        // the same live blur/refraction chain and only rebuild when its source,

        // geometry, registration or base material actually changes.

        val sceneSignature = "$sourceIdentity/$w/$h/$pad/$dx/$dy/$baseColor"

        if (sceneSignature != opticalSceneSignature || !opticalNode.hasDisplayList()) {

            diagReRecords++

            val recording = opticalNode.beginRecording(w + pad * 2, h + pad * 2)

            recording.drawColor(baseColor)

            recording.clipRect(0f, 0f, (w + pad * 2).toFloat(), (h + pad * 2).toFloat())

            recording.translate(dx.toFloat(), dy.toFloat())

            recording.drawRenderNode(sourceNode)

            opticalNode.endRecording()

            opticalNode.setPosition(-pad, -pad, w + pad, h + pad)

            opticalSceneSignature = sceneSignature

        }

        canvas.save()

        canvas.clipPath(path)

        canvas.drawRenderNode(opticalNode)

        canvas.restore()

    }



    private fun logOpticsSkip(reason: String) {

        if (opticsDiagLogged) return

        opticsDiagLogged = true

        android.util.Log.i("WmGlassDiag", "netease player optics skip: $reason")

    }



    private fun logOpticsOk(message: String) {

        if (opticsDiagLogged) return

        opticsDiagLogged = true

        android.util.Log.i("WmGlassDiag", "netease player optics ok: $message")

    }



    fun update(

        fillColor: Int,

        dark: Boolean,

        radiusPercent: Float,

        smoothing: Float,

        outlineStrength: Float,

    ) {

        if (this.fillColor == fillColor && this.dark == dark &&

            abs(this.radiusPercent - radiusPercent) < 0.001f &&

            abs(this.smoothing - smoothing) < 0.001f &&

            abs(this.outlineStrength - outlineStrength) < 0.001f

        ) return

        this.fillColor = fillColor

        this.dark = dark

        this.radiusPercent = radiusPercent

        this.smoothing = smoothing

        this.outlineStrength = outlineStrength

        invalidateSelf()

    }



    override fun draw(canvas: Canvas) {

        if (bounds.isEmpty) return

        diagDraws++

        val diagStart = System.nanoTime()

        val density = canvas.density.takeIf { it > 0 }?.let { it / 160f } ?: 1f

        val stroke = density.coerceAtLeast(1f)

        val inset = stroke * 0.5f

        rect.set(

            bounds.left + inset,

            bounds.top + inset,

            bounds.right - inset,

            bounds.bottom - inset,

        )

        val radius = min(rect.width(), rect.height()) * radiusPercent / 100f - inset

        path.setBottomBarSquircle(rect, radius.coerceAtLeast(0f), smoothing)



        // Ambient shadow ringing the player capsule, same params as the

        // navigation panel: offset-free, clipped to the outer half.

        if (canvas.isHardwareAccelerated) {

            shadowPaint.color = Color.TRANSPARENT

            shadowPaint.setShadowLayer(

                PLAYER_SHADOW_BLUR_DP * density,

                0f,

                0f,

                Color.argb(

                    ((if (dark) PLAYER_SHADOW_ALPHA else PLAYER_SHADOW_LIGHT_ALPHA) * 255f).toInt(),

                    0,

                    0,

                    0,

                ),

            )

            canvas.save()

            canvas.clipOutPath(path)

            canvas.drawPath(path, shadowPaint)

            canvas.restore()

        }



        val opticsStart = System.nanoTime()

        drawOptics(canvas)

        val opticsElapsed = System.nanoTime() - opticsStart

        diagOpticsNs += opticsElapsed

        if (opticsElapsed > diagOpticsMax) diagOpticsMax = opticsElapsed

        fillPaint.color = fillColor

        canvas.drawPath(path, fillPaint)



        val alpha = (255f * outlineStrength.coerceIn(0f, 1f)).roundToInt()

        strokePaint.strokeWidth = stroke

        if (dark && liquidGlassEnabled && bloomShader != null && canvas.isHardwareAccelerated) {

            // Dynamic bloom highlight, matching the navigation panel's stroke.

            drawBloomStrokePlayer(canvas, rect, radius.coerceAtLeast(0f), outlineStrength.coerceIn(0f, 1f))

        } else {

            strokePaint.shader = LinearGradient(

                rect.left,

                rect.top,

                rect.right,

                rect.bottom,

                intArrayOf(

                    Color.argb(alpha, 255, 255, 255),

                    Color.argb((alpha * 0.32f).roundToInt(), 255, 255, 255),

                    Color.argb((alpha * if (dark) 0.12f else 0.16f).roundToInt(), 255, 255, 255),

                ),

                floatArrayOf(0f, 0.52f, 1f),

                Shader.TileMode.CLAMP,

            )

            canvas.drawPath(path, strokePaint)

            strokePaint.shader = null

        }

        logDiagIfDue()

    }



    private fun logDiagIfDue() {

        val now = SystemClock.uptimeMillis()

        if (now - lastDiagLogMs < 1000L) return

        lastDiagLogMs = now

        val draws = diagDraws

        val avgOpticsUs = if (draws > 0) (diagOpticsNs / draws) / 1000 else 0L

        android.util.Log.i(

            "GlassPerfNetEasePlayer",

            "draws=$draws reRec=$diagReRecords " +

                "opticsAvgUs=$avgOpticsUs maxUs=${diagOpticsMax / 1000} " +

                "scene=${scene?.javaClass?.simpleName} shown=${scene?.isShown} " +

                "blur=$blurRadius lens=$liquidGlassEnabled",

        )

        diagDraws = 0

        diagReRecords = 0

        diagOpticsNs = 0

        diagOpticsMax = 0

    }



    /** Dynamic bloom stroke copied from the navigation panel's highlight. */

    private fun drawBloomStrokePlayer(canvas: Canvas, target: RectF, radius: Float, alpha: Float) {

        val shader = bloomShader ?: return

        if (alpha <= 0f || target.isEmpty || !canvas.isHardwareAccelerated) return

        runCatching {

            val width = target.width()

            val height = target.height()

            val halfWidth = width / 2f

            val halfHeight = height / 2f

            shader.setFloatUniform("halfView", halfWidth, halfHeight)

            shader.setFloatUniform("highlightAlpha", alpha)

            shader.setColorUniform("strokeColor", Color.WHITE)

            shader.setFloatUniform("strokeAlphaMul", 0.12f)

            setBloomLightPlayer(shader, "1", -45f, primary = true)

            setBloomLightPlayer(shader, "2", -45f, primary = false)



            bloomPaint.shader = shader

            bloomPaint.style = Paint.Style.STROKE

            bloomPaint.strokeWidth = displayDensity.coerceAtLeast(1f)

            lightingRect.set(0f, 0f, width, height)

            lightingPath.setBottomBarSquircle(lightingRect, radius, smoothing)

            canvas.save()

            canvas.translate(target.left, target.top)

            canvas.drawPath(lightingPath, bloomPaint)

            canvas.restore()

            bloomPaint.shader = null

            bloomPaint.style = Paint.Style.FILL

        }

    }



    private fun setBloomLightPlayer(shader: RuntimeShader, suffix: String, rotation: Float, primary: Boolean) {

        val dx: Float

        val dy: Float

        val dz: Float

        val intensity: Float

        if (primary) {

            var gx = BloomTiltTracker.gravityX

            var gy = BloomTiltTracker.gravityY

            val displayRotation = blurTarget?.display?.rotation ?: Surface.ROTATION_0

            when (displayRotation) {

                Surface.ROTATION_90 -> { val remap = gx; gx = -gy; gy = remap }

                Surface.ROTATION_180 -> { gx = -gx; gy = -gy }

                Surface.ROTATION_270 -> { val remap = gx; gx = gy; gy = -remap }

            }

            val gMagSq = gx * gx + gy * gy

            val lx0: Float

            val ly0: Float

            if (gMagSq > BloomTiltTracker.GRAVITY_DIR_THRESHOLD_SQ) {

                val invMag = 1f / sqrt(gMagSq)

                lx0 = gx * invMag

                ly0 = gy * invMag

            } else {

                lx0 = 0f

                ly0 = -1f

            }

            val radians = Math.toRadians(rotation.toDouble())

            val c = cos(radians).toFloat()

            val s = sin(radians).toFloat()

            dx = c * lx0 - s * ly0

            dy = s * lx0 + c * ly0

            dz = -0.05f

            intensity = 1f

        } else {

            dx = 0f

            dy = 0.1f

            dz = -0.5f

            intensity = 0.4f

        }

        val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.000001f)

        shader.setFloatUniform("lightDir$suffix", dx / length, dy / length, dz / length)

        shader.setColorUniform("lightColor$suffix", Color.WHITE)

        shader.setFloatUniform("lightIntensity$suffix", intensity)

    }



    override fun setAlpha(alpha: Int) {

        fillPaint.alpha = alpha.coerceIn(0, 255)

        invalidateSelf()

    }



    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {

        fillPaint.colorFilter = colorFilter

        strokePaint.colorFilter = colorFilter

        invalidateSelf()

    }



    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT



    private companion object {

        // Same offset-free ambient ring as the navigation panel's dropShadow.

        const val PLAYER_SHADOW_BLUR_DP = 10f

        const val PLAYER_SHADOW_ALPHA = 0.14f

        const val PLAYER_SHADOW_LIGHT_ALPHA = 0.19f

    }

}
