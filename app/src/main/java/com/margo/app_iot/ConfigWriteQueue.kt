package com.margo.app_iot

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import java.util.ArrayDeque

class ConfigWriteQueue {

    private val queue =
        ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()

    private var isWriting = false

    fun enqueue(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        queue.addLast(characteristic to value)
    }

    fun clear() {
        queue.clear()
        isWriting = false
    }

    fun start(gatt: BluetoothGatt) {
        if (isWriting) return
        if (queue.isEmpty()) return

        isWriting = true
        val (ch, value) = queue.removeFirst()
        ch.value = value
        val ok = gatt.writeCharacteristic(ch)

        Log.d(
            "WRITE_Q",
            "start write uuid=${ch.uuid} len=${value.size} ok=$ok props=${ch.properties} writeType=${ch.writeType} firstBytes=${
                value.take(8).joinToString(" ") { "%02X".format(it) }
            }"
        )

//        gatt.writeCharacteristic(ch)
    }

    fun onWriteComplete(gatt: BluetoothGatt) {
        if (queue.isNotEmpty()) {
            val (ch, value) = queue.removeFirst()
            ch.value = value

            val ok = gatt.writeCharacteristic(ch)

            Log.d(
                "WRITE_Q",
                "next write uuid=${ch.uuid} len=${value.size} ok=$ok props=${ch.properties} writeType=${ch.writeType} firstBytes=${
                    value.take(8).joinToString(" ") { "%02X".format(it) }
                }"
            )

//            gatt.writeCharacteristic(ch)
        } else {
            isWriting = false
        }
    }

    fun isBusy(): Boolean = isWriting
}
