package io.github.offlineglass.hook.adapters.wechat

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

internal class WeChatFoldTouchProxy(context: Context) : View(context) {

    companion object {
    fun findClickTarget(bar: View): View? {

        var current: View? = bar

        repeat(6) {

            val candidate = current ?: return null

            if (candidate.isAttachedToWindow && candidate.visibility == View.VISIBLE &&

                candidate.isEnabled && candidate.isClickable && candidate.hasOnClickListeners()

            ) {

                return candidate

            }

            current = candidate.parent as? View

        }

        return null

    }
    }

    var clickTarget: View? = null

    var beforeClick: (() -> Unit)? = null

    private var activeClickTarget: View? = null

    private var activeBeforeClick: (() -> Unit)? = null

    private var downX = 0f

    private var downY = 0f

    private var moved = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()



    init {

        isClickable = true

        isFocusable = false

        background = null

        alpha = 1f

    }



    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                activeClickTarget = clickTarget?.takeIf {

                    it.isAttachedToWindow && it.visibility == View.VISIBLE && it.isEnabled

                }

                activeBeforeClick = beforeClick

                downX = event.x

                downY = event.y

                moved = false

                isPressed = activeClickTarget != null

                return activeClickTarget != null

            }

            MotionEvent.ACTION_MOVE -> {

                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {

                    moved = true

                    isPressed = false

                }

                return activeClickTarget != null

            }

            MotionEvent.ACTION_UP -> {

                val target = activeClickTarget

                val shouldClick = !moved && target != null && target.isAttachedToWindow && target.isEnabled

                isPressed = false

                activeClickTarget = null

                val prepareNativeClick = activeBeforeClick

                activeBeforeClick = null

                moved = false

                if (shouldClick) {

                    prepareNativeClick?.invoke()

                    target.performClick()

                }

                return target != null

            }

            MotionEvent.ACTION_CANCEL -> {

                val handled = activeClickTarget != null

                isPressed = false

                activeClickTarget = null

                activeBeforeClick = null

                moved = false

                return handled

            }

        }

        return activeClickTarget != null

    }

}
