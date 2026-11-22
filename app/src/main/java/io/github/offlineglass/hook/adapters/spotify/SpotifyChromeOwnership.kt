package io.github.offlineglass.hook.adapters.spotify

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.HookConfigReader
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Visual ownership starts before model/feed readiness. Native actions remain intact. */
internal object SpotifyChromeOwnership {
    private val sessions=WeakHashMap<Activity,WeakReference<Session>>()
    fun attach(activity: Activity) {
        val content=activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        sessions[activity]?.get()?.takeIf { it.content===content }?.let { return }
        release(activity)
        Session(activity,content).also { sessions[activity]=WeakReference(it);it.start() }
    }
    fun entering(activity: Activity) { sessions[activity]?.get()?.entering() }
    fun release(activity: Activity) { sessions.remove(activity)?.get()?.stop() }

    private class Session(val activity: Activity,val content: ViewGroup):
        ViewTreeObserver.OnPreDrawListener,ViewTreeObserver.OnGlobalLayoutListener,
        View.OnAttachStateChangeListener {
        private val chrome=SpotifyNativeTopChrome()
        private val ids=HashMap<String,Int>()
        private var observer: ViewTreeObserver?=null
        private var dirty=true
        private var enabled=false
        private var configAt=0L
        private var libraryOwner: WeakReference<View>?=null
        private var libraryNodes=emptyList<Any>()
        private var libraryAttempts=0
        private var libraryAt=0L
        private var searchNodes=emptyList<Any>()
        private var searchAt=0L
        private fun find(root: View,name: String): View? {
            val id=ids.getOrPut(name) { root.resources.getIdentifier(name,"id",activity.packageName) }
            return if(id==0)null else root.findViewById(id)
        }
        fun entering() { dirty=true;libraryAttempts=0;libraryAt=0L }
        fun start() { content.addOnAttachStateChangeListener(this);observe() }
        private fun observe() {
            val tree=content.viewTreeObserver
            if(tree===observer || !tree.isAlive)return
            unobserve()
            tree.addOnGlobalLayoutListener(this);tree.addOnPreDrawListener(this);observer=tree
        }
        private fun unobserve() {
            observer?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalLayoutListener(this);it.removeOnPreDrawListener(this)
            };observer=null
        }
        override fun onGlobalLayout() { dirty=true }
        override fun onViewAttachedToWindow(view: View) { dirty=true;observe() }
        override fun onViewDetachedFromWindow(view: View) { unobserve() }
        override fun onPreDraw(): Boolean {
            val now=SystemClock.uptimeMillis()
            if(activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                if(enabled) { enabled=false;restore() }
                configAt=0L
                return true
            }
            if(configAt==0L || now-configAt>=500L) {
                val next=HookConfigReader.read(activity,activity.packageName).enabled &&
                    activity.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE
                configAt=now
                if(next!=enabled) {
                    enabled=next;dirty=true;libraryAttempts=0
                    if(!enabled)restore()
                }
            }
            if(!enabled || content.windowVisibility!=View.VISIBLE)return true
            chrome.maintain()
            SpotifyLibraryCompose.maintain(libraryNodes)
            SpotifyLibraryCompose.maintain(searchNodes)
            if(!dirty)return true
            dirty=false
            // Stable resource ownership + native containment, never coordinates or R8 names.
            val main=find(content,"main_content") ?: return true
            val navigation=find(main,"navigation_bar") as? ViewGroup
            if(navigation?.javaClass?.name=="com.spotify.mainlayout.ui.view.containers.MainLayoutContentContainerView")
            {
                chrome.hide(navigation)
                for(name in listOf("bottom_gradient","bottom_gradient_black_fade","bottom_gradient_window_insets"))
                    find(main,name)?.let { chrome.hide(it) }
            }
            val filters=find(main,"filters_container") as? ViewGroup
            if(filters!=null && filters.isShown) {
                val bar=find(main,"app_bar") as? ViewGroup
                if(bar!=null && find(bar,"filters_container")===filters)chrome.hide(bar)
            }
            if(searchAt==0L || now-searchAt>=350L) {
                searchAt=now
                SpotifySearchBinding.layout(main)?.let { search ->
                    chrome.hide(search.bar)
                    if(search.composeField)SpotifyLibraryCompose.bindVisual(search.data.search.source,
                        listOf(search.data.search.virtualId),this)?.let { nodes ->
                        SpotifyLibraryCompose.forget(searchNodes.filter { old -> nodes.none { it===old } },this)
                        searchNodes=nodes
                    }
                }
            }
            val fragment=find(main,"fragment_container_host") ?: return true
            if(libraryOwner?.get()?.isAttachedToWindow==false) {
                SpotifyLibraryCompose.forget(libraryNodes,this);libraryNodes=emptyList()
                libraryOwner=null;libraryAttempts=0
            }
            if(libraryNodes.isEmpty() && libraryAttempts<12 && (libraryAt==0L || now-libraryAt>=32L)) {
                libraryAt=now;libraryAttempts++
                runCatching {
                    SpotifyLibraryBinding.read(fragment,chromeOnly=true)?.let { binding ->
                        SpotifyLibraryCompose.bind(binding,this)?.let {
                            libraryNodes=it;libraryOwner=WeakReference(binding.owner)
                        }
                    }
                }.onFailure { android.util.Log.w("SpotifyGlass","early Library chrome contract failed",it) }
            }
            return true // Never cancel the window's frame while semantics are loading.
        }
        private fun restore() {
            chrome.restore()
            SpotifyLibraryCompose.clear(libraryNodes,this)
            SpotifyLibraryCompose.clear(searchNodes,this);searchNodes=emptyList()
            libraryOwner?.get()?.let { SpotifyLibraryCompose.invalidate(it) }
            libraryNodes=emptyList();libraryOwner=null
        }
        fun stop() { unobserve();content.removeOnAttachStateChangeListener(this);restore() }
    }
}
