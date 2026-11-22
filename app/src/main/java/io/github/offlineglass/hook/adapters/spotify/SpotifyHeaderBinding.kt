package io.github.offlineglass.hook.adapters.spotify

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo

/** Native Compose virtual buttons through the Android accessibility contract, not R8 names. */
internal class SpotifyHeaderBinding(private val root: ViewGroup, private val actionsAllowed: () -> Boolean) {
    data class Button(val label: String, val selected: Boolean, val source: View,
        val virtualId: Int, val bounds: Rect, val artwork: Rect = bounds, val artworkId: Int = virtualId,val nativeAction: Boolean=false, val profileHeaderOwner: Boolean=false) {
        fun click(): Boolean {
            if(nativeAction)return source.isAttachedToWindow && source.isShown && source.isEnabled && source.performClick()
            val provider = source.accessibilityNodeProvider ?: return false
            return provider.performAction(virtualId, AccessibilityNodeInfo.ACTION_CLICK, Bundle.EMPTY)
        }
        fun drawAvatar(canvas: Canvas, size: Float) {
            if (!source.isAttachedToWindow || artwork.isEmpty) return
            val pos = IntArray(2); source.getLocationOnScreen(pos)
            val save = canvas.save()
            val scale = size / maxOf(artwork.width(), artwork.height())
            canvas.scale(scale, scale)
            canvas.translate(pos[0] - artwork.left.toFloat(), pos[1] - artwork.top.toFloat())
            source.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
    var buttons: List<Button> = emptyList(); private set
    var avatar: Button? = null; private set
    private val childId = runCatching {
        AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }.getOrNull()
    private val known = HashMap<Pair<View, Int>, String>()
    private val primary = LinkedHashMap<String, Button>()
    private var scrollTarget: Pair<View, Int>? = null
    private var discovering = false
    private var discoveryAttempts = 0
    private fun live()=root.isAttachedToWindow && root.windowVisibility==View.VISIBLE && actionsAllowed()
    fun discoverHidden() {
        if (!live() || discovering || discoveryAttempts>=2 || primary.keys.containsAll(listOf("all","music","podcasts","audiobooks"))) return
        val target=scrollTarget ?: return
        discoveryAttempts++
        if (target.first.accessibilityNodeProvider?.performAction(target.second, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, Bundle.EMPTY) != true) return
        discovering=true
        root.postDelayed({ if(live())refresh();discovering=false },250L)
    }
    fun click(label: String): Boolean {
        if(!live())return false
        if(!refresh())return false
        fun current()=avatar?.takeIf { it.label==label } ?: buttons.singleOrNull { it.label==label }
        if(current()?.click()==true)return true
        val target=scrollTarget ?: return false
        // Native LazyRow may not expose an off-screen chip's click action.
        // Reveal through its native scroll action and look up the fresh label;
        // never reuse stale screen coordinates or invent a page index.
        val provider=target.first.accessibilityNodeProvider ?: return false
        provider.performAction(target.second,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,Bundle.EMPTY)
        root.postDelayed({
            if(live()) {
                refresh()
                if(current()?.click()!=true) {
                    provider.performAction(target.second,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,Bundle.EMPTY)
                    root.postDelayed({ if(live()) { refresh();current()?.click() } },250L)
                }
            }
        },250L)
        return true
    }

    fun refresh(): Boolean {
        primary.entries.removeAll { !it.value.source.isAttachedToWindow }
        known.keys.removeAll { !it.first.isAttachedToWindow }
        val found = ArrayList<Button>()
        var face: Button? = null
        val anonymous=ArrayList<Button>()
        fun read(owner: View) {
            val provider = owner.accessibilityNodeProvider ?: return
            var count = 0
            val visited = HashSet<Int>()
            fun visit(id: Int, actionId: Int?, actionBounds: Rect?, checked: Boolean, depth: Int) {
                if (depth > 16 || count++ >= 128 || !visited.add(id)) return
                val node = provider.createAccessibilityNodeInfo(id) ?: return
                try {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if(node.isScrollable && bounds.width()>bounds.height()*2) scrollTarget=owner to id
                    val targetId = if (node.isClickable) id else actionId
                    val targetBounds = if (node.isClickable) bounds else actionBounds
                    val selected = checked || node.isChecked || node.isSelected
                    val text = node.text?.toString().orEmpty()
                    val description = node.contentDescription?.toString().orEmpty()
                    if (targetId != null && targetBounds != null && !targetBounds.isEmpty) {
                        if(text.isBlank() && description.isBlank())anonymous+=Button("",false,owner,targetId,Rect(targetBounds),Rect(bounds),id)
                        if (text.isNotBlank()) {
                            known[owner to targetId] = text
                            found += Button(text, selected, owner, targetId, Rect(targetBounds))
                        }
                        if (description.contains("profile", true) || description.contains("头像") ||
                            description.contains("个人资料") || description.contains("个人主页"))
                            face = Button(description, false, owner, targetId, Rect(targetBounds), Rect(bounds), id)
                    }
                    for (i in 0 until node.childCount) {
                        val raw = childId?.invoke(node, i) as? Long ?: continue
                        visit((raw shr 32).toInt(), targetId, targetBounds, selected, depth + 1)
                    }
                    if (node.isClickable && text.isBlank() && !bounds.isEmpty) {
                        known[owner to id]?.let { previous ->
                            if (found.none { it.source === owner && it.virtualId == id })
                                found += Button(previous, selected, owner, id, Rect(bounds))
                        }
                    }
                } finally { @Suppress("DEPRECATION") node.recycle() }
            }
            visit(View.NO_ID, null, null, false, 0)
        }
        fun owners(view: View) {
            if (view.accessibilityNodeProvider != null) { read(view); return }
            if (view is ViewGroup) for (i in 0 until view.childCount) owners(view.getChildAt(i))
        }
        owners(root)
        val next = found.distinctBy { it.source to it.virtualId }.filterNot {
            it.source===face?.source && it.virtualId==face?.virtualId }.sortedBy { it.bounds.left }
        next.filter { role(it.label)!="secondary" }.forEach { primary[role(it.label)]=it }
        val selectedRole=next.firstOrNull { it.selected && role(it.label)!="secondary" }?.let { role(it.label) }
        if(selectedRole!=null) primary.replaceAll { key,value -> value.copy(selected=key==selectedRole) }
        if(face==null && primary["all"]!=null && primary["music"]!=null) {
            val first=primary["all"] ?: return false
            face=anonymous.filter { it.bounds.width()>0 && kotlin.math.abs(it.bounds.width()-it.bounds.height())<=2 &&
                it.bounds.right<=first.bounds.left }.groupBy { it.source to it.virtualId }.values
                .mapNotNull { group -> group.minByOrNull { it.artwork.width().toLong()*it.artwork.height() } }.singleOrNull()
        }
        // Only the Home filters qualify; Library/Search chips must stay native.
        if (face == null || primary["all"]==null || primary["music"]==null) return false
        avatar = face
        buttons=buildList {
            for(key in listOf("all","music","podcasts","audiobooks")) primary[key]?.let { button ->
                add(button)
                if(button.selected && key in listOf("music","podcasts"))
                    addAll(next.filter { role(it.label)=="secondary" })
            }
        }
        return true
    }
    companion object {
        fun role(label: String): String = when (label.lowercase()) {
            "all", "全部" -> "all"
            "music", "音乐" -> "music"
            "podcasts", "播客" -> "podcasts"
            "audiobooks", "有声书" -> "audiobooks"
            else -> "secondary"
        }
    }
}
