package io.github.offlineglass.hook.adapters.xhs

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** The note page's public view contracts and the title row they own. */
internal data class XhsNoteTopBinding(
    val page: RelativeLayout,
    val feed: ViewGroup,
    val header: LinearLayout,
    val status: View,
    val container: FrameLayout,
    val title: ViewGroup,
    val row: RelativeLayout,
    val titleStatus: View,
    val back: ImageView,
    val avatar: View,
    val avatarFrame: View,
    val name: TextView,
    val nameGroup: ViewGroup,
    val share: ImageView,
    private val legacyFollow: TextView?,
) {
    data class Follow(val control: View, val label: TextView)

    private val avatarDiameterGetter = runCatching {
        // XYAvatarView reserves space around the image for optional rings and badges.
        avatar.javaClass.getMethod("getActualWidth").takeIf { it.returnType == Int::class.javaPrimitiveType }
    }.getOrNull()

    fun avatarDiameter(): Int {
        val limit = minOf(avatar.width, avatar.height)
        val diameter = runCatching { avatarDiameterGetter?.invoke(avatar) as? Int }.getOrNull()
        return diameter?.takeIf { it in 1..limit } ?: limit
    }

    fun followControls(): List<Follow> {
        return row.children().mapNotNull { child ->
            if (!child.hasOnClickListeners()) return@mapNotNull null
            val label = if (child === legacyFollow) legacyFollow else (child as? FrameLayout)?.children()
                ?.filterIsInstance<TextView>()
                ?.singleOrNull { it.javaClass.name == "com.xingin.follow.followbutton.impl.FollowTextView" }
            label?.let { Follow(child, it) }
        }
    }

    fun isCurrent(): Boolean = page.isAttachedToWindow && feed.parent === page && header.parent === feed &&
        container.parent === page && title.parent === container && row.parent === title &&
        share.parent === row && nameGroup.parent === row && avatarFrame.parent?.parent === row &&
        back.parent === avatarFrame.parent && avatar.parent === avatarFrame && name.isAttachedToWindow

    companion object {
        private const val PAGE = "com.xingin.matrix.notedetail.notecontent.NoteDetailContentView"
        private const val TITLE = "com.xingin.matrix.notedetail.titlebar.TitlebarView"
        private const val STATUS = "com.xingin.matrix.base.widgets.StatusBarView"

        fun find(root: ViewGroup, ownedFeed: View?): XhsNoteTopBinding? {
            val content = root.findViewById<ViewGroup>(android.R.id.content) ?: return null
            val pages = ArrayList<RelativeLayout>(1)
            var budget = 48
            fun visit(view: View, depth: Int) {
                if (budget-- <= 0) return
                if (view is RelativeLayout && view.javaClass.name == PAGE) {
                    pages.add(view)
                    return
                }
                if (depth > 0 && view is ViewGroup && view !is AdapterOwnedOverlay)
                    view.children().forEach { visit(it, depth - 1) }
            }
            visit(content, 4)
            if (budget < 0) return null
            val page = pages.singleOrNull() ?: return null
            val feed = page.nativeView("getNestedScrollLayout") as? ViewGroup ?: return null
            val container = page.nativeView("getTitleBarContainer") as? FrameLayout ?: return null
            if (feed.parent !== page || container.parent !== page ||
                feed.javaClass.name != "com.xingin.matrix.base.widgets.NestedScrollLayout" ||
                page.indexOfChild(feed) >= page.indexOfChild(container)) return null
            val header = page.nativeView("getNestedHeaderContainer") as? LinearLayout ?: return null
            if (feed.getChildAt(0) !== header || header.orientation != LinearLayout.VERTICAL) return null
            val status = page.children().singleOrNull { it.javaClass.name == STATUS } ?: return null
            val params = feed.layoutParams as? RelativeLayout.LayoutParams ?: return null
            val below = params.getRule(RelativeLayout.BELOW)
            if (status.id == View.NO_ID || below != status.id &&
                !(feed === ownedFeed && below == 0 && params.topMargin == 0)) return null
            val title = container.children().singleOrNull { it is LinearLayout && it.javaClass.name == TITLE }
                as? ViewGroup ?: return null
            val row = title.children().filterIsInstance<RelativeLayout>().singleOrNull() ?: return null
            val titleStatus = title.children().singleOrNull { it.javaClass.name == STATUS } ?: return null
            val avatar = title.nativeView("getAvatarView") ?: return null
            val avatarFrame = avatar.parent as? FrameLayout ?: return null
            val left = avatarFrame.parent as? LinearLayout ?: return null
            val share = title.nativeView("getMoreView") as? ImageView ?: return null
            val nameId = row.resources.getIdentifier("nickNameTV", "id", row.context.packageName)
            if (nameId == 0 || left.parent !== row || share.parent !== row) return null
            val names = row.children().filterIsInstance<LinearLayout>().mapNotNull { group ->
                (group.findViewById<View>(nameId) as? TextView)?.let { group to it }
            }.singleOrNull() ?: return null
            val back = left.children().filterIsInstance<ImageView>().singleOrNull { it.hasOnClickListeners() }
                ?: return null
            // The presenter subscribes to the TitlebarView for author clicks.
            if (!title.hasOnClickListeners() || !share.hasOnClickListeners()) return null
            return XhsNoteTopBinding(page, feed, header, status, container, title, row, titleStatus, back, avatar,
                avatarFrame, names.second, names.first, share, title.nativeView("getFollowTV") as? TextView)
        }
    }
}

private fun View.nativeView(getter: String): View? = runCatching {
    val method = javaClass.getMethod(getter)
    if (View::class.java.isAssignableFrom(method.returnType)) method.invoke(this) as? View else null
}.getOrNull()

private fun ViewGroup.children(): List<View> = List(childCount, ::getChildAt)
