package com.example.pos_network_tester

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.pos_network_tester.logging.LogEntry
import com.example.pos_network_tester.logging.TestEventLogger
import com.example.pos_network_tester.network.InternetProbe
import com.example.pos_network_tester.network.ProbeSettings
import com.example.pos_network_tester.network.WifiProfile
import com.example.pos_network_tester.network.WifiProfileStore
import com.example.pos_network_tester.network.WifiSecurity
import com.example.pos_network_tester.overlay.FloatingPanelService
import com.example.pos_network_tester.privileged.ExecutorState
import com.example.pos_network_tester.privileged.ShizukuExecutor
import com.example.pos_network_tester.simulation.ScenarioSettings
import com.example.pos_network_tester.ui.theme.Pos_network_testerTheme
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var canDrawOverlays by mutableStateOf(false)
    private var panelRunning by mutableStateOf(false)

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Pos_network_testerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        canDrawOverlays = canDrawOverlays,
                        panelRunning = panelRunning,
                        actions = MainActions(
                            requestOverlayPermission = ::requestOverlayPermission,
                            requestRuntimePermissions = ::requestRuntimePermissions,
                            requestShizukuPermission = ::requestShizukuPermission,
                            retryShizuku = { ShizukuExecutor.refreshState() },
                            togglePanel = ::togglePanel,
                            addProfile = ::addProfile,
                            removeProfile = { WifiProfileStore.remove(it.id) },
                            togglePrimary = { WifiProfileStore.togglePrimary(it.id) },
                            saveProbeUrl = ::saveProbeUrl,
                            saveScenarioSettings = ::saveScenarioSettings,
                            exportCsv = ::exportLog,
                            clearLog = { TestEventLogger.clear() }
                        )
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canDrawOverlays = Settings.canDrawOverlays(this)
        panelRunning = FloatingPanelService.isRunning
        ShizukuExecutor.refreshState()
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        runtimePermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestShizukuPermission() {
        if (!ShizukuExecutor.requestPermission()) {
            toast("Permiso bloqueado: concédelo en la app Shizuku → Aplicaciones autorizadas")
        }
    }

    private fun togglePanel() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Concede primero el permiso de superposición")
            return
        }
        if (FloatingPanelService.isRunning) {
            FloatingPanelService.stop(this)
            panelRunning = false
        } else {
            FloatingPanelService.start(this)
            panelRunning = true
        }
    }

    private fun addProfile(ssid: String, security: WifiSecurity, passphrase: String): Boolean {
        if (ssid.isBlank()) {
            toast("El SSID no puede estar vacío")
            return false
        }
        if (security.needsPassphrase && passphrase.length < 8) {
            toast("La clave ${security.label} debe tener al menos 8 caracteres")
            return false
        }
        WifiProfileStore.add(ssid, security, passphrase)
        return true
    }

    private fun saveScenarioSettings(off: String, on: String, rotation: String) {
        val error = ScenarioSettings.save(off.toIntOrNull(), on.toIntOrNull(), rotation.toIntOrNull())
        toast(error ?: "Tiempos de prueba guardados")
    }

    private fun saveProbeUrl(url: String) {
        toast(ProbeSettings.setUrl(url) ?: "URL de sonda guardada")
    }

    private fun exportLog() {
        val file = TestEventLogger.exportCsv(this)
        toast("Exportado a ${file.absolutePath}")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

private class MainActions(
    val requestOverlayPermission: () -> Unit,
    val requestRuntimePermissions: () -> Unit,
    val requestShizukuPermission: () -> Unit,
    val retryShizuku: () -> Unit,
    val togglePanel: () -> Unit,
    val addProfile: (String, WifiSecurity, String) -> Boolean,
    val removeProfile: (WifiProfile) -> Unit,
    val togglePrimary: (WifiProfile) -> Unit,
    val saveProbeUrl: (String) -> Unit,
    val saveScenarioSettings: (String, String, String) -> Unit,
    val exportCsv: () -> Unit,
    val clearLog: () -> Unit
)

@Composable
private fun MainScreen(
    modifier: Modifier = Modifier,
    canDrawOverlays: Boolean,
    panelRunning: Boolean,
    actions: MainActions
) {
    val entries by TestEventLogger.entries.collectAsState()
    val profiles by WifiProfileStore.profiles.collectAsState()
    val executorState by ShizukuExecutor.state.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("POS Network Tester", style = MaterialTheme.typography.headlineSmall) }

        item {
            Section("Permisos") {
                Text(if (canDrawOverlays) "Superposición: concedida" else "Superposición: pendiente")
                if (!canDrawOverlays) {
                    Button(onClick = actions.requestOverlayPermission) { Text("Conceder superposición") }
                }
                Button(onClick = actions.requestRuntimePermissions) {
                    Text("Permisos de red y notificaciones")
                }
            }
        }

        item { ShizukuSection(executorState, actions) }

        item {
            Section("Panel flotante") {
                Button(onClick = actions.togglePanel) {
                    Text(if (panelRunning) "Detener panel flotante" else "Iniciar panel flotante")
                }
            }
        }

        item {
            Section("Perfiles Wi-Fi para cambio rápido") {
                Text(
                    "★ = red principal: la de Internet estable. \"Restaurar\" y las pruebas vuelven siempre a ella.",
                    style = MaterialTheme.typography.bodySmall
                )
                profiles.forEach { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { actions.togglePrimary(profile) }) {
                            Text(if (profile.isPrimary) "★" else "☆", style = MaterialTheme.typography.titleLarge)
                        }
                        Text(
                            "${profile.ssid} · ${profile.security.label}" +
                                if (profile.isPrimary) " · principal" else "",
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { actions.removeProfile(profile) }) { Text("Eliminar") }
                    }
                }
                AddProfileForm(actions.addProfile)
            }
        }

        item { ScenarioSettingsSection(actions.saveScenarioSettings) }

        item { ProbeSection(actions.saveProbeUrl) }

        item {
            Section("Historial (${entries.size})") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.exportCsv) { Text("Exportar CSV") }
                    Button(onClick = actions.clearLog) { Text("Limpiar") }
                }
            }
        }
        items(entries.asReversed(), key = { it.id }) { entry -> LogRow(entry) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun ShizukuSection(state: ExecutorState, actions: MainActions) {
    Section("Control de red (Shizuku)") {
        Text(state.label)
        when (state) {
            ExecutorState.NOT_RUNNING, ExecutorState.UNSUPPORTED -> {
                Text(
                    "Sin Shizuku la app solo puede observar la red. Para cambiar de red desde el panel:\n" +
                        "1. Instala Shizuku en el POS.\n" +
                        "2. Ábrelo y elige \"Iniciar mediante ADB conectado\": ejecuta en el Mac el " +
                        "comando que muestra (repetir tras cada reinicio del POS).\n" +
                        "3. Vuelve aquí y autoriza esta app.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = actions.retryShizuku) { Text("Volver a comprobar") }
            }
            ExecutorState.PERMISSION_REQUIRED ->
                Button(onClick = actions.requestShizukuPermission) { Text("Autorizar en Shizuku") }
            ExecutorState.CONNECTING, ExecutorState.READY -> Unit
        }
    }
}

@Composable
private fun ScenarioSettingsSection(onSave: (String, String, String) -> Unit) {
    val current by ScenarioSettings.config.collectAsState()
    var off by remember(current) { mutableStateOf(current.intermittenceOffSeconds.toString()) }
    var on by remember(current) { mutableStateOf(current.intermittenceOnSeconds.toString()) }
    var rotation by remember(current) { mutableStateOf(current.rotationIntervalSeconds.toString()) }
    Section("Tiempos de las pruebas (segundos)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondsField("Intermit. sin red", off, Modifier.weight(1f)) { off = it }
            SecondsField("Intermit. con red", on, Modifier.weight(1f)) { on = it }
        }
        SecondsField("Rotación de redes: cada", rotation, Modifier.fillMaxWidth()) { rotation = it }
        Button(onClick = { onSave(off, on, rotation) }) { Text("Guardar tiempos") }
    }
}

@Composable
private fun SecondsField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun ProbeSection(onSave: (String) -> Unit) {
    val current by ProbeSettings.url.collectAsState()
    var draft by remember(current) { mutableStateOf(current) }
    Section("Sonda de Internet real") {
        Text(
            "Este POS tiene la validación de Android desactivada, así que la app comprueba Internet " +
                "con su propia petición HTTP. Para medir la disponibilidad real del servicio, usa el " +
                "endpoint de salud del sandbox de pagos (https, sin datos sensibles).",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("URL de sonda") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(draft) }) { Text("Guardar") }
            TextButton(onClick = { onSave(InternetProbe.DEFAULT_PROBE_URL) }) { Text("Por defecto") }
        }
    }
}

@Composable
private fun AddProfileForm(onAdd: (String, WifiSecurity, String) -> Boolean) {
    var ssid by remember { mutableStateOf("") }
    var security by remember { mutableStateOf(WifiSecurity.WPA2) }
    var passphrase by remember { mutableStateOf("") }

    OutlinedTextField(
        value = ssid,
        onValueChange = { ssid = it },
        label = { Text("SSID (exacto)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WifiSecurity.entries.forEach { option ->
            FilterChip(
                selected = security == option,
                onClick = { security = option },
                label = { Text(option.label) }
            )
        }
    }
    if (security.needsPassphrase) {
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Clave") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Button(onClick = {
        if (onAdd(ssid, security, passphrase)) {
            ssid = ""
            passphrase = ""
        }
    }) { Text("Añadir perfil") }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column {
        Text(
            "${formatter.format(entry.timestampMillis)} [${entry.eventType}] ${entry.testId.orEmpty()}",
            style = MaterialTheme.typography.labelSmall
        )
        Text(entry.message, style = MaterialTheme.typography.bodySmall)
        Text(
            "Wi-Fi: ${entry.wifiState} · Móvil: ${entry.mobileState} (${entry.mobileNetworkType})",
            style = MaterialTheme.typography.labelSmall
        )
    }
}
