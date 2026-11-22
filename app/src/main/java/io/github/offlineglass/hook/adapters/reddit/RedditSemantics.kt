package io.github.offlineglass.hook.adapters.reddit

import android.graphics.Rect
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** APK evidence: NavControl exposes TestTag, Selected and native OnClick semantics. */
internal class RedditSemantics {
    private val methods = HashMap<Pair<Class<*>, String>, Method?>()
    private val translations = HashMap<Class<*>, Method>()
    private val geometry = java.util.WeakHashMap<Any, Rect>()
    private var geometryOwner: java.lang.ref.WeakReference<View>? = null
    private var geometrySize = 0L
    private fun method(type: Class<*>, role: String, predicate: (Method) -> Boolean): Method? =
        methods.getOrPut(type to role) {
            type.methods.filter { !Modifier.isStatic(it.modifiers) && predicate(it) }
                .singleOrNull()?.apply { isAccessible = true }
        }

    data class Entry(val raw: Any, val layout: Any, val id: Int, val properties: Map<String, Any?>,
                     val parent: Entry?) {
        val tag get() = properties["TestTag"] as? String
        val selected get() = properties["Selected"] as? Boolean
    }
    data class Tab(val entry: Entry, val bounds: Rect, val action: Any, val revision: Int)
    data class Binding(val owner: View, val bar: Entry, val bounds: Rect, val tabs: List<Tab>,
                       val draw: Method, val canvasType: Class<*>, val canvasField: java.lang.reflect.Field,
                       val invalidateLayers: Method, val root: Any, val translate: Method, val maskLayout: Any)
    var failure = "not resolved"; private set

    private fun configField(type: Class<*>) = RedditReflection.config(type)
    private fun properties(raw: Any): Map<String, Any?> {
        val config = configField(raw.javaClass)?.get(raw) as? Iterable<*> ?: error("semantics config ambiguous")
        val result = HashMap<String, Any?>()
        for (item in config) {
            val entry = item as? Map.Entry<*, *> ?: continue
            val key = entry.key ?: continue
            val name = key.javaClass.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) && it.type == String::class.java
            }.singleOrNull()?.apply { isAccessible = true }?.get(key) as? String ?: continue
            if (name in setOf("TestTag", "Selected", "OnClick", "ContentDescription", "Text")) result[name] = entry.value
        }
        return result
    }
    fun callback(action: Any): Any? {
        return RedditReflection.callback(action)
    }
    fun click(tab: Tab): Boolean = runCatching {
        val function = callback(tab.action) ?: return false
        method(function.javaClass, "invoke") { it.name == "invoke" && it.parameterCount == 0 }
            ?.invoke(function) != false
    }.getOrDefault(false)

    fun read(owner: View): Binding? = runCatching {
        val size=(owner.width.toLong() shl 32) or (owner.height.toLong() and 0xffffffffL)
        if(geometryOwner?.get() !== owner || geometrySize != size) {
            geometry.clear();geometryOwner=java.lang.ref.WeakReference(owner);geometrySize=size
        }
        val root = owner.javaClass.getMethod("getRoot").invoke(owner) ?: error("no layout root")
        val semantics = owner.javaClass.getMethod("getSemanticsOwner").invoke(owner) ?: error("no semantics owner")
        // The semantics root is the sole zero-argument result with an Iterable config field.
        val first = method(semantics.javaClass, "root") {
            it.parameterCount == 0 && configField(it.returnType) != null
        }?.invoke(semantics) ?: error("semantics root ambiguous")
        val entries = ArrayList<Entry>()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun visit(raw: Any, parent: Entry?, depth: Int) {
            check(depth <= 40 && entries.size < 1200) { "semantics traversal budget exceeded" }
            check(visited.add(raw)) { "cyclic semantics" }
            val fields = raw.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) }
            val layout = fields.singleOrNull { it.type == root.javaClass }?.get(raw) ?: error("layout link ambiguous")
            val id = fields.singleOrNull { it.type == Int::class.javaPrimitiveType }?.getInt(raw) ?: error("semantic ID ambiguous")
            val entry = Entry(raw, layout, id, properties(raw), parent)
            entries += entry
            val children = RedditReflection.children(raw.javaClass)?.invoke(raw, false, false) as? List<*>
                ?: error("children contract ambiguous")
            children.forEach { if (it != null) visit(it, entry, depth + 1) }
        }
        visit(first, null, 0)
        val bar = entries.filter { it.tag == "bottom_nav" }.singleOrNull() ?: error("bottom_nav missing or ambiguous")
        val buttons = entries.filter { entry -> entry.tag == "bottom_nav_button" &&
            generateSequence(entry.parent) { it.parent }.any { it === bar } }
        check(buttons.size in 3..6) { "unexpected native tab count ${buttons.size}" }
        val provider = owner.accessibilityNodeProvider ?: error("no bounds provider")
        fun bounds(entry: Entry): Rect {
            // A deliberately invisible native branch no longer has accessible
            // screen bounds. Reuse only geometry previously verified for this
            // exact live layout object, owner and viewport; never guess coordinates.
            val node=runCatching { provider.createAccessibilityNodeInfo(entry.id) }.getOrNull()
            val measured=try { node?.let { Rect().also(it::getBoundsInScreen) } }
                finally { @Suppress("DEPRECATION") node?.recycle() }
            if(measured!=null && !measured.isEmpty) {
                geometry[entry.layout]=Rect(measured)
                return measured
            }
            return geometry[entry.layout]?.let(::Rect) ?: error("native bounds unavailable for new layout")
        }
        val barBounds = bounds(bar)
        val tabs = buttons.map { entry ->
            val action = entry.properties["OnClick"] ?: error("native click absent")
            check(callback(action) != null && entry.selected != null) { "click/selection contract failed" }
            val revision = entries.filter { child -> generateSequence(child) { it.parent }.any { it === entry } }
                .fold(1) { hash, child -> hash*31 + child.properties.filterKeys { it != "OnClick" }.hashCode() }
            Tab(entry, bounds(entry), action, revision)
        }.sortedBy { it.bounds.left }
        check(tabs.count { it.entry.selected == true } == 1) { "selection missing or ambiguous" }
        check(tabs.all { barBounds.contains(it.bounds) }) { "tab outside bar" }
        check(tabs.zipWithNext().all { (a,b) -> a.bounds.right <= b.bounds.left + 2 }) { "overlapping tabs" }
        val screen = IntArray(2).also(owner::getLocationOnScreen)
        check(barBounds.width() >= owner.width * .7 && barBounds.top >= screen[1] + owner.height * .6) { "bar outside bottom layout" }
        // LayoutNode.draw(Canvas, GraphicsLayer) is checked against the Canvas interface ABI.
        val draw = method(root.javaClass, "draw") { m ->
            m.returnType == Void.TYPE && m.parameterCount == 2 && m.parameterTypes[0].isInterface &&
                m.parameterTypes[0].methods.any { it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java)) } &&
                m.parameterTypes[0].methods.any { it.parameterTypes.contentEquals(arrayOf(
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)) }
        } ?: error("native draw ABI ambiguous")
        // Owner -> CanvasHolder -> AndroidCanvas; require exactly one linked implementation.
        val wrappers = RedditReflection.linkedInstances(owner, draw.parameterTypes[0])
        val wrapper = wrappers.singleOrNull() ?: error("CanvasHolder expected one actual Canvas, found ${wrappers.size}")
        val canvasField = wrapper.javaClass.fields.singleOrNull { it.type == android.graphics.Canvas::class.java && !Modifier.isStatic(it.modifiers) }
            ?: error("AndroidCanvas backing field ambiguous")
        wrapper.javaClass.getConstructor() // Never replace the live owner's canvas.
        // Identify translation by behavior on a fresh, isolated Canvas. Scaling has
        // the same signature; parameter count alone cannot distinguish these roles.
        val translate = translations.getOrPut(wrapper.javaClass) { wrapper.javaClass.methods.filter {
            it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType))
        }.singleOrNull { candidate ->
            val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val fresh = wrapper.javaClass.getConstructor().newInstance()
                val canvas = android.graphics.Canvas(bitmap)
                canvasField.set(fresh, canvas)
                candidate.invoke(fresh, 3f, 5f)
                val values = FloatArray(9)
                @Suppress("DEPRECATION") canvas.matrix.getValues(values)
                values.contentEquals(floatArrayOf(1f,0f,3f,0f,1f,5f,0f,0f,1f))
            } finally { bitmap.recycle() }
        } ?: error("Canvas translation contract ambiguous") }
        val invalidate = owner.javaClass.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(root.javaClass))
        }?.apply { isAccessible = true } ?: error("native layer invalidation ambiguous")
        failure = "verified ${tabs.size} native tabs"
        Binding(owner, bar, barBounds, tabs, draw, wrapper.javaClass, canvasField, invalidate, root, translate,
            RedditLayoutBranch.resolve(bar.layout,root))
    }.onFailure { failure = it.message ?: it.javaClass.simpleName }.getOrNull()
}
