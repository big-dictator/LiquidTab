package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup

/** WeChat page-specific veil timing; shared glass rendering remains in the host. */
internal class WeChatVisualOrchestrator {
    private var navigationBlendSelected = -1
    private companion object {
        const val WECHAT_TAB_CHAT = 0
        const val WECHAT_TAB_CONTACTS = 1
        const val WECHAT_TAB_PROFILE = 3
        const val WECHAT_CHAT_LIST_VEIL_ALPHA = 255
    }

    fun update(host: View, source: ViewGroup?, selectedProvider: () -> Int, enabled: Boolean, density: Float, fold: WeChatFoldController, probe: WeChatContentTargetProbe, veil: WeChatVeilController, surfaceColor: () -> Int) {

        if (!enabled) {

            if (probe.target != null) probe.clear()

            veil.clearContacts()

            veil.clearChatList()

            veil.clearNavigationBlend()

            navigationBlendSelected = -1

            return

        }

        if (source == null || !source.isAttachedToWindow ||

            (source.parent as? View)?.ancestorsAreVisible() == false

        ) {

            if (probe.target != null) probe.clear()

            veil.clearContacts()

            // WeChat briefly hides/reparents the native tab source during the

            // chat -> list transition. Keep the permanent chat-list veil alive

            // across that transient gap so the bottom background is never

            // exposed for a frame. Contacts normally keeps this fallback fully

            // transparent underneath its taller page-owned veil; promote it

            // synchronously when the native row enters its reparenting gap.

            // This touches only our sibling overlay, never conversation rows or

            // the folded-chat container (whose mutation caused the old empty-row bug).

            veil.applyChatList(host, 0.5f, 255, surfaceColor())

            veil.clearNavigationBlend()

            navigationBlendSelected = -1

            return

        }

        val selected = selectedProvider()

        if (selected != navigationBlendSelected) {

            // The blend layer's geometry is anchored to the LiquidTab host,

            // not to any page's content, so a tab switch never invalidates it.

            // Removing it here would flash the bare feed for a frame before the

            // new page's probe rebuilds it �?the exact flicker this guards.

            navigationBlendSelected = selected

        }



        // Contacts owns a permanent colour veil. It is an app-specific repair

        // for WeChat's tall retained native navigation surface, not the optional

        // global bottom-gradient effect, so the settings switch must never tear

        // it down while this tab is visible.

        if (selected == WECHAT_TAB_CONTACTS) {

            if (probe.appliedSelected != selected) probe.clear()

            veil.clearNavigationBlend()

            // Keep the host-anchored list veil allocated but invisible while

            // Contacts' taller veil is healthy. If WeChat detaches that page

            // during a tab switch, the fallback can become opaque in this same

            // pre-draw instead of being created one frame after the list appears.

            veil.applyChatList(host, 0.5f, 0, surfaceColor())

            // Contacts has a taller retained native surface than the other

            // main pages. Keep its original independently measured fade range

            // instead of forcing it through the generic host-relative veil.

            // Install the new layer before retiring the persistent fallback;

            // if WeChat's page target is between reparent/layout passes, retain

            // that fallback so switching tabs cannot expose a bare frame.

            val root = host.rootView as? ViewGroup ?: run {

                veil.setChatListAlpha(WECHAT_CHAT_LIST_VEIL_ALPHA)

                return

            }

            val target = probe.resolveTarget(host, root, fold.weChatFoldBarTarget, fold.weChatFoldBarTouchProxy, veil) ?: run {

                veil.setChatListAlpha(WECHAT_CHAT_LIST_VEIL_ALPHA)

                return

            }

            if (target.width <= 0 || target.height <= 0) {

                veil.setChatListAlpha(WECHAT_CHAT_LIST_VEIL_ALPHA)

                return

            }

            veil.applyContacts(host, source, target, density, surfaceColor())

            if (veil.hasContactsVeil) veil.setChatListAlpha(0)

            probe.appliedSelected = selected

            return

        }



        val usesPermanentMainVeil = selected in WECHAT_TAB_CHAT..WECHAT_TAB_PROFILE

        if (!usesPermanentMainVeil) veil.clearChatList()

        // The chat-list veil is a permanent WeChat layout treatment. It does

        // not follow the optional bottom-gradient-blur preference.

        if (usesPermanentMainVeil) {

            veil.applyChatList(host, if (selected == WECHAT_TAB_CHAT || selected == WECHAT_TAB_CONTACTS) 0.5f else 0f, 255, surfaceColor())

        }



        veil.clearContacts()
        veil.clearNavigationBlend()
    }

    private fun View.ancestorsAreVisible(): Boolean {
        var current: View? = this
        repeat(16) {
            val view = current ?: return true
            if (view.visibility != View.VISIBLE) return false
            current = view.parent as? View
        }
        return true
    }
}

