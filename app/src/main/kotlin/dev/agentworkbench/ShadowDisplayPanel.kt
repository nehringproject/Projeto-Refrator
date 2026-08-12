package dev.agentworkbench

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DistributionShadowDisplayPanel(@Suppress("UNUSED_PARAMETER") workspacePath: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember(context) { ShizukuShadowDisplayBridge.get(context.applicationContext) }
    var state by remember { mutableStateOf(bridge.snapshot()) }
    var frame by remember { mutableStateOf(bridge.latestFrame()) }
    var packageName by remember { mutableStateOf("com.android.settings") }
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Pronto para criar uma tela paralela 720×1600.") }

    LaunchedEffect(bridge) {
        while (true) {
            state = bridge.snapshot()
            val replacement = bridge.latestFrame()
            if (replacement != null) frame = replacement
            delay(750)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = WorkbenchTokens.Surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("ShadowDisplay · computer use paralelo")
            Text(
                if (state.active) {
                    "Ativo no display ${state.displayId} · ${state.width}×${state.height} · a tela física não recebe os toques"
                } else state.detail,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !state.active && state.supported,
                    onClick = {
                        scope.launch {
                            runCatching { bridge.start() }
                                .onSuccess { state = it; message = "Tela paralela criada." }
                                .onFailure { message = it.message ?: "Falha ao criar tela paralela." }
                        }
                    },
                ) { Text("Iniciar") }
                OutlinedButton(
                    enabled = state.active,
                    onClick = { scope.launch { bridge.stop(); state = bridge.snapshot() } },
                ) { Text("Parar") }
                OutlinedButton(
                    enabled = state.active,
                    onClick = { scope.launch { bridge.keyEvent(4) } },
                ) { Text("Voltar") }
                OutlinedButton(
                    enabled = state.active,
                    onClick = { scope.launch { bridge.keyEvent(3) } },
                ) { Text("Home") }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (state.width > 0) state.width.toFloat() / state.height else 0.45f)
                    .background(WorkbenchTokens.Canvas, RoundedCornerShape(12.dp))
                    .pointerInput(state.displayId, state.width, state.height) {
                        detectTapGestures { point ->
                            if (state.active) {
                                val x = (point.x / size.width * state.width).toInt().coerceIn(0, state.width - 1)
                                val y = (point.y / size.height * state.height).toInt().coerceIn(0, state.height - 1)
                                scope.launch { runCatching { bridge.tap(x, y) } }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                frame?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Prévia ao vivo da tela paralela",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                } ?: Text("A prévia aparecerá quando um aplicativo renderizar um frame.")
            }
            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Pacote Android") },
            )
            Button(
                enabled = state.active && packageName.isNotBlank(),
                onClick = {
                    scope.launch {
                        runCatching { bridge.launch(packageName.trim()) }
                            .onSuccess { message = "Aplicativo aberto no display ${state.displayId}." }
                            .onFailure { message = it.message ?: "Falha ao abrir aplicativo." }
                    }
                },
            ) { Text("Abrir na tela paralela") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(500) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Digitar na tela paralela") },
                )
                Button(
                    enabled = state.active && text.isNotBlank(),
                    onClick = { scope.launch { bridge.text(text); text = "" } },
                ) { Text("Enviar") }
            }
            Text(message)
            Text("Ao atingir temperatura crítica, pare a missão. FLAG_SECURE, biometria e DRM não são capturados.")
        }
    }
}
