package dev.agentworkbench

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One rate-limit window reported by the provider, e.g. "Janela de 5 horas" or "Semana". */
internal data class RateLimitWindow(val label: String, val usedPercent: Float?, val resetsAtMillis: Long?)

/** Everything the inspector shows. The caller rebuilds it as the conversation changes. */
internal data class InspectorState(
    val generating: Boolean,
    val statusLine: String,
    val activities: List<ToolActivity>,
    val images: List<File>,
    val contextTokens: Long?,
    val contextExact: Boolean,
    val contextWindowTokens: Int,
    val sessionInputTokens: Long,
    val sessionOutputTokens: Long,
    val sessionCachedTokens: Long,
    val turns: Int,
    val rateLimits: List<RateLimitWindow>,
    val providerName: String,
    val modelId: String,
    val capabilities: String,
)

private val InspectorTabTitles = listOf("Atividade", "Imagens", "Contexto", "Uso")

private const val InspectorPreviewLinesClosed = 3
private const val InspectorPreviewLinesOpen = 12

/**
 * Bottom sheet with four swipeable tabs (activity timeline, images, context gauge, usage).
 * It stays live: while [InspectorState.generating] is true the activity list follows new rows.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ConversationInspectorSheet(
    state: InspectorState,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    imageContent: @Composable (file: File, modifier: Modifier, onClick: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState(
        initialPage = initialTab.coerceIn(0, InspectorTabTitles.lastIndex),
        pageCount = { InspectorTabTitles.size },
    )
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WorkbenchTokens.Surface,
        contentColor = WorkbenchTokens.Text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = WorkbenchTokens.Border) },
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            InspectorTabRow(
                pagerState = pagerState,
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                when (page) {
                    0 -> InspectorActivityPage(state)
                    1 -> InspectorImagesPage(state.images, imageContent)
                    2 -> InspectorContextPage(state)
                    else -> InspectorUsagePage(state)
                }
            }
        }
    }
}

/** Segmented tabs. The underline follows the pager live, so drags and taps move it the same way. */
@Composable
private fun InspectorTabRow(pagerState: PagerState, onSelect: (Int) -> Unit) {
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val tabCount = InspectorTabTitles.size
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onSizeChanged { rowWidthPx = it.width },
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                InspectorTabTitles.forEachIndexed { index, title ->
                    val isSelected = pagerState.currentPage == index
                    val tint by animateColorAsState(
                        targetValue = if (isSelected) WorkbenchTokens.Text else WorkbenchTokens.TextMuted,
                        animationSpec = tween(durationMillis = 240),
                        label = "inspectorTabTint",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(role = Role.Tab, onClick = { onSelect(index) })
                            .semantics { selected = isSelected },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            color = tint,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(1f / tabCount)
                    .height(2.dp)
                    .graphicsLayer {
                        translationX =
                            (pagerState.currentPage + pagerState.currentPageOffsetFraction) * rowWidthPx / tabCount
                    }
                    .padding(horizontal = 22.dp)
                    .background(WorkbenchTokens.Gold, CircleShape),
            )
        }
        HorizontalDivider(color = WorkbenchTokens.BorderSoft)
    }
}

// ---------------------------------------------------------------------------------------------
// Tab 1: activity timeline
// ---------------------------------------------------------------------------------------------

@Composable
private fun InspectorActivityPage(state: InspectorState) {
    val activities = state.activities
    // Reversed so index 0 (the newest row) sits at the bottom; a reversed list stays anchored there.
    val newestFirst = remember(activities) { activities.asReversed() }
    val listState = rememberLazyListState()
    val expandedIds = remember { mutableStateMapOf<String, Boolean>() }
    val generatingNow by rememberUpdatedState(state.generating)

    LaunchedEffect(activities.size) {
        if (generatingNow && activities.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        InspectorStatusHeader(generating = state.generating, statusLine = state.statusLine)
        AnimatedContent(
            targetState = activities.isEmpty(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
            label = "inspectorActivityContent",
        ) { empty ->
            if (empty) {
                InspectorEmptyText("Nenhuma ferramenta usada ainda nesta conversa.")
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = newestFirst,
                        key = { index, activity -> "${activities.lastIndex - index}:${activity.callId}" },
                    ) { index, activity ->
                        val rowKey = "${activities.lastIndex - index}:${activity.callId}"
                        InspectorActivityRow(
                            activity = activity,
                            expanded = expandedIds[rowKey] == true,
                            onToggle = { expandedIds[rowKey] = expandedIds[rowKey] != true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorStatusHeader(generating: Boolean, statusLine: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        AnimatedVisibility(
            visible = generating,
            enter = fadeIn(tween(200)) + expandVertically(),
            exit = fadeOut(tween(200)) + shrinkVertically(),
        ) {
            InspectorIndeterminateLine(modifier = Modifier.padding(bottom = 10.dp))
        }
        AnimatedContent(
            targetState = statusLine,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "inspectorStatusLine",
        ) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelMedium,
                color = if (generating) WorkbenchTokens.Gold else WorkbenchTokens.TextMuted,
            )
        }
    }
}

/** Slim indeterminate line: a soft violet segment sweeping across a hairline track. */
@Composable
private fun InspectorIndeterminateLine(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "inspectorLine")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_400, easing = LinearEasing)),
        label = "inspectorLinePhase",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(WorkbenchTokens.BorderSoft),
    ) {
        val segment = size.width * 0.32f
        val start = -segment + (size.width + segment) * phase
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, WorkbenchTokens.Gold, Color.Transparent),
                startX = start,
                endX = start + segment,
            ),
            topLeft = Offset(start, 0f),
            size = Size(segment, size.height),
        )
    }
}

@Composable
private fun InspectorActivityRow(
    activity: ToolActivity,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val preview = activity.resultPreview?.takeIf { it.isNotBlank() }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WorkbenchTokens.SurfaceRaised)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .clickable(enabled = preview != null, onClick = onToggle)
            .animateContentSize()
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        InspectorStatusGlyph(status = activity.status)
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = activity.toolName.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp,
                ),
                color = WorkbenchTokens.TextMuted,
            )
            Text(
                text = activity.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = WorkbenchTokens.Text,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (preview != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = WorkbenchTokens.TextMuted,
                    maxLines = if (expanded) InspectorPreviewLinesOpen else InspectorPreviewLinesClosed,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(WorkbenchTokens.Canvas)
                        .padding(8.dp),
                )
                Text(
                    text = if (expanded) "Recolher" else "Ver resultado",
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.Gold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Status glyph: spinner while running, check, cross on failure or denial, bang while waiting. */
@Composable
private fun InspectorStatusGlyph(status: ToolActivityStatus) {
    Crossfade(targetState = status, label = "inspectorGlyph") { current ->
        Box(
            modifier = Modifier
                .size(18.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = inspectorStatusLabel(current)
                },
            contentAlignment = Alignment.Center,
        ) {
            when (current) {
                ToolActivityStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = WorkbenchTokens.Gold,
                    strokeWidth = 1.5.dp,
                    trackColor = WorkbenchTokens.GoldSoft,
                )
                ToolActivityStatus.COMPLETE -> Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelLarge,
                    color = WorkbenchTokens.Green,
                )
                ToolActivityStatus.FAILED, ToolActivityStatus.DENIED -> Text(
                    text = "✕",
                    style = MaterialTheme.typography.labelLarge,
                    color = WorkbenchTokens.Red,
                )
                ToolActivityStatus.AWAITING_APPROVAL, ToolActivityStatus.AWAITING_INPUT -> Text(
                    text = "!",
                    style = MaterialTheme.typography.labelLarge,
                    color = WorkbenchTokens.Gold,
                )
            }
        }
    }
}

private fun inspectorStatusLabel(status: ToolActivityStatus): String = when (status) {
    ToolActivityStatus.AWAITING_APPROVAL -> "Aguardando aprovação"
    ToolActivityStatus.AWAITING_INPUT -> "Aguardando resposta"
    ToolActivityStatus.RUNNING -> "Em execução"
    ToolActivityStatus.COMPLETE -> "Concluída"
    ToolActivityStatus.FAILED -> "Falhou"
    ToolActivityStatus.DENIED -> "Negada"
}

// ---------------------------------------------------------------------------------------------
// Tab 2: images
// ---------------------------------------------------------------------------------------------

@Composable
private fun InspectorImagesPage(
    images: List<File>,
    imageContent: @Composable (file: File, modifier: Modifier, onClick: () -> Unit) -> Unit,
) {
    var preview by remember { mutableStateOf<File?>(null) }
    AnimatedContent(
        targetState = images.isEmpty(),
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
        label = "inspectorImagesContent",
    ) { empty ->
        if (empty) {
            InspectorEmptyText("Nenhuma imagem nesta conversa.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(items = images, key = { _, file -> file.absolutePath }) { index, file ->
                    imageContent(
                        file,
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(WorkbenchTokens.SurfaceRaised)
                            .semantics { contentDescription = "Imagem ${index + 1} de ${images.size}" },
                    ) { preview = file }
                }
            }
        }
    }
    preview?.let { file ->
        InspectorImagePreview(
            file = file,
            imageContent = imageContent,
            onDismiss = { preview = null },
        )
    }
}

@Composable
private fun InspectorImagePreview(
    file: File,
    imageContent: @Composable (file: File, modifier: Modifier, onClick: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(WorkbenchTokens.Canvas),
            contentAlignment = Alignment.Center,
        ) {
            imageContent(
                file,
                Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(16.dp)),
                onDismiss,
            )
            Text(
                text = "Fechar",
                style = MaterialTheme.typography.labelLarge,
                color = WorkbenchTokens.Text,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tab 3: context gauge
// ---------------------------------------------------------------------------------------------

@Composable
private fun InspectorContextPage(state: InspectorState) {
    val windowTokens = state.contextWindowTokens.coerceAtLeast(1)
    val contextTokens = state.contextTokens
    val fraction = contextTokens?.let { (it.toFloat() / windowTokens).coerceIn(0f, 1f) }
    val animatedFraction by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "inspectorContextGauge",
    )
    val percentText = if (fraction == null) {
        "—"
    } else {
        "${if (state.contextExact) "" else "~"}${(animatedFraction * 100f).roundToInt()}%"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (fraction == null) {
                        "Contexto: sem contagem ainda"
                    } else {
                        "Contexto usado: $percentText"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            InspectorGauge(
                fraction = animatedFraction,
                color = inspectorUsageColor(animatedFraction),
                modifier = Modifier.fillMaxSize(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = percentText,
                    style = MaterialTheme.typography.displaySmall,
                    color = WorkbenchTokens.Text,
                )
                Text(
                    text = "do contexto",
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.TextMuted,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = if (contextTokens == null) {
                "Sem contagem de tokens nesta conversa ainda."
            } else {
                "${inspectorCompact(contextTokens)} tokens usados de ${inspectorCompact(windowTokens.toLong())}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = WorkbenchTokens.Text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                contextTokens == null -> "A contagem aparece depois da primeira resposta."
                state.contextExact -> "Contagem exata informada pelo provedor."
                else -> "Estimativa; o provedor não informou a contagem exata."
            },
            style = MaterialTheme.typography.labelSmall,
            color = WorkbenchTokens.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(28.dp))
        InspectorInfoCard {
            InspectorInfoRow(label = "Provedor", value = state.providerName)
            InspectorInfoRow(label = "Modelo", value = state.modelId)
            if (state.capabilities.isNotBlank()) {
                InspectorInfoRow(label = "Capacidades", value = state.capabilities)
            }
        }
    }
}

/** Ring gauge: a muted track with the usage arc drawn over it. */
@Composable
private fun InspectorGauge(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokePx = 12.dp.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = WorkbenchTokens.SurfaceHigh,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx),
        )
        if (fraction > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tab 4: usage
// ---------------------------------------------------------------------------------------------

@Composable
private fun InspectorUsagePage(state: InspectorState) {
    // Refreshes the "renova em" countdown every 30 seconds while this page is visible.
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val totalTokens = state.sessionInputTokens + state.sessionOutputTokens

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InspectorSectionLabel("Sessão")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InspectorStatCard(label = "Entrada", value = state.sessionInputTokens, modifier = Modifier.weight(1f))
            InspectorStatCard(label = "Saída", value = state.sessionOutputTokens, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InspectorStatCard(label = "Em cache", value = state.sessionCachedTokens, modifier = Modifier.weight(1f))
            InspectorStatCard(label = "Total", value = totalTokens, modifier = Modifier.weight(1f))
        }
        InspectorInfoCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Turnos",
                    style = MaterialTheme.typography.labelMedium,
                    color = WorkbenchTokens.TextMuted,
                )
                InspectorAnimatedCount(
                    value = state.turns.toLong(),
                    style = MaterialTheme.typography.titleLarge,
                    color = WorkbenchTokens.Text,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        InspectorSectionLabel("Limites de uso")
        if (state.rateLimits.isEmpty()) {
            Text(
                text = "O provedor não informa limites de uso por janela.",
                style = MaterialTheme.typography.bodyMedium,
                color = WorkbenchTokens.TextMuted,
            )
        } else {
            state.rateLimits.forEach { window ->
                InspectorRateLimitRow(window = window, now = now)
            }
        }
    }
}

@Composable
private fun InspectorStatCard(label: String, value: Long, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(WorkbenchTokens.SurfaceRaised)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: ${inspectorCompact(value)} tokens"
            },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = WorkbenchTokens.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        InspectorAnimatedCount(
            value = value,
            style = MaterialTheme.typography.headlineSmall,
            color = WorkbenchTokens.Text,
        )
    }
}

/** Number that counts up or down to [value] instead of jumping. */
@Composable
private fun InspectorAnimatedCount(
    value: Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = value.coerceAtLeast(0L).toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "inspectorCount",
    )
    Text(
        text = inspectorCompact(animated.roundToLong()),
        style = style,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun InspectorRateLimitRow(window: RateLimitWindow, now: Long) {
    val fraction = window.usedPercent?.let { (it / 100f).coerceIn(0f, 1f) }
    val animatedFraction by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "inspectorRateLimitBar",
    )
    val usageText = window.usedPercent?.let { "${it.roundToInt()}% usado" } ?: NOT_REPORTED
    val usageColor = if (fraction == null) WorkbenchTokens.TextMuted else inspectorUsageColor(fraction)
    val resetText = window.resetsAtMillis?.let { inspectorResetText(it, now) } ?: NOT_REPORTED
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WorkbenchTokens.SurfaceRaised)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${window.label}: $usageText. $resetText"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = window.label,
                style = MaterialTheme.typography.titleSmall,
                color = WorkbenchTokens.Text,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(
                text = usageText,
                style = MaterialTheme.typography.labelMedium,
                color = usageColor,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(WorkbenchTokens.SurfaceHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .fillMaxHeight()
                    .background(inspectorUsageColor(animatedFraction)),
            )
        }
        Text(
            text = resetText,
            style = MaterialTheme.typography.labelSmall,
            color = WorkbenchTokens.TextMuted,
        )
    }
}

@Composable
private fun InspectorInfoCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WorkbenchTokens.SurfaceRaised)
            .border(1.dp, WorkbenchTokens.BorderSoft, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun InspectorInfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = WorkbenchTokens.TextMuted,
        )
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = WorkbenchTokens.Text,
        )
    }
}

@Composable
private fun InspectorSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
        color = WorkbenchTokens.TextMuted,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun InspectorEmptyText(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = WorkbenchTokens.TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

private const val NOT_REPORTED = "não informado pelo provedor"

/** Green at low usage, violet in the middle, red near the limit. */
private fun inspectorUsageColor(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return if (f <= 0.5f) {
        lerp(WorkbenchTokens.Green, WorkbenchTokens.Gold, f / 0.5f)
    } else {
        lerp(WorkbenchTokens.Gold, WorkbenchTokens.Red, (f - 0.5f) / 0.5f)
    }
}

/** Compact token count: 950, 12.3k, 1.2M. Rounds up into the next unit when needed. */
private fun inspectorCompact(value: Long): String {
    if (value < 1_000L) return value.toString()
    val (divisor, suffix) = when {
        value >= 999_950_000L -> 1_000_000_000L to "B"
        value >= 999_950L -> 1_000_000L to "M"
        else -> 1_000L to "k"
    }
    val tenths = (value * 10 + divisor / 2) / divisor
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) "$whole$suffix" else "$whole.$fraction$suffix"
}

/** "renova em 2h 5min" or "renova em 40min", rounded up to the minute. */
private fun inspectorResetText(resetsAtMillis: Long, now: Long): String {
    val remainingMinutes = (resetsAtMillis - now).coerceAtLeast(0L).let { (it + 59_999L) / 60_000L }
    if (remainingMinutes == 0L) return "renova agora"
    val hours = remainingMinutes / 60L
    val minutes = remainingMinutes % 60L
    return if (hours > 0L) "renova em ${hours}h ${minutes}min" else "renova em ${minutes}min"
}
