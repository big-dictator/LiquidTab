package io.github.offlineglass.hook.adapters.apple_music

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.WindowId
import io.github.offlineglass.hook.setBottomBarSquircle
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Resolves live Modifier.Node chains from the artist's verified semantic layout nodes. */
internal class AppleMusicArtistCompose(private val abi: OverflowDraw.Abi) {
    internal class CoordinateAccess private constructor(
        private val attached: Method,
        private val size: Method,
        private val transform: Method,
    ) {
        fun read(coordinate: Any, matrix: FloatArray): Long? {
            if (!attached.declaringClass.isInstance(coordinate)) return null
            return try {
                if (attached.invoke(coordinate) != true) return null
                val dimensions = size.invoke(coordinate) as Long
                if ((dimensions ushr 32).toInt() <= 0 || dimensions.toInt() <= 0) return null
                transform.invoke(coordinate, matrix)
                dimensions
            } catch (failure: InvocationTargetException) {
                // Lazy content can lose its owning layout during disposal. Compose
                // rejects both detached coordinates and detached ancestor walks.
                if (failure.targetException !is IllegalStateException) throw failure
                null
            }
        }

        companion object {
            fun resolve(role: Class<*>): CoordinateAccess? {
                val members = role.declaredMethods
                if (members.none { it.returnType == role && it.parameterCount == 0 }) return null
                val attached = members.singleOrNull {
                    it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0
                } ?: return null
                val size = members.singleOrNull {
                    it.returnType == Long::class.javaPrimitiveType && it.parameterCount == 0
                } ?: return null
                val transform = members.singleOrNull {
                    it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java))
                } ?: return null
                return CoordinateAccess(attached.apply { isAccessible = true }, size.apply { isAccessible = true },
                    transform.apply { isAccessible = true })
            }
        }
    }

    /** The Compose owner keeps its attached LayoutNodes in one int-keyed scatter map. */
    class LayoutPopulation private constructor(
        private val table: Any, private val count: Field, private var previous: Int,
    ) {
        fun changed(): Boolean {
            val current = count.getInt(table)
            if (current == previous) return false
            previous = current
            return true
        }

        companion object {
            fun resolve(table: Any, root: Any): LayoutPopulation? {
                val storage = generateSequence(table.javaClass as Class<*>?) { it.superclass }
                    .map { type -> type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) } }
                    .singleOrNull { fields ->
                        fields.count { it.type == IntArray::class.java } == 1 &&
                            fields.count { it.type == LongArray::class.java } == 1 &&
                            fields.count { it.type == Array<Any>::class.java } == 1
                    } ?: return null
                val values = storage.single { it.type == Array<Any>::class.java }
                    .apply { isAccessible = true }.get(table) as Array<*>
                if (values.size > 131072 || values.none { it === root }) return null
                val size = values.count { it != null }
                val dimensions = storage.filter { it.type == Int::class.javaPrimitiveType }
                    .onEach { it.isAccessible = true }
                if (size == values.size || dimensions.size != 2 ||
                    dimensions.count { it.getInt(table) == values.size } != 1) return null
                val count = dimensions.singleOrNull { it.getInt(table) == size } ?: return null
                return LayoutPopulation(table, count, size)
            }
        }
    }

    data class Draw(val method: Method, val content: Method, val access: DrawAccess)
    data class Placement(val target: Any, val methods: List<Method>, val request: () -> Unit)
    data class Material(
        val haze: Any, val draw: Draw, val color: () -> Int?,
        val placement: Placement?, val invalidate: () -> Unit,
        val artwork: Artwork?, val frameClip: Clip?, val solid: Boolean, val sources: () -> List<Source>,
        val bounds: () -> RectF?, val cachedEffect: () -> android.graphics.RenderEffect?,
        val covered: List<Pair<Any, Draw>> = emptyList(),
        val toolbarItem: ToolbarItem? = null,
        val drawBounds: (() -> RectF?)? = null,
    )
    data class Artwork(val layout: Any, val draw: Method, val rect: RectF, val radiusX: Float,
        val radiusY: Float, val clip: Clip?)
    data class Source(val node: RenderNode, val x: Float, val y: Float)

    data class PressLayer(val layout: Any, val draw: Method, val parentBounds: () -> RectF?,
        val invalidate: () -> Unit)

    fun exteriorLayer(platter: Any): PressLayer? {
        val parent = abi.parentOf(platter) ?: return null
        val bounds = Chain.boundsReader(parent) ?: return null
        val repaint = Chain.read(parent).orEmpty().firstNotNullOfOrNull {
            Chain.invalidator(parent, it, abi.parentOf(parent))
        } ?: { abi.invalidateLayers.invoke(null, parent); Unit }
        return PressLayer(platter, abi.draw, bounds, repaint)
    }

    fun pressLayer(click: Any, root: Any, bounds: Rect): PressLayer? {
        // The layout draws into its parent's local canvas before its own clipped layer.
        // Include coincident wrappers, but never scale the row containing neighbouring actions.
        var layout = click
        for (parent in generateSequence(abi.parentOf(click), abi::parentOf).take(5)) {
            if (parent === root) break
            val rect = Chain.bounds(parent) ?: break
            if (kotlin.math.abs(rect.left - bounds.left) > 2 || kotlin.math.abs(rect.top - bounds.top) > 2 ||
                kotlin.math.abs(rect.right - bounds.right) > 2 || kotlin.math.abs(rect.bottom - bounds.bottom) > 2) break
            layout = parent
        }
        val parent = abi.parentOf(layout) ?: return null
        val parentBounds = Chain.boundsReader(parent) ?: return null
        val invalidate = generateSequence(parent, abi::parentOf).take(4).firstNotNullOfOrNull { ancestor ->
            Chain.read(ancestor).orEmpty().firstNotNullOfOrNull { modifier ->
                Chain.invalidator(ancestor, modifier, abi.parentOf(ancestor))
            }
        } ?: return null
        return PressLayer(layout, abi.draw, parentBounds, invalidate)
    }

    // Native platter Box -> empty backdrop sibling + clickable Box -> vector painter.
    // Keep the real vector subtree, including its current tint and draw state.
    data class ToolbarItem(
        val backdrop: Any, val draw: Method, val icon: Any,
        val decorations: List<Pair<Any, Draw>>, val invalidate: () -> Unit,
    )

    fun toolbarItem(click: Any, root: Any): ToolbarItem? {
        val box = platter(click, root) ?: return null
        val children = abi.children(box, matchOrdering = false) ?: return null
        val backdrop = children.singleOrNull { child ->
            abi.children(child, matchOrdering = false)?.isEmpty() == true
        } ?: return null
        val face = children.singleOrNull { it !== backdrop } ?: return null
        val branch = localLayouts(face, 4, limit = 20)
        if (click !in branch) return null
        val icon = branch.singleOrNull { layout ->
            layout !== face && abi.children(layout, matchOrdering = false)?.isEmpty() == true &&
                Chain.read(layout)?.count { it.toString().startsWith("PainterModifier(") } == 1
        } ?: return null
        val rear = Chain.bounds(backdrop) ?: return null
        val iconBounds = Chain.boundsReader(icon) ?: return null
        val image = iconBounds() ?: return null
        if (kotlin.math.abs(rear.width() - rear.height()) > 2 || image.width() >= rear.width() ||
            kotlin.math.abs(rear.centerX() - image.centerX()) > 3 ||
            kotlin.math.abs(rear.centerY() - image.centerY()) > 3) return null
        // The clickable face owns the clipped shadow, color and border. Its
        // vector is the only visual child. Preserve native layout and placement.
        val path = generateSequence(abi.parentOf(icon), abi::parentOf).takeWhile { it !== box }.toList()
        if (path.lastOrNull() !== face || branch.size != path.size + 1) return null
        val decorations = path.flatMap { layout ->
            val modifiers = Chain.read(layout) ?: return null
            modifiers.mapNotNull { node -> drawTypes.get(node.javaClass)?.let { node to it } }
        }
        return ToolbarItem(backdrop, abi.draw, icon, decorations) {
            abi.invalidateLayers.invoke(null, box)
        }
    }

    fun material(
        click: Any, root: Any, bounds: Rect, role: AppleMusicArtistGlass.Role, density: Float,
        roomBack: Boolean = false,
    ): Material? {
        val outset = if (role.top) platter(click, root) ?: click else click
        var branch = outset
        repeat(if (roomBack) 3 else if (role.top) 1 else 4) {
            if (branch === root) return null
            val candidates = localLayouts(branch, if (role == AppleMusicArtistGlass.Role.CARD) 6
                else if (role.fixed) 3 else 2, limit = 64).mapNotNull { layout ->
                if (roomBack) {
                    val rect = Chain.bounds(layout) ?: return@mapNotNull null
                    if (kotlin.math.abs(rect.centerX() - bounds.exactCenterX()) > 2 * density ||
                        kotlin.math.abs(rect.centerY() - bounds.exactCenterY()) > 2 * density ||
                        rect.width() !in (bounds.width() - 16 * density)..(bounds.width() + 3 * density) ||
                        kotlin.math.abs(rect.width() - rect.height()) > 2 * density
                    ) return@mapNotNull null
                } else if (role.albumAction) {
                    val rect = Chain.bounds(layout) ?: return@mapNotNull null
                    if (kotlin.math.abs(rect.centerX() - bounds.exactCenterX()) > 2 * density ||
                        kotlin.math.abs(rect.centerY() - bounds.exactCenterY()) > 3 * density ||
                        kotlin.math.abs(rect.width() - bounds.width()) > 13 * density ||
                        kotlin.math.abs(rect.height() - bounds.height()) > 13 * density
                    ) return@mapNotNull null
                } else if (role == AppleMusicArtistGlass.Role.CARD) {
                    val rect = Chain.bounds(layout) ?: return@mapNotNull null
                    if (kotlin.math.abs(rect.centerX() - bounds.exactCenterX()) > 2 * density ||
                        kotlin.math.abs(rect.centerY() - bounds.exactCenterY()) > 12 * density ||
                        rect.width() !in (bounds.width() - 60 * density)..(bounds.width() + 3 * density) ||
                        rect.height() !in (bounds.height() - 28 * density)..(bounds.height() + 3 * density)
                    ) return@mapNotNull null
                }
                val modifiers = Chain.read(layout) ?: return@mapNotNull null
                val haze = modifiers.mapNotNull { node ->
                    val kind = hazeTypes.get(node.javaClass) ?: return@mapNotNull null
                    val style = kind.styles.firstNotNullOfOrNull { it.get(node) } ?: return@mapNotNull null
                    if (!style.toString().startsWith("HazeStyle(backgroundColor=")) return@mapNotNull null
                    node to kind
                }.singleOrNull()
                haze?.let { layout to it }
            }
            val candidate = candidates.singleOrNull()
            if (candidate != null) {
                val (layout, pair) = candidate
                val (haze, kind) = pair
                val placement = if (role.top) placement(outset) else null
                val frameClip = if (role == AppleMusicArtistGlass.Role.CARD)
                    clip(layout, layout, 24f, 24f, density, ancestors = true) else null
                val artwork = if (role == AppleMusicArtistGlass.Role.CARD) artwork(layout, density) else null
                return Material(haze, kind.draw, { kind.color(haze) }, placement,
                    invalidate = Chain.invalidator(layout, haze, abi.parentOf(layout)) ?: return null, artwork = artwork,
                    frameClip = frameClip,
                    solid = false, sources = { kind.sources(haze) }, bounds = Chain.boundsReader(layout) ?: return null,
                    cachedEffect = { kind.cachedEffect(haze) }, covered = borders(layout),
                    drawBounds = if (role.top) Chain.modifierBoundsReader(layout, haze) else null)
            }
            if (candidates.size > 1) return null
            branch = abi.parentOf(branch) ?: return null
            if (bounds.isEmpty) return null
        }
        return null
    }

    fun platter(click: Any, root: Any): Any? {
        var child = click
        repeat(4) {
            val parent = abi.parentOf(child)?.takeUnless { it === root } ?: return null
            val children = abi.children(parent, matchOrdering = false) ?: return null
            val sibling = children.singleOrNull { it !== child }
            if (children.size == 2 && sibling != null &&
                abi.children(sibling, matchOrdering = false)?.isEmpty() == true
            ) return parent
            if (children.singleOrNull() !== child) return null
            child = parent
        }
        return null
    }

    /**
     * Toolbar background is a Compose solid-color BackgroundNode. Its draw
     * method paints a shape, then calls drawContent for the icon and input.
     */
    data class ToolbarBackground(val node: Any, val draw: Draw, val invalidate: () -> Unit)

    fun toolbarBackground(click: Any, root: Any, bounds: Rect, density: Float): ToolbarBackground? {
        val branch = platter(click, root) ?: click
        return localLayouts(branch, 3, limit = 32).flatMap { layout ->
            val rect = Chain.bounds(layout) ?: return@flatMap emptyList()
            if (kotlin.math.abs(rect.centerX() - bounds.exactCenterX()) > 2 * density ||
                kotlin.math.abs(rect.centerY() - bounds.exactCenterY()) > 2 * density ||
                rect.width() !in (bounds.width() - 13 * density)..(bounds.width() + 24 * density) ||
                kotlin.math.abs(rect.width() - rect.height()) > density
            ) return@flatMap emptyList()
            Chain.read(layout).orEmpty().mapNotNull { node ->
                val color = toolbarBackgroundPackedFields.get(node.javaClass).map { it.getLong(node) }
                    .singleOrNull { it.toInt() == 0 && (it ushr 56) in 1L..254L }
                if (color == null) return@mapNotNull null
                val draw = drawTypes.get(node.javaClass) ?: return@mapNotNull null
                val invalidate = Chain.invalidator(layout, node, abi.parentOf(layout)) ?: return@mapNotNull null
                ToolbarBackground(node, draw, invalidate)
            }
        }.singleOrNull()
    }

    fun solid(click: Any, root: Any, bounds: Rect, density: Float,
        role: AppleMusicArtistGlass.Role,
        exactLayout: Boolean = false, toolbarFace: Boolean = false): Material? {
        val card = role == AppleMusicArtistGlass.Role.CARD
        val horizontalInset = when (role) {
            AppleMusicArtistGlass.Role.CARD -> 60
            AppleMusicArtistGlass.Role.CARD_ACTION -> 24
            else -> 13
        } * density
        val branch = generateSequence(click, abi::parentOf).take(4).takeWhile { it !== root }.toList()
        val layouts = if (exactLayout) listOf(click)
            else branch.flatMap { localLayouts(it, if (card) 4 else 2) }.distinct()
        val options = layouts.flatMap { layout ->
            val rect = Chain.bounds(layout) ?: return@flatMap emptyList()
            if (kotlin.math.abs(rect.centerX() - bounds.exactCenterX()) > 2 * density ||
                kotlin.math.abs(rect.centerY() - bounds.exactCenterY()) > (if (card) 12 else 3) * density ||
                rect.width() !in (bounds.width() - horizontalInset)..(bounds.width() + 3 * density) ||
                rect.height() !in (bounds.height() - 24 * density)..(bounds.height() + 3 * density)
            ) return@flatMap emptyList()
            Chain.read(layout).orEmpty().mapNotNull { modifier ->
                val draw = drawTypes.get(modifier.javaClass) ?: return@mapNotNull null
                val own = modifier.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
                val block = own.singleOrNull { it.type.isInterface &&
                    it.type.methods.any { method -> method.name == "invoke" && method.parameterCount == 1 }
                }?.apply { isAccessible = true }
                val function = block?.get(modifier)
                val packed = if (function != null && own.size == 1 &&
                    function.javaClass.declaredFields.count { it.type == Int::class.javaPrimitiveType } == 1
                ) packedColor(function) else if ((role == AppleMusicArtistGlass.Role.CARD_ACTION ||
                    role == AppleMusicArtistGlass.Role.PLAY || role.albumAction || toolbarFace) &&
                    toolbarBackgroundPackedFields.get(modifier.javaClass).isNotEmpty()) {
                    packedColor(modifier)
                } else if (own.any { it.type == Float::class.javaPrimitiveType }) {
                    val shape = own.firstOrNull { it.type.isInterface && it.type.methods.any { method ->
                        method.parameterCount == 3 && method.parameterTypes.first() == Long::class.javaPrimitiveType
                    } }?.apply { isAccessible = true }?.get(modifier)
                    if (shape?.toString()?.startsWith("RoundedCornerShape(topStart = ") == true)
                        packedColor(modifier) else null
                } else null
                if (packed == null) return@mapNotNull null
                Material(modifier, draw, { packedColor(block?.get(modifier) ?: modifier) }, null,
                    invalidate = Chain.invalidator(layout, modifier, abi.parentOf(layout)) ?: return@mapNotNull null,
                    artwork = if (card) artwork(layout, density, frameIsCard = true) else null,
                    frameClip = when {
                        card -> clip(layout, layout, 24f, 24f, density, ancestors = true)
                        role == AppleMusicArtistGlass.Role.ALBUM_PLAY -> {
                            val radius = AppleMusicArtistCorners.albumPlayRadius(rect.height()) / density
                            clip(layout, layout, radius, radius, density, albumPlay = true)
                        }
                        else -> null
                    },
                    solid = true, sources = { emptyList() }, bounds = Chain.boundsReader(layout) ?: return@mapNotNull null,
                    cachedEffect = { null }, covered = borders(layout),
                    drawBounds = if (role.top || exactLayout) Chain.modifierBoundsReader(layout, modifier) else null)
            }
        }
        return options.singleOrNull()
    }

    private fun borders(layout: Any): List<Pair<Any, Draw>> = (Chain.read(layout) ?: emptyList()).mapNotNull { node ->
        val own = node.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
        val width = own.singleOrNull { it.type == Float::class.javaPrimitiveType } ?: return@mapNotNull null
        val shape = own.singleOrNull { it.type.isInterface && it.type.declaredMethods.any { method ->
            method.parameterCount == 3 && method.parameterTypes[0] == Long::class.javaPrimitiveType &&
                method.parameterTypes[1].isEnum
        } } ?: return@mapNotNull null
        val brush = own.singleOrNull { it.get(node)?.toString()?.startsWith("SolidColor(value=") == true }
            ?: return@mapNotNull null
        if (node.javaClass.declaredConstructors.none { it.parameterTypes.contentEquals(arrayOf(
                width.type, brush.type, shape.type)) }) return@mapNotNull null
        // Border delegates its cached draw to a separate Modifier.Node. Verify
        // the cache callback captures this exact border before suppressing it.
        own.mapNotNull { field ->
            if (!field.type.isInterface || field === shape) return@mapNotNull null
            val delegate = field.get(node) ?: return@mapNotNull null
            val draw = drawTypes.get(delegate.javaClass) ?: return@mapNotNull null
            val owns = fields.get(delegate.javaClass).filter { it.type.isInterface && it.type.methods.any {
                method -> method.name == "invoke" && method.parameterCount == 1
            } }.mapNotNull { it.get(delegate) }.any { block ->
                fields.get(block.javaClass).any { it.get(block) === node }
            }
            (delegate to draw).takeIf { owns }
        }.singleOrNull()
    }

    private fun packedColor(value: Any): Int? {
        var result: Int? = null
        for (field in packedColorFields.get(value.javaClass)) {
            val packed = field.getLong(value)
            if (packed.toInt() != 0) continue
            if (result != null) return null
            result = (packed ushr 32).toInt()
        }
        return result
    }

    private fun artwork(hazeLayout: Any, density: Float, frameIsCard: Boolean = false): Artwork? {
        val card = if (frameIsCard) hazeLayout else abi.parentOf(hazeLayout) ?: return null
        val frame = Chain.bounds(hazeLayout) ?: return null
        val candidates = localLayouts(card, 7, limit = 64).mapNotNull { layout ->
            val art = Chain.bounds(layout) ?: return@mapNotNull null
            val left = art.left - frame.left
            val upper = art.top - frame.top
            if (art.width() !in 60 * density..120 * density ||
                kotlin.math.abs(art.width() - art.height()) > 2 * density ||
                left !in 8 * density..19 * density || upper !in 8 * density..20 * density ||
                kotlin.math.abs(art.bottom - (frame.bottom - upper)) > 3 * density
            ) return@mapNotNull null
            Triple(layout, art, Chain.bounds(abi.parentOf(layout) ?: return@mapNotNull null)
                ?: return@mapNotNull null)
        }
        // An image may have wrapper layouts with identical bounds. Select the
        // outermost one, provided the complete measured image is unique.
        val art = candidates.filter { candidate ->
            candidates.none { other -> abi.parentOf(candidate.first) === other.first }
        }.singleOrNull() ?: return null
        val (layout, box, parent) = art
        val local = RectF(box)
        local.offset(-parent.left, -parent.top)
        val radius = 24f * density
        val x = radius - (box.left - frame.left)
        val y = radius - (box.top - frame.top)
        return Artwork(layout, abi.draw, local, x, y,
            clip(layout, layout, x / density, y / density, density, outerRadius = radius))
    }

    private fun clip(
        start: Any, measured: Any, rx: Float, ry: Float, density: Float, ancestors: Boolean = false,
        outerRadius: Float = 0f, albumPlay: Boolean = false,
    ): Clip? {
        if (rx <= 0f || ry <= 0f) return null
        val frame = Chain.bounds(measured) ?: return null
        val layouts = if (albumPlay) listOf(start)
            else if (ancestors) generateSequence(start, abi::parentOf).take(4).toList()
            else localLayouts(start, 3)
        val matches = layouts.flatMap { layout ->
            val rect = Chain.bounds(layout)
            if (rect == null || kotlin.math.abs(rect.left - frame.left) > 2 ||
                kotlin.math.abs(rect.top - frame.top) > 2 ||
                kotlin.math.abs(rect.width() - frame.width()) > 2 ||
                kotlin.math.abs(rect.height() - frame.height()) > 2
            ) return@flatMap emptyList()
            (Chain.read(layout) ?: emptyList()).mapNotNull { node ->
                Clip.resolve(layout, node, frame, rx * density, ry * density, outerRadius, albumPlay = albumPlay)
            }
        }
        return matches.singleOrNull()
    }

    internal class Clip private constructor(
        private val modifier: Any, private val field: Field, private val refresh: () -> Unit,
        private val outline: Method, private val factory: java.lang.reflect.Constructor<*>,
        private val pathFactory: java.lang.reflect.Constructor<*>,
        private val width: Float, private val height: Float, private val rx: Float, private val ry: Float,
        private val outerRadius: Float,
        private val flatEdge: Int,
        private val dynamicSize: Boolean,
        private val albumPlay: Boolean,
    ) {
        private var original = field.get(modifier)
        private var cachedSize = 0L
        private var cachedOutline: Any? = null
        private val replacement = Proxy.newProxyInstance(field.type.classLoader, arrayOf(field.type)) { proxy, method, args ->
            when {
                method == outline -> {
                    val size = args[0] as Long
                    val w = Float.fromBits((size ushr 32).toInt())
                    val h = Float.fromBits(size.toInt())
                    if (!dynamicSize && (kotlin.math.abs(w - width) > 3 || kotlin.math.abs(h - height) > 3))
                        method.invoke(original, *args)
                    else {
                        if (cachedSize != size || cachedOutline == null) {
                            val path = if (outerRadius > 0f) AppleMusicArtistCorners.artwork(w, h,
                                outerRadius, outerRadius - rx, outerRadius - ry) else Path().apply {
                                val radius = if (albumPlay) AppleMusicArtistCorners.albumPlayRadius(h) else rx
                                setBottomBarSquircle(RectF(0f, 0f, w, h), radius, -1f)
                                if (flatEdge != 0) {
                                    val flat = Path()
                                    val middle = h / 2f
                                    flat.addRect(0f, if (flatEdge > 0) middle else 0f, w,
                                        if (flatEdge > 0) h else middle, Path.Direction.CW)
                                    op(flat, Path.Op.UNION)
                                }
                            }
                            cachedOutline = factory.newInstance(pathFactory.newInstance(path))
                            cachedSize = size
                        }
                        cachedOutline
                    }
                }
                method.name == "equals" -> proxy === args[0]
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "ArtistContinuousOutline"
                else -> method.invoke(original, *(args ?: emptyArray()))
            }
        }

        fun update(enabled: Boolean): Boolean = runCatching {
            val current = field.get(modifier)
            if (enabled && current !== replacement) {
                original = current
                field.set(modifier, replacement)
                try { refresh() } catch (error: Throwable) {
                    field.set(modifier, original)
                    throw error
                }
            } else if (!enabled && current === replacement) {
                field.set(modifier, original)
                refresh()
            }
            enabled && field.get(modifier) === replacement
        }.getOrDefault(false)

        companion object {
            fun resolve(layout: Any, node: Any, frame: RectF, rx: Float, ry: Float, outerRadius: Float,
                flatEdge: Int = 0, settingsRow: Boolean = false, albumPlay: Boolean = false): Clip? {
                val description = node.toString()
                if (!description.startsWith("SimpleGraphicsLayerModifier(") || ", clip=true," !in description) return null
                val member = fields.get(node.javaClass).singleOrNull { candidate ->
                    candidate.type.isInterface && candidate.type.declaredMethods.any { method ->
                        method.parameterCount == 3 && method.parameterTypes[0] == Long::class.javaPrimitiveType &&
                            method.parameterTypes[1].isEnum && method.returnType.declaredClasses.isNotEmpty()
                    }
                } ?: return null
                if (member.get(node)?.toString()?.startsWith("RoundedCornerShape(") != true) return null
                val outline = member.type.declaredMethods.singleOrNull {
                    it.parameterCount == 3 && it.parameterTypes[0] == Long::class.javaPrimitiveType
                } ?: return null
                val (factory, path) = outline.returnType.declaredClasses.flatMap { it.declaredConstructors.toList() }
                    .mapNotNull { ctor ->
                        val path = ctor.parameterTypes.singleOrNull()?.declaredConstructors?.singleOrNull {
                            it.parameterTypes.contentEquals(arrayOf(Path::class.java))
                        } ?: return@mapNotNull null
                        ctor to path
                    }.singleOrNull() ?: return null
                val block = fields.get(node.javaClass).mapNotNull { it.get(node) }.singleOrNull { value ->
                    value.javaClass.declaredFields.any {
                        it.type == node.javaClass && it.apply { isAccessible = true }.get(value) === node
                    }
                } ?: return null
                val refresh = Chain.refreshClip(layout, node, block)
                if (refresh == null && !settingsRow && !albumPlay) {
                    ArtistDiagnostics.log("clip coordinator rejected: $description")
                    return null
                }
                // Lazy rows can receive their graphics layer after this modifier
                // is prepared. The native callback reads the installed shape.
                val refreshWhenOwned = refresh ?: {
                    Chain.refreshClip(layout, node, block)?.invoke()
                    Unit
                }
                return Clip(node, member, refreshWhenOwned, outline.apply { isAccessible = true },
                    factory.apply { isAccessible = true }, path.apply { isAccessible = true },
                    frame.width(), frame.height(), rx, ry, outerRadius, flatEdge, settingsRow || albumPlay, albumPlay)
            }
        }
    }

    private fun localLayouts(root: Any, depth: Int, limit: Int = 40): List<Any> {
        val pending = ArrayDeque<Pair<Any, Int>>()
        val result = ArrayList<Any>()
        pending.add(root to 0)
        while (pending.isNotEmpty() && result.size < limit) {
            val (layout, level) = pending.removeFirst()
            result.add(layout)
            if (level < depth) {
                abi.children(layout, 12, matchOrdering = false)?.forEach { pending.add(it to level + 1) }
            }
        }
        return if (pending.isEmpty()) result else emptyList()
    }

    fun placement(layout: Any): Placement? {
        val parent = abi.parentOf(layout) ?: return null
        val childPass = measurePass(layout) ?: return null
        val parentPass = measurePass(parent) ?: return null
        val methods = childPass.javaClass.declaredMethods.filter {
            it.returnType == Void.TYPE && it.parameterCount == 3 &&
                it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                it.parameterTypes[1] == Float::class.javaPrimitiveType &&
                it.parameterTypes[2] != Any::class.java && !Modifier.isStatic(it.modifiers)
        }
        if (methods.size != 2 || methods.count { it.parameterTypes[2].isInterface } != 1 ||
            parentPass.javaClass != childPass.javaClass
        ) return null
        val request = parentPass.javaClass.getMethod("requestLayout")
        return Placement(childPass, methods) { request.invoke(parentPass) }
    }

    private fun measurePass(layout: Any): Any? = fields.get(layout.javaClass).mapNotNull { member ->
        val type = member.type
        if (type.declaredFields.none { it.type == layout.javaClass } ||
            type.declaredFields.none { it.type.isEnum && it.type.enumConstants.orEmpty().any { value ->
                (value as Enum<*>).name == "LayingOut"
            } }
        ) return@mapNotNull null
        val delegate = member.get(layout) ?: return@mapNotNull null
        fields.get(type).mapNotNull { pass ->
            if (pass.type.declaredFields.none { it.type == type } ||
                pass.type.methods.none { it.name == "requestLayout" && it.parameterCount == 0 }
            ) null else pass.get(delegate)
        }.singleOrNull()
    }.singleOrNull()

    internal class DrawAccess private constructor(
        private val path: List<Field>, private val wrapper: Field, private val size: Field,
    ) {
        fun read(scope: Any): Triple<Canvas, Float, Float>? {
            val parameters = path.fold(scope as Any?) { value, field -> value?.let(field::get) } ?: return null
            val value = wrapper.get(parameters) ?: return null
            val native = fields.get(value.javaClass).singleOrNull { it.type == Canvas::class.java } ?: return null
            val canvas = native.get(value) as? Canvas ?: return null
            val packed = size.getLong(parameters)
            val width = Float.fromBits((packed ushr 32).toInt())
            val height = Float.fromBits(packed.toInt())
            if (!canvas.isHardwareAccelerated || !width.isFinite() || !height.isFinite() ||
                width <= 0 || height <= 0
            ) return null
            return Triple(canvas, width, height)
        }

        companion object {
            fun resolve(scope: Class<*>): DrawAccess? {
                val canvasScope = fields.get(scope).filter { entry ->
                    entry.type.declaredFields.any { params -> isDrawParameters(params.type) }
                }.singleOrNull() ?: return null
                val parameters = fields.get(canvasScope.type).singleOrNull { isDrawParameters(it.type) } ?: return null
                val holder = fields.get(parameters.type)
                val wrapper = holder.singleOrNull { field ->
                    field.type.isInterface && field.type.methods.any {
                        it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java))
                    }
                } ?: return null
                // The wrapper implementation is supplied by the native draw pass.
                val size = holder.singleOrNull { it.type == Long::class.javaPrimitiveType } ?: return null
                return DrawAccess(listOf(canvasScope, parameters), wrapper, size)
            }

            private fun isDrawParameters(type: Class<*>): Boolean =
                type.declaredFields.any { it.type == Long::class.javaPrimitiveType } &&
                    type.declaredFields.any { it.type.isEnum && it.type.enumConstants.orEmpty().any { value ->
                        (value as Enum<*>).name == "Ltr"
                    } } &&
                    type.declaredFields.any { it.type.isInterface && it.type.methods.any { method ->
                        method.parameterTypes.contentEquals(arrayOf(FloatArray::class.java))
                    } }
        }
    }

    internal object Chain {
        private val heads = object : ClassValue<List<Pair<Field, Field>>>() {
            override fun computeValue(type: Class<*>) = fields.get(type).flatMap { member ->
                if (member.type.declaredFields.count { it.type == type } != 1) emptyList()
                else fields.get(member.type).filter { field ->
                    val base = field.type.superclass
                    base != null && base.declaredFields.count { it.type == base } == 3
                }.map { member to it }
            }
        }
        /** Native invalidateDraw walks outward to the nearest owned layer.
         * Descendant invalidation misses parent recordings; root recursion repaints the page. */
        fun invalidator(layout: Any, node: Any, parent: Any?): (() -> Unit)? {
            if (parent == null) return null
            val member = fields.get(node.javaClass).singleOrNull {
                it.type.declaredFields.count { field -> field.type == layout.javaClass } == 1 &&
                    it.type.declaredFields.count { field -> field.type == it.type } == 2
            } ?: return null
            val start = member.get(node) ?: return null
            val type = member.type
            val owner = fields.get(type).singleOrNull { it.type == layout.javaClass } ?: return null
            val links = fields.get(type).filter { it.type == type }
            val outward = links.singleOrNull { link ->
                var cursor: Any? = start
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
                while (cursor != null && seen.size < 32 && seen.add(cursor)) {
                    if (owner.get(cursor) !== layout) return@singleOrNull owner.get(cursor) === parent
                    cursor = link.get(cursor)
                }
                false
            } ?: return null
            val layer = fields.get(type).singleOrNull { field ->
                field.type.isInterface && field.type.methods.any {
                    it.name == "invalidate" && it.parameterCount == 0 && it.returnType == Void.TYPE
                } && field.type.methods.any { it.name == "destroy" && it.parameterCount == 0 } &&
                    field.type.methods.any { it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java)) }
            } ?: return null
            val invalidate = layer.type.getMethod("invalidate").apply { isAccessible = true }
            // Read live layer holders, but never scan fields or a subtree per frame.
            return action@{
                var cursor: Any? = member.get(node)
                repeat(64) {
                    val current = cursor ?: return@action
                    layer.get(current)?.let { invalidate.invoke(it); return@action }
                    cursor = outward.get(current)
                }
            }
        }

        private fun find(layout: Any): Pair<Any, Any>? =
            heads.get(layout.javaClass).mapNotNull { (member, headField) ->
                val value = member.get(layout) ?: return@mapNotNull null
                val head = headField.get(value) ?: return@mapNotNull null
                if (head.toString() != "<Head>") return@mapNotNull null
                value to head
            }.singleOrNull()

        fun refreshClip(layout: Any, node: Any, block: Any): (() -> Unit)? {
            val coordinator = fields.get(node.javaClass).singleOrNull { member ->
                member.type.declaredFields.count { it.type == layout.javaClass } == 1 &&
                    member.type.declaredFields.count { it.type == member.type } == 2
            } ?: return null
            val start = coordinator.get(node) ?: return null
            val type = coordinator.type
            val owner = fields.get(type).singleOrNull { it.type == layout.javaClass } ?: return null
            val refresh = type.declaredMethods.singleOrNull { method ->
                method.returnType == Void.TYPE && method.parameterCount == 2 &&
                    method.parameterTypes[0].isInstance(block) &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes[0].isInterface
            }?.apply { isAccessible = true } ?: return null
            // NodeCoordinator owns the layer callback. Its Placeable ancestor
            // also has a Function1 field for measurement; it is not a layer block.
            val blockField = refresh.declaringClass.declaredFields.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.type == refresh.parameterTypes[0]
            }?.apply { isAccessible = true } ?: return null
            val links = fields.get(type).filter { it.type == type }
            val pending = ArrayDeque<Any>().apply { add(start) }
            val peers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            while (pending.isNotEmpty() && peers.size < 16) {
                val next = pending.removeFirst()
                if (!peers.add(next) || owner.get(next) !== layout) continue
                links.mapNotNull { it.get(next) }.filterNot { it in peers }.forEach(pending::add)
            }
            if (pending.isNotEmpty()) return null
            val target = peers.singleOrNull {
                owner.get(it) === layout && blockField.get(it) === block
            } ?: return null
            return {
                if (blockField.get(target) === block) refresh.invoke(target, block, true)
            }
        }

        fun bounds(layout: Any): RectF? = boundsReader(layout)?.invoke()

        fun boundsReader(layout: Any): (() -> RectF?)? {
            val (chain, _) = find(layout) ?: return null
            val coordinate = fields.get(chain.javaClass).singleOrNull { member ->
                member.type.declaredFields.any { it.type == layout.javaClass } &&
                    member.type.interfaces.any { role ->
                        role.declaredMethods.any { it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java)) }
                    }
            }?.get(chain) ?: return null
            return coordinateBoundsReader(coordinate) { coordinate }
        }

        fun modifierBoundsReader(layout: Any, modifier: Any): (() -> RectF?)? {
            val field = fields.get(modifier.javaClass).singleOrNull { member ->
                member.type.declaredFields.count { it.type == layout.javaClass } == 1 &&
                    member.type.declaredFields.count { it.type == member.type } == 2
            } ?: return null
            val coordinator = field.get(modifier) ?: return null
            val owner = fields.get(field.type).singleOrNull { it.type == layout.javaClass } ?: return null
            return coordinateBoundsReader(coordinator) {
                field.get(modifier)?.takeIf { owner.get(it) === layout }
            }
        }

        private fun coordinateBoundsReader(initial: Any, current: () -> Any?): (() -> RectF?)? {
            val access = generateSequence(initial.javaClass) { it.superclass }.flatMap { it.interfaces.asSequence() }
                .distinct().mapNotNull(CoordinateAccess::resolve).singleOrNull() ?: return null
            val matrix = FloatArray(16)
            val rect = RectF()
            return read@{
                val coordinate = current() ?: return@read null
                matrix.fill(0f)
                matrix[0] = 1f; matrix[5] = 1f; matrix[10] = 1f; matrix[15] = 1f
                val size = access.read(coordinate, matrix) ?: return@read null
                val width = (size ushr 32).toInt()
                val height = size.toInt()
                if (kotlin.math.abs(matrix[0] - 1f) > .03f || kotlin.math.abs(matrix[5] - 1f) > .03f ||
                    kotlin.math.abs(matrix[1]) > .03f || kotlin.math.abs(matrix[4]) > .03f
                ) return@read null
                rect.set(matrix[12], matrix[13], matrix[12] + width, matrix[13] + height)
                rect
            }
        }

        fun read(layout: Any): List<Any>? {
            val (chain, sentinel) = find(layout) ?: return null
            val base = sentinel.javaClass.superclass ?: return null
            val members = fields.get(chain.javaClass)
            val first = members.singleOrNull { it.type == base }?.get(chain) ?: return null
            val tail = members.singleOrNull { field ->
                field.type.superclass == base && field.get(chain)?.toString() == "<tail>"
            }?.get(chain) ?: return null
            if (first === tail) return emptyList()
            // The <Head> sentinel is linked only during modifier diffing. The
            // live head and the permanent tail define the forward chain.
            return fields.get(base).filter { it.type == base }.mapNotNull { link ->
                val nodes = ArrayList<Any>()
                var current: Any? = first
                while (current != null && current !== tail && nodes.size < 64) {
                    if (current === sentinel || nodes.any { it === current }) return@mapNotNull null
                    nodes.add(current)
                    current = link.get(current)
                }
                nodes.takeIf { current === tail }
            }.singleOrNull()
        }
    }

    private data class Haze(
        val styles: List<Field>, val overrideTints: Field?, val styleTints: Field,
        val tintColor: Field, val draw: Draw, val areas: Field?, val renderer: Field?,
    ) {
        fun cachedEffect(node: Any): android.graphics.RenderEffect? {
            val draw = renderer?.get(node) ?: return null
            val fields = fields.get(draw.javaClass)
            if (fields.count { it.get(draw) === node } != 1) return null
            val wrapper = fields.singleOrNull { field ->
                field.type.declaredFields.any { it.type == android.graphics.RenderEffect::class.java }
            }?.get(draw) ?: return null
            return Companion.fields.get(wrapper.javaClass)
                .singleOrNull { it.type == android.graphics.RenderEffect::class.java }
                ?.get(wrapper) as? android.graphics.RenderEffect
        }

        fun color(node: Any): Int? {
            val overrides = overrideTints?.get(node) as? List<*>
            val tint = overrides?.firstOrNull() ?: styles.mapNotNull { field ->
                field.get(node)?.let(styleTints::get) as? List<*>
            }.mapNotNull { it.firstOrNull() }.distinct().singleOrNull() ?: return null
            if (!tintColor.declaringClass.isInstance(tint)) return null
            val packed = tintColor.getLong(tint)
            return if (packed.toInt() == 0) (packed ushr 32).toInt() else null
        }

        fun sources(node: Any): List<Source> {
            val records = areas?.get(node) as? List<*> ?: return emptyList()
            if (records.size > 24) return emptyList()
            return records.mapNotNull { area ->
                area ?: return@mapNotNull null
                // A layer may be absent between recordings. Cache the type
                // contract separately from the changing layer value.
                val type = area.javaClass
                if (!areaAccess.containsKey(type)) areaAccess[type] = AreaAccess.resolve(area)
                areaAccess[type]?.read(area)
            }
        }
    }

    private class AreaAccess(val position: Method, val layer: Method, val recording: Field) {
        fun read(area: Any): Source? {
            if (recording.getBoolean(area)) return null
            val packed = position.invoke(area) as Long
            val x = Float.fromBits((packed ushr 32).toInt())
            val y = Float.fromBits(packed.toInt())
            if (!x.isFinite() || !y.isFinite()) return null
            val wrapper = layer.invoke(area) ?: return null
            val native = holders.get(wrapper.javaClass)?.get(wrapper) ?: return null
            val node = renderNodes.get(native.javaClass)?.get(native) as? RenderNode ?: return null
            return if (node.hasDisplayList()) Source(node, x, y) else null
        }
        companion object {
            private val holders = object : ClassValue<Field?>() {
                override fun computeValue(type: Class<*>) = fields.get(type).singleOrNull { field ->
                    field.type.declaredFields.any { it.type == RenderNode::class.java }
                }
            }
            private val renderNodes = object : ClassValue<Field?>() {
                override fun computeValue(type: Class<*>) = fields.get(type).singleOrNull {
                    it.type == RenderNode::class.java
                }
            }
            fun resolve(area: Any): AreaAccess? {
                if (!area.toString().startsWith("HazeArea(positionOnScreen=")) return null
                val position = area.javaClass.declaredMethods.singleOrNull {
                    it.returnType == Long::class.javaPrimitiveType && it.parameterCount == 0
                }?.apply { isAccessible = true } ?: return null
                val layer = area.javaClass.declaredMethods.singleOrNull { method ->
                    method.parameterCount == 0 && method.returnType.declaredFields.any { outer ->
                        outer.type.declaredFields.any { it.type == RenderNode::class.java }
                    }
                }?.apply { isAccessible = true } ?: return null
                val recording = fields.get(area.javaClass).singleOrNull { it.type == Boolean::class.javaPrimitiveType }
                    ?: return null
                return AreaAccess(position, layer, recording)
            }
        }
    }

    companion object {
        private val packedColorFields = object : ClassValue<List<Field>>() {
            override fun computeValue(type: Class<*>): List<Field> = type.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) && it.type == Long::class.javaPrimitiveType
            }.onEach { it.isAccessible = true }
        }
        private val areaAccess = HashMap<Class<*>, AreaAccess?>()
        private val fields = object : ClassValue<List<Field>>() {
            override fun computeValue(type: Class<*>): List<Field> =
                generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
                    .flatMap { it.declaredFields.asSequence() }.filterNot { Modifier.isStatic(it.modifiers) }
                    .onEach { it.isAccessible = true }.toList()
        }
        private val toolbarBackgroundPackedFields = object : ClassValue<List<Field>>() {
            override fun computeValue(type: Class<*>): List<Field> {
                if (drawTypes.get(type) == null) return emptyList()
                val own = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
                    .onEach { it.isAccessible = true }
                val hasShape = own.groupBy(Field::getType).entries.count { (contract, members) ->
                    contract.isInterface && members.size == 2 && contract.declaredMethods.any {
                        it.parameterCount == 3 && it.parameterTypes.first() == Long::class.javaPrimitiveType &&
                            it.parameterTypes[1].isEnum
                    }
                } == 1
                // The background keeps the current and cached shape, a color
                // and a cached packed size. Color and brush share one alpha.
                if (!hasShape || own.count { it.type == Float::class.javaPrimitiveType } != 1 ||
                    own.count { it.type == Long::class.javaPrimitiveType } != 2) return emptyList()
                return own.filter { it.type == Long::class.javaPrimitiveType }
            }
        }

        private val drawTypes = object : ClassValue<Draw?>() {
            override fun computeValue(type: Class<*>): Draw? = type.declaredMethods.mapNotNull { method ->
                if (method.returnType != Void.TYPE || method.parameterCount != 1) return@mapNotNull null
                val scope = method.parameterTypes.single()
                val access = DrawAccess.resolve(scope) ?: return@mapNotNull null
                val content = scope.interfaces.flatMap { it.declaredMethods.toList() }.singleOrNull {
                    it.parameterCount == 0 && it.returnType == Void.TYPE && Modifier.isAbstract(it.modifiers)
                } ?: return@mapNotNull null
                Draw(method.apply { isAccessible = true },
                    scope.getMethod(content.name).apply { isAccessible = true }, access)
            }.singleOrNull()
        }
        private val hazeTypes = object : ClassValue<Haze?>() {
            override fun computeValue(type: Class<*>): Haze? {
                val styles = type.declaredFields.groupBy(Field::getType).values.singleOrNull { group ->
                    group.size == 2 && group[0].type.declaredConstructors.any { ctor ->
                        val args = ctor.parameterTypes
                        args.size == 5 && args[0] == Long::class.javaPrimitiveType &&
                            args[1] == List::class.java && args[2] == Float::class.javaPrimitiveType &&
                            args[3] == Float::class.javaPrimitiveType
                    }
                }?.onEach { it.isAccessible = true } ?: return null
                val tintType = styles[0].type.declaredConstructors.singleOrNull {
                    it.parameterTypes.getOrNull(1) == List::class.java && it.parameterCount == 5
                }?.parameterTypes?.last() ?: return null
                val tint = fields.get(tintType).singleOrNull { it.type == Long::class.javaPrimitiveType } ?: return null
                val styleTints = fields.get(styles[0].type).singleOrNull { it.type == List::class.java } ?: return null
                val overrideTints = fields.get(type).singleOrNull {
                    it.type == List::class.java && (it.genericType as? java.lang.reflect.ParameterizedType)
                        ?.actualTypeArguments?.singleOrNull() == tintType
                }
                val areas = fields.get(type).singleOrNull { field ->
                    if (field.type != List::class.java) return@singleOrNull false
                    val item = (field.genericType as? java.lang.reflect.ParameterizedType)
                        ?.actualTypeArguments?.singleOrNull() as? Class<*> ?: return@singleOrNull false
                    item.declaredFields.any { it.type == WindowId::class.java }
                }
                val draw = drawTypes.get(type) ?: return null
                val renderer = fields.get(type).singleOrNull { field ->
                    field.type.isInterface && field.type.declaredMethods.any { method ->
                        method.returnType == Void.TYPE && method.parameterCount == 1 &&
                            method.parameterTypes[0].isAssignableFrom(draw.method.parameterTypes[0])
                    } && field.type.declaredMethods.any { it.returnType == Void.TYPE && it.parameterCount == 0 }
                }
                return Haze(styles, overrideTints, styleTints, tint, draw, areas, renderer)
            }
        }
    }
}
