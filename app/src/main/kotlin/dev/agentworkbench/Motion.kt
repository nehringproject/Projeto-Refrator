package dev.agentworkbench

import androidx.compose.animation.core.animateFloat

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Small, calm motion helpers shared by the home screens.
 * Everything is plain Compose (animation.core, foundation, ui). Only the status dot and the
 * shimmer loop forever; everything else runs once and settles.
 */

private const val DefaultPressedScale = 0.96f

/**
 * Scales the element down while a finger is on it, with a soft spring back.
 *
 * Use this form when the element gets its clicks from a combinedClickable/clickable that has no
 * shared interaction source: a pass-through pointer listener mirrors the press into our own source.
 */
internal fun Modifier.pressScale(pressedScale: Float = DefaultPressedScale): Modifier = composed {
    // Observes the touch on the Initial pass without consuming anything, so the clickable /
    // combinedClickable underneath still receives the tap and the long press.
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScaleObserved",
    )
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                pressed = false
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Scales the element while [interactionSource] reports a press. Pass the same source to the
 * clickable/Surface that owns the gesture (e.g. `clickable(interactionSource = source, ...)`).
 */
internal fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = DefaultPressedScale,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Drop-in replacement for `clickable(onClick = ...)` that also presses down with [pressScale].
 * Keeps the default ripple so the touch still has an indication.
 */
internal fun Modifier.pressScale(
    enabled: Boolean = true,
    pressedScale: Float = DefaultPressedScale,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    this
        .pressScale(source, pressedScale)
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}

/** Keys that already played their entrance this session, so scrolling back never replays it. */
private val appearedKeys = HashSet<Any>()

/**
 * Fades in and rises a few dp the first time [key] is composed. Items are staggered by [index]
 * (capped, so long lists do not wait forever). Once shown, the key is remembered for the whole
 * process and later compositions start fully visible.
 */
internal fun Modifier.appearOnce(key: Any, index: Int = 0): Modifier = composed {
    val progress = remember(key) { Animatable(if (key in appearedKeys) 1f else 0f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            delay(minOf(index, 8) * 40L)
            progress.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
            appearedKeys += key
        }
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 14.dp.toPx()
    }
}

/**
 * A subtle highlight band that sweeps across the element. Meant for loading placeholders; put it
 * after clip/background so the band stays inside the placeholder shape:
 * `Modifier.clip(shape).background(color).shimmer()`.
 */
internal fun Modifier.shimmer(
    highlight: Color = Color.White.copy(alpha = 0.08f),
    durationMillis: Int = 1_400,
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerPhase",
    )
    drawWithContent {
        drawContent()
        val halfBand = size.width * 0.25f
        val center = size.width * phase
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(center - halfBand, 0f),
                end = Offset(center + halfBand, 0f),
            ),
        )
    }
}

/** Placeholder bar for a line of text that is still loading. */
@Composable
internal fun ShimmerText(
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    height: Dp = 12.dp,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(WorkbenchTokens.SurfaceHigh)
            .shimmer(),
    )
}

/**
 * Small status dot with a soft halo that expands and fades, like a heartbeat. The layout size is
 * always [diameter]; the halo is drawn outside it so neighbouring text does not move.
 */
@Composable
internal fun PulseDot(
    color: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 7.dp,
) {
    val transition = rememberInfiniteTransition(label = "pulseDot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulsePhase",
    )
    Box(modifier = modifier.size(diameter)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val s = 1f + pulse * 1.6f
                    scaleX = s
                    scaleY = s
                    alpha = (1f - pulse) * 0.45f
                }
                .background(color, CircleShape),
        )
        Box(modifier = Modifier.matchParentSize().background(color, CircleShape))
    }
}
