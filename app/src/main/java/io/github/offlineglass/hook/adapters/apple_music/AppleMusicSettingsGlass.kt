package io.github.offlineglass.hook.adapters.apple_music

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.apple_music.AppleMusicArtistCompose.Chain
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import kotlin.math.abs

/** Settings is the native action_settings destination. Only its verified draw nodes are changed. */
internal class AppleMusicSettingsGlass(private val host: GlassHostLayout) {
    private val pkg = targetSpec.packageName
    private val fragmentTag = host.resources.getIdentifier("fragment_container_view_tag", "id", pkg)
    private val nativeRadius = host.resources.getIdentifier("corner_radius_xl", "dimen", pkg)
    private val backLabels by lazy {
        listOf("navigate_up", "nav_app_bar_navigate_up_description").mapNotNull { name ->
            host.resources.getIdentifier(name, "string", pkg).takeIf { it != 0 }?.let(host.resources::getString)
        }.toSet()
    }
    private var page: View? = null
    private var back: AppleMusicArtistOptics.Surface? = null
    private val navHostId = host.resources.getIdentifier("nav_host_fragment_activity_main", "id", pkg)
    private var navHost: View? = null
    private var composeOwner: View? = null
    private var composeRoot: Any? = null
    private val activeNodes = java.util.IdentityHashMap<Any, Int>()
    private val retries = java.util.IdentityHashMap<Any, Int>()
    private var mapKeys: java.lang.reflect.Field? = null
    private var mapValues: java.lang.reflect.Field? = null
    private val cards = java.util.IdentityHashMap<Any, AppleMusicArtistCompose.Clip>()
    private val controls = java.util.IdentityHashMap<Any, Toggle>()
    private val location = IntArray(2)

    fun update(root: View?) {
        if (root == null) {
            clear()
            return
        }
        back?.refresh()
        if (composeOwner?.let { it.isAttachedToWindow && it.isShown && it.rootView === root } == true) return
        runCatching { bind(root) }.onFailure {
            ArtistDiagnostics.log("settings draw contract rejected: ${it.cause ?: it}")
        }
    }

    private fun bind(root: View) {
        if (fragmentTag == 0) return
        val scope = navHost?.takeIf { it.isAttachedToWindow && it.rootView === root }
            ?: root.findViewById<View>(navHostId)?.also { navHost = it } ?: return
        val current = page?.takeIf { it.isAttachedToWindow && it.isShown && it.rootView === root.rootView }
            ?: views(scope, 22).singleOrNull {
                it.getTag(fragmentTag)?.javaClass?.name ==
                    "com.apple.android.music.settings2.fragment.SettingsFragment"
            }
        if (current !== page) {
            clear()
            page = current
        }
        val owner = current?.let { views(it, 20).singleOrNull { candidate ->
            candidate.accessibilityNodeProvider != null && candidate.javaClass.methods.any {
                it.name == "getLayoutNodes" && it.parameterCount == 0
            }
        } } ?: return
        ArtistDiagnostics.attach(owner.context)
        val abi = OverflowDraw.abi(owner)
        val layout = abi.root.invoke(owner) ?: return
        if (composeOwner !== owner || composeRoot !== layout) {
            composeRoot?.let(settingsRoots::remove)
            composeOwner = owner
            composeRoot = layout
            installBeforeLayoutDraw(abi)
            settingsRoots[layout] = java.lang.ref.WeakReference(this)
            activeNodes.clear()
            mapKeys = null
            mapValues = null
        }
    }

    private fun beforeLayoutDraw(owner: View, abi: OverflowDraw.Abi, layout: Any) {
        // A recycled row can rewrite the shape on an existing modifier.
        cards.values.forEach { it.update(true) }
        val map = abi.layoutNodes.invoke(owner) ?: return
        val members by lazy {
            generateSequence(map.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }.toList()
        }
        val valuesField = mapValues ?: members.singleOrNull {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Array<Any>::class.java
        }?.apply { isAccessible = true }?.also { mapValues = it } ?: return
        val keysField = mapKeys ?: members.singleOrNull {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == IntArray::class.java
        }?.apply { isAccessible = true }?.also { mapKeys = it } ?: return
        val nodes = valuesField.get(map) as? Array<*> ?: return
        val ids = keysField.get(map) as? IntArray ?: return
        if (nodes.size != ids.size || nodes.size > 1024) return
        var live = 0
        var changed = back == null
        for (i in nodes.indices) {
            val node = nodes[i] ?: continue
            if (!layout.javaClass.isInstance(node)) continue
            live++
            if (activeNodes[node] != ids[i]) changed = true
        }
        if (!changed && live == activeNodes.size) {
            if (retries.isNotEmpty()) bindLayouts(owner, abi, layout, retries.keys.toList())
            return
        }
        val current = java.util.IdentityHashMap<Any, Int>()
        val added = ArrayList<Any>()
        for (i in nodes.indices) {
            val node = nodes[i] ?: continue
            if (!layout.javaClass.isInstance(node)) continue
            current[node] = ids[i]
            if (activeNodes[node] != ids[i]) {
                retries[node] = 4
                added.add(node)
            } else if (node in retries) added.add(node)
        }
        cards.entries.removeAll { (node, clip) ->
            if (current.containsKey(node) && activeNodes[node] == current[node]) false
            else { clip.update(false); true }
        }
        controls.entries.removeAll { (node, toggle) ->
            if (current.containsKey(node) && activeNodes[node] == current[node]) false
            else { detachToggle(toggle); true }
        }
        activeNodes.clear()
        activeNodes.putAll(current)
        retries.keys.retainAll(current.keys)
        bindLayouts(owner, abi, layout, added)
    }

    private fun bindLayouts(owner: View, abi: OverflowDraw.Abi, layout: Any, added: List<Any>) {
        val geometry = AppleMusicArtistCompose(abi)
        val density = owner.resources.displayMetrics.density
        val nativeCardRadius = if (nativeRadius != 0) owner.resources.getDimension(nativeRadius) / density else 0f
        owner.getLocationOnScreen(location)
        for (node in added) {
            val attempts = retries[node] ?: 1
            if (attempts <= 1) retries.remove(node) else retries[node] = attempts - 1
            var clipPending = false

            val frame = runCatching { Chain.bounds(node) }.getOrNull() ?: continue
            if (nativeCardRadius > 0f && frame.width() > owner.width * .75f) {
                Chain.read(node).orEmpty().forEach { modifier ->
                    val description = modifier.toString()
                    if (!description.startsWith("SimpleGraphicsLayerModifier(")) return@forEach
                    val match = cornerPattern.find(description) ?: return@forEach
                    val radii = match.groupValues.drop(1).map { it.toFloatOrNull() ?: 0f }
                    val top = radii[0] == nativeCardRadius && radii[1] == nativeCardRadius
                    val bottom = radii[2] == nativeCardRadius && radii[3] == nativeCardRadius
                    if ((!top && radii.take(2).any { it != 0f }) ||
                        (!bottom && radii.drop(2).any { it != 0f }) || (!top && !bottom)) return@forEach
                    val edge = if (top && bottom) 0 else if (top) 1 else -1
                    clipPending = true
                    AppleMusicArtistCompose.Clip.resolve(node, modifier, frame, 22f * density,
                        22f * density, 0f, edge, settingsRow = true)?.let { clip ->
                        if (clip.update(true)) {
                            cards[node] = clip
                            clipPending = false

                        }
                    }
                }
            }
            // Material switch track is 52x32dp with one separately placed thumb.
            if (frame.width() / density !in 51f..53f || frame.height() / density !in 31f..49f) {
                if (!clipPending) retries.remove(node)
                continue
            }
            val thumb = abi.children(node, 2, matchOrdering = false)?.singleOrNull() ?: continue
            val thumbBounds = Chain.boundsReader(thumb) ?: continue
            val thumbFrame = thumbBounds() ?: continue
            if (thumbFrame.width() / density !in 14f..32f ||
                abs(thumbFrame.centerY() - frame.centerY()) > 4f * density) continue
            val bounds = Rect().also(frame::round)
            val material = geometry.solid(node, layout, bounds, density, AppleMusicArtistGlass.Role.INFO, exactLayout = true)
                ?.takeIf { it.covered.isNotEmpty() } ?: continue
            val checked = checkedReader(thumb) ?: continue
            val toggle = Toggle(owner, material, thumb, checked)
            attachToggle(toggle)
            controls[node] = toggle
            retries.remove(node)

            material.invalidate()
            owner.invalidate()
        }
        if (back == null) bindBack(owner, abi, layout, geometry, density)
        ArtistDiagnostics.attach(owner.context)
        ArtistDiagnostics.log("settings bindings cards=${cards.size} toggles=${controls.size} back=${back != null}")
    }

    private fun bindBack(owner: View, abi: OverflowDraw.Abi, layout: Any,
        geometry: AppleMusicArtistCompose, density: Float) {
        val provider = owner.accessibilityNodeProvider ?: return
        val pending = ArrayDeque<Pair<Int, Int>>().apply { add(View.NO_ID to View.NO_ID) }
        val found = ArrayList<Pair<Int, Rect>>()
        repeat(180) {
            val (id, parent) = pending.removeFirstOrNull() ?: return@repeat
            val item = provider.createAccessibilityNodeInfo(id) ?: return@repeat
            try {
                if (item.contentDescription?.toString() in backLabels && item.isVisibleToUser) {
                    val buttonId = if (item.isClickable) id else parent
                    val button = provider.createAccessibilityNodeInfo(buttonId)
                    if (button != null) try {
                        val box = Rect().also(button::getBoundsInScreen)
                        if (button.isClickable && box.width() / density in 36f..56f &&
                            box.top - location[1] < 120 * density) found.add(buttonId to box)
                    } finally { button.recycle() }
                }
                for (index in 0 until minOf(item.childCount, 40)) {
                    val child = childId?.invoke(item, index) as? Long ?: break
                    pending.add((child shr 32).toInt() to id)
                }
            } finally { item.recycle() }
        }
        val (id, box) = found.distinctBy { it.first }.singleOrNull() ?: return
        val node = io.github.offlineglass.hook.adapters.spotify.SpotifyComposeIndex
            .read(layout, abi.layoutNodes.invoke(owner))?.node(id) ?: return
        val role = AppleMusicArtistGlass.Role.BACK
        val haze = geometry.material(node, layout, box, role, density) ?: return
        val solid = geometry.solid(node, layout, box, density, role)
        val native = solid?.copy(sources = haze.sources, placement = haze.placement,
            covered = solid.covered + (haze.haze to haze.draw)) ?: haze
        val surface = AppleMusicArtistOptics.Surface(host, owner, role, native, box, settingsBack = true)
        if (AppleMusicArtistOptics.bind(native.haze, surface)) back = surface
    }

    fun clear() {
        composeRoot?.let(settingsRoots::remove)
        composeRoot = null
        composeOwner = null
        activeNodes.clear()
        mapKeys = null
        mapValues = null
        back?.let { AppleMusicArtistOptics.unbind(it.native.haze, it) }
        back = null
        cards.values.forEach { it.update(false) }
        cards.clear()
        controls.values.forEach(::detachToggle)
        controls.clear()
        page = null
    }

    private class Toggle(val owner: View, val native: AppleMusicArtistCompose.Material,
        private val thumbLayout: Any, private var checked: () -> Boolean?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = android.animation.ArgbEvaluator()
        private var activeColor = 0xff0085ff.toInt()
        private var previous: Boolean? = null
        private var fraction = 0f
        private var animation: android.animation.ValueAnimator? = null

        fun stop() { animation?.cancel() }

        fun draw(scope: Any): Boolean {
            val (canvas, width, height) = native.draw.access.read(scope) ?: return false
            val density = owner.resources.displayMetrics.density
            if (abs(width / density - 52f) > 1f || abs(height / density - 32f) > 1f) return false
            val selected = checked() ?: checkedReader(thumbLayout)?.let { replacement ->
                checked = replacement
                replacement()
            } ?: previous ?: return false
            if (previous != selected) {
                val target = if (selected) 1f else 0f
                if (previous == null) fraction = target else {
                    stop()
                    animation = android.animation.ValueAnimator.ofFloat(fraction, target).apply {
                        duration = 220L
                        interpolator = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
                        addUpdateListener {
                            fraction = it.animatedValue as Float
                            native.invalidate()
                            owner.invalidate()
                        }
                        start()
                    }
                }
                previous = selected
            }
            val rtl = owner.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val dark = owner.resources.configuration.uiMode and 0x30 == 0x20
            val off = if (dark) 0x5c787880 else 0x33787878
            if (selected) activeColor = native.color() ?: activeColor
            paint.color = colors.evaluate(fraction, off, activeColor) as Int
            val left = width / 2 - 32 * density
            val mid = height / 2
            canvas.drawRoundRect(left, mid - 14 * density, left + 64 * density,
                mid + 14 * density, 14 * density, 14 * density, paint)
            val center = left + (22f + 20f * if (rtl) 1f - fraction else fraction) * density
            paint.color = Color.WHITE
            canvas.drawRoundRect(center - 20 * density, mid - 12 * density,
                center + 20 * density, mid + 12 * density, 12 * density, 12 * density, paint)
            return true
        }
    }

    private companion object {
        val childId = runCatching {
            android.view.accessibility.AccessibilityNodeInfo::class.java.getDeclaredMethod(
                "getChildId", Int::class.javaPrimitiveType).apply { isAccessible = true }
        }.getOrNull()
        val cornerPattern = Regex("""RoundedCornerShape\(topStart = CornerSize\(size = ([\d.]+)\.(?:dp|px)\), topEnd = CornerSize\(size = ([\d.]+)\.(?:dp|px)\), bottomEnd = CornerSize\(size = ([\d.]+)\.(?:dp|px)\), bottomStart = CornerSize\(size = ([\d.]+)\.(?:dp|px)\)\)""")
        val toggles = java.util.IdentityHashMap<Any, Toggle>()
        val borders = java.util.IdentityHashMap<Any, Toggle>()
        val hooks = HashSet<java.lang.reflect.Method>()
        val settingsRoots = java.util.WeakHashMap<Any, java.lang.ref.WeakReference<AppleMusicSettingsGlass>>()
        val layoutDrawHooks = HashSet<java.lang.reflect.Method>()

        fun installBeforeLayoutDraw(abi: OverflowDraw.Abi) {
            if (abi.draw in layoutDrawHooks) return
            XposedBridge.hookMethod(abi.draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // The native Compose owner measures its lazy items just before drawing its root.
                    val layout = param.thisObject ?: return
                    val session = settingsRoots[layout]?.get() ?: return
                    val owner = session.composeOwner ?: return
                    runCatching {
                        if (owner.isShown && owner.isAttachedToWindow) session.beforeLayoutDraw(owner, abi, layout)
                    }.onFailure { ArtistDiagnostics.log("settings before draw rejected: ${it.cause ?: it}") }
                }
            })
            layoutDrawHooks.add(abi.draw)
        }

        private fun checkedReader(layout: Any): (() -> Boolean?)? {
            // The native ThumbElement is recreated with the current checked value.
            // Resolve its enclosing modifier and binary chain by type and semantics.
            val fields = layout.javaClass.declaredFields.filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type.isInterface
            }.onEach { it.isAccessible = true }
            val entry = fields.singleOrNull {
                it.get(layout)?.toString()?.contains("ThumbElement(interactionSource=") == true
            } ?: return null
            val pending = ArrayDeque<Pair<Any, List<java.lang.reflect.Field>>>()
            pending.add((entry.get(layout) ?: return null) to emptyList())
            val candidates = ArrayList<Pair<List<java.lang.reflect.Field>, java.lang.reflect.Field>>()
            var budget = 24
            while (pending.isNotEmpty() && budget-- > 0) {
                val (element, chain) = pending.removeFirst()
                val members = element.javaClass.declaredFields.filterNot {
                    java.lang.reflect.Modifier.isStatic(it.modifiers)
                }
                if (element.toString().startsWith("ThumbElement(interactionSource=")) {
                    val checked = members.singleOrNull { it.type == Boolean::class.javaPrimitiveType }
                        ?.apply { isAccessible = true } ?: return null
                    candidates.add(chain to checked)
                    continue
                }
                members.filter { it.type == entry.type }.forEach { field ->
                    field.isAccessible = true
                    field.get(element)?.let { pending.add(it to (chain + field)) }
                }
            }
            val (chain, checked) = candidates.singleOrNull()?.takeIf { pending.isEmpty() } ?: return null
            return read@{
                var element = entry.get(layout) ?: return@read null
                for (field in chain) {
                    if (!field.declaringClass.isInstance(element)) return@read null
                    element = field.get(element) ?: return@read null
                }
                if (checked.declaringClass.isInstance(element)) checked.getBoolean(element) else null
            }
        }

        fun attachToggle(toggle: Toggle) {
            val material = toggle.native
            (material.covered.map { it.second } + material.draw).forEach { draw ->
                if (draw.method in hooks) return@forEach
                XposedBridge.hookMethod(draw.method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val scope = param.args[0] ?: return
                        val toggle = toggles[param.thisObject]
                        if (toggle != null) {
                            if (toggle.draw(scope)) param.result = null
                        } else if (param.thisObject in borders) {
                            draw.content.invoke(scope)
                            param.result = null
                        }
                    }
                })
                hooks.add(draw.method)
            }
            toggles[material.haze] = toggle
            material.covered.forEach { (node, _) -> borders[node] = toggle }
        }

        fun detachToggle(toggle: Toggle) {
            toggle.stop()
            val material = toggle.native
            if (toggles[material.haze] === toggle) toggles.remove(material.haze)
            material.covered.forEach { (node, _) -> if (borders[node] === toggle) borders.remove(node) }
            runCatching { material.invalidate(); toggle.owner.invalidate() }
        }

        fun views(root: View, limit: Int): List<View> {
            val pending = ArrayDeque<View>().apply { add(root) }
            val result = ArrayList<View>()
            while (pending.isNotEmpty() && result.size < limit) {
                val view = pending.removeFirst()
                if (!view.isShown) continue
                result.add(view)
                if (view is ViewGroup) repeat(minOf(view.childCount, 28)) { pending.add(view.getChildAt(it)) }
            }
            return result
        }
    }
}
