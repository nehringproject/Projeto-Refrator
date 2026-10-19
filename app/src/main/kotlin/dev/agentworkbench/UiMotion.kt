package dev.agentworkbench

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/*
 * Shared motion vocabulary for the Refrator screens. Keep durations and springs here so the
 * app moves with one rhythm: quick for state flips, standard for content, gentle for entrances.
 */

/** Durations (ms). Pick one of these instead of inventing a new number in a screen. */
internal object UiMotion {
    const val Quick = 180
    const val Standard = 280
    const val Entrance = 360
}

/** The app's default spring for things that should feel alive but never wobble for long. */
internal fun <T> uiSpring(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Eased tween for state changes (colours, fades, small slides). */
internal fun <T> uiQuick(): FiniteAnimationSpec<T> = tween(UiMotion.Quick, easing = FastOutSlowInEasing)

/**
 * A number that rolls when it changes: a bigger count slides up, a smaller one slides down,
 * and the old value fades out underneath. Used for counters in the board and the group header.
 */
@Composable
internal fun AnimatedCount(
    count: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    AnimatedContent(
        targetState = count,
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            val up = targetState > initialState
            (
                fadeIn(tween(UiMotion.Standard, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(UiMotion.Standard, easing = FastOutSlowInEasing)) { if (up) it / 2 else -it / 2 }
                ) togetherWith (
                fadeOut(tween(UiMotion.Quick)) +
                    slideOutVertically(tween(UiMotion.Quick)) { if (up) -it / 2 else it / 2 }
                )
        },
        label = "animatedCount",
    ) { value ->
        Text(
            text = value.toString(),
            style = style,
            color = color,
            fontWeight = fontWeight,
        )
    }
}

/**
 * Three dots that bounce in a continuous wave ("está digitando"). One shared loop drives all
 * three; each dot is offset in phase, so the motion never stalls between keyframes.
 */
@Composable
internal fun BouncingDots(
    color: Color,
    modifier: Modifier = Modifier,
    dot: Dp = 7.dp,
    gap: Dp = 5.dp,
    lift: Dp = 4.dp,
) {
    val transition = rememberInfiniteTransition(label = "bouncingDots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart),
        label = "bouncingPhase",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(dot)
                    .graphicsLayer {
                        // 0..1 wave per dot, shifted a little so the three rise one after another.
                        val wave = ((sin((phase - index * 0.9f).toDouble()) + 1.0) / 2.0).toFloat()
                        translationY = -wave * lift.toPx()
                        alpha = 0.35f + 0.65f * wave
                    }
                    .background(color, CircleShape),
            )
        }
    }
}
