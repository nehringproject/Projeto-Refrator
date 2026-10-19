package dev.agentworkbench

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** The town is this many times wider than the card; the street runs off both edges. */
private const val PLAZA_CITY_SCALE = 2.2f

/** The card is taller so the town has room for buildings, a street and a tram. */
private val PLAZA_CARD_H = 260.dp

/** Where the buildings meet the street, as a fraction of the card height. */
private const val PLAZA_GROUND_FRAC = 0.78f
private val PLAZA_GROUND_DP: Dp = PLAZA_CARD_H * PLAZA_GROUND_FRAC

/** The square in front of the town hall, as a fraction of the town width. */
private const val PLAZA_SQUARE = 0.5f

private const val PLAZA_MAX_CITIZENS = 10

/**
 * The Sociedade Prisma as a tiny living town, wider than its card and scrolled sideways: real places
 * (town hall, office, library, cafe, lab, workshop, studio, houses and a park), each with its own
 * little animation. The sky follows the real clock. Every prisma walks to the place that fits its
 * job, busy ones gather at the square in front of the town hall, sleepers go home. Tap one to open it.
 */
@Composable
internal fun PrismaPlaza(
    bots: List<Bot>,
    busyIds: Set<String>,
    onOpen: (Bot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var minute by remember { mutableIntStateOf(plazaMinuteOfDay()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Wake a moment after each new minute so the sky moves on the minute.
            delay(plazaMsToNextMinute() + 250L)
            minute = plazaMinuteOfDay()
        }
    }
    val hour = minute / 60
    val time = minute / 60f
    val dayness = plazaDayness(time)
    val night = 1f - dayness
    val late = plazaLateness(time)
    // Chimney smoke in the morning and in the evening.
    val smoke = if (time < 9f || time >= 18f) 1f else 0f
    val skyBrush = remember(minute) { Brush.verticalGradient(plazaSkyColors(time)) }
    val admin = bots.firstOrNull(PrismaAdmin::isAdmin)
    val citizens = bots.filterNot(PrismaAdmin::isAdmin).take(PLAZA_MAX_CITIZENS)
    val slots = plazaSlots(citizens, busyIds, hour)
    val houseMask = slots.fold(0) { mask, slot -> if (slot.asleep) mask or (1 shl slot.house) else mask }
    val anyBusy = bots.any { it.id in busyIds }
    val scroll = rememberScrollState()
    // Start centred on the plaza, as soon as the scroll range is known.
    LaunchedEffect(Unit) {
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        scroll.scrollTo(scroll.maxValue / 2)
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(PLAZA_CARD_H)
            .clip(RoundedCornerShape(26.dp))
            .background(skyBrush)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(26.dp)),
    ) {
        val cityWidth = maxWidth * PLAZA_CITY_SCALE
        // The sky stays put; the town slides across it.
        PlazaSky(time = time, dayness = dayness, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().horizontalScroll(scroll)) {
            Box(modifier = Modifier.width(cityWidth).fillMaxHeight()) {
                PlazaCity(night = night, late = late, houses = houseMask, modifier = Modifier.fillMaxSize())
                PlazaAmbient(night = night, dayness = dayness, smoke = smoke, modifier = Modifier.fillMaxSize())
                PlazaLight(anyBusy = anyBusy, modifier = Modifier.fillMaxSize())
                PlazaPlace.values().forEach { place ->
                    Text(
                        text = place.title,
                        modifier = Modifier
                            .offset(x = cityWidth * place.from, y = PLAZA_GROUND_DP - place.height.dp - 30.dp)
                            .width(cityWidth * (place.to - place.from)),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
                admin?.let { maestro ->
                    PlazaMaestro(bot = maestro, x = cityWidth * PLAZA_SQUARE - 26.dp, anyBusy = anyBusy, onOpen = onOpen)
                }
                citizens.forEachIndexed { k, bot ->
                    val slot = slots[k]
                    PlazaCitizen(
                        bot = bot,
                        targetX = cityWidth * slot.fraction,
                        roamRange = cityWidth * 0.05f,
                        period = 7_000 + k * 1_300,
                        index = k + 1,
                        busy = slot.busy,
                        asleep = slot.asleep,
                        figure = 40,
                        onOpen = onOpen,
                    )
                }
            }
        }
        // Fixed over the scrolling town: the title, the caption, the edge fade and the scroll hint.
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp)) {
            Text("Sociedade Prisma", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(
                text = plazaCaption(hour, citizens.size, busyIds.size),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(36.dp)
                .background(plazaEdgeFade),
        )
        PlazaScrollHint(scroll = scroll, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
    }
}

/** A thin track under the card: it shows where you are along the town, so the scrolling is easy to find. */
@Composable
private fun PlazaScrollHint(scroll: ScrollState, modifier: Modifier) {
    Canvas(modifier = modifier.size(width = 48.dp, height = 3.dp)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = Color.White.copy(alpha = 0.18f), size = size, cornerRadius = radius)
        val thumb = size.width * 0.3f
        val at = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.6f),
            topLeft = Offset((size.width - thumb) * at, 0f),
            size = Size(thumb, size.height),
            cornerRadius = radius,
        )
    }
}

/** Where a citizen stands: a fraction of the town width, and what it is doing. */
private class PlazaSlot(val fraction: Float, val busy: Boolean, val asleep: Boolean, val house: Int)

/**
 * The spot of every citizen. Busy prismas stand shoulder to shoulder on the square, sleepers take a
 * house each (three houses, then they share), and everyone else stands at the place that fits its job.
 */
private fun plazaSlots(citizens: List<Bot>, busyIds: Set<String>, hour: Int): List<PlazaSlot> {
    val busyTotal = citizens.count { it.id in busyIds }
    var busySeen = 0
    var sleepSeen = 0
    val houses = PlazaPlace.HOUSES
    return citizens.mapIndexed { k, bot ->
        when {
            bot.id in busyIds -> {
                val fraction = PLAZA_SQUARE + (busySeen - (busyTotal - 1) / 2f) * 0.032f
                busySeen++
                PlazaSlot(fraction, busy = true, asleep = false, house = 0)
            }
            bot.isQuietAt(hour) -> {
                val house = sleepSeen % 3
                sleepSeen++
                val fraction = houses.from + (houses.to - houses.from) * (0.2f + 0.3f * house)
                PlazaSlot(fraction, busy = false, asleep = true, house = house)
            }
            else -> {
                val place = plazaPlaceFor(bot)
                // A little spread so two prismas at the same place do not stand on each other.
                val fraction = place.center + ((k % 3) - 1) * 0.02f
                PlazaSlot(fraction, busy = false, asleep = false, house = 0)
            }
        }
    }
}

// ---- The town: street and buildings ---------------------------------------------------------------

/**
 * The street and the static buildings. Its inputs only change once a minute (or when someone goes to
 * bed), so the canvas is redrawn then and costs nothing between frames.
 */
@Composable
private fun PlazaCity(night: Float, late: Float, houses: Int, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val ground = h * PLAZA_GROUND_FRAC
        val curb = 9.dp.toPx()
        // Sidewalk, road and the dashed lane mark.
        drawRect(plazaSidewalk, topLeft = Offset(0f, ground), size = Size(w, curb))
        drawRect(plazaRoad, topLeft = Offset(0f, ground + curb), size = Size(w, h - ground - curb))
        drawLine(
            color = plazaLaneMark,
            start = Offset(0f, ground + curb + 14.dp.toPx()),
            end = Offset(w, ground + curb + 14.dp.toPx()),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 9.dp.toPx())),
        )
        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, ground), Offset(w, ground), 1.dp.toPx())
        PlazaPlace.values().forEach { place ->
            val x = w * place.from
            val bw = w * (place.to - place.from)
            val top = ground - place.height.dp.toPx()
            when (place) {
                PlazaPlace.PARK -> plazaParkLawn(x, bw, ground)
                PlazaPlace.HOUSES -> plazaHouses(x, bw, ground, houses, night)
                PlazaPlace.LIBRARY -> {
                    plazaBlock(x, top, bw, ground)
                    plazaBookshelves(x, top, bw, ground, night)
                }
                PlazaPlace.LAB -> {
                    plazaBlock(x, top, bw, ground)
                    plazaSign(x, top + 6.dp.toPx(), bw)
                    // The flask on the sign; its bubbles rise in the ambient layer.
                    drawCircle(plazaFlask, radius = 3.5.dp.toPx(), center = Offset(x + bw * 0.5f, top + 14.dp.toPx()))
                    drawRect(plazaFlask, Offset(x + bw * 0.5f - 1.dp.toPx(), top + 8.dp.toPx()), Size(2.dp.toPx(), 5.dp.toPx()))
                    plazaWindowGrid(x, top, bw, ground, cols = 2, topPad = 26.dp.toPx(), seed = 2, night = night, late = late)
                }
                PlazaPlace.HALL -> plazaHall(x, top, bw, ground, night, late)
                PlazaPlace.STUDIO -> {
                    plazaBlock(x, top, bw, ground)
                    // A note bobs above this sign in the ambient layer.
                    plazaSign(x, top + 6.dp.toPx(), bw)
                    plazaWindowGrid(x, top, bw, ground, cols = 2, topPad = 26.dp.toPx(), seed = 4, night = night, late = late)
                }
                PlazaPlace.CAFE -> plazaCafe(x, top, bw, ground, night)
                PlazaPlace.WORKSHOP -> {
                    plazaBlock(x, top, bw, ground)
                    // The gear on this sign turns in the ambient layer.
                    plazaSign(x, top + 6.dp.toPx(), bw)
                    plazaWindowGrid(x, top, bw, ground, cols = 2, topPad = 26.dp.toPx(), seed = 3, night = night, late = late)
                }
                PlazaPlace.OFFICE -> {
                    plazaBlock(x, top, bw, ground)
                    drawLine(
                        plazaLampPost,
                        Offset(x + bw * 0.7f, top - 4.dp.toPx()),
                        Offset(x + bw * 0.7f, top - 14.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    // The screens that blink are drawn over these windows in the ambient layer.
                    plazaWindowGrid(x, top, bw, ground, cols = 2, topPad = 8.dp.toPx(), seed = 5, night = night, late = late)
                }
            }
        }
        // Street lamps along the sidewalk, lit as night comes.
        plazaLamps.forEach { lampX ->
            val lx = w * lampX
            val headY = ground - 26.dp.toPx()
            drawLine(plazaLampPost, Offset(lx, ground), Offset(lx, headY), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
            if (night > 0.01f) {
                drawCircle(plazaGold.copy(alpha = 0.10f * night), radius = 20.dp.toPx(), center = Offset(lx, headY))
                drawCircle(plazaGold.copy(alpha = 0.22f * night), radius = 8.dp.toPx(), center = Offset(lx, headY))
            }
            drawRoundRect(
                color = lerp(plazaLampOff, plazaGold, night),
                topLeft = Offset(lx - 3.dp.toPx(), headY - 2.dp.toPx()),
                size = Size(6.dp.toPx(), 3.dp.toPx()),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
        // The fountain basin at the far end; its jets are in the ambient layer.
        val fx = w * 0.95f
        drawRoundRect(
            color = plazaStone,
            topLeft = Offset(fx - 15.dp.toPx(), ground - 5.dp.toPx()),
            size = Size(30.dp.toPx(), 5.dp.toPx()),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
        )
        drawRoundRect(
            color = plazaWater.copy(alpha = 0.3f),
            topLeft = Offset(fx - 12.dp.toPx(), ground - 6.dp.toPx()),
            size = Size(24.dp.toPx(), 2.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}

/** Rows of windows that fit between the roof and the ground (at most five). */
private fun DrawScope.plazaRowCount(top: Float, ground: Float, topPad: Float): Int =
    ((ground - top - topPad - 6.dp.toPx()) / 14.dp.toPx()).toInt().coerceIn(0, 5)

/**
 * A grid of windows. Each one lights up at its own moment after dusk, and some switch off late.
 * The office screens use the same grid, so the ambient layer can blink exactly these windows.
 */
private fun DrawScope.plazaWindowGrid(
    x: Float,
    top: Float,
    bw: Float,
    ground: Float,
    cols: Int,
    topPad: Float,
    seed: Int,
    night: Float,
    late: Float,
) {
    val rows = plazaRowCount(top, ground, topPad)
    val step = bw * 0.64f / cols
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val threshold = ((row * 5 + col * 3 + seed * 7) % 10) / 10f
            val offAtNight = (row + col + seed) % 3 == 0
            var on = ((night - threshold * 0.7f) / 0.3f).coerceIn(0f, 1f)
            if (offAtNight) on *= 1f - late
            drawRoundRect(
                color = lerp(plazaWindowOff, plazaWindowOn, on),
                topLeft = Offset(x + bw * 0.18f + col * step, top + topPad + row * 14.dp.toPx()),
                size = Size(step * 0.6f, 6.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** A building body with a thin cornice on top. */
private fun DrawScope.plazaBlock(x: Float, top: Float, bw: Float, ground: Float) {
    drawRoundRect(
        color = plazaBuilding,
        topLeft = Offset(x, top),
        size = Size(bw, ground - top),
        cornerRadius = CornerRadius(5.dp.toPx()),
    )
    drawRect(plazaCornice, Offset(x - 2.dp.toPx(), top - 4.dp.toPx()), Size(bw + 4.dp.toPx(), 4.dp.toPx()))
}

/** The little board that carries a building's icon. Its icon is drawn by the caller (or the ambient layer). */
private fun DrawScope.plazaSign(x: Float, boardTop: Float, bw: Float) {
    drawRoundRect(
        color = plazaSignBoard,
        topLeft = Offset(x + bw * 0.3f, boardTop),
        size = Size(bw * 0.4f, 16.dp.toPx()),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
}

/** The park's lawn strip along the sidewalk. The trees are in the ambient layer so they can sway. */
private fun DrawScope.plazaParkLawn(x: Float, bw: Float, ground: Float) {
    drawRoundRect(
        color = plazaGrass,
        topLeft = Offset(x, ground - 3.dp.toPx()),
        size = Size(bw, 3.dp.toPx()),
        cornerRadius = CornerRadius(1.5.dp.toPx()),
    )
}

/** Three little houses. A window lights up for a prisma asleep inside (see [houses]). */
private fun DrawScope.plazaHouses(x: Float, bw: Float, ground: Float, houses: Int, night: Float) {
    val hh = 36.dp.toPx()
    val top = ground - hh
    val hw = bw / 3f - 6.dp.toPx()
    for (i in 0 until 3) {
        val hx = x + bw * i / 3f + 3.dp.toPx()
        drawRoundRect(plazaBuilding, Offset(hx, top), Size(hw, hh), CornerRadius(4.dp.toPx()))
        // Roof: a little triangle, and a chimney for the smoke.
        translate(left = hx, top = top) {
            scale(hw, 14.dp.toPx(), pivot = Offset.Zero) { drawPath(plazaRoof, plazaRoofColor) }
        }
        drawRect(plazaStone, Offset(hx + hw * 0.7f, top - 16.dp.toPx()), Size(4.dp.toPx(), 16.dp.toPx()))
        // A sleeping prisma lights its window; the empty houses stay dark.
        val awake = if (((houses shr i) and 1) == 1) max(0.35f, night) else 0f
        drawRoundRect(
            color = lerp(plazaWindowOff, plazaWindowOn, awake),
            topLeft = Offset(hx + hw * 0.14f, ground - 28.dp.toPx()),
            size = Size(hw * 0.26f, 9.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
        drawRect(plazaDoor, Offset(hx + hw * 0.44f, ground - 14.dp.toPx()), Size(hw * 0.14f, 14.dp.toPx()))
    }
}

/** A striped awning, a glass front and a cup sign. The steam is drawn in the ambient layer. */
private fun DrawScope.plazaCafe(x: Float, top: Float, bw: Float, ground: Float, night: Float) {
    plazaBlock(x, top, bw, ground)
    val stripe = bw / 4f
    for (s in 0 until 4) {
        drawRect(
            color = if (s % 2 == 0) plazaAwningA else plazaAwningB,
            topLeft = Offset(x + stripe * s, top + 8.dp.toPx()),
            size = Size(stripe, 8.dp.toPx()),
        )
    }
    plazaSign(x, top + 18.dp.toPx(), bw)
    drawRoundRect(
        color = plazaCup,
        topLeft = Offset(x + bw * 0.5f - 4.dp.toPx(), top + 24.dp.toPx()),
        size = Size(8.dp.toPx(), 7.dp.toPx()),
        cornerRadius = CornerRadius(1.5.dp.toPx()),
    )
    // Glass front: warm inside once it is dark.
    drawRoundRect(
        color = lerp(plazaWindowOff, plazaWindowOn, night),
        topLeft = Offset(x + bw * 0.12f, top + 38.dp.toPx()),
        size = Size(bw * 0.76f, ground - top - 40.dp.toPx()),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
}

/** Library shelves: two tall bays of books, lit warmly at night. */
private fun DrawScope.plazaBookshelves(x: Float, top: Float, bw: Float, ground: Float, night: Float) {
    val shelfTop = top + 20.dp.toPx()
    val shelfH = ground - shelfTop - 6.dp.toPx()
    val rowH = shelfH / 3f
    val backdrop = lerp(plazaShelf, plazaShelfLit, 0.25f + 0.55f * night)
    for (i in 0 until 2) {
        val wx = x + bw * (0.12f + i * 0.46f)
        val ww = bw * 0.42f
        drawRoundRect(backdrop, Offset(wx, shelfTop), Size(ww, shelfH), CornerRadius(3.dp.toPx()))
        for (row in 0 until 3) {
            val rowTop = shelfTop + row * rowH
            for (s in 0 until 5) {
                drawRect(
                    color = plazaBooks[(row + s + i) % plazaBooks.size],
                    topLeft = Offset(wx + 3.dp.toPx() + s * (ww - 6.dp.toPx()) / 5f, rowTop + 2.dp.toPx()),
                    size = Size(2.5.dp.toPx(), rowH - 5.dp.toPx()),
                )
            }
            drawRect(plazaShelf, Offset(wx, rowTop + rowH - 2.dp.toPx()), Size(ww, 2.dp.toPx()))
        }
    }
}

/** The town hall: a pediment, four columns, a door, steps and two lit windows at the corners. */
private fun DrawScope.plazaHall(x: Float, top: Float, bw: Float, ground: Float, night: Float, late: Float) {
    plazaBlock(x, top, bw, ground)
    // Pediment; the flagpole stands on its left corner (the flag waves in the ambient layer).
    translate(left = x, top = top) {
        scale(bw, 16.dp.toPx(), pivot = Offset.Zero) { drawPath(plazaRoof, plazaBuilding) }
    }
    for (c in 0 until 4) {
        drawRect(
            color = plazaColumn,
            topLeft = Offset(x + bw * (0.12f + c * 0.25f), top + 8.dp.toPx()),
            size = Size(2.5.dp.toPx(), ground - top - 12.dp.toPx()),
        )
    }
    drawRoundRect(
        color = plazaDoor,
        topLeft = Offset(x + bw * 0.5f - 7.dp.toPx(), ground - 24.dp.toPx()),
        size = Size(14.dp.toPx(), 24.dp.toPx()),
        cornerRadius = CornerRadius(7.dp.toPx()),
    )
    drawRect(plazaSteps, Offset(x - 5.dp.toPx(), ground - 4.dp.toPx()), Size(bw + 10.dp.toPx(), 4.dp.toPx()))
    val lit = lerp(plazaWindowOff, plazaWindowOn, night * (1f - late * 0.5f))
    drawRoundRect(lit, Offset(x + bw * 0.04f, top + 30.dp.toPx()), Size(6.dp.toPx(), 12.dp.toPx()), CornerRadius(2.dp.toPx()))
    drawRoundRect(
        lit,
        Offset(x + bw * 0.96f - 6.dp.toPx(), top + 30.dp.toPx()),
        Size(6.dp.toPx(), 12.dp.toPx()),
        CornerRadius(2.dp.toPx()),
    )
}
/** Sun, moon, stars, shooting star and drifting clouds. Animated, drawn behind the buildings. */
@Composable
private fun PlazaSky(time: Float, dayness: Float, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "sky")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(150_000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift",
    )
    val twinkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200, easing = LinearEasing), RepeatMode.Restart),
        label = "twinkle",
    )
    val shoot by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart),
        label = "shoot",
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.78f
        val apex = h * 0.36f
        val night = 1f - dayness
        if (night > 0.01f) {
            plazaStars.forEachIndexed { k, (x, y) ->
                val s = sin(twinkle * PLAZA_TAU + k * 1.7f)
                drawCircle(
                    color = Color.White.copy(alpha = night * (0.3f + 0.55f * (0.5f + 0.5f * s))),
                    radius = 1.5.dp.toPx(),
                    center = Offset(w * x, h * y),
                )
            }
            // Now and then a shooting star crosses the upper right of the sky.
            if (shoot in 0.55f..0.67f) {
                val u = (shoot - 0.55f) / 0.12f
                val head = Offset(w * (0.56f + 0.34f * u), h * (0.04f + 0.26f * u))
                val tail = head - Offset(w * 0.12f, h * 0.09f)
                val a = night * sin(u * PLAZA_PI)
                drawLine(Color.White.copy(alpha = a * 0.8f), start = tail, end = head, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Color.White.copy(alpha = a), radius = 1.6.dp.toPx(), center = head)
            }
        }
        if (dayness > 0.01f) {
            // The sun travels from left to right between 5h and 18h.
            val p = ((time - 5f) / 13.5f).coerceIn(0f, 1f)
            val center = Offset(w * (0.1f + 0.8f * p), horizon - sin(p * PLAZA_PI) * (horizon - apex))
            val core = lerp(plazaSunCore, plazaSunWarm, 1f - sin(p * PLAZA_PI))
            drawCircle(core.copy(alpha = 0.10f * dayness), radius = 18.dp.toPx(), center = center)
            drawCircle(core.copy(alpha = 0.20f * dayness), radius = 11.dp.toPx(), center = center)
            drawCircle(core.copy(alpha = dayness), radius = 7.dp.toPx(), center = center)
        }
        // The moon rises at 18h30 and sets at 5h30, crossing right to left with a soft glow.
        val q = ((time - 18.5f + 24f) % 24f) / 11f
        if (q <= 1f) {
            val edge = minOf(q / 0.1f, (1f - q) / 0.1f, 1f)
            val center = Offset(w * (0.9f - 0.8f * q), horizon - sin(q * PLAZA_PI) * (horizon - apex))
            drawCircle(plazaMoonCore.copy(alpha = 0.08f * edge), radius = 20.dp.toPx(), center = center)
            drawCircle(plazaMoonCore.copy(alpha = 0.18f * edge), radius = 12.dp.toPx(), center = center)
            drawCircle(plazaMoonCore.copy(alpha = edge), radius = 6.5.dp.toPx(), center = center)
        }
        val cloudAlpha = 0.16f * dayness
        if (cloudAlpha > 0.005f) {
            plazaClouds.forEach { cloud ->
                val cw = cloud.width.dp.toPx()
                // Each cloud loops across the whole card, so the drift never jumps.
                val period = w + cw * 2f
                val left = ((cloud.x0 * w + drift * cloud.loops * period) % period) - cw
                plazaCloud(left, h * cloud.y, cw, Color.White.copy(alpha = cloudAlpha))
            }
        }
    }
}

/** Soft spotlight over the stage while any prisma is busy. */
@Composable
private fun PlazaLight(anyBusy: Boolean, modifier: Modifier) {
    val spot by animateFloatAsState(
        targetValue = if (anyBusy) 1f else 0f,
        animationSpec = tween(900),
        label = "spot",
    )
    val transition = rememberInfiniteTransition(label = "light")
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Canvas(modifier) {
        val strength = spot * (0.85f + 0.15f * breathe)
        if (strength > 0.01f) {
            scale(size.width, size.height, pivot = Offset.Zero) {
                drawPath(plazaConeOuter, Color.White.copy(alpha = 0.07f * strength))
                drawPath(plazaConeInner, Color.White.copy(alpha = 0.07f * strength))
            }
            drawOval(
                color = Color.White.copy(alpha = 0.10f * strength),
                topLeft = Offset(size.width * 0.36f, size.height * 0.72f),
                size = Size(size.width * 0.28f, size.height * 0.1f),
            )
        }
    }
}

// ---- The living layer -----------------------------------------------------------------------------

/**
 * Everything that moves on its own: trees, the fountain, the flag, the book, bubbles, the gear, the
 * note, the steam, the screens, chimney smoke, the tram, the birds by day, fireflies and a cat by
 * night. One transition drives the whole layer; nothing here allocates per frame.
 */
@Composable
private fun PlazaAmbient(night: Float, dayness: Float, smoke: Float, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val tick by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "tick",
    )
    val sway by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200, easing = LinearEasing), RepeatMode.Restart),
        label = "sway",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val blink by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "blink",
    )
    val tram by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Restart),
        label = "tram",
    )
    val birds by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart),
        label = "birds",
    )
    Canvas(modifier) {
        val w = size.width
        val ground = size.height * PLAZA_GROUND_FRAC
        plazaParkLife(w, ground, tick, sway, night)
        plazaTownLife(w, ground, tick, sway, spin, blink, smoke, night)
        plazaTramLife(w, ground, tram, night)
        plazaSkyLife(w, birds, tick, dayness)
    }
}

/** Trees that sway, a fountain that spurts, and fireflies over the park at night. */
private fun DrawScope.plazaParkLife(w: Float, ground: Float, tick: Float, sway: Float, night: Float) {
    for (i in 0 until 3) {
        val x = w * (0.02f + i * 0.045f)
        drawRect(plazaTrunk, Offset(x - 1.5.dp.toPx(), ground - 16.dp.toPx()), Size(3.dp.toPx(), 16.dp.toPx()))
        val sx = x + sin(sway * PLAZA_TAU + i * 1.3f) * 2.5.dp.toPx()
        drawCircle(plazaTree, radius = 10.dp.toPx(), center = Offset(sx, ground - 24.dp.toPx()))
        drawCircle(plazaTree, radius = 6.dp.toPx(), center = Offset(sx + 4.dp.toPx(), ground - 34.dp.toPx()))
    }
    // The fountain: three jets that arc up and fall back into the basin.
    val fx = w * 0.95f
    drawRect(plazaStone, Offset(fx - 1.5.dp.toPx(), ground - 18.dp.toPx()), Size(3.dp.toPx(), 13.dp.toPx()))
    for (j in 0 until 3) {
        val p = (tick + j / 3f) % 1f
        drawCircle(
            color = plazaWater.copy(alpha = 0.85f * (1f - p)),
            radius = 1.4.dp.toPx(),
            center = Offset(fx + (j - 1) * 6.dp.toPx() * p, ground - 18.dp.toPx() - sin(p * PLAZA_PI) * 14.dp.toPx()),
        )
    }
    if (night > 0.05f) {
        for (f in 0 until 6) {
            val fxl = w * (0.02f + 0.11f * ((f * 0.37f) % 1f)) + sin(tick * PLAZA_TAU + f) * 5.dp.toPx()
            val fy = ground - 26.dp.toPx() - (f % 3) * 14.dp.toPx() + cos(tick * PLAZA_TAU + f * 1.7f) * 4.dp.toPx()
            val a = night * (0.4f + 0.6f * (0.5f + 0.5f * sin(sway * PLAZA_TAU * 2f + f * 1.9f)))
            drawCircle(plazaFirefly.copy(alpha = 0.25f * a), radius = 4.dp.toPx(), center = Offset(fxl, fy))
            drawCircle(plazaFirefly.copy(alpha = a), radius = 1.2.dp.toPx(), center = Offset(fxl, fy))
        }
    }
}

/** The life of the buildings: the flag, the book, the bubbles, the gear, the note, the steam, the screens, the smoke and the cat. */
private fun DrawScope.plazaTownLife(
    w: Float,
    ground: Float,
    tick: Float,
    sway: Float,
    spin: Float,
    blink: Float,
    smoke: Float,
    night: Float,
) {
    // Town hall: the flag waves from the pole on the corner of the pediment.
    val hall = PlazaPlace.HALL
    val poleX = w * hall.from + 9.dp.toPx()
    val poleBase = ground - hall.height.dp.toPx() - 4.dp.toPx()
    val poleTop = poleBase - 24.dp.toPx()
    drawLine(plazaLampPost, Offset(poleX, poleBase), Offset(poleX, poleTop), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    for (s in 0 until 6) {
        val sx = poleX + 1.dp.toPx() + s * 3.dp.toPx()
        val sy = poleTop + 1.dp.toPx() + sin(sway * PLAZA_TAU + s * 0.9f) * s * 0.5.dp.toPx()
        drawRect(plazaFlag.copy(alpha = 0.9f), Offset(sx, sy), Size(3.dp.toPx(), 9.dp.toPx()))
    }

    // Library: a book on the front whose right page turns over.
    val lib = PlazaPlace.LIBRARY
    val libTop = ground - lib.height.dp.toPx()
    val libCx = w * lib.from + w * (lib.to - lib.from) * 0.5f
    drawRect(plazaPage, Offset(libCx - 6.dp.toPx(), libTop + 5.dp.toPx()), Size(6.dp.toPx(), 8.dp.toPx()))
    val turn = abs(cos(tick * PLAZA_PI)).coerceAtLeast(0.12f)
    scale(turn, 1f, pivot = Offset(libCx, libTop + 5.dp.toPx())) {
        drawRect(plazaPage.copy(alpha = 0.92f), Offset(libCx, libTop + 5.dp.toPx()), Size(6.dp.toPx(), 8.dp.toPx()))
    }

    // Lab: bubbles rise from the flask on the sign.
    val lab = PlazaPlace.LAB
    val labCx = w * lab.from + w * (lab.to - lab.from) * 0.5f
    val labTop = ground - lab.height.dp.toPx()
    for (i in 0 until 3) {
        val p = (tick + i / 3f) % 1f
        drawCircle(
            color = plazaFlask.copy(alpha = 0.8f * (1f - p)),
            radius = (0.9f + p * 0.6f).dp.toPx(),
            center = Offset(labCx + sin(p * PLAZA_TAU + i) * 1.5.dp.toPx(), labTop + 12.dp.toPx() - p * 8.dp.toPx()),
        )
    }

    // Workshop: the gear on the sign turns.
    val ws = PlazaPlace.WORKSHOP
    val gx = w * ws.from + w * (ws.to - ws.from) * 0.5f
    val gy = ground - ws.height.dp.toPx() + 14.dp.toPx()
    translate(left = gx, top = gy) {
        rotate(degrees = spin * 360f, pivot = Offset.Zero) {
            scale(6.dp.toPx(), 6.dp.toPx(), pivot = Offset.Zero) { drawPath(plazaGear, plazaGearColor) }
        }
    }
    drawCircle(plazaSignBoard, radius = 2.dp.toPx(), center = Offset(gx, gy))

    // Studio: a note bobs above the sign.
    val st = PlazaPlace.STUDIO
    val stx = w * st.from + w * (st.to - st.from) * 0.5f
    plazaNote(
        x = stx + 2.dp.toPx(),
        y = ground - st.height.dp.toPx() + 18.dp.toPx() - sin(tick * PLAZA_TAU) * 2.dp.toPx(),
        s = 0.9f,
        color = plazaNoteColor.copy(alpha = 0.6f + 0.4f * sin(tick * PLAZA_TAU).coerceAtLeast(0f)),
    )

    // Cafe: steam rises from the cup, and at night a cat sleeps on the roof.
    val cafe = PlazaPlace.CAFE
    val cfx = w * cafe.from + w * (cafe.to - cafe.from) * 0.5f
    val cafeTop = ground - cafe.height.dp.toPx()
    for (i in 0 until 3) {
        val p = (tick + i / 3f) % 1f
        drawCircle(
            color = plazaSteam.copy(alpha = 0.5f * (1f - p)),
            radius = (1.2f + p * 1.4f).dp.toPx(),
            center = Offset(cfx + sin(p * PLAZA_TAU + i) * 2.dp.toPx(), cafeTop + 24.dp.toPx() - p * 14.dp.toPx()),
        )
    }
    if (night > 0.05f) {
        val catX = cfx + 14.dp.toPx()
        val catY = cafeTop - 4.dp.toPx()
        val cat = plazaCat.copy(alpha = night)
        drawOval(cat, topLeft = Offset(catX - 6.dp.toPx(), catY - 7.dp.toPx()), size = Size(12.dp.toPx(), 7.dp.toPx()))
        drawCircle(cat, radius = 3.5.dp.toPx(), center = Offset(catX + 6.dp.toPx(), catY - 8.dp.toPx()))
        drawLine(
            cat,
            Offset(catX - 5.dp.toPx(), catY - 1.dp.toPx()),
            Offset(catX - 12.dp.toPx(), catY - 6.dp.toPx() + sin(sway * PLAZA_TAU) * 1.5.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(plazaFirefly.copy(alpha = 0.9f * night), radius = 0.8.dp.toPx(), center = Offset(catX + 7.dp.toPx(), catY - 9.dp.toPx()))
    }

    // Office: a few screens in the windows blink, in a checker pattern.
    val off = PlazaPlace.OFFICE
    val ox = w * off.from
    val obw = w * (off.to - off.from)
    val offTop = ground - off.height.dp.toPx()
    val topPad = 8.dp.toPx()
    val step = obw * 0.32f
    val rows = plazaRowCount(offTop, ground, topPad)
    for (row in 0 until rows) {
        for (col in 0 until 2) {
            if ((row + col) % 2 == 0) {
                val on = 0.5f + 0.5f * sin(blink * PLAZA_TAU + row * 1.3f + col * 2.1f)
                drawRoundRect(
                    color = plazaScreen.copy(alpha = (0.2f + 0.6f * on) * (0.45f + 0.55f * night)),
                    topLeft = Offset(ox + obw * 0.18f + col * step, offTop + topPad + row * 14.dp.toPx()),
                    size = Size(step * 0.6f, 6.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
    }

    // Houses: chimney smoke in the morning and in the evening.
    if (smoke > 0.01f) {
        val houses = PlazaPlace.HOUSES
        val hx0 = w * houses.from
        val hbw = w * (houses.to - houses.from)
        val chimneyTop = ground - 36.dp.toPx() - 16.dp.toPx()
        for (i in 0 until 3) {
            val hx = hx0 + hbw * i / 3f + 3.dp.toPx()
            val hw = hbw / 3f - 6.dp.toPx()
            val chimneyX = hx + hw * 0.7f + 2.dp.toPx()
            for (j in 0 until 2) {
                val p = (sway + i * 0.33f + j * 0.5f) % 1f
                drawCircle(
                    color = plazaSmoke.copy(alpha = 0.3f * smoke * (1f - p)),
                    radius = (2f + p * 4f).dp.toPx(),
                    center = Offset(chimneyX + p * 4.dp.toPx(), chimneyTop - p * 34.dp.toPx()),
                )
            }
        }
    }
}

/** A tram crosses the street during the first 60% of every 20 seconds, with a soft headlight at night. */
private fun DrawScope.plazaTramLife(w: Float, ground: Float, tram: Float, night: Float) {
    if (tram >= 0.6f) return
    val prog = tram / 0.6f
    val left = -70.dp.toPx() + (w + 80.dp.toPx()) * prog
    val top = ground + 13.dp.toPx()
    val len = 64.dp.toPx()
    drawRoundRect(plazaTram, Offset(left, top), Size(len, 22.dp.toPx()), CornerRadius(7.dp.toPx()))
    for (j in 0 until 4) {
        drawRoundRect(
            color = lerp(plazaTramWin, plazaWindowOn, night),
            topLeft = Offset(left + 7.dp.toPx() + j * 13.dp.toPx(), top + 4.dp.toPx()),
            size = Size(9.dp.toPx(), 8.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
    drawLine(plazaLampPost, Offset(left + len * 0.5f, top), Offset(left + len * 0.5f, top - 6.dp.toPx()), strokeWidth = 1.dp.toPx())
    if (night > 0.05f) {
        val lamp = Offset(left + len, top + 14.dp.toPx())
        drawCircle(plazaGold.copy(alpha = 0.22f * night), radius = 18.dp.toPx(), center = lamp)
        drawCircle(plazaGold.copy(alpha = 0.7f * night), radius = 2.dp.toPx(), center = lamp)
    }
}

/** A flock of five flies in a V across the sky by day. */
private fun DrawScope.plazaSkyLife(w: Float, birds: Float, tick: Float, dayness: Float) {
    if (dayness < 0.05f) return
    val leadX = -40.dp.toPx() + (w + 80.dp.toPx()) * birds
    val leadY = 44.dp.toPx() + sin(birds * PLAZA_TAU) * 6.dp.toPx()
    val color = plazaBird.copy(alpha = 0.6f * dayness)
    for (k in -2..2) {
        val bx = leadX - abs(k) * 9.dp.toPx()
        val by = leadY + k * 5.dp.toPx()
        val flap = sin(tick * PLAZA_TAU * 2f + k * 0.8f) * 3.dp.toPx()
        drawLine(color, Offset(bx - 4.dp.toPx(), by - 2.dp.toPx() + flap), Offset(bx, by), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(bx, by), Offset(bx + 4.dp.toPx(), by - 2.dp.toPx() + flap), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
    }
}
/** Maestro on the stage: a baton that swings, a note now and then, and a brighter look when busy. */
@Composable
private fun PlazaMaestro(bot: Bot, x: Dp, anyBusy: Boolean, onOpen: (Bot) -> Unit) {
    val transition = rememberInfiniteTransition(label = "maestro")
    val conduct by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "conduct",
    )
    val notes by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_600, easing = LinearEasing), RepeatMode.Restart),
        label = "notes",
    )
    val accent = Mascots.of(bot.mascot).accent
    Box(
        modifier = Modifier
            .offset(x = x, y = PLAZA_GROUND_DP - 52.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(bot) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                MascotAvatar(
                    index = bot.mascot,
                    size = 52.dp,
                    active = anyBusy,
                    mood = if (anyBusy) MascotMood.WORKING else MascotMood.HAPPY,
                )
                Canvas(modifier = Modifier.size(52.dp)) {
                    val hand = Offset(size.width * 0.84f, size.height * 0.56f)
                    val len = 20.dp.toPx()
                    val tip = Offset(hand.x + len * 0.42f, hand.y - len)
                    rotate(degrees = sin(conduct * PLAZA_TAU) * 16f, pivot = hand) {
                        drawLine(Color.White.copy(alpha = 0.92f), start = hand, end = tip, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                    if (notes < 0.4f) {
                        val u = notes / 0.4f
                        plazaNote(
                            x = tip.x + sin(u * PLAZA_TAU) * 3.dp.toPx(),
                            y = tip.y - u * 22.dp.toPx(),
                            s = 1f,
                            color = accent.copy(alpha = 0.9f * sin(u * PLAZA_PI)),
                        )
                    }
                }
            }
            Text(
                text = bot.name,
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.95f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlazaCitizen(
    bot: Bot,
    targetX: Dp,
    roamRange: Dp,
    period: Int,
    index: Int,
    busy: Boolean,
    asleep: Boolean,
    figure: Int,
    onOpen: (Bot) -> Unit,
) {
    // One clock per prisma: the stroll, a quick hop, the prop loop, typing dots and the z z z.
    val transition = rememberInfiniteTransition(label = "citizen")
    val stroll by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(period.coerceAtLeast(1), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset((index * 911) % period.coerceAtLeast(1), StartOffsetType.FastForward),
        ),
        label = "stroll",
    )
    val hop by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(340, easing = LinearEasing), RepeatMode.Restart),
        label = "hop",
    )
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "clock",
    )
    val typing by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "typing",
    )
    val zz by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "zz",
    )
    // Walking eases out when busy or asleep, and back in when free again.
    val roam by animateFloatAsState(
        targetValue = if (busy || asleep) 0f else 1f,
        animationSpec = tween(700),
        label = "roam",
    )
    // A move to another place glides over (no teleporting); the hops show that it is walking.
    val targetPx = with(LocalDensity.current) { targetX.toPx() }
    val walkX by animateFloatAsState(
        targetValue = targetPx,
        animationSpec = tween(2_200, easing = FastOutSlowInEasing),
        label = "walk",
    )
    val accent = Mascots.of(bot.mascot).accent
    val prop = remember(bot.name, bot.job) { plazaPropFor(bot) }
    // A busy prisma works faster: the prop loop runs three times per clock cycle.
    val loops = if (busy) 3 else 1
    Box(
        modifier = Modifier
            .offset(y = PLAZA_GROUND_DP - figure.dp)
            .graphicsLayer { translationX = walkX + roamRange.toPx() * roam * plazaStrollPos(stroll) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(bot) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.graphicsLayer {
                    val walking = abs(walkX - targetPx) > 0.5f
                    val walk = max(plazaStrollWalk(stroll) * roam, if (walking) 1f else 0f)
                    val air = abs(sin(hop * PLAZA_PI))
                    val squash = (1f - air) * 0.035f * walk
                    translationY = if (asleep) 3.dp.toPx() else -air * 4.dp.toPx() * walk
                    scaleX = 1f + squash
                    scaleY = 1f - squash
                    // Lean into the direction of the walk, and tilt a little when looking around.
                    val heading = if (walking) {
                        if (targetPx > walkX) 1f else -1f
                    } else {
                        if (stroll < 0.47f) 1f else -1f
                    }
                    rotationZ = heading * walk * 3.5f + plazaStrollLook(stroll) * roam * 5f
                },
                contentAlignment = Alignment.Center,
            ) {
                MascotAvatar(
                    index = bot.mascot,
                    size = figure.dp,
                    active = busy,
                    mood = if (busy) MascotMood.WORKING else MascotMood.IDLE,
                    modifier = Modifier.graphicsLayer { alpha = if (asleep) 0.75f else 1f },
                )
                Canvas(modifier = Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 2.dp).size(18.dp)) {
                    plazaDrawProp(prop, (clock * loops) % 1f, accent)
                }
                if (busy) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-8).dp)
                            .background(Color.White, RoundedCornerShape(9.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Canvas(modifier = Modifier.size(width = 16.dp, height = 6.dp)) {
                            for (i in 0 until 3) {
                                val lift = max(0f, sin(typing * PLAZA_TAU - i * 0.9f))
                                drawCircle(
                                    color = plazaInk.copy(alpha = 0.35f + 0.5f * lift),
                                    radius = 2.2.dp.toPx(),
                                    center = Offset(size.width * (0.18f + i * 0.32f), size.height * 0.5f - lift * 2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
                if (asleep) {
                    for (i in 0 until 3) {
                        Text(
                            text = "z",
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-4).dp)
                                .graphicsLayer {
                                    val p = (zz + i * 0.33f) % 1f
                                    alpha = sin(p * PLAZA_PI)
                                    translationX = p * 8.dp.toPx()
                                    translationY = -p * 16.dp.toPx()
                                },
                            color = Color.White,
                            fontSize = (9 + i * 2).sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                text = bot.name,
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.95f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
/** Draws the job prop of one prisma. [t] is its loop, from 0 to 1. */
private fun DrawScope.plazaDrawProp(prop: PlazaProp, t: Float, accent: Color) {
    val w = size.width
    val h = size.height
    when (prop) {
        PlazaProp.LAPTOP -> {
            drawRoundRect(
                color = Color(0xFFC9CCD8),
                topLeft = Offset(w * 0.06f, h * 0.76f),
                size = Size(w * 0.88f, h * 0.12f),
                cornerRadius = CornerRadius(h * 0.06f),
            )
            drawRoundRect(
                color = Color(0xFF2A2D3C),
                topLeft = Offset(w * 0.16f, h * 0.12f),
                size = Size(w * 0.68f, h * 0.62f),
                cornerRadius = CornerRadius(h * 0.08f),
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.9f),
                topLeft = Offset(w * 0.26f, h * 0.26f),
                size = Size(w * 0.34f, h * 0.08f),
                cornerRadius = CornerRadius(h * 0.04f),
            )
            drawRoundRect(
                color = Color(0xFFB0B6C8),
                topLeft = Offset(w * 0.26f, h * 0.42f),
                size = Size(w * 0.46f, h * 0.08f),
                cornerRadius = CornerRadius(h * 0.04f),
            )
            // The blinking cursor on the next line.
            if (t < 0.5f) {
                drawRect(color = Color.White, topLeft = Offset(w * 0.26f, h * 0.58f), size = Size(w * 0.05f, h * 0.1f))
            }
        }
        PlazaProp.PLANNER -> {
            drawRoundRect(
                color = Color(0xFFE9DFC9),
                topLeft = Offset(w * 0.14f, h * 0.16f),
                size = Size(w * 0.72f, h * 0.8f),
                cornerRadius = CornerRadius(h * 0.08f),
            )
            drawRoundRect(
                color = Color(0xFF8E96AB),
                topLeft = Offset(w * 0.36f, h * 0.06f),
                size = Size(w * 0.28f, h * 0.16f),
                cornerRadius = CornerRadius(h * 0.05f),
            )
            for (i in 0 until 3) {
                drawRect(color = Color(0xFFB7AE98), topLeft = Offset(w * 0.24f, h * (0.38f + i * 0.17f)), size = Size(w * 0.22f, h * 0.05f))
            }
            // A page on top flips over its left edge.
            val flip = abs(cos(t * PLAZA_PI)).coerceAtLeast(0.05f)
            scale(flip, 1f, pivot = Offset(w * 0.5f, h * 0.5f)) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.85f),
                    topLeft = Offset(w * 0.5f, h * 0.3f),
                    size = Size(w * 0.3f, h * 0.56f),
                    cornerRadius = CornerRadius(h * 0.05f),
                )
            }
        }
        PlazaProp.NEWS -> {
            drawRoundRect(
                color = Color(0xFFF2EDDD),
                topLeft = Offset(w * 0.1f, h * 0.12f),
                size = Size(w * 0.8f, h * 0.8f),
                cornerRadius = CornerRadius(h * 0.06f),
            )
            drawRect(color = accent.copy(alpha = 0.8f), topLeft = Offset(w * 0.1f, h * 0.12f), size = Size(w * 0.08f, h * 0.8f))
            for (i in 0 until 3) {
                drawRect(
                    color = Color(0xFFB7B09C),
                    topLeft = Offset(w * 0.26f, h * (0.28f + i * 0.16f)),
                    size = Size(w * (0.54f - i * 0.1f), h * 0.05f),
                )
            }
            // A pencil that wiggles while it writes.
            val tip = Offset(w * 0.6f, h * 0.84f)
            rotate(degrees = sin(t * PLAZA_TAU) * 10f, pivot = tip) {
                drawLine(Color(0xFFFFD35A), start = tip, end = Offset(w * 0.92f, h * 0.46f), strokeWidth = h * 0.09f, cap = StrokeCap.Round)
            }
        }
        PlazaProp.PHONE -> {
            drawRoundRect(
                color = Color(0xFF2A2D3C),
                topLeft = Offset(w * 0.26f, h * 0.04f),
                size = Size(w * 0.48f, h * 0.92f),
                cornerRadius = CornerRadius(w * 0.12f),
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.55f + 0.45f * sin(t * PLAZA_TAU)),
                topLeft = Offset(w * 0.32f, h * 0.14f),
                size = Size(w * 0.36f, h * 0.68f),
                cornerRadius = CornerRadius(w * 0.04f),
            )
            drawCircle(color = Color(0xFFB0B6C8), radius = w * 0.04f, center = Offset(w * 0.5f, h * 0.88f))
        }
        PlazaProp.SEARCH -> {
            val center = Offset(w * 0.42f + sin(t * PLAZA_TAU) * w * 0.05f, h * 0.42f + cos(t * PLAZA_TAU) * h * 0.04f)
            val r = w * 0.26f
            drawCircle(color = Color.White.copy(alpha = 0.14f), radius = r, center = center)
            drawCircle(color = Color(0xFFE6E8F0), radius = r, center = center, style = Stroke(width = 2.dp.toPx()))
            drawLine(
                color = Color(0xFFE6E8F0),
                start = center + Offset(r * 0.7f, r * 0.7f),
                end = Offset(w * 0.86f, h * 0.86f),
                strokeWidth = 2.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = accent, radius = r * 0.22f, center = center + Offset(-r * 0.3f, -r * 0.3f))
        }
        PlazaProp.MUSIC -> {
            for (n in 0 until 2) {
                val p = (t + n * 0.5f) % 1f
                plazaNote(
                    x = w * (0.34f + n * 0.34f) + sin(p * PLAZA_TAU) * w * 0.05f,
                    y = h * 0.9f - p * h * 0.72f,
                    s = 1.1f,
                    color = accent.copy(alpha = sin(p * PLAZA_PI)),
                )
            }
        }
        PlazaProp.COIN -> {
            val bob = abs(sin(t * PLAZA_PI)) * h * 0.1f
            val center = Offset(w * 0.5f, h * 0.5f - bob)
            scale(abs(cos(t * PLAZA_PI)).coerceAtLeast(0.1f), 1f, pivot = center) {
                drawCircle(color = Color(0xFFFFD35A), radius = w * 0.4f, center = center)
                drawCircle(color = Color(0xFFE0A526), radius = w * 0.26f, center = center, style = Stroke(width = 1.2.dp.toPx()))
            }
        }
        PlazaProp.HEART -> {
            // Two little beats per loop.
            val beat = 1f + 0.16f * max(0f, sin(t * PLAZA_TAU * 2f))
            translate(left = w * 0.5f, top = h * 0.54f) {
                scale(w * 0.9f * beat, h * 0.9f * beat, pivot = Offset.Zero) {
                    drawPath(plazaHeart, Color(0xFFFF6B8A))
                }
            }
        }
        PlazaProp.DUMBBELL -> {
            val lift = (0.5f - 0.5f * cos(t * PLAZA_TAU)) * h * 0.2f
            val cy = h * 0.66f - lift
            drawRoundRect(
                color = Color(0xFFB8BDD0),
                topLeft = Offset(w * 0.2f, cy - h * 0.035f),
                size = Size(w * 0.6f, h * 0.07f),
                cornerRadius = CornerRadius(h * 0.035f),
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(w * 0.08f, cy - h * 0.2f),
                size = Size(w * 0.16f, h * 0.4f),
                cornerRadius = CornerRadius(w * 0.04f),
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(w * 0.76f, cy - h * 0.2f),
                size = Size(w * 0.16f, h * 0.4f),
                cornerRadius = CornerRadius(w * 0.04f),
            )
        }
        PlazaProp.SPARKLE -> {
            val s = 0.8f + 0.3f * abs(sin(t * PLAZA_PI))
            val floatY = sin(t * PLAZA_TAU) * h * 0.06f
            translate(left = w * 0.5f, top = h * 0.5f + floatY) {
                scale(w * 0.9f * s, h * 0.9f * s, pivot = Offset.Zero) {
                    drawPath(plazaStar, accent.copy(alpha = 0.7f + 0.3f * abs(sin(t * PLAZA_PI))))
                }
            }
        }
    }
}

private fun DrawScope.plazaNote(x: Float, y: Float, s: Float, color: Color) {
    val head = 2.4.dp.toPx() * s
    drawCircle(color = color, radius = head, center = Offset(x, y))
    drawLine(
        color = color,
        start = Offset(x + head * 0.9f, y),
        end = Offset(x + head * 0.9f, y - head * 4f),
        strokeWidth = 1.4.dp.toPx() * s,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.plazaCloud(left: Float, top: Float, width: Float, color: Color) {
    val r = width * 0.16f
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top + r),
        size = Size(width, r * 2.2f),
        cornerRadius = CornerRadius(r * 1.1f),
    )
    drawCircle(color = color, radius = r * 1.5f, center = Offset(left + width * 0.36f, top + r))
    drawCircle(color = color, radius = r * 1.9f, center = Offset(left + width * 0.62f, top + r * 0.9f))
}

// ---- Time of day --------------------------------------------------------------------------------

private fun plazaMinuteOfDay(): Int {
    val now = Calendar.getInstance()
    return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
}

private fun plazaMsToNextMinute(): Long {
    val now = Calendar.getInstance()
    return 60_000L - (now.get(Calendar.SECOND) * 1_000L + now.get(Calendar.MILLISECOND))
}

/** 1 in full daylight, 0 at night, ramping at dawn (5h-7h) and dusk (17h-19h30). */
private fun plazaDayness(time: Float): Float = when {
    time < 5f || time >= 19.5f -> 0f
    time < 7f -> (time - 5f) / 2f
    time < 17f -> 1f
    else -> (19.5f - time) / 2.5f
}

/** 0 while the windows are on, 1 late at night when some of them go dark. */
private fun plazaLateness(time: Float): Float = when {
    time >= 23f || time < 4f -> 1f
    time in 21.5f..23f -> (time - 21.5f) / 1.5f
    time in 4f..5.5f -> 1f - (time - 4f) / 1.5f
    else -> 0f
}

private class PlazaSkyStop(val at: Float, val top: Color, val mid: Color)

private val plazaSkyStops = listOf(
    PlazaSkyStop(0f, Color(0xFF07080F), Color(0xFF111326)),
    PlazaSkyStop(4.5f, Color(0xFF07080F), Color(0xFF111326)),
    PlazaSkyStop(6.5f, Color(0xFF2A1B3D), Color(0xFF5B2E4F)),
    PlazaSkyStop(9f, Color(0xFF16233F), Color(0xFF243B63)),
    PlazaSkyStop(16f, Color(0xFF16233F), Color(0xFF243B63)),
    PlazaSkyStop(18.5f, Color(0xFF3A1D3A), Color(0xFF6B2F3A)),
    PlazaSkyStop(20.5f, Color(0xFF07080F), Color(0xFF111326)),
    PlazaSkyStop(24f, Color(0xFF07080F), Color(0xFF111326)),
)

/** Sky gradient for [time] (hours, 0..24), blended between the dawn, day, dusk and night palettes. */
private fun plazaSkyColors(time: Float): List<Color> {
    val stops = plazaSkyStops
    var i = 0
    while (i < stops.lastIndex - 1 && time >= stops[i + 1].at) i++
    val a = stops[i]
    val b = stops[i + 1]
    val f = ((time - a.at) / (b.at - a.at)).coerceIn(0f, 1f)
    return listOf(lerp(a.top, b.top, f), lerp(a.mid, b.mid, f), plazaGround)
}

// ---- Movement -----------------------------------------------------------------------------------

private fun plazaSmooth(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * One stroll loop, p in 0..1: walk right (0 to 0.42), pause and look around, walk back (0.52 to
 * 0.94), pause at home. Returns the position from 0 (lane) to 1 (far end).
 */
private fun plazaStrollPos(p: Float): Float = when {
    p < 0.42f -> plazaSmooth(0f, 0.42f, p)
    p < 0.52f -> 1f
    p < 0.94f -> 1f - plazaSmooth(0.52f, 0.94f, p)
    else -> 0f
}

/** 1 while walking (hopping), 0 while pausing. Eased at the edges so the hops fade in and out. */
private fun plazaStrollWalk(p: Float): Float =
    plazaSmooth(0f, 0.04f, p) * (1f - plazaSmooth(0.40f, 0.44f, p)) +
        plazaSmooth(0.50f, 0.54f, p) * (1f - plazaSmooth(0.92f, 0.96f, p))

/** A small head turn during the pauses. */
private fun plazaStrollLook(p: Float): Float = when {
    p in 0.42f..0.52f -> sin((p - 0.42f) / 0.10f * PLAZA_TAU)
    p >= 0.94f -> sin((p - 0.94f) / 0.06f * PLAZA_TAU)
    else -> 0f
}

// ---- Job props ----------------------------------------------------------------------------------

private enum class PlazaProp { LAPTOP, PLANNER, NEWS, PHONE, SEARCH, MUSIC, COIN, HEART, DUMBBELL, SPARKLE }

/** Keywords (lowercase, no accents). A keyword matches a word that equals it or, for longer ones, starts with it. */
private val plazaPropRules: List<Pair<PlazaProp, List<String>>> = listOf(
    PlazaProp.LAPTOP to listOf("codigo", "codar", "software", "engenheir", "programa", "desenvolv", "dev", "devs", "bug", "deploy"),
    PlazaProp.PLANNER to listOf("planej", "agenda", "semana", "estud", "calendari", "rotina", "lembrete"),
    PlazaProp.NEWS to listOf("resumo", "resum", "noticia", "texto", "escrev", "redig", "artigo", "traduc", "traduz"),
    PlazaProp.PHONE to listOf("opera", "celular", "telefon", "tela", "telas", "app", "apps", "android", "aplicativo"),
    PlazaProp.SEARCH to listOf("pesquis", "busca", "buscar", "web", "navega", "google"),
    PlazaProp.MUSIC to listOf("musica", "som", "sons", "audio", "playlist", "podcast"),
    PlazaProp.COIN to listOf("financ", "dinheiro", "gasto", "orcament", "pagament", "fatura", "cobranca"),
    PlazaProp.HEART to listOf("saude", "medic", "nutri", "remedio", "fisio"),
    PlazaProp.DUMBBELL to listOf("treino", "academia", "exercic", "musculac", "corrida", "correr"),
)

private val plazaWordSplit = Regex("[^a-z0-9]+")
private val plazaDiacritics = Regex("\\p{Mn}+")

/** The prop for a prisma, taken from the words of its name and job. Default: a floating sparkle. */
private fun plazaPropFor(bot: Bot): PlazaProp {
    val words = Normalizer.normalize("${bot.name} ${bot.job}".lowercase(), Normalizer.Form.NFD)
        .replace(plazaDiacritics, "")
        .split(plazaWordSplit)
        .filter { it.isNotEmpty() }
    return plazaPropRules.firstOrNull { (_, keys) ->
        keys.any { key -> words.any { word -> word == key || (key.length >= 5 && word.startsWith(key)) } }
    }?.first ?: PlazaProp.SPARKLE
}

// ---- Shapes and constants -----------------------------------------------------------------------

private const val PLAZA_PI = 3.1415927f
private const val PLAZA_TAU = 6.2831855f

private val plazaGround = Color(0xFF14151C)
private val plazaInk = Color(0xFF14151C)
private val plazaBuilding = Color(0xFF1A1B24).copy(alpha = 0.92f)
private val plazaWindowOff = Color.White.copy(alpha = 0.06f)
private val plazaWindowOn = Color(0xFFFFD98A).copy(alpha = 0.8f)
private val plazaGold = Color(0xFFFFD98A)
private val plazaLampOff = Color(0xFF3A3D4E)
private val plazaLampPost = Color(0xFF2B2D3B)
private val plazaStage = Color(0xFF8B7CFF)
private val plazaSunCore = Color(0xFFFFF1B8)
private val plazaSunWarm = Color(0xFFFF9F6B)
private val plazaMoonCore = Color(0xFFFFF7DA)


// ---- The town's places and palette ----------------------------------------------------------------

/**
 * The places of the town, left to right, as fractions of the town width. [height] is the building
 * height in dp (its sign sits above it); the park has no building, only trees.
 */
private enum class PlazaPlace(val title: String, val from: Float, val to: Float, val height: Float) {
    PARK("Parque", 0f, 0.13f, 30f),
    HOUSES("Casas", 0.14f, 0.27f, 36f),
    LIBRARY("Biblioteca", 0.28f, 0.355f, 74f),
    LAB("Laboratório", 0.365f, 0.435f, 88f),
    HALL("Prefeitura", 0.445f, 0.555f, 112f),
    STUDIO("Estúdio", 0.565f, 0.635f, 72f),
    CAFE("Café", 0.645f, 0.715f, 64f),
    WORKSHOP("Oficina", 0.725f, 0.8f, 66f),
    OFFICE("Escritório", 0.81f, 0.885f, 96f);

    /** Middle of the place, as a fraction of the town width. */
    val center: Float get() = (from + to) / 2f
}

/** Keywords (lowercase, no accents) that send a prisma to a place. Anything else goes to the park. */
private val plazaPlaceRules: List<Pair<PlazaPlace, List<String>>> = listOf(
    PlazaPlace.OFFICE to listOf("codigo", "codar", "software", "engenheir", "programa", "desenvolv", "dev", "devs", "bug", "deploy", "escritorio"),
    PlazaPlace.LIBRARY to listOf("pesquis", "estud", "leitur", "leitor", "livro", "academ", "busca", "buscar", "web", "navega", "google"),
    PlazaPlace.CAFE to listOf("resumo", "resum", "texto", "escrev", "redig", "artigo", "traduc", "traduz", "noticia"),
    PlazaPlace.LAB to listOf("ciencia", "cientist", "dados", "dado", "analis", "estatist", "quimic", "laborat", "experiment"),
    PlazaPlace.WORKSHOP to listOf("opera", "celular", "telefon", "tela", "telas", "app", "apps", "android", "aplicativo", "sistema"),
    PlazaPlace.STUDIO to listOf("musica", "musical", "som", "sons", "audio", "playlist", "podcast", "arte", "artista", "design", "desenh", "ilustr"),
)

private fun plazaWordsOf(bot: Bot): List<String> =
    Normalizer.normalize("${bot.name} ${bot.job}".lowercase(), Normalizer.Form.NFD)
        .replace(plazaDiacritics, "")
        .split(plazaWordSplit)
        .filter { it.isNotEmpty() }

/** The place where a prisma works, from the words of its name and job. Default: the park. */
private fun plazaPlaceFor(bot: Bot): PlazaPlace {
    val words = plazaWordsOf(bot)
    return plazaPlaceRules.firstOrNull { (_, keys) ->
        keys.any { key -> words.any { word -> word == key || (key.length >= 5 && word.startsWith(key)) } }
    }?.first ?: PlazaPlace.PARK
}

/** Lamp posts along the sidewalk, as fractions of the town width. */
private val plazaLamps = listOf(0.135f, 0.275f, 0.36f, 0.64f, 0.805f, 0.93f)

/** The roof triangle, in unit coordinates: base on y = 0, apex at y = -1. Scaled where it is drawn. */
private val plazaRoof: Path = Path().apply {
    moveTo(-0.05f, 0f)
    lineTo(1.05f, 0f)
    lineTo(0.5f, -1f)
    close()
}

/** A sixteen-tooth gear, in unit coordinates. Turned by rotate() in the ambient layer. */
private val plazaGear: Path = Path().apply {
    for (i in 0 until 16) {
        val a = i * PLAZA_TAU / 16f
        val r = if (i % 2 == 0) 1f else 0.7f
        val x = cos(a) * r
        val y = sin(a) * r
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val plazaSidewalk = Color(0xFF1C1E2A)
private val plazaRoad = Color(0xFF101119)
private val plazaLaneMark = Color.White.copy(alpha = 0.16f)
private val plazaCornice = Color(0xFF262939)
private val plazaShelf = Color(0xFF3A2C1C)
private val plazaShelfLit = Color(0xFFFFD98A)
private val plazaBooks = listOf(Color(0xFFE35D6A), Color(0xFF5B8DEF), Color(0xFF7BD389), Color(0xFFF2C94C), Color(0xFFB79CF0))
private val plazaSignBoard = Color(0xFF2A2D44)
private val plazaFlask = Color(0xFF7DFFB2)
private val plazaGearColor = Color(0xFF2DD4BF)
private val plazaNoteColor = Color(0xFFF2C94C)
private val plazaCup = Color(0xFFFFF1E0)
private val plazaAwningA = Color(0xFFFF6B8A)
private val plazaAwningB = Color(0xFFF7F3EA)
private val plazaColumn = Color(0xFF3A3E56)
private val plazaDoor = Color(0xFF0E0F16)
private val plazaSteps = Color(0xFF30334A)
private val plazaRoofColor = Color(0xFF2B2E42)
private val plazaStone = Color(0xFF3A3E56)
private val plazaGrass = Color(0xFF1F3B2E)
private val plazaTree = Color(0xFF22533F)
private val plazaTrunk = Color(0xFF3B2A22)
private val plazaWater = Color(0xFF7DD3FC)
private val plazaScreen = Color(0xFF7DD3FC)
private val plazaPage = Color(0xFFF2EDDD)
private val plazaSteam = Color(0xFFFFFFFF)
private val plazaSmoke = Color(0xFFD7DBEA)
private val plazaFirefly = Color(0xFFFFE066)
private val plazaCat = Color(0xFF2B2E40)
private val plazaTram = Color(0xFF3E4A6B)
private val plazaTramWin = Color(0xFF2A3350)
private val plazaBird = Color(0xFFE6E8F0)
private val plazaFlag = Color(0xFF8B7CFF)
private val plazaEdgeFade = Brush.horizontalGradient(listOf(Color.Transparent, Color(0x66000000)))

private val plazaStars = listOf(
    0.08f to 0.12f, 0.22f to 0.28f, 0.37f to 0.1f, 0.55f to 0.22f, 0.68f to 0.08f, 0.83f to 0.2f,
    0.93f to 0.1f, 0.15f to 0.05f, 0.47f to 0.3f, 0.62f to 0.04f, 0.9f to 0.34f, 0.3f to 0.2f,
)

private class PlazaCloud(val x0: Float, val y: Float, val width: Float, val loops: Int)

private val plazaClouds = listOf(PlazaCloud(0.1f, 0.42f, 34f, 1), PlazaCloud(0.62f, 0.3f, 26f, 2))

private fun plazaHeartPath(): Path = Path().apply {
    moveTo(0f, 0.42f)
    cubicTo(-0.38f, 0.16f, -0.48f, -0.04f, -0.48f, -0.2f)
    cubicTo(-0.48f, -0.37f, -0.36f, -0.46f, -0.22f, -0.46f)
    cubicTo(-0.12f, -0.46f, -0.04f, -0.4f, 0f, -0.32f)
    cubicTo(0.04f, -0.4f, 0.12f, -0.46f, 0.22f, -0.46f)
    cubicTo(0.36f, -0.46f, 0.48f, -0.37f, 0.48f, -0.2f)
    cubicTo(0.48f, -0.04f, 0.38f, 0.16f, 0f, 0.42f)
    close()
}

private fun plazaStarPath(): Path = Path().apply {
    moveTo(0f, -0.5f)
    lineTo(0.1f, -0.1f)
    lineTo(0.5f, 0f)
    lineTo(0.1f, 0.1f)
    lineTo(0f, 0.5f)
    lineTo(-0.1f, 0.1f)
    lineTo(-0.5f, 0f)
    lineTo(-0.1f, -0.1f)
    close()
}

/** A light cone from the top edge down to the ground, in unit coordinates. */
private fun plazaCone(topLeft: Float, topRight: Float, bottomLeft: Float, bottomRight: Float): Path = Path().apply {
    moveTo(topLeft, 0f)
    lineTo(topRight, 0f)
    lineTo(bottomRight, 0.78f)
    lineTo(bottomLeft, 0.78f)
    close()
}

// Paths are built once and reused on every frame, in unit coordinates scaled at draw time.
private val plazaHeart: Path = plazaHeartPath()
private val plazaStar: Path = plazaStarPath()
private val plazaConeOuter: Path = plazaCone(0.44f, 0.56f, 0.3f, 0.7f)
private val plazaConeInner: Path = plazaCone(0.47f, 0.53f, 0.36f, 0.64f)

private fun plazaCaption(hour: Int, citizens: Int, busy: Int): String {
    val period = when (hour) {
        in 5..11 -> "manhã"
        in 12..17 -> "tarde"
        in 18..21 -> "noite"
        else -> "madrugada"
    }
    val who = if (citizens == 1) "1 Prisma" else "$citizens Prismas"
    return if (busy > 0) "$who · $busy trabalhando agora · $period" else "$who na praça · $period"
}
