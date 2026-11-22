package io.github.offlineglass.hook.adapters.quora

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/** Adds scrollable tail space, without shrinking the WebView or its backdrop. */
internal class QuoraWebSafety {
    private var lastProbe = 0L
    private var active: WebView? = null
    private var signature: String? = null
    private val visibleRect=Rect()
    private val webRect=Rect()
    private val hostRect=Rect()

    fun visibleBoundWeb(): WebView? = active?.takeIf { it.isAttachedToWindow && it.isShown }

    fun update(host: View, source: ViewGroup?, owner: ViewGroup?, topBottom: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastProbe < 200L) return
        lastProbe = now
        val selected = QuoraViews.selected(source)
        if (source?.childCount != 5 || selected !in 0..4 || owner == null || !host.isShown) {
            restore(); return
        }
        val content = QuoraViews.child(owner,"content_wrapper") as? ViewGroup ?: run { restore(); return }
        // The verified controller is authoritative when its current WebView is
        // visibly inside this exact content branch. No periodic whole-page walk.
        val current=currentWebView(host)
        val web=current?.takeIf { visibleWeb(it,content) } ?: discoverWeb(content)
            ?: run { restore(); return }
        if (!web.getGlobalVisibleRect(webRect) || !host.getGlobalVisibleRect(hostRect)) { restore(); return }
        val safety = if(selected==0) 0 else (webRect.bottom - hostRect.top + 12f * host.resources.displayMetrics.density).toInt()
            .coerceAtLeast(0)
        val topSafety=(topBottom-webRect.top).coerceAtLeast(0)
        val next = "${web.url}|${web.height}|$safety|$topSafety|$selected"
        if (active !== web) { restore(); active = web }
        if (signature == next && web.progress == 100) return
        // Loading pages receive retries until the DOM exists; steady pages do no JS polling.
        signature = if (web.progress == 100) next else null
        val script = INSTALL.replace("__PIXELS__", safety.toString()).replace("__HEIGHT__", web.height.toString())
            .replace("__TOP__",topSafety.toString())
            .replace("__STRIP__",(selected==2).toString())
        web.evaluateJavascript(script, null)
    }

    private fun visibleWeb(web: WebView, content: ViewGroup): Boolean {
        if(web.width<=0||web.height<=0||!web.isShown||
            (web.parent as? View)?.let(QuoraAdapter::name)!="swiperefresh"||
            !QuoraViews.descendant(web,content))return false
        var node: View?=web
        repeat(24) {
            val current=node ?: return@repeat
            if(current.alpha<.01f||current.visibility!=View.VISIBLE)return false
            node=current.parent as? View
        }
        return web.getGlobalVisibleRect(visibleRect)&&visibleRect.width()>=web.width*.9f
    }

    private fun discoverWeb(content: ViewGroup): WebView? {
        val candidates = ArrayList<WebView>()
        val queue = java.util.ArrayDeque<Pair<View, Int>>()
        queue.add(content to 0)
        var budget = 1024
        while (queue.isNotEmpty() && budget-- > 0) {
            val (view, depth) = queue.removeFirst()
            if (!view.isShown || view.width <= 0 || view.height <= 0) continue
            if (view is WebView) {
                // Verified QWebView is a child of the page's native refresh container.
                if ((view.parent as? View)?.let(QuoraAdapter::name) == "swiperefresh") candidates.add(view)
            } else if (view is ViewGroup && depth < 18) {
                for (i in 0 until view.childCount) queue.add(view.getChildAt(i) to depth + 1)
            }
        }
        return candidates.filter { visibleWeb(it,content) }.singleOrNull()?.takeIf { queue.isEmpty() }
    }

    fun restore() {
        active?.let { web ->
            if (web.isAttachedToWindow) runCatching { web.evaluateJavascript(REMOVE, null) }
        }
        active = null
        signature = null
    }

    private var rootGetter: java.lang.reflect.Method? = null
    private var webGetter: java.lang.reflect.Method? = null
    private fun currentWebView(host: View): WebView? = runCatching {
        // Verified native current-tab -> root controller -> current WebView chain.
        val activity=host.context as? android.app.Activity ?: return null
        val getRoot=rootGetter?.takeIf { it.declaringClass.isInstance(activity) }
            ?: activity.javaClass.getDeclaredMethod("getCurrentRootWebViewController").apply {
                require(parameterCount==0&&!returnType.isPrimitive)
                isAccessible=true;rootGetter=this
            }
        val controller=getRoot.invoke(activity) ?: return null
        val getWeb=webGetter?.takeIf { it.declaringClass.isInstance(controller) }
            ?: controller.javaClass.getMethod("getMQWebView").apply {
            require(parameterCount==0&&WebView::class.java.isAssignableFrom(returnType));webGetter=this
        }
        getWeb.invoke(controller) as? WebView
    }.getOrNull()

    companion object {
        private const val REMOVE = "(function(){var s=window.__liquidTabQuoraSafety;if(s){s.stop();delete window.__liquidTabQuoraSafety;}})();"
        private val INSTALL = """
            (function(){
              if(!document.body)return;
              var key='__liquidTabQuoraSafety', old=window[key];
              if(old){old.pixels=__PIXELS__;old.topPixels=__TOP__;old.stripEnabled=__STRIP__;old.nativeHeight=__HEIGHT__;old.refresh();return;}
              var state={pixels:__PIXELS__,topPixels:__TOP__,stripEnabled:__STRIP__,nativeHeight:__HEIGHT__},spacer=document.createElement('div');
              var head=document.createElement('div');
              spacer.setAttribute('aria-hidden','true');
              spacer.style.cssText='display:block!important;flex:0 0 auto!important;width:1px!important;min-height:0!important;pointer-events:none!important;clear:both!important;';
              head.style.cssText=spacer.style.cssText;head.setAttribute('aria-hidden','true');
              var pending=0,stopped=false;
              var strip=null,stripOriginal=null,shift=0,scrollFrame=0,stripProbeAfter=0;
              function restoreStrip(){
                if(strip&&stripOriginal){strip.style.opacity=stripOriginal.opacity;strip.style.transform=stripOriginal.transform;strip.style.pointerEvents=stripOriginal.pointerEvents;}
                strip=null;stripOriginal=null;shift=0;
              }
              function findStrip(){
                if(!state.stripEnabled){restoreStrip();stripProbeAfter=0;return;}
                if(strip&&strip.isConnected)return;
                if(strip&&!strip.isConnected)stripProbeAfter=0;
                restoreStrip();
                var now=performance.now();if(now<stripProbeAfter)return;
                stripProbeAfter=now+2000;
                var all=document.querySelectorAll('*');
                if(all.length>3000)return;
                var matches=[];
                for(var i=0;i<all.length;i++){
                  var n=all[i],role=n.getAttribute('role'),css=getComputedStyle(n);
                  if(role!=='tablist'&&css.position!=='sticky'&&css.position!=='fixed')continue;
                  if(css.transform!=='none')continue;
                  var r=n.getBoundingClientRect();
                  if(r.width<innerWidth*0.65||r.height<=0||r.height>innerHeight*.18)continue;
                  var tabs=Array.from(n.querySelectorAll('[role="tab"],a[href],button,[role="button"]')).filter(function(t){return t.getBoundingClientRect().width>0;});
                  if(tabs.length<2||tabs.length>8)continue;
                  if(role==='tablist'&&!tabs.some(function(t){return t.getAttribute('aria-selected')==='true';}))continue;
                  var rects=tabs.map(function(t){return t.getBoundingClientRect();});
                  var y=rects[0].top+rects[0].height/2;
                  if(!rects.every(function(t,j){return Math.abs(t.top+t.height/2-y)<Math.max(4,r.height*.2)&&(j===0||t.left>=rects[j-1].right-2);}))continue;
                  matches.push(n);
                }
                // A semantic tablist inside a sticky parent is one capability;
                // retain its outer wrapper so the entire opaque row fades.
                matches=matches.filter(function(n){return !matches.some(function(p){return p!==n&&p.contains(n);});});
                if(matches.length!==1)return;
                strip=matches[0];stripOriginal={opacity:strip.style.opacity,transform:strip.style.transform,pointerEvents:strip.style.pointerEvents};
              }
              function updateStrip(){
                scrollFrame=0;if(stopped||!strip||!strip.isConnected)return;
                var rect=strip.getBoundingClientRect(),top=rect.top-shift;
                var boundary=state.topPixels*innerHeight/Math.max(1,state.nativeHeight);
                var p=Math.max(0,Math.min(1,(boundary-top)/Math.max(1,rect.height)));
                p=p*p*(3-2*p);shift=-rect.height*.4*p;
                strip.style.opacity=String((stripOriginal.opacity===''?1:Number(stripOriginal.opacity))*(1-p));
                strip.style.transform='translateY('+shift+'px)';
                strip.style.pointerEvents=p>=.99?'none':stripOriginal.pointerEvents;
              }
              function onScroll(){if(!stopped&&state.stripEnabled&&strip&&strip.isConnected&&!scrollFrame)scrollFrame=requestAnimationFrame(updateStrip);}
              function refresh(){
                if(stopped||!document.body)return;
                var root=document.scrollingElement||document.documentElement;
                var probe=document.elementFromPoint(innerWidth/2,Math.min(innerHeight-1,innerHeight*0.65));
                var target=null;
                for(var n=probe;n&&n!==document.body&&n!==root;n=n.parentElement){
                  var css=getComputedStyle(n);
                  if(/^(auto|scroll)$/.test(css.overflowY)&&n.clientHeight>innerHeight*0.4){target=n;break;}
                }
                target=target||document.body;
                var height=Math.ceil(state.pixels*innerHeight/Math.max(1,state.nativeHeight))+'px';
                if(spacer.style.height!==height)spacer.style.setProperty('height',height,'important');
                if(spacer.parentNode!==target)target.appendChild(spacer);
                var topHeight=Math.ceil(state.topPixels*innerHeight/Math.max(1,state.nativeHeight))+'px';
                if(head.style.height!==topHeight)head.style.setProperty('height',topHeight,'important');
                if(head.parentNode!==target)target.insertBefore(head,target.firstChild);
                findStrip();updateStrip();
              }
              var observer=new MutationObserver(function(records){
                if(stopped)return;
                var changed=false,needsStrip=state.stripEnabled&&(!strip||!strip.isConnected);
                for(var i=0;i<records.length;i++){
                  var record=records[i];
                  for(var j=0;j<record.addedNodes.length;j++){
                    var n=record.addedNodes[j];if(n===spacer||n===head)continue;changed=true;
                    if(needsStrip&&n.nodeType===1&&(n.getAttribute('role')==='tablist'||n.querySelector('[role="tablist"]')))stripProbeAfter=0;
                  }
                  for(var j=0;j<record.removedNodes.length;j++){var n=record.removedNodes[j];if(n!==spacer&&n!==head)changed=true;}
                }
                if(!changed||pending)return;
                pending=setTimeout(function(){pending=0;refresh();},120);
              });
              state.refresh=refresh;
              state.stop=function(){stopped=true;observer.disconnect();clearTimeout(pending);cancelAnimationFrame(scrollFrame);removeEventListener('scroll',onScroll,true);removeEventListener('resize',refresh);restoreStrip();spacer.remove();head.remove();};
              window[key]=state;refresh();observer.observe(document.body,{childList:true,subtree:true});addEventListener('resize',refresh);addEventListener('scroll',onScroll,{capture:true,passive:true});
            })();
        """.trimIndent()
    }
}
