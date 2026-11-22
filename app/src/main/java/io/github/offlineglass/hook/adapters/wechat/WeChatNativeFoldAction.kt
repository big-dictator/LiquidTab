package io.github.offlineglass.hook.adapters.wechat

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class WeChatNativeFoldAction(owner: View, helper: Any) {
    private val owner = WeakReference(owner)
    private val helper = WeakReference(helper)
    private val helperType = helper.javaClass
    @Volatile private var access: Access? = null
    private var started = false
    private var nextRead = 0L
    private var lastClick = 0L
    private var collapsed: Boolean? = null

    private data class Access(val toggle: Method, val store: SharedPreferences)

    fun state(): Boolean? {
        val view = owner.get()?.takeIf { it.isAttachedToWindow } ?: return null
        if (!started) {
            started = true
            val path = view.context.applicationInfo.sourceDir
            Thread({
                runCatching {
                    val binding = synchronized(bindings) {
                        bindings.getOrPut(path to helperType) {
                            runCatching { WeChatFoldDexContract.resolve(path, helperType.name) }
                        }.getOrThrow()
                    }
                    val toggle = helperType.getDeclaredMethod(binding.toggle())
                    check(toggle.returnType == Void.TYPE && !Modifier.isStatic(toggle.modifiers))
                    toggle.isAccessible = true
                    val storeClass = Class.forName(binding.storeClass(), false, helperType.classLoader)
                    check(SharedPreferences::class.java.isAssignableFrom(storeClass))
                    val factory = storeClass.getDeclaredMethod(binding.storeFactory(), String::class.java)
                    check(factory.returnType == storeClass && Modifier.isStatic(factory.modifiers))
                    factory.isAccessible = true
                    view.post {
                        if (this.owner.get() === view && helper.get() != null) {
                            runCatching {
                                access = Access(toggle, factory.invoke(null, WeChatFoldDexContract.STORE) as SharedPreferences)
                                view.rootView.invalidate()
                            }.onFailure { Log.w("LiquidTabWeChatFold", "Cannot open native fold state", it) }
                        }
                    }
                }.onFailure { Log.w("LiquidTabWeChatFold", "Cannot bind native fold action", it) }
            }, "LiquidTab-WeChatFold").start()
        }
        val current = access ?: return null
        val now = SystemClock.uptimeMillis()
        if (now >= nextRead) {
            nextRead = now + 150L
            collapsed = runCatching {
                when (current.store.getInt(WeChatFoldDexContract.STATE, 65536)) {
                    131072 -> true
                    65536, 262144 -> false
                    else -> null
                }
            }.getOrNull()
        }
        return collapsed
    }

    fun toggle(): Boolean {
        val instance = helper.get() ?: return false
        val view = owner.get()?.takeIf { it.isAttachedToWindow && it.isShown } ?: return false
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < 500L || state() == null) return false
        val current = access ?: return false
        lastClick = now
        return runCatching {
            current.toggle.invoke(instance)
            nextRead = 0L
            view.rootView.invalidate()
            view.postDelayed({ view.rootView.invalidate() }, 170L)
            view.postDelayed({ view.rootView.invalidate() }, 520L)
            true
        }.onFailure { Log.w("LiquidTabWeChatFold", "Native fold toggle failed", it) }.getOrDefault(false)
    }

    private companion object {
        val bindings = mutableMapOf<Pair<String, Class<*>>, Result<WeChatFoldDexContract.Binding>>()
    }
}
