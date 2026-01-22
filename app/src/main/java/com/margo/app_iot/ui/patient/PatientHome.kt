@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.margo.app_iot.ui.patient

import android.bluetooth.le.ScanResult
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.margo.app_iot.BleManager
import com.margo.app_iot.data.SessionStore
import com.margo.app_iot.network.ApiClient
import com.margo.app_iot.VisualizationScreen
import kotlinx.coroutines.launch

@Composable
fun PatientHome(
    api: ApiClient,
    session: SessionStore,
    bleManager: BleManager,
    permissionLauncher: ActivityResultLauncher<Array<String>>,
    blePermissions: Array<String>,
    devices: List<ScanResult>,
    isConnected: Boolean,
    connectedDeviceName: String?,
    onLogout: () -> Unit
) {
    var tab by remember { mutableStateOf(PatientTab.Ble) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patient") },
                actions = {
                    TextButton(onClick = onLogout) { Text("Logout") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                PatientTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.cd) },
                        label = null // icon-only (no text)
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                PatientTab.Ble -> PatientBleTab(
                    permissionLauncher = permissionLauncher,
                    blePermissions = blePermissions,
                    devices = devices,
                    onConnect = { bleManager.connect(it.device) },
                    isConnected = isConnected,
                    connectedDeviceName = connectedDeviceName
                )

                PatientTab.Config -> PatientConfigTab(
                    isConnected = isConnected,
                    bleManager = bleManager
                )

                PatientTab.RealTime -> VisualizationScreen(
                    modifier = Modifier.fillMaxSize(),
                    isConnected = isConnected,
                    bleManager = bleManager
                )

                PatientTab.History -> PatientHistoryTab(
                    api = api,
                    session = session
                )

                PatientTab.Model3D -> Placeholder3DTab()
            }
        }
    }
}

private enum class PatientTab(val icon: androidx.compose.ui.graphics.vector.ImageVector, val cd: String) {
    Ble(Icons.Filled.Bluetooth, "BLE"),
    Config(Icons.Filled.Settings, "Config"),
    RealTime(Icons.Filled.ShowChart, "Real-time graphics"),
    History(Icons.Filled.History, "History"),
    Model3D(Icons.Filled.ViewInAr, "3D model")
}

@Composable
private fun PatientBleTab(
    permissionLauncher: ActivityResultLauncher<Array<String>>,
    blePermissions: Array<String>,
    devices: List<ScanResult>,
    onConnect: (ScanResult) -> Unit,
    isConnected: Boolean,
    connectedDeviceName: String?
) {
    // твой текущий экран Connect — просто вынесла
    com.margo.app_iot.BleConnectScreen(
        modifier = Modifier.fillMaxSize(),
        onRequestPermissions = { permissionLauncher.launch(blePermissions) },
        devices = devices,
        onDeviceSelected = onConnect,
        isConnected = isConnected,
        connectedDeviceName = connectedDeviceName
    )
}

@Composable
private fun PatientConfigTab(
    isConnected: Boolean,
    bleManager: BleManager
) {
    com.margo.app_iot.ConfigScreen(
        modifier = Modifier.fillMaxSize(),
        isConnected = isConnected,
        onApplyWifi = { ssid, pass -> bleManager.sendWifi(ssid, pass) },
        onApplyConfig = { cfg -> bleManager.sendConfig(cfg) },
        isConfigApplied = false
    )
}

@Composable
private fun PatientHistoryTab(
    api: ApiClient,
    session: SessionStore
) {
    val scope = rememberCoroutineScope()
    val username by session.usernameFlow.collectAsState(initial = "")
    var comment by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))

        // TODO: по твоему описанию тут ещё должны быть: "doctor name" и история экспериментов.
        // Сейчас API отдаёт только comment, поэтому doctor name — placeholder.
        Text("Doctor: (TODO: backend should provide doctor name)", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                loading = true
                error = null
                scope.launch {
                    val res = api.patientGetComment(username)
                    loading = false
                    if (res.isSuccess) comment = res.getOrNull().orEmpty()
                    else error = res.exceptionOrNull()?.message ?: "Failed"
                }
            }
        ) { Text(if (loading) "Loading..." else "Refresh comment") }

        Spacer(Modifier.height(10.dp))
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = comment,
            onValueChange = {},
            readOnly = true,
            label = { Text("Doctor comment") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
        )

        Spacer(Modifier.height(16.dp))
        Text("Experiments history (TODO)", style = MaterialTheme.typography.titleMedium)
        Text("Placeholder: we will add list UI when you decide the format.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Placeholder3DTab() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("3D Model", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text("TODO: 3D model screen placeholder. We'll add rendering later.")
    }
}
