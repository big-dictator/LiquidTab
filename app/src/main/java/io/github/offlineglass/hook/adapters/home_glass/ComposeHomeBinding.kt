package io.github.offlineglass.hook.adapters.home_glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Field
import java.util.IdentityHashMap

/** Reads the app's Compose semantics directly; no accessibility service or invented page indices. */
internal open class ComposeHomeBinding(val header: ViewGroup, val strip: ViewGroup, private val spec: HomeSemanticSpec) {
    private val methods = HashMap<Pair<Class<*>, String>, Method?>()
    private val configFields = HashMap<Class<*>, Field?>()
    private val keyNames = IdentityHashMap<Any, String?>()
    private val callbackFields = HashMap<Class<*>, Field?>()
    private val owners = IdentityHashMap<ViewGroup, View>()
    private val propertyNames = setOf("Text", "ContentDescription", "Selected", "OnClick")
    private val updateDisplayList = runCatching {
        View::class.java.getDeclaredMethod("updateDisplayListIfDirty").apply { isAccessible = true }
    }.getOrNull()
    private val coordinatesApi by lazy { runCatching { header.javaClass.classLoader!!.loadClass("androidx.compose.ui.layout.LayoutCoordinates") }.getOrNull() }
    private val boundsMethod by lazy {
        coordinatesApi?.let { api -> runCatching { header.javaClass.classLoader!!.loadClass("androidx.compose.ui.layout.LayoutCoordinatesKt").getMethod("boundsInRoot", api) }.getOrNull() }
    }
    private val debug = StringBuilder()
    private fun structural(value: Any, role: String, accept: (Method) -> Boolean): Method? {
        val key = value.javaClass to role
        if (!methods.containsKey(key)) {
            methods[key] = value.javaClass.methods.filter { !Modifier.isStatic(it.modifiers) && accept(it) }
                .singleOrNull()?.apply { isAccessible = true }
        }
        return methods[key]
    }
    private fun configField(type: Class<*>): Field? {
        if (!configFields.containsKey(type)) configFields[type] = type.fields.singleOrNull {
            !Modifier.isStatic(it.modifiers) && Iterable::class.java.isAssignableFrom(it.type)
        }
        return configFields[type]
    }
    private fun config(raw: Any): Any? = call(raw, "getConfig") ?: configField(raw.javaClass)?.get(raw)
    private fun bounds(raw: Any): Any? {
        call(raw, "getBoundsInRoot")?.let { return it }
        val api = coordinatesApi ?: return null
        val getter = structural(raw, "coordinates") { it.parameterCount == 0 && api.isAssignableFrom(it.returnType) } ?: return null
        val coordinates = getter.invoke(raw) ?: return null
        return boundsMethod?.invoke(null, coordinates)
    }
    private fun call(value: Any, name: String): Any? {
        val key = value.javaClass to name
        if (!methods.containsKey(key)) {
            methods[key] = value.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.apply { isAccessible = true }
        }
        val method = methods[key] ?: return null
        return method.invoke(value)
    }

    private fun callback(action: Any): Any? {
        call(action, "getAction")?.let { return it }
        val type = action.javaClass
        if (!callbackFields.containsKey(type)) callbackFields[type] = type.fields.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.type.name == "kotlin.Function"
        } ?: type.fields.singleOrNull { field ->
            !Modifier.isStatic(field.modifiers) && field.type.isInterface &&
                field.get(action)?.javaClass?.methods?.any { it.name == "invoke" && it.parameterCount == 0 } == true
        }
        return callbackFields[type]?.get(action)
    }

    private inner class Node(
        val source: View, bounds: () -> RectF, val properties: Map<String, Any?>, val parent: Node?,
    ) {
        val bounds by lazy(LazyThreadSafetyMode.NONE, bounds)
        fun lineage() = generateSequence(this) { it.parent }
        val text: String get() = (properties["Text"] as? List<*>)?.joinToString("") { it.toString() }.orEmpty()
        val description: String get() = (properties["ContentDescription"] as? List<*>)?.joinToString(" ").orEmpty()
        val selected: Boolean get() = lineage().any { it.properties["Selected"] == true }
        // Labels may contribute a descriptive OnClick with no callback. Keep looking for the native action.
        val action: Any? get() = lineage().mapNotNull { it.properties["OnClick"] }.firstOrNull { callback(it) != null }
    }
    data class Tab(val cell: View, val title: TextView)
    var tabs: List<Tab> = emptyList(); private set
    var avatar: View? = null; private set
    var message: View? = null; private set
    var game: View? = null; private set
    var category: View? = null; private set
    var search: View? = null; private set
    var diagnostic: String = ""; private set
    val searchText = TextView(header.context)
    private var lastRefresh = 0L
    fun needsRefresh(now: Long): Boolean = now - lastRefresh >= 2000L ||
        (now - lastRefresh >= 300L && owners.values.any { it.isDirty || it.isLayoutRequested })

    private fun nodes(root: ViewGroup): List<Node> {
        fun owner(view: View): View? {
            if (view.javaClass.methods.any { it.name == "getSemanticsOwner" && it.parameterCount == 0 }) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) owner(view.getChildAt(i))?.let { return it }
            return null
        }
        val host = owners[root]?.takeIf { it.isAttachedToWindow } ?: owner(root)?.also { owners[root] = it } ?: run {
            debug.append("noOwner=${root.javaClass.name} children=${(0 until root.childCount).map { root.getChildAt(it).javaClass.name }};")
            return emptyList()
        }
        val semantics = call(host, "getSemanticsOwner") ?: run { debug.append("ownerNull;"); return emptyList() }
        val first = call(semantics, "getUnmergedRootSemanticsNode")
            ?: call(semantics, "getRootSemanticsNode")
            ?: structural(semantics, "root") { it.parameterCount == 0 && configField(it.returnType) != null }?.invoke(semantics)
            ?: run { debug.append("noRoot=${semantics.javaClass.name};"); return emptyList() }
        val result = ArrayList<Node>()
        fun visit(raw: Any, parent: Node?, depth: Int) {
            if (depth > 32 || result.size >= 256) return
            val config = config(raw) ?: run { debug.append("noConfig=${raw.javaClass.name};"); return }
            val iterator = call(config, "iterator") as? Iterator<*> ?: run { debug.append("noIterator=${config.javaClass.name};"); return }
            val props = HashMap<String, Any?>()
            while (iterator.hasNext()) {
                val entry = iterator.next() as? Map.Entry<*, *> ?: continue
                val key = entry.key ?: continue
                if (!keyNames.containsKey(key)) keyNames[key] = call(key, "getName") as? String ?: key.javaClass.declaredFields.asSequence()
                    .filter { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                    .mapNotNull { field -> runCatching { field.isAccessible = true; field.get(key) as? String }.getOrNull() }
                    .firstOrNull { it in propertyNames }
                    ?: propertyNames.firstOrNull { key.toString().endsWith(": $it") }
                if (keyNames[key] == null && debug.length < 1000) debug.append("key=${key.javaClass.name}:$key;")
                val name = keyNames[key] ?: continue
                if (name !in propertyNames) continue
                props[name] = entry.value
            }
            val node = Node(host, {
                val rawBounds = bounds(raw)
                if (rawBounds != null) {
                    fun edge(name: String) = (call(rawBounds, name) as? Number)?.toFloat() ?: 0f
                    RectF(edge("getLeft"), edge("getTop"), edge("getRight"), edge("getBottom"))
                } else {
                    // Older/minified Compose may rename the coordinate ABI; Android's provider is stable.
                    val id = (call(raw, "getId") as? Int) ?: raw.javaClass.fields.singleOrNull {
                        !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
                    }?.getInt(raw)
                    val info = id?.let { host.accessibilityNodeProvider?.createAccessibilityNodeInfo(it) }
                    val rect = android.graphics.Rect()
                    info?.getBoundsInScreen(rect)
                    val screen = IntArray(2); host.getLocationOnScreen(screen)
                    rect.offset(-screen[0], -screen[1])
                    info?.recycle()
                    RectF(rect)
                }
            }, props, parent)
            result += node
            // Compose's public replacedChildren keeps semantics independent of R8 names in Bili.
            val children = call(raw, "getReplacedChildren") ?: call(raw, "getChildren")
                ?: structural(raw, "children") {
                    List::class.java.isAssignableFrom(it.returnType) && it.parameterCount in 2..3 &&
                        it.parameterTypes.all { type -> type == Boolean::class.javaPrimitiveType }
                }?.let { method -> method.invoke(raw, *Array<Any>(method.parameterCount) { false }) }
                ?: structural(raw, "replacedChildren") { List::class.java.isAssignableFrom(it.returnType) && it.parameterCount == 0 }?.invoke(raw)
            (children as? List<*>)?.forEach { if (it != null) visit(it, node, depth + 1) }
        }
        visit(first, null, 0)
        return result
    }

    private inner class NativePart(context: Context, var node: Node, var actionNode: Node = node, private val artwork: Boolean) : View(context) {
        init { update(node, actionNode) }
        fun update(value: Node, target: Node = value) {
            node = value; actionNode = target
            if (artwork) {
                val w = value.bounds.width().toInt().coerceAtLeast(1)
                val h = value.bounds.height().toInt().coerceAtLeast(1)
                if (width != w || height != h) layout(0, 0, w, h)
            }
            isSelected = target.selected
            contentDescription = target.description.ifBlank { target.text }
        }
        override fun performClick(): Boolean {
            lastRefresh = 0L
            // Invoke the native AccessibilityAction's real callback, even while its source is hidden.
            val action = actionNode.action ?: return false
            val function = callback(action) ?: return false
            val result = call(function, "invoke")
            return result != false
        }
        override fun draw(canvas: Canvas) {
            if (!artwork || !node.source.isAttachedToWindow || node.bounds.isEmpty) return
            val saved = canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.translate(-node.bounds.left, -node.bounds.top)
            // Reuse the app's live display list. Re-record only when the native source is dirty.
            val recorded = if (canvas.isHardwareAccelerated) runCatching {
                updateDisplayList?.invoke(node.source) as? RenderNode
            }.getOrNull() else null
            if (recorded?.hasDisplayList() == true) canvas.drawRenderNode(recorded) else node.source.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }

    fun refresh(dark: Boolean): Boolean {
        lastRefresh = android.os.SystemClock.uptimeMillis()
        debug.setLength(0)
        val top = nodes(header)
        val row = nodes(strip)
        val candidates = row.filter { it.text.isNotBlank() && it.action != null }
            .distinctBy { it.text }
        diagnostic = "top=${top.size} row=${row.size} tabs=${candidates.map { it.text }} actions=${top.filter { it.action != null }.map { it.description }} debug=$debug" +
            if (candidates.isEmpty()) " rows=" + row.map { node -> node.properties.mapValues { (_, v) -> v.toString() } } else ""
        if (!spec.channels.all { label -> candidates.any { it.text == label } }) return false
        fun topNode(vararg labels: String) = top.firstOrNull { node -> labels.any { node.description.contains(it, true) } }
        val face = topNode(*spec.avatar.toTypedArray()) ?: topNode(*spec.account.toTypedArray()) ?: return false
        val faceAction = topNode(*spec.account.toTypedArray())?.takeIf { it.action != null } ?: face
        val searchImage = topNode(*spec.searchIcon.toTypedArray())
        val searchAction = searchImage?.lineage()?.firstOrNull { it.action != null }
            ?: topNode(*spec.search.toTypedArray())?.takeIf { it.action != null } ?: return false
        val mail = topNode(*spec.message.toTypedArray())?.takeIf { it.action != null }
        if (spec.requireMessage && mail == null) return false
        fun part(old: View?, node: Node?, action: Node? = node, artwork: Boolean = false): View? {
            if (node == null || action == null) return null
            return (old as? NativePart)?.apply { update(node, action) } ?: NativePart(header.context, node, action, artwork)
        }
        avatar = part(avatar, face, faceAction, artwork = true)
        message = part(message, mail, artwork = true)
        game = part(game, topNode(*spec.game.toTypedArray()))
        search = part(search, searchAction)
        val oldTabs = tabs.associateBy { it.title.text.toString() }
        tabs = candidates.map { node ->
            val old = oldTabs[node.text]
            val title = old?.title ?: TextView(header.context)
            title.text = node.text; title.isSelected = node.selected
            title.setTextColor(if (dark) Color.WHITE else Color.BLACK)
            Tab(part(old?.cell, node)!!, title)
        }
        val word = top.firstOrNull { it.text.isNotBlank() }?.text ?: "搜索"
        searchText.text = word
        searchText.textSize = 14f
        searchText.setTextColor(if (dark) 0xFFBBBBBB.toInt() else 0xFF777777.toInt())
        return true
    }
}
