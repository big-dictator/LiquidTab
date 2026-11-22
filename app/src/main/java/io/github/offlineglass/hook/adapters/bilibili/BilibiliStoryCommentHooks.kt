package io.github.offlineglass.hook.adapters.bilibili


import android.animation.ValueAnimator
import android.app.Dialog
import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.*
import android.widget.*
import io.github.offlineglass.hook.compat.*
import io.github.offlineglass.hook.*
import io.github.offlineglass.rendering.*
import java.util.WeakHashMap
import kotlin.math.*

/** Story comments own a separate dialog window; never change the video activity window. */
internal object BilibiliStoryCommentHooks {
    private val dialogs = WeakHashMap<View, Boolean>()
    private val owners = WeakHashMap<View, Owner>()
    private val replyOwners = WeakHashMap<View, Owner>()
    private val replyHosts = WeakHashMap<View, java.lang.ref.WeakReference<View>>()
    private val nativeActivities = WeakHashMap<View, Boolean>()
    private const val DETAIL_PAGE = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    private const val PLAYLIST_PAGE = "com.bilibili.ship.theseus.playlist.UnitedPlaylistActivity"
    private var packageName = "tv.danmaku.bili"
    private var nativeMainLayout: ((View, ViewGroup) -> Unit)? = null

    fun install(loader: ClassLoader, targetPackage: String = "tv.danmaku.bili", nativeActivityComments: Boolean = false,
        nativeCommentLayout: ((View, ViewGroup) -> Unit)? = null) {
        packageName = targetPackage
        nativeMainLayout = nativeCommentLayout
        if (nativeActivityComments) installNativeActivityComments()
        val fragment = runCatching { loader.loadClass("com.bilibili.video.story.tab.StoryTabDialogFragment") }.getOrNull() ?: return
        BilibiliStoryCommentViewport.install(loader)
        XposedBridge.hookAllMethods(fragment, "onStart", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dialog = XposedHelpers.callMethod(param.thisObject, "getDialog") as? Dialog ?: return
                val window = dialog.window ?: return
                val decor = window.decorView
                if (dialogs.put(decor, true) == null) {
                    decor.viewTreeObserver.addOnGlobalLayoutListener { runCatching { adapt(dialog) }.onFailure { XposedBridge.log("LiquidTab Story comments: $it") } }
                }
                adapt(dialog)
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "setSystemUiVisibility",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (dialogs.containsKey(param.thisObject)) param.args[0] = (param.args[0] as Int) or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                }
            })
        XposedBridge.hookAllMethods(Dialog::class.java, "dispatchTouchEvent",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val decor = (param.thisObject as Dialog).window?.decorView ?: return
                    (replyOwners[decor]?.takeIf { it.bar.isShown && it.bar.isAttachedToWindow } ?: owners[decor])
                        ?.touch(param.args[0] as MotionEvent)
                }
            })
        XposedBridge.hookAllMethods(Dialog::class.java, "dismiss", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val decor = (param.thisObject as Dialog).window?.decorView ?: return
                BilibiliStoryCommentViewport.release(decor)
                owners.remove(decor)?.dispose()
                replyOwners.remove(decor)?.dispose()
                replyHosts.remove(decor)
            }
        })
    }

    private fun marginReference(decor: View): View {
        var context = decor.context
        while (context is ContextWrapper) {
            if (context is Activity) return context.window.decorView
            val base = context.baseContext
            if (base === context) break
            context = base
        }
        return decor
    }

    private fun adapt(dialog: Dialog) {
        if (!dialog.isShowing) return
        val window = dialog.window ?: return
        val decor = window.decorView
        window.navigationBarColor = Color.TRANSPARENT
        window.navigationBarDividerColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        val flags = decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (decor.systemUiVisibility != flags) decor.systemUiVisibility = flags
        // HyperOS otherwise reserves a separate gesture strip even for edge-to-edge dialogs.
        runCatching {
            val stub = XposedHelpers.callMethod(decor, "getDecorViewImmersiveStub") ?: return@runCatching
            if (XposedHelpers.callMethod(stub, "isForceImmersive") != true) {
                XposedHelpers.callMethod(stub, "setForceImmersiveNavBar", true)
                XposedHelpers.callMethod(decor, "updateColorViews", null, false)
                decor.requestApplyInsets()
            }
        }
        val content = decor.findViewById<View>(android.R.id.content)
        if (content != null && content.paddingBottom != 0) content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, 0)
        adaptContent(decor)
        adaptNativeReply(decor, "story_secondary_comment_container")
        BilibiliStoryCommentViewport.attach(dialog)
    }

    /** Native player comments reuse the controls and their existing window geometry. */
    private fun installNativeActivityComments() {
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                if (activity.packageName != packageName) return
                val playlist = activity.javaClass.name == PLAYLIST_PAGE
                if (!playlist && activity.javaClass.name != DETAIL_PAGE) return
                val decor = activity.window.decorView
                fun adaptPage() {
                    if (playlist) {
                        val pager = decor.findViewById<ViewGroup>(decor.resources.getIdentifier("pager", "id", packageName))
                        val scope = pager?.findViewById<ViewGroup>(decor.resources.getIdentifier("comment_container", "id", packageName))
                        if (scope == null) {
                            owners.remove(decor)?.dispose()
                        } else {
                            adaptContent(decor, scope, verifyPlaylistStructure = true)
                        }
                        adaptNativeReply(decor, playlist = true)
                    } else {
                        adaptContent(decor)
                        adaptNativeReply(decor)
                    }
                }
                if (nativeActivities.put(decor, true) == null) {
                    decor.viewTreeObserver.addOnGlobalLayoutListener {
                        runCatching { adaptPage() }.onFailure { XposedBridge.log("LiquidTab native comments: $it") }
                    }
                }
                adaptPage()
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val decor = (param.thisObject as Activity).window.decorView
                (replyOwners[decor]?.takeIf { it.bar.isShown && it.bar.isAttachedToWindow } ?: owners[decor])
                    ?.touch(param.args[0] as MotionEvent)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val decor = (param.thisObject as Activity).window.decorView
                owners.remove(decor)?.dispose()
                replyOwners.remove(decor)?.dispose()
                replyHosts.remove(decor)
                nativeActivities.remove(decor)
            }
        })
    }

    private fun adaptNativeReply(decor: View, hostName: String = "fl_tab_pager_container", playlist: Boolean = false) {
        val host = replyHosts[decor]?.get()?.takeIf { it.isAttachedToWindow } ?: decor.findViewById<View>(
            decor.resources.getIdentifier(hostName, "id", packageName))?.also {
                replyHosts[decor] = java.lang.ref.WeakReference(it)
            }
        val scope = if (playlist) {
            val parent = host?.parent as? ViewGroup
            val resources = decor.resources
            val titleId = resources.getIdentifier("cmt3_page_title_container", "id", packageName)
            val listId = resources.getIdentifier("cmt3_swipe_refresh", "id", packageName)
            val barId = resources.getIdentifier("cmt3_fake_input_bar_container", "id", packageName)
            var candidate: RelativeLayout? = null
            if (parent != null && titleId != 0 && listId != 0 && barId != 0) {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i) as? RelativeLayout ?: continue
                    if (!child.isShown || !child.isAttachedToWindow) continue
                    val title = child.findViewById<View>(titleId)
                    val list = child.findViewById<View>(listId)
                    val bar = child.findViewById<ViewGroup>(barId)
                    if (title?.parent !== child || list?.parent !== child || bar?.parent !== child) continue
                    if (candidate != null) {
                        candidate = null
                        break
                    }
                    candidate = child
                }
            }
            candidate
        } else (host as? ViewGroup)?.let { group ->
            (group.childCount - 1 downTo 0).firstNotNullOfOrNull { group.getChildAt(it).takeIf { child -> child.isShown } }
        }
        if (scope == null) { replyOwners.remove(decor)?.dispose(); return }
        adaptContent(decor, scope, replyOwners, "cmt3_swipe_refresh", verifyPlaylistStructure = playlist)
    }

    private fun adaptContent(decor: View, scope: View = decor,
        bindings: WeakHashMap<View, Owner> = owners, mainName: String = "comment_main",
        verifyPlaylistStructure: Boolean = false) {
        bindings[decor]?.let { existing ->
            var ancestor: View? = existing.bar
            while (ancestor != null && ancestor !== scope) ancestor = ancestor.parent as? View
            if (ancestor === scope && existing.bar.isAttachedToWindow &&
                (!verifyPlaylistStructure || existing.matchesPlaylistStructure())
            ) { existing.geometry(decor); existing.colors(); return }
            bindings.remove(decor)?.dispose()
        }
        fun find(name: String): View? = scope.findViewById(scope.resources.getIdentifier(name, "id", packageName))
        val bar = find("cmt3_fake_input_bar_container") as? ViewGroup ?: return
        val row = find("fake_input_layout") as? LinearLayout ?: return
        val input = row.findViewById<EditText>(decor.resources.getIdentifier("input", "id", packageName)) ?: return
        val emoji = row.findViewById<ImageView>(decor.resources.getIdentifier("emotion", "id", packageName)) ?: return
        val main = find(mainName) ?: return
        val source = if (verifyPlaylistStructure) {
            if (nativeMainLayout == null || main.parent !== bar.parent ||
                input.parent !== row || emoji.parent !== row || !emoji.isClickable
            ) return
            main.findViewById<ViewGroup>(decor.resources.getIdentifier("cmt3_recycler", "id", packageName))
        } else find("cmt3_recycler")
        if (source == null) return
        val density = decor.resources.displayMetrics.density
        val reference = marginReference(decor)
        val gap = glassHostBottomMarginPx(reference)
        val side = symmetricGlassSideMarginPx(reference, density)
        val height = (44f * density).roundToInt()
        // Keep the fragment's hierarchy and native listeners intact. Remove its bottom constraint
        // to the input so the list can continue underneath the floating buttons.
        if (nativeMainLayout == null && main.layoutParams !is RelativeLayout.LayoutParams) runCatching {
            val lp = main.layoutParams
            lp.javaClass.getField("bottomToTop").setInt(lp, -1)
            lp.javaClass.getField("bottomToBottom").setInt(lp, 0)
            main.layoutParams = lp
        }
        if (main.layoutParams is FrameLayout.LayoutParams) {
            main.layoutParams = (main.layoutParams as FrameLayout.LayoutParams).apply {
                this.height = ViewGroup.LayoutParams.MATCH_PARENT
                bottomMargin = 0
            }
        }
        fun clear(v: View) {
            v.background = null
            if (v is ViewGroup) {
                v.clipChildren = false; v.clipToPadding = false
                for (i in 0 until v.childCount) clear(v.getChildAt(i))
            }
        }
        clear(bar)
        bar.layoutParams = bar.layoutParams.apply {
            this.height = height + gap
            if (this is FrameLayout.LayoutParams) gravity = Gravity.BOTTOM
        }
        if (nativeMainLayout == null && bar.layoutParams !is RelativeLayout.LayoutParams) runCatching {
            val lp = bar.layoutParams
            lp.javaClass.getField("topToBottom").setInt(lp, -1)
            lp.javaClass.getField("topToTop").setInt(lp, -1)
            lp.javaClass.getField("bottomToTop").setInt(lp, -1)
            lp.javaClass.getField("bottomToBottom").setInt(lp, 0)
            bar.layoutParams = lp
        }
        var wrapper = row.parent as? View
        while (wrapper != null && wrapper !== bar) {
            wrapper.layoutParams = wrapper.layoutParams.apply { this.height = ViewGroup.LayoutParams.MATCH_PARENT }
            wrapper = wrapper.parent as? View
        }
        row.layoutParams = row.layoutParams.apply { this.height = height + gap }
        row.gravity = Gravity.BOTTOM
        row.setPadding(side, 0, side, gap)
        input.layoutParams = LinearLayout.LayoutParams(0, height, 1f).apply { rightMargin = (8f * density).roundToInt() }
        input.setPadding((16f * density).roundToInt(), 0, (12f * density).roundToInt(), 0)
        emoji.layoutParams = LinearLayout.LayoutParams(height, height)
        emoji.setPadding((10f * density).roundToInt(), (10f * density).roundToInt(), (10f * density).roundToInt(), (10f * density).roundToInt())
        emoji.scaleType = ImageView.ScaleType.FIT_CENTER
        nativeMainLayout?.invoke(main, bar)
        // Refresh can move the list from its parent draw pass. Sampling the
        // recycler alone bypasses that pass. Use only the refresh branch that
        // owns this recycler and is separate from the floating input.
        val refresh = find("cmt3_swipe_refresh") as? ViewGroup
        val scene = refresh?.takeIf {
            source.parent === it && !isWithin(bar, it)
        } ?: source
        val owner = Owner(source, scene, input, emoji, bar, row, main)
        bindings[decor] = owner
        input.background = owner.textGlass
        emoji.background = owner.emojiGlass
        owner.colors()
        owner.geometry(decor)
        // The GPU references the list's live node; no recursive capture or permanent frame loop.
    }

    private fun isWithin(view: View, ancestor: View): Boolean =
        generateSequence(view) { it.parent as? View }.any { it === ancestor }

    private class Owner(
        val source: View, scene: View, val input: EditText, val emoji: ImageView,
        val bar: ViewGroup, val row: LinearLayout, val main: View,
    ) {
        fun matchesPlaylistStructure(): Boolean =
            source.isAttachedToWindow && main.isAttachedToWindow && main.parent === bar.parent &&
                input.parent === row && emoji.parent === row && row.isAttachedToWindow

        private val originalListBottomPadding = source.paddingBottom
        private var appliedListBottomPadding: Int? = null
        private val listLocation = IntArray(2)
        private val barLocation = IntArray(2)

        private fun updateListSafePadding() {
            if (!source.isAttachedToWindow || source.height <= 0 ||
                !bar.isShown || input.height <= 0 || emoji.height <= 0) return
            source.getLocationInWindow(listLocation)
            // Use the resting container edge, not the buttons' animated scale:
            // press/rebound must not relayout the recycler on every frame.
            bar.getLocationInWindow(barLocation)
            val controlsTop = barLocation[1]
            val overlap = (listLocation[1] + source.height - controlsTop).coerceIn(0, source.height)
            val breathingRoom = (8f * source.resources.displayMetrics.density).roundToInt()
            val required = maxOf(originalListBottomPadding, overlap + breathingRoom)
            if (source.paddingBottom != required) {
                source.setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, required)
            }
            appliedListBottomPadding = required
        }

        val textGlass = Glass(input, scene, source, false)
        val emojiGlass = Glass(emoji, scene, source, true)
        private val shadowHost = bar.rootView as ViewGroup
        private val shadows = object : View(source.context) {
            init { setWillNotDraw(false); isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            override fun onDraw(canvas: Canvas) {
                val saved = canvas.save()
                textGlass.excludeInterior(canvas, shadowHost)
                emojiGlass.excludeInterior(canvas, shadowHost)
                textGlass.drawAmbient(canvas, shadowHost)
                emojiGlass.drawAmbient(canvas, shadowHost)
                canvas.restoreToCount(saved)
            }
        }
        private var geometrySignature = ""
        private val refreshAmbient = ViewTreeObserver.OnPreDrawListener {
            reconcileChrome()
            updateListSafePadding()
            if (shadows.width != shadowHost.width || shadows.height != shadowHost.height) {
                shadows.layout(0, 0, shadowHost.width, shadowHost.height)
                shadows.invalidate()
            }
            val signature = textGlass.geometryKey() + emojiGlass.geometryKey()
            if (geometrySignature != signature) {
                geometrySignature = signature
                textGlass.invalidateSelf()
                emojiGlass.invalidateSelf()
                shadows.invalidate()
            }
            true
        }
        init {
            shadowHost.overlay.add(shadows)
            shadowHost.viewTreeObserver.addOnPreDrawListener(refreshAmbient)
            textGlass.invalidateAmbient = { shadows.invalidate() }
            emojiGlass.invalidateAmbient = { shadows.invalidate() }
        }
        fun dispose() {
            if (appliedListBottomPadding != null && source.paddingBottom == appliedListBottomPadding) {
                source.setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, originalListBottomPadding)
            }
            if (shadowHost.viewTreeObserver.isAlive) shadowHost.viewTreeObserver.removeOnPreDrawListener(refreshAmbient)
            shadowHost.overlay.remove(shadows); textGlass.stop(); emojiGlass.stop()
        }
        fun geometry(decor: View) {
            reconcileChrome()
            val reference = marginReference(decor)
            val gap = glassHostBottomMarginPx(reference)
            val side = symmetricGlassSideMarginPx(reference, decor.resources.displayMetrics.density)
            val height = (44f * decor.resources.displayMetrics.density).roundToInt() + gap
            if (bar.layoutParams.height != height) bar.layoutParams = bar.layoutParams.apply { this.height = height }
            if (row.layoutParams.height != height) row.layoutParams = row.layoutParams.apply { this.height = height }
            if (row.paddingBottom != gap || row.paddingLeft != side) row.setPadding(side, 0, side, gap)
            if (shadows.width != shadowHost.width || shadows.height != shadowHost.height) {
                shadows.layout(0, 0, shadowHost.width, shadowHost.height)
            }
            shadows.invalidate()
        }
        /** Host theme/layout updates may restore chrome after our first installation. */
        private fun reconcileChrome() {
            nativeMainLayout?.invoke(main, bar)
            // Story replies use Android's public BELOW/ABOVE rules. Keep the
            // native title anchor and scroll owner; extend only below the input.
            if (nativeMainLayout == null && main.parent === bar.parent) {
                (main.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                    if (params.getRule(RelativeLayout.ABOVE) != 0 ||
                        params.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != RelativeLayout.TRUE) {
                        params.removeRule(RelativeLayout.ABOVE)
                        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
                        main.layoutParams = params
                    }
                }
            }
            fun clearWrapper(view: View) {
                if (view === input || view === emoji) return
                if (view.background != null) view.background = null
                if (view.foreground != null) view.foreground = null
                if (view.clipBounds != null) view.clipBounds = null
                if (view is ViewGroup) {
                    view.clipChildren = false
                    view.clipToPadding = false
                    for (index in 0 until view.childCount) clearWrapper(view.getChildAt(index))
                }
            }
            clearWrapper(bar)
            // Allow content beneath the floating input, but retain the list's top/side
            // viewport. Disabling all ancestor clipping lets recycled rows escape the sheet.
            var ancestor: View? = source
            val container = bar.parent
            while (ancestor != null) {
                if (ancestor !== source && ancestor.clipBounds != null) ancestor.clipBounds = null
                if (ancestor is ViewGroup) {
                    ancestor.clipChildren = true
                    ancestor.clipToPadding = false
                }
                if (ancestor === container) break
                ancestor = ancestor.parent as? View
            }
            val viewport = Rect(0, source.paddingTop.coerceAtMost(source.height), source.width, source.height)
            if (source.clipBounds != viewport) source.clipBounds = viewport
            if (input.background !== textGlass) input.background = textGlass
            if (emoji.background !== emojiGlass) emoji.background = emojiGlass
        }
        fun colors() {
            val color = if (source.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) Color.WHITE else Color.BLACK
            input.setTextColor(color); input.setHintTextColor(color)
            emoji.imageTintList = android.content.res.ColorStateList.valueOf(color)
        }
        fun touch(event: MotionEvent) { textGlass.touch(event); emojiGlass.touch(event); shadows.invalidate() }
    }

    private class Glass(val host: View, val scene: View, val source: View, val circular: Boolean) : Drawable() {
        private val node = RenderNode("BiliStoryCommentGlass")
        private val ambient = RenderNode("BiliStoryCommentAmbient")
        private val lens = io.github.offlineglass.rendering.PostureGlass.shader(KYANT_LENS_SHADER)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val glassEdge = io.github.offlineglass.rendering.NativeGlassEdge()
        private val outsideClip = Path()
        private val outsideMatrix = Matrix()
        private val rect = RectF()
        private val location = IntArray(2)
        private val sampleMatrix = Matrix()
        private var signature = ""
        private var down = false
        private var press = 0f
        private var glow = 0f
        private var spread = 0f
        private var x = .5f
        private var y = .5f
        private var animator: ValueAnimator? = null
        var invalidateAmbient: () -> Unit = {}
        fun stop() { animator?.cancel() }
        private val sourceField = View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val config = HookConfigReader.read(host.context, packageName)
            val d = host.resources.displayMetrics.density
            val dark = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val w = bounds.width(); val h = bounds.height(); val radius = min(w, h) / 2f
            rect.set(bounds)
            if (circular) { path.reset(); path.addOval(rect, Path.Direction.CW) }
            else path.setBottomBarSquircle(rect, radius, config.cornerSmoothing)
            val pad = (26f * d).roundToInt()
            io.github.offlineglass.rendering.PostureGlass.sync(lens, host,
                config.postureEffectsEnabled && config.liquidGlassEnabled && !config.solidBarEnabled, config.liquidViscosity, config.flowHorizontalStrength, config.flowVerticalStrength)
            val key = "$w/$h/$dark/${config.blurRadius}/${config.liquidGlassEnabled}/${config.cornerSmoothing}:${config.refractionStrength}"
            val postureChanged = io.github.offlineglass.rendering.PostureGlass.effectChanged(lens)
            val geometryChanged = signature != key
            if (geometryChanged || postureChanged) {
                lens.setFloatUniform("size", w.toFloat(), h.toFloat())
                lens.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
                lens.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                lens.setFloatUniform("refractionHeight", min(h / 2f, 24f * d))
                lens.setFloatUniform("refractionAmount", -24f * d * config.refractionScale)
                lens.setFloatUniform("depthEffect", 0f)
                val blur = NativeBackdropBlur.effect(config.blurRadius * .8f * d)
                node.setRenderEffect(if (config.liquidGlassEnabled) RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), blur) else blur)
            }
            if (geometryChanged) {
                ambient.setPosition(-pad, -pad, w + pad, h + pad)
                val c = ambient.beginRecording(w + pad * 2, h + pad * 2)
                c.translate(pad.toFloat(), pad.toFloat())
                paint.style = Paint.Style.STROKE; paint.strokeWidth = (if (dark) 2.5f else 3f) * d
                paint.color = if (dark) 0x38FFFFFF else 0x28000000
                c.drawPath(path, paint); ambient.endRecording()
                val r = (if (dark) 6f else 7f) * d
                ambient.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                signature = key
                // Overlay can be cached before the buttons record their first display list.
                // Refresh once when this node becomes available, including after reopening.
                invalidateAmbient()
            }
            val save = canvas.save()
            if (canvas.isHardwareAccelerated) {
                val src = sourceField.get(scene) as RenderNode
                node.setPosition(-pad, -pad, w + pad, h + pad)
                val c = node.beginRecording(w + pad * 2, h + pad * 2)
                c.drawColor(if (dark) 0xFF17171A.toInt() else Color.WHITE)
                // RenderNode already carries the scene's own layout/transform.
                // Map its parent space into the moving button, including sheet
                // translations and press scale, rather than subtracting origins.
                sampleMatrix.reset()
                (scene.parent as? View)?.transformMatrixToGlobal(sampleMatrix)
                host.transformMatrixToLocal(sampleMatrix)
                c.translate(pad.toFloat(), pad.toFloat())
                c.concat(sampleMatrix)
                if (src.hasDisplayList()) c.drawRenderNode(src)
                node.endRecording()
                val clip = canvas.save(); canvas.clipPath(path); canvas.drawRenderNode(node); canvas.restoreToCount(clip)
            }
            paint.shader = null; paint.style = Paint.Style.FILL
            paint.color = if (dark) Color.argb((config.darkAlpha * 255).toInt(), 33, 31, 38) else Color.argb((config.lightAlpha * 255).toInt(), 255, 255, 255)
            canvas.drawPath(path, paint)
            if (config.liquidGlassEnabled && !config.solidBarEnabled) {
                if (config.outlineEnabled) glassEdge.draw(canvas, path, d, dark, (if (dark) .42f else .6f) * 2f * config.darkBarHighlightStrength)
            } else {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = .7f * d
                paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xB3FFFFFF.toInt(), 0x18FFFFFF, 0x60FFFFFF), null, Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL; paint.shader = null
            }
            if (press > 0f || glow > 0f) {
                val alpha = ((max(press, 0f) * .20f + glow * .38f) * 255).toInt().coerceIn(0, 255)
                paint.shader = RadialGradient(w * x, h * y, max(h.toFloat(), w * (.22f + spread * .55f)), intArrayOf(Color.argb(alpha,255,255,255),0x00FFFFFF), null, Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint); paint.shader = null
            }
            canvas.restoreToCount(save)
        }
        fun excludeInterior(canvas: Canvas, parent: View) {
            if (!host.isShown) return
            updateAmbientMatrix(parent)
            path.transform(outsideMatrix, outsideClip)
            canvas.clipOutPath(outsideClip)
        }
        fun drawAmbient(canvas: Canvas, parent: View) {
            if (!canvas.isHardwareAccelerated || !host.isShown || !ambient.hasDisplayList()) return
            updateAmbientMatrix(parent)
            val save = canvas.save()
            canvas.concat(outsideMatrix)
            io.github.offlineglass.rendering.PostureGlass.shadow(canvas, host,
                RectF(0f,0f,bounds.width().toFloat(),bounds.height().toFloat())) { canvas.drawRenderNode(ambient) }
            canvas.restoreToCount(save)
        }
        private fun updateAmbientMatrix(parent: View) {
            outsideMatrix.reset()
            host.transformMatrixToGlobal(outsideMatrix)
            val inverse = Matrix()
            parent.transformMatrixToGlobal(inverse)
            inverse.invert(inverse)
            outsideMatrix.postConcat(inverse)
        }
        fun geometryKey(): String {
            val matrix = Matrix()
            host.transformMatrixToGlobal(matrix)
            val values = FloatArray(9)
            matrix.getValues(values)
            val hostGeometry = values.contentToString()
            matrix.reset()
            source.transformMatrixToGlobal(matrix)
            matrix.getValues(values)
            // Drawable display lists cache the recorded sampling transform.
            // Include the recycler's own drag translation as well as its
            // ancestors. The floating sibling does not get these invalidations.
            return "${host.isShown}/${host.width}/${host.height}/$hostGeometry/" +
                "${values.contentToString()}/${source.left}/${source.top}/" +
                "${source.width}/${source.height}/${source.scrollX}/${source.scrollY}"
        }
        fun touch(e: MotionEvent) {
            host.getLocationOnScreen(location)
            val inside = host.isShown && e.rawX >= location[0] && e.rawX < location[0] + host.width && e.rawY >= location[1] && e.rawY < location[1] + host.height
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (inside) { down = true; x = (e.rawX-location[0])/host.width; y = (e.rawY-location[1])/host.height; animate(true, false) }
                MotionEvent.ACTION_MOVE -> if (down && !inside) { down = false; animate(false, false) }
                MotionEvent.ACTION_UP -> if (down) { down = false; animate(false, inside) }
                MotionEvent.ACTION_CANCEL -> if (down) { down = false; animate(false, false) }
            }
        }
        private fun animate(pressed: Boolean, clicked: Boolean) {
            animator?.cancel(); val from = press
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (pressed) 130L else 420L
                addUpdateListener {
                    val t = it.animatedValue as Float
                    press = if (pressed) from + (1-from)*t else if (t == 1f) 0f else from * exp(-6*t) * cos(12*t)
                    glow = if (clicked) (1-t)*(1-t) else 0f; spread = t
                    val scale = 1 + press * if (circular) .075f else .035f
                    host.scaleX = scale; host.scaleY = scale; invalidateSelf(); invalidateAmbient()
                }
                start()
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
