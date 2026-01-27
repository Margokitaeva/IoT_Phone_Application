package com.margo.app_iot

import android.bluetooth.le.ScanResult
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BleConnectScreen(
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
    onStopScan: () -> Unit,
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

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan BLE devices")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onStopScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop scanning")
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
    onFinishExperiment: () -> Unit,
    isConfigApplied: Boolean
) {
    if (!isConnected) {
        Column(modifier.padding(16.dp)) {
            Text("Connect BLE device first (in BLE tab)")
        }
        return
    }

    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var experimentName by remember { mutableStateOf("") }
//    var cfgB by remember { mutableStateOf("") }
    var isLedEnabled by remember { mutableStateOf(false) }
    var samplingMs by remember { mutableStateOf("") }

    LazyColumn(modifier = modifier.padding(16.dp)) {

        item { Text("Wi-Fi Configuration", style = MaterialTheme.typography.titleLarge) }

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
                onClick = {
                    Log.d("WIFI", "Apply WiFi BUTTON clicked")
                    onApplyWifi(ssid, password)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply Wi-Fi") }
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
                value = experimentName,
                onValueChange = { experimentName = it },
                label = { Text("Experiment name") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = samplingMs,
                onValueChange = { samplingMs = it.filter { ch -> ch.isDigit() } },
                label = { Text("Sampling interval (ms)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick = {
                    onApplyConfig(
                        linkedMapOf(
                            "isLedEnabled" to if (isLedEnabled) "1" else "0",
                            "experimentName" to experimentName,
                            "samplingMs" to samplingMs
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply Config") }
        }

        item {
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { onFinishExperiment() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Finish experiment") }
        }
    }
}
