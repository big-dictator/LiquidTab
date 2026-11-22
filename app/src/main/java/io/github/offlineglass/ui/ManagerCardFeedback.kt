package io.github.offlineglass.ui

import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import top.yukonga.miuix.kmp.utils.TiltFeedback

internal class CardFeedbackPolicy { var controls by mutableIntStateOf(0) }
internal val LocalCardFeedbackPolicies = staticCompositionLocalOf { emptyList<CardFeedbackPolicy>() }

/** Disable tilt on every enclosing card, not the control's own feedback. */
@Composable
internal fun ExcludeControlCardTilt() {
    val policies = LocalCardFeedbackPolicies.current
    DisposableEffect(policies) {
        policies.forEach { it.controls++ }
        onDispose { policies.forEach { it.controls-- } }
    }
}

/** Observe without consuming: switches, sliders and scrolling retain their gestures. */
@Composable
internal fun Modifier.managerCardFeedback(): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val feedback = remember { TiltFeedback() }
    return indication(interactions, feedback).pointerInput(interactions) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val press = PressInteraction.Press(down.position)
            interactions.tryEmit(press)
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        released = true
                        break
                    }
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
            } finally {
                interactions.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
        }
    }
}

@Composable
internal fun ManagerMaterialCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.material3.CardDefaults.shape,
    colors: androidx.compose.material3.CardColors = androidx.compose.material3.CardDefaults.cardColors(),
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val policy = remember { CardFeedbackPolicy() }
    val parents = LocalCardFeedbackPolicies.current
    val policies = remember(parents, policy) { parents + policy }
    val feedbackModifier = if (policy.controls == 0) modifier.managerCardFeedback() else modifier
    val cardContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        CompositionLocalProvider(LocalCardFeedbackPolicies provides policies) { content() }
    }
    if (onClick == null) {
        androidx.compose.material3.Card(modifier = feedbackModifier, shape = shape, colors = colors, content = cardContent)
    } else {
        androidx.compose.material3.Card(onClick = onClick, modifier = feedbackModifier, shape = shape, colors = colors, content = cardContent)
    }
}
