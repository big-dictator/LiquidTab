package io.github.offlineglass.hook.adapters.quora

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/** Native sidebar ownership and its verified EdgeToEdgeUtil bottom reservation. */
internal class QuoraSidebar {
    private var sidebar: ViewGroup?=null
    private var panel: View?=null
    private var touch: View?=null
    private var originalBottom: Int?=null
    private val visibleRect=Rect()
    private val background=QuoraSidebarBackground()
    fun update(owner: ViewGroup?) {
        val root=owner?.parent as? ViewGroup ?: return
        if(QuoraAdapter.name(root)!="root_hook")return
        val side=QuoraViews.child(root,"sidebar") as? ViewGroup ?: return
        val p=QuoraViews.child(side,"sidebar_container") ?: return
        val t=QuoraViews.child(side,"touchView") ?: return
        if(sidebar!==side||panel!==p||touch!==t) {
            restore()
            sidebar=side;panel=p;touch=t
        }
        val insets=side.rootWindowInsets ?: return
        val types=WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        if(QuoraNavigationContract.isNavigationPadding(side.paddingBottom,
                insets.getInsets(types).bottom,insets.getInsetsIgnoringVisibility(types).bottom)) {
            originalBottom=side.paddingBottom
            QuoraSidebarInsets.compensate(side,side.paddingBottom)
            side.setPadding(side.paddingLeft,side.paddingTop,side.paddingRight,0)
        }
        background.update(side)
    }
    fun open(): Boolean {
        val side=sidebar ?: return false
        val p=panel ?: return false
        if(!side.isShown)return false
        if(touch?.visibility==View.VISIBLE)return true
        // Native setSideBarX moves the panel offscreen when closed; keep
        // controls suppressed until the closing animation fully leaves.
        return p.getGlobalVisibleRect(visibleRect)&&visibleRect.width()>1&&visibleRect.height()>1
    }
    fun restore() {
        background.restore()
        sidebar?.let(QuoraSidebarInsets::restore)
        sidebar?.let { side -> originalBottom?.let { bottom ->
            if(side.paddingBottom==0) {
                side.setPadding(side.paddingLeft,side.paddingTop,side.paddingRight,bottom);side.requestApplyInsets()
            }
        } }
        sidebar=null;panel=null;touch=null;originalBottom=null
    }
}
