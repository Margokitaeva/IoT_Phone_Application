@file:OptIn(ExperimentalMaterial3Api::class)

package com.margo.app_iot.ui.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.margo.app_iot.network.ApiClient

@Composable
fun ExperimentDetailsReadOnlyCard(
    doctorId: String?,
    comment: String?,
    metrics: ApiClient.ExperimentMetrics?,
    onRefresh: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Comment from doctor ${doctorId ?: "—"}",
                style = MaterialTheme.typography.titleSmall
            )
            if (onRefresh != null) {
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }

        val commentText = comment?.takeIf { it.isNotBlank() } ?: "—"
        AssistChip(onClick = {}, label = { Text(commentText) })

        Spacer(Modifier.height(2.dp))
        Text("Metrics", style = MaterialTheme.typography.titleSmall)

        MetricsTable(metrics = metrics)
    }
}

@Composable
fun ExperimentDetailsEditorCard(
    doctorId: String?,
    initialComment: String?,
    metrics: ApiClient.ExperimentMetrics?,
    onSave: (String) -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    var draft by rememberSaveable(initialComment) { mutableStateOf(initialComment.orEmpty()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Comment (doctor ${doctorId ?: "—"})",
                style = MaterialTheme.typography.titleSmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRefresh != null) {
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                }
                Button(onClick = { onSave(draft) }) { Text("Save") }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text("Comment") }
        )

        Spacer(Modifier.height(2.dp))
        Text("Metrics", style = MaterialTheme.typography.titleSmall)
        MetricsTable(metrics = metrics)
    }
}

@Composable
fun MetricsTable(metrics: ApiClient.ExperimentMetrics?) {
    if (metrics == null) {
        Text("—")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricRow("hip_rom_left", metrics.hip_rom_left)
        MetricRow("hip_rom_right", metrics.hip_rom_right)
        MetricRow("knee_rom_left", metrics.knee_rom_left)
        MetricRow("knee_rom_right", metrics.knee_rom_right)
        MetricRow("cadence_est", metrics.cadence_est)
        MetricRow("symmetry_index", metrics.symmetry_index)
        MetricRow("pelvis_pitch_rom", metrics.pelvis_pitch_rom)
        MetricRow("pelvis_roll_rom", metrics.pelvis_roll_rom)
        MetricRow("created_at", metrics.created_at)
        MetricRow("commented_at", metrics.commented_at)
    }
}

@Composable
private fun MetricRow(label: String, value: Any?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value?.toString()?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}
