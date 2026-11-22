package io.github.offlineglass.hook.adapters.chatgpt

import android.graphics.Rect
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Only the native fade on the composer/search dock is replaced. */
internal object ChatGptDockMasks {
    enum class Kind { ELEVATED, SCAFFOLD }

    private val b get() = ChatGptBindings
    private val installed = mutableSetOf<Kind>()
    private val byMask = WeakHashMap<Any, WeakHashMap<Any, Unit>>()
    private val byOwner = WeakHashMap<Any, List<WeakReference<Any>>>()

    fun install(kind: Kind, draw: String) {
        XposedBridge.hookMethod(b.method(draw), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owners = byMask[param.thisObject] ?: return
                if (kindOf(param.thisObject) != kind) return
                if (owners.keys.none(ChatGptComposeBackground::isGlassActive)) return
                b.call("scope.content", param.args[0])
                param.result = null
            }
        })
        installed.add(kind)
    }

    fun kindOf(node: Any): Kind? {
        if (Kind.ELEVATED in installed && b.type("searchGradient.type").isInstance(node)) {
            return Kind.ELEVATED
        }
        if (Kind.SCAFFOLD in installed && b.method("scaffoldGradient.draw").declaringClass.isInstance(node) &&
            b.type("scaffoldGradient.builder").isInstance(b.read("scaffoldGradient.factory", node))) {
            return Kind.SCAFFOLD
        }
        return null
    }

    fun bind(anchor: View, owner: Any, control: Rect, candidates: List<Triple<Kind, Any, Any>>) {
        val layout = b.layout(owner) ?: return
        val tolerance = 44f * anchor.resources.displayMetrics.density
        val found = candidates.filter { (kind, node, host) ->
            b.placed(host) && b.contains(host, layout, onLimit = false) &&
                (kind == Kind.SCAFFOLD || listOfNotNull(
                    ChatGptComposeBackground.screenBounds(node, anchor),
                    ChatGptComposeBackground.coordinateBounds(b.outer(host), anchor),
                ).any { rect ->
                    rect.width() >= control.width() - 4 &&
                        rect.height() in 1..(anchor.height * .42f).toInt() &&
                        rect.left <= control.left + 4 && rect.right >= control.right - 4 &&
                        rect.top <= control.top + tolerance && rect.bottom >= control.bottom - tolerance
                })
        }.groupBy { it.first }
        val next = found.values.mapNotNull { it.singleOrNull()?.second }
        val previous = byOwner[owner].orEmpty().mapNotNull { it.get() }
        if (previous != next) {
            clear(owner)
            if (next.isNotEmpty()) byOwner[owner] = next.map { WeakReference(it) }
            next.forEach { byMask.getOrPut(it) { WeakHashMap() }[owner] = Unit }
            invalidate(owner)
        }
        ChatGptDiagnostics.report(anchor.context, "dockGradient",
            "elevated=${found[Kind.ELEVATED]?.size ?: 0}; scaffold=${found[Kind.SCAFFOLD]?.size ?: 0}; " +
                "discovered=${candidates.size}; bound=${next.size}")
    }

    fun clear(owner: Any) {
        byOwner.remove(owner)?.forEach { reference ->
            val mask = reference.get() ?: return@forEach
            val owners = byMask[mask] ?: return@forEach
            owners.remove(owner)
            if (owners.isEmpty()) byMask.remove(mask)
            runCatching { b.invalidate(mask) }
        }
    }

    fun invalidate(owner: Any) {
        byOwner[owner]?.forEach { reference ->
            reference.get()?.let { runCatching { b.invalidate(it) } }
        }
    }
}
