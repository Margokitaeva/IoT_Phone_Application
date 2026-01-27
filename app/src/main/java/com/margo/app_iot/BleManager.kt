package com.margo.app_iot

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class BleManager(
    private val context: Context,
    private val onDeviceFound: (ScanResult) -> Unit,
    private val onConnected: (String?) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onConfigApplied: () -> Unit
) {

    // ===== UUIDs =====
    // ===== Wi-Fi =====
    private val WIFI_SERVICE_UUID = shortUuid(0xFFF0)
    private val WIFI_SSID_CHAR_UUID  = shortUuid(0xFFF1)
    private val WIFI_PASS_CHAR_UUID  = shortUuid(0xFFF2)
    private val WIFI_APPLY_CHAR_UUID = shortUuid(0xFFF3)

    // ===== Config =====
    private val CONFIG_SERVICE_UUID = shortUuid(0xFFF4)
    private val LED_ENABLE_CHAR_UUID = shortUuid(0xFF12)
    private val EXP_NAME_CHAR_UUID      = shortUuid(0xFFF5)
    private val SAMPLING_MS_CHAR_UUID      = shortUuid(0xFFF6)
    private val CONFIG_APPLY_CHAR_UUID = shortUuid(0xFFF7)
    // TODO: rename to match ESP32 firmware naming
    private val FINISH_EXPERIMENT_CHAR_UUID  = shortUuid(0xFFFB)

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
    fun sendConfig(cfg: Map<String, String>) {
        val g = gatt ?: return
        val service = g.getService(CONFIG_SERVICE_UUID) ?: return

        val uuidMap = mapOf(
            "isLedEnabled" to LED_ENABLE_CHAR_UUID,
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


    // ===== GATT CALLBACK =====
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onDisconnected()
            }
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
            if (status != BluetoothGatt.GATT_SUCCESS) return

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

            // 1) Попытка как текст (JSON может приходить кусками)
            val chunk = try {
                String(data, Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }

            val looksLikeText = chunk.any { it == '{' || it == '}' || it == '[' || it == ']' || it == ':' || it == '"' }

            if (looksLikeText) {
                jsonRxBuffer.append(chunk)

                val bufStr = jsonRxBuffer.toString()
                val endIndex = bufStr.lastIndexOf('}')
                if (endIndex != -1) {
                    val jsonStr = bufStr.substring(0, endIndex + 1)

                    jsonRxBuffer.clear()
                    if (endIndex + 1 < bufStr.length) {
                        jsonRxBuffer.append(bufStr.substring(endIndex + 1))
                    }

                    try {
                        val json = JSONObject(jsonStr)

                        val mpuArr = json.optJSONArray("mpuProcessedData") ?: JSONArray()

                        val batch = buildList {
                            for (i in 0 until mpuArr.length()) {
                                val row = mpuArr.getJSONArray(i)
                                if (row.length() >= 4) {
                                    add(
                                        QuaternionSample(
                                            q0 = row.getDouble(0).toFloat(),
                                            q1 = row.getDouble(1).toFloat(),
                                            q2 = row.getDouble(2).toFloat(),
                                            q3 = row.getDouble(3).toFloat()
                                        )
                                    )
                                }
                            }
                        }

                        if (batch.isNotEmpty()) {
                            onQuaternionBatch?.invoke(batch)

                            // OPTIONAL: оставляем совместимость со старым UI (первый кватернион как раньше)
//                            onQuaternionSample?.invoke(batch.first())
                        }
                    } catch (e: Exception) {
                        // ignore bad JSON
                    }
                }
                return
            }

            // 2) Fallback: старый бинарный формат (4 float little-endian)
//            val buffer = java.nio.ByteBuffer
//                .wrap(data)
//                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
//
//            val sample = QuaternionSample(
//                q0 = buffer.float,
//                q1 = buffer.float,
//                q2 = buffer.float,
//                q3 = buffer.float
//            )
//
//            onQuaternionSample?.invoke(sample)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != QUAT_CHAR_UUID) return

            // 1) Попытка как текст (JSON может приходить кусками)
            val chunk = try {
                String(value, Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }

            val looksLikeText = chunk.any { it == '{' || it == '}' || it == '[' || it == ']' || it == ':' || it == '"' }

            if (looksLikeText) {
                jsonRxBuffer.append(chunk)

                val bufStr = jsonRxBuffer.toString()
                val endIndex = bufStr.lastIndexOf('}')
                if (endIndex != -1) {
                    val jsonStr = bufStr.substring(0, endIndex + 1)

                    jsonRxBuffer.clear()
                    if (endIndex + 1 < bufStr.length) {
                        jsonRxBuffer.append(bufStr.substring(endIndex + 1))
                    }

                    try {
                        val json = JSONObject(jsonStr)
                        val mpuArr = json.optJSONArray("mpuProcessedData") ?: JSONArray()

                        val batch = buildList {
                            for (i in 0 until mpuArr.length()) {
                                val row = mpuArr.optJSONArray(i) ?: continue
                                if (row.length() >= 4) {
                                    add(
                                        QuaternionSample(
                                            q0 = row.getDouble(0).toFloat(),
                                            q1 = row.getDouble(1).toFloat(),
                                            q2 = row.getDouble(2).toFloat(),
                                            q3 = row.getDouble(3).toFloat()
                                        )
                                    )
                                }
                            }
                        }

                        if (batch.isNotEmpty()) {
                            // ВАЖНО: графики подписаны на batch
                            onQuaternionBatch?.invoke(batch)

                            // OPTIONAL: совместимость со старым single-sample UI
                            onQuaternionSample?.invoke(batch.first())
                        }
                    } catch (e: Exception) {
                        // ignore bad JSON
                    }
                }
                return
            }

            // 2) Fallback: старый бинарный формат (4 float little-endian)
            val buffer = java.nio.ByteBuffer
                .wrap(value)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)

            val sample = QuaternionSample(
                q0 = buffer.float,
                q1 = buffer.float,
                q2 = buffer.float,
                q3 = buffer.float
            )

            onQuaternionSample?.invoke(sample)
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
        }
    }

    fun readQuaternionOnce() {
        val g = gatt ?: return
        val service = g.getService(DATA_SERVICE_UUID) ?: return
        val ch = service.getCharacteristic(QUAT_CHAR_UUID) ?: return
        g.readCharacteristic(ch)
    }

    fun enableQuaternionNotifications() {
        val g = gatt ?: return
        val service = g.getService(DATA_SERVICE_UUID) ?: return
        val ch = service.getCharacteristic(QUAT_CHAR_UUID) ?: return

        g.setCharacteristicNotification(ch, true)

        val cccd = ch.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        ) ?: return

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(cccd)
    }



    fun setOnQuaternionSampleListener(listener: (QuaternionSample) -> Unit) {
        onQuaternionSample = listener
    }

    fun setOnQuaternionBatchListener(listener: (List<QuaternionSample>) -> Unit) {
        onQuaternionBatch = listener
    }
}
