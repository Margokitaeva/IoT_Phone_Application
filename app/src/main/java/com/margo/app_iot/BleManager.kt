package com.margo.app_iot

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Base64
import android.util.Log
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


class BleManager(
    private val context: Context,
    private val onDeviceFound: (ScanResult) -> Unit,
    private val onConnected: (String?) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onConfigApplied: () -> Unit
) {

//    private var onBleDebug: ((String) -> Unit)? = null
//
//    fun setOnBleDebugListener(cb: ((String) -> Unit)?) {
//        onBleDebug = cb
//    }

    private val bleHandler = Handler(Looper.getMainLooper())

    // ===== UUIDs =====
    // ===== Wi-Fi =====
    private val WIFI_SERVICE_UUID = shortUuid(0xFFF0)
    private val WIFI_SSID_CHAR_UUID  = shortUuid(0xFFF1)
    private val WIFI_PASS_CHAR_UUID  = shortUuid(0xFFF2)
    private val WIFI_APPLY_CHAR_UUID = shortUuid(0xFFF3)

    // ===== Config =====
    private val CONFIG_SERVICE_UUID = shortUuid(0xFFF4)
//    private val LED_ENABLE_CHAR_UUID = shortUuid(0xFF12)
    private val EXP_NAME_CHAR_UUID      = shortUuid(0xFFF5)
    private val SAMPLING_MS_CHAR_UUID      = shortUuid(0xFFF6)
    private val CONFIG_APPLY_CHAR_UUID = shortUuid(0xFFF7)
    // TODO: rename to match ESP32 firmware naming
    private val FINISH_EXPERIMENT_CHAR_UUID  = shortUuid(0xFFFB)

    // TODO: согласовать/переименовать UUID/названия с ESP
    private val DEVICE_ID_CHAR_UUID = shortUuid(0xFFFC)   // notify/read: ESP -> phone (deviceId)
    private val USER_ID_CHAR_UUID   = shortUuid(0xFFFD)   // write: phone -> ESP (userId/username)

    // data
    private val DATA_SERVICE_UUID = shortUuid(0xFFF8)
    private val QUAT_CHAR_UUID   = shortUuid(0xFFF9)

//    private val SERVICE_UUID = shortUuid(0xFFF0)
//
//    private val WIFI_SSID_UUID = shortUuid(0xFFF1)
//    private val WIFI_PASS_UUID = shortUuid(0xFFF2)
//
//    private val LED_ENABLE_UUID = shortUuid(0xFFF6)
//    private val CFG_A_UUID = shortUuid(0xFFF4)
//    private val CFG_B_UUID = shortUuid(0xFFF5)

    private val jsonRxBuffer = StringBuilder()

    private val _handshakeOk = MutableStateFlow(false)
    val handshakeOk: StateFlow<Boolean> = _handshakeOk

    fun setHandshakeOk(v: Boolean) { _handshakeOk.value = v }

    private fun shortUuid(hex: Int): UUID =
        UUID.fromString(
            String.format(
                "0000%04X-0000-1000-8000-00805F9B34FB",
                hex
            )
        )

    // ===== BLE =====
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter =
        bluetoothManager.adapter

    private val scanner = bluetoothAdapter.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private val writeQueue = ConfigWriteQueue()

    private var onQuaternionSample: ((QuaternionSample) -> Unit)? = null
    private var onQuaternionBatch: ((List<QuaternionSample>) -> Unit)? = null

    private var onDeviceIdReceived: ((String) -> Unit)? = null

    private val DESIRED_MTU = 500

//    private val configQueue = ConfigWriteQueue()

    // ===== SCAN =====
    fun startScan() {
        scanner.startScan(scanCallback)
    }

    fun stopScan() {
        try {
            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e("BLE", "stopScan: missing permission", e)
        } catch (e: Exception) {
            Log.e("BLE", "stopScan failed", e)
        }
    }

//    fun disconnectAndClose() {
//        // Остановить сканирование (если шло)
//        stopScan()
//
//        // Сбросить буферы/коллбеки чтобы ничего не прилетало в UI после logout
//        clearCallbacks()
//        jsonRxBuffer.clear()
//
//        // Разорвать gatt
//        try {
//            gatt?.disconnect()
//        } catch (_: Exception) {}
//
//        try {
//            gatt?.close()
//        } catch (_: Exception) {}
//
//        gatt = null
//    }

    fun disconnectAndClose() {
        // 1) остановить скан
        try { stopScan() } catch (_: Exception) {}

        // 2) отрубить выдачу в UI
        clearCallbacks()

        // 3) очистить RX буфер (лучше под локом, если есть конкурентный доступ)
//        synchronized(rxLock) {
//            jsonRxBuffer.clear()
//        }
        jsonRxBuffer.clear()

        // 4) корректно прибить gatt
        val g = gatt
        gatt = null
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
    }

    fun clearCallbacks() {
        onQuaternionSample = null
        onQuaternionBatch = null
        onDeviceIdReceived = null
        // onConfigApplied у тебя приходит в конструктор как callback — его занулять нельзя
    }




    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            onDeviceFound(result)
        }
    }

    // ===== CONNECT =====
    fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback)
    }

    // ===== WRITE WIFI =====
    fun sendWifi(ssid: String, pass: String) {
//        Log.d("WIFI", "sendWifi CALLED")

        val g = gatt
        if (g == null) {
            Log.e("WIFI", "gatt == null")
            return
        }

        val service = g.getService(WIFI_SERVICE_UUID)
        if (service == null) {
            Log.e("WIFI", "WIFI_SERVICE not found: $WIFI_SERVICE_UUID")
            return
        }

        val ssidCh = service.getCharacteristic(WIFI_SSID_CHAR_UUID)
        val passCh = service.getCharacteristic(WIFI_PASS_CHAR_UUID)
        val applyCh = service.getCharacteristic(WIFI_APPLY_CHAR_UUID)

        if (ssidCh == null) {
            Log.e("WIFI", "SSID characteristic NOT found")
            return
        }
        if (passCh == null) {
            Log.e("WIFI", "PASS characteristic NOT found")
            return
        }
        if (applyCh == null) {
            Log.e("WIFI", "APPLY characteristic NOT found")
            return
        }

        Log.d("WIFI", "All characteristics found, enqueue writes")

        writeQueue.clear()
        writeQueue.enqueue(ssidCh, ssid.toByteArray())
        writeQueue.enqueue(passCh, pass.toByteArray())
        writeQueue.enqueue(applyCh, byteArrayOf(1))

        writeQueue.start(g)
    }

    // ===== WRITE CONFIG =====

    fun sendUserId(userId: String) {
        val g = gatt ?: return
        val service = g.getService(CONFIG_SERVICE_UUID) ?: return
        val ch = service.getCharacteristic(USER_ID_CHAR_UUID) ?: return

        val bytes = userId.toByteArray(StandardCharsets.UTF_8)

        writeQueue.clear()
        writeQueue.enqueue(ch, bytes)
        writeQueue.start(g)
    }


    fun sendConfig(cfg: Map<String, String>) {
        val g = gatt ?: return
        val service = g.getService(CONFIG_SERVICE_UUID) ?: return

        val uuidMap = mapOf(
//            "isLedEnabled" to LED_ENABLE_CHAR_UUID,
            "experimentName" to EXP_NAME_CHAR_UUID,
            "samplingMs" to SAMPLING_MS_CHAR_UUID
        )

        writeQueue.clear()

        // parameters
        for ((key, value) in cfg) {
            val uuid = uuidMap[key] ?: continue
            val ch = service.getCharacteristic(uuid) ?: continue
            writeQueue.enqueue(ch, value.toByteArray())
        }

        // config apply
        val applyCh = service.getCharacteristic(CONFIG_APPLY_CHAR_UUID) ?: return
        Log.d("CFG", "apply props=${applyCh.properties} writeType=${applyCh.writeType}")
        writeQueue.enqueue(applyCh, byteArrayOf(1))

        writeQueue.start(g)
    }

    fun finishExperiment() {
        val g = gatt
        if (g == null) {
            Log.e("BLE", "finishExperiment: gatt == null")
            return
        }

        // тот же сервис, что и для config
        val service = g.getService(CONFIG_SERVICE_UUID)
        if (service == null) {
            Log.e("BLE", "finishExperiment: config service not found: $CONFIG_SERVICE_UUID")
            return
        }

        // новая характеристика внутри этого же сервиса
        val ch = service.getCharacteristic(FINISH_EXPERIMENT_CHAR_UUID)
        if (ch == null) {
            Log.e("BLE", "finishExperiment: characteristic not found: $FINISH_EXPERIMENT_CHAR_UUID")
            return
        }

        try {
            writeQueue.clear()
            writeQueue.enqueue(ch, byteArrayOf(1))
            writeQueue.start(g)

            Log.i("BLE", "finishExperiment: sent 1 to $FINISH_EXPERIMENT_CHAR_UUID")
        } catch (e: Exception) {
            Log.e("BLE", "finishExperiment: write failed", e)
        }
    }

    private fun decodeFloat32LeBase64(b64: String, floatsExpected: Int): FloatArray {
        val raw = try {
            Base64.decode(b64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            Base64.decode(b64.trim(), Base64.DEFAULT)
        }

        if (raw.size % 4 != 0) {
            throw IllegalArgumentException("Raw byte length is not multiple of 4: ${raw.size}")
        }

        val actualFloats = raw.size / 4
        if (actualFloats != floatsExpected) {
            throw IllegalArgumentException("Float count mismatch: expected=$floatsExpected actual=$actualFloats")
        }

        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(actualFloats) { bb.float }
    }

    private fun parseQuaternionJsonB64(json: JSONObject): List<QuaternionSample> {
        val format = json.optString("mpuProcessedData_format", "")
        if (format != "float32_le_base64") {
            throw IllegalArgumentException("Unsupported format: $format")
        }

        val floatsExpected = json.optInt("mpuProcessedData_floats", -1)
        if (floatsExpected <= 0) {
            throw IllegalArgumentException("mpuProcessedData_floats missing/invalid: $floatsExpected")
        }

        val b64 = json.optString("mpuProcessedData_b64", "")
        if (b64.isBlank()) return emptyList()

        val floats = decodeFloat32LeBase64(b64, floatsExpected)

        if (floats.size % 4 != 0) {
            throw IllegalArgumentException("Float array length is not multiple of 4: ${floats.size}")
        }

        val out = ArrayList<QuaternionSample>(floats.size / 4)
        var i = 0
        while (i + 3 < floats.size) {
            out.add(
                QuaternionSample(
                    q0 = floats[i],
                    q1 = floats[i + 1],
                    q2 = floats[i + 2],
                    q3 = floats[i + 3]
                )
            )
            i += 4
        }
        return out
    }


    private var hadWriteError = false;


    // ===== GATT CALLBACK =====
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _handshakeOk.value = false

                // Просим MTU. Если телефон не поддержит — вернёт false, тогда просто идём дальше.
                val ok = gatt.requestMtu(DESIRED_MTU)
                Log.d("BLE", "requestMtu($DESIRED_MTU) -> $ok")

                if (!ok) {
                    // если запрос не ушёл — сразу дискавер сервисов
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _handshakeOk.value = false
                onDisconnected()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BLE", "onMtuChanged mtu=$mtu status=$status")
            // Дальше продолжаем обычный флоу
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onConnected(gatt.device.name)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                hadWriteError = true
                Log.e("WRITE_E", "WRITE FAILED uuid=${characteristic.uuid} status=$status")
            }

            Log.d("CFG", "onCharWrite uuid=${characteristic.uuid} status=$status")
            writeQueue.onWriteComplete(gatt)

            if (!writeQueue.isBusy()) {
                onConfigApplied()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid != QUAT_CHAR_UUID) return

            val data = characteristic.value ?: return

            val chunk = try {
                String(data, Charsets.UTF_8)
            } catch (_: Exception) {
                return
            }

            // если буфер пустой — начинаем только когда увидели начало JSON
            if (jsonRxBuffer.isEmpty()) {
                val start = chunk.indexOf('{')
                if (start == -1) return
                jsonRxBuffer.append(chunk.substring(start))
            } else {
                // если уже начали собирать JSON — добавляем любые куски (включая base64 без спец-символов)
                jsonRxBuffer.append(chunk)
            }

            // пытаемся вытаскивать завершённые JSON-объекты
            while (true) {
                val bufStr = jsonRxBuffer.toString()

                // если вдруг до '{' накопился мусор — срезаем
                val startIdx = bufStr.indexOf('{')
                if (startIdx == -1) {
                    jsonRxBuffer.clear()
                    return
                }
                if (startIdx > 0) {
                    jsonRxBuffer.delete(0, startIdx)
                }

                val endIdx = jsonRxBuffer.indexOf("}")
                if (endIdx == -1) return // ещё не пришёл конец JSON

                val jsonStr = jsonRxBuffer.substring(0, endIdx + 1)

                try {
                    val json = JSONObject(jsonStr)
                    val batch = parseQuaternionJsonB64(json)
                    if (batch.isNotEmpty()) onQuaternionBatch?.invoke(batch)

                    // удаляем обработанный JSON, оставляем хвост (если он уже пришёл)
                    jsonRxBuffer.delete(0, endIdx + 1)
                } catch (e: Exception) {
                    // если '}' есть, но JSON не парсится — скорее всего это разрыв/мусор
                    Log.w("BLE", "Bad JSON in onCharacteristicRead, drop buffer. msg=${e.message}")
                    jsonRxBuffer.clear()
                    return
                }
            }
        }


        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // 1) DeviceId notify (ESP -> phone)
            if (characteristic.uuid == DEVICE_ID_CHAR_UUID) {
                val devId = try {
                    String(value, Charsets.UTF_8)
                        .trim()
                        .trim('\u0000')
                } catch (_: Exception) {
                    ""
                }

                if (devId.isNotBlank()) {
                    onDeviceIdReceived?.invoke(devId)
                }
                return
            }

            // 2) Quaternion JSON notify only
            if (characteristic.uuid != QUAT_CHAR_UUID) return

            val chunk = try {
                String(value, Charsets.UTF_8)
            } catch (_: Exception) {
                return
            }

            // если буфер пустой — начинаем только когда увидели начало JSON
            if (jsonRxBuffer.isEmpty()) {
                val start = chunk.indexOf('{')
                if (start == -1) return
                jsonRxBuffer.append(chunk.substring(start))
            } else {
                // если уже начали собирать JSON — добавляем любые куски (включая base64 без спец-символов)
                jsonRxBuffer.append(chunk)
            }

            // пытаемся вытаскивать завершённые JSON-объекты
            while (true) {
                val bufStr = jsonRxBuffer.toString()

                // если вдруг до '{' накопился мусор — срезаем
                val startIdx = bufStr.indexOf('{')
                if (startIdx == -1) {
                    jsonRxBuffer.clear()
                    return
                }
                if (startIdx > 0) {
                    jsonRxBuffer.delete(0, startIdx)
                }

                val endIdx = jsonRxBuffer.indexOf("}")
                if (endIdx == -1) return // ещё не пришёл конец JSON

                val jsonStr = jsonRxBuffer.substring(0, endIdx + 1)

                try {
                    val json = JSONObject(jsonStr)
                    val batch = parseQuaternionJsonB64(json)
                    if (batch.isNotEmpty()) onQuaternionBatch?.invoke(batch)

                    // удаляем обработанный JSON, оставляем хвост (если он уже пришёл)
                    jsonRxBuffer.delete(0, endIdx + 1)
                } catch (e: Exception) {
                    Log.w("BLE", "Bad JSON in onCharacteristicChanged, drop buffer. msg=${e.message}")
                    jsonRxBuffer.clear()
                    return
                }
            }
        }





        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                println("CCCD write failed: ${descriptor.uuid}")
            } else {
                println("CCCD enabled OK")
            }
//            onBleDebug?.invoke("onDescriptorWrite uuid=${descriptor.uuid} status=$status")
        }
    }

    fun readQuaternionOnce() {
        val g = gatt ?: return
        val service = g.getService(DATA_SERVICE_UUID) ?: return
        val ch = service.getCharacteristic(QUAT_CHAR_UUID) ?: return
        g.readCharacteristic(ch)
    }

//    fun enableQuaternionNotifications() {
//        val g = gatt ?: return
//        val service = g.getService(DATA_SERVICE_UUID) ?: return
//        val ch = service.getCharacteristic(QUAT_CHAR_UUID) ?: return
//
//        g.setCharacteristicNotification(ch, true)
//
//        val cccd = ch.getDescriptor(
//            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
//        ) ?: return
//
//        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//        g.writeDescriptor(cccd)
//    }
//
//    fun enableDeviceIdNotifications() {
//        val g = gatt ?: return
//        val service = g.getService(CONFIG_SERVICE_UUID) ?: return
//        val ch = service.getCharacteristic(DEVICE_ID_CHAR_UUID) ?: return
//
//        g.setCharacteristicNotification(ch, true)
//
//        val cccd = ch.getDescriptor(
//            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
//        ) ?: return
//
//        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//        g.writeDescriptor(cccd)
//    }

    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private fun enableNotify(serviceUuid: UUID, charUuid: UUID, tag: String) {
        val g = gatt ?: run {
//            onBleDebug?.invoke("$tag: gatt=null")
            return
        }
        val service = g.getService(serviceUuid) ?: run {
//            onBleDebug?.invoke("$tag: service not found $serviceUuid")
            return
        }
        val ch = service.getCharacteristic(charUuid) ?: run {
//            onBleDebug?.invoke("$tag: char not found $charUuid")
            return
        }

        val okNotif = g.setCharacteristicNotification(ch, true)
//        onBleDebug?.invoke("$tag: setCharacteristicNotification=$okNotif")

        val cccd = ch.getDescriptor(CCCD_UUID) ?: run {
//            onBleDebug?.invoke("$tag: CCCD(2902) not found")
            return
        }

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val okWrite = g.writeDescriptor(cccd)
//        onBleDebug?.invoke("$tag: writeDescriptor(CCCD)=$okWrite")

        // если false — значит GATT был занят и запись не ушла
        // (дальше можно сделать retry, но хотя бы ты увидишь причину)
    }

    fun enableDeviceIdNotifications() {
        enableNotify(CONFIG_SERVICE_UUID, DEVICE_ID_CHAR_UUID, tag = "DEV_ID_NOTIFY")
    }

    fun enableQuaternionNotifications() {
        enableNotify(DATA_SERVICE_UUID, QUAT_CHAR_UUID, tag = "QUAT_NOTIFY")
    }




    fun setOnQuaternionSampleListener(listener: (QuaternionSample) -> Unit) {
        onQuaternionSample = listener
    }

    fun setOnQuaternionBatchListener(listener: (List<QuaternionSample>) -> Unit) {
        onQuaternionBatch = listener
    }

    fun setOnDeviceIdListener(listener: (String) -> Unit) {
        onDeviceIdReceived = listener
    }
}
