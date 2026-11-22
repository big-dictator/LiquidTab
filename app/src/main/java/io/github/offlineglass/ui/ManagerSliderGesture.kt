package io.github.offlineglass.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Manager-only ownership: observes down/up without stealing the slider's events. */
internal class ManagerSliderGesture {
    private var owners by mutableIntStateOf(0)
    val active get() = owners > 0
    fun acquire() { owners++ }
    fun release() { owners = (owners - 1).coerceAtLeast(0) }
}

internal val LocalManagerSliderGesture = staticCompositionLocalOf<ManagerSliderGesture?> { null }

internal fun verifyManagerSliderGesture() {
    val owner = ManagerSliderGesture()
    check(!owner.active)
    owner.acquire(); owner.acquire()
    check(owner.active)
    owner.release()
    check(owner.active) // nested card + thumb observers must not release early
    owner.release()
    check(!owner.active)
    owner.release()
    check(!owner.active)
}

@Composable
internal fun Modifier.managerSliderGesture(): Modifier {
    val owner = LocalManagerSliderGesture.current ?: return this
    return pointerInput(owner) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            owner.acquire()
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                owner.release()
            }
        }
    }
}
