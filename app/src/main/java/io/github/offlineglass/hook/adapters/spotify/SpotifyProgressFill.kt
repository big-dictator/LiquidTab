package io.github.offlineglass.hook.adapters.spotify


import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import kotlin.math.abs

/** Uses Spotify's own hidden progress control; never changes the playback clock. */
internal class SpotifyProgressFill(private val player: WeakReference<View>) {
    private var bar = WeakReference<ProgressBar>(null)
    private var cover = WeakReference<ImageView>(null)
    private var enabled = false
    private var nativeColor: Int? = null
    private var lastCover = WeakReference<Drawable>(null)
    private var coverGeneration = -1
    private var probeTime = 0L
    private var fallbackColor: Int? = null
    private var fromColor = Color.TRANSPARENT
    private var toColor = Color.TRANSPARENT
    private var colorTime = 0L
    private var fromProgress = 0f
    private var toProgress = 0f
    private var progressTime = 0L
    // One continuous field avoids seams between a solid fill and separate light layers.
    private val lightShader = RuntimeShader("""
        uniform float4 panel;
        uniform float progress;
        uniform float4 theme;
        half4 main(float2 p) {
            float height = max(panel.w, 1.0);
            float radius = height * 0.5;
            float played = panel.z * progress;
            float end = panel.x + played;
            float y = clamp(p.y - (panel.y + radius), -radius, radius);
            float arc = sqrt(max(radius * radius - y * y, 0.0));
            float distance = p.x - (end - radius + arc);
            // Keep full-height lighting with a feathered circular leading edge.
            float mask = 1.0 - smoothstep(-height * 0.4, height * 0.7, distance);
            mask = mix(mask, 1.0, smoothstep(0.97, 1.0, progress));
            mask *= smoothstep(0.0, radius * 0.3, played);
            // The whole played region fades smoothly from left to right,
            // rather than staying flat until the leading edge.
            float along = clamp((p.x - panel.x) / max(played, 1.0), 0.0, 1.0);
            float light = mix(0.85, 0.18, smoothstep(0.0, 1.0, along));
            float alpha = theme.a * mask * light;
            return half4(theme.rgb * alpha, alpha);
        }
    """.trimIndent())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lightShader }
    private val themeHsv = FloatArray(3)
    private var rawTheme: Int? = null
    private var brightTheme = Color.TRANSPARENT
    private var paintColor = Int.MIN_VALUE
    private val sample = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
    private val sampleCanvas = Canvas(sample)
    private val pixels = IntArray(24 * 24)

    fun bind(progress: ProgressBar?, image: ImageView?, active: Boolean) {
        if (bar.get() !== progress) {
            bar = WeakReference(progress)
            player.get()?.let { target ->
                val weak=WeakReference(this)
                SpotifyPlaybackHooks.observe(target,progress) { weak.get()?.wake() }
            }
        }
        cover = WeakReference(image)
        if (enabled != active) {
            enabled = active
            if (!active) { fromProgress = 0f; toProgress = 0f }
            wake()
        }
    }
    fun setThemeColor(color: Int) {
        // Spotify initializes its color animator with this generic placeholder.
        nativeColor = color.takeUnless { (it and 0xFFFFFF) == 0x282828 || Color.alpha(it) == 0 }
        if (enabled) wake()
    }
    private fun wake() {
        if (enabled) player.get()?.postInvalidateOnAnimation()
    }
    private fun mix(a: Int, b: Int, f: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
    private fun fraction(now: Long): Float {
        val t = ((now - progressTime) / 180f).coerceIn(0f, 1f)
        return fromProgress + (toProgress - fromProgress) * (t * (2f - t))
    }
    private fun color(now: Long): Int = mix(fromColor, toColor,
        ((now - colorTime) / 450f).coerceIn(0f, 1f))

    private fun probeCover(now: Long) {
        if (now - probeTime < 350L) return
        probeTime = now
        val drawable = cover.get()?.drawable
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        val generation = bitmap?.generationId ?: 0
        if (lastCover.get() === drawable && coverGeneration == generation) return
        lastCover = WeakReference(drawable)
        coverGeneration = generation
        fallbackColor = null
        if (drawable == null || bitmap?.isRecycled == true) return
        // Tiny sample once per artwork, independent of information-feed rendering.
        sample.eraseColor(Color.TRANSPARENT)
        val sampled = runCatching {
            if (bitmap != null) {
                val software = if (bitmap.config == Bitmap.Config.HARDWARE)
                    bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                try { sampleCanvas.drawBitmap(software, null, Rect(0, 0, 24, 24), null) }
                finally { if (software !== bitmap) software?.recycle() }
            } else {
                val old = Rect(drawable.bounds)
                drawable.setBounds(0, 0, 24, 24)
                try { drawable.draw(sampleCanvas) } finally { drawable.bounds = old }
            }
        }.isSuccess
        if (!sampled) return
        sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        val weights = FloatArray(24)
        val red = FloatArray(24); val green = FloatArray(24); val blue = FloatArray(24)
        val hsv = FloatArray(3)
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 128) continue
            Color.colorToHSV(pixel, hsv)
            if (hsv[2] < 0.12f || hsv[2] > 0.95f && hsv[1] < 0.12f) continue
            val bin = (hsv[0] / 15f).toInt().coerceIn(0, 23)
            val weight = 0.2f + hsv[1] * hsv[2]
            weights[bin] += weight
            red[bin] += Color.red(pixel) * weight
            green[bin] += Color.green(pixel) * weight
            blue[bin] += Color.blue(pixel) * weight
        }
        val best = weights.indices.maxByOrNull { weights[it] } ?: return
        if (weights[best] > 0f) fallbackColor = Color.rgb(
            (red[best] / weights[best]).toInt(), (green[best] / weights[best]).toInt(),
            (blue[best] / weights[best]).toInt())
    }

    fun draw(canvas: Canvas, bounds: RectF, clip: Path, opacity: Int, density: Float) {
        if (!enabled) return
        val target = player.get() ?: return
        val clock = SpotifyPlaybackHooks.clock(target)
        val progress = bar.get()
        if (clock == null && (progress == null || progress.isIndeterminate || progress.max <= progress.min)) return
        val now = SystemClock.uptimeMillis()
        probeCover(now)
        val runtimeColor = SpotifyPlaybackHooks.color(target)?.takeUnless {
            (it and 0xFFFFFF) == 0x282828 || Color.alpha(it) == 0
        }
        val nativeTheme = nativeColor ?: runtimeColor
        val theme = nativeTheme?.let {
            Color.colorToHSV(it, themeHsv)
            // A neutral native background is not a useful colorful track accent.
            if (themeHsv[1] < 0.12f) fallbackColor ?: it else it
        } ?: fallbackColor
        if (theme != rawTheme) {
            rawTheme = theme
            brightTheme = theme?.let {
                // Lift the track hue without adding white or SCREEN bleaching.
                Color.colorToHSV(it, themeHsv)
                if (themeHsv[1] > 0.12f) themeHsv[1] = themeHsv[1].coerceIn(0.48f, 0.72f)
                themeHsv[2] = themeHsv[2].coerceAtLeast(0.96f)
                Color.HSVToColor(148, themeHsv)
            } ?: Color.TRANSPARENT
        }
        val wantedColor = brightTheme
        if (wantedColor != toColor) {
            fromColor = color(now); toColor = wantedColor; colorTime = now
        }
        val wanted = clock?.fraction() ?: ((progress!!.progress - progress.min).toFloat() /
            (progress.max - progress.min)).coerceIn(0f, 1f)
        if (abs(wanted - toProgress) > 0.000001f) {
            fromProgress = fraction(now); toProgress = wanted; progressTime = now
        }
        val shown = fraction(now)
        val tint = color(now)
        if (paintColor != tint) {
            paintColor = tint
            lightShader.setFloatUniform("theme", Color.red(tint) / 255f,
                Color.green(tint) / 255f, Color.blue(tint) / 255f, Color.alpha(tint) / 255f)
        }
        lightShader.setFloatUniform("panel", bounds.left, bounds.top, bounds.width(), bounds.height())
        lightShader.setFloatUniform("progress", shown)
        val save = if (opacity == 255) canvas.save() else canvas.saveLayerAlpha(bounds, opacity)
        canvas.clipPath(clip)
        canvas.drawRect(bounds, fill)
        canvas.restoreToCount(save)
        if (clock != null && clock.speed > 0f && shown < 1f && target.isShown)
            target.postInvalidateDelayed(33L)
        else if (now - progressTime < 180L || now - colorTime < 450L) wake()
    }
    fun release() {
        player.get()?.let { SpotifyPlaybackHooks.unbind(it) }
        bar.clear(); cover.clear(); lastCover.clear()
        sample.recycle()
    }
}
