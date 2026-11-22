package io.github.offlineglass.hook.adapters.meituan_main

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import java.lang.ref.WeakReference

/** Shared MSC content ownership for checkout, scrolling, layout and sampling.
 * Discovery is bounded and layout-driven; no per-frame whole-tree probes. */
internal class MeituanMainBinding {
    private var owner: WeakReference<View>? = null
    private var observer: ViewTreeObserver? = null
    private var dirty = true
    private var nextProbe = 0L
    private var page: ViewGroup? = null
    var revision = 0
        private set
    private var scrollRevision = -1
    private var cachedScroll: ScrollView? = null
    private var lastIssue = ""
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { dirty = true }
    private val types = java.util.WeakHashMap<Class<*>, Boolean>()

    fun issue(message: String) {
        if (lastIssue != message) Log.w("MeituanBinding", message)
        lastIssue = message
    }

    fun isMsc(view: View): Boolean = types.getOrPut(view.javaClass) {
        generateSequence(view.javaClass as Class<*>?) { it.superclass }
            .any { it.name == "com.meituan.msc.views.ReactRootView" } && view is ViewGroup
    }

    fun tree(root: View, budget: Int = 1600): List<View> {
        val result = ArrayList<View>(); val stack = ArrayDeque<View>(); stack += root
        while (stack.isNotEmpty() && result.size < budget) {
            val view = stack.removeLast()
            if (view is AdapterOwnedOverlay || view is io.github.offlineglass.hook.GlassHostLayout ||
                view.visibility != View.VISIBLE) continue
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        if (stack.isNotEmpty()) { issue("MSC discovery budget exhausted"); return emptyList() }
        return result
    }

    fun page(root: View): ViewGroup? {
        if (owner?.get() !== root || observer?.isAlive != true) {
            release(); owner = WeakReference(root)
            observer = root.viewTreeObserver.also { it.addOnGlobalLayoutListener(layout) }
        }
        if (page?.let { !it.isAttachedToWindow || !it.isShown } == true) { page = null; dirty = true }
        if (!dirty || SystemClock.uptimeMillis() < nextProbe) return page
        dirty = false; nextProbe = SystemClock.uptimeMillis() + 250L
        val candidates = tree(root).filterIsInstance<ViewGroup>().filter {
            var ancestor: View? = it
            var visible = true
            while (ancestor != null && ancestor !== root) {
                if (ancestor.visibility != View.VISIBLE || ancestor.alpha <= .001f) visible = false
                ancestor = ancestor.parent as? View
            }
            it.isShown && visible && isMsc(it)
        }
        revision++
        page = candidates.singleOrNull()
        if (page == null) issue("MSC content root must be unique: candidates=${candidates.size}")
        return page
    }

    fun text(view: View): CharSequence? = if (view is TextView) view.text else {
        if (!view.javaClass.name.startsWith("com.meituan.msc.")) null else runCatching {
            val getter = view.javaClass.getMethod("getText")
            if (CharSequence::class.java.isAssignableFrom(getter.returnType)) getter.invoke(view) as? CharSequence else null
        }.getOrNull()
    }

    fun scroll(root: View): ScrollView? {
        val content = page(root) ?: return null
        if (scrollRevision == revision && cachedScroll?.let { !it.isAttachedToWindow } != true) return cachedScroll
        scrollRevision = revision
        cachedScroll = tree(content).filterIsInstance<ScrollView>().filter {
            it.isShown && it.width >= content.width * .9f && it.height >= content.height * .5f
        }.singleOrNull().also { if (it == null) issue("MSC main vertical viewport missing or ambiguous") }
        return cachedScroll
    }

    fun backTop(content: ViewGroup, footer: View): View? {
        val candidates = tree(content).filter { view ->
            if (view === footer) return@filter false
            val semantic = view.contentDescription?.toString().orEmpty() in setOf("回到顶部", "返回顶部", "置顶") ||
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("") in
                setOf("back_top", "back_to_top", "scroll_to_top")
            semantic && (view.isClickable || (view.parent as? View)?.hasOnClickListeners() == true)
        }
        val actions = candidates.map { if (it.hasOnClickListeners()) it else
            ((it.parent as? View)?.takeIf { parent -> parent.hasOnClickListeners() } ?: it) }.distinct()
        return MeituanMainTabContract.unique(actions).also {
            if (it == null) issue("back-to-top requires a unique native semantic action; anonymous image untouched")
        }
    }

    fun release() {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout)
        observer = null; owner = null; page = null; dirty = true; nextProbe = 0L; cachedScroll = null; scrollRevision = -1
    }
}
