package com.margo.app_iot

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import java.util.UUID

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

//    private val configQueue = ConfigWriteQueue()

    // ===== SCAN =====
    fun startScan() {
        scanner.startScan(scanCallback)
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

            if (characteristic.uuid == QUAT_CHAR_UUID) {
                val data = characteristic.value

                // пример: 4 float по 4 байта
                val buffer = java.nio.ByteBuffer
                    .wrap(data)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)

                val sample = QuaternionSample(
                    q0 = buffer.float,
                    q1 = buffer.float,
                    q2 = buffer.float,
                    q3 = buffer.float
                )

                onQuaternionSample?.invoke(sample)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == QUAT_CHAR_UUID) {

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
}
