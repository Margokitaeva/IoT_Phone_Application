package com.margo.app_iot

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.bluetooth.le.ScanResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.margo.app_iot.data.SessionStore
import com.margo.app_iot.network.ApiClient
import com.margo.app_iot.network.AuthRepository
import com.margo.app_iot.ui.auth.AuthRoot
import com.margo.app_iot.ui.doctor.DoctorHome
import com.margo.app_iot.ui.patient.PatientHome
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // ===== UI STATE =====
    private val scanResults = mutableStateListOf<ScanResult>()
    private val isConnected = mutableStateOf(false)
    private val connectedDeviceName = mutableStateOf<String?>(null)

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

        bleManager = BleManager(
            context = this,
            onDeviceFound = { result ->
                if (!scanResults.any { it.device.address == result.device.address }) {
                    scanResults.add(result)
                }
            },
            onConnected = { name ->
                isConnected.value = true
                connectedDeviceName.value = name
            },
            onDisconnected = {
                isConnected.value = false
                connectedDeviceName.value = null
            },
            onConfigApplied = { /* unused here */ }
        )

        setContent {
            MaterialTheme {
                val session = remember { SessionStore(this) }

                // TODO: ВСТАВЬ СВОЙ URL СЕРВЕРА
                val api = remember { ApiClient(baseUrl = "http://10.24.107.28:3000") }

                // ВАЖНО: один общий репозиторий, который делает refresh/retry
                val auth = remember { AuthRepository(api = api, session = session) }

                val loggedIn by session.loggedInFlow.collectAsState(initial = false)
                val role by session.roleFlow.collectAsState(initial = "")

                val scope = rememberCoroutineScope()

                if (!loggedIn) {
                    AuthRoot(
                        api = api,
                        session = session,
                        onAuthed = { /* state updates via DataStore */ }
                    )
                } else {
                    if (role == "patient") {
                        PatientHome(
                            api = api,
                            auth = auth,
                            session = session,
                            bleManager = bleManager,
                            permissionLauncher = permissionLauncher,
                            blePermissions = blePermissions,
                            devices = scanResults,
                            isConnected = isConnected.value,
                            connectedDeviceName = connectedDeviceName.value,
                            onLogout = {
                                scope.launch {
                                    // 1) BLE disconnect/close + stop scan
                                    bleManager.disconnectAndClose()

                                    // 2) (опционально) чистим UI-список найденных девайсов
                                    scanResults.clear()
                                    isConnected.value = false
                                    connectedDeviceName.value = null

                                    // 3) server logout + local session logout (у тебя это уже внутри auth.logout())
                                    auth.logout()
                                }
                            }

                        )
                    } else {
                        DoctorHome(
                            api = api,
                            auth = auth,
                            session = session,
                            onLogout = {
                                scope.launch {
                                    // 1) BLE disconnect/close + stop scan
                                    bleManager.disconnectAndClose()

                                    // 2) (опционально) чистим UI-список найденных девайсов
                                    scanResults.clear()
                                    isConnected.value = false
                                    connectedDeviceName.value = null

                                    // 3) server logout + local session logout (у тебя это уже внутри auth.logout())
                                    auth.logout()
                                }
                            }

                        )
                    }
                }
            }
        }
    }
}
