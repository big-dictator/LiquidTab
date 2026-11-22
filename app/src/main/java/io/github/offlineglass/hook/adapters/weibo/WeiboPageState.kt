package io.github.offlineglass.hook.adapters.weibo

import android.os.SystemClock
import io.github.offlineglass.hook.GlassHostLayout

internal fun GlassHostLayout.weiboPageAllowsNavigation(): Boolean {
    val state = appNavigationState as? WeiboNavigationState ?: return true
    if (selectedIndex == MESSAGE_INDEX) {
        WeiboAdapter.currentTab = MESSAGE_INDEX
        resetMessageScrollStopState()
        state.messageSafety.update(this)
        return true
    }
    val tabHost = findWeiboTabHost()
    val currentTab = tabHost?.currentTab ?: run {
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
            state.messageSafety.update(this)
            resetMessageScrollStopState()
            return true
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
