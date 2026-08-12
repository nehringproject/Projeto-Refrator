package dev.agentworkbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AgentPlatformSettingsCard(
    repository: AgentPlatformRepository,
    settings: ProviderSettings,
    availableSkills: List<AgentSkill>,
) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<AgentProfile>>(emptyList()) }
    var mcpServers by remember { mutableStateOf<List<McpServerConfig>>(emptyList()) }
    var commands by remember { mutableStateOf<List<CommandTemplate>>(emptyList()) }
    var profileName by remember { mutableStateOf("") }
    var profilePrompt by remember { mutableStateOf("") }
    var mcpName by remember { mutableStateOf("") }
    var mcpUrl by remember { mutableStateOf("") }
    var commandName by remember { mutableStateOf("") }
    var commandPrompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Carregando plataforma de agentes…") }
    var selectedProfileId by remember { mutableStateOf(repository.selectedProfileId()) }

    suspend fun reload() {
        repository.ensureDefaultProfile(settings)
        profiles = repository.profiles()
        mcpServers = repository.mcpServers()
        commands = repository.commands()
    }
    LaunchedEffect(repository, settings) {
        reload()
        status = "Perfis, MCP, hooks e comandos prontos."
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Plataforma de agentes", style = MaterialTheme.typography.titleMedium)
            Text(
                "Perfis especializados, até dois subagentes simultâneos, MCP e hooks auditáveis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profiles.forEach { profile ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${profile.providerPreset ?: "automático"} · ${profile.modelId ?: "modelo do chat"} · ${profile.maxDelegates} delegados",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (profile.systemPrompt.isNotBlank()) {
                            Text(profile.systemPrompt, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                        TextButton(
                            onClick = {
                                repository.selectProfile(profile.id)
                                selectedProfileId = profile.id
                            },
                        ) {
                            Text(if (selectedProfileId == profile.id) "✓ Perfil ativo" else "Usar neste app")
                        }
                    }
                }
            }
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nome do novo perfil") },
                singleLine = true,
            )
            OutlinedTextField(
                value = profilePrompt,
                onValueChange = { profilePrompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Prompt especializado") },
                minLines = 3,
                maxLines = 8,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = profileName.isNotBlank(),
                onClick = {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        repository.saveProfile(
                            AgentProfile(
                                id = UUID.randomUUID().toString(),
                                name = profileName.trim(),
                                description = "Perfil personalizado",
                                systemPrompt = profilePrompt.trim(),
                                providerPreset = settings.preset.name,
                                modelId = settings.modelId,
                                reasoningLevel = "provider_default",
                                toolPolicyJson = JSONObject().put("mode", settings.executionMode.name).toString(),
                                skillIdsJson = JSONArray(availableSkills.map(AgentSkill::id)).toString(),
                                mcpServerIdsJson = "[]",
                                browserProfileId = null,
                                maxDelegates = 2,
                                enabled = true,
                                createdAtMillis = now,
                                updatedAtMillis = now,
                            ),
                        )
                        profileName = ""
                        profilePrompt = ""
                        reload()
                    }
                },
            ) { Text("Criar perfil") }

            HorizontalDivider()
            Text("MCP remoto", style = MaterialTheme.typography.titleSmall)
            mcpServers.forEach { server ->
                Text(
                    "${server.name} · ${server.transport} · ${if (server.enabled) "ativo" else "desativado"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            OutlinedTextField(
                value = mcpName,
                onValueChange = { mcpName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nome do servidor MCP") },
                singleLine = true,
            )
            OutlinedTextField(
                value = mcpUrl,
                onValueChange = { mcpUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL Streamable HTTP/SSE") },
                singleLine = true,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = mcpName.isNotBlank() && mcpUrl.isNotBlank(),
                onClick = {
                    scope.launch {
                        repository.saveMcpServer(
                            McpServerConfig(
                                id = UUID.randomUUID().toString(),
                                name = mcpName.trim(),
                                transport = McpTransportKind.STREAMABLE_HTTP.name,
                                commandOrUrl = mcpUrl.trim(),
                                argumentsJson = "[]",
                                environmentKeysJson = "[]",
                                workspaceId = null,
                                permissionPolicyJson = JSONObject().put("default", "ask").toString(),
                                enabled = true,
                                updatedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                        mcpName = ""
                        mcpUrl = ""
                        reload()
                    }
                },
            ) { Text("Adicionar MCP") }

            HorizontalDivider()
            Text("Comandos reutilizáveis", style = MaterialTheme.typography.titleSmall)
            commands.forEach { command ->
                Text("/${command.name} · ${command.description}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = commandName,
                onValueChange = { commandName = it.filter { char -> char.isLetterOrDigit() || char in "_-" } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nome do comando") },
                singleLine = true,
            )
            OutlinedTextField(
                value = commandPrompt,
                onValueChange = { commandPrompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Template de prompt") },
                minLines = 2,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = commandName.isNotBlank() && commandPrompt.isNotBlank(),
                onClick = {
                    scope.launch {
                        repository.saveCommand(
                            CommandTemplate(
                                id = UUID.randomUUID().toString(),
                                name = commandName,
                                description = "Comando personalizado",
                                argumentsSchemaJson = "{}",
                                promptTemplate = commandPrompt,
                                defaultAgentProfileId = AgentPlatformRepository.DEFAULT_PROFILE_ID,
                                enabled = true,
                                updatedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                        commandName = ""
                        commandPrompt = ""
                        reload()
                    }
                },
            ) { Text("Salvar comando") }
            Text(status, style = MaterialTheme.typography.labelMedium)
        }
    }
}
