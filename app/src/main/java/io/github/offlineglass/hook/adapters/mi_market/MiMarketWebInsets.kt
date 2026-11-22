package io.github.offlineglass.hook.adapters.mi_market

import android.webkit.WebView
import java.util.WeakHashMap
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XC_MethodHook

/** Web padding belongs inside the document so it can scroll behind the chrome. */
internal object MiMarketWebInsets {
    private val insets = WeakHashMap<WebView, Pair<Int, Int>>()
    private var installed = false
    private val callbacks = HashSet<java.lang.reflect.Method>()
    private fun bindClient(view: WebView) {
        val client = view.webViewClient ?: return
        for (name in listOf("onPageCommitVisible", "onPageFinished")) {
            val method = MiMarketBindingContracts.pageCallback(client.javaClass, WebView::class.java, name)
                ?: continue
            if (method.returnType != Void.TYPE || !callbacks.add(method)) continue
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val target = param.args.firstOrNull() as? WebView ?: return
                    if (target.webViewClient !== param.thisObject) return
                    insets[target]?.let { inject(target,it.first,it.second) }
                }
            })
        }
    }
    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        XposedBridge.hookAllMethods(WebView::class.java, "setWebViewClient", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? WebView ?: return
                    if (insets.containsKey(view)) bindClient(view)
                }
            })
    }
    fun apply(view: WebView, top: Int, bottom: Int) {
        bindClient(view)
        val value = top to bottom
        if (insets[view] == value) return
        insets[view] = value
        inject(view,top,bottom)
    }
    private fun inject(view: WebView, top: Int, bottom: Int) {
        view.evaluateJavascript("""(function(){
            window.__liquidtabMarketInsets=[$top,$bottom];
            function apply(){var v=window.__liquidtabMarketInsets;if(!v||!document.body||!document.head)return;
              var s=document.getElementById('liquidtab-market-insets');
              if(!s){s=document.createElement('style');s.id='liquidtab-market-insets';
                var c=getComputedStyle(document.body);s.dataset.top=parseFloat(c.paddingTop)||0;s.dataset.bottom=parseFloat(c.paddingBottom)||0;document.head.appendChild(s);}
              var ratio=devicePixelRatio||1;s.textContent='body{padding-top:'+((+s.dataset.top)+v[0]/ratio)+'px!important;padding-bottom:'+((+s.dataset.bottom)+v[1]/ratio)+'px!important;}';}
            apply();if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',apply,{once:true});
        })()""",null)
    }
    fun remove(view: WebView) {
        if (insets.remove(view) == null) return
        // A recreated Activity may already have destroyed this document.
        if (!view.isAttachedToWindow) return
        view.evaluateJavascript("window.__liquidtabMarketInsets=null;document.getElementById('liquidtab-market-insets')?.remove();",null)
    }
}
