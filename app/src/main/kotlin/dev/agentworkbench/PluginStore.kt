package dev.agentworkbench

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Plugin store. The catalog ships as assets/plugins/catalog.json and lists capabilities a Prisma
 * can use: native Android actions (android_native) or remote MCP servers (mcp_remote).
 * The enabled set lives in the "plugins" SharedPreferences. Enabling an mcp_remote plugin also
 * registers it as an MCP server, so the rest of the app sees it like any other server.
 */

/** One entry of the plugin catalog. */
internal data class PluginEntry(
    val id: String,
    val name: String,
    val emoji: String,
    val category: String,
    /** [PluginRegistry.KIND_NATIVE] or [PluginRegistry.KIND_MCP]. */
    val kind: String,
    val description: String,
    val capabilities: List<String> = emptyList(),
    /** "none", "oauth", "api_key" or "token". */
    val auth: String,
    /** Remote MCP endpoint; only for mcp_remote entries. */
    val url: String? = null,
    val docs: String? = null,
    /** "pronto", "oficial", "comunidade" or "a confirmar". */
    val status: String,
    val prismaHint: String,
    /** Tool names; only for android_native entries. */
    val tools: List<String> = emptyList(),
)

internal object PluginRegistry {
    const val KIND_NATIVE = "android_native"
    const val KIND_MCP = "mcp_remote"

    private const val CATALOG_ASSET = "plugins/catalog.json"
    private const val PREFS_NAME = "plugins"
    private const val KEY_ENABLED = "enabled"

    private val lock = Any()
    @Volatile private var cache: List<PluginEntry>? = null

    /**
     * The parsed catalog, loaded once per process. A missing or malformed file gives an empty
     * list instead of an exception.
     */
    fun catalog(context: Context): List<PluginEntry> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = runCatching { parseCatalog(readCatalog(context)) }.getOrDefault(emptyList())
            cache = loaded
            return loaded
        }
    }

    /** Ids of the plugins the user switched on. */
    fun enabledIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ENABLED, null)?.toSet() ?: SocietyTools.enabledPlugins(context)

    /**
     * Stores the enabled flag only. For mcp_remote entries call [syncMcp] as well so the MCP
     * server config matches the flag.
     */
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        synchronized(lock) {
            val next = enabledIds(context).toMutableSet()
            if (enabled) next.add(id) else next.remove(id)
            prefs(context).edit().putStringSet(KEY_ENABLED, next).apply()
        }
    }

    /** Catalog entries that are switched on, in catalog order. */
    fun enabled(context: Context): List<PluginEntry> {
        val ids = enabledIds(context)
        return catalog(context).filter { it.id in ids }
    }

    /** Compact block for the model listing the enabled plugins; empty when none is enabled. */
    fun promptFor(context: Context): String {
        val active = enabled(context)
        if (active.isEmpty()) return ""
        val lines = active.joinToString(separator = "\n") { entry ->
            val hint = entry.prismaHint.ifBlank { entry.description }
            val tools = if (entry.tools.isEmpty()) "" else " [ferramentas: ${entry.tools.joinToString(", ")}]"
            "- ${entry.name} (${entry.id}): $hint$tools"
        }
        return "<plugins>\n$lines\n</plugins>"
    }

    /**
     * Saves (or disables) the remote MCP server behind an mcp_remote plugin. Does nothing for
     * native plugins or entries without a URL. Throws if the database write fails.
     */
    suspend fun syncMcp(context: Context, entry: PluginEntry, enabled: Boolean) {
        if (entry.kind != KIND_MCP) return
        val url = entry.url?.takeIf { it.isNotBlank() } ?: return
        AgentPlatformRepository(context).saveMcpServer(
            McpServerConfig(
                id = "plugin-" + entry.id,
                name = entry.name,
                transport = McpTransportKind.STREAMABLE_HTTP.name,
                commandOrUrl = url,
                argumentsJson = "[]",
                environmentKeysJson = "[]",
                workspaceId = null,
                permissionPolicyJson = "{\"approval\":\"ask\"}",
                enabled = enabled,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readCatalog(context: Context): String =
        context.applicationContext.assets.open(CATALOG_ASSET).use { it.bufferedReader(Charsets.UTF_8).readText() }

    private fun parseCatalog(text: String): List<PluginEntry> {
        val plugins = JSONObject(text).optJSONArray("plugins") ?: return emptyList()
        val out = mutableListOf<PluginEntry>()
        for (index in 0 until plugins.length()) {
            val item = plugins.optJSONObject(index) ?: continue
            val entry = parseEntry(item) ?: continue
            out += entry
        }
        return out
    }

    private fun parseEntry(json: JSONObject): PluginEntry? {
        val id = json.pluginText("id")
        val name = json.pluginText("name")
        if (id.isEmpty() || name.isEmpty()) return null
        return PluginEntry(
            id = id,
            name = name,
            emoji = json.pluginText("emoji").ifEmpty { "🧩" },
            category = json.pluginText("category").ifEmpty { "Outros" },
            kind = json.pluginText("kind", KIND_NATIVE),
            description = json.pluginText("description"),
            capabilities = pluginStrings(json.optJSONArray("capabilities")),
            auth = json.pluginText("auth", "none"),
            url = json.pluginText("url").takeIf { it.isNotEmpty() },
            docs = json.pluginText("docs").takeIf { it.isNotEmpty() },
            status = json.pluginText("status", "a confirmar"),
            prismaHint = json.pluginText("prisma_hint"),
            tools = pluginStrings(json.optJSONArray("tools")),
        )
    }
}

private fun JSONObject.pluginText(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback).trim()

private fun pluginStrings(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index).trim().takeIf { it.isNotEmpty() && it != "null" }
    }
}

private const val PluginFilterAll = "Todos"
private const val PluginFilterActive = "Ativos"

private val PluginAmber = Color(0xFFF2B84B)

private val PluginAccents = listOf(
    WorkbenchTokens.Gold,
    WorkbenchTokens.Green,
    WorkbenchTokens.Purple,
    WorkbenchTokens.Sky,
    WorkbenchTokens.Coral,
    PluginAmber,
)

/** Stable accent per plugin: the same id always gets the same colour. */
private fun pluginAccent(id: String): Color =
    PluginAccents[(id.hashCode() and 0x7fffffff) % PluginAccents.size]

private fun pluginAuthLabel(auth: String): String = when (auth) {
    "oauth" -> "Login com a sua conta (OAuth)"
    "api_key" -> "Precisa de chave de API"
    "token" -> "Precisa de um token de acesso"
    else -> "Sem login"
}

/**
 * Plugin store tab: search, category chips, a hero card with the active count, the catalog as
 * cards, and a detail sheet per plugin. [onAskMaestro] receives a ready message for the assistant.
 */
@Composable
internal fun PluginStoreScreen(modifier: Modifier = Modifier, onAskMaestro: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // null while the catalog is still being read; then an empty list means "no catalog".
    val catalog by produceState<List<PluginEntry>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { PluginRegistry.catalog(context) }
    }
    val entries = catalog.orEmpty()
    var enabledIds by remember { mutableStateOf(PluginRegistry.enabledIds(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PluginFilterAll) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val filters = remember(entries) {
        listOf(PluginFilterAll, PluginFilterActive) +
            entries.map { it.category.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    val visible = remember(entries, enabledIds, filter, query) {
        val needle = query.trim()
        entries.filter { entry ->
            val inFilter = when (filter) {
                PluginFilterAll -> true
                PluginFilterActive -> entry.id in enabledIds
                else -> entry.category.trim() == filter
            }
            inFilter && (
                needle.isEmpty() ||
                    listOf(entry.name, entry.description, entry.category).any { it.contains(needle, ignoreCase = true) }
                )
        }
    }

    fun setActive(entry: PluginEntry, active: Boolean) {
        PluginRegistry.setEnabled(context, entry.id, active)
        enabledIds = PluginRegistry.enabledIds(context)
        scope.launch { runCatching { PluginRegistry.syncMcp(context, entry, active) } }
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(60) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            singleLine = true,
            placeholder = { Text("Buscar plugins", color = WorkbenchTokens.TextFaint) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = WorkbenchTokens.TextMuted,
                )
            },
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WorkbenchTokens.Border,
                unfocusedBorderColor = WorkbenchTokens.BorderSoft,
                cursorColor = WorkbenchTokens.Text,
            ),
        )
        LazyRow(
            modifier = Modifier.padding(vertical = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filters, key = { it }) { label ->
                PluginFilterChip(label = label, selected = filter == label) { filter = label }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { PluginHero(activeCount = entries.count { it.id in enabledIds }) }
            when {
                catalog == null -> items(3) { PluginSkeleton() }
                entries.isEmpty() -> item {
                    PluginEmptyState("O catálogo de plugins não está disponível neste aparelho.")
                }
                visible.isEmpty() -> item {
                    PluginEmptyState(
                        if (filter == PluginFilterActive && query.isBlank()) {
                            "Nenhum plugin ativo ainda. Toque em Ativar em algum plugin da lista."
                        } else {
                            "Nada encontrado. Tente outra busca ou filtro."
                        },
                    )
                }
                else -> itemsIndexed(visible, key = { _, entry -> entry.id }) { index, entry ->
                    PluginCard(
                        entry = entry,
                        index = index,
                        placement = Modifier.animateItem(),
                        active = entry.id in enabledIds,
                        onOpen = { openId = entry.id },
                        onToggle = { setActive(entry, entry.id !in enabledIds) },
                    )
                }
            }
        }
    }

    entries.firstOrNull { it.id == openId }?.let { opened ->
        PluginDetailSheet(
            entry = opened,
            active = opened.id in enabledIds,
            onToggle = { setActive(opened, opened.id !in enabledIds) },
            onAskMaestro = {
                openId = null
                onAskMaestro("Me ajude a configurar o plugin ${opened.name} (${opened.id}).")
            },
            onDismiss = { openId = null },
        )
    }
}

@Composable
private fun PluginHero(activeCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(WorkbenchTokens.GoldSoft, WorkbenchTokens.Surface)))
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Dê superpoderes aos seus Prismas",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = WorkbenchTokens.Text,
            )
            Text(
                text = "Conecte apps e serviços para que cada Prisma aja por você, sempre com a sua permissão.",
                style = MaterialTheme.typography.bodySmall,
                color = WorkbenchTokens.TextMuted,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PulseDot(if (activeCount > 0) WorkbenchTokens.Green else WorkbenchTokens.TextFaint)
                Text(
                    text = when (activeCount) {
                        0 -> "Nenhum plugin ativo ainda"
                        1 -> "1 plugin ativo"
                        else -> "$activeCount plugins ativos"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = WorkbenchTokens.TextMuted,
                )
            }
        }
        MascotAvatar(index = Mascots.forKey("plugins"), size = 64.dp, active = activeCount > 0, pokeOnTap = true)
    }
}

@Composable
private fun PluginSkeleton() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .height(94.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(20.dp))
            .shimmer(),
    )
}

@Composable
private fun PluginEmptyState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MascotAvatar(index = 4, size = 72.dp)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = WorkbenchTokens.TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PluginFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val textColor by animateColorAsState(
        if (selected) WorkbenchTokens.Canvas else WorkbenchTokens.TextMuted,
        label = "pluginChipText",
    )
    val fill by animateColorAsState(
        if (selected) WorkbenchTokens.Text else WorkbenchTokens.Surface,
        label = "pluginChipFill",
    )
    val edge by animateColorAsState(
        if (selected) WorkbenchTokens.Text else WorkbenchTokens.BorderSoft,
        label = "pluginChipEdge",
    )
    // Touch area at least 48dp; the chip keeps its size in the middle.
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .pressScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(fill)
                .border(1.dp, edge, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
        )
    }
}

@Composable
private fun PluginCard(
    entry: PluginEntry,
    index: Int,
    active: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    /** Lazy-list placement (animateItem), so filtering glides the cards instead of jumping. */
    placement: Modifier = Modifier,
) {
    Row(
        modifier = placement
            .fillMaxWidth()
            .appearOnce(key = "plugin:${entry.id}", index = index)
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(WorkbenchTokens.Surface)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(20.dp))
            .pressScale(pressedScale = 0.98f, onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PluginEmoji(entry = entry)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = WorkbenchTokens.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = WorkbenchTokens.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PluginStatusBadge(entry.status)
                Text(
                    text = entry.category,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = WorkbenchTokens.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PluginToggle(active = active, onToggle = onToggle)
    }
}

/** "Ativar" / "Ativo ✓" pill. It pops with a spring whenever the state changes, not on first draw. */
@Composable
private fun PluginToggle(active: Boolean, onToggle: () -> Unit) {
    val pop = remember { Animatable(1f) }
    var lastActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active != lastActive) {
            lastActive = active
            pop.snapTo(0.86f)
            pop.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }
    val fill by animateColorAsState(
        if (active) WorkbenchTokens.GreenSoft else WorkbenchTokens.Text,
        label = "pluginToggleFill",
    )
    val edge by animateColorAsState(
        if (active) WorkbenchTokens.Green.copy(alpha = 0.55f) else Color.Transparent,
        label = "pluginToggleEdge",
    )
    val content by animateColorAsState(
        if (active) WorkbenchTokens.Green else WorkbenchTokens.Canvas,
        label = "pluginToggleText",
    )
    Text(
        text = if (active) "Ativo ✓" else "Ativar",
        modifier = Modifier
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .clip(RoundedCornerShape(50))
            .background(fill)
            .border(1.dp, edge, RoundedCornerShape(50))
            .pressScale(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = content,
    )
}

@Composable
private fun PluginEmoji(entry: PluginEntry, size: Dp = 52.dp, fontSize: TextUnit = 24.sp) {
    val accent = pluginAccent(entry.id)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = entry.emoji.ifBlank { "🧩" }, fontSize = fontSize)
    }
}

@Composable
private fun PluginStatusBadge(status: String) {
    val normalized = status.trim().lowercase()
    val (label, color) = when (normalized) {
        "pronto" -> "Pronto" to WorkbenchTokens.Green
        "oficial" -> "Oficial" to WorkbenchTokens.Sky
        "comunidade" -> "Comunidade" to WorkbenchTokens.Purple
        "a confirmar", "" -> "A confirmar" to PluginAmber
        else -> normalized.replaceFirstChar { it.uppercase() } to WorkbenchTokens.TextMuted
    }
    Text(
        text = label,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun PluginSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = WorkbenchTokens.TextFaint,
    )
}

@Composable
private fun PluginBulletChip(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(WorkbenchTokens.SurfaceHigh)
            .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(WorkbenchTokens.Gold))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = WorkbenchTokens.Text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginDetailSheet(
    entry: PluginEntry,
    active: Boolean,
    onToggle: () -> Unit,
    onAskMaestro: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WorkbenchTokens.SurfaceRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PluginEmoji(entry = entry, size = 68.dp, fontSize = 32.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkbenchTokens.Text,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = entry.category,
                            style = MaterialTheme.typography.labelMedium,
                            color = WorkbenchTokens.TextMuted,
                        )
                        PluginStatusBadge(entry.status)
                    }
                }
            }
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = WorkbenchTokens.TextMuted,
            )
            if (entry.capabilities.isNotEmpty()) {
                PluginSectionLabel("O que ele faz")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.capabilities.forEach { PluginBulletChip(it) }
                }
            }
            PluginSectionLabel("Como o Prisma usa")
            Text(
                text = entry.prismaHint.ifBlank { entry.description },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(WorkbenchTokens.Surface)
                    .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = WorkbenchTokens.Text,
            )
            PluginSectionLabel("Acesso")
            Text(
                text = pluginAuthLabel(entry.auth),
                style = MaterialTheme.typography.bodyMedium,
                color = WorkbenchTokens.Text,
            )
            if (entry.kind == PluginRegistry.KIND_MCP && entry.auth == "oauth") {
                Text(
                    text = "O login OAuth abre no navegador na próxima versão; o Maestro pode te guiar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PluginAmber,
                )
            }
            entry.docs
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { docs ->
                    TextButton(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(docs))) } },
                    ) {
                        Text("Ver documentação", color = WorkbenchTokens.Sky)
                    }
                }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(if (active) WorkbenchTokens.SurfaceHigh else WorkbenchTokens.Text)
                    .pressScale(onClick = onToggle)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (active) "Desativar" else "Ativar",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) WorkbenchTokens.Text else WorkbenchTokens.Canvas,
                )
            }
            TextButton(onClick = onAskMaestro, modifier = Modifier.fillMaxWidth()) {
                Text("Pedir ao Maestro para configurar", color = WorkbenchTokens.TextMuted)
            }
        }
    }
}
