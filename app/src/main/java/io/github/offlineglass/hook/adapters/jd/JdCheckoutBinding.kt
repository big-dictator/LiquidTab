package io.github.offlineglass.hook.adapters.jd

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** A verified footer and its original three touch regions, in one Taro scene. */
internal class JdCheckoutBinding(
    val shell: ViewGroup, val row: ViewGroup, val eventRoot: ViewGroup,
    val select: View, val amount: View, val checkout: View,
    private val selectLabel: View, private val checkoutLabel: View,
) {
    fun isCurrent(): Boolean = shell.isAttachedToWindow && shell.isShown &&
        shell.width > 0 && shell.height > 0 && inside(shell, eventRoot) &&
        (row === shell || inside(row, shell)) && select.parent === row &&
        amount.parent === row && checkout.parent === row &&
        inside(selectLabel, select) && inside(checkoutLabel, checkout)

    class Finder {
        private val textReaders = HashMap<Class<*>, Method?>()
        private val pending = ArrayDeque<View>()
        private val labels = ArrayDeque<View>()
        private val position = IntArray(2)

        fun find(host: GlassHostLayout): JdCheckoutBinding? {
            val nav = host.navigationSource ?: return null
            val frame = JdNavigationBinding.mainFrame(nav) ?: return null
            val page = JdNativeContentBinding.content(nav) ?: JdClassicLayout.content(frame) ?: return null
            val density = page.resources.displayMetrics.density
            var result: JdCheckoutBinding? = null
            pending.add(page)
            try {
                var remaining = 400
                while (pending.isNotEmpty()) {
                    if (remaining-- == 0) return null
                    val group = pending.removeLast() as? ViewGroup ?: continue
                    if (group.visibility != View.VISIBLE || group.alpha <= .05f ||
                        group.width <= 0 || group.height <= 0 || isScrolling(group)) continue
                    if (group.width >= page.width * .8f && group.height in (32 * density).toInt()..(96 * density).toInt()) {
                        group.getLocationInWindow(position)
                        if (position[1] + group.height >= page.height * .78f) {
                            val candidate = bindRow(group, page, density)
                            if (candidate != null) {
                                if (result != null) return null
                                result = candidate
                                continue
                            }
                        }
                    }
                    if (group.childCount + pending.size > remaining) return null
                    for (index in 0 until group.childCount) pending.add(group.getChildAt(index))
                }
                return result
            } finally { pending.clear() }
        }

        private fun bindRow(row: ViewGroup, page: View, density: Float): JdCheckoutBinding? {
            if (row.childCount !in 3..8) return null
            val branches = (0 until row.childCount).map(row::getChildAt)
                .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
                .sortedBy { it.left }
            if (branches.size != 3) return null
            val (select, amount, checkout) = branches
            if (select.right > amount.left || amount.right > checkout.left) return null
            val selectLabel = label(select) { it == "全选" || it.equals("Select all", true) } ?: return null
            val checkoutLabel = label(checkout) {
                it.startsWith("去结算") || it.startsWith("结算") || it.startsWith("领券结算") ||
                    it.startsWith("Checkout", true)
            } ?: return null
            label(amount, unique = false) {
                it.contains('¥') || it.contains('￥') || it.contains("合计") || it.contains("明细") ||
                    it.startsWith("计算中") || it.any(Char::isDigit)
            } ?: return null
            val eventRoot = eventRoot(row, page) ?: return null
            var shell = row
            // The floating layout has a separate bottom-inset shell. Only
            // absorb wrappers that contain this row as their sole laid-out child.
            repeat(4) {
                val parent = shell.parent as? ViewGroup ?: return@repeat
                if (parent !== eventRoot && parent.width >= page.width * .8f &&
                    parent.height <= 160 * density && parent.childCount <= 16 &&
                    (0 until parent.childCount).none {
                        val child = parent.getChildAt(it)
                        child !== shell && child.visibility == View.VISIBLE && child.width > 0 && child.height > 0
                    }) shell = parent
            }
            return JdCheckoutBinding(shell, row, eventRoot, select, amount, checkout, selectLabel, checkoutLabel)
        }

        private fun label(root: View, unique: Boolean = true, matches: (String) -> Boolean): View? {
            labels.add(root)
            var result: View? = null
            try {
                var remaining = 48
                while (labels.isNotEmpty()) {
                    if (remaining-- == 0) return null
                    val view = labels.removeLast()
                    if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) continue
                    val text = text(view)
                    if (text != null && matches(text.trim())) {
                        if (!unique) return view
                        if (result != null) return null
                        result = view
                    }
                    if (view is ViewGroup) {
                        if (view.childCount + labels.size > remaining) return null
                        for (index in 0 until view.childCount) labels.add(view.getChildAt(index))
                    }
                }
                return result
            } finally { labels.clear() }
        }

        private fun text(view: View): String? {
            if (view is TextView) return view.text?.toString()
            val type = view.javaClass
            if (!type.name.startsWith("com.taro.")) return null
            if (!textReaders.containsKey(type) && textReaders.size < 32) {
                textReaders[type] = runCatching { type.getMethod("getText") }.getOrNull()?.takeIf {
                    !Modifier.isStatic(it.modifiers) && CharSequence::class.java.isAssignableFrom(it.returnType)
                }
            }
            return runCatching { (textReaders[type]?.invoke(view) as? CharSequence)?.toString() }.getOrNull()
        }

        private fun eventRoot(row: View, page: View): ViewGroup? {
            var current = row.parent as? ViewGroup
            repeat(12) {
                val view = current ?: return null
                if (view === page) return null
                if (generateSequence(view.javaClass as Class<*>) { it.superclass }.any {
                    it.name == "com.taro.core.react.ReactRootView"
                }) return view.takeIf { inside(it, page) }
                current = view.parent as? ViewGroup
            }
            return null
        }

        private fun isScrolling(view: ViewGroup): Boolean =
            view is android.widget.ScrollView || view is android.widget.HorizontalScrollView ||
                view.javaClass.simpleName.contains("Scroll") || view.javaClass.simpleName.contains("Recycler") ||
                view.javaClass.simpleName.contains("Refresh")
    }

    private companion object {
        fun inside(child: View, owner: View): Boolean {
            var view = child
            repeat(14) {
                if (view === owner) return true
                view = view.parent as? View ?: return false
            }
            return false
        }
    }
}
