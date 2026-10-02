package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal class WeChatNativeFrostController {
    private var lastWeChatFrostSelection = -1
    private val weChatFrostedPages = ArrayList<View>(4)
    private val weChatFrostDisplayReset = ArrayList<View>(4)
    private var weChatFrostSuppressionLogged = false
    private var weChatBottomScanLogged = false
    private companion object {
        const val VIEW_PAGER_CLASS = "com.tencent.mm.ui.base.CustomViewPager"
        const val FROSTED_CONTENT_VIEW_CLASS = "com.tencent.mm.ui.FrostedContentView"
        const val BOUNCE_VIEW_CLASS = "com.tencent.mm.ui.widget.pulldown.MMWeUIBounceView"
    }

    fun suppress(source: ViewGroup?, hostRoot: View, selected: Int) {

        if (source == null) return

        if (source.visibility != View.VISIBLE) source.visibility = View.VISIBLE

        if (source.alpha != 0f) source.alpha = 0f

        // Never clear arbitrary bottom-screen backgrounds here. Even with the

        // navigation edge immersed, chat/detail surfaces own their backgrounds;

        // only LauncherUI's page-scoped Frost is safe to suppress.

        suppressNativeWeChatBottomFrost(source, hostRoot, selected)

    }

    private fun suppressNativeWeChatBottomFrost(source: ViewGroup, hostRoot: View, selected: Int) {

        // LauncherUIBottomTabView.onLayout() forwards its height/translation to

        // the FrostedContentView belonging to each ViewPager page. Contacts and

        // Discovery can be created after that initial layout, leaving their

        // bottomBlurAreaHeight at the native navigation height until a theme

        // change happens to lay the tab row out again. Update those direct page

        // children during pre-draw, after layout but before pixels are emitted.

        val navigationParent = source.parent as? ViewGroup ?: return

        for (parentIndex in 0 until navigationParent.childCount) {

            val pager = navigationParent.getChildAt(parentIndex) as? ViewGroup ?: continue

            if (pager.javaClass.name != VIEW_PAGER_CLASS) continue

            val selectionChanged = selected != lastWeChatFrostSelection

            if (selectionChanged && selected >= 0) {

                // The target page now exists. Trigger the same tab-row

                // onLayout callback that WeChat normally reaches only during

                // a configuration/theme change.

                source.forceLayout()

                source.requestLayout()

                navigationParent.requestLayout()

            }

            removeWeChatNativeTabInset(pager, source.height)

            if (selectionChanged ||

                weChatFrostedPages.isEmpty() ||

                weChatFrostedPages.any { !it.isAttachedToWindow }

            ) {

                lastWeChatFrostSelection = selected

                weChatFrostedPages.clear()

                val stack = ArrayDeque<Pair<View, Int>>()

                stack += pager to 0

                while (stack.isNotEmpty()) {

                    val (candidate, depth) = stack.removeLast()

                    var candidateClass: Class<*>? = candidate.javaClass

                    while (candidateClass != null) {

                        if (candidateClass.name == FROSTED_CONTENT_VIEW_CLASS) {

                            weChatFrostedPages += candidate

                            break

                        }

                        candidateClass = candidateClass.superclass

                    }

                    if (candidate is ViewGroup && depth < 8) {

                        for (index in 0 until candidate.childCount) {

                            stack += candidate.getChildAt(index) to (depth + 1)

                        }

                    }

                }

            }

            for (page in weChatFrostedPages) {

                if (page !in weChatFrostDisplayReset) {

                    weChatFrostDisplayReset += page

                    runCatching {

                        XposedHelpers.callMethod(page, "setFrostedEnabled", false)

                        XposedHelpers.callMethod(page, "setBottomBlurAreaHeight", 0)

                        // Drop the display list that may already contain the

                        // native 171 px strip, then return to normal hardware

                        // rendering on the following frame.

                        page.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                        hostRoot.invalidate()

                        page.postOnAnimation {

                            page.setLayerType(View.LAYER_TYPE_NONE, null)

                            page.invalidate()

                            (page.parent as? View)?.invalidate()

                            hostRoot.invalidate()

                        }

                    }

                }

                val bottomHeight = runCatching {

                    XposedHelpers.callMethod(page, "getBottomBlurAreaHeight") as? Int

                }.getOrNull()

                if (bottomHeight == null || bottomHeight != 0) {

                    runCatching {

                        XposedHelpers.callMethod(page, "setBottomBlurAreaHeight", 0)

                        page.invalidate()

                        if (!weChatFrostSuppressionLogged) {

                            weChatFrostSuppressionLogged = true

                            XposedBridge.log(

                                "[OfflineGlass][WeChatFrost] ${page.javaClass.name} bottom=$bottomHeight -> 0",

                            )

                        }

                    }

                }

            }

            // Diagnostic: scan for visible views in the bottom tab-bar area

            if (!weChatBottomScanLogged) {

                val root = hostRoot as? ViewGroup

                if (root != null && root.width > 0 && root.height > 0 && source.height > 0) {

                    val barTop = root.height - source.height - 100

                    val barBottom = root.height

                    val rect = android.graphics.Rect()

                    val diagStack = ArrayDeque<Pair<View, Int>>()

                    diagStack += root to 0

                    val hits = mutableListOf<String>()

                    while (diagStack.isNotEmpty()) {

                        val (v, d) = diagStack.removeLast()

                        if (v.getGlobalVisibleRect(rect) &&

                            rect.bottom > barTop && rect.top < barBottom &&

                            v.alpha > 0.01f && v.visibility == View.VISIBLE

                        ) {

                            val bg = (v as? ViewGroup)?.background ?: (v.background)

                            hits.add("  d$d ${v.javaClass.name} b=${rect.top}-${rect.bottom} alpha=${v.alpha} bg=${bg != null}")

                        }

                        if (v is ViewGroup && d < 12) {

                            for (i in 0 until v.childCount) diagStack += v.getChildAt(i) to (d + 1)

                        }

                    }

                    if (hits.isNotEmpty()) {

                        weChatBottomScanLogged = true

                        XposedBridge.log("[OfflineGlass][WeChatBottomScan] barTop=$barTop barBottom=$barBottom hits=${hits.size}")

                        hits.forEach { XposedBridge.log("[OfflineGlass][WeChatBottomScan] $it") }

                    }

                }

            }

            return

        }

    }

    private fun removeWeChatNativeTabInset(pager: ViewGroup, nativeTabHeight: Int) {

        if (nativeTabHeight <= 0) return

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += pager to 0

        while (stack.isNotEmpty()) {

            val (candidate, depth) = stack.removeLast()

            if (candidate.javaClass.name == BOUNCE_VIEW_CLASS) {

                val margins = candidate.layoutParams as? ViewGroup.MarginLayoutParams

                if (margins != null && margins.bottomMargin in 1..(nativeTabHeight + 8)) {

                    margins.bottomMargin = 0

                    candidate.layoutParams = margins

                    candidate.requestLayout()

                }

            }

            if (candidate is ViewGroup && depth < 5) {

                for (index in 0 until candidate.childCount) {

                    stack += candidate.getChildAt(index) to (depth + 1)

                }

            }

        }

    }
}

