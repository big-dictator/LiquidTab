package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

internal class WeChatMiniProgramDetector {
    private var titleAnchor = java.lang.ref.WeakReference<TextView>(null)
    private var contentAnchor = java.lang.ref.WeakReference<TextView>(null)
    private var lastMissRoot = java.lang.ref.WeakReference<View>(null)
    private var nextProbeAt = 0L
    private val titleBounds = android.graphics.Rect()
    private val contentBounds = android.graphics.Rect()

    private companion object {
        const val WECHAT_BOUNCE_VIEW_CLASS = "com.tencent.mm.ui.widget.pulldown.MMWeUIBounceView"
        const val WECHAT_MINI_PANEL_RECENT_LABEL = "\u6700\u8fd1"
        val WECHAT_MINI_PANEL_CONTENT_LABELS = arrayOf("\u641c\u7d22\u5c0f\u7a0b\u5e8f", "\u6700\u8fd1\u4f7f\u7528\u7684\u5c0f\u7a0b\u5e8f", "\u5e38\u7528\u7684\u5c0f\u7a0b\u5e8f", "\u6211\u7684\u5c0f\u7a0b\u5e8f")
        const val WECHAT_MINI_PANEL_MAX_VIEW_COUNT = 768
        const val WECHAT_MINI_PANEL_MAX_DEPTH = 16
        const val DIAGNOSTIC_TAG = "OfflineGlassGeom"
    }

    fun isVisible(activity: Activity?): Boolean {

        val content = activity?.findViewById<ViewGroup>(android.R.id.content)

        if (content == null) {

            return false

        }

        val cachedTitle = titleAnchor.get()
        val cachedContent = contentAnchor.get()
        if (cachedTitle != null && cachedContent != null &&
            cachedTitle.rootView === content.rootView && cachedContent.rootView === content.rootView &&
            cachedTitle.isShown && cachedContent.isShown && cachedTitle.alpha > .01f && cachedContent.alpha > .01f &&
            cachedTitle.text.toString().trim() == WECHAT_MINI_PANEL_RECENT_LABEL &&
            WECHAT_MINI_PANEL_CONTENT_LABELS.any { cachedContent.text.contains(it) } &&
            cachedTitle.getGlobalVisibleRect(titleBounds) && cachedContent.getGlobalVisibleRect(contentBounds)) return true
        val now = android.os.SystemClock.uptimeMillis()
        if (lastMissRoot.get() === content && now < nextProbeAt) return false
        var foundTitle: TextView? = null
        var foundContent: TextView? = null
        var hasRecentTitle = false

        var hasMiniProgramContent = false





        var visited = 0

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += content to 0

        while (stack.isNotEmpty() && visited++ < WECHAT_MINI_PANEL_MAX_VIEW_COUNT) {

            val (candidate, depth) = stack.removeLast()

            if (candidate.visibility != View.VISIBLE || candidate.alpha <= 0.01f) continue

            if (candidate is TextView) {

                val label = candidate.text?.toString()?.trim().orEmpty()

                if (label == WECHAT_MINI_PANEL_RECENT_LABEL) { hasRecentTitle = true; foundTitle = candidate }

                if (WECHAT_MINI_PANEL_CONTENT_LABELS.any { marker -> label.contains(marker) }) {

                    hasMiniProgramContent = true
                    foundContent = candidate

                }

                if (hasRecentTitle && hasMiniProgramContent) {
                    nextProbeAt = 0L
                    titleAnchor = java.lang.ref.WeakReference(foundTitle)
                    contentAnchor = java.lang.ref.WeakReference(foundContent)
                    return true

                }

            }

            if (candidate is ViewGroup && depth < WECHAT_MINI_PANEL_MAX_DEPTH) {

                // ConversationListView registers the mini-app desktop as a
                // ListView header. Conversation rows cannot own that desktop.
                val childCount = if (candidate is android.widget.ListView &&
                    candidate.javaClass.name == "com.tencent.mm.ui.conversation.ConversationListView") {
                    (candidate.headerViewsCount - candidate.firstVisiblePosition)
                        .coerceIn(0, candidate.childCount)
                } else candidate.childCount
                for (index in childCount - 1 downTo 0) {

                    stack += candidate.getChildAt(index) to (depth + 1)

                }

            }

        }

        if (lastMissRoot.get() !== content) lastMissRoot = java.lang.ref.WeakReference(content)
        nextProbeAt = now + 48L
        return false

    }
}

