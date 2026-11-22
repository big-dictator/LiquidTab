package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup

/** Identify LauncherUI's page owner by its native siblings, never APK resource numbers. */
internal object WeChatLauncherPages {
    private const val PAGER = "com.tencent.mm.ui.base.CustomViewPager"
    private const val TAB = "com.tencent.mm.ui.LauncherUIBottomTabView"
    private const val FROST = "com.tencent.mm.ui.FrostedContentView"

    private val pagerStates = java.util.WeakHashMap<View, Int>()
    private var currentItem: java.lang.reflect.Method? = null

    fun installPagerTransition(loader: ClassLoader) {
        val pager = Class.forName(PAGER, false, loader)
        val setter = pager.getMethod("setScrollState", Int::class.javaPrimitiveType)
        val getter = pager.getMethod("getCurrentItem")
        check(setter.returnType == Void.TYPE && getter.returnType == Int::class.javaPrimitiveType)
        io.github.offlineglass.hook.compat.XposedBridge.hookMethod(setter,
            object : io.github.offlineglass.hook.compat.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!isMainPager(view)) return
                    pagerStates[view] = param.args[0] as Int
                    if (pagerStates[view] == 0) view.rootView.postInvalidateOnAnimation()
                }
            })
        currentItem = getter
    }

    fun hasSettled(source: View?, selected: Int): Boolean {
        val parent = source?.parent as? ViewGroup ?: return true
        val pager = (0 until parent.childCount).asSequence()
            .map(parent::getChildAt).singleOrNull(::isMainPager) ?: return true
        val getter = currentItem ?: return true
        return (pagerStates[pager] ?: 0) == 0 &&
            runCatching { getter.invoke(pager) == selected }.getOrDefault(true)
    }

    fun isMainPager(view: View): Boolean {
        if (view.javaClass.name != PAGER || view !is ViewGroup) return false
        val parent = view.parent as? ViewGroup ?: return false
        // Both the current APK and the device hierarchy place the native tab
        // beside the main pager. Secondary/chat pagers do not own that tab.
        return (0 until parent.childCount).any { parent.getChildAt(it).javaClass.name == TAB }
    }

    fun owns(instance: Any?): Boolean {
        val page = instance as? View ?: return false
        if (page.javaClass.name != FROST) return false
        return (page.parent as? View)?.let(::isMainPager) == true
    }
}
