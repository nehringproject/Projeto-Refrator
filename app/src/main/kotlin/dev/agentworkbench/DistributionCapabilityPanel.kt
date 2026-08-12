package dev.agentworkbench

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

object MediaProjectionGrantStore {
    @Volatile var resultCode: Int? = null
    @Volatile var data: Intent? = null
    fun clear() { resultCode = null; data = null }
}

@Composable
fun DistributionCapabilityPanel() {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val authorizations = remember(context) { CapabilityAuthorizationStore(context) }
    val executions = remember(context) { ExecutionRepository(context) }
    val documentTree = remember(context) { DocumentTreeAccess(context) }
    var revision by remember { mutableIntStateOf(0) }
    var mediaProjectionGranted by remember { mutableStateOf(MediaProjectionGrantStore.data != null) }
    var vpnPrepared by remember { mutableStateOf(VpnService.prepare(context) == null) }
    val packageUri = remember(context) { Uri.parse("package:${context.packageName}") }

    val refresh: () -> Unit = { revision += 1 }

    // As concessões abaixo acontecem numa tela do Android, fora do app. Quem manda o usuário
    // pra lá é o open(), que não recebe resultado nenhum de volta — então a única hora
    // confiável de reler o estado é quando esta tela volta ao primeiro plano. Antes o refresh
    // rodava na linha seguinte ao startActivity, ou seja, antes de o usuário sequer sair daqui,
    // e a linha continuava dizendo "indisponível" mesmo depois de conceder.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vpnPrepared = VpnService.prepare(context) == null
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            MediaProjectionGrantStore.resultCode = result.resultCode
            MediaProjectionGrantStore.data = result.data
            mediaProjectionGranted = true
        }
        refresh()
    }
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vpnPrepared = result.resultCode == Activity.RESULT_OK || VpnService.prepare(context) == null
        refresh()
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { documentTree.grant(uri) }
        refresh()
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }

    fun authorized(id: String): Boolean {
        revision
        return authorizations.isAuthorized(id)
    }
    fun setAuthorized(id: String, enabled: Boolean, source: String, scopeJson: String) {
        authorizations.setAuthorized(id, enabled)
        revision += 1
        scope.launch {
            executions.setCapabilityLease(id, enabled, scopeJson, source)
        }
    }
    fun open(intent: Intent) {
        // Sem refresh() aqui: ele rodaria antes de o usuário chegar na tela do Android.
        // Quem resincroniza é o observador de ON_RESUME lá em cima.
        runCatching { activity.startActivity(intent) }
    }

    val accessibilityGranted = secureComponentEnabled(
        context,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ComponentName(context, AgentAccessibilityService::class.java),
    )
    val notificationListenerGranted = secureComponentEnabled(
        context,
        "enabled_notification_listeners",
        ComponentName(context, AgentNotificationListenerService::class.java),
    )
    val appOps = context.getSystemService(AppOpsManager::class.java)
    @Suppress("DEPRECATION")
    val usageMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
    } else {
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
    }
    val usageGranted = usageMode == AppOpsManager.MODE_ALLOWED
    val overlayGranted = Settings.canDrawOverlays(context)
    // Android throws SecurityException when this API is queried without
    // REQUEST_INSTALL_PACKAGES. The public flavor deliberately omits that
    // restricted permission, so never call the API there.
    val installGranted = BuildConfig.DEVELOPER_BUILD && runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)
    val batteryGranted = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)
    val postNotificationsGranted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val safGranted = documentTree.status().granted
    val shizuku = DistributionBindings.privilegedShellBridge(context)
    val shizukuStatus = shizuku.snapshot()
    val homeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        roleManager.isRoleAvailable(RoleManager.ROLE_HOME) && roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    } else {
        false
    }
    val deviceOwner = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        .isDeviceOwnerApp(context.packageName)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Central de Capacidades", style = MaterialTheme.typography.titleMedium)
            Text(
                "Cada acesso exige a concessão do Android e uma autorização separada no app. " +
                    "Revogar a chave interna interrompe novas ações do agente mesmo se o Android continuar concedendo.",
                style = MaterialTheme.typography.bodySmall,
            )
            CapabilityRow("Accessibility", "Árvore de UI, gestos e ações globais", accessibilityGranted, authorized("accessibility"), "AccessibilityService", {
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { setAuthorized("accessibility", it, "android_accessibility", "all_enabled_apps") }
            CapabilityRow("Notificações", "Lista local redigida", notificationListenerGranted, authorized("notifications"), "NotificationListenerService", {
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { setAuthorized("notifications", it, "notification_listener", "posted_notifications") }
            if (BuildConfig.DEVELOPER_BUILD) {
                CapabilityRow("Uso de apps", "Recurso em desenvolvimento", usageGranted, authorized("usage_stats"), "AppOps", {
                    open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri))
                }) { setAuthorized("usage_stats", it, "usage_access", "device_usage") }
            }
            if (BuildConfig.DEVELOPER_BUILD) {
                CapabilityRow("Captura de tela", "Recurso em desenvolvimento", mediaProjectionGranted, authorized("media_projection"), "MediaProjection", {
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                }) {
                    if (!it) { MediaProjectionGrantStore.clear(); mediaProjectionGranted = false }
                    setAuthorized("media_projection", it, "media_projection", "current_session")
                }
            }
            CapabilityRow("Pasta externa", "Árvore SAF escolhida pelo usuário", safGranted, authorized("external_tree"), "Storage Access Framework", {
                treeLauncher.launch(null)
            }) { setAuthorized("external_tree", it, "saf", documentTree.status().uri ?: "none") }
            CapabilityRow("Shizuku", "UID shell real; não equivale a root", shizukuStatus.permissionGranted, authorized("shizuku"), "Shizuku", {
                shizuku.requestPermission(activity, 7_202)
                refresh()
            }) { setAuthorized("shizuku", it, "shizuku", "uid=${shizukuStatus.uid}") }
            if (BuildConfig.DEVELOPER_BUILD) {
                CapabilityRow("Overlay", "Recurso em desenvolvimento", overlayGranted, authorized("overlay"), "AppOps overlay", {
                    open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
                }) { setAuthorized("overlay", it, "overlay", "package") }
            }
            if (BuildConfig.DEVELOPER_BUILD) {
                CapabilityRow("Instalar APK", "Recurso em desenvolvimento", installGranted, authorized("apk_install"), "Package Installer", {
                    open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
                }) { setAuthorized("apk_install", it, "unknown_sources", "package") }
            }
            if (BuildConfig.DEVELOPER_BUILD) {
                CapabilityRow("VPN local", "Recurso em desenvolvimento", vpnPrepared, authorized("vpn"), "VpnService", {
                    VpnService.prepare(context)?.let(vpnLauncher::launch) ?: run { vpnPrepared = true }
                }) { setAuthorized("vpn", it, "vpn_prepare", "local_metadata_only") }
            }
            CapabilityRow("Segundo plano", "Ignorar otimização de bateria", batteryGranted, authorized("background_power"), "Doze allowlist", {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
            }) { setAuthorized("background_power", it, "battery_optimization", "agent_runs") }
            CapabilityRow("Notificação do serviço", "Progresso, pausar e parar", postNotificationsGranted, authorized("service_notifications"), "Runtime permission", {
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { setAuthorized("service_notifications", it, "runtime_permission", "agent_execution") }
            CapabilityRow("Launcher padrão", "Papel HOME opcional", homeGranted, authorized("launcher_role"), "RoleManager", {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val alias = ComponentName(
                        context.packageName,
                        "dev.agentworkbench.RefratorHomeActivity",
                    )
                    context.packageManager.setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                    val roleManager = context.getSystemService(RoleManager::class.java)
                    if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                        roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    }
                }
            }) { setAuthorized("launcher_role", it, "role_manager", "home") }
            if (BuildConfig.DEVELOPER_BUILD) {
                CapabilityRow("Device Owner", "Recurso em desenvolvimento", deviceOwner, authorized("device_owner"), "DevicePolicyManager", {
                    open(Intent(Settings.ACTION_SETTINGS))
                }) { setAuthorized("device_owner", it, "device_policy", "dedicated_device") }
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = refresh) { Text("Atualizar estados") }
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }) { Text("Assistente padrão · configuração opcional") }
        }
    }
}

@Composable
private fun CapabilityRow(
    title: String,
    scope: String,
    androidGranted: Boolean,
    appAuthorized: Boolean,
    source: String,
    onRequestAndroid: () -> Unit,
    onAuthorizedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Android: ${if (androidGranted) "concedido" else "indisponível"} · fonte: $source · $scope",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Switch(
                checked = appAuthorized,
                enabled = androidGranted,
                onCheckedChange = onAuthorizedChange,
            )
        }
        if (!androidGranted) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onRequestAndroid) {
                Text("Conceder no Android")
            }
        }
    }
}

private fun secureComponentEnabled(context: Context, key: String, component: ComponentName): Boolean =
    Settings.Secure.getString(context.contentResolver, key)
        ?.split(':')
        ?.any { ComponentName.unflattenFromString(it) == component }
        ?: false
