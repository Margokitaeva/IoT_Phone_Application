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
import com.margo.app_iot.ui.auth.AuthRoot
import com.margo.app_iot.ui.doctor.DoctorHome
import com.margo.app_iot.ui.patient.PatientHome
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // ===== UI STATE (твои текущие) ===== :contentReference[oaicite:1]{index=1}
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


                // TODO: ВСТАВЬ СВОЙ URL СЕРВЕРА:
                // пример: "http://10.0.2.2:8080" (если сервер на ПК и запускаешь эмулятор)
                // или "http://192.168.0.10:8080" (если сервер в локальной сети)
                val api = remember { ApiClient(baseUrl = "http://10.24.107.28:5000") }

                val loggedIn by session.loggedInFlow.collectAsState(initial = false)
                val role by session.roleFlow.collectAsState(initial = "")
                val username by session.usernameFlow.collectAsState(initial = "")

                val scope = rememberCoroutineScope()

                if (!loggedIn) {
                    AuthRoot(
                        api = api,
                        session = session,
                        onAuthed = { /* state will update via DataStore */ }
                    )
                } else {
                    if (role == "patient") {
                        PatientHome(
                            api = api,
                            session = session,
                            bleManager = bleManager,
                            permissionLauncher = permissionLauncher,
                            blePermissions = blePermissions,
                            devices = scanResults,
                            isConnected = isConnected.value,
                            connectedDeviceName = connectedDeviceName.value,
                            onLogout = { scope.launch { session.logout() } }
                        )
                    } else {
                        DoctorHome(
                            api = api,
                            session = session,
                            onLogout = { scope.launch { session.logout() } }
                        )
                    }
                }
            }
        }
    }
}
