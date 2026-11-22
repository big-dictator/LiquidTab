package io.github.offlineglass.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup

/** One verified business API for native order, current page and cell identity. */
internal class DouyinTopBarBinding(loader: ClassLoader) {
    companion object {
        private val instances = java.util.WeakHashMap<ClassLoader, java.lang.ref.WeakReference<DouyinTopBarBinding>>()
        @Synchronized fun shared(loader: ClassLoader): DouyinTopBarBinding =
            instances[loader]?.get() ?: DouyinTopBarBinding(loader).also {
                instances[loader] = java.lang.ref.WeakReference(it)
            }
    }
    private val api = requireNotNull(DouyinTopBarContract.ability(
        Class.forName("com.ss.android.ugc.aweme.ability.EzHomePage", false, loader), View::class.java)) {
        "Native home-tab ability contract unavailable"
    }
    data class State(val owner: Any, val current: String, val count: Int, val currentPosition: Int)
    data class Cell(val id: String, val content: View)
    private data class CellContract(val value: DouyinTopBarContract.Cell?)
    private val cells = object : ClassValue<CellContract>() {
        override fun computeValue(type: Class<*>) = CellContract(
            DouyinTopBarContract.cell(type, ViewGroup::class.java, View::class.java))
    }
    fun state(): State? {
        val owner = api.provider().invoke(null) ?: return null
        val current = api.current().invoke(owner) as? String ?: return null
        val count = api.count().invoke(owner) as? Int ?: return null
        if (current.isBlank() || count !in 1..64) return null
        val position = api.position().invoke(owner, current) as? Int ?: return null
        return State(owner, current, count, position).takeIf { position in 0 until count }
    }
    fun position(state: State, id: String): Int? =
        (api.position().invoke(state.owner, id) as? Int)?.takeIf { it in 0 until state.count }

    fun cell(view: View): Cell? {
        if (view !is ViewGroup) return null
        val contract = cells.get(view.javaClass).value ?: return null
        val id = contract.id().invoke(view) as? String ?: return null
        val content = contract.content().invoke(view) as? View ?: return null
        if (id.isBlank()) return null
        var ancestor: View? = content
        repeat(16) {
            if (ancestor === view) return Cell(id, content)
            ancestor = ancestor?.parent as? View
        }
        return null
    }
}
