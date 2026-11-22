package io.github.offlineglass.hook.adapters.x

import android.content.res.Configuration
import android.view.View
import android.view.WindowInsets
import android.webkit.WebView
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference

/** Reserve space in the verified Grok WebView's viewport, not in the whole Compose tree. */
internal object XGrokViewport {
    private var webRef: WeakReference<WebView>? = null
    private var heightLimit = 0
    private val hostLocation = IntArray(2)
    private val webLocation = IntArray(2)
    private val candidates=java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView,Boolean>())
    private var scannedRoot=WeakReference<View>(null)
    private var wasActive=false

    private fun isGrok(web: WebView): Boolean = runCatching {
        val url=android.net.Uri.parse(web.url)
        url.host in setOf("x.com","twitter.com","grok.com") &&
            (url.host=="grok.com" || url.path.orEmpty().split('/').contains("grok"))
    }.getOrDefault(false)

    fun install() {
        XposedBridge.hookAllMethods(WebView::class.java,"onAttachedToWindow",object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { (p.thisObject as? WebView)?.let(candidates::add) }
        })
        XposedBridge.hookAllMethods(WebView::class.java,"onDetachedFromWindow",object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                candidates.remove(p.thisObject)
                if(p.thisObject===webRef?.get()) { webRef=null;heightLimit=0 }
            }
        })
        XposedBridge.hookAllMethods(WebView::class.java, "onMeasure", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (p.thisObject !== webRef?.get() || heightLimit <= 0) return
                val spec = p.args[1] as Int
                val size = View.MeasureSpec.getSize(spec)
                if (size > heightLimit) p.args[1] = View.MeasureSpec.makeMeasureSpec(
                    heightLimit, View.MeasureSpec.getMode(spec))
            }
        })
    }

    fun update(host: View) {
        val active = XBottomBarHooks.selectedIndex == 2 &&
            host.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
            host.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
        if (!active) {
            wasActive=false
            if (heightLimit != 0) {
                heightLimit = 0
                webRef?.get()?.requestLayout()
            }
            return
        }
        // One bounded discovery on page entry/root replacement. Failed lookup
        // is cached too; normal frames inspect only observed WebView instances.
        if(!wasActive || scannedRoot.get()!==host.rootView) {
            scannedRoot=WeakReference(host.rootView)
            fun walk(view: View,depth: Int) {
                if(depth>24) return
                if(view is WebView) candidates.add(view)
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) walk(view.getChildAt(i),depth+1)
            }
            walk(host.rootView,0)
        }
        wasActive=true
        val valid=candidates.filter { it.isAttachedToWindow && it.isShown && it.rootView===host.rootView && isGrok(it) }
        val web=valid.singleOrNull() ?: run {
            if(heightLimit!=0) { heightLimit=0;webRef?.get()?.requestLayout() };webRef=null
            return
        }
        webRef=WeakReference(web)
        host.getLocationInWindow(hostLocation)
        web.getLocationInWindow(webLocation)
        val limit = hostLocation[1] - webLocation[1] - (16f * host.resources.displayMetrics.density).toInt()
        if (limit > 0 && heightLimit != limit) {
            heightLimit = limit
            web.requestLayout()
        }
    }
}
