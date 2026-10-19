package dev.agentworkbench

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Indigo → violet → pink ink for the "ink in water" motif. */
internal object InkTokens {
    val Deep = Color(0xFF241552)
    val Mid = Color(0xFF4C2BB5)
    val Glint = Color(0xFFFF4F86)
}

/**
 * Slow, liquid ink: a few soft radial blobs drifting on independent Lissajous paths. Where they
 * overlap the gradients add up, which reads as ink diffusing in water. [intensity] scales the
 * opacity so the same effect can be a faint background or a visible orb.
 */
@Composable
internal fun InkBlobs(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    periodMillis: Int = 7_000,
) {
    val transition = rememberInfiniteTransition(label = "ink")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
        label = "inkPhase",
    )
    Canvas(modifier = modifier) {
        val reach = min(size.width, size.height)
        INK_BLOBS.forEach { blob ->
            val angle = phase * blob.speed + blob.offset
            val center = Offset(
                x = size.width / 2f + cos(angle) * size.width * blob.drift,
                y = size.height / 2f + sin(angle * 1.37f) * size.height * blob.drift,
            )
            // Radius breathes a little so the blobs seem to swell and thin like real ink.
            val radius = reach * blob.radius * (0.85f + 0.15f * sin(angle * 2f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        blob.color.copy(alpha = 0.95f * intensity),
                        InkTokens.Deep.copy(alpha = 0.45f * intensity),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

private class InkBlob(val offset: Float, val speed: Float, val drift: Float, val radius: Float, val color: Color)

private val INK_BLOBS = listOf(
    InkBlob(offset = 0.0f, speed = 1.0f, drift = 0.16f, radius = 0.42f, color = InkTokens.Mid),
    InkBlob(offset = 2.1f, speed = -0.8f, drift = 0.20f, radius = 0.34f, color = InkTokens.Deep),
    InkBlob(offset = 4.2f, speed = 1.3f, drift = 0.12f, radius = 0.26f, color = InkTokens.Glint),
)
