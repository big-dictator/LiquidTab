package io.github.offlineglass.hook.adapters.taobao

import android.view.View
import android.view.ViewGroup
import android.widget.TabHost
import java.lang.ref.WeakReference

/** Native hideNavigation animates/hides TBFragmentTabHost itself. Its hidden
 * row is still the main-page router, not evidence that the main page left.
 * Keep our independent navigation while that router remains in this window.
 * Never change native visibility or intercept its business callbacks. */
internal class TaobaoNavigationVisibility {
    private var nativeHost = WeakReference<TabHost>(null)
    private var row = WeakReference<ViewGroup>(null)
    private var sourceType = "none"
    private var pageStatus = "not-read"
    private var pageGetter: java.lang.reflect.Method? = null
    private var viewGetter: java.lang.reflect.Method? = null

    fun bind(source: ViewGroup?) {
        if (source == null || !source.isAttachedToWindow) return
        val cached=nativeHost.get()
        if(row.get()===source&&cached?.isAttachedToWindow==true&&cached.rootView===source.rootView)return
        sourceType = source.javaClass.name
        var ancestor: View? = source
        repeat(8) {
            val current = ancestor ?: return
            if (current is TabHost && current.javaClass.name == "com.taobao.tao.navigation.TBFragmentTabHost") {
                nativeHost = WeakReference(current)
                row = WeakReference(source)
                pageGetter=runCatching{current.javaClass.getMethod("getCurrentFragment")}.getOrNull()
                viewGetter=null
                return
            }
            ancestor = current.parent as? View
        }
    }

    fun retain(host: View?, selected: Int): Boolean {
        host ?: return false
        val native = nativeHost.get() ?: return false
        val source = row.get() ?: return false
        val parent = native.parent as? View ?: return false
        return selected in 0..4 && host.isAttachedToWindow &&
            native.isAttachedToWindow && source.isAttachedToWindow &&
            native.rootView === host.rootView && source.rootView === host.rootView &&
            parent.isShown && parent.width > 0 && parent.height > 0 &&
            source.childCount == 5 && native.currentTab == selected
    }

    fun clear() { nativeHost.clear(); row.clear();pageGetter=null;viewGetter=null }

    fun selected(): Int? = nativeHost.get()?.takeIf { it.isAttachedToWindow }?.currentTab
        ?.takeIf { it in 0..4 }

    fun nativeRouter(): TabHost? = nativeHost.get()?.takeIf { it.isAttachedToWindow }

    fun diagnostic(): String = "source=$sourceType native=${nativeHost.get()?.javaClass?.name} selected=${selected()} page=$pageStatus"

    fun page(): View? = runCatching {
        val native = nativeHost.get()?.takeIf { it.isAttachedToWindow } ?: return null
        val fragment = pageGetter?.invoke(native) ?: return null
        val getter=viewGetter?.takeIf{it.declaringClass.isInstance(fragment)}
            ?: fragment.javaClass.getMethod("getView").also{viewGetter=it}
        (getter.invoke(fragment) as? View)
            .also { pageStatus = if(it!=null) "native-fragment-view" else "pending-view" }
            ?.takeIf { it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 }
    }.onFailure { pageStatus = it.javaClass.simpleName }.getOrNull()
}
