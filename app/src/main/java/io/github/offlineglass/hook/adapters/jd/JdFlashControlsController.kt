package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** JD 秒送 coupon strip and floating cart: discovery, anchoring and restoration. */
internal class JdFlashControlsController(private val finder: JdChromeFinder) {
    var banner: WeakReference<View>? = null
    var cart: WeakReference<View>? = null
    var nextFindAt = 0L
    private val baseTranslations = WeakHashMap<View, Float>()

    fun clearForNewProducts(now: Long, backoffMs: Long) {
        banner = null
        cart = null
        nextFindAt = now + backoffMs
    }

    fun lift(root: ViewGroup, rootHeight: Int, host: View) {
        val rootWidth = root.width
        if (rootWidth <= 0 || rootHeight <= 0) return
        var liveBanner = banner?.get()
        if (liveBanner != null && (!liveBanner.isAttachedToWindow || liveBanner.parent == null)) liveBanner = null
        if (liveBanner == null) banner = null
        var liveCart = cart?.get()
        if (liveCart != null && (!liveCart.isAttachedToWindow || liveCart.parent == null)) liveCart = null
        if (liveCart == null) cart = null
        if (liveBanner == null || liveCart == null) {
            val now = SystemClock.uptimeMillis()
            if (now < nextFindAt) {
                if (liveBanner != null || liveCart != null) correct(root, host)
                return
            }
            val (foundBanner, foundCart) = finder.findFlashBottomControls(root, rootWidth, rootHeight)
            if (foundBanner != null) { banner = WeakReference(foundBanner); liveBanner = foundBanner }
            if (foundCart != null) { cart = WeakReference(foundCart); liveCart = foundCart }
            nextFindAt = if (foundBanner != null || foundCart != null) now + 600L else now + 1_200L
            if (liveBanner == null && liveCart == null) return
        }
        correct(root, host)
    }

    fun correct(root: ViewGroup?, host: View) {
        val scene = root ?: return
        if (scene.height <= 0 || host.width <= 0 || host.height <= 0) return
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val safeBottom = hostLocation[1] - CHECKOUT_BOTTOM_GAP_PX
        val liveBanner = banner?.get()
        var bannerTop = Int.MIN_VALUE
        if (liveBanner != null && liveBanner.isAttachedToWindow && liveBanner.parent != null) {
            val location = IntArray(2).also(liveBanner::getLocationInWindow)
            val delta = location[1] + liveBanner.height - safeBottom
            if (abs(delta) > 1) {
                baseTranslations.putIfAbsent(liveBanner, liveBanner.translationY)
                liveBanner.translationY -= delta.toFloat()
                Log.i("JdGlassDiag", "JdFlash translate banner ${liveBanner.javaClass.simpleName} " +
                    "h=${liveBanner.height} fromBottom=${location[1] + liveBanner.height} target=$safeBottom delta=$delta")
            }
            bannerTop = IntArray(2).also(liveBanner::getLocationInWindow)[1]
        }
        val liveCart = cart?.get() ?: return
        if (!liveCart.isAttachedToWindow || liveCart.parent == null) return
        val targetBottom = if (bannerTop != Int.MIN_VALUE) bannerTop - BACK_TO_TOP_GAP_PX else safeBottom
        val location = IntArray(2).also(liveCart::getLocationInWindow)
        val delta = location[1] + liveCart.height - targetBottom
        if (abs(delta) > 1) {
            baseTranslations.putIfAbsent(liveCart, liveCart.translationY)
            liveCart.translationY -= delta.toFloat()
            Log.i("JdGlassDiag", "JdFlash translate cart ${liveCart.javaClass.simpleName} " +
                "h=${liveCart.height} fromBottom=${location[1] + liveCart.height} target=$targetBottom delta=$delta")
        }
    }

    fun restore() {
        baseTranslations.forEach { (view, base) ->
            if (view.isAttachedToWindow && abs(view.translationY - base) > 0.5f) view.translationY = base
        }
        baseTranslations.clear()
        banner = null
        cart = null
        nextFindAt = 0L
    }

    private companion object {
        const val CHECKOUT_BOTTOM_GAP_PX = 8
        const val BACK_TO_TOP_GAP_PX = 24
    }
}
