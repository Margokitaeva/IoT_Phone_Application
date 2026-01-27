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
private fun PatientBleTab(
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
    val deviceId by session.deviceIdFlow.collectAsState(initial = "")

    var showAddDialog by remember { mutableStateOf(false) }
    var addDeviceId by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }

    // твой текущий экран Connect — просто вынесла
    com.margo.app_iot.BleConnectScreen(
        modifier = Modifier.fillMaxSize(),
        onRequestPermissions = { permissionLauncher.launch(blePermissions) },
        devices = devices,
        onAddDevice = {
            addDeviceId = ""
            addError = null
            showAddDialog = true
        },
        onDeleteDevice = {
            deleteError = null
            if (deviceId.isBlank()) {
                deleteError = "No device linked. Add a device first."
            } else {
                showDeleteConfirm = true
            }
        },
        onDeviceSelected = onConnect,
        isConnected = isConnected,
        connectedDeviceName = connectedDeviceName
    )

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!adding) showAddDialog = false },
            title = { Text("Add device") },
            text = {
                Column {
                    OutlinedTextField(
                        value = addDeviceId,
                        onValueChange = { addDeviceId = it },
                        label = { Text("DeviceID") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (addError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(addError!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !adding && addDeviceId.isNotBlank() && username.isNotBlank(),
                    onClick = {
                        adding = true
                        addError = null

                        scope.launch {
                            val res = api.patientAddDevice(
                                username = username,
                                deviceId = addDeviceId.trim()
                            )

                            adding = false

                            if (res.isSuccess) {
                                session.setDeviceId(addDeviceId.trim())
                                showAddDialog = false
                            } else {
                                val msg = res.exceptionOrNull()?.message.orEmpty()
                                addError =
                                    if (msg.contains("DEVICE_ALREADY_EXISTS")) {
                                        "You already have a device. Delete it first, then add a new one."
                                    } else {
                                        "Failed to add device: $msg"
                                    }
                            }
                        }
                    }
                ) { Text(if (adding) "Adding..." else "Confirm") }
            },
            dismissButton = {
                OutlinedButton(
                    enabled = !adding,
                    onClick = { showAddDialog = false }
                ) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteConfirm = false },
            title = { Text("Delete device?") },
            text = { Text("Are you sure you want to delete this device from your account?") },
            confirmButton = {
                Button(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            val res = api.patientDeleteDevice(
                                patientName = username,
                                deviceId = deviceId.trim()
                            )
                            deleting = false

                            if (res.isSuccess) {
                                session.clearDeviceId()
//                                bleManager.deleteDevice()
                                showDeleteConfirm = false
                            } else {
                                deleteError = res.exceptionOrNull()?.message ?: "Delete failed"
                                showDeleteConfirm = false
                            }
                        }
                    }
                ) { Text(if (deleting) "Deleting..." else "Delete") }
            },
            dismissButton = {
                OutlinedButton(
                    enabled = !deleting,
                    onClick = { showDeleteConfirm = false }
                ) { Text("Cancel") }
            }
        )
    }

    if (deleteError != null) {
        Spacer(Modifier.height(8.dp))
        Text(deleteError!!, color = MaterialTheme.colorScheme.error)
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

    // comment
    var comment by remember { mutableStateOf("") }
    var loadingComment by remember { mutableStateOf(false) }
    var commentError by remember { mutableStateOf<String?>(null) }

    // experiments list
    var experiments by remember { mutableStateOf(listOf<String>()) }
    var loadingList by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    var selectedExperiment by remember { mutableStateOf<String?>(null) }

    // selected experiment result
    var result by remember { mutableStateOf<ApiClient.PatResult?>(null) }
    var loadingResult by remember { mutableStateOf(false) }
    var resultError by remember { mutableStateOf<String?>(null) }

    fun loadComment() {
        if (username.isBlank()) return
        loadingComment = true
        commentError = null
        scope.launch {
            val res = api.patientGetComment(username)
            loadingComment = false
            if (res.isSuccess) comment = res.getOrNull().orEmpty()
            else commentError = res.exceptionOrNull()?.message ?: "Failed to load comment"
        }
    }

    fun loadExperiments() {
        if (username.isBlank()) return
        loadingList = true
        listError = null
        scope.launch {
            val res = api.patientGetExperiments(username)
            loadingList = false
            if (res.isSuccess) experiments = res.getOrNull().orEmpty()
            else listError = res.exceptionOrNull()?.message ?: "Failed to load experiments"
        }
    }

    fun loadSelectedResult(expId: String) {
        if (username.isBlank()) return
        selectedExperiment = expId
        loadingResult = true
        resultError = null
        result = null

        scope.launch {
            val res = api.patientGetPatResult(username, expId)
            loadingResult = false
            if (res.isSuccess) result = res.getOrNull()
            else resultError = res.exceptionOrNull()?.message ?: "Failed to load result"
        }
    }

    LaunchedEffect(username) {
        if (username.isNotBlank()) {
            loadComment()
            loadExperiments()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { loadComment() }) {
                Text(if (loadingComment) "Loading..." else "Refresh comment")
            }
            Button(onClick = { loadExperiments() }) {
                Text(if (loadingList) "Loading..." else "Refresh experiments")
            }
        }

        // ---- comment ----
        Spacer(Modifier.height(10.dp))
        if (commentError != null) Text(commentError!!, color = MaterialTheme.colorScheme.error)

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = comment,
            onValueChange = {},
            readOnly = true,
            label = { Text("Doctor comment") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
        )

        // ---- experiments list ----
        Spacer(Modifier.height(16.dp))
        Text("Experiments", style = MaterialTheme.typography.titleMedium)

        if (listError != null) {
            Spacer(Modifier.height(8.dp))
            Text(listError!!, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(8.dp))

        if (experiments.isEmpty() && !loadingList) {
            Text("No experiments yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(experiments) { exp ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        onClick = { loadSelectedResult(exp) }
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(exp, style = MaterialTheme.typography.titleMedium)
                            if (selectedExperiment == exp) {
                                Text("Selected", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("Tap to open", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // ---- selected experiment result ----
        Spacer(Modifier.height(14.dp))
        Text("Selected experiment result", style = MaterialTheme.typography.titleMedium)

        if (loadingResult) {
            Spacer(Modifier.height(8.dp))
            Text("Loading result...")
        }

        if (resultError != null) {
            Spacer(Modifier.height(8.dp))
            Text(resultError!!, color = MaterialTheme.colorScheme.error)
        }

        val r = result
        if (r != null) {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    if (r.updatedAt != null) {
                        Text("Updated at: ${r.updatedAt}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }

                    MetricRow("Hip Range of Motion (Left)", r.hipRomLeft)
                    MetricRow("Hip Range of Motion (Right)", r.hipRomRight)
                    MetricRow("Knee Range of Motion (Left)", r.kneeRomLeft)
                    MetricRow("Knee Range of Motion (Right)", r.kneeRomRight)

                    Spacer(Modifier.height(6.dp))
                    MetricRow("Cadence (Estimated)", r.cadenceEst)
                    MetricRow("Symmetry Index", r.symmetryIndex)

                    Spacer(Modifier.height(6.dp))
                    MetricRow("Pelvis Pitch Range of Motion", r.pelvisPitchRom)
                    MetricRow("Pelvis Roll Range of Motion", r.pelvisRollRom)
                }
            }
        }
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
private fun PatientExperiments3DTab(
    api: ApiClient,
    session: SessionStore
) {
    val scope = rememberCoroutineScope()
    val username by session.usernameFlow.collectAsState(initial = "")

    var experiments by remember { mutableStateOf(listOf<String>()) }
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }

    var loadingList by remember { mutableStateOf(false) }
    var loadingData by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var lastLoaded by remember { mutableStateOf<ExperimentDataResponse?>(null) }

    val filtered = remember(experiments, query) {
        if (query.isBlank()) experiments
        else experiments.filter { it.contains(query, ignoreCase = true) }
    }

    fun loadExperiments() {
        if (username.isBlank()) return
        loadingList = true
        error = null
        scope.launch {
            val res = api.patientGetExperiments(username)
            loadingList = false
            if (res.isSuccess) {
                experiments = res.getOrNull().orEmpty()
            } else {
                error = res.exceptionOrNull()?.message ?: "Failed to load experiments"
            }
        }
    }

    LaunchedEffect(username) {
        if (username.isNotBlank()) loadExperiments()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("3D Model", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))

        if (username.isBlank()) {
            Text("Loading user...", color = MaterialTheme.colorScheme.tertiary)
            return@Column
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Experiment", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { loadExperiments() }) {
                Text(if (loadingList) "..." else "Refresh")
            }
        }

        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                value = query,
                onValueChange = {
                    query = it
                    expanded = true
                },
                label = { Text("Search / select experiment") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                filtered.forEach { exp ->
                    DropdownMenuItem(
                        text = { Text(exp) },
                        onClick = {
                            selected = exp
                            query = exp
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val expId = selected ?: return@Button
                loadingData = true
                error = null
                scope.launch {
                    val res = api.patientGetExperimentData(username, expId)
                    loadingData = false
                    if (res.isSuccess) {
                        lastLoaded = res.getOrNull()
                        // TODO: start 3D simulation rendering here (later)
                    } else {
                        error = res.exceptionOrNull()?.message ?: "Failed to load experiment data"
                    }
                }
            },
            enabled = selected != null && !loadingData,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loadingData) "Loading..." else "Show simulation")
        }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))

        // заглушка с фактом получения данных
        val data = lastLoaded
        if (data == null) {
            Text("Simulation placeholder (TODO)", style = MaterialTheme.typography.titleMedium)
            Text("Select an experiment and press “Show simulation”.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Loaded experiment data:", style = MaterialTheme.typography.titleMedium)
//            Text("deviceID: ${data.deviceID}", style = MaterialTheme.typography.bodyMedium)
//            Text("timestamp: ${data.timestamp}", style = MaterialTheme.typography.bodyMedium)
//            Text("frames: ${data.mpuProcessedData.size}", style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(10.dp))
            Text("3D rendering TODO: we will draw cylinders / stick model later.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

