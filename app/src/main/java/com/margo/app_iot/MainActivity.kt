package com.margo.app_iot

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.bluetooth.le.ScanResult
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    // ===== UI STATE =====
    private val scanResults = mutableStateListOf<ScanResult>()
    private val isConnected = mutableStateOf(false)
    private val connectedDeviceName = mutableStateOf<String?>(null)
    private val isConfigApplied = mutableStateOf(false)

    // ===== BLE =====
    private lateinit var bleManager: BleManager

    // ===== PERMISSIONS =====
    private val blePermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            bleManager.startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ===== BLE MANAGER =====
        bleManager = BleManager(
            context = this,
            onDeviceFound = { result ->
                if (!scanResults.any { it.device.address == result.device.address }) {
                    scanResults.add(result)
                }
            },
            onConnected = { name ->
                println("connected")
                isConnected.value = true
                connectedDeviceName.value = name
            },
            onDisconnected = {
                println("disconnected")
                isConnected.value = false
                connectedDeviceName.value = null
            },
            onConfigApplied = {
                isConfigApplied.value = true
            }
        )

        setContent {

            var selectedTab by remember { mutableStateOf(0) }

            // автопереход в Config после connect
            LaunchedEffect(isConnected.value) {
                if (isConnected.value) selectedTab = 1
            }

            Scaffold(
                topBar = {

                    TabRow(selectedTabIndex = selectedTab,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Connect") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Config") }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Charts") }
                        )
                    }
                }
            ) { padding ->
                when (selectedTab) {

                    // ===== CONNECT =====
                    0 -> BleConnectScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        onRequestPermissions = {
                            permissionLauncher.launch(blePermissions)
                        },
                        devices = scanResults,
                        onDeviceSelected = { result ->
                            bleManager.connect(result.device)
                        },
                        isConnected = isConnected.value,
                        connectedDeviceName = connectedDeviceName.value
                    )

                    1 -> ConfigScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        isConnected = isConnected.value,
                        onApplyWifi = { ssid, pass ->
                            bleManager.sendWifi(ssid, pass)
                        },
                        onApplyConfig = { cfg ->
                            isConfigApplied.value = false
                            bleManager.sendConfig(cfg)
                        },
                        isConfigApplied = isConfigApplied.value
                    )

                    2 -> VisualizationScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        isConnected = isConnected.value,
                        bleManager = bleManager
                    )
                }
            }
        }
    }
}



@Composable
fun BleConnectScreen(
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
    devices: List<ScanResult>,
    onDeviceSelected: (ScanResult) -> Unit,
    isConnected: Boolean,
    connectedDeviceName: String?
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {

        // Scan button (НЕ СКРОЛЛИТСЯ)
        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan BLE devices")
        }

        if (isConnected) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Connected to $connectedDeviceName",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Found devices:",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(devices.size) { index ->
                val device = devices[index]

                Column {
                    Text(
                        text = device.device.name ?: "Unknown device",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = device.device.address,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = { onDeviceSelected(device) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect")
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}


@Composable
fun ConfigScreen(
    modifier: Modifier = Modifier,
    isConnected: Boolean,
    onApplyWifi: (String, String) -> Unit,
    onApplyConfig: (Map<String, String>) -> Unit,
    isConfigApplied: Boolean
) {
    if (!isConnected) {
        Column(modifier.padding(16.dp)) {
            Text("Connect BLE device first (in Connect tab)")
        }
        return
    }

    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cfgA by remember { mutableStateOf("") }
    var cfgB by remember { mutableStateOf("") }
    var isLedEnabled by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.padding(16.dp)) {

        item {
            Text("Wi-Fi Configuration", style = MaterialTheme.typography.titleLarge)
        }

        item {
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("SSID") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick = { Log.d("WIFI", "Apply WiFi BUTTON clicked")
                    onApplyWifi(ssid, password) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply Wi-Fi")
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            Text("Device Config", style = MaterialTheme.typography.titleLarge)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("LED enabled")
                Switch(
                    checked = isLedEnabled,
                    onCheckedChange = { isLedEnabled = it }
                )
            }
        }

        item {
            OutlinedTextField(
                value = cfgA,
                onValueChange = { cfgA = it },
                label = { Text("paramA") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = cfgB,
                onValueChange = { cfgB = it },
                label = { Text("paramB") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick = {
                    onApplyConfig(
                        linkedMapOf(
                            "isLedEnabled" to if (isLedEnabled) "1" else "0",
                            "paramA" to cfgA,
                            "paramB" to cfgB
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply Config")
            }
        }

//        if (isConfigApplied) {
//            item {
//                Text(
//                    text = "Config applied ✔",
//                    color = MaterialTheme.colorScheme.tertiary
//                )
//            }
//        }
    }
}


//@Composable
//fun BLEWiFiConfiguratorUI(modifier: Modifier = Modifier,
//                          onRequestPermissions: () -> Unit,
//                          devices: List<ScanResult>,
//                          onDeviceSelected: (ScanResult) -> Unit,
//                          onApplyWifi: (String, String) -> Unit,
//                          onApplyConfig: (Map <String, String>) -> Unit,
//                          isConnected: Boolean,
//                          connectedDeviceName: String?,
//                          isConfigApplied: Boolean) {
//
//    var ssid by remember { mutableStateOf("") }
//    var password by remember { mutableStateOf("") }
//    var scanRequested by remember { mutableStateOf(false) }
//    var showErrors by remember { mutableStateOf(false) }
//    var ssidError = showErrors && ssid.isBlank()
//    var passwordError = showErrors && password.isBlank()
//    var cfgA by remember{ mutableStateOf("")}
//    var cfgB by remember { mutableStateOf("") }
//    var ledEnabled by remember { mutableStateOf(false) }
//
//    Column (
//        modifier = modifier.padding(16.dp)
//                            .fillMaxSize()
//    ) {
//
//
//        // ble scanning part
//        // Scan button
//        Button(
//            onClick = { onRequestPermissions()
//                        scanRequested = true },
//            modifier = Modifier.fillMaxWidth()
//        ) {
//            Text("Scan BLE devices")
//        }
//
//        if (isConnected) {
//            Spacer(modifier = Modifier.height(12.dp))
//            Text(
//                text = "Connected to $connectedDeviceName",
//                color = MaterialTheme.colorScheme.tertiary,
//                style = MaterialTheme.typography.titleMedium
//            )
//        }
//
////        Spacer(modifier = Modifier.height(16.dp))
//
//        Spacer(modifier = Modifier.height(24.dp))
//
//        Text(
//            text = "Please select your device: \"ESP32_GATTS\"",
//            style = MaterialTheme.typography.titleMedium,
//            color = MaterialTheme.colorScheme.primary
//        )
//
//        Spacer(modifier = Modifier.height(8.dp))
//
////        Text("Found devices:", style = MaterialTheme.typography.titleMedium)
//        Text("Found devices:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f))
//
//        Spacer(modifier = Modifier.height(10.dp))
//
//        LazyColumn {
//            items(devices.size) { index ->
//                val device = devices[index]
//
//                Column {
//                    Text(
//                        text = device.device.name ?: "Unknown device",
//                        style = MaterialTheme.typography.bodyLarge
//                    )
//
//                    Text(
//                        text = device.device.address,
//                        style = MaterialTheme.typography.bodySmall
//                    )
//
//                    Button(
//                        onClick = { onDeviceSelected(device) },
//                        modifier = Modifier.fillMaxWidth()
//                    ) {
//                        Text("Connect")
//                    }
//                }
//
//                Spacer(modifier = Modifier.height(8.dp))
//
//                HorizontalDivider()
//
//                Spacer(modifier = Modifier.height(8.dp))
//            }
//        }
//
//        // if isConnected show wifi and configuration settings
//        if (!isConnected) {
//            Spacer(modifier = Modifier.height(16.dp))
//            Text(
//                text = "Connect to a BLE device to continue",
//                color = MaterialTheme.colorScheme.secondary
//            )
//            return@Column // doesn't show everything which is after it
//        }
//
//        // wifi configuration
//        Text(text = "WiFi Configuration", style = MaterialTheme.typography.headlineSmall)
//
//        Spacer(modifier = Modifier.height(12.dp))
//
//        // SSID field
//        OutlinedTextField(
//            value = ssid,
//            onValueChange = { ssid = it },
//            label = { Text("SSID") },
//            modifier = Modifier.fillMaxWidth()
//        )
//
//        if (ssidError) {
//            Spacer(modifier = Modifier.height(3.dp))
//            Text(
//                text = "Please enter WiFi SSID",
//                color = MaterialTheme.colorScheme.error,
//                style = MaterialTheme.typography.bodySmall
//            )
//        }
//
//        Spacer(modifier = Modifier.height(8.dp))
//
//        // Password field
//        OutlinedTextField(
//            value = password,
//            onValueChange = { password = it },
//            label = { Text("Password") },
//            visualTransformation = PasswordVisualTransformation(),
//            modifier = Modifier.fillMaxWidth()
//        )
//
//        if (passwordError) {
//            Spacer(modifier = Modifier.height(3.dp))
//            Text(
//                text = "Please enter WiFi password",
//                color = MaterialTheme.colorScheme.error,
//                style = MaterialTheme.typography.bodySmall
//            )
//        }
//
//        Spacer(modifier = Modifier.height(16.dp))
//
//        Button(
//            onClick = {
//                showErrors = true
//                if (!ssidError && !passwordError) {
//                    onApplyWifi(ssid, password)
//                }
//            },
//            modifier = Modifier.fillMaxWidth()
//        ) {
//            Text("Apply WiFi Settings")
//        }
//
//        Spacer(modifier = Modifier.height(24.dp))
//
//
//        // configuration of other variables
//        Spacer(modifier = Modifier.height(24.dp))
//        Text(text = "Device Config", style = MaterialTheme.typography.titleMedium)
//
//        Spacer(modifier = Modifier.height(12.dp))
//
//        Row(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(vertical = 8.dp),
//            horizontalArrangement = Arrangement.SpaceBetween
//        ) {
//            Text(
//                text = "LED enabled",
//                style = MaterialTheme.typography.bodyLarge
//            )
//
//            Switch(
//                checked = ledEnabled,
//                onCheckedChange = { ledEnabled = it }
//            )
//            Text(if (ledEnabled) "ON" else "OFF")
//        }
//
//        Spacer(modifier = Modifier.height(8.dp))
//
//        OutlinedTextField(
//            value = cfgA,
//            onValueChange = { cfgA = it },
//            label = { Text("paramA (stub)") },
//            modifier = Modifier.fillMaxWidth()
//        )
//
//        Spacer(modifier = Modifier.height(8.dp))
//
//        OutlinedTextField(
//            value = cfgB,
//            onValueChange = { cfgB = it },
//            label = { Text("paramB (stub)") },
//            modifier = Modifier.fillMaxWidth()
//        )
//
//        Spacer(modifier = Modifier.height(12.dp))
//
//        Button(
//            onClick = {
//                // not sending wifi
//                onApplyConfig(
//                    linkedMapOf(
//                        "isLedEnabled" to if (ledEnabled) "1" else "0",
//                        "paramA" to cfgA,
//                        "paramB" to cfgB
//                    )
//                )
//            },
//            modifier = Modifier.fillMaxWidth()
//        ) {
//            Text("Apply Config")
//        }
//
//        if (isConfigApplied) {
//            Spacer(modifier = Modifier.height(8.dp))
//            Text(
//                text = "Config applied successfully",
//                color = MaterialTheme.colorScheme.tertiary,
//                style = MaterialTheme.typography.bodyMedium
//            )
//        }
//
//
//    }
//}







