package dev.agentworkbench

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing

/**
 * Every looping motion of a mascot at time [ms], as pure functions: the same inputs always give
 * the same pose. Periods and keyframes are the ones the avatar always had.
 */
internal class MascotMotion(ms: Long, index: Int, seed: Int) {
    val seconds = (ms % 3_600_000L) / 1000f
    val blink = motionKeys(ms, 3_800 + (index % 5) * 300, 0, 3_400 to 0f, 3_480 to 1f, 3_600 to 0f)
    val breath = motionPingPong(ms, 2_800)
    val glance = motionKeys(
        ms, 7_000 + (index % 4) * 900, 0,
        2_000 to 0f, 2_250 to -1f, 2_900 to -1f, 3_150 to 0f, 5_200 to 1f, 5_700 to 1f, 5_950 to 0f,
    )
    val wobble = motionPingPong(ms, 560) * 2f - 1f
    val tremble = motionPingPong(ms, 110, LinearEasing) * 2f - 1f
    val tilt = motionPingPong(ms, 1_600) * 2f - 1f
    val question = motionPingPong(ms, 1_200)
    val shake = motionKeys(
        ms, 2_200, 0,
        0 to 0f, 70 to -1f, 140 to 1f, 210 to -1f, 280 to 1f, 340 to -0.5f, 400 to 0.5f, 460 to 0f,
    )
    val bounce = motionPingPong(ms, 420)
    val pulse = motionPingPong(ms, 520)
    val drop = motionLoop(ms, 1_500)
    val orbit = motionLoop(ms, 2_400) * 360f
    val sparkle = motionLoop(ms, 1_800)
    val clock = motionLoop(ms, 4_000, (seed * 397) % 4_000)
    val boing = motionKeys(
        ms, 9_000 + (seed % 6) * 700, (seed * 1_237) % 9_000,
        6_000 to 0f, 6_130 to 1f, 6_330 to -1f, 6_520 to 0.45f, 6_640 to -0.15f, 6_760 to 0f,
    )
    val curious = motionKeys(
        ms, 12_000 + (seed % 5) * 1_100, (seed * 2_113) % 12_000,
        3_000 to 0f, 3_350 to 1f, 4_700 to 1f, 5_050 to 0f,
    )
}

/** 0..1 sawtooth with the given period (ms), shifted by [offset]. */
internal fun motionLoop(ms: Long, period: Int, offset: Int = 0): Float =
    ((ms + offset).mod(period.toLong())).toFloat() / period

/** 0 → 1 → 0, each half lasting [period] ms and eased (like tween(period) with RepeatMode.Reverse). */
internal fun motionPingPong(ms: Long, period: Int, easing: Easing = FastOutSlowInEasing): Float {
    val p = motionLoop(ms, period * 2)
    val triangle = if (p < 0.5f) p * 2f else 2f - p * 2f
    return easing.transform(triangle)
}

/** Linear keyframes over one period (value 0 before the first key, the last key's value after it). */
internal fun motionKeys(ms: Long, period: Int, offset: Int, vararg keys: Pair<Int, Float>): Float {
    val t = (ms + offset).mod(period.toLong()).toInt()
    var previous = 0 to 0f
    for (key in keys) {
        if (t <= key.first) {
            val span = (key.first - previous.first).coerceAtLeast(1)
            val f = ((t - previous.first).toFloat() / span).coerceIn(0f, 1f)
            return previous.second + (key.second - previous.second) * f
        }
        previous = key
    }
    return previous.second
}
