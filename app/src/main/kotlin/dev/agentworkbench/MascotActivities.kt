package dev.agentworkbench

import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What a mascot is doing with its hands while it works. */
enum class MascotActivity {
    COOKING,      // little pan with a flipping item + steam
    MINING,       // pickaxe swinging, sparks/chips flying
    SEARCHING,    // magnifier sweeping with a glint
    LAPTOP,       // tiny laptop in front, screen glow + blinking cursor, typing bob
    PHONE,        // tiny phone held up, screen pulsing, thumb tap
    GLASSES,      // round glasses on the face, a hand pushing them up now and then
    SWEATING,     // big sweat drops + fanning itself (tired)
    READING,      // tiny open book, pages flipping
    WRITING,      // notepad + pencil scribbling
    PAINTING,     // brush with colored dabs
    MUSIC,        // headphones + floating notes
    LIFTING,      // dumbbell up/down
    WATERING,     // watering can pouring drops on a sprout
    TELESCOPE,    // telescope with twinkling star
    JUGGLING,     // three balls juggling
    BUILDING,     // hammer tapping a block
    CHEMISTRY,    // flask bubbling
    TYPING_FAST,  // keyboard with flying keys (for heavy coding)
}

/** The activity each of the 30 mascots does by default while it works. Index = position in [Mascots.all]. */
internal fun signatureActivity(index: Int): MascotActivity = activitySignatures[index.mod(activitySignatures.size)]

private val activitySignatures: List<MascotActivity> = listOf(
    MascotActivity.TELESCOPE,   // 0 PLANET: looks up at the sky
    MascotActivity.WRITING,     // 1 TRIANGLE
    MascotActivity.MUSIC,       // 2 CLOUD: headphones on a cloud
    MascotActivity.SEARCHING,   // 3 CYCLOPS: one big eye, one magnifier
    MascotActivity.CHEMISTRY,   // 4 SPARKLE
    MascotActivity.WATERING,    // 5 LEAF_BLOB: tends its sprout
    MascotActivity.READING,     // 6 GHOST
    MascotActivity.LAPTOP,      // 7 HEXAGON
    MascotActivity.PAINTING,    // 8 FLOWER
    MascotActivity.TELESCOPE,   // 9 MOON
    MascotActivity.PHONE,       // 10 EGG
    MascotActivity.MINING,      // 11 SPIKY
    MascotActivity.SWEATING,    // 12 CLOUD (cream): a tired cloud
    MascotActivity.GLASSES,     // 13 HEPTAGON
    MascotActivity.BUILDING,    // 14 KITE
    MascotActivity.PAINTING,    // 15 CLOVER
    MascotActivity.JUGGLING,    // 16 CAT
    MascotActivity.LIFTING,     // 17 JELLY
    MascotActivity.JUGGLING,    // 18 STAR
    MascotActivity.BUILDING,    // 19 GEAR
    MascotActivity.MUSIC,       // 20 HEART
    MascotActivity.SEARCHING,   // 21 PLANET (lavender)
    MascotActivity.COOKING,     // 22 ONIGIRI
    MascotActivity.WRITING,     // 23 SPADE
    MascotActivity.CHEMISTRY,   // 24 SLIME
    MascotActivity.READING,     // 25 TRIEYE
    MascotActivity.WATERING,    // 26 MUSHROOM
    MascotActivity.PHONE,       // 27 NOODLE
    MascotActivity.SWEATING,    // 28 MANYEYES: overwhelmed
    MascotActivity.TYPING_FAST, // 29 TV
)

/** What the current tool suggests the mascot is doing, or null when the tool says nothing. */
internal fun activityForTool(toolName: String?): MascotActivity? {
    val name = toolName?.trim()?.lowercase().orEmpty()
    if (name.isEmpty()) return null
    return when {
        name.startsWith("web_") || name.startsWith("browser_") ||
            name.startsWith("https_") || name.startsWith("http_") -> MascotActivity.SEARCHING
        name.startsWith("workspace_") || name.startsWith("file_") ||
            name.startsWith("code_") || name.startsWith("git_") -> MascotActivity.LAPTOP
        name.startsWith("terminal") || name.startsWith("android_shell") ||
            name.startsWith("python_") -> MascotActivity.TYPING_FAST
        name.startsWith("accessibility_") || name.startsWith("app_open") ||
            name.startsWith("whatsapp_send") || name.startsWith("sms_") ||
            name.startsWith("phone_") || name.startsWith("android_device") -> MascotActivity.PHONE
        name.startsWith("notebook_") -> MascotActivity.WRITING
        name.startsWith("calendar_") || name.startsWith("routine_") -> MascotActivity.READING
        name.startsWith("image") || name.startsWith("ocr") -> MascotActivity.PAINTING
        name.startsWith("build") || name.startsWith("gradle") -> MascotActivity.BUILDING
        else -> null
    }
}

/**
 * Draws the activity around the mascot for one frame. `t` is a shared clock in seconds (same for every avatar, so the same mascot animates identically everywhere); derive all motion from it (e.g. sin(t * 2π / period + phase)). `weight` 0..1 fades the whole activity in/out (multiply alphas and scale by it). `accent` is the mascot's accent colour; `ink` its eye colour (dark or light) for outlines/contrast. Draw on top of the mascot (props in front, near the lower-right/lower-front of the head, a few may sit on the head like headphones/glasses).
 */
internal fun drawMascotActivity(
    canvas: android.graphics.Canvas,
    activity: MascotActivity,
    s: Float, cx: Float, cy: Float, r: Float,
    t: Float,
    weight: Float,
    accent: Color,
    ink: Color,
    paint: Paint,
) {
    if (weight <= 0.001f) return
    paint.isAntiAlias = true
    val f = ActivityFrame(canvas, paint, s, cx, cy, r, t, weight.coerceIn(0f, 1f), accent, ink)
    canvas.save()
    // Props grow in from the head as they fade in, and shrink back into it as they fade out.
    val grow = 0.5f + 0.5f * f.w
    canvas.scale(grow, grow, cx, cy)
    when (activity) {
        MascotActivity.COOKING -> activityCooking(f)
        MascotActivity.MINING -> activityMining(f)
        MascotActivity.SEARCHING -> activitySearching(f)
        MascotActivity.LAPTOP -> activityLaptop(f)
        MascotActivity.PHONE -> activityPhone(f)
        MascotActivity.GLASSES -> activityGlasses(f)
        MascotActivity.SWEATING -> activitySweating(f)
        MascotActivity.READING -> activityReading(f)
        MascotActivity.WRITING -> activityWriting(f)
        MascotActivity.PAINTING -> activityPainting(f)
        MascotActivity.MUSIC -> activityMusic(f)
        MascotActivity.LIFTING -> activityLifting(f)
        MascotActivity.WATERING -> activityWatering(f)
        MascotActivity.TELESCOPE -> activityTelescope(f)
        MascotActivity.JUGGLING -> activityJuggling(f)
        MascotActivity.BUILDING -> activityBuilding(f)
        MascotActivity.CHEMISTRY -> activityChemistry(f)
        MascotActivity.TYPING_FAST -> activityTypingFast(f)
    }
    canvas.restore()
}

// ---- Palette and shared constants ----------------------------------------------------------

private const val ACTIVITY_TAU = 6.2831855f
private const val ACTIVITY_PI = 3.1415927f

/** The plate behind every friend. Props get this colour as their edge so they read on any head. */
private val activityPlate = Color(0xFF14151C)
private val activityBone = Color(0xFFF8F3EA)
private val activityMist = Color(0xFFE6EAF2)
private val activitySteel = Color(0xFFC7CEDB)
private val activitySteelDark = Color(0xFF5B6478)
private val activitySlate = Color(0xFF3A4256)
private val activityInk = Color(0xFF2B2C36)
private val activityWood = Color(0xFFB5763A)
private val activityTan = Color(0xFFE0A96D)
private val activitySun = Color(0xFFFFC857)
private val activityCoral = Color(0xFFFF6B6B)
private val activitySky = Color(0xFF38BDF8)
private val activityBlue = Color(0xFF3B9CFF)
private val activityLeaf = Color(0xFF22C55E)
private val activityRock = Color(0xFF8A93A6)

private val activityDabX = floatArrayOf(0.36f, 0.54f, 0.68f, 0.8f)
private val activityDabY = floatArrayOf(0.74f, 0.8f, 0.7f, 0.84f)
private val activityDabColors = listOf(activityCoral, activitySun, activitySky, activityLeaf)
private val activityBubbleX = floatArrayOf(0.6f, 0.7f, 0.8f, 0.66f)

// ---- Geometry helpers -----------------------------------------------------------------------

private fun activityFract(x: Float): Float = x - floor(x)

private fun activityLine(x1: Float, y1: Float, x2: Float, y2: Float): Path =
    Path().apply {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }

private fun activityDiamond(x: Float, y: Float, rad: Float): Path =
    Path().apply {
        moveTo(x, y - rad)
        lineTo(x + rad, y)
        lineTo(x, y + rad)
        lineTo(x - rad, y)
        close()
    }

/** A four-point sparkle: eight points, long ones on the cardinal directions. */
private fun activityStarPath(x: Float, y: Float, rad: Float): Path =
    Path().apply {
        for (i in 0 until 8) {
            val a = (-90f + i * 45f) * ACTIVITY_PI / 180f
            val rr = if (i % 2 == 0) rad else rad * 0.4f
            val px = x + cos(a) * rr
            val py = y + sin(a) * rr
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }

/** A teardrop whose round bottom is centred on (x, y) and whose point rises to 2.1 radii above. */
private fun activityDropPath(x: Float, y: Float, rad: Float): Path =
    Path().apply {
        moveTo(x, y - 2.1f * rad)
        cubicTo(x + 0.3f * rad, y - 1.2f * rad, x + rad, y - 0.6f * rad, x + rad, y)
        arcTo(RectF(x - rad, y - rad, x + rad, y + rad), 0f, 180f, false)
        cubicTo(x - rad, y - 0.6f * rad, x - 0.3f * rad, y - 1.2f * rad, x, y - 2.1f * rad)
        close()
    }

/** A fan-shaped sector from (x, y) out to [rad], spanning [sweep] degrees from [start]. */
private fun activityFanPath(x: Float, y: Float, rad: Float, start: Float, sweep: Float): Path =
    Path().apply {
        moveTo(x, y)
        val a0 = start * ACTIVITY_PI / 180f
        lineTo(x + rad * cos(a0), y + rad * sin(a0))
        arcTo(RectF(x - rad, y - rad, x + rad, y + rad), start, sweep, false)
        close()
    }

// ---- Drawing context ------------------------------------------------------------------------

/**
 * Everything one frame of an activity needs. Positions are given relative to the head centre in
 * units of the head radius ([x], [y]); the paint is shared and set fresh by every call.
 */
private class ActivityFrame(
    val canvas: android.graphics.Canvas,
    val paint: Paint,
    val s: Float,
    val cx: Float,
    val cy: Float,
    val r: Float,
    val t: Float,
    val w: Float,
    val accent: Color,
    val ink: Color,
) {
    private val edgeWidth: Float get() = s * 0.05f

    /** Horizontal position, in head radii from the head centre. */
    fun x(k: Float): Float = cx + k * r

    /** Vertical position, in head radii from the head centre. */
    fun y(k: Float): Float = cy + k * r

    /** Colour as ARGB, with its alpha scaled by [a] and by the whole activity's weight. */
    private fun argb(c: Color, a: Float): Int = c.copy(alpha = (c.alpha * a * w).coerceIn(0f, 1f)).toArgb()

    private fun fill(c: Color, a: Float, corner: Float = 0f): Paint {
        paint.style = Paint.Style.FILL
        paint.color = argb(c, a)
        paint.pathEffect = if (corner > 0f) CornerPathEffect(corner) else null
        return paint
    }

    private fun stroke(width: Float, c: Color, a: Float, corner: Float = 0f): Paint {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = argb(c, a)
        paint.pathEffect = if (corner > 0f) CornerPathEffect(corner) else null
        return paint
    }

    private fun edge(width: Float, a: Float, corner: Float = 0f): Paint = stroke(width, activityPlate, a, corner)

    /** Flat fill with no edge, for glass, glows and dabs. */
    fun flatPath(path: Path, c: Color, a: Float = 1f) = canvas.drawPath(path, fill(c, a))

    fun flatRect(rect: RectF, rx: Float, c: Color, a: Float = 1f) = canvas.drawRoundRect(rect, rx, rx, fill(c, a))

    fun flatCircle(x: Float, y: Float, rad: Float, c: Color, a: Float = 1f) = canvas.drawCircle(x, y, rad, fill(c, a))

    fun flatStroke(path: Path, width: Float, c: Color, a: Float = 1f) = canvas.drawPath(path, stroke(width, c, a))

    /** A flat sticker: a thin plate-coloured edge, then the fill. */
    fun stickPath(path: Path, c: Color, a: Float = 1f) {
        val corner = s * 0.012f
        canvas.drawPath(path, edge(edgeWidth, a, corner))
        canvas.drawPath(path, fill(c, a, corner))
    }

    /** A stroked path with the same plate-coloured edge around it. */
    fun stickStroke(path: Path, width: Float, c: Color, a: Float = 1f) {
        canvas.drawPath(path, edge(width + edgeWidth, a))
        canvas.drawPath(path, stroke(width, c, a))
    }

    fun stickLine(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, c: Color, a: Float = 1f) =
        stickStroke(activityLine(x1, y1, x2, y2), width, c, a)

    fun stickRect(rect: RectF, rx: Float, c: Color, a: Float = 1f) {
        canvas.drawRoundRect(rect, rx, rx, edge(edgeWidth, a))
        canvas.drawRoundRect(rect, rx, rx, fill(c, a))
    }

    fun stickOval(rect: RectF, c: Color, a: Float = 1f) {
        canvas.drawOval(rect, edge(edgeWidth, a))
        canvas.drawOval(rect, fill(c, a))
    }

    fun stickCircle(x: Float, y: Float, rad: Float, c: Color, a: Float = 1f) {
        canvas.drawCircle(x, y, rad, edge(edgeWidth, a))
        canvas.drawCircle(x, y, rad, fill(c, a))
    }
}

// ---- The 18 activities ----------------------------------------------------------------------

/** Pan on the lower right with an item flipping in it, and three puffs of steam. */
private fun activityCooking(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    for (k in 0 until 3) {
        val ph = activityFract(f.t / 2f + k / 3f)
        val a = sin(ACTIVITY_PI * ph) * 0.85f
        f.flatCircle(
            f.x(0.42f + (k - 1) * 0.24f + 0.07f * sin(ACTIVITY_TAU * ph * 2f + k)),
            f.y(0.4f - 0.95f * ph),
            (0.09f + 0.07f * ph) * r,
            activityMist,
            a,
        )
    }
    val px = f.x(0.42f)
    val py = f.y(0.72f)
    val tilt = -6f + 5f * sin(ACTIVITY_TAU * f.t / 1.6f)
    canvas.save()
    canvas.rotate(tilt, px, py)
    f.stickLine(px + 0.3f * r, py - 0.04f * r, px + 0.82f * r, py - 0.1f * r, 0.14f * r, activityWood)
    f.stickOval(RectF(px - 0.36f * r, py - 0.2f * r, px + 0.36f * r, py + 0.2f * r), activitySteel)
    val p = activityFract(f.t / 1.6f)
    val toss = 4f * p * (1f - p)
    val flip = max(0.18f, abs(cos(ACTIVITY_TAU * p)))
    val ix = px + 0.02f * r
    val iy = py - 0.2f * r - 0.9f * r * toss
    f.stickOval(RectF(ix - 0.2f * r * flip, iy - 0.18f * r, ix + 0.2f * r * flip, iy + 0.18f * r), activitySun)
    canvas.restore()
}

/** A pickaxe swings down from the top right into a rock with a gem, and chips fly off each strike. */
private fun activityMining(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    val period = 1.2f
    val q = activityFract(f.t / period)
    val z = 0.5f - 0.5f * cos(ACTIVITY_TAU * q)
    val strike = z * z * z
    val ox = f.x(0.66f)
    val oy = f.y(0.7f)
    f.stickOval(RectF(ox - 0.32f * r, oy - 0.24f * r, ox + 0.32f * r, oy + 0.24f * r), activityRock)
    f.stickPath(activityDiamond(ox + 0.04f * r, oy - 0.04f * r, 0.12f * r), f.accent)
    // Chips fly up and out just after each strike, then fall away.
    val age = activityFract(q - 0.5f)
    if (age < 0.35f) {
        val secs = age * period
        val fade = 1f - age / 0.35f
        for (k in 0 until 4) {
            val ang = (-150f + k * 40f) * ACTIVITY_PI / 180f
            val x = ox + 0.12f * r + cos(ang) * 1.6f * r * secs
            val y = oy - 0.1f * r + sin(ang) * 1.6f * r * secs + 1.5f * r * secs * secs
            val c = if (k % 2 == 0) f.accent else activityBone
            canvas.save()
            canvas.rotate(secs * 900f, x, y)
            f.stickRect(RectF(x - 0.05f * r, y - 0.05f * r, x + 0.05f * r, y + 0.05f * r), 0.02f * r, c, fade)
            canvas.restore()
        }
    }
    // The pick: handle, head across its end, and the hand at the top.
    val pvx = f.x(1.0f)
    val pvy = f.y(-0.5f)
    val ang = (76.6f + (106.6f - 76.6f) * strike) * ACTIVITY_PI / 180f
    val dx = cos(ang)
    val dy = sin(ang)
    val hx = pvx + 0.98f * r * dx
    val hy = pvy + 0.98f * r * dy
    f.stickLine(pvx, pvy, hx, hy, 0.12f * r, activityWood)
    f.stickLine(hx - dy * 0.18f * r, hy + dx * 0.18f * r, hx + dy * 0.18f * r, hy - dx * 0.18f * r, 0.13f * r, activitySteel)
    f.stickCircle(pvx, pvy, 0.13f * r, activityBone)
}

/** A magnifier on the lower right sweeps back and forth, with a glint riding on the glass. */
private fun activitySearching(f: ActivityFrame) {
    val r = f.r
    val sweep = ACTIVITY_TAU * f.t / 2.4f
    val lx = f.x(0.4f + 0.14f * sin(sweep))
    val ly = f.y(0.4f + 0.07f * sin(2f * sweep))
    val lens = 0.36f * r
    val d = 0.7071f
    f.stickLine(lx + lens * d, ly + lens * d, lx + 0.7f * r * d, ly + 0.7f * r * d, 0.16f * r, activityWood)
    f.flatCircle(lx, ly, lens, activitySky, 0.4f)
    f.stickStroke(Path().apply { addCircle(lx, ly, lens, Path.Direction.CW) }, 0.14f * r, activitySteel)
    val glint = 0.4f + 0.45f * (0.5f + 0.5f * sin(sweep + 1f))
    f.flatStroke(
        Path().apply { addArc(RectF(lx - 0.22f * r, ly - 0.22f * r, lx + 0.22f * r, ly + 0.22f * r), 200f, 55f) },
        0.07f * r,
        activityMist,
        glint,
    )
}

/** A small laptop in front of the lower face: the screen glows in the accent, text grows, a cursor blinks. */
private fun activityLaptop(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    // The whole laptop jiggles a little, as if the keys are going.
    canvas.save()
    canvas.translate(0f, 0.025f * r * sin(ACTIVITY_TAU * f.t / 0.5f))
    f.stickRect(RectF(f.x(-0.02f), f.y(0.36f), f.x(0.62f), f.y(0.78f)), 0.05f * r, activitySlate)
    val glow = 0.7f + 0.3f * sin(ACTIVITY_TAU * f.t)
    f.flatRect(RectF(f.x(0.04f), f.y(0.42f), f.x(0.58f), f.y(0.72f)), 0.03f * r, f.accent, glow)
    val p1 = activityFract(f.t / 1.6f)
    val p2 = activityFract(f.t / 1.6f + 0.5f)
    f.flatRect(RectF(f.x(0.1f), f.y(0.47f), f.x(0.1f + 0.44f * p1), f.y(0.53f)), 0.03f * r, activityInk, 0.85f)
    val end2 = 0.1f + 0.4f * p2
    f.flatRect(RectF(f.x(0.1f), f.y(0.59f), f.x(end2), f.y(0.65f)), 0.03f * r, activityInk, 0.85f)
    if (activityFract(f.t) < 0.5f) {
        f.flatRect(RectF(f.x(end2 + 0.05f), f.y(0.56f), f.x(end2 + 0.08f), f.y(0.68f)), 0f, activityInk)
    }
    f.stickRect(RectF(f.x(-0.2f), f.y(0.8f), f.x(0.72f), f.y(0.9f)), 0.05f * r, activitySteel)
    canvas.restore()
}

/** A small phone held up on the right: the screen pulses and a thumb taps it. */
private fun activityPhone(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    val px = f.x(0.66f)
    val py = f.y(0.46f)
    val tap = 0.5f - 0.5f * cos(ACTIVITY_TAU * f.t / 1.1f)
    // The hand under the phone goes first, so the phone sits on top of it.
    f.stickCircle(px - 0.12f * r, py + 0.42f * r, 0.14f * r, activityBone)
    canvas.save()
    canvas.rotate(-14f, px, py)
    f.stickRect(RectF(px - 0.2f * r, py - 0.35f * r, px + 0.2f * r, py + 0.35f * r), 0.07f * r, activitySlate)
    f.flatRect(RectF(px - 0.15f * r, py - 0.27f * r, px + 0.15f * r, py + 0.2f * r), 0.03f * r, f.accent, 0.55f + 0.45f * tap)
    f.flatRect(RectF(px - 0.1f * r, py - 0.2f * r, px + 0.06f * r, py - 0.15f * r), 0.025f * r, activityBone, 0.8f)
    val thumbY = py + 0.14f * r - 0.08f * r * tap
    f.stickOval(RectF(px + 0.06f * r - 0.11f * r, thumbY - 0.08f * r, px + 0.06f * r + 0.11f * r, thumbY + 0.08f * r), activityBone)
    canvas.restore()
}

/** Round glasses on the face. Every so often a hand comes up from the lower right and pushes them up. */
private fun activityGlasses(f: ActivityFrame) {
    val r = f.r
    val p = activityFract(f.t / 3.2f)
    val e = sin(ACTIVITY_PI * ((p - 0.08f) / 0.3f).coerceIn(0f, 1f))
    val lift = 0.14f * e
    val gy = -lift
    f.flatCircle(f.x(-0.3f), f.y(gy), 0.26f * r, activityBone, 0.2f)
    f.flatCircle(f.x(0.3f), f.y(gy), 0.26f * r, activityBone, 0.2f)
    f.stickStroke(Path().apply { addCircle(f.x(-0.3f), f.y(gy), 0.26f * r, Path.Direction.CW) }, 0.12f * r, f.ink)
    f.stickStroke(Path().apply { addCircle(f.x(0.3f), f.y(gy), 0.26f * r, Path.Direction.CW) }, 0.12f * r, f.ink)
    f.stickLine(f.x(-0.04f), f.y(gy), f.x(0.04f), f.y(gy), 0.1f * r, f.ink)
    f.stickLine(f.x(-0.56f), f.y(gy), f.x(-0.9f), f.y(gy - 0.06f), 0.09f * r, f.ink)
    f.stickLine(f.x(0.56f), f.y(gy), f.x(0.9f), f.y(gy - 0.06f), 0.09f * r, f.ink)
    // The hand travels from the lower right to the bridge and back.
    val hx = 0.9f * (1f - e)
    val hy = 0.95f + (-0.1f - lift - 0.95f) * e
    f.stickCircle(f.x(hx), f.y(hy), 0.15f * r, activityBone, (e * 3f).coerceIn(0f, 1f))
}

/** Big drops run down both sides of the head while a fan flaps in front of the face. */
private fun activitySweating(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    for (k in 0 until 2) {
        val ph = activityFract(f.t / 1.8f + k * 0.5f)
        val dxk = if (k == 0) 1.08f else -0.98f
        val a = sin(ACTIVITY_PI * ph)
        f.stickPath(activityDropPath(f.x(dxk), f.y(-0.7f + 1.2f * ph), 0.16f * r), activitySky, a)
    }
    val ox = f.x(0.7f)
    val oy = f.y(0.74f)
    val swing = 22f * sin(ACTIVITY_TAU * f.t / 0.9f)
    canvas.save()
    canvas.rotate(swing, ox, oy)
    f.stickLine(ox, oy, ox + 0.1f * r, oy + 0.22f * r, 0.1f * r, activityWood)
    f.stickPath(activityFanPath(ox, oy, 0.5f * r, -150f, 80f), activityBone)
    f.stickPath(activityFanPath(ox, oy, 0.26f * r, -150f, 80f), activityCoral)
    f.stickCircle(ox + 0.1f * r, oy + 0.26f * r, 0.12f * r, activityBone)
    canvas.restore()
}

/** A tiny open book; one page turns over the spine again and again. */
private fun activityReading(f: ActivityFrame) {
    val r = f.r
    val spine = f.x(0.56f)
    val top = f.y(0.66f)
    val bottom = f.y(0.92f)
    f.stickRect(RectF(f.x(0.08f), f.y(0.6f), f.x(1.04f), f.y(0.96f)), 0.05f * r, activityBlue)
    f.flatRect(RectF(f.x(0.12f), top, spine - 0.02f * r, bottom), 0.03f * r, activityBone)
    f.flatRect(RectF(spine + 0.02f * r, top, f.x(1.0f), bottom), 0.03f * r, activityBone)
    // The turning page swings from the right side over to the left and back.
    val fx = cos(ACTIVITY_TAU * activityFract(f.t / 1.6f))
    val reach = 0.42f * r * fx
    f.flatRect(RectF(min(spine, spine + reach), top, max(spine, spine + reach), bottom), 0.03f * r, activitySteel)
}

/** A notepad on the lower right: a pencil scribbles a zigzag line, then the page starts again. */
private fun activityWriting(f: ActivityFrame) {
    val r = f.r
    f.stickRect(RectF(f.x(0.3f), f.y(0.58f), f.x(0.98f), f.y(0.98f)), 0.05f * r, activityBone)
    f.flatRect(RectF(f.x(0.3f), f.y(0.58f), f.x(0.98f), f.y(0.7f)), 0.05f * r, activityCoral)
    val q = activityFract(f.t / 2.4f)
    val shown = min(1f, q / 0.8f)
    val fade = ((1f - q) * 5f).coerceIn(0f, 1f)
    val n = 7
    fun px(i: Int): Float = f.x(0.4f + i * 0.08f)
    fun py(i: Int): Float = f.y(if (i % 2 == 0) 0.77f else 0.91f)
    val seg = shown * (n - 1)
    val full = floor(seg).toInt().coerceAtMost(n - 1)
    val frac = seg - full
    val scribble = Path()
    scribble.moveTo(px(0), py(0))
    for (i in 1..full) scribble.lineTo(px(i), py(i))
    var tipX = px(full)
    var tipY = py(full)
    if (full < n - 1 && frac > 0f) {
        tipX = px(full) + (px(full + 1) - px(full)) * frac
        tipY = py(full) + (py(full + 1) - py(full)) * frac
        scribble.lineTo(tipX, tipY)
    }
    f.flatStroke(scribble, 0.09f * r, activityInk, fade)
    val pencilAlpha = fade * min(1f, shown * 6f)
    f.stickLine(tipX + 0.05f * r, tipY - 0.05f * r, tipX + 0.36f * r, tipY - 0.36f * r, 0.16f * r, activitySun, pencilAlpha)
    f.stickCircle(tipX, tipY, 0.05f * r, activityInk, pencilAlpha)
}

/** A small canvas on the lower right: a brush dabs four colours on it, one after another. */
private fun activityPainting(f: ActivityFrame) {
    val r = f.r
    f.stickRect(RectF(f.x(0.2f), f.y(0.6f), f.x(0.92f), f.y(0.94f)), 0.06f * r, activityBone)
    val q = activityFract(f.t / 2.4f)
    val fade = ((1f - q) * 8f).coerceIn(0f, 1f)
    for (k in 0 until 4) {
        val pop = ((q * 4f - k - 0.2f) / 0.2f).coerceIn(0f, 1f)
        if (pop > 0f) {
            val rad = 0.11f * r * (1f - (1f - pop) * (1f - pop))
            f.flatCircle(f.x(activityDabX[k]), f.y(activityDabY[k]), rad, activityDabColors[k], fade)
        }
    }
    // The brush hovers over the current spot, then presses down onto it.
    val k = min(3, floor(q * 4f).toInt())
    val local = activityFract(q * 4f)
    val press = sin(ACTIVITY_PI * (local / 0.4f).coerceIn(0f, 1f))
    val tx = f.x(activityDabX[k])
    val ty = f.y(activityDabY[k]) - 0.16f * r + 0.16f * r * press
    f.stickLine(tx + 0.1f * r, ty - 0.2f * r, tx + 0.5f * r, ty - 0.8f * r, 0.13f * r, activityWood)
    f.stickCircle(tx, ty, 0.08f * r, f.accent)
}

/** A note sprite: a round head on a stem with a flag. */
private fun activityNote(f: ActivityFrame, x: Float, y: Float, a: Float) {
    val r = f.r
    f.stickCircle(x, y, 0.1f * r, activitySun, a)
    f.stickLine(x + 0.09f * r, y, x + 0.09f * r, y - 0.42f * r, 0.07f * r, activitySun, a)
    f.stickLine(x + 0.09f * r, y - 0.42f * r, x + 0.24f * r, y - 0.3f * r, 0.08f * r, activitySun, a)
}

/** Headphones on the head, bobbing, with three notes floating up from the right. */
private fun activityMusic(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    canvas.save()
    canvas.translate(0f, 0.03f * r * sin(ACTIVITY_TAU * f.t))
    val band = Path().apply { addArc(RectF(f.x(-1.08f), f.y(-1.08f), f.x(1.08f), f.y(1.08f)), 200f, 140f) }
    f.stickStroke(band, 0.12f * r, f.accent)
    f.stickRect(RectF(f.x(-1.23f), f.y(-0.45f), f.x(-0.93f), f.y(0.05f)), 0.12f * r, f.accent)
    f.stickRect(RectF(f.x(0.93f), f.y(-0.45f), f.x(1.23f), f.y(0.05f)), 0.12f * r, f.accent)
    canvas.restore()
    for (k in 0 until 3) {
        val ph = activityFract(f.t / 2.4f + k / 3f)
        val a = sin(ACTIVITY_PI * ph)
        activityNote(f, f.x(1.0f + 0.18f * sin(ACTIVITY_TAU * ph + k)), f.y(0.2f - 1.15f * ph), a)
    }
}

/** A dumbbell held in front of the lower right, going up and down, with a hand on each end. */
private fun activityLifting(f: ActivityFrame) {
    val r = f.r
    val lift = 0.5f - 0.5f * cos(ACTIVITY_TAU * f.t / 1.6f)
    val y = f.y(0.66f - 0.42f * lift)
    f.stickLine(f.x(0.25f), y, f.x(1.05f), y, 0.11f * r, activitySteel)
    f.stickRect(RectF(f.x(0.16f), y - 0.22f * r, f.x(0.32f), y + 0.22f * r), 0.05f * r, activityCoral)
    f.stickRect(RectF(f.x(0.98f), y - 0.22f * r, f.x(1.14f), y + 0.22f * r), 0.05f * r, activityCoral)
    f.stickCircle(f.x(0.24f), y, 0.08f * r, activityBone)
    f.stickCircle(f.x(1.06f), y, 0.08f * r, activityBone)
}

/** A watering can tips over its spout onto a sprout at the lower right; drops fall onto the sprout. */
private fun activityWatering(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    val p = activityFract(f.t / 2f)
    val pour = sin(ACTIVITY_PI * ((p - 0.15f) / 0.5f).coerceIn(0f, 1f))
    // The sprout, growing a touch with every watering.
    val sx = f.x(0.68f)
    val base = f.y(1.0f)
    val top = f.y(0.9f) - 0.06f * r * pour
    f.stickLine(sx, base, sx, top, 0.1f * r, activityLeaf)
    val lx = sx - 0.12f * r
    val ly = top + 0.02f * r
    canvas.save()
    canvas.rotate(-35f, lx, ly)
    f.stickOval(RectF(lx - 0.13f * r, ly - 0.07f * r, lx + 0.13f * r, ly + 0.07f * r), activityLeaf)
    canvas.restore()
    val rx = sx + 0.12f * r
    canvas.save()
    canvas.rotate(35f, rx, ly)
    f.stickOval(RectF(rx - 0.13f * r, ly - 0.07f * r, rx + 0.13f * r, ly + 0.07f * r), activityLeaf)
    canvas.restore()
    // The can, turning about its bottom right corner.
    val px = f.x(0.42f)
    val py = f.y(0.5f)
    val theta = 80f * pour
    val rad = theta * ACTIVITY_PI / 180f
    canvas.save()
    canvas.rotate(theta, px, py)
    f.stickStroke(
        Path().apply { addArc(RectF(px - 0.34f * r, py - 0.46f * r, px - 0.06f * r, py - 0.18f * r), 180f, 180f) },
        0.08f * r,
        activitySky,
    )
    f.stickRect(RectF(px - 0.4f * r, py - 0.32f * r, px, py), 0.07f * r, activitySky)
    f.stickLine(px - 0.02f * r, py - 0.06f * r, px + 0.26f * r, py - 0.22f * r, 0.1f * r, activitySky)
    f.stickCircle(px + 0.26f * r, py - 0.22f * r, 0.07f * r, activitySky)
    canvas.restore()
    // Water runs out of the spout tip and falls onto the sprout.
    val tipX = px + (0.26f * cos(rad) + 0.22f * sin(rad)) * r
    val tipY = py + (0.26f * sin(rad) - 0.22f * cos(rad)) * r
    for (k in 0 until 3) {
        val ph = activityFract(f.t / 0.8f + k / 3f)
        val dropY = tipY + (top - tipY) * ph * ph
        f.stickCircle(tipX, dropY, 0.07f * r, activitySky, (pour * 2.5f).coerceIn(0f, 1f) * (1f - ph))
    }
}

/** A telescope on a stand, its tube swinging a little, aimed at a star that twinkles at the end of the view. */
private fun activityTelescope(f: ActivityFrame) {
    val r = f.r
    val px = f.x(0.42f)
    val py = f.y(0.82f)
    f.stickRect(RectF(px - 0.2f * r, py + 0.14f * r, px + 0.2f * r, py + 0.22f * r), 0.04f * r, activitySlate)
    f.stickLine(px, py, px, py + 0.16f * r, 0.1f * r, activitySteel)
    val aim = (-55f + 3f * sin(ACTIVITY_TAU * f.t / 2.4f)) * ACTIVITY_PI / 180f
    val tx = px + cos(aim) * 0.8f * r
    val ty = py + sin(aim) * 0.8f * r
    f.stickLine(px, py, tx, ty, 0.24f * r, activitySteel)
    f.stickCircle(tx, ty, 0.12f * r, activitySun)
    val tw = 0.5f + 0.5f * sin(ACTIVITY_TAU * f.t / 1.2f)
    f.stickPath(activityStarPath(f.x(1.2f), f.y(-0.3f), (0.14f + 0.07f * tw) * r), activitySun)
    f.flatCircle(f.x(1.05f), f.y(-0.62f), 0.05f * r, activitySun, 1f - tw)
    f.flatCircle(f.x(1.38f), f.y(0.0f), 0.05f * r, activitySun, tw)
}

/** Three balls thrown from one hand to the other and back again, over the right side of the head. */
private fun activityJuggling(f: ActivityFrame) {
    val r = f.r
    val left = 0.2f
    val right = 1.0f
    val base = 0.9f
    f.stickCircle(f.x(left), f.y(base), 0.11f * r, activityBone)
    f.stickCircle(f.x(right), f.y(base), 0.11f * r, activityBone)
    for (k in 0 until 3) {
        val u = activityFract(f.t / 1.2f + k / 3f)
        val v = if (u < 0.5f) u * 2f else (u - 0.5f) * 2f
        val bx = if (u < 0.5f) left + (right - left) * v else right - (right - left) * v
        val by = base - 1.0f * sin(ACTIVITY_PI * v)
        val c = when (k) {
            0 -> activitySun
            1 -> activityCoral
            else -> activitySky
        }
        f.stickCircle(f.x(bx), f.y(by), 0.13f * r, c)
    }
}

/** A hammer swings from the top right and taps a block on the lower right; dust puffs on each hit. */
private fun activityBuilding(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    val q = activityFract(f.t / 0.9f)
    val z = 0.5f - 0.5f * cos(ACTIVITY_TAU * q)
    val strike = z * z * z * z
    val hitAge = activityFract(q - 0.5f)
    val squash = (1f - hitAge / 0.3f).coerceAtLeast(0f) * 0.04f * r
    f.stickRect(RectF(f.x(0.49f), f.y(0.5f) + squash, f.x(0.95f), f.y(0.97f)), 0.06f * r, activityTan)
    val pvx = f.x(0.95f)
    val pvy = f.y(-0.5f)
    val ang = (76.6f + (106.6f - 76.6f) * strike) * ACTIVITY_PI / 180f
    val hx = pvx + 0.98f * r * cos(ang)
    val hy = pvy + 0.98f * r * sin(ang)
    f.stickLine(pvx, pvy, hx, hy, 0.12f * r, activityWood)
    canvas.save()
    canvas.rotate(ang * 180f / ACTIVITY_PI + 90f, hx, hy)
    f.stickRect(RectF(hx - 0.2f * r, hy - 0.08f * r, hx + 0.2f * r, hy + 0.08f * r), 0.04f * r, activitySteelDark)
    canvas.restore()
    if (hitAge < 0.35f) {
        val age = hitAge / 0.35f
        for (k in 0 until 3) {
            val dirX = (k - 1) * 0.3f
            val dx = f.x(0.62f + 0.12f * k) + dirX * age * 0.25f * r
            val dy = f.y(0.5f) - 0.2f * r * age
            f.flatCircle(dx, dy, (0.06f + 0.06f * age) * r, activityMist, 1f - age)
        }
    }
}

/** A flask on the lower right, its liquid (in the accent colour) bubbling, and a sparkle above it. */
private fun activityChemistry(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    val flask = Path().apply {
        moveTo(f.x(0.63f), f.y(0.2f))
        lineTo(f.x(0.77f), f.y(0.2f))
        lineTo(f.x(0.77f), f.y(0.46f))
        lineTo(f.x(0.96f), f.y(0.9f))
        quadTo(f.x(0.99f), f.y(0.97f), f.x(0.92f), f.y(0.97f))
        lineTo(f.x(0.52f), f.y(0.97f))
        quadTo(f.x(0.45f), f.y(0.97f), f.x(0.48f), f.y(0.9f))
        lineTo(f.x(0.63f), f.y(0.46f))
        close()
    }
    f.flatPath(flask, activityBone, 0.22f)
    val wave = ACTIVITY_TAU * f.t / 1.2f
    val liquid = Path().apply {
        moveTo(f.x(0.4f), f.y(0.7f + 0.03f * sin(wave)))
        for (i in 1..8) {
            lineTo(f.x(0.4f + i * 0.075f), f.y(0.7f + 0.03f * sin(wave + i * 1.1f)))
        }
        lineTo(f.x(1.0f), f.y(1.0f))
        lineTo(f.x(0.4f), f.y(1.0f))
        close()
    }
    canvas.save()
    canvas.clipPath(flask)
    f.flatPath(liquid, f.accent, 0.95f)
    for (k in 0 until 4) {
        val ph = activityFract(f.t / 1.6f + k / 4f)
        val by = f.y(0.92f - 0.2f * ph)
        f.flatCircle(f.x(activityBubbleX[k]), by, (0.04f + 0.03f * sin(ACTIVITY_PI * ph)) * r, activityMist, 0.9f)
    }
    canvas.restore()
    f.stickStroke(flask, 0.07f * r, activityBone, 0.95f)
    val spark = 0.5f + 0.5f * sin(ACTIVITY_TAU * f.t / 1.4f)
    f.stickPath(activityStarPath(f.x(1.0f), f.y(-0.5f), (0.08f + 0.06f * spark) * r), activitySun)
}

/** A keyboard in front of the lower face: one key bounces at a time, and two keys fly up off it. */
private fun activityTypingFast(f: ActivityFrame) {
    val r = f.r
    val canvas = f.canvas
    f.stickRect(RectF(f.x(-0.35f), f.y(0.64f), f.x(1.0f), f.y(0.99f)), 0.06f * r, activitySteelDark)
    val pressed = floor(activityFract(f.t / 0.5f) * 8f).toInt()
    for (k in 0 until 8) {
        val row = k / 4
        val col = k % 4
        val kx = -0.2f + col * 0.3f + row * 0.15f
        val ky = if (row == 0) 0.74f else 0.9f
        val dip = if (k == pressed) 0.04f else 0f
        f.stickRect(
            RectF(f.x(kx - 0.12f), f.y(ky - 0.075f + dip), f.x(kx + 0.12f), f.y(ky + 0.075f + dip)),
            0.03f * r,
            if (k == pressed) activitySun else activityBone,
        )
    }
    for (j in 0 until 2) {
        val ph = activityFract(f.t / 0.9f + j * 0.5f)
        val fx = 0.3f + 0.45f * j + 0.08f * sin(ACTIVITY_TAU * ph)
        val fy = 0.6f - 4f * ph * (1f - ph)
        canvas.save()
        canvas.rotate(360f * ph, f.x(fx), f.y(fy))
        f.stickRect(RectF(f.x(fx - 0.12f), f.y(fy - 0.08f), f.x(fx + 0.12f), f.y(fy + 0.08f)), 0.03f * r, activityCoral, 1f - 0.4f * ph)
        canvas.restore()
    }
}
