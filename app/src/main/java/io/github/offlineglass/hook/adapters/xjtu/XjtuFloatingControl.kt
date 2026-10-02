package io.github.offlineglass.hook.adapters.xjtu

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import io.github.offlineglass.hook.GlassHostLayout

/** Reposition only the homepage's identified draggable campus assistant above the glass bar. */
internal object XjtuFloatingControl {
    private var lastCheckAt = 0L

    fun update(host: GlassHostLayout, scene: ViewGroup?, selectedIndex: Int) {
        val now = SystemClock.uptimeMillis()
        val interval = if (selectedIndex == 3) 1_000L else 350L
        if (now - lastCheckAt < interval || !host.isShown || host.width <= 0) return
        lastCheckAt = now
        val web = scene?.let(::findWebView) ?: return
        if (web.width <= 0 || !web.isShown) return
        val hostPos = IntArray(2).also(host::getLocationOnScreen)
        val webPos = IntArray(2).also(web::getLocationOnScreen)
        val rootPos = IntArray(2).also(host.rootView::getLocationOnScreen)
        val rootBottom = rootPos[1] + host.rootView.height
        val gap = (rootBottom - hostPos[1] - host.height).coerceAtLeast(0)
        val targetBottom = hostPos[1] - gap
        val script = """(function(){
            var bubble=null;
            if($selectedIndex===0){
                var image=document.querySelector('img[src*="1781152819705"],uni-image[src*="1781152819705"]');
                bubble=image && image.closest('uni-movable-view,v-uni-movable-view');
            } else if($selectedIndex===3){
                var nodes=Array.from(document.querySelectorAll('uni-fab,.uni-fab,[class*="fab"],[class*="add"],[class*="plus"],[aria-label*="添加"],[aria-label*="新增"],[title*="添加"]'));
                var options=[];
                nodes.forEach(function(node){
                    var el=node;
                    for(var i=0;i<4 && el;i++,el=el.parentElement){
                        var r=el.getBoundingClientRect(), ratio=r.width/Math.max(1,r.height);
                        if(r.width>=38 && r.height>=38 && r.width<=180 && r.height<=180 &&
                           ratio>.72 && ratio<1.38 && r.left>window.innerWidth*.55 &&
                           r.top>window.innerHeight*.45 && r.bottom<window.innerHeight+80){
                            options.push({el:el,score:(window.innerWidth-r.right)+(window.innerHeight-r.bottom)});
                            break;
                        }
                    }
                });
                // Some uni-app builds render the add action without a FAB class.
                // Its visible orange circular surface is stable across those builds.
                if(!options.length){
                    Array.from(document.querySelectorAll('button,[role="button"],a,div,view,uni-view')).forEach(function(el){
                        var r=el.getBoundingClientRect();
                        if(r.width<42 || r.height<42 || r.width>170 || r.height>170 ||
                           Math.abs(r.width-r.height)>Math.min(r.width,r.height)*.28 ||
                           r.left<window.innerWidth*.62 || r.top<window.innerHeight*.55 ||
                           r.bottom>window.innerHeight+90) return;
                        var css=getComputedStyle(el), radius=parseFloat(css.borderTopLeftRadius)||0;
                        var color=css.backgroundColor.match(/\d+/g);
                        if(!color || color.length<3 || radius<Math.min(r.width,r.height)*.32) return;
                        var red=+color[0], green=+color[1], blue=+color[2];
                        if(red<175 || green<45 || green>175 || blue>145) return;
                        options.push({el:el,score:(window.innerWidth-r.right)+(window.innerHeight-r.bottom)});
                    });
                }
                options.sort(function(a,b){return a.score-b.score;});
                bubble=options.length ? options[0].el : null;
            }
            if(!bubble || !window.innerWidth) return;
            var scale=${web.width}/window.innerWidth;
            var target=(${targetBottom}-${webPos[1]})/scale;
            // Remove the movable-area/scroll ancestor from viewport positioning.
            // Keep the original node so its tap handler continues to open the assistant.
            if(!bubble.dataset.liquidTabFixed){
                var rect=bubble.getBoundingClientRect();
                if(rect.width<=0 || rect.height<=0) return;
                bubble.dataset.liquidTabFixed=String($selectedIndex);
                bubble.style.width=rect.width+'px';
                bubble.style.height=rect.height+'px';
                bubble.style.right=Math.max(12,window.innerWidth-rect.right)+'px';
                document.body.appendChild(bubble);
            }
            if(!document.getElementById('liquid-tab-fixed-assistant')){
                var style=document.createElement('style');
                style.id='liquid-tab-fixed-assistant';
                style.textContent='[data-liquid-tab-fixed]{position:fixed!important;'
                    +'left:auto!important;top:auto!important;margin:0!important;'
                    +'transform:none!important;translate:none!important;'
                    +'transition:none!important;z-index:2147483000!important;}';
                document.head.appendChild(style);
            }
            var bottom=Math.max(0,window.innerHeight-target)+'px';
            if(bubble.style.bottom!==bottom) bubble.style.setProperty('bottom',bottom,'important');
        })();"""
        web.evaluateJavascript(script, null)
    }

    private fun findWebView(root: ViewGroup): WebView? {
        val stack = ArrayDeque<View>(); stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 256) {
            val view = stack.removeLast()
            if (view is WebView && view.isShown) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        return null
    }
}
