package io.github.offlineglass.hook.adapters.taobao

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Per-window Taobao channel and bar-gesture state. No global app state. */
internal class TaobaoScene {
    var flashSaleChannelActive = false
    var fliggyChannelActive = false
    var barPriorityUntil = 0L
    private var lastFlashProbe = 0L

    data class Change(val flashSaleChanged: Boolean, val flashSale: Boolean,
                      val fliggyChanged: Boolean)

    fun probe(root: ViewGroup, now: Long = SystemClock.uptimeMillis()): Change? {
        if (now - lastFlashProbe < 120L) return null
        lastFlashProbe = now
        val (flashSale, fliggy) = probeTopChannels(root)
        val change = Change(flashSale != flashSaleChannelActive, flashSale,
            fliggy != fliggyChannelActive)
        flashSaleChannelActive = flashSale
        fliggyChannelActive = fliggy
        return change
    }

    fun reset() {
        flashSaleChannelActive = false
        fliggyChannelActive = false
        barPriorityUntil = 0L
    }

    private fun probeTopChannels(root: ViewGroup): Pair<Boolean, Boolean> {
        val topLimit = (root.height * 0.25f).toInt()
        var flashSale = false
        var fliggy = false
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 1600) {
            val (view, depth) = stack.removeLast()
            if (view.visibility != View.VISIBLE) continue
            val label = (view as? TextView)?.text?.toString()
            if (view is TextView && (label == "闪购" || label == "飞猪")) {
                val location = IntArray(2).also(view::getLocationInWindow)
                var selected = view.isSelected
                var ancestor = view.parent as? View
                repeat(3) {
                    selected = selected || ancestor?.isSelected == true
                    ancestor = ancestor?.parent as? View
                }
                if (selected && location[1] < topLimit) {
                    if (label == "闪购") flashSale = true else fliggy = true
                    if (flashSale && fliggy) break
                }
            }
            if (view is ViewGroup && depth < 32) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
        return flashSale to fliggy
    }
}
