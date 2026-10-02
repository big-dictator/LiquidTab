package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout

/** App-specific veil placement and ownership; the shared glass engine stays in the host. */
internal class WeChatVeilController {
    private var wechatContactsVeilTarget: View? = null
    private var wechatContactsVeilDrawable: WeChatContactsVeilDrawable? = null
    private var wechatChatListVeilTarget: ViewGroup? = null
    private var wechatChatListVeilDrawable: WeChatContactsVeilDrawable? = null
    private var wechatChatListVeilLayer: View? = null
    private var wechatNavigationBlendTarget: View? = null
    private var wechatNavigationBlendDrawable: WeChatContactsVeilDrawable? = null
    private var wechatNavigationBlendLayer: View? = null
    val hasContactsVeil: Boolean get() = wechatContactsVeilDrawable != null
    private companion object {
        const val CONTACTS_FADE_DP = 148f
        const val CONTACTS_SOLID_LIFT_DP = 6f
        const val CONTACTS_MAX_HOST_RATIO = 0.5f
        const val CHAT_LIST_ALPHA = 255
    }

    fun applyContacts(host: View, navigationSource: View?, target: View, density: Float, surfaceColor: Int) {

        val source = navigationSource ?: return

        val targetLoc = IntArray(2).also(target::getLocationInWindow)

        val sourceLoc = IntArray(2).also(source::getLocationInWindow)

        // WeChat's native row is taller than Tieba's and its frosting can

        // extend slightly above the icon row. Move the fully opaque boundary

        // upward by a small app-specific safety margin instead of copying a

        // fixed Tieba height.

        val solidStart = (

            sourceLoc[1] - targetLoc[1] - CONTACTS_SOLID_LIFT_DP * density

            ).coerceIn(0f, target.height.toFloat())

        // Keep the opaque native-remnant coverage unchanged, but constrain the

        // transition itself to at most half a LiquidTab height. The bar is only

        // a ruler here; the fade still grows upward from the opaque edge.

        val fadeHeight = minOf(

            CONTACTS_FADE_DP * density,

            host.height * CONTACTS_MAX_HOST_RATIO,

        ).coerceAtLeast(1f)



        var drawable = wechatContactsVeilDrawable

        if (wechatContactsVeilTarget !== target || drawable == null) {

            clearContacts()

            drawable = WeChatContactsVeilDrawable()

            drawable.setBounds(0, 0, target.width, target.height)

            target.overlay.add(drawable)

            wechatContactsVeilTarget = target

            wechatContactsVeilDrawable = drawable

        } else if (drawable.bounds.width() != target.width || drawable.bounds.height() != target.height) {

            drawable.setBounds(0, 0, target.width, target.height)

        }

        drawable.update(solidStart, fadeHeight, surfaceColor)

    }

    fun clearContacts() {

        val target = wechatContactsVeilTarget

        val drawable = wechatContactsVeilDrawable

        if (target != null && drawable != null) {

            runCatching { target.overlay.remove(drawable) }

        }

        wechatContactsVeilTarget = null

        wechatContactsVeilDrawable = null

    }

    fun applyChatList(

        host: View,

        fadeFromHostRatio: Float,

        alpha: Int,

        surfaceColor: Int,

    ) {

        val target = host.parent as? ViewGroup ?: return

        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0 ||

            host.width <= 0 || host.height <= 0

        ) return

        val targetLoc = IntArray(2).also(target::getLocationInWindow)

        val hostLoc = IntArray(2).also(host::getLocationInWindow)

        val root = host.rootView

        val rootLoc = IntArray(2).also(root::getLocationInWindow)

        val navigationInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            root.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0

        } else {

            @Suppress("DEPRECATION")

            (root.rootWindowInsets?.stableInsetBottom ?: 0)

        }

        // The raised navigation area's real top edge is the fully opaque anchor.

        // Chat fades from the bar centre; Discover/Profile fade from its top.

        val navigationTopWindow = if (navigationInset > 0) {

            rootLoc[1] + root.height - navigationInset

        } else {

            targetLoc[1] + target.height

        }

        val solidStart = (navigationTopWindow - targetLoc[1]).toFloat()

            .coerceIn(1f, target.height.toFloat())

        val fadeTop = (hostLoc[1] + host.height * fadeFromHostRatio - targetLoc[1])

            .coerceIn(0f, solidStart - 1f)

        val fadeHeight = (solidStart - fadeTop).coerceAtLeast(1f)



        var drawable = wechatChatListVeilDrawable

        var layer = wechatChatListVeilLayer

        if (wechatChatListVeilTarget !== target || layer?.parent !== target || drawable == null) {

            clearChatList()

            drawable = WeChatContactsVeilDrawable().also { it.alpha = CHAT_LIST_ALPHA }

            layer = View(host.context).apply {

                isClickable = false

                isFocusable = false

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

                background = drawable

            }

            val hostIndex = target.indexOfChild(host).coerceAtLeast(0)

            target.addView(

                layer,

                hostIndex,

                FrameLayout.LayoutParams(

                    ViewGroup.LayoutParams.MATCH_PARENT,

                    ViewGroup.LayoutParams.MATCH_PARENT,

                ),

            )

            wechatChatListVeilTarget = target

            wechatChatListVeilDrawable = drawable

            wechatChatListVeilLayer = layer

        }

        drawable.alpha = alpha.coerceIn(0, 255)

        drawable.update(solidStart, fadeHeight, surfaceColor)

    }

    fun setChatListAlpha(alpha: Int) {

        val drawable = wechatChatListVeilDrawable ?: return

        val value = alpha.coerceIn(0, 255)

        if (drawable.alpha == value) return

        drawable.alpha = value

        wechatChatListVeilLayer?.invalidate()

    }

    fun clearChatList() {

        val target = wechatChatListVeilTarget

        wechatChatListVeilLayer?.let { layer ->

            runCatching { (layer.parent as? ViewGroup)?.removeView(layer) }

        }

        wechatChatListVeilTarget = null

        wechatChatListVeilDrawable = null

        wechatChatListVeilLayer = null

    }



    /**

     * Joins the live chat blur to the system-owned gesture strip. The bottom

     * pixel reaches the strip's real colour, while the colour fades upward

     * through the already-blurred content. LiquidTab is a later sibling and

     * remains optically and interactively above this non-touch overlay.

     */

    fun applyNavigationBlend(host: View, target: View, surfaceColor: Int) {

        if (!target.isAttachedToWindow) return

        val content = host.parent as? ViewGroup ?: return

        val root = host.rootView

        if (content.height <= 0 || root.height <= 0) return

        val contentLoc = IntArray(2).also(content::getLocationInWindow)

        val rootLoc = IntArray(2).also(root::getLocationInWindow)

        val inset = (rootLoc[1] + root.height - contentLoc[1] - content.height)

            .coerceAtLeast(0)

        if (inset <= 1) {

            clearNavigationBlend()

            return

        }

        // Gradient spans from the raised gesture surface's top all the way to

        // the content bottom, with no solid zone: the navigation colour fades

        // in gradually and reaches full opacity only at the very screen edge.

        val fadeTop = (content.height - inset).toFloat()

            .coerceIn(0f, content.height.toFloat())

        val solidStart = content.height.toFloat()

        val fadeHeight = (solidStart - fadeTop).coerceAtLeast(1f)

        var drawable = wechatNavigationBlendDrawable

        var layer = wechatNavigationBlendLayer

        if (layer?.parent !== content || drawable == null) {

            clearNavigationBlend()

            drawable = WeChatContactsVeilDrawable()

            layer = View(host.context).apply {

                isClickable = false

                isFocusable = false

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

                background = drawable

            }

            val hostIndex = content.indexOfChild(host).coerceAtLeast(0)

            content.addView(

                layer,

                hostIndex,

                FrameLayout.LayoutParams(

                    ViewGroup.LayoutParams.MATCH_PARENT,

                    ViewGroup.LayoutParams.MATCH_PARENT,

                ),

            )

            // This sibling is composited after the Gaussian-filtered page but

            // before LiquidTab, so the navigation-colour join is never sampled

            // into the blur and can still never tint or intercept the glass bar.

            wechatNavigationBlendTarget = content

            wechatNavigationBlendDrawable = drawable

            wechatNavigationBlendLayer = layer

        }

        drawable.update(solidStart, fadeHeight, surfaceColor)

    }

    fun resolveNavigationBlendTarget(host: View, root: ViewGroup, foldProxy: View?): View? {

        val cached = wechatNavigationBlendTarget

        if (cached != null && cached.isAttachedToWindow && cached.visibility == View.VISIBLE &&

            cached.width > 0 && cached.height > 0

        ) return cached

        val content = host.parent as? ViewGroup ?: return null

        var best: View? = null

        var bestArea = 0

        for (index in 0 until content.childCount) {

            val child = content.getChildAt(index)

            if (child === host || child === foldProxy ||

                child.visibility != View.VISIBLE || child.alpha < 0.5f

            ) continue

            val rect = Rect()

            if (!child.getGlobalVisibleRect(rect) ||

                !rect.intersect(0, 0, root.width, root.height)

            ) continue

            val area = rect.width() * rect.height()

            if (area > bestArea) {

                bestArea = area

                best = child

            }

        }

        return best

    }

    fun clearNavigationBlend() {

        wechatNavigationBlendLayer?.let { layer ->

            runCatching { (layer.parent as? ViewGroup)?.removeView(layer) }

        }

        wechatNavigationBlendTarget = null

        wechatNavigationBlendDrawable = null

        wechatNavigationBlendLayer = null

    }
}

