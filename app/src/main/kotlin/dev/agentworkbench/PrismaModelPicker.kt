package dev.agentworkbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentworkbench.provider.http.ModelCatalogResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picks the brain of one prisma: "same as the app" or any provider the user already set up, and
 * then a model from that provider's catalog (or typed by hand). [onChange] gets the preset name
 * and model id, both null for "same as the app".
 */
@Composable
internal fun PrismaModelPicker(
    providerPreset: String?,
    modelId: String?,
    onChange: (String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { ProviderSettingsRepository(context.applicationContext) }
    val allowCleartext = remember { TransportSecurityPreferences(context.applicationContext).allowLocalCleartext() }
    val app = remember { repository.load() }
    var configured by remember { mutableStateOf<List<ProviderPreset>>(emptyList()) }
    LaunchedEffect(Unit) {
        configured = withContext(Dispatchers.IO) { repository.configuredPresets(app, allowCleartext) }
    }
    val selected = providerPreset?.let { name -> runCatching { ProviderPreset.valueOf(name) }.getOrNull() }
    // The model is always chosen and pinned: a new (or old, never-set) Prisma starts with the app's
    // current provider and model saved as its own, so later changes to the app never move it.
    LaunchedEffect(selected) {
        if (selected == null) onChange(app.preset.name, app.modelId)
    }
    var models by remember(selected) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember(selected) { mutableStateOf(false) }
    var query by remember(selected) { mutableStateOf("") }
    LaunchedEffect(selected) {
        val preset = selected ?: return@LaunchedEffect
        loading = true
        val settings = repository.settingsForPrisma(Bot(name = "", job = "", providerPreset = preset.name), app)
        models = withContext(Dispatchers.IO) {
            when (val result = runCatching { repository.loadModelCatalog(settings, allowCleartext) }.getOrNull()) {
                is ModelCatalogResult.Success -> result.models.map { it.id }.distinct()
                else -> emptyList()
            }
        }
        loading = false
    }

    Column(modifier = modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Modelo deste Prisma", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextMuted)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            configured.forEach { preset ->
                ModelPickerChip(text = preset.displayName, selected = selected == preset) {
                    // Pin a concrete model right away, so the central changing models never changes this prisma.
                    if (selected != preset) onChange(preset.name, repository.currentModelFor(preset, app))
                }
            }
        }
        if (configured.isEmpty()) {
            Text(
                text = "Configure mais provedores em Perfil → Modelo e contas para escolher outros aqui.",
                style = MaterialTheme.typography.bodySmall,
                color = WorkbenchTokens.TextFaint,
            )
        }
        AnimatedVisibility(visible = selected != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = modelId ?: selected?.let { repository.settingsForPrisma(Bot(name = "", job = "", providerPreset = it.name), app).modelId }
                Text(
                    text = "Usando: ${current ?: "padrão do provedor"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WorkbenchTokens.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (loading) "Carregando modelos…" else "Buscar ou digitar o ID do modelo") },
                )
                val shown = models.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }.take(24)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (query.isNotBlank() && shown.none { it.equals(query.trim(), ignoreCase = true) }) {
                        ModelPickerChip(text = "Usar \"${query.trim()}\"", selected = false) { onChange(selected?.name, query.trim()) }
                    }
                    shown.forEach { id ->
                        ModelPickerChip(text = id.substringAfterLast('/'), selected = id == modelId) { onChange(selected?.name, id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val accent = if (selected) WorkbenchTokens.Gold else WorkbenchTokens.BorderSoft
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) WorkbenchTokens.Gold.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, accent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) WorkbenchTokens.Text else WorkbenchTokens.TextMuted,
        maxLines = 1,
    )
}
