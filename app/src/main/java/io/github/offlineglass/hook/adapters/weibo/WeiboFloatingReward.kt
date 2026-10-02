package io.github.offlineglass.hook.adapters.weibo

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun GlassHostLayout.adjustWeiboFloatingReward() {
    val weiboState = appNavigationState as? WeiboNavigationState ?: return

        if (width <= 0 || height <= 0) return

        val now = SystemClock.uptimeMillis()

        val cached = weiboState.rewardTarget

        if (cached == null || !cached.isAttachedToWindow || now - weiboState.lastRewardProbe > 700L) {

            weiboState.lastRewardProbe = now

            val root = rootView as? ViewGroup ?: return

            val hostLocation = IntArray(2).also(::getLocationInWindow)

            val hostTop = hostLocation[1]

            var best: View? = null

            var bestScore = Int.MIN_VALUE

            val stack = ArrayDeque<View>()

            stack.add(root)

            while (stack.isNotEmpty()) {

                val view = stack.removeLast()

                if (view === this || isDescendantOf(view, this)) continue

                if (view is ViewGroup) {

                    for (index in 0 until view.childCount) stack.add(view.getChildAt(index))

                }

                if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) continue

                val idName = viewResourceEntryName(view).orEmpty().lowercase()

                val description = view.contentDescription?.toString().orEmpty()

                val text = (view as? TextView)?.text?.toString().orEmpty()

                val className = view.javaClass.name.lowercase()

                val marker = "$idName $description $text $className".lowercase()

                val keyword = marker.contains("红包") || marker.contains("激活") ||

                    marker.contains("必得") || marker.contains("点击激活") || marker.contains("领取") ||

                    marker.contains("redpacket") || marker.contains("hongbao") || marker.contains("reward") ||

                    className.contains("redpacket") || className.contains("feedredpacketpopview")

                if (!keyword) continue

                var candidate = view

                var parent = candidate.parent as? ViewGroup

                while (parent != null && parent !== root &&

                    parent.width <= 240f * density && parent.height <= 280f * density

                ) {

                    candidate = parent

                    parent = parent.parent as? ViewGroup

                }

                val location = IntArray(2).also(candidate::getLocationInWindow)

                val rightSide = location[0] + candidate.width / 2 > root.width * 0.68f

                if (!rightSide) continue

                val bottom = location[1] + candidate.height

                val score = (if (bottom > hostTop - 220f * density) 80 else 0) +

                    (if (idName.contains("red") || idName.contains("reward")) 60 else 0) -

                    abs(bottom - hostTop) / density.toInt().coerceAtLeast(1)

                if (score > bestScore) {

                    bestScore = score

                    best = candidate

                }

            }

            if (best !== cached) {

                cached?.let { old ->

                    weiboState.rewardBaseTranslations[old]?.let { base -> old.translationY = base }

                }

                weiboState.rewardTarget = best

            }

        }

        val target = weiboState.rewardTarget ?: return

        val base = weiboState.rewardBaseTranslations.getOrPut(target) { target.translationY }

        val hostLocation = IntArray(2).also(::getLocationInWindow)

        val targetLocation = IntArray(2).also(target::getLocationInWindow)

        val safeGap = ((layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0)

            .coerceAtLeast((8f * density).roundToInt())

        val currentBottom = targetLocation[1] + target.height

        val baseBottom = currentBottom - (target.translationY - base).roundToInt()

        val wanted = (base + (hostLocation[1] - safeGap - baseBottom)).coerceAtMost(base)

        if (abs(target.translationY - wanted) > 0.5f) target.translationY = wanted

        var parent = target.parent as? ViewGroup

        repeat(4) {

            parent?.clipChildren = false

            parent?.clipToPadding = false

            parent = parent?.parent as? ViewGroup

        }

    }
