package dev.agentworkbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Computador" in a Prisma's profile: whether its terminal is awake, its latest commands with
 * output and the files of its own folder. Read-only; the Prisma works there through its tools.
 */
@Composable
internal fun PrismaComputerSection(bot: Bot, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var history by remember(bot.id) { mutableStateOf("") }
    var files by remember(bot.id) { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var folder by remember(bot.id) { mutableStateOf("") }
    LaunchedEffect(bot.id, refresh) {
        withContext(Dispatchers.IO) {
            val place = PrismaComputer.placeFor(context, bot)
            folder = "prismas/${place.handle}/"
            history = PrismaComputer.recentHistory(context, bot, 8_000)
            files = PrismaComputer.files(context, bot, 120)
        }
    }
    val awake = bot.id in PrismaComputer.awake()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Computador", style = MaterialTheme.typography.labelLarge, color = WorkbenchTokens.TextMuted)
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (awake) WorkbenchTokens.Green else WorkbenchTokens.TextFaint),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                if (awake) "terminal ligado" else "terminal dormindo",
                style = MaterialTheme.typography.labelSmall,
                color = WorkbenchTokens.TextFaint,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) { Text("Atualizar") }
        }
        Text(
            "Ubuntu compartilhado · pasta própria $folder · navegador com logins próprios",
            style = MaterialTheme.typography.bodySmall,
            color = WorkbenchTokens.TextFaint,
        )
        Text("Últimos comandos", style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.Text)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp, max = 240.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(WorkbenchTokens.Canvas)
                .border(1.dp, WorkbenchTokens.BorderSoft, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            // Newest at the bottom, like a terminal; starts scrolled to the end.
            val scroll = rememberScrollState()
            LaunchedEffect(history) { scroll.scrollTo(scroll.maxValue) }
            Text(
                text = history.ifBlank { "Nenhum comando ainda." },
                modifier = Modifier.verticalScroll(scroll).horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = if (history.isBlank()) WorkbenchTokens.TextFaint else WorkbenchTokens.Text,
            )
        }
        Text("Arquivos (${files.size})", style = MaterialTheme.typography.labelMedium, color = WorkbenchTokens.Text)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(WorkbenchTokens.Surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (files.isEmpty()) {
                Text("A pasta está vazia.", style = MaterialTheme.typography.bodySmall, color = WorkbenchTokens.TextFaint)
            }
            files.forEach { (path, size) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        path,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (path.endsWith("/")) WorkbenchTokens.Gold else WorkbenchTokens.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (size >= 0) {
                        Text(readableSize(size), style = MaterialTheme.typography.labelSmall, color = WorkbenchTokens.TextFaint)
                    }
                }
            }
        }
    }
}

private fun readableSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}

/**
 * Shown over everything while a Prisma waits for the user to log in on its browser: the live page
 * of that Prisma's own browser. The model never sees what is typed here.
 */
@Composable
internal fun PrismaLoginHost() {
    val request by PrismaBrowsers.loginRequest.collectAsState()
    val current = request ?: return
    val context = LocalContext.current
    Dialog(
        onDismissRequest = { PrismaBrowsers.finishLogin(current, false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(WorkbenchTokens.Canvas)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Login para ${current.botName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = WorkbenchTokens.Text,
                )
                Text(
                    "${current.reason}. Entre na sua conta abaixo: o login fica salvo só no navegador de ${current.botName}, " +
                        "e o Prisma não vê o que você digita aqui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WorkbenchTokens.TextMuted,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).apply { PrismaBrowsers.viewFor(current)?.let(::addView) }
                    },
                )
            }
            DisposableEffect(current) {
                onDispose { PrismaBrowsers.releaseView(current, context) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { PrismaBrowsers.finishLogin(current, false) }) { Text("Cancelar") }
                Button(onClick = { PrismaBrowsers.finishLogin(current, true) }) { Text("Pronto, entrei") }
            }
        }
    }
}
