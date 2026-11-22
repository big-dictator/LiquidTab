package io.github.offlineglass.hook.adapters.apple_music

import android.graphics.Rect
import android.view.View
import io.github.offlineglass.hook.adapters.apple_music.AppleMusicArtistGlass.Role
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/** Compose reports a new semantics configuration on attachment and clears it on detach. */
internal class AppleMusicArtistSemantics private constructor(
    private val abi: OverflowDraw.Abi, private val configuration: Field,
    private val labels: Map<Role, Set<String>>, private val changed: () -> Unit,
) {
    data class Target(val node: Any, val bounds: Rect)
    private val attached = IdentityHashMap<Any, Role>()
    private var unsubscribe: (() -> Unit)? = null
    private var clickKey: Any? = null
    private var descriptionKey: Any? = null

    private fun properties(node: Any) = configuration.get(node) as? Iterable<*>

    fun isDetached(node: Any) = configuration.get(node) == null

    private fun hasClick(node: Any) = properties(node)?.any {
        val key = (it as? Map.Entry<*, *>)?.key ?: return@any false
        if (clickKey == null && key.toString() == "AccessibilityKey: OnClick") clickKey = key
        key === clickKey
    } == true

    private fun updated(node: Any) {
        val old = attached.remove(node)
        val descriptions = properties(node)?.firstNotNullOfOrNull {
            val entry = it as? Map.Entry<*, *> ?: return@firstNotNullOfOrNull null
            val key = entry.key ?: return@firstNotNullOfOrNull null
            if (descriptionKey == null && key.toString() == "AccessibilityKey: ContentDescription") descriptionKey = key
            if (key === descriptionKey) entry.value as? List<*> else null
        }
        val role = descriptions?.let { names -> labels.entries.singleOrNull { (_, values) ->
            names.any { it is String && it in values }
        }?.key }
        if (role != null && attached.size < 32) attached[node] = role
        if (old != role) changed()
    }

    fun button(role: Role): Target? = attached.entries.asSequence().filter { it.value == role }
        .mapNotNull { entry ->
            val node = generateSequence(entry.key, abi::parentOf).take(5).firstOrNull(::hasClick)
                ?: return@mapNotNull null
            target(node)
        }.singleOrNull()

    fun enclosingCard(action: Target, width: Int, density: Float): Target? =
        generateSequence(abi.parentOf(action.node), abi::parentOf).take(16)
            .filter(::hasClick).mapNotNull(::target).filter {
                it.bounds.contains(action.bounds) && it.bounds.width() > width * .65f &&
                    it.bounds.height().toFloat() in (86f * density)..(160f * density)
            }.singleOrNull()

    private fun target(node: Any): Target? {
        val frame = AppleMusicArtistCompose.Chain.bounds(node) ?: return null
        return Target(node, Rect().also(frame::round))
    }

    fun close() {
        unsubscribe?.invoke()
        unsubscribe = null
        attached.clear()
    }

    companion object {
        fun observe(view: View, abi: OverflowDraw.Abi, labels: Map<Role, Set<String>>,
            changed: () -> Unit): AppleMusicArtistSemantics? {
            val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) ?: return null
            val root = abi.root.invoke(view) ?: return null
            val table = abi.layoutNodes.invoke(view) ?: return null
            val members = owner.javaClass.declaredFields.onEach { it.isAccessible = true }
            if (members.none { it.get(owner) === root } || members.none { it.get(owner) === table }) return null
            val contract = members.mapNotNull { member ->
                val generic = member.genericType as? ParameterizedType ?: return@mapNotNull null
                val type = generic.actualTypeArguments.singleOrNull() as? Class<*> ?: return@mapNotNull null
                if (!type.isInterface) return@mapNotNull null
                val notify = type.declaredMethods.singleOrNull() ?: return@mapNotNull null
                if (notify.returnType != Void.TYPE || notify.parameterCount != 2 ||
                    notify.parameterTypes[0] != root.javaClass ||
                    !Iterable::class.java.isAssignableFrom(notify.parameterTypes[1])) return@mapNotNull null
                Triple(member, type, notify)
            }.singleOrNull() ?: return null
            val (member, type, notify) = contract
            val config = root.javaClass.declaredFields.singleOrNull { it.type == notify.parameterTypes[1] }
                ?.apply { isAccessible = true } ?: return null
            val observers = member.get(owner) ?: return null
            fun mutation(result: Class<*>): Method? = observers.javaClass.declaredMethods.singleOrNull {
                it.returnType == result && it.parameterTypes.contentEquals(arrayOf(Any::class.java))
            }?.apply { isAccessible = true }
            val add = mutation(Void.TYPE) ?: return null
            val remove = mutation(Boolean::class.javaPrimitiveType!!) ?: return null
            val binding = AppleMusicArtistSemantics(abi, config, labels, changed)
            val listener = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when {
                    method == notify -> { binding.updated(args[0]); null }
                    method.name == "equals" -> proxy === args[0]
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    else -> "Apple Music artist controls"
                }
            }
            add.invoke(observers, listener)
            binding.unsubscribe = { remove.invoke(observers, listener) }
            return binding
        }
    }
}
