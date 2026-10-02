package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/** Send a complete gesture through NavigationGroup so JD handles reselect and icon animation. */
internal object JdTabTouch {
    fun dispatch(item: View, source: ViewGroup?): Boolean {
        if (!item.isAttachedToWindow || !item.isEnabled || item.width <= 0 || item.height <= 0) return false
        val row = source?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
        val now = SystemClock.uptimeMillis()
        val itemLocation = IntArray(2).also(item::getLocationInWindow)
        val sourceLocation = IntArray(2).also { row?.getLocationInWindow(it) }
        val x = if (row != null) itemLocation[0] - sourceLocation[0] + item.width / 2f else item.width / 2f
        val y = if (row != null) itemLocation[1] - sourceLocation[1] + item.height / 2f else item.height / 2f
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 48L, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            val target = row ?: item
            val acceptedDown = target.dispatchTouchEvent(down)
            val acceptedUp = target.dispatchTouchEvent(up)
            acceptedDown || acceptedUp
        } finally {
            down.recycle()
            up.recycle()
        }
    }
}
