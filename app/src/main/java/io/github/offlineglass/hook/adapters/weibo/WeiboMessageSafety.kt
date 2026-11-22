package io.github.offlineglass.hook.adapters.weibo

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.GlassHostLayout
import kotlin.math.ceil

/** One owner for conversation-list tail space. No private type or page-index binding. */
internal class WeiboMessageSafety {
    private var root: View? = null
    private var candidate: ViewGroup? = null
    private var applied: ViewGroup? = null
    private var originalBottom = 0
    private var originalClip = true
    private var appliedBottom = 0
    private var dirty = true
    private var attempts = 0
    private var nextProbe = 0L
    private val bounds = Rect()
    private val listLocation = IntArray(2)
    private val barLocation = IntArray(2)
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (candidate?.isAttachedToWindow != true) { dirty = true; attempts = 0 }
    }
    var active = false
        private set

    fun update(host: GlassHostLayout) {
        if (root !== host.rootView) {
            dispose()
            root = host.rootView
            root?.viewTreeObserver?.addOnGlobalLayoutListener(layoutListener)
        }
        if (!host.config.enabled) {
            restore()
            return
        }
        candidate = WeiboMessageBinding.visibleList(host)
        if (candidate == null) {
            val now = SystemClock.uptimeMillis()
            if (!dirty || attempts >= 8 || now < nextProbe) { restore(); return }
            attempts++
            nextProbe = now + 250L
            WeiboMessageBinding.discover(host.rootView)
            candidate = WeiboMessageBinding.visibleList(host)
        }
        val target = candidate
        if (target == null || !target.isShown || !target.getGlobalVisibleRect(bounds) ||
            bounds.width() < target.width * .9f || target.height <= 0 || host.height <= 0) {
            restore()
            return
        }
        if (applied !== target) {
            restore()
            applied = target
            originalBottom = target.paddingBottom
            originalClip = target.clipToPadding
            appliedBottom = originalBottom
        }
        target.getLocationInWindow(listLocation)
        host.getLocationInWindow(barLocation)
        val barTop = barLocation[1] - host.translationY
        val gap = 8f * host.resources.displayMetrics.density
        val bottom = WeiboGeometry.bottomPadding((listLocation[1] + target.height).toFloat(), barTop, gap, originalBottom)
        if (target.paddingBottom != bottom) {
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, bottom)
            android.util.Log.i("LiquidTab-Weibo", "MessageSafety applied: padding=$bottom, original=$originalBottom, listBottom=${listLocation[1] + target.height}, barTop=$barTop")
            WeiboMessageProbe.record(host.context, "applied selected=${host.selectedIndex} padding=${target.paddingBottom} listBottom=${listLocation[1] + target.height} barTop=$barTop gap=$gap")
        }
        if (target.clipToPadding) target.clipToPadding = false
        appliedBottom = bottom
        active = true
    }

    fun restore() {
        applied?.let {
            if (it.paddingBottom == appliedBottom)
                it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, originalBottom)
            if (it.clipToPadding != originalClip) it.clipToPadding = originalClip
        }
        applied = null
        active = false
    }

    fun dispose() {
        restore()
        root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        root = null; candidate = null
        dirty = true; attempts = 0; nextProbe = 0L
    }
}
