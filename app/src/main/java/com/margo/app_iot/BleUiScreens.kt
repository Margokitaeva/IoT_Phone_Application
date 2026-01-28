package com.margo.app_iot

import android.bluetooth.le.ScanResult
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.margo.app_iot.data.SessionStore
import com.margo.app_iot.network.ApiClient
import com.margo.app_iot.network.AuthRepository
import com.margo.app_iot.network.toUserMessage
import kotlinx.coroutines.launch

@Composable
fun BleConnectScreen(
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
    onStopScan: () -> Unit,
    devices: List<ScanResult>,
    onDeviceSelected: (ScanResult) -> Unit,
    isConnected: Boolean,
    connectedDeviceName: String?,
    onDisconnect: () -> Unit
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

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect")
            }
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

private enum class HandshakePhase {
    WaitingForNotify, Exchanging, Ready, Error
}

@Composable
fun ConfigScreen(
    modifier: Modifier = Modifier,
    isConnected: Boolean,
    api: ApiClient,
    auth: AuthRepository,
    session: SessionStore,
    bleManager: BleManager,
    onApplyWifi: (String, String) -> Unit,
    onApplyConfig: (Map<String, String>) -> Unit,
    onFinishExperiment: () -> Unit,
)   {
    if (!isConnected) {
        Column(modifier.padding(16.dp)) {
            Text("Connect BLE device first (in BLE tab)")
        }
        return
    }

    val scope = rememberCoroutineScope()

    val userId by session.usernameFlow.collectAsState(initial = "")
    val savedDeviceId by session.deviceIdFlow.collectAsState(initial = "")

    var phase by remember { mutableStateOf(HandshakePhase.WaitingForNotify) }
    var statusText by remember { mutableStateOf("Waiting for confirmation from device...") }
    var lastEspDeviceId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val handshakeOk by bleManager.handshakeOk.collectAsState(initial = false)

//    var handshakeOk by remember { mutableStateOf(false) }

    // если уже спарено (есть deviceId в сессии) — считаем handshake done
//    LaunchedEffect(handshakeOk) {
//        if (handshakeOk) {
//            phase = HandshakePhase.Ready
//            statusText = "Successfully connected to device ($savedDeviceId). You can configure it."
//            busy = false
//        }
//    }
    LaunchedEffect(handshakeOk, savedDeviceId) {
        if (handshakeOk) {
            phase = HandshakePhase.Ready
            busy = false
            statusText =
                if (savedDeviceId.isNotBlank())
                    "Successfully connected to device ($savedDeviceId). You can configure it."
                else
                    "Successfully connected to device. You can configure it."
        }
    }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            busy = false
            lastEspDeviceId = null

            // включать notify можно всегда
            bleManager.enableDeviceIdNotifications()

            // но UI-статус НЕ сбрасываем, если handshake уже готов
            if (!handshakeOk) {
                phase = HandshakePhase.WaitingForNotify
                statusText =
                    if (savedDeviceId.isNotBlank())
                        "Saved deviceId: $savedDeviceId\nWaiting for confirmation from device… (press the button on device)"
                    else
                        "Waiting for confirmation from device… (press the button on device)"
            } else {
                phase = HandshakePhase.Ready
                statusText = "Successfully connected to device ($savedDeviceId). You can configure it."
            }
        } else {
            busy = false
            lastEspDeviceId = null
            phase = HandshakePhase.WaitingForNotify
            statusText = "Connect BLE device first (in BLE tab)"
        }
    }



    // при заходе на экран — включаем notify и ждём deviceId
//    LaunchedEffect(isConnected) {
//        if (isConnected) {
//            // новый коннект => handshake всегда заново
////            bleManager.setHandshakeOk(false)
////            handshakeOk = false
//            busy = false
//            lastEspDeviceId = null
//            phase = HandshakePhase.WaitingForNotify
//
//            statusText =
//                if (savedDeviceId.isNotBlank())
//                    "Saved deviceId: $savedDeviceId\nWaiting for confirmation from device… (press the button on device)"
//                else
//                    "Waiting for confirmation from device… (press the button on device)"
//
//            bleManager.enableDeviceIdNotifications()
//        } else {
//            // если отвалились — тоже сброс
////            handshakeOk = false
////            bleManager.setHandshakeOk(false)
//            busy = false
//            lastEspDeviceId = null
//            phase = HandshakePhase.WaitingForNotify
//            statusText = "Connect BLE device first (in BLE tab)"
//        }
//    }


    // слушаем deviceId notify (handshake живёт тут!)
    DisposableEffect(Unit) {
        bleManager.setOnDeviceIdListener { devId ->
            if (devId.isBlank()) return@setOnDeviceIdListener
            if (busy) return@setOnDeviceIdListener // игнор дубликатов, пока обрабатываем

            lastEspDeviceId = devId

            scope.launch {
                if (userId.isBlank()) {
                    phase = HandshakePhase.Error
                    statusText = "Handshake error: userId is empty (login again)."
                    return@launch
                }

                busy = true
                phase = HandshakePhase.Exchanging
                statusText = "Data exchange with device… received deviceId=$devId, sending userId…"

                // 1) send userId to ESP (по твоей логике: только после того, как ESP прислал deviceId)
                bleManager.sendUserId(userId)

                // 2) server pairing check + upsert (чтобы логика совпадала с тем, что ты делала в BLE табе)
                statusText = "Checking pairing on server…"

                val getRes = auth.call { token ->
                    api.getDeviceByUserId(userId = userId, accessToken = token)
                }

                if (getRes.isSuccess) {
                    val serverPair = getRes.getOrNull() // null == 404 "not paired" (как у тебя было)

                    if (serverPair == null) {
                        statusText = "Server: not paired. Pairing…"
                        val putRes = auth.call { token ->
                            api.putDeviceByUserId(userId = userId, deviceId = devId, accessToken = token)
                        }
                        if (putRes.isSuccess) {
                            session.setDeviceId(devId)
                            phase = HandshakePhase.Ready
                            bleManager.setHandshakeOk(true)
//                            handshakeOk = true;
                            statusText = "Successfully connected to device ($devId). You can configure it."

                        } else {
                            phase = HandshakePhase.Error
                            bleManager.setHandshakeOk(false)
//                            handshakeOk = false;
                            statusText = "Pairing failed: ${putRes.exceptionOrNull()?.toUserMessage() ?: "unknown error"}"
                        }
                    } else {
                        if (serverPair.deviceId == devId) {
                            session.setDeviceId(devId)
                            phase = HandshakePhase.Ready
                            bleManager.setHandshakeOk(true)
//                            handshakeOk = true;
                            statusText = "Successfully connected to device ($devId). You can configure it."
                        } else {
                            statusText = "DeviceId mismatch. Updating server…"
                            val putRes = auth.call { token ->
                                api.putDeviceByUserId(userId = userId, deviceId = devId, accessToken = token)
                            }
                            if (putRes.isSuccess) {
                                session.setDeviceId(devId)
                                phase = HandshakePhase.Ready
                                bleManager.setHandshakeOk(true)
//                                handshakeOk = true;
                                statusText = "Successfully connected to device ($devId). You can configure it."
                            } else {
                                phase = HandshakePhase.Error
                                bleManager.setHandshakeOk(false)
//                                handshakeOk = false;
                                statusText = "Update failed: ${putRes.exceptionOrNull()?.toUserMessage() ?: "unknown error"}"
                            }
                        }
                    }
                } else {
                    val e = getRes.exceptionOrNull()
                    phase = HandshakePhase.Error
                    bleManager.setHandshakeOk(false)
//                    handshakeOk = false;
                    statusText = "Server check failed: ${e?.toUserMessage() ?: "unknown error"}"
                }

                busy = false
            }
        }

        onDispose {
            bleManager.setOnDeviceIdListener { }
        }
    }


    val buttonsEnabled = handshakeOk && !busy

    // UI fields
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var experimentName by remember { mutableStateOf("") }
//    var isLedEnabled by remember { mutableStateOf(false) }
    var samplingMs by remember { mutableStateOf("") }
    var experimentNameError by remember { mutableStateOf<String?>(null) }
    var applyConfigSuccess by remember { mutableStateOf<String?>(null) }


    LazyColumn(modifier = modifier.padding(16.dp)) {

        // --- Handshake status block (BEFORE Wi-Fi block) ---
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Device handshake", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))

                    when (phase) {
                        HandshakePhase.WaitingForNotify -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }
                        HandshakePhase.Exchanging -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }
                        HandshakePhase.Ready -> { /* no progress */ }
                        HandshakePhase.Error -> { /* no progress */ }
                    }

                    Text(statusText)

                    if (lastEspDeviceId != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("ESP deviceId: ${lastEspDeviceId!!}")
                    }
                    if (savedDeviceId.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Saved deviceId: $savedDeviceId")
                    }

                    if (!handshakeOk) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "You can't configure device until you fully connect to it.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

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
                onClick = { onApplyWifi(ssid, password) },
                enabled = buttonsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply Wi-Fi") }
        }

        item {
            Spacer(Modifier.height(24.dp))
            Text("Device Config", style = MaterialTheme.typography.titleLarge)
        }

//        item {
//            Row(
//                modifier = Modifier.fillMaxWidth(),
//                horizontalArrangement = Arrangement.SpaceBetween
//            ) {
//                Text("LED enabled")
//                Switch(
//                    checked = isLedEnabled,
//                    onCheckedChange = { isLedEnabled = it },
//                    enabled = buttonsEnabled
//                )
//            }
//        }

        item {
            OutlinedTextField(
                value = experimentName,
                onValueChange = {
                    experimentName = it
                    experimentNameError = null
                    applyConfigSuccess = null
                },
                label = { Text("Experiment name") },
                isError = experimentNameError != null,
                supportingText = {
                    if (experimentNameError != null) {
                        Text(experimentNameError!!)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = samplingMs,
                onValueChange = {
                    samplingMs = it.filter { ch -> ch.isDigit() }
                    applyConfigSuccess = null
                },
                label = { Text("Sampling interval (ms)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick = {
//                    onApplyConfig(
//                        linkedMapOf(
////                            "isLedEnabled" to if (isLedEnabled) "1" else "0",
//                            "samplingMs" to samplingMs,
//                            "experimentName" to experimentName
//                        )
//                    )
                    applyConfigSuccess = null

                    val exp = experimentName.trim()
                    val smp = samplingMs.trim()

                    if (exp.isBlank()) {
                        experimentNameError = "Please enter experiment name."
                        return@Button
                    }
                    if (smp.isBlank()) {
                        experimentNameError = "Please enter sampling interval (ms)."
                        return@Button
                    }

                    scope.launch {
                        busy = true
                        experimentNameError = null
                        applyConfigSuccess = "Checking experiment name on server…"

                        val existsRes = auth.call { token ->
                            api.experimentExists(exp, token)
                        }

                        if (existsRes.isFailure) {
                            busy = false
                            experimentNameError = "Server check failed: ${existsRes.exceptionOrNull()?.toUserMessage() ?: "unknown error"}"
                            return@launch
                        }

                        val exists = existsRes.getOrNull() == true
                        if (exists) {
                            busy = false
                            experimentNameError = "Experiment with name '$exp' already exists. Choose another name."
                            return@launch
                        }

                        // 3) всё ок -> отправляем конфиг на ESP
                        onApplyConfig(
                            linkedMapOf(
                                "samplingMs" to smp,
                                "experimentName" to exp
                            )
                        )

                        busy = false
                        applyConfigSuccess = "Config sent to device."
                        experimentNameError = null
                    }
                },
                enabled = buttonsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply Config") }

            if (applyConfigSuccess != null) {
                Text(
                    text = applyConfigSuccess!!,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }



        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onFinishExperiment() },
                enabled = buttonsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Finish experiment") }
        }
    }
}