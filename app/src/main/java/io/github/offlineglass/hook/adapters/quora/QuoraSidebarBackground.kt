package io.github.offlineglass.hook.adapters.quora

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/** Extends the actual sidebar webpage background, preserving native borders above it. */
internal class QuoraSidebarBackground {
    private class Extension(val original: Drawable?) : Drawable() {
        var inset=0;var color: Int?=null
        val paint=Paint()
        override fun draw(canvas: Canvas) {
            original?.let { it.bounds=bounds;it.draw(canvas) }
            color?.let { paint.color=it;canvas.drawRect(bounds.left.toFloat(),
                (bounds.bottom-inset).toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat(),paint) }
        }
        override fun setAlpha(alpha: Int) { original?.alpha=alpha;paint.alpha=alpha }
        override fun setColorFilter(filter: ColorFilter?) { original?.colorFilter=filter }
        @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    private var panel: ViewGroup?=null
    private var extension: Extension?=null
    private var token=""
    private var attempts=0
    private var nextProbe=0L
    private var webBinding: WebView?=null
    private var bindingDirty=true
    private var observer: android.view.ViewTreeObserver?=null
    private val layoutListener=android.view.ViewTreeObserver.OnGlobalLayoutListener { bindingDirty=true }
    fun update(side: ViewGroup) {
        val p=QuoraViews.child(side,"sidebar_container") as? ViewGroup ?: return
        if(panel!==p) {
            restore();panel=p
        }
        if(observer!==p.viewTreeObserver) {
            observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
            observer=p.viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutListener) }
            bindingDirty=true
        }
        if(p.background!==extension) {
            val old=extension?.color
            extension=Extension(p.background).also { it.color=old;p.background=it }
        }
        extension?.inset=p.paddingBottom
        val now=SystemClock.uptimeMillis()
        if(now<nextProbe)return
        nextProbe=now+250
        val retained=webBinding?.takeIf { !bindingDirty&&it.isAttachedToWindow&&QuoraViews.descendant(it,p) }
        val web=retained ?: run {
            val queue=java.util.ArrayDeque<Pair<View,Int>>();queue.add(p to 0)
            val webs=ArrayList<WebView>();var budget=128
            while(queue.isNotEmpty()&&budget-->0) {
                val (v,depth)=queue.removeFirst()
                if(v is WebView)webs.add(v)
                else if(v is ViewGroup&&depth<12)for(i in 0 until v.childCount)queue.add(v.getChildAt(i) to depth+1)
            }
            bindingDirty=false
            webs.singleOrNull()?.takeIf { queue.isEmpty() }.also { webBinding=it }
        }
        if(web==null||web.progress!=100)return
        val key="${System.identityHashCode(web)}:${web.url}:${web.resources.configuration.uiMode}"
        if(key!=token){token=key;attempts=0}
        if(attempts++>=3)return
        web.evaluateJavascript("""
            (function(){var n=document.elementFromPoint(innerWidth*.5,innerHeight*.8)||document.body;
              for(var depth=0;n&&depth<24;n=n.parentElement,depth++){
                var c=getComputedStyle(n).backgroundColor,m=c.match(/^rgba?\(([^)]+)\)$/);
                if(!m)continue;var v=m[1].split(/[,\s]+/).filter(Boolean).map(Number);
                if(v.length<3||(v.length>3&&v[3]!==1))continue;
                return '#'+v.slice(0,3).map(function(x){return Math.round(x).toString(16).padStart(2,'0');}).join('');
              }return null;})();
        """.trimIndent()) { result ->
            if(panel!==p||token!=key)return@evaluateJavascript
            val hex=result?.trim('"') ?: return@evaluateJavascript
            if(!hex.matches(Regex("#[0-9a-fA-F]{6}")))return@evaluateJavascript
            extension?.let { e -> val color=Color.parseColor(hex)
                if(e.color!=color){e.color=color;e.invalidateSelf()} }
        }
    }
    fun restore() {
        val tree=observer?.takeIf { it.isAlive } ?: panel?.viewTreeObserver
        tree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        panel?.let { p -> extension?.let { if(p.background===it)p.background=it.original } }
        panel=null;extension=null;token="";attempts=0
        observer=null;webBinding=null;bindingDirty=true;nextProbe=0L
    }
}
