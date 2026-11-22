package io.github.offlineglass.hook.adapters.x

import android.app.Activity
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Independent page roles. No dependency on navigation, Haze, shader or DEX availability. */
internal object XPageSemantics {
    private var installed=false
    private val sessions=WeakHashMap<Activity,Session>()
    fun install() {
        if(installed) return
        installed=true
        XposedBridge.hookAllMethods(Activity::class.java,"onPostResume",object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { (p.thisObject as? Activity)?.let(::onHostReady) }
        })
        XposedBridge.hookAllMethods(Activity::class.java,"onStop",object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { sessions[p.thisObject]?.stop() }
        })
        XposedBridge.hookAllMethods(Activity::class.java,"onDestroy",object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { sessions.remove(p.thisObject)?.close() }
        })
    }
    fun onHostReady(activity: Activity) {
        if(activity.packageName!=XAdapter.PACKAGE_NAME || activity.isDestroyed) return
        sessions.getOrPut(activity) { Session(activity) }.resume()
        XReplyComposerGlass.onHostReady(activity)
    }
    fun request(anchor: View) { sessions.values.filter { it.root.get()===anchor.rootView }.forEach { it.request() } }
    private fun syncGallery() {
        XAdapter.onMediaGalleryChanged(sessions.values.any { it.resumed && it.gallery })
        XAdapter.onSemanticReplyChanged(sessions.values.any { it.resumed && it.reply })
        XAdapter.onComposerPageChanged(sessions.values.any { it.resumed && it.composer })
    }

    private class Session(activity: Activity) {
        val root=WeakReference(activity.window.decorView)
        private val activityRef=WeakReference(activity)
        var resumed=false;private set
        var gallery=false;private set
        var reply=false;private set
        var composer=false;private set
        private var queued=false
        private var lastScan=0L
        private var lastReport=""
        private data class TargetHandle(val editorId: Int,val parentId: Int,var bounds: RectF)
        private data class Snapshot(val target: RectF?,val gallery: Boolean,val handle: TargetHandle?,val composer: Boolean=false)
        private val targets=WeakHashMap<View,TargetHandle>()
        private val hosts=java.util.Collections.newSetFromMap(WeakHashMap<View,Boolean>())
        private val scanTask=Runnable { queued=false;if(resumed) scan() }
        private val settleTask=Runnable { request() }
        private var observer: ViewTreeObserver?=null
        private val layout=ViewTreeObserver.OnGlobalLayoutListener { request() }
        private val preDraw=ViewTreeObserver.OnPreDrawListener {
            // Refresh only the two already verified semantic IDs. RenderNode
            // translations/keyboard animations need not dispatch global layout.
            if(resumed) targets.entries.toList().forEach { (host,handle) ->
                val provider=host.accessibilityNodeProvider
                val editor=provider?.createAccessibilityNodeInfo(handle.editorId)
                val parent=provider?.createAccessibilityNodeInfo(handle.parentId)
                val current=try {
                    if(editor!=null && parent!=null && host.isShown) replyBounds(host,editor,parent) else null
                } finally {
                    @Suppress("DEPRECATION")
                    editor?.recycle()
                    @Suppress("DEPRECATION")
                    parent?.recycle()
                }
                if(current==null) {
                    targets.remove(host);XReplyComposerGlass.onSemantics(host,null);request()
                } else if(current!=handle.bounds) {
                    handle.bounds=current
                    XReplyComposerGlass.onSemantics(host,current)
                }
            }
            true
        }
        fun resume() {
            resumed=true
            val view=root.get() ?: return
            val tree=view.viewTreeObserver
            if(observer!==tree) { detach();observer=tree;tree.addOnGlobalLayoutListener(layout);tree.addOnPreDrawListener(preDraw) }
            request()
            // Bounded startup retries only; later discovery is layout/resume driven.
            view.removeCallbacks(settleTask)
            view.postDelayed(settleTask,750)
        }
        fun request() {
            val view=root.get() ?: return
            if(!resumed || queued) return
            queued=true
            view.postDelayed(scanTask,(180L-(SystemClock.uptimeMillis()-lastScan)).coerceAtLeast(0L))
        }
        fun stop() {
            resumed=false;gallery=false;reply=false;composer=false
            root.get()?.let { it.removeCallbacks(scanTask);it.removeCallbacks(settleTask) }
            queued=false
            hosts.toList().forEach { XReplyComposerGlass.onSemantics(it,null) }
            syncGallery()
        }
        private fun detach() { observer?.takeIf { it.isAlive }?.let { it.removeOnGlobalLayoutListener(layout);it.removeOnPreDrawListener(preDraw) };observer=null }
        fun close() { stop();detach();hosts.clear();targets.clear() }
        private fun scan() {
            lastScan=SystemClock.uptimeMillis()
            val view=root.get() ?: return
            val seen=mutableSetOf<View>()
            var anyGallery=false
            var anyReply=false
            var anyComposer=false
            fun walk(v: View,depth: Int) {
                if(depth>24 || !v.isShown) return
                if(v.javaClass.name.startsWith("androidx.compose.ui.platform.") && v.accessibilityNodeProvider!=null) {
                    seen+=v
                    val result=read(v)
                    anyGallery=anyGallery || result.gallery
                    anyReply=anyReply || result.target!=null
                    anyComposer=anyComposer || result.composer
                    if(result.handle!=null) targets[v]=result.handle else targets.remove(v)
                    XReplyComposerGlass.onSemantics(v,result.target)
                    return
                }
                if(v is ViewGroup) for(i in 0 until v.childCount) walk(v.getChildAt(i),depth+1)
            }
            runCatching { walk(view,0) }.onFailure {
                activityRef.get()?.let { a -> java.io.File(a.getExternalFilesDir(null),"liquidtab-x-pages.txt").writeText("semantic contract failure: ${it.javaClass.simpleName}: ${it.message}") }
                return
            }
            (hosts.toSet()-seen).forEach { targets.remove(it);XReplyComposerGlass.onSemantics(it,null) }
            hosts.clear();hosts.addAll(seen)
            gallery=anyGallery;reply=anyReply;composer=anyComposer;syncGallery()
            val report="gallery=$gallery; reply=$reply; composer=$composer; hosts=${hosts.size}; resumed=$resumed"
            if(report!=lastReport) {
                lastReport=report
                activityRef.get()?.let { a -> runCatching {
                    java.io.File(a.getExternalFilesDir(null),"liquidtab-x-pages.txt").writeText(report)
                } }
            }
        }
        private fun replyBounds(host: View,editor: AccessibilityNodeInfo,parent: AccessibilityNodeInfo): RectF? {
            if(!editor.isEditable || !editor.isVisibleToUser || editor.viewIdResourceName?.substringAfterLast('/')!="post-detail-reply-text-field") return null
            val e=Rect().also(editor::getBoundsInScreen)
            val r=Rect().also(parent::getBoundsInScreen)
            if(e.isEmpty || parent.childCount<2 || !r.contains(e) || r.width()<=host.width*.7f || r.height()>=host.height*.5f) return null
            val screen=IntArray(2).also(host::getLocationOnScreen)
            val window=IntArray(2).also(host::getLocationInWindow)
            return RectF(r).apply { offset((window[0]-screen[0]).toFloat(),(window[1]-screen[1]).toFloat()) }
        }
        private fun read(host: View): Snapshot {
            val provider=host.accessibilityNodeProvider ?: return Snapshot(null,false,null)
            data class Entry(val id: Int,val node: AccessibilityNodeInfo,val parent: Entry?)
            val queue=ArrayDeque<Entry>();val all=mutableListOf<Entry>();val ids=mutableSetOf<Int>()
            val found=mutableListOf<TargetHandle>()
            var media=false
            var fullComposer=false
            try {
                provider.createAccessibilityNodeInfo(View.NO_ID)?.let { queue.add(Entry(View.NO_ID,it,null)) }
                while(queue.isNotEmpty() && all.size<1200) {
                    val e=queue.removeFirst();all+=e
                    val n=e.node
                    if(n.isEditable && n.isVisibleToUser) {
                        // Stable native page tag plus an actual editable descendant; independent
                        // of IME visibility, text/locale, obfuscated owners and navigation binding.
                        var ancestor=e.parent
                        while(ancestor!=null) {
                            val rootNode=ancestor.node
                            if(rootNode.isVisibleToUser && rootNode.childCount>0 &&
                                rootNode.viewIdResourceName?.substringAfterLast('/')=="ComposerRoot") {
                                val bounds=Rect().also(rootNode::getBoundsInScreen)
                                val editorBounds=Rect().also(n::getBoundsInScreen)
                                if(bounds.width()>=host.width*.85f && bounds.height()>=host.height*.7f &&
                                    !editorBounds.isEmpty && bounds.contains(editorBounds)) fullComposer=true
                            }
                            ancestor=ancestor.parent
                        }
                    }
                    if(n.isVisibleToUser && n.viewIdResourceName?.substringAfterLast('/')=="MediaGallery" && n.childCount>0) {
                        val r=Rect().also(n::getBoundsInScreen)
                        if(r.width()>=host.width*.85f && r.height()>=host.height*.7f) media=true
                    }
                    if(n.isEditable && n.isVisibleToUser && n.viewIdResourceName?.substringAfterLast('/')=="post-detail-reply-text-field") {
                        var parent=e.parent
                        while(parent!=null) {
                            val bounds=replyBounds(host,n,parent.node)
                            if(bounds!=null) {
                                found+=TargetHandle(e.id,parent.id,bounds)
                                break
                            }
                            parent=parent.parent
                        }
                    }
                    for(i in 0 until n.childCount) {
                        val packed=runCatching { XposedHelpers.callMethod(n,"getChildId",i) as Long }.getOrNull() ?: continue
                        val id=(packed ushr 32).toInt()
                        if(ids.add(id)) provider.createAccessibilityNodeInfo(id)?.let { queue.add(Entry(id,it,e)) }
                    }
                }
                val handle=found.singleOrNull()
                return Snapshot(handle?.bounds,media,handle,fullComposer)
            } finally {
                @Suppress("DEPRECATION")
                (all+queue).forEach { it.node.recycle() }
            }
        }
    }
}
