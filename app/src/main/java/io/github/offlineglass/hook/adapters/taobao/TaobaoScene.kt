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
    private var channelRoot=java.lang.ref.WeakReference<ViewGroup>(null)
    private var channelLabels=emptyList<java.lang.ref.WeakReference<TextView>>()
    private val channelLocation=IntArray(2)
    private var lastDiscovery=0L

    data class Change(val flashSaleChanged: Boolean, val flashSale: Boolean,
                      val fliggyChanged: Boolean)

    fun probe(root: ViewGroup, now: Long = SystemClock.uptimeMillis(), homeSelected: Boolean = true): Change? {
        // Cached homepage tabs can retain selected channel labels off-page.
        // Clear that state immediately when the actual main tab changes.
        if (!homeSelected) {
            if(!flashSaleChannelActive&&!fliggyChannelActive)return null
            val change = Change(flashSaleChannelActive, false, fliggyChannelActive)
            flashSaleChannelActive = false
            fliggyChannelActive = false
            lastFlashProbe = 0L
            return change
        }
        if (now - lastFlashProbe < 120L) return null
        lastFlashProbe = now
        val (flashSale, fliggy) = probeTopChannels(root, now)
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
        channelLabels=emptyList();channelRoot.clear();lastDiscovery=0L;lastFlashProbe=0L
    }

    private fun probeTopChannels(root: ViewGroup, now: Long): Pair<Boolean, Boolean> {
        val topLimit = (root.height * 0.25f).toInt()
        val cached=channelLabels.mapNotNull{it.get()}
        val bound=channelRoot.get()===root&&cached.size==channelLabels.size&&
            cached.all{it.isAttachedToWindow&&it.isShown&&it.rootView===root.rootView}
        // Some home layouts legitimately omit one or both channels. Cache the
        // negative/partial discovery too; selection on bound labels still runs
        // at 120 ms, while missing optional labels no longer trigger full scans.
        if(bound&&now-lastDiscovery<1000L){
            var flash=false;var fliggy=false
            for(label in cached){
                if(!label.isShown)continue
                val text=label.text?.toString()
                if(text!="闪购"&&text!="飞猪"){channelLabels=emptyList();channelRoot.clear();return probeTopChannels(root,now)}
                label.getLocationInWindow(channelLocation)
                var selected=label.isSelected;var ancestor=label.parent as? View
                repeat(3){selected=selected||ancestor?.isSelected==true;ancestor=ancestor?.parent as? View}
                if(selected&&channelLocation[1]<topLimit){if(text=="闪购")flash=true else fliggy=true}
            }
            return flash to fliggy
        }
        lastDiscovery=now
        val found=ArrayList<TextView>(2)
        var flashSale = false
        var fliggy = false
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 1600) {
            val (view, depth) = stack.removeLast()
            if (view.visibility != View.VISIBLE || !view.isShown) continue
            val label = (view as? TextView)?.text?.toString()
            if (view is TextView && (label == "闪购" || label == "飞猪")) {
                val location = channelLocation.also(view::getLocationInWindow)
                if(location[1]<topLimit)found.add(view)
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
        val unique=found.groupBy{it.text.toString()}
        channelRoot=java.lang.ref.WeakReference(root)
        channelLabels=unique.values.filter{it.size==1}.map{java.lang.ref.WeakReference(it.single())}
        // Do not choose a first matching channel when duplicate semantic labels
        // exist; its selected state would affect the wrong page's visibility.
        if(unique["闪购"]?.size!=1)flashSale=false
        if(unique["飞猪"]?.size!=1)fliggy=false
        return flashSale to fliggy
    }
}
