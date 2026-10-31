package dev.ninecontroller.android.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch

/**
 * Motion tokens (docs/DESIGN_SYSTEM.md): fast presses, slower arrivals, strong out-curves, nothing
 * linear. The controller surface itself never animates input — a press shows on the next frame.
 */
object NineMotion {
    val out = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)        // arrivals
    val hover = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f) // presses, exits
    const val PRESS = 100; const val RELEASE = 180; const val UI = 180; const val PANEL = 320; const val EXIT = 160
    /** Pressed controls shrink this much (3 %): felt, not seen. */
    const val PRESS_SCALE = 0.97f
}

/**
 * Press feedback for every clickable in the app, instead of the Material ripple: the control dips to
 * 97 % on press and eases back on release.
 */
object PressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0x9
}

private class PressNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val pressed = Animatable(0f)
    override fun onAttach() {
        coroutineScope.launch {
            var down = 0
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> down++
                    is PressInteraction.Release, is PressInteraction.Cancel -> down = maxOf(0, down - 1)
                    else -> return@collect
                }
                val on = down > 0
                launch { pressed.animateTo(if (on) 1f else 0f, tween(if (on) NineMotion.PRESS else NineMotion.RELEASE, easing = NineMotion.hover)) }
            }
        }
    }
    override fun ContentDrawScope.draw() {
        val s = 1f - (1f - NineMotion.PRESS_SCALE) * pressed.value
        if (s == 1f) drawContent() else scale(s) { this@draw.drawContent() }
    }
}

/**
 * Screen changes: the new screen rises a little (2 % of its height) and fades in; the old one fades out
 * quickly. Fade only when animations are reduced, and for the controller (it rotates the screen).
 */
object NineTransitions {
    fun enter(reduced: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val fade = fadeIn(tween(NineMotion.PANEL, easing = NineMotion.out))
        if (reduced || targetState.destination.route == "play") fade
        else fade + slideInVertically(tween(NineMotion.PANEL, easing = NineMotion.out)) { it / 50 }
    }
    fun exit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { fadeOut(tween(NineMotion.EXIT, easing = NineMotion.hover)) }
}
