package io.github.offlineglass.hook.adapters.jd

import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Both native bars inherit the same controller and button API. */
internal object JdNavigationBinding {
    const val SLOT_COUNT = 5
    const val HOME = 0
    const val BROWSE = 1
    private const val MAIN_FRAME = "com.jingdong.app.mall.MainFrameLayout"
    private const val NATIVE_PACKAGE = "com.jingdong.common.unification.navigationbar"

    data class Slot(val id: Int, val button: ViewGroup, val touch: View, val icon: View,
                    val label: TextView, val badge: View, val title: String?)
    class Row(val view: ViewGroup, val slots: List<Slot>, private val selection: Method,
              private val hideShadow: Method?) {
        fun selected(): Int? = runCatching {
            val nativeId = selection.invoke(view) as Int
            slots.indexOfFirst { it.id == nativeId }.takeIf { it >= 0 }
        }.getOrNull()

        fun isCurrent(): Boolean = view.childCount == slots.size && slots.indices.all { index ->
            val slot = slots[index]
            view.getChildAt(index) === slot.button && slot.touch.id == slot.id &&
                (!view.isAttachedToWindow || slot.touch.isAttachedToWindow && slot.icon.isAttachedToWindow &&
                    slot.label.isAttachedToWindow && slot.badge.isAttachedToWindow)
        }

        fun hideSelectionShadow() { runCatching { hideShadow?.invoke(view) } }
    }

    private data class Api(val methods: JdNavigationContract.Api, val hideShadow: Method?,
                           val roles: Lazy<List<Set<Int>>>)
    private var api: Api? = null
    private val rows = WeakHashMap<ViewGroup, WeakReference<Row>>()
    private val retryAt = WeakHashMap<ViewGroup, Long>()
    private val reported = HashSet<Class<*>>()

    fun initialize(loader: ClassLoader): Class<*>? = runCatching {
        val group = Class.forName("$NATIVE_PACKAGE.newbar.NavigationGroup", false, loader)
        val button = Class.forName("$NATIVE_PACKAGE.newbar.NavigationButton", false, loader)
        val constants = Class.forName("$NATIVE_PACKAGE.NavigationConstants", false, loader)
        fun id(role: String) = constants.getField("NAVIGATION_$role").getInt(null)
        api = Api(JdNavigationContract.resolve(group, button, ViewGroup::class.java, View::class.java),
            runCatching { group.getMethod("hideSelectShadowForIsland") }.getOrNull(),
            // NavigationConstants also initializes resource-backed labels. Read
            // its values only after JD has created the native button row.
            lazy(LazyThreadSafetyMode.NONE) {
                listOf(setOf(id("HOME")), setOf(id("FAXIAN"), id("VIDEO")),
                    setOf(id("MESSAGE"), id("MESSAGENEW")), setOf(id("SHOPPINGCAR")), setOf(id("MYJD")))
            })
        group
    }.onFailure { XposedBridge.log("LiquidTab JD navigation API unavailable: $it") }.getOrNull()

    fun invalidate(view: ViewGroup) {
        rows.remove(view)
        retryAt.remove(view)
    }

    private fun inside(child: View, owner: ViewGroup): Boolean {
        var current = child.parent
        repeat(8) {
            if (current === owner) return true
            current = (current as? View)?.parent ?: return false
        }
        return false
    }

    fun resolve(view: ViewGroup?): Row? {
        view ?: return null
        val contract = api ?: return null
        val methods = contract.methods
        if (!methods.group().isInstance(view) || view.childCount != SLOT_COUNT) return null
        rows[view]?.get()?.takeIf(Row::isCurrent)?.let { return it }
        val now = android.os.SystemClock.uptimeMillis()
        if (now < (retryAt[view] ?: 0L)) return null
        return runCatching {
            val slots = (0 until SLOT_COUNT).map { index ->
                val button = view.getChildAt(index) as ViewGroup
                check(methods.button().isInstance(button)) { "unrelated child" }
                val id = methods.id().invoke(button) as Int
                val touch = methods.touch().invoke(button) as View
                val icon = methods.icon().invoke(button) as View
                val label = methods.label().invoke(button) as TextView
                val badge = methods.badge().invoke(button) as View
                check(touch.id == id && touch.isClickable && inside(touch, button) &&
                    inside(icon, button) && inside(label, button) && inside(badge, button)) { "unbound native button $id" }
                Slot(id, button, touch, icon, label, badge, methods.title().invoke(button) as? String)
            }
            val ids = slots.map(Slot::id)
            check(JdNavigationContract.rolesMatch(ids, contract.roles.value)) { "unrecognized native role order: $ids" }
            Row(view, slots, methods.selection(), contract.hideShadow).also { rows[view] = WeakReference(it) }
        }.onFailure {
            retryAt[view] = now + 250L
            if (reported.size < 8 && reported.add(view.javaClass))
                XposedBridge.log("LiquidTab JD navigation row rejected: $it")
        }.getOrNull()
    }

    fun findInShell(shell: View): Row? = rowsInShell(shell)?.singleOrNull()

    fun mainFrame(row: ViewGroup): ViewGroup? {
        var ancestor = row.parent as? ViewGroup ?: return null
        repeat(12) {
            if (ancestor.javaClass.name == MAIN_FRAME) return ancestor
            ancestor = ancestor.parent as? ViewGroup ?: return null
        }
        return null
    }

    private fun rowsInShell(shell: View): List<Row>? {
        val stack = ArrayDeque<View>()
        stack.add(shell)
        val found = ArrayList<Row>(2)
        var remaining = 100
        while (stack.isNotEmpty()) {
            if (remaining-- == 0) return null
            val view = stack.removeLast() as? ViewGroup ?: continue
            if (view.visibility != View.VISIBLE) continue
            val row = resolve(view)
            if (row != null) {
                found.add(row)
                if (found.size == 2) return found
            } else {
                for (index in 0 until view.childCount) {
                    if (stack.size >= 100) return null
                    stack.add(view.getChildAt(index))
                }
            }
        }
        return found
    }

    fun find(root: View): Row? {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack.add(root to 0)
        var remaining = 60
        var found: Row? = null
        while (stack.isNotEmpty()) {
            if (remaining-- == 0) return null
            val (view, depth) = stack.removeLast()
            if (view !is ViewGroup || view.visibility != View.VISIBLE) continue
            if (view.javaClass.name == MAIN_FRAME) {
                for (index in 0 until view.childCount) {
                    val shell = view.getChildAt(index) as? RelativeLayout ?: continue
                    val rows = rowsInShell(shell) ?: return null
                    for (row in rows) {
                        if (found != null) return null
                        found = row
                    }
                }
            } else if (depth < 4) {
                for (index in view.childCount - 1 downTo 0) {
                    if (stack.size >= 60) return null
                    stack.add(view.getChildAt(index) to depth + 1)
                }
            }
        }
        return found?.takeIf { it.view.isAttachedToWindow && it.view.isShown && it.view.width > 0 && it.view.height > 0 }
    }
}
