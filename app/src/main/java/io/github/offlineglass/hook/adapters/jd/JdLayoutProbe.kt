package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference

/** JD layout/scroll listener lifecycle and bounded discovery of floating native controls. */
internal class JdLayoutProbe(
    private val host: View,
    private val finder: JdChromeFinder,
    private val checkout: JdCheckoutController,
    private val flash: JdFlashControlsController,
) {
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var layoutRoot: WeakReference<View>? = null
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var scrollRoot: WeakReference<View>? = null
    private var lastLayoutDetect = 0L

    fun attach(
        root: View,
        selectedIndex: () -> Int,
        newProductsActive: () -> Boolean,
        onContentScroll: () -> Unit,
        refreshNewProducts: (ViewGroup, Long) -> Unit,
    ) {
        if (scrollRoot?.get() !== root || scrollListener == null) {
            scrollListener?.let { scrollRoot?.get()?.viewTreeObserver?.removeOnScrollChangedListener(it) }
            val listener = ViewTreeObserver.OnScrollChangedListener { onContentScroll() }
            scrollListener = listener
            scrollRoot = WeakReference(root)
            root.viewTreeObserver.addOnScrollChangedListener(listener)
        }
        if (layoutRoot?.get() === root && layoutListener != null) return
        layoutListener?.let { layoutRoot?.get()?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val flashBanner = flash.banner?.get()
            val flashCart = flash.cart?.get()
            val flashBannerLive = flashBanner != null && flashBanner.isAttachedToWindow
            val flashCartLive = flashCart != null && flashCart.isAttachedToWindow
            if (flashBannerLive || flashCartLive) flash.correct(host.rootView as? ViewGroup, host)
            val now = SystemClock.uptimeMillis()
            val liveRoot = host.rootView as? ViewGroup ?: return@OnGlobalLayoutListener
            if (liveRoot.width <= 0 || liveRoot.height <= 0) return@OnGlobalLayoutListener
            refreshNewProducts(liveRoot, now)
            val bar = checkout.bar?.get()
            if (bar != null && bar.isAttachedToWindow && bar.parent != null) {
                checkout.correct(host.rootView as? ViewGroup, host)
                return@OnGlobalLayoutListener
            }
            if (now - lastLayoutDetect < 80L) return@OnGlobalLayoutListener
            if ((checkout.discoveryExhausted || now < checkout.nextFindAt) &&
                now < flash.nextFindAt) return@OnGlobalLayoutListener
            lastLayoutDetect = now
            if (selectedIndex() == 0 && !newProductsActive() && now >= flash.nextFindAt &&
                (!flashBannerLive || !flashCartLive)
            ) {
                val (foundBanner, foundCart) = finder.findFlashBottomControls(liveRoot, liveRoot.width, liveRoot.height)
                if (foundBanner != null) flash.banner = WeakReference(foundBanner)
                if (foundCart != null) flash.cart = WeakReference(foundCart)
                flash.nextFindAt = if (foundBanner != null || foundCart != null) now + 600L else now + 1_200L
                if (foundBanner != null || foundCart != null) flash.correct(host.rootView as? ViewGroup, host)
            } else if (selectedIndex() != 0) {
                flash.nextFindAt = now + FLASH_INACTIVE_BACKOFF_MS
            }
            if (checkout.discoveryExhausted || now < checkout.nextFindAt) return@OnGlobalLayoutListener
            val found = finder.findCheckoutBar(liveRoot, liveRoot.width, liveRoot.height)
            if (found != null) {
                checkout.bar = WeakReference(found)
                checkout.nextFindAt = 0L
                checkout.correct(host.rootView as? ViewGroup, host)
            } else {
                checkout.discoveryExhausted = true
                checkout.nextFindAt = Long.MAX_VALUE
            }
        }
        layoutListener = listener
        layoutRoot = WeakReference(root)
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    fun detach() {
        layoutListener?.let { layoutRoot?.get()?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        layoutListener = null
        layoutRoot = null
        scrollListener?.let { scrollRoot?.get()?.viewTreeObserver?.removeOnScrollChangedListener(it) }
        scrollListener = null
        scrollRoot = null
        lastLayoutDetect = 0L
    }

    private companion object { const val FLASH_INACTIVE_BACKOFF_MS = 5_000L }
}
