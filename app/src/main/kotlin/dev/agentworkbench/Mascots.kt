package dev.agentworkbench

import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class MascotShape {
    PLANET, TRIANGLE, CLOUD, SPARKLE, LEAF_BLOB, GHOST, HEXAGON, FLOWER, MOON, EGG, SPIKY,
    HEPTAGON, KITE, CLOVER, CAT, STAR, GEAR, HEART, SPADE, ONIGIRI,
    // The odd ones: a single big eye, wobbly tentacles, melting goo, too many eyes...
    CYCLOPS, JELLY, SLIME, TRIEYE, MUSHROOM, NOODLE, MANYEYES, TV,
}

enum class MascotDeco { DOT, SPARKLE, TRIANGLE, SPEED, DIAMOND, HEX, PLUS, HEART, DASH, NONE }

/** What the mascot is feeling. Changing mood cross-fades the face and motion. */
enum class MascotMood { IDLE, WORKING, THINKING_HARD, ANNOYED, CONFUSED, HAPPY }

/** One flat sticker friend: silhouette, colours, capsule eyes and a little decoration beside it. */
data class Mascot(
    val shape: MascotShape,
    val body: Color,
    val eyes: Color,
    val deco: MascotDeco,
    val decoColor: Color,
    val tilt: Float = -8f,
) {
    private val dark: Boolean get() = (body.red + body.green + body.blue) / 3f < 0.35f

    /** A vivid colour that represents this mascot in the UI (send button, token bar, tags). */
    val accent: Color get() = if (dark) eyes else body
}

object Mascots {
    // "Black" friends are charcoal so they still read on the app's black background.
    private val black = Color(0xFF2B2C36)
    private val cream = Color(0xFFEFE7DC)

    val all: List<Mascot> = listOf(
        Mascot(MascotShape.PLANET, black, Color(0xFFFF4D4D), MascotDeco.DOT, Color(0xFFFF4D4D)),
        Mascot(MascotShape.TRIANGLE, cream, Color(0xFF16161A), MascotDeco.TRIANGLE, cream, tilt = 6f),
        Mascot(MascotShape.CLOUD, black, Color(0xFF2F7BFF), MascotDeco.DOT, Color(0xFF2F7BFF)),
        Mascot(MascotShape.CYCLOPS, Color(0xFFB8F04A), Color(0xFF16161A), MascotDeco.DOT, Color(0xFFB8F04A), tilt = 0f),
        Mascot(MascotShape.SPARKLE, Color(0xFF3B1F8F), Color(0xFFD9CCFF), MascotDeco.SPARKLE, Color(0xFF4C2BB5), tilt = 0f),
        Mascot(MascotShape.LEAF_BLOB, black, Color(0xFF22C55E), MascotDeco.NONE, Color(0xFF22C55E)),
        Mascot(MascotShape.GHOST, Color(0xFFC9B6F2), black, MascotDeco.SPEED, Color(0xFFB79CF0), tilt = 4f),
        Mascot(MascotShape.HEXAGON, Color(0xFFF2A33A), Color.White, MascotDeco.HEX, Color(0xFFF2A33A)),
        Mascot(MascotShape.FLOWER, Color(0xFFFF4F86), Color.White, MascotDeco.PLUS, Color(0xFFFF4F86), tilt = 0f),
        Mascot(MascotShape.MOON, black, Color(0xFFF7C548), MascotDeco.SPARKLE, Color(0xFFF7C548), tilt = 0f),
        Mascot(MascotShape.EGG, Color(0xFF3B9CFF), Color.White, MascotDeco.DOT, Color(0xFF3B9CFF), tilt = 24f),
        Mascot(MascotShape.SPIKY, black, Color(0xFF8B5CF6), MascotDeco.TRIANGLE, Color(0xFF8B5CF6)),
        Mascot(MascotShape.CLOUD, cream, black, MascotDeco.DOT, Color(0xFFE4DACC), tilt = 0f),
        Mascot(MascotShape.HEPTAGON, Color(0xFF0F3D2E), Color(0xFF4ADE9F), MascotDeco.HEX, Color(0xFF22C55E)),
        Mascot(MascotShape.KITE, Color(0xFF0F1E3D), Color(0xFF3B9CFF), MascotDeco.TRIANGLE, Color(0xFF3B9CFF), tilt = 0f),
        Mascot(MascotShape.CLOVER, Color(0xFFFF3B3B), Color.White, MascotDeco.DOT, Color(0xFFFF3B3B)),
        Mascot(MascotShape.CAT, black, Color(0xFFE879F9), MascotDeco.SPEED, Color(0xFFFF4F86), tilt = 0f),
        Mascot(MascotShape.JELLY, Color(0xFF7DD3FC), Color(0xFF0B2233), MascotDeco.NONE, Color(0xFF7DD3FC), tilt = 0f),
        Mascot(MascotShape.STAR, Color(0xFFC9B6F2), Color(0xFF2E1A6B), MascotDeco.DOT, Color(0xFFC9B6F2), tilt = 0f),
        Mascot(MascotShape.GEAR, black, Color(0xFF2DD4BF), MascotDeco.TRIANGLE, Color(0xFF2DD4BF)),
        Mascot(MascotShape.HEART, Color(0xFFFF3D7F), Color.White, MascotDeco.HEART, Color(0xFFFF3D7F), tilt = 0f),
        Mascot(MascotShape.PLANET, black, Color(0xFFE9DDFF), MascotDeco.NONE, Color(0xFF8B5CF6)),
        Mascot(MascotShape.ONIGIRI, cream, Color(0xFFF2994A), MascotDeco.DASH, Color(0xFFF2994A), tilt = 0f),
        Mascot(MascotShape.SPADE, Color(0xFF3B1F8F), Color.White, MascotDeco.DOT, Color(0xFF3B1F8F), tilt = 0f),
        // New, stranger friends (appended so existing bots keep their mascot).
        Mascot(MascotShape.SLIME, Color(0xFFFF9EC7), Color(0xFF2A0F1E), MascotDeco.NONE, Color(0xFFFF9EC7), tilt = 0f),
        Mascot(MascotShape.TRIEYE, Color(0xFFE8B931), Color(0xFF1C1405), MascotDeco.DASH, Color(0xFFE8B931), tilt = 0f),
        Mascot(MascotShape.MUSHROOM, Color(0xFF8B5CF6), Color(0xFF1A1033), MascotDeco.NONE, Color(0xFFB79CF0), tilt = 0f),
        Mascot(MascotShape.NOODLE, Color(0xFFFFD166), Color(0xFF241A05), MascotDeco.NONE, Color(0xFFFFD166), tilt = 0f),
        Mascot(MascotShape.MANYEYES, black, Color(0xFFFFE066), MascotDeco.NONE, Color(0xFFFFE066), tilt = 0f),
        Mascot(MascotShape.TV, cream, Color(0xFF4ADE80), MascotDeco.NONE, Color(0xFF4ADE80), tilt = 0f),
    )

    fun of(index: Int): Mascot = all[index.mod(all.size)]

    /** A stable mascot for a conversation without a bot, derived from its id. */
    fun forKey(key: String): Int = abs(key.hashCode()) % all.size
}

/**
 * Flat sticker mascot drawn natively (rounded corners via CornerPathEffect). Blinks every few
 * seconds; while [active] it bobs and wobbles as if busy.
 *
 * [mood] changes the face and motion (see [MascotMood]). [active] is kept for older callers: it
 * upgrades the default IDLE mood to WORKING.
 */
@Composable
internal fun MascotAvatar(
    index: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    mood: MascotMood = if (active) MascotMood.WORKING else MascotMood.IDLE,
    /** What it does while working; null = this mascot's own signature activity. */
    activity: MascotActivity? = null,
    /** Show the work activity props (off for tiny or decorative avatars). */
    showActivity: Boolean = size >= 28.dp,
    /**
     * A quick tap makes it hop and wiggle once. Only for avatars that are not inside a clickable,
     * because the tap is observed without being consumed, but it still is a real touch target.
     */
    pokeOnTap: Boolean = false,
) {
    // `active` upgrades a plain IDLE to WORKING; any other explicit mood is shown as given.
    val shown = if (active && mood == MascotMood.IDLE) MascotMood.WORKING else mood
    val mascot = Mascots.of(index)
    // Every value below is a pure function of one shared clock, so the same mascot moves exactly
    // the same way in the home, the chat and the city (and N avatars cost one frame loop).
    val shared = LocalMascotClock.current
    val time = shared ?: rememberMascotTicker()
    // One-shot reaction to a tap: 0 -> 1 over ~640 ms, 1 means "settled" (no motion).
    val poke = remember { Animatable(1f) }
    val pokeScope = rememberCoroutineScope()
    val pokeGesture = if (pokeOnTap) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var last = down
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    last = event.changes.first()
                } while (event.changes.any { it.pressed })
                // A short touch pokes; a long press is left to whatever gesture owns it.
                if (last.uptimeMillis - down.uptimeMillis < 450L) {
                    pokeScope.launch {
                        poke.snapTo(0f)
                        poke.animateTo(1f, tween(640, easing = LinearEasing))
                    }
                }
            }
        }
    } else {
        Modifier
    }
    val seed = index.mod(Mascots.all.size)
    val turn = if (seed % 2 == 0) 1f else -1f
    val working = shown == MascotMood.WORKING || shown == MascotMood.THINKING_HARD
    val chosenActivity = activity ?: signatureActivity(seed)
    val wActivity by moodWeight(working && showActivity, "moodActivity")
    // Mood weights: each one fades in and out, so changing mood never snaps.
    val wIdle by moodWeight(shown == MascotMood.IDLE, "moodIdle")
    val wWork by moodWeight(shown == MascotMood.WORKING, "moodWork")
    val wThink by moodWeight(shown == MascotMood.THINKING_HARD, "moodThink")
    val wAngry by moodWeight(shown == MascotMood.ANNOYED, "moodAngry")
    val wConfused by moodWeight(shown == MascotMood.CONFUSED, "moodConfused")
    val wHappy by moodWeight(shown == MascotMood.HAPPY, "moodHappy")

    Canvas(
        modifier = modifier
            .size(size)
            .then(pokeGesture)
            .graphicsLayer {
                val m = MascotMotion(time.value, index, seed)
                val boing = m.boing
                val shake = m.shake
                val wobble = m.wobble
                val bounce = m.bounce
                val curious = m.curious
                val breath = m.breath
                val h = this.size.height
                val w = this.size.width
                // The tap reaction: one hop that loses energy, with a small wiggle (1 = settled).
                val p = poke.value
                val pokeHop = (sin(p * 2.0 * PI) * (1.0 - p)).toFloat()
                val pokeWiggle = (sin(p * 4.0 * PI) * (1.0 - p)).toFloat()
                // Working: the bob and wobble. Everything else breathes; happy adds a hop.
                // Idle adds the squash-and-hop and the curious tilt, pivoting from the feet.
                val squash = boing * wIdle
                transformOrigin = TransformOrigin(0.5f, 0.92f)
                translationX = shake * w * 0.035f * wAngry
                translationY = -abs(wobble) * h * 0.05f * wWork - bounce * h * 0.05f * wHappy +
                    min(0f, boing) * h * 0.07f * wIdle - pokeHop * h * 0.12f
                rotationZ = wobble * 4f * wWork + curious * 9f * turn * wIdle + pokeWiggle * 7f
                val scale = 1f + breath * 0.03f * (1f - wWork)
                scaleX = scale * (1f + 0.07f * squash)
                scaleY = scale * (1f - 0.09f * squash)
            },
    ) {
        val m = MascotMotion(time.value, index, seed)
        val blink = m.blink
        val glance = m.glance
        val tremble = m.tremble
        val tilt = m.tilt
        val pulse = m.pulse
        val drop = m.drop
        val orbit = m.orbit
        val question = m.question
        val sparkle = m.sparkle
        val clock = m.clock
        val boing = m.boing
        val curious = m.curious
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val s = this.size.minDimension
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val cx = s * 0.47f
            val cy = s * 0.53f
            val r = s * 0.33f
            val frame = MoodFrame(
                idle = wIdle,
                think = wThink,
                angry = wAngry,
                confused = wConfused,
                happy = wHappy,
                tremble = tremble,
                tilt = tilt,
                pulse = pulse,
                drop = drop,
                orbit = orbit,
                question = question,
                sparkle = sparkle,
            )
            // Soft sticker plate so every friend reads on the black UI.
            canvas.drawCircle(s / 2f, s / 2f, s * 0.5f, paint.fillRounded(Color(0xFF14151C), 0f))
            // Idle life shows at full strength only while idle; other moods tone it down.
            val life = 0.4f + 0.6f * frame.idle
            drawDecoration(canvas, mascot, s, paint, clock, life)
            // The whole head floats, hops, sways or beats first; then confusion sways it and
            // thinking hard trembles it, on top of its own tilt.
            canvas.save()
            canvas.rotate(headSway(mascot.shape, clock, life, boing), cx, cy + swayPivot(mascot.shape) * r)
            val beat = headScale(mascot.shape, clock, life)
            canvas.scale(beat, beat, cx, cy)
            canvas.rotate(mascot.tilt + frame.tilt * 7f * frame.confused + frame.tremble * 1.5f * frame.think, cx, cy)
            val hy = cy + headLift(mascot.shape, clock, life) * r
            drawBody(canvas, mascot, cx, hy, r, paint, clock, life, boing)
            drawEyes(canvas, mascot, cx, hy, r, blink, glance * frame.idle, frame, paint, clock, curious * frame.idle)
            canvas.restore()
            drawMoodProps(canvas, mascot, s, cx, cy, r, frame, paint)
            // What it does with its hands while it works (cooking, mining, magnifier, laptop…).
            if (wActivity > 0.01f) {
                drawMascotActivity(canvas, chosenActivity, s, cx, cy, r, m.seconds, wActivity, mascot.accent, mascot.eyes, paint)
            }
        }
    }
}

/** One clock (ms) for every mascot on screen; provided once at the root of the app. */
internal val LocalMascotClock = staticCompositionLocalOf<State<Long>?> { null }

/** Wrap the app in this so every MascotAvatar shares the same clock. */
@Composable
internal fun ProvideMascotClock(content: @Composable () -> Unit) {
    val clock = rememberMascotTicker()
    CompositionLocalProvider(LocalMascotClock provides clock, content = content)
}

/** A frame-driven millisecond clock (uptime based, so every ticker agrees). */
@Composable
private fun rememberMascotTicker(): State<Long> = produceState(SystemClock.uptimeMillis()) {
    while (true) {
        withFrameNanos { value = SystemClock.uptimeMillis() }
    }
}


private fun polygon(cx: Float, cy: Float, radius: Float, points: Int, rotation: Float = -90f, inner: Float? = null): Path {
    val path = Path()
    val total = if (inner != null) points * 2 else points
    for (i in 0 until total) {
        val angle = Math.toRadians((rotation + i * 360.0 / total)).toFloat()
        val rr = if (inner != null && i % 2 == 1) inner else radius
        val x = cx + cos(angle) * rr
        val y = cy + sin(angle) * rr
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun Paint.fillRounded(color: Color, corner: Float): Paint = apply {
    this.color = color.toArgb()
    style = Paint.Style.FILL
    pathEffect = if (corner > 0f) CornerPathEffect(corner) else null
}

/** Cream used for mushroom stems, the cyclops' eye white and other light details. */
private val bone = Color(0xFFF8F3EA)

/** The dark green of the onigiri's nori band. */
private val nori = Color(0xFF1F3A2B)

/** Where the noodle friend's head is at this moment of its sway. */
private fun noodleHead(cx: Float, cy: Float, r: Float, clock: Float): Pair<Float, Float> {
    val sway = sin(2f * PI.toFloat() * clock) * r * 0.16f
    return (cx + r * 0.38f + sway) to (cy - r * 0.55f + abs(sway) * 0.25f)
}

/** One soft pulse that rises and falls while the clock is between [from] and [to], and is 0 outside. */
private fun pulseIn(clock: Float, from: Float, to: Float): Float {
    if (clock <= from || clock >= to) return 0f
    return sin(PI.toFloat() * (clock - from) / (to - from))
}

/** A lub-dub heartbeat, once per cycle. */
private fun heartBeat(clock: Float): Float = pulseIn(clock, 0.02f, 0.2f) + 0.6f * pulseIn(clock, 0.3f, 0.45f)

/** A sharp 0..1 twinkle that peaks [times] per cycle. */
private fun twinkle(wave: Float, times: Float): Float {
    val t = 0.5f + 0.5f * sin(wave * times)
    return t * t * t
}

/** How far one cloud puff has swelled. The puffs take turns, so the swell ripples round the cloud. */
private fun swell(wave: Float, life: Float, k: Int): Float = 1f + 0.08f * life * (0.5f + 0.5f * sin(wave - k * 1.25f))

/** Whole-head lift in units of r (negative is up): the ghost floats, the triangle and onigiri hop as they lean. */
private fun headLift(shape: MascotShape, clock: Float, life: Float): Float {
    val wave = 2f * PI.toFloat() * clock
    return when (shape) {
        MascotShape.GHOST -> 0.06f * life * sin(wave)
        MascotShape.TRIANGLE, MascotShape.ONIGIRI -> -0.04f * life * sin(wave) * sin(wave)
        else -> 0f
    }
}

/** Whole-head sway in degrees, around [swayPivot]: the moon rocks, the spade swings, the egg wobbles on a boing. */
private fun headSway(shape: MascotShape, clock: Float, life: Float, boing: Float): Float {
    val wave = 2f * PI.toFloat() * clock
    return when (shape) {
        MascotShape.MOON -> 6f * life * sin(wave)
        MascotShape.SPADE -> 5f * life * sin(wave)
        MascotShape.TRIANGLE, MascotShape.ONIGIRI -> 4f * life * sin(wave)
        MascotShape.EGG -> 9f * life * boing
        else -> 0f
    }
}

/** Where the head pivots for [headSway], in units of r from its centre. */
private fun swayPivot(shape: MascotShape): Float = when (shape) {
    MascotShape.MOON -> 1.2f
    MascotShape.SPADE -> -1.6f
    else -> 1f
}

/** Whole-head scale: the heart beats, the rest hold still. */
private fun headScale(shape: MascotShape, clock: Float, life: Float): Float =
    if (shape == MascotShape.HEART) 1f + 0.07f * life * heartBeat(clock) else 1f

/** A spiky star whose tips swell one after another, a ripple running round the ring. */
private fun ripplePath(cx: Float, cy: Float, r: Float, points: Int, wave: Float, life: Float): Path {
    val path = Path()
    for (i in 0 until points * 2) {
        val angle = Math.toRadians(-90.0 + i * 360.0 / (points * 2)).toFloat()
        val rr = if (i % 2 == 1) {
            r * 0.82f
        } else {
            r * 1.2f * (1f + 0.1f * life * sin(wave - (i / 2) * 2f * PI.toFloat() / points))
        }
        val x = cx + cos(angle) * rr
        val y = cy + sin(angle) * rr
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** A soft white glint that sweeps once across a flat-faced body each cycle, kept inside its outline. */
private fun drawGlint(
    canvas: android.graphics.Canvas,
    body: Path,
    cx: Float,
    cy: Float,
    r: Float,
    clock: Float,
    life: Float,
    paint: Paint,
    corner: Float,
) {
    val u = (clock - 0.1f) / 0.3f
    if (u <= 0f || u >= 1f) return
    val x = cx + (-1.5f + 3f * u) * r
    val band = Path().apply {
        moveTo(x - r * 0.2f, cy - 2f * r)
        lineTo(x + r * 0.2f, cy - 2f * r)
        lineTo(x + r * 1.2f, cy + 2f * r)
        lineTo(x + r * 0.8f, cy + 2f * r)
        close()
    }
    val glint = Path(body).apply { op(band, Path.Op.INTERSECT) }
    val alpha = sin(PI.toFloat() * u) * 0.35f * life
    canvas.drawPath(glint, paint.fillRounded(Color.White.copy(alpha = alpha), corner))
}

/** A little bow tie that turns a few degrees about its knot, fluttering. */
private fun drawBow(canvas: android.graphics.Canvas, x: Float, y: Float, u: Float, degrees: Float, paint: Paint, color: Color) {
    canvas.save()
    canvas.rotate(degrees, x, y)
    paint.fillRounded(color, 0f)
    canvas.drawPath(polygon(x - u, y, u, 3, rotation = 0f), paint)
    canvas.drawPath(polygon(x + u, y, u, 3, rotation = 180f), paint)
    canvas.restore()
}

private fun drawBody(canvas: android.graphics.Canvas, m: Mascot, cx: Float, cy: Float, r: Float, paint: Paint, clock: Float, life: Float, boing: Float) {
    val c = m.body
    val wave = 2f * PI.toFloat() * clock
    when (m.shape) {
        MascotShape.CYCLOPS -> {
            // A round lump with a little sprout on top; the single big eye is drawn with the eyes.
            canvas.drawCircle(cx, cy + r * 0.05f, r * 1.05f, paint.fillRounded(c, 0f))
            val sprout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = r * 0.12f
                color = c.toArgb()
            }
            val tip = sin(wave) * r * 0.12f
            canvas.drawPath(
                Path().apply {
                    moveTo(cx, cy - r * 0.95f)
                    quadTo(cx + r * 0.05f, cy - r * 1.25f, cx + r * 0.22f + tip, cy - r * 1.32f)
                },
                sprout,
            )
            canvas.drawCircle(cx + r * 0.24f + tip, cy - r * 1.34f, r * 0.12f, paint.fillRounded(c, 0f))
        }
        MascotShape.JELLY -> {
            // Wavy tentacles first, then the dome with a scalloped hem over them.
            val tentacle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = r * 0.15f
                color = c.copy(alpha = 0.85f).toArgb()
            }
            val hem = cy + r * 0.12f
            listOf(-0.6f, -0.2f, 0.2f, 0.6f).forEachIndexed { k, fx ->
                val x = cx + fx * r
                val a = sin(wave + k * 1.4f) * r * 0.2f
                val b = sin(wave + k * 1.4f + 1.6f) * r * 0.2f
                canvas.drawPath(
                    Path().apply {
                        moveTo(x, hem)
                        quadTo(x + a, hem + r * 0.42f, x + b * 0.6f, hem + r * (if (k % 2 == 0) 0.82f else 0.9f))
                    },
                    tentacle,
                )
            }
            val dome = Path().apply {
                moveTo(cx - r * 1.05f, hem)
                arcTo(RectF(cx - r * 1.05f, hem - r * 1.05f, cx + r * 1.05f, hem + r * 1.05f), 180f, 180f)
                val step = r * 2.1f / 5f
                for (i in 0 until 5) {
                    val x0 = cx + r * 1.05f - step * i
                    quadTo(x0 - step / 2f, hem + r * 0.28f, x0 - step, hem)
                }
                close()
            }
            canvas.drawPath(dome, paint.fillRounded(c, 0f))
            // A soft shine on the dome.
            canvas.drawOval(
                RectF(cx - r * 0.75f, hem - r * 0.85f, cx - r * 0.35f, hem - r * 0.55f),
                paint.fillRounded(Color.White.copy(alpha = 0.35f), 0f),
            )
        }
        MascotShape.SLIME -> {
            val goo = Path().apply {
                moveTo(cx - r * 1.05f, cy + r * 0.72f)
                cubicTo(cx - r * 1.1f, cy - r * 0.2f, cx - r * 0.7f, cy - r * 1.0f, cx, cy - r * 1.0f)
                cubicTo(cx + r * 0.7f, cy - r * 1.0f, cx + r * 1.1f, cy - r * 0.2f, cx + r * 1.05f, cy + r * 0.72f)
                quadTo(cx, cy + r * 0.92f, cx - r * 1.05f, cy + r * 0.72f)
                close()
            }
            canvas.drawPath(goo, paint.fillRounded(c, 0f))
            // Two drips that slowly stretch and snap back.
            listOf(-0.5f to 0f, 0.35f to 0.45f).forEach { (fx, phase) ->
                val stretch = (sin(wave + phase * 6f) + 1f) / 2f
                val x = cx + fx * r
                val top = cy + r * 0.62f
                val len = r * (0.25f + 0.32f * stretch)
                canvas.drawRoundRect(RectF(x - r * 0.11f, top, x + r * 0.11f, top + len), r * 0.11f, r * 0.11f, paint)
                canvas.drawCircle(x, top + len, r * 0.14f, paint)
            }
            canvas.drawOval(
                RectF(cx - r * 0.7f, cy - r * 0.75f, cx - r * 0.3f, cy - r * 0.45f),
                paint.fillRounded(Color.White.copy(alpha = 0.4f), 0f),
            )
        }
        MascotShape.TRIEYE -> {
            // A lumpy potato: an oval with a bump on its shoulder.
            paint.fillRounded(c, 0f)
            canvas.drawOval(RectF(cx - r * 1.05f, cy - r * 0.95f, cx + r * 1.0f, cy + r * 1.0f), paint)
            canvas.drawCircle(cx + r * 0.72f, cy - r * 0.62f, r * 0.36f, paint)
            canvas.drawCircle(cx - r * 0.55f, cy + r * 0.7f, r * 0.3f, paint)
        }
        MascotShape.MUSHROOM -> {
            // Bone-white stem (where the face lives) under a spotted cap.
            canvas.drawRoundRect(RectF(cx - r * 0.58f, cy - r * 0.5f, cx + r * 0.58f, cy + r * 1.0f), r * 0.45f, r * 0.45f, paint.fillRounded(bone, 0f))
            val cap = Path().apply {
                moveTo(cx - r * 1.22f, cy - r * 0.3f)
                cubicTo(cx - r * 1.25f, cy - r * 1.35f, cx + r * 1.25f, cy - r * 1.35f, cx + r * 1.22f, cy - r * 0.3f)
                quadTo(cx, cy - r * 0.05f, cx - r * 1.22f, cy - r * 0.3f)
                close()
            }
            canvas.drawPath(cap, paint.fillRounded(c, 0f))
            paint.fillRounded(bone.copy(alpha = 0.9f), 0f)
            canvas.drawCircle(cx - r * 0.5f, cy - r * 0.7f, r * 0.17f, paint)
            canvas.drawCircle(cx + r * 0.25f, cy - r * 0.88f, r * 0.13f, paint)
            canvas.drawCircle(cx + r * 0.75f, cy - r * 0.5f, r * 0.11f, paint)
        }
        MascotShape.NOODLE -> {
            // A long squiggly body whose head sways on its neck.
            val (hx, hy) = noodleHead(cx, cy, r, clock)
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = r * 0.5f
                color = c.toArgb()
            }
            canvas.drawPath(
                Path().apply {
                    moveTo(cx - r * 0.95f, cy + r * 0.85f)
                    cubicTo(cx + r * 0.2f, cy + r * 1.05f, cx - r * 0.75f, cy - r * 0.05f, hx, hy)
                },
                body,
            )
            canvas.drawCircle(hx, hy, r * 0.48f, paint.fillRounded(c, 0f))
        }
        MascotShape.MANYEYES -> {
            // A bumpy dark blob; its many eyes are drawn with the eyes.
            canvas.drawPath(polygon(cx, cy, r * 1.18f, 7, inner = r * 1.0f), paint.fillRounded(c, r * 0.55f))
        }
        MascotShape.TV -> {
            // A little retro telly: wobbling antennas, a dark screen with a scan line and two knobs.
            val antenna = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = r * 0.08f
                color = c.toArgb()
            }
            val sway = sin(wave) * r * 0.1f
            val top = cy - r * 0.75f
            canvas.drawLine(cx - r * 0.1f, top, cx - r * 0.55f + sway, cy - r * 1.28f, antenna)
            canvas.drawLine(cx + r * 0.1f, top, cx + r * 0.45f - sway, cy - r * 1.22f, antenna)
            paint.fillRounded(m.eyes, 0f)
            canvas.drawCircle(cx - r * 0.55f + sway, cy - r * 1.28f, r * 0.1f, paint)
            canvas.drawCircle(cx + r * 0.45f - sway, cy - r * 1.22f, r * 0.1f, paint)
            canvas.drawRoundRect(RectF(cx - r * 1.12f, top, cx + r * 1.12f, cy + r * 0.85f), r * 0.32f, r * 0.32f, paint.fillRounded(c, 0f))
            val screen = RectF(cx - r * 0.88f, cy - r * 0.52f, cx + r * 0.52f, cy + r * 0.62f)
            canvas.drawRoundRect(screen, r * 0.22f, r * 0.22f, paint.fillRounded(Color(0xFF0E1512), 0f))
            val scan = screen.top + (screen.height() - r * 0.06f) * clock
            canvas.drawRect(screen.left + r * 0.08f, scan, screen.right - r * 0.08f, scan + r * 0.05f, paint.fillRounded(m.eyes.copy(alpha = 0.18f), 0f))
            paint.fillRounded(Color(0xFF9A9083), 0f)
            canvas.drawCircle(cx + r * 0.82f, cy - r * 0.22f, r * 0.12f, paint)
            canvas.drawCircle(cx + r * 0.82f, cy + r * 0.2f, r * 0.12f, paint)
        }
        MascotShape.PLANET -> {
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = r * 0.14f
                strokeCap = Paint.Cap.ROUND
                color = m.decoColor.toArgb()
            }
            // The ring tips a little as it precesses; a pale moon rides it, hidden on the far half.
            val tilt = -18f + 6f * sin(wave) * life
            val oval = RectF(cx - r * 1.55f, cy - r * 0.42f, cx + r * 1.55f, cy + r * 0.42f)
            val tiltRad = Math.toRadians(tilt.toDouble()).toFloat()
            val mx = cos(wave) * r * 1.55f
            val my = sin(wave) * r * 0.42f
            val moonX = cx + mx * cos(tiltRad) - my * sin(tiltRad)
            val moonY = cy + mx * sin(tiltRad) + my * cos(tiltRad)
            val far = sin(wave) < 0f
            canvas.save()
            canvas.rotate(tilt, cx, cy)
            canvas.drawArc(oval, 180f, 180f, false, ring)
            canvas.restore()
            if (far) canvas.drawCircle(moonX, moonY, r * 0.14f, paint.fillRounded(bone, 0f))
            canvas.drawCircle(cx, cy, r, paint.fillRounded(c, 0f))
            canvas.save()
            canvas.rotate(tilt, cx, cy)
            canvas.drawArc(oval, 0f, 180f, false, ring)
            canvas.restore()
            if (!far) canvas.drawCircle(moonX, moonY, r * 0.14f, paint.fillRounded(bone, 0f))
        }
        MascotShape.TRIANGLE -> canvas.drawPath(polygon(cx, cy + r * 0.15f, r * 1.25f, 3), paint.fillRounded(c, r * 0.35f))
        MascotShape.ONIGIRI -> {
            val rice = polygon(cx, cy + r * 0.12f, r * 1.2f, 3)
            canvas.drawPath(rice, paint.fillRounded(c, r * 0.6f))
            // A band of nori wrapped round the lower part of the rice.
            val wrap = Path().apply { addRect(cx - 2f * r, cy + r * 0.5f, cx + 2f * r, cy + 2f * r, Path.Direction.CW) }
            val band = Path(rice).apply { op(wrap, Path.Op.INTERSECT) }
            canvas.drawPath(band, paint.fillRounded(nori, r * 0.6f))
        }
        MascotShape.CLOUD -> {
            paint.fillRounded(c, 0f)
            // The puffs swell in turn, a slow ripple running round the cloud.
            canvas.drawCircle(cx, cy - r * 0.25f, r * 0.62f * swell(wave, life, 1), paint)
            canvas.drawCircle(cx - r * 0.62f, cy + r * 0.12f, r * 0.5f * swell(wave, life, 0), paint)
            canvas.drawCircle(cx + r * 0.62f, cy + r * 0.12f, r * 0.5f * swell(wave, life, 2), paint)
            canvas.drawCircle(cx - r * 0.25f, cy + r * 0.38f, r * 0.45f * swell(wave, life, 4), paint)
            canvas.drawCircle(cx + r * 0.25f, cy + r * 0.38f, r * 0.45f * swell(wave, life, 3), paint)
        }
        MascotShape.SPARKLE -> {
            // Twinkles: the star pulses and its points sway a little.
            val tw = 1f + 0.05f * life * twinkle(wave, 2f)
            canvas.save()
            canvas.rotate(sin(wave) * 10f * life, cx, cy)
            canvas.scale(tw, tw, cx, cy)
            canvas.drawPath(polygon(cx, cy, r * 1.35f, 4, inner = r * 0.55f), paint.fillRounded(c, r * 0.25f))
            canvas.restore()
        }
        MascotShape.STAR -> {
            // A slow spin (a fifth of a turn per cycle keeps the loop seamless) and a gentle twinkle.
            val tw = 1f + 0.05f * life * twinkle(wave, 1f)
            val pivotY = cy + r * 0.05f
            canvas.save()
            canvas.rotate(clock * 72f, cx, pivotY)
            canvas.scale(tw, tw, cx, pivotY)
            canvas.drawPath(polygon(cx, pivotY, r * 1.25f, 5, inner = r * 0.68f), paint.fillRounded(c, r * 0.3f))
            canvas.restore()
        }
        MascotShape.SPIKY -> canvas.drawPath(ripplePath(cx, cy, r, 9, wave, life), paint.fillRounded(c, r * 0.06f))
        MascotShape.GEAR -> {
            // The body turns one tooth-step per cycle, so the loop is seamless; the eyes stay upright.
            canvas.save()
            canvas.rotate(clock * 45f, cx, cy)
            canvas.drawPath(polygon(cx, cy, r * 1.15f, 8, inner = r * 0.92f), paint.fillRounded(c, r * 0.12f))
            canvas.restore()
        }
        MascotShape.HEXAGON -> {
            val hex = polygon(cx, cy, r * 1.15f, 6, rotation = 0f)
            canvas.drawPath(hex, paint.fillRounded(c, r * 0.25f))
            drawGlint(canvas, hex, cx, cy, r, clock, life, paint, r * 0.25f)
        }
        MascotShape.HEPTAGON -> {
            val hept = polygon(cx, cy, r * 1.12f, 7)
            canvas.drawPath(hept, paint.fillRounded(c, r * 0.2f))
            drawGlint(canvas, hept, cx, cy, r, clock, life, paint, r * 0.2f)
        }
        MascotShape.KITE -> {
            // A little bowed tail trails from the bottom point, with two bows that flutter as it moves.
            val tail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = r * 0.05f
                color = m.decoColor.toArgb()
            }
            val bx = cx + r * 0.35f
            val by = cy + r * 1.15f
            val flick = sin(wave) * r * 0.1f * life
            val kx = bx - r * 0.12f + flick
            val ky = by + r * 0.22f
            val ex = bx - r * 0.34f + flick * 1.5f
            val ey = by + r * 0.12f
            canvas.drawPath(Path().apply { moveTo(bx, by); quadTo(kx, ky, ex, ey) }, tail)
            drawBow(canvas, 0.25f * bx + 0.5f * kx + 0.25f * ex, 0.25f * by + 0.5f * ky + 0.25f * ey, r * 0.12f, sin(wave) * 22f * life, paint, m.decoColor)
            drawBow(canvas, ex, ey, r * 0.12f, sin(wave + 1.7f) * 22f * life, paint, m.decoColor)
            val path = Path().apply {
                moveTo(cx - r * 0.2f, cy - r * 1.15f)
                lineTo(cx + r * 1.0f, cy - r * 0.55f)
                lineTo(cx + r * 0.35f, cy + r * 1.15f)
                lineTo(cx - r * 0.95f, cy + r * 0.35f)
                close()
            }
            canvas.drawPath(path, paint.fillRounded(c, r * 0.12f))
        }
        MascotShape.LEAF_BLOB -> {
            canvas.drawOval(RectF(cx - r * 1.1f, cy - r * 0.78f, cx + r * 1.1f, cy + r * 0.85f), paint.fillRounded(c, 0f))
            val leaf = Path().apply {
                moveTo(cx + r * 0.25f, cy - r * 0.78f)
                quadTo(cx + r * 0.2f, cy - r * 1.45f, cx + r * 1.05f, cy - r * 1.4f)
                quadTo(cx + r * 1.0f, cy - r * 0.8f, cx + r * 0.25f, cy - r * 0.78f)
                close()
            }
            // The leaf flutters on its stalk.
            val flutter = life * (8f * sin(wave) + 3f * sin(2f * wave + 1f))
            canvas.save()
            canvas.rotate(flutter, cx + r * 0.25f, cy - r * 0.78f)
            canvas.drawPath(leaf, paint.fillRounded(m.decoColor, 0f))
            canvas.restore()
        }
        MascotShape.GHOST -> {
            // A rounded head over a scalloped hem; the scallops ripple as the ghost floats.
            val hem = cy + r * 0.85f
            val ghost = Path().apply {
                moveTo(cx - r, cy - r * 0.05f)
                arcTo(RectF(cx - r, cy - r * 1.05f, cx + r, cy + r * 0.95f), 180f, 180f)
                lineTo(cx + r, hem)
                val step = r * 2f / 3f
                for (i in 0 until 3) {
                    val x0 = cx + r - step * i
                    val sag = r * (0.2f + 0.07f * life * sin(wave + i * 2.1f))
                    quadTo(x0 - step / 2f, hem + sag * 2f, x0 - step, hem)
                }
                close()
            }
            canvas.drawPath(ghost, paint.fillRounded(c, 0f))
        }
        MascotShape.EGG -> canvas.drawOval(RectF(cx - r * 0.85f, cy - r * 1.1f, cx + r * 0.85f, cy + r * 1.1f), paint.fillRounded(c, 0f))
        MascotShape.FLOWER -> {
            paint.fillRounded(c, 0f)
            // The petals turn slowly round the face; four-fold symmetry keeps the loop seamless.
            canvas.save()
            canvas.rotate(clock * 90f, cx, cy)
            listOf(0f, 90f, 180f, 270f).forEach { a ->
                val rad = Math.toRadians(a.toDouble() + 45.0)
                canvas.drawCircle(cx + cos(rad).toFloat() * r * 0.55f, cy + sin(rad).toFloat() * r * 0.55f, r * 0.62f, paint)
            }
            canvas.restore()
            canvas.drawCircle(cx, cy, r * 0.6f, paint)
        }
        MascotShape.CLOVER -> {
            paint.fillRounded(c, 0f)
            // The leaves breathe in turn, one after another round the clover.
            listOf(-90f, 30f, 150f).forEachIndexed { k, a ->
                val rad = Math.toRadians(a.toDouble())
                val breathe = 1f + 0.07f * life * sin(wave - k * 2.1f)
                canvas.drawCircle(cx + cos(rad).toFloat() * r * 0.5f, cy + sin(rad).toFloat() * r * 0.5f, r * 0.68f * breathe, paint)
            }
        }
        MascotShape.MOON -> {
            val moon = Path().apply { addCircle(cx, cy, r * 1.1f, Path.Direction.CW) }
            val bite = Path().apply { addCircle(cx + r * 0.65f, cy - r * 0.45f, r * 0.82f, Path.Direction.CW) }
            moon.op(bite, Path.Op.DIFFERENCE)
            canvas.drawPath(moon, paint.fillRounded(c, 0f))
        }
        MascotShape.CAT -> {
            // The tail swishes behind the body, and the ears flick together now and then (on the boing).
            val swish = sin(wave) * r * 0.15f * life
            val tail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = r * 0.16f
                color = c.toArgb()
            }
            canvas.drawPath(
                Path().apply {
                    moveTo(cx + r * 0.55f, cy + r * 0.7f)
                    cubicTo(cx + r * 0.95f, cy + r * 1.05f + swish, cx + r * 1.2f, cy + r * 0.9f - swish, cx + r * 1.1f, cy + r * 0.68f + swish * 0.5f)
                },
                tail,
            )
            val twitch = boing * 10f * life
            paint.fillRounded(c, r * 0.12f)
            canvas.save()
            canvas.rotate(twitch, cx - r * 0.62f, cy - r * 0.65f)
            canvas.drawPath(polygon(cx - r * 0.62f, cy - r * 0.65f, r * 0.5f, 3, rotation = -100f), paint)
            canvas.restore()
            canvas.save()
            canvas.rotate(twitch, cx + r * 0.62f, cy - r * 0.65f)
            canvas.drawPath(polygon(cx + r * 0.62f, cy - r * 0.65f, r * 0.5f, 3, rotation = -80f), paint)
            canvas.restore()
            canvas.drawOval(RectF(cx - r * 1.05f, cy - r * 0.85f, cx + r * 1.05f, cy + r * 0.95f), paint.fillRounded(c, 0f))
            // The inner ear of the left ear, drawn over the head and flicking with it.
            canvas.save()
            canvas.rotate(twitch, cx - r * 0.62f, cy - r * 0.65f)
            canvas.drawPath(polygon(cx - r * 0.62f, cy - r * 0.72f, r * 0.2f, 3, rotation = -100f), paint.fillRounded(m.eyes.copy(alpha = 0.8f), r * 0.05f))
            canvas.restore()
        }
        MascotShape.HEART, MascotShape.SPADE -> {
            val heart = Path().apply {
                moveTo(cx, cy + r * 1.0f)
                cubicTo(cx - r * 1.7f, cy - r * 0.05f, cx - r * 0.75f, cy - r * 1.35f, cx, cy - r * 0.45f)
                cubicTo(cx + r * 0.75f, cy - r * 1.35f, cx + r * 1.7f, cy - r * 0.05f, cx, cy + r * 1.0f)
                close()
            }
            if (m.shape == MascotShape.SPADE) {
                canvas.save()
                canvas.rotate(180f, cx, cy)
                canvas.drawPath(heart, paint.fillRounded(c, 0f))
                canvas.restore()
                canvas.drawPath(polygon(cx, cy + r * 1.05f, r * 0.42f, 3, rotation = -90f), paint.fillRounded(c, r * 0.1f))
            } else {
                canvas.drawPath(heart, paint.fillRounded(c, 0f))
            }
        }
    }
}

/** How long a mood takes to cross-fade in or out. */
private const val MOOD_FADE_MS = 450

/** The sky blue of the sweat drop. */
private val sweatBlue = Color(0xFF8FD8FF)

/** Animates a mood's weight from 0 to 1 (or back), so its effects blend in and out smoothly. */
@Composable
private fun moodWeight(on: Boolean, label: String): State<Float> = animateFloatAsState(
    targetValue = if (on) 1f else 0f,
    animationSpec = tween(MOOD_FADE_MS, easing = FastOutSlowInEasing),
    label = label,
)

/**
 * One frame of mood state: each mood's animated weight (0..1) plus the looping motion values,
 * so several moods can blend while they cross-fade.
 */
private class MoodFrame(
    val idle: Float,
    val think: Float,
    val angry: Float,
    val confused: Float,
    val happy: Float,
    val tremble: Float,  // -1..1, fast jitter while thinking hard
    val tilt: Float,     // -1..1, slow head sway while confused
    val pulse: Float,    // 0..1, anger vein pulse
    val drop: Float,     // 0..1, sweat drop sliding down
    val orbit: Float,    // degrees, thinking dots circling the head
    val question: Float, // 0..1, "?" bobbing
    val sparkle: Float,  // 0..1, happy sparkle loop
)

/** One eye's place on the face, relative to the head centre in units of r, and its size. */
private class EyeSpot(val dx: Float, val dy: Float, val scale: Float = 1f)

/** Where each friend keeps its eyes: most have two, the odd ones have one, three or five. */
private fun eyeSpots(m: Mascot, cx: Float, cy: Float, r: Float, clock: Float): List<EyeSpot> = when (m.shape) {
    MascotShape.TRIANGLE, MascotShape.ONIGIRI -> listOf(EyeSpot(-0.3f, 0.32f), EyeSpot(0.3f, 0.32f))
    MascotShape.SPADE -> listOf(EyeSpot(-0.3f, -0.15f), EyeSpot(0.3f, -0.15f))
    MascotShape.HEART -> listOf(EyeSpot(-0.3f, -0.05f), EyeSpot(0.3f, -0.05f))
    MascotShape.CYCLOPS -> listOf(EyeSpot(0f, 0f, 2.2f))
    MascotShape.JELLY -> listOf(EyeSpot(-0.3f, -0.38f, 0.85f), EyeSpot(0.3f, -0.38f, 0.85f))
    MascotShape.SLIME -> listOf(EyeSpot(-0.32f, 0.02f), EyeSpot(0.32f, 0.02f))
    MascotShape.TRIEYE -> listOf(EyeSpot(-0.42f, 0.12f, 0.85f), EyeSpot(0.38f, 0.12f, 0.85f), EyeSpot(-0.02f, -0.45f, 0.95f))
    MascotShape.MUSHROOM -> listOf(EyeSpot(-0.22f, 0.35f, 0.7f), EyeSpot(0.22f, 0.35f, 0.7f))
    MascotShape.NOODLE -> {
        val (hx, hy) = noodleHead(cx, cy, r, clock)
        val dx = (hx - cx) / r
        val dy = (hy - cy) / r
        listOf(EyeSpot(dx - 0.17f, dy, 0.62f), EyeSpot(dx + 0.17f, dy, 0.62f))
    }
    MascotShape.MANYEYES -> listOf(
        EyeSpot(-0.45f, -0.38f, 0.7f),
        EyeSpot(0.38f, -0.48f, 0.9f),
        EyeSpot(-0.02f, 0.05f, 1.15f),
        EyeSpot(0.55f, 0.25f, 0.6f),
        EyeSpot(-0.55f, 0.42f, 0.55f),
    )
    MascotShape.TV -> listOf(EyeSpot(-0.42f, 0.05f, 0.8f), EyeSpot(0.12f, 0.05f, 0.8f))
    else -> listOf(EyeSpot(-0.3f, 0f), EyeSpot(0.3f, 0f))
}

/**
 * Capsule eyes, reshaped by the moods: thinking squints them, anger flattens and tilts them
 * into a frown, confusion shrinks the second one, and happy fades them into upward arcs. The
 * cyclops gets one big eye with a wandering pupil, and the many-eyed blob blinks eye by eye.
 */
private fun drawEyes(
    canvas: android.graphics.Canvas,
    m: Mascot,
    cx: Float,
    cy: Float,
    r: Float,
    blink: Float,
    glance: Float,
    frame: MoodFrame,
    paint: Paint,
    clock: Float,
    lookUp: Float,
) {
    val eyeW = r * 0.24f
    val wave = 2f * PI.toFloat() * clock
    eyeSpots(m, cx, cy, r, clock).forEachIndexed { k, spot ->
        val sc = spot.scale
        // The many-eyed blob blinks each eye at its own moment.
        val b = if (m.shape == MascotShape.MANYEYES) {
            val p = (clock + k * 0.29f) % 1f
            if (p < 0.05f) sin(PI.toFloat() * p / 0.05f) else 0f
        } else {
            blink
        }
        val ex = cx + spot.dx * r + glance * r * 0.12f
        val ey = cy + spot.dy * r - lookUp * r * 0.08f
        val squint = (1f - 0.85f * b) * (1f - 0.4f * frame.think) * (1f - 0.55f * frame.angry)
        val capsuleAlpha = 1f - frame.happy
        if (m.shape == MascotShape.CYCLOPS) {
            drawCyclopsEye(canvas, m, ex, ey, r, squint, glance, lookUp, wave, frame, capsuleAlpha, paint)
        } else if (capsuleAlpha > 0.01f) {
            // Anger widens the eyes a little; confusion makes the second eye smaller.
            val w = eyeW * sc * (1f + 0.3f * frame.angry)
            val h = r * 0.5f * sc * squint * (if (k == 1) 1f - 0.3f * frame.confused else 1f)
            // Anger tilts each bar so its inner end dips, like a frown.
            val side = if (abs(spot.dx) < 0.1f) 0f else if (spot.dx > 0f) 1f else -1f
            val angle = -side * 20f * frame.angry
            paint.fillRounded(m.eyes.copy(alpha = m.eyes.alpha * capsuleAlpha), 0f)
            canvas.save()
            canvas.rotate(angle, ex, ey)
            if (m.shape == MascotShape.FLOWER) {
                // The flower friend has little "+" eyes.
                canvas.drawRoundRect(RectF(ex - eyeW, ey - eyeW * 0.35f, ex + eyeW, ey + eyeW * 0.35f), eyeW, eyeW, paint)
                canvas.drawRoundRect(RectF(ex - eyeW * 0.35f, ey - eyeW, ex + eyeW * 0.35f, ey + eyeW), eyeW, eyeW, paint)
            } else {
                val rect = RectF(ex - w / 2, ey - h / 2, ex + w / 2, ey + h / 2)
                // Rounded ends stay within the bar, so flat (squinted or angry) bars still look like capsules.
                val corner = min(w, h) / 2
                canvas.drawRoundRect(rect, corner, corner, paint)
            }
            canvas.restore()
        }
        if (frame.happy > 0.01f) {
            // Happy: an upward "^" arc with round ends.
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = r * 0.11f * min(sc, 1.4f)
                color = m.eyes.copy(alpha = m.eyes.alpha * frame.happy).toArgb()
            }
            val half = eyeW * 0.9f * sc
            val arcPath = Path().apply {
                moveTo(ex - half, ey + eyeW * 0.25f * sc)
                quadTo(ex, ey - eyeW * 1.6f * sc, ex + half, ey + eyeW * 0.25f * sc)
            }
            canvas.drawPath(arcPath, arc)
        }
    }
}

/** The cyclops' single eye: a white with a pupil that wanders around, squints and frowns. */
private fun drawCyclopsEye(
    canvas: android.graphics.Canvas,
    m: Mascot,
    ex: Float,
    ey: Float,
    r: Float,
    squint: Float,
    glance: Float,
    lookUp: Float,
    wave: Float,
    frame: MoodFrame,
    alpha: Float,
    paint: Paint,
) {
    if (alpha < 0.01f) return
    val rw = r * 0.5f
    val rh = rw * squint.coerceAtLeast(0.08f)
    val white = Path().apply { addOval(RectF(ex - rw, ey - rh, ex + rw, ey + rh), Path.Direction.CW) }
    canvas.drawPath(white, paint.fillRounded(bone.copy(alpha = alpha), 0f))
    canvas.save()
    canvas.clipPath(white)
    // The pupil drifts in a slow loop (and follows glances), looking up when curious.
    val px = ex + (cos(wave) * 0.32f + glance) * r * 0.16f
    val py = ey + sin(wave * 2f) * r * 0.05f - lookUp * r * 0.12f
    val pr = r * 0.22f
    canvas.drawCircle(px, py, pr, paint.fillRounded(m.eyes.copy(alpha = alpha), 0f))
    canvas.drawCircle(px - pr * 0.35f, py - pr * 0.35f, pr * 0.28f, paint.fillRounded(Color.White.copy(alpha = alpha * 0.9f), 0f))
    canvas.restore()
    if (frame.angry > 0.01f) {
        // A single "V" brow pressing down on the eye.
        val brow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = r * 0.12f
            color = m.eyes.copy(alpha = frame.angry * alpha).toArgb()
        }
        canvas.drawPath(
            Path().apply {
                moveTo(ex - rw * 1.05f, ey - rw * 0.95f)
                lineTo(ex, ey - rw * 0.45f)
                lineTo(ex + rw * 1.05f, ey - rw * 0.95f)
            },
            brow,
        )
    }
}

/** Little mood props around the head: anger vein, "?", sweat drop, thinking dots and sparkles. */
private fun drawMoodProps(
    canvas: android.graphics.Canvas,
    m: Mascot,
    s: Float,
    cx: Float,
    cy: Float,
    r: Float,
    frame: MoodFrame,
    paint: Paint,
) {
    drawAngerVein(canvas, m, s, frame)
    drawQuestionMark(canvas, m, s, frame)
    drawSweatDrop(canvas, s, frame, paint)
    drawThinkingDots(canvas, m, s, cx, cy, r, frame, paint)
    drawSparkles(canvas, m, s, frame, paint)
}

/** Four short rounded strokes in an X with a gap in the middle, pulsing at the top-left corner. */
private fun drawAngerVein(canvas: android.graphics.Canvas, m: Mascot, s: Float, frame: MoodFrame) {
    if (frame.angry < 0.01f) return
    val x = s * 0.2f
    val y = s * 0.22f
    val u = s * 0.065f * (0.85f + 0.25f * frame.pulse)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = u * 0.4f
        color = m.decoColor.copy(alpha = frame.angry).toArgb()
    }
    listOf(45.0, 135.0, 225.0, 315.0).forEach { deg ->
        val rad = Math.toRadians(deg)
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        canvas.drawLine(x + ux * u * 0.45f, y + uy * u * 0.45f, x + ux * u, y + uy * u, stroke)
    }
}

/** A rounded "?" that bobs above the top-left corner while confused. */
private fun drawQuestionMark(canvas: android.graphics.Canvas, m: Mascot, s: Float, frame: MoodFrame) {
    if (frame.confused < 0.01f) return
    val u = s * 0.085f
    val x = s * 0.2f
    val y = s * 0.2f + (frame.question - 0.5f) * u * 0.5f
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = u * 0.4f
        color = m.decoColor.copy(alpha = frame.confused).toArgb()
    }
    val hook = Path().apply {
        moveTo(x - u * 0.5f, y - u * 0.45f)
        cubicTo(x - u * 0.5f, y - u * 1.25f, x + u * 0.5f, y - u * 1.25f, x + u * 0.5f, y - u * 0.4f)
        cubicTo(x + u * 0.5f, y, x, y + u * 0.05f, x, y + u * 0.45f)
    }
    canvas.drawPath(hook, ink)
    // The dot under the hook, in the same ink.
    ink.style = Paint.Style.FILL
    canvas.drawCircle(x, y + u * 0.95f, u * 0.2f, ink)
}

/** A sweat drop that slides down beside the head, fading in at the top and out at the bottom. */
private fun drawSweatDrop(canvas: android.graphics.Canvas, s: Float, frame: MoodFrame, paint: Paint) {
    val alpha = frame.think * sin(PI.toFloat() * frame.drop)
    if (alpha < 0.01f) return
    val d = s * 0.04f
    val x = s * 0.1f
    val y = s * (0.36f + 0.28f * frame.drop)
    val color = sweatBlue.copy(alpha = alpha)
    val tip = Path().apply {
        moveTo(x, y - 2f * d)
        lineTo(x + d * 0.9f, y - 0.2f * d)
        lineTo(x - d * 0.9f, y - 0.2f * d)
        close()
    }
    canvas.drawPath(tip, paint.fillRounded(color, d * 0.4f))
    canvas.drawCircle(x, y, d, paint.fillRounded(color, 0f))
}

/** Three small dots circling just above the head, like a strained thought. */
private fun drawThinkingDots(
    canvas: android.graphics.Canvas,
    m: Mascot,
    s: Float,
    cx: Float,
    cy: Float,
    r: Float,
    frame: MoodFrame,
    paint: Paint,
) {
    if (frame.think < 0.01f) return
    paint.fillRounded(m.decoColor.copy(alpha = frame.think), 0f)
    val d = s * 0.03f
    for (k in 0 until 3) {
        val rad = Math.toRadians((frame.orbit + k * 120f).toDouble())
        val x = cx + cos(rad).toFloat() * r * 0.95f
        val y = cy - r * 1.1f + sin(rad).toFloat() * r * 0.28f
        canvas.drawCircle(x, y, d, paint)
    }
}

/** Three sparkles, each on its own phase of the loop, so they pop in and out in turn. */
private fun drawSparkles(canvas: android.graphics.Canvas, m: Mascot, s: Float, frame: MoodFrame, paint: Paint) {
    if (frame.happy < 0.01f) return
    val spots = listOf(0.14f to 0.36f, 0.85f to 0.38f, 0.22f to 0.8f)
    spots.forEachIndexed { k, (x, y) ->
        // sin gives a smooth 0 -> 1 -> 0 pop for each sparkle.
        val size = s * 0.07f * frame.happy * sin(PI.toFloat() * ((frame.sparkle + k / 3f) % 1f))
        if (size > 0.5f) {
            canvas.drawPath(polygon(x * s, y * s, size, 4, inner = size * 0.35f), paint.fillRounded(m.decoColor, 0f))
        }
    }
}

/**
 * The little decoration by the top-right corner. It bobs gently, and most kinds turn a little back
 * and forth; the speed lines slide and the heart beats.
 */
private fun drawDecoration(canvas: android.graphics.Canvas, m: Mascot, s: Float, paint: Paint, clock: Float, life: Float) {
    val wave = 2f * PI.toFloat() * clock
    val x = s * 0.86f
    val y = s * 0.16f + sin(wave) * s * 0.02f * life
    val d = s * 0.075f
    val turn = sin(wave) * 12f * life
    paint.fillRounded(m.decoColor, 0f)
    canvas.save()
    canvas.rotate(turn, x, y)
    when (m.deco) {
        MascotDeco.DOT -> canvas.drawCircle(x, y, d, paint)
        MascotDeco.SPARKLE -> {
            val tw = 1f + 0.2f * life * twinkle(wave, 2f)
            canvas.scale(tw, tw, x, y)
            canvas.drawPath(polygon(x, y, d * 1.4f, 4, inner = d * 0.45f), paint)
        }
        MascotDeco.TRIANGLE -> canvas.drawPath(polygon(x, y, d * 1.2f, 3, rotation = -30f), paint.fillRounded(m.decoColor, d * 0.2f))
        MascotDeco.DIAMOND -> canvas.drawPath(polygon(x, y, d * 1.3f, 4), paint.fillRounded(m.decoColor, d * 0.35f))
        MascotDeco.HEX -> canvas.drawPath(polygon(x, y, d * 1.15f, 6, rotation = 0f), paint.fillRounded(m.decoColor, d * 0.25f))
        MascotDeco.PLUS -> {
            canvas.drawRoundRect(RectF(x - d * 1.2f, y - d * 0.4f, x + d * 1.2f, y + d * 0.4f), d, d, paint)
            canvas.drawRoundRect(RectF(x - d * 0.4f, y - d * 1.2f, x + d * 0.4f, y + d * 1.2f), d, d, paint)
        }
        MascotDeco.HEART -> {
            val beat = 1f + 0.12f * life * heartBeat(clock)
            canvas.scale(beat, beat, x, y)
            val h = Path().apply {
                moveTo(x, y + d)
                cubicTo(x - d * 1.7f, y, x - d * 0.75f, y - d * 1.35f, x, y - d * 0.45f)
                cubicTo(x + d * 0.75f, y - d * 1.35f, x + d * 1.7f, y, x, y + d)
                close()
            }
            canvas.drawPath(h, paint)
        }
        MascotDeco.SPEED -> {
            // The speed lines slide back and forth rather than turn.
            canvas.rotate(-turn, x, y)
            val sx = s * 0.8f + sin(wave) * d * 0.25f * life
            canvas.drawRoundRect(RectF(sx, s * 0.5f, sx + d * 2.2f, s * 0.5f + d * 0.6f), d, d, paint)
            canvas.drawRoundRect(RectF(sx + d * 0.6f, s * 0.6f, sx + d * 2.6f, s * 0.6f + d * 0.6f), d, d, paint)
        }
        MascotDeco.DASH -> {
            canvas.save()
            canvas.rotate(-60f, x, y + d)
            canvas.drawRoundRect(RectF(x - d * 1.4f, y, x + d * 1.4f, y + d * 0.7f), d, d, paint)
            canvas.restore()
        }
        MascotDeco.NONE -> Unit
    }
    canvas.restore()
}

