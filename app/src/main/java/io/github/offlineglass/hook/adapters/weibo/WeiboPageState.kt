package io.github.offlineglass.hook.adapters.weibo

import android.os.SystemClock
import io.github.offlineglass.hook.GlassHostLayout

internal fun GlassHostLayout.weiboPageAllowsNavigation(): Boolean {
    val state = appNavigationState as? WeiboNavigationState ?: return true
    val tabHost = findWeiboTabHost()
    val currentTab = runCatching {
        tabHost?.javaClass?.getMethod("getCurrentTab")?.invoke(tabHost) as? Int
    }.getOrNull() ?: run {
        state.refreshNavigationSource(this)
        return true
    }
    when (currentTab) {
        VIDEO_INDEX -> {
            WeiboAdapter.currentTab = VIDEO_INDEX
            if (state.videoHideTime == 0L) state.videoHideTime = SystemClock.uptimeMillis() + 1_500L
            return SystemClock.uptimeMillis() < state.videoHideTime
        }
        MESSAGE_INDEX -> {
            WeiboAdapter.currentTab = currentTab
            ensureMsgScrollStopHideListener()
            return !msgScrolledToStop
        }
        else -> {
            WeiboAdapter.currentTab = currentTab
            WeiboAdapter.lastNonVideoTab = currentTab
            state.videoHideTime = 0L
            state.messageHideTime = 0L
            state.messagePeekUntil = 0L
            resetMessageScrollStopState()
            if (selectedIndex == VIDEO_INDEX || selectedIndex == MESSAGE_INDEX) {
                snapToNavigationIndex(currentTab)
            }
            return true
        }
    }
}

private const val VIDEO_INDEX = 1
private const val MESSAGE_INDEX = 3
