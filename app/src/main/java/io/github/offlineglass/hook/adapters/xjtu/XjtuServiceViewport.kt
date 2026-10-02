package io.github.offlineglass.hook.adapters.xjtu

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.WeakHashMap

/** The service page caches uni.getSystemInfoSync().windowHeight before native tab fitting is removed. */
internal object XjtuServiceViewport {
    private val injected = WeakHashMap<WebView, String>()
    private var lastCheckAt = 0L
    private var lastSelectedIndex = -1

    fun update(scene: ViewGroup?, selectedIndex: Int) {
        if (selectedIndex != lastSelectedIndex) {
            injected.clear()
            lastSelectedIndex = selectedIndex
        }
        if (selectedIndex != 1 || scene == null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCheckAt < 1_000L) return
        lastCheckAt = now
        // Several cached tab WebViews can be marked shown. Identify the service DOM,
        // rather than assuming the last native WebView belongs to the selected tab.
        val stack = ArrayDeque<View>()
        stack += scene
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view is WebView && view.isShown && view.width > 0 && view.height > 0) {
                val key = "${view.url}:${view.width}:${view.height}"
                if (injected[view] != key) {
                    injected[view] = key
                    view.evaluateJavascript(SCRIPT, null)
                }
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) stack += view.getChildAt(i)
            }
        }
    }

    internal val SCRIPT = """(function(){
        if(window.__liquidTabServiceViewport){window.__liquidTabServiceViewport();return;}
        var target=null, observed=null, pending=false, deadline=Date.now()+15000;
        function apply(){
            pending=false;
            target=document.querySelector('.main-box-service[data-v-290cbddc]');
            if(!target) return;
            if(observed!==target){
                observer.disconnect();
                observer.observe(target,{attributes:true,attributeFilter:['style']});
                observed=target;
            }
            var rect=target.getBoundingClientRect();
            if(rect.width<=0 || !window.innerHeight) return;
            var height=Math.max(1,window.innerHeight-rect.top)+'px';
            if(target.style.height!==height || target.style.getPropertyPriority('height')!=='important')
                target.style.setProperty('height',height,'important');
            // Fill with actual content by extending its existing scroll viewport.
            // Keep the native node, scroll position, events, and page colours intact.
        }
        function queue(){if(!pending){pending=true;requestAnimationFrame(apply);}}
        window.__liquidTabServiceViewport=apply;
        window.addEventListener('resize',queue);
        var observer=new MutationObserver(function(records){
            if(!target || !target.isConnected){queue();return;}
            for(var i=0;i<records.length;i++){
                if(records[i].target===target || records[i].type==='childList'){queue();break;}
            }
        });
        observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style']});
        // Some cached pages mount after their WebView first appears in the native tree.
        var timer=setInterval(function(){
            apply();
            if(target || Date.now()>deadline){clearInterval(timer);if(!target)observer.disconnect();}
        },500);
        apply();
    })();"""
}
