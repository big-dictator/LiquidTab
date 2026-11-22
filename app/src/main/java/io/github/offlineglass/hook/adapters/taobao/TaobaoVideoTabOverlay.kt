package io.github.offlineglass.hook.adapters.taobao

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ViewAnimator
import java.util.WeakHashMap
import kotlin.math.abs

/** Native video notification lives beside TBFragmentTabHost, outside its alpha. */
internal class TaobaoVideoTabOverlay {
    private val hidden = WeakHashMap<View, Int>()
    private val tabLocation = IntArray(2)
    private val candidateLocation = IntArray(2)
    private val forcedHidden = WeakHashMap<View, Int>()

    fun update(navigation: ViewGroup?) {
        navigation ?: return
        var ancestor: View? = navigation
        var tabHost: ViewGroup? = null
        repeat(4) {
            if (ancestor?.javaClass?.name == "com.taobao.tao.navigation.TBFragmentTabHost") {
                tabHost = ancestor as? ViewGroup
            }
            ancestor = ancestor?.parent as? View
        }
        val tabs = tabHost ?: return
        val parent = tabs.parent as? ViewGroup ?: return
        val video = navigation.getChildAt(1) ?: return
        if (navigation.childCount != 5 || video.width <= 0 || tabs.height <= 0) return
        video.getLocationInWindow(tabLocation)
        val videoCenter = tabLocation[0] + video.width / 2f
        tabs.getLocationInWindow(tabLocation)
        val nativeBottom = tabLocation[1] + tabs.height
        val tolerance = 8f * tabs.resources.displayMetrics.density
        // Only direct native-tab siblings; never traverse page content or message avatars.
        for (index in 0 until parent.childCount) {
            val candidate = parent.getChildAt(index)
            if (hidden.containsKey(candidate)) {
                if (candidate.visibility == View.VISIBLE) candidate.visibility = View.INVISIBLE
                continue
            }
            if (candidate !is FrameLayout || candidate.id != View.NO_ID ||
                candidate.childCount != 2 || candidate.visibility != View.VISIBLE ||
                candidate.width !in video.width..(video.width * 3) ||
                candidate.height !in (tabs.height / 2)..(tabs.height * 2)) continue
            val imageAnimator = candidate.getChildAt(0) as? ViewAnimator ?: continue
            val badge = candidate.getChildAt(1) as? FrameLayout ?: continue
            if (badge.childCount != 1 || badge.width > video.width || badge.height > tabs.height) continue
            val badgeAnimator = badge.getChildAt(0) as? ViewAnimator ?: continue
            if (imageAnimator.childCount == 0 || badgeAnimator.childCount == 0) continue
            if ((0 until imageAnimator.childCount).any {
                    imageAnimator.getChildAt(it).javaClass.name != "com.taobao.uikit.extend.feature.view.TUrlImageView"
                } || (0 until badgeAnimator.childCount).any { badgeAnimator.getChildAt(it) !is TextView }) continue
            candidate.getLocationInWindow(candidateLocation)
            if (abs(candidateLocation[1] + candidate.height - nativeBottom) > tolerance ||
                abs(candidateLocation[0] + candidate.width / 2f - videoCenter) > tolerance) continue
            hidden[candidate] = candidate.visibility
            candidate.visibility = View.INVISIBLE
        }
    }

    /** Hide only the stable semantic tab while preserving its native router slot. */
    fun hideVideoTabLabel(labels: List<TextView>, selectedIndex: Int): List<Pair<TextView, Int>> {
        val video = labels.singleOrNull { it.text?.toString()?.trim() == "视频" } ?: return emptyList()
        if (selectedIndex == 1 || video.parent == null) return emptyList()
        val old = video.visibility
        if (old == View.VISIBLE) video.visibility = View.INVISIBLE
        return listOf(video to old)
    }

    fun dispose() {
        for ((view, visibility) in hidden) {
            if (view.visibility == View.INVISIBLE) view.visibility = visibility
        }
        hidden.clear()
        for ((view, visibility) in forcedHidden) if (view.visibility == View.INVISIBLE) view.visibility = visibility
        forcedHidden.clear()
    }
}
