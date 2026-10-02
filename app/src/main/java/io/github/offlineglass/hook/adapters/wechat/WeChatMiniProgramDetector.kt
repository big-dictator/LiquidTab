package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XposedBridge

internal class WeChatMiniProgramDetector {
    private var lastWeChatPanelSignature = ""
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

        var hasRecentTitle = false

        var hasMiniProgramContent = false

        val bounceStates = ArrayList<String>()

        val panelClasses = linkedSetOf<String>()

        var visited = 0

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += content to 0

        while (stack.isNotEmpty() && visited++ < WECHAT_MINI_PANEL_MAX_VIEW_COUNT) {

            val (candidate, depth) = stack.removeLast()

            if (candidate.visibility != View.VISIBLE || candidate.alpha <= 0.01f) continue

            if (candidate.javaClass.name == WECHAT_BOUNCE_VIEW_CLASS) {

                val rect = Rect()

                candidate.getGlobalVisibleRect(rect)

                bounceStates += "y=${candidate.y.toInt()},ty=${candidate.translationY.toInt()}," +

                    "sy=${candidate.scrollY},h=${candidate.height},r=${rect.top}:${rect.bottom}," +

                    "c=${(candidate as? ViewGroup)?.childCount ?: -1}"

            }

            val className = candidate.javaClass.name

            if (className.contains("appbrand", ignoreCase = true) ||

                className.contains("pulldown", ignoreCase = true) ||

                className.contains("bounce", ignoreCase = true) ||

                className.contains("recent", ignoreCase = true)

            ) {

                panelClasses += className

            }

            if (candidate is TextView) {

                val label = candidate.text?.toString()?.trim().orEmpty()

                if (label == WECHAT_MINI_PANEL_RECENT_LABEL) hasRecentTitle = true

                if (WECHAT_MINI_PANEL_CONTENT_LABELS.any { marker -> label.contains(marker) }) {

                    hasMiniProgramContent = true

                }

                if (hasRecentTitle && hasMiniProgramContent) {

                    return true

                }

            }

            if (candidate is ViewGroup && depth < WECHAT_MINI_PANEL_MAX_DEPTH) {

                for (index in candidate.childCount - 1 downTo 0) {

                    stack += candidate.getChildAt(index) to (depth + 1)

                }

            }

        }

        val signature = bounceStates.joinToString("|") + ";classes=" + panelClasses.joinToString(",")

        if (signature != lastWeChatPanelSignature) {

            lastWeChatPanelSignature = signature

            Log.i(DIAGNOSTIC_TAG, "wechat-bounce=$signature")

            XposedBridge.log("[$DIAGNOSTIC_TAG] wechat-bounce=$signature")

        }

        return false

    }
}

