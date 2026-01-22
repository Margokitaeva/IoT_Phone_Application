package com.margo.app_iot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.collections.plus
import kotlin.collections.takeLast

@Composable
fun VisualizationScreen(
    modifier: Modifier = Modifier,
    isConnected: Boolean,
    bleManager: BleManager
) {
    if (!isConnected) {
        Column(modifier = modifier.padding(16.dp)) {
            Text("Visualization", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Connect BLE device first (in Connect tab).")
        }
        return
    }

    var samples by remember { mutableStateOf(listOf<QuaternionSample>()) }
    var t by remember { mutableStateOf(0f) }

    val onNewSample: (QuaternionSample) -> Unit = { sample ->
        samples = (samples + sample).takeLast(200)
    }

    LaunchedEffect(Unit) {
        bleManager.setOnQuaternionSampleListener(onNewSample)
    }

    // odbieranie co jakis czas
//    LaunchedEffect(isConnected) {
//        if (!isConnected) return@LaunchedEffect
//
//        while (true) {
//            bleManager.readQuaternionOnce()
//            delay(1000) // jak czesto
//        }
//    }

    // po notyfikacji
    LaunchedEffect(isConnected) {
        if (isConnected) {
            bleManager.enableQuaternionNotifications()
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        Text("Quaternion", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        QuaternionChart(
            samples = samples,
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        )
    }
}


@Composable
fun QuaternionChart(
    samples: List<QuaternionSample>,
    modifier: Modifier = Modifier
) {
    if (samples.isEmpty()) return

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val paddingLeft = 60f
        val paddingBottom = 40f
        val paddingTop = 20f
        val paddingRight = 20f

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        // диапазон значений (пока фиксированный)
        val minY = -1f
        val maxY = 1f

        fun mapY(value: Float): Float {
            val norm = (value - minY) / (maxY - minY)
            return paddingTop + chartHeight * (1f - norm)
        }

        fun mapX(index: Int): Float {
            if (samples.size <= 1) return paddingLeft
            return paddingLeft + chartWidth * index / (samples.size - 1)
        }

        /* ---------- GRID ---------- */

        val gridLines = 4
        val gridColor = androidx.compose.ui.graphics.Color.LightGray.copy(alpha = 0.5f)

        for (i in 0..gridLines) {
            val y = paddingTop + chartHeight * i / gridLines
            drawLine(
                color = gridColor,
                start = androidx.compose.ui.geometry.Offset(paddingLeft, y),
                end = androidx.compose.ui.geometry.Offset(width - paddingRight, y),
                strokeWidth = 1f
            )
        }

        for (i in 0..gridLines) {
            val x = paddingLeft + chartWidth * i / gridLines
            drawLine(
                color = gridColor,
                start = androidx.compose.ui.geometry.Offset(x, paddingTop),
                end = androidx.compose.ui.geometry.Offset(x, height - paddingBottom),
                strokeWidth = 1f
            )
        }

        /* ---------- AXES ---------- */

        val axisColor = androidx.compose.ui.graphics.Color.Black

        // Y axis
        drawLine(
            color = axisColor,
            start = androidx.compose.ui.geometry.Offset(paddingLeft, paddingTop),
            end = androidx.compose.ui.geometry.Offset(paddingLeft, height - paddingBottom),
            strokeWidth = 2f
        )

        // X axis
        drawLine(
            color = axisColor,
            start = androidx.compose.ui.geometry.Offset(paddingLeft, height - paddingBottom),
            end = androidx.compose.ui.geometry.Offset(width - paddingRight, height - paddingBottom),
            strokeWidth = 2f
        )

        /* ---------- LABEL ON Y ---------- */

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 28f
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        for (i in 0..gridLines) {
            val value = maxY - (maxY - minY) * i / gridLines
            val y = paddingTop + chartHeight * i / gridLines
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.1f", value),
                paddingLeft - 8f,
                y + 10f,
                textPaint
            )
        }

        /* ---------- QUATERNIONS LINES ---------- */

        fun drawSignal(
            values: List<Float>,
            color: androidx.compose.ui.graphics.Color
        ) {
            val path = androidx.compose.ui.graphics.Path()
            values.forEachIndexed { index, v ->
                val x = mapX(index)
                val y = mapY(v)
                if (index == 0) path.moveTo(x, y)
                else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
        }

        drawSignal(samples.map { it.q0 }, androidx.compose.ui.graphics.Color.Red)
        drawSignal(samples.map { it.q1 }, androidx.compose.ui.graphics.Color.Green)
        drawSignal(samples.map { it.q2 }, androidx.compose.ui.graphics.Color.Blue)
        drawSignal(samples.map { it.q3 }, androidx.compose.ui.graphics.Color.Magenta)
    }
}