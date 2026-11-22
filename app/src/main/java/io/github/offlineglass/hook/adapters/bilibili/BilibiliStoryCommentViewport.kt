package io.github.offlineglass.hook.adapters.bilibili

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.MotionEvent
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap

/** Native story tab padding follows visible sheet geometry, including status-fit offsets. */
internal object BilibiliStoryCommentViewport {
    private val listeners = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()
    fun install(loader: ClassLoader) {
        runCatching {
            val fragment = loader.loadClass("com.bilibili.video.story.tab.StoryTabDialogFragment")
            // Resolve the complete behavior contract before changing any native input path.
            val dragArea = loader.loadClass("com.bilibili.video.story.view.StoryTabDragArea")
            val callbackBase = loader.loadClass("com.google.android.material.bottomsheet.BottomSheetBehavior\$BottomSheetCallback")
            val callback = fragment.declaredFields.map { it.type }.distinct().single { type ->
                callbackBase.isAssignableFrom(type) && type.declaredFields.count { it.type == fragment } == 1
            }
            val outer = callback.declaredFields.single { it.type == fragment }.apply { isAccessible = true }
            val behaviorType = loader.loadClass("com.google.android.material.bottomsheet.BottomSheetBehavior")
            val behaviorGetter = fragment.declaredMethods.single {
                it.parameterTypes.isEmpty() && behaviorType.isAssignableFrom(it.returnType)
            }.apply { isAccessible = true }
            // Native drag-area DOWN sets expandedOffset(0) while state is still
            // EXPANDED-at-R. A layout before touch slop then jumps to full size.
            // Let the parent Behavior start dragging before changing that offset.
            XposedBridge.hookAllMethods(dragArea, "onTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((param.args.firstOrNull() as? MotionEvent)?.actionMasked == MotionEvent.ACTION_DOWN)
                        param.result = false
                }
            })
            XposedBridge.hookAllMethods(View::class.java, "performClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.thisObject.javaClass.name == "com.bilibili.video.story.view.StoryDialogExpandButton")
                        param.result = true
                }
            })
            XposedBridge.hookAllMethods(fragment, "onViewCreated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // This is the native ViewPager2 (not an opaque ViewBinding field).
                    // Preserve its own swipe behavior, remove only the fullscreen click.
                    val root = param.args.firstOrNull() as? View ?: return
                    pager(root)?.let { it.setOnClickListener(null); it.isClickable = false }
                }
            })
            for (name in listOf("onSlide", "onStateChanged")) {
                XposedBridge.hookAllMethods(callback, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val owner = outer.get(param.thisObject) ?: return
                        val dialog = XposedHelpers.callMethod(owner, "getDialog") as? Dialog ?: return
                        if (name == "onStateChanged" && param.args.getOrNull(1) == 1) {
                            val behavior = behaviorGetter.invoke(owner)
                            if (behavior != null) XposedHelpers.callMethod(behavior, "setExpandedOffset", 0)
                        }
                        if (name == "onStateChanged" && param.args.getOrNull(1) == 3) {
                            val sheet = param.args.firstOrNull() as? View
                            val status = dialog.window?.decorView?.rootWindowInsets
                                ?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
                            if (sheet != null && sheet.top <= status) {
                                val behavior = behaviorGetter.invoke(owner)
                                if (behavior != null) XposedHelpers.callMethod(behavior, "setExpandedOffset", 0)
                            }
                        }
                        update(dialog)
                    }
                })
            }
            XposedBridge.log("LiquidTab Bilibili story comment behavior contract verified")
        }.onFailure { XposedBridge.log("LiquidTab story comment viewport: $it") }
    }
    private fun pager(view: View): View? {
        if (view.javaClass.name == "androidx.viewpager2.widget.ViewPager2") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) pager(view.getChildAt(index))?.let { return it }
        return null
    }
    fun attach(dialog: Dialog) {
        val decor = dialog.window?.decorView ?: return
        if (!listeners.containsKey(decor)) {
            val listener = ViewTreeObserver.OnPreDrawListener { update(dialog); true }
            listeners[decor] = listener
            decor.viewTreeObserver.addOnPreDrawListener(listener)
        }
        update(dialog)
    }
    private fun update(dialog: Dialog) {
        if (!dialog.isShowing) return
        val decor = dialog.window?.decorView ?: return
        if (decor.height <= 0 || decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) return
        val sheetId = decor.resources.getIdentifier("design_bottom_sheet", "id", dialog.context.packageName)
        val sheet = decor.findViewById<View>(sheetId) ?: return
        if (sheet.height <= 0 || !sheet.isShown) return
        val content = pager(sheet) ?: return
        val position = IntArray(2)
        sheet.getLocationInWindow(position)
        val sheetBottom = position[1] + sheet.height
        decor.getLocationInWindow(position)
        val windowBottom = position[1] + decor.height
        // Collapsed sheets extend below the window; expanded sheets end at its
        // bottom even when their top is not zero due to a status/cutout inset.
        val bottom = (sheetBottom - windowBottom).coerceAtLeast(0)
        if (content.paddingBottom != bottom)
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
    }
    fun release(decor: View) {
        val listener = listeners.remove(decor) ?: return
        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(listener)
    }
}
