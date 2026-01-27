@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.margo.app_iot.ui.patient

import android.bluetooth.le.ScanResult
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.margo.app_iot.BleManager
import com.margo.app_iot.ExperimentDataResponse
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
                    api = api,
                    session = session,
                    permissionLauncher = permissionLauncher,
                    blePermissions = blePermissions,
                    devices = devices,
                    onConnect = { bleManager.connect(it.device) },
                    isConnected = isConnected,
                    connectedDeviceName = connectedDeviceName,
                    bleManager = bleManager
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

                PatientTab.Model3D -> PatientExperiments3DTab(
                    api = api,
                    session = session
                )
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
fun PatientBleTab(
    api: ApiClient,
    session: SessionStore,
    permissionLauncher: ActivityResultLauncher<Array<String>>,
    blePermissions: Array<String>,
    devices: List<ScanResult>,
    onConnect: (ScanResult) -> Unit,
    isConnected: Boolean,
    connectedDeviceName: String?,
    bleManager: BleManager
) {
    val scope = rememberCoroutineScope()

    val username by session.usernameFlow.collectAsState(initial = "")
    val savedDeviceId by session.deviceIdFlow.collectAsState(initial = "")

    val accessToken by session.accessTokenFlow.collectAsState(initial = "")

    var pairingStatus by remember { mutableStateOf<String?>(null) }
    var lastEspDeviceId by remember { mutableStateOf<String?>(null) }
    var pairingInProgress by remember { mutableStateOf(false) }

    // Подписка на уведомления deviceId от ESP
    LaunchedEffect(Unit) {
        bleManager.setOnDeviceIdListener { devId ->
            // защита от дублей/флуда
            if (devId.isBlank()) return@setOnDeviceIdListener
            if (pairingInProgress && devId == lastEspDeviceId) return@setOnDeviceIdListener

            lastEspDeviceId = devId

            scope.launch {
                if (username.isBlank()) {
                    pairingStatus = "Pairing error: username(userId) is empty."
                    return@launch
                }

                pairingInProgress = true
                pairingStatus = "ESP deviceId received: $devId. Sending userId to ESP..."

                // 1) Отправляем userId в ESP (как ты описала — только после того, как ESP прислал deviceId)
                bleManager.sendUserId(username)

                // 2) Проверяем deviceId на сервере
                pairingStatus = "Checking device on server..."
                val getRes = api.getDeviceByUserId(
                    userId = username,
                    accessToken = accessToken
                )

                if (getRes.isSuccess) {
                    val serverPair = getRes.getOrNull() // null == 404 "Device not paired"

                    if (serverPair == null) {
                        // 404 -> делаем UPSERT
                        pairingStatus = "Server says: not paired (404). Pairing with ESP id..."
                        val putRes = api.putDeviceByUserId(
                            userId = username,
                            deviceId = devId,
                            accessToken = accessToken
                        )

                        if (putRes.isSuccess) {
                            session.setDeviceId(devId)
                            pairingStatus = "Paired OK. DeviceId saved: $devId"
                        } else {
                            val e = putRes.exceptionOrNull()
                            pairingStatus = "Pairing failed: ${e?.message ?: "unknown error"}"
                        }
                    } else {
                        // На сервере есть deviceId -> сравниваем
                        if (serverPair.deviceId == devId) {
                            session.setDeviceId(devId)
                            pairingStatus = "Paired OK (server matches). DeviceId saved: $devId"
                        } else {
                            pairingStatus =
                                "Mismatch. Server: ${serverPair.deviceId}, ESP: $devId. Updating server..."

                            val putRes = api.putDeviceByUserId(
                                userId = username,
                                deviceId = devId,
                                accessToken = accessToken
                            )

                            if (putRes.isSuccess) {
                                session.setDeviceId(devId)
                                pairingStatus = "Server updated. Paired OK. DeviceId saved: $devId"
                            } else {
                                val e = putRes.exceptionOrNull()
                                pairingStatus = "Failed to update server: ${e?.message ?: "unknown error"}"
                            }
                        }
                    }
                } else {
                    // Ошибка GET
                    val e = getRes.exceptionOrNull()
                    if (e is ApiClient.ApiHttpException && e.code == 403) {
                        pairingStatus = "Forbidden (403): you are not allowed to access device pairing."
                    } else {
                        pairingStatus = "Failed to check server: ${e?.message ?: "unknown error"}"
                    }
                }

                pairingInProgress = false
            }
        }
    }

    // Когда подключились по BLE — включаем notify на deviceId характеристику
    LaunchedEffect(isConnected) {
        if (isConnected) {
            bleManager.enableDeviceIdNotifications()
            pairingStatus = "Connected. Press the button on ESP to send deviceId..."
        } else {
            pairingInProgress = false
        }
    }

    Column(Modifier.fillMaxSize()) {

        // Connect UI (без Add/Delete)
        com.margo.app_iot.BleConnectScreen(
            modifier = Modifier.fillMaxSize(),
            onRequestPermissions = { permissionLauncher.launch(blePermissions) },
            onStopScan = { bleManager.stopScan() },
            devices = devices,
            onDeviceSelected = onConnect,
            isConnected = isConnected,
            connectedDeviceName = connectedDeviceName
        )

        Spacer(Modifier.height(12.dp))

        // Pairing status panel
        if (pairingStatus != null || savedDeviceId.isNotBlank() || lastEspDeviceId != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Pairing", style = MaterialTheme.typography.titleMedium)

                    Spacer(Modifier.height(6.dp))
                    Text("UserId: ${if (username.isBlank()) "—" else username}")

                    Spacer(Modifier.height(4.dp))
                    Text("ESP deviceId: ${lastEspDeviceId ?: "—"}")

                    Spacer(Modifier.height(4.dp))
                    Text("Saved deviceId: ${if (savedDeviceId.isBlank()) "—" else savedDeviceId}")

                    if (pairingStatus != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(pairingStatus!!)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
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
        onFinishExperiment = { bleManager.finishExperiment() },
        isConfigApplied = false
    )
}

@Composable
fun PatientHistoryTab(
    api: ApiClient,
    session: SessionStore
) {
    val scope = rememberCoroutineScope()
    val userId by session.usernameFlow.collectAsState(initial = "")
    val accessToken by session.accessTokenFlow.collectAsState(initial = "")

    var doctorId by remember { mutableStateOf<String?>(null) }
    var doctorError by remember { mutableStateOf<String?>(null) }
    var loadingDoctor by remember { mutableStateOf(false) }

    fun loadDoctor() {
        if (userId.isBlank() || accessToken.isBlank()) return
        loadingDoctor = true
        doctorError = null
        scope.launch {
            val res = api.getDoctorIdByPatientId(patientId = userId, accessToken = accessToken)
            loadingDoctor = false
            if (res.isSuccess) {
                // если у тебя 404 -> null, то тут будет null
                doctorId = res.getOrNull()?.doctorId
            } else {
                doctorError = res.exceptionOrNull()?.message ?: "Failed to load doctor"
            }
        }
    }

    LaunchedEffect(userId, accessToken) {
        if (userId.isNotBlank() && accessToken.isNotBlank()) loadDoctor()
    }

    Column(Modifier.fillMaxSize()) {
        if (loadingDoctor) {
            // можно показывать где-то сверху, не обязательно
        }
        if (doctorError != null) {
            // тоже можно показывать где-то сверху
        }

        com.margo.app_iot.ui.shared.ExperimentsScreen(
            api = api,
            accessToken = accessToken,
            ownerUserId = userId,
            doctorIdLabel = doctorId,
            editableComment = false,
            title = "History"
        )
    }
}



@Composable
private fun MetricRow(label: String, value: Double?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value?.let { String.format("%.4f", it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientExperiments3DTab(
    api: ApiClient,
    session: SessionStore
) {
    val scope = rememberCoroutineScope()

    val userId by session.usernameFlow.collectAsState(initial = "")
    val accessToken by session.accessTokenFlow.collectAsState(initial = "")

    var experimentIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var listLoading by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }

    var selectedExpId by rememberSaveable { mutableStateOf<String?>(null) }

    var dataLoading by remember { mutableStateOf(false) }
    var dataError by remember { mutableStateOf<String?>(null) }
    var lastData by remember { mutableStateOf<ApiClient.ExperimentDataApiResponse?>(null) }

    fun refreshExperiments() {
        if (userId.isBlank() || accessToken.isBlank()) return
        listLoading = true
        listError = null
        scope.launch {
            val res = api.getExperimentsByUserId(userId = userId, accessToken = accessToken)
            listLoading = false
            if (res.isSuccess) {
                experimentIds = res.getOrNull()?.experimentIds ?: emptyList()
                if (selectedExpId == null && experimentIds.isNotEmpty()) {
                    selectedExpId = experimentIds.first()
                }
            } else {
                listError = res.exceptionOrNull()?.message ?: "Failed to load experiments"
            }
        }
    }

    fun loadSelectedData() {
        val expId = selectedExpId ?: return
        if (accessToken.isBlank()) return

        dataLoading = true
        dataError = null
        scope.launch {
            val res = api.getExperimentData(experimentId = expId, accessToken = accessToken)
            dataLoading = false
            if (res.isSuccess) {
                lastData = res.getOrNull()
            } else {
                dataError = res.exceptionOrNull()?.message ?: "Failed to load experiment data"
            }
        }
    }

    LaunchedEffect(userId, accessToken) {
        if (userId.isNotBlank() && accessToken.isNotBlank()) {
            refreshExperiments()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("3D / Experiment data", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        if (accessToken.isBlank()) {
            Text("No access token. Please login again.", color = MaterialTheme.colorScheme.error)
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { refreshExperiments() }, enabled = !listLoading) {
                if (listLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Refresh experiments")
            }

            OutlinedButton(
                onClick = { loadSelectedData() },
                enabled = selectedExpId != null && !dataLoading
            ) {
                if (dataLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Load data")
            }
        }

        if (listError != null) {
            Spacer(Modifier.height(10.dp))
            Text(listError!!, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(14.dp))

        Text("Select experiment:", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        if (experimentIds.isEmpty() && !listLoading) {
            Text("No experiments yet.")
            return@Column
        }

        // Simple selectable list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(experimentIds.size) { idx ->
                val expId = experimentIds[idx]
                val selected = expId == selectedExpId

                Card(
                    onClick = { selectedExpId = expId },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (selected) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else CardDefaults.cardColors()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(expId, style = MaterialTheme.typography.titleMedium)

                            // TODO: show experiment start time here when backend provides it
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Loaded data summary (placeholder for real 3D rendering)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Loaded data (preview)", style = MaterialTheme.typography.titleSmall)

                if (dataError != null) {
                    Text(dataError!!, color = MaterialTheme.colorScheme.error)
                }

                val d = lastData
                if (d == null) {
                    Text("—")
                } else {
                    val n = d.items.size
                    val last = d.items.lastOrNull()
                    val lastTs = last?.ts_ms
                    val sensors = last?.mpuProcessedData?.size

                    Text("experimentId: ${d.experimentId}")
                    Text("items: $n")
                    Text("last ts_ms: ${lastTs ?: "—"}")
                    Text("sensors in last item: ${sensors ?: "—"}")

                    // TODO: here you can pass d.items into your 3D pipeline / animation player
                }
            }
        }
    }
}


