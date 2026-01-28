@file:OptIn(ExperimentalMaterial3Api::class)

package com.margo.app_iot.ui.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.margo.app_iot.network.ApiClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs


/**
 * ✅ Пациент: read-only, показываем референсы.
 */
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
            val header = if (doctorId.isNullOrBlank()) "You have no doctor yet" else "Comment from doctor $doctorId"
            Text(
                text = header,
                style = MaterialTheme.typography.titleSmall
            )
            if (onRefresh != null) {
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }

        val commentText = comment?.takeIf { it.isNotBlank() } ?: "There's no comment yet"
        AssistChip(onClick = {}, label = { Text(commentText) })

        Spacer(Modifier.height(2.dp))
        Text("Metrics", style = MaterialTheme.typography.titleSmall)

        // ✅ пациенту показываем refs
        MetricsTable(metrics = metrics, showReferences = true)
    }
}

/**
 * ✅ Врач: может редактировать комментарий, референсы НЕ показываем.
 */
@Composable
fun ExperimentDetailsEditorCard(
    doctorId: String?,
    initialComment: String?,
    metrics: ApiClient.ExperimentMetrics?,
    onSave: (String) -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    var draft by rememberSaveable(initialComment) { mutableStateOf(initialComment.orEmpty()) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

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
                Button(onClick = {
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    onSave(draft)
                }) { Text("Save") }
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

        // ✅ врачу refs не показываем
        MetricsTable(metrics = metrics, showReferences = false)
    }
}

/**
 * showReferences = true  -> показываем Ref диапазон и статус (OK/LOW/HIGH)
 * showReferences = false -> показываем только значения
 */
@Composable
fun MetricsTable(
    metrics: ApiClient.ExperimentMetrics?,
    showReferences: Boolean
) {
    if (metrics == null) {
        Text("—")
        return
    }

    // Референсы (для gait-метрик). Можешь поправить диапазоны под свою методику.
    val refs = remember {
        mapOf(
            "hip_rom_left" to RefRange(42.0, 53.0, "°"),
            "hip_rom_right" to RefRange(42.0, 53.0, "°"),
            "knee_rom_left" to RefRange(56.0, 61.0, "°"),
            "knee_rom_right" to RefRange(56.0, 61.0, "°"),
            "cadence_est" to RefRange(90.0, 120.0, " spm"),
            // если у тебя symmetry_index — % асимметрии (0 = идеально)
            "symmetry_index" to RefRange(0.0, 10.0, " %"),
            "pelvis_pitch_rom" to RefRange(1.0, 2.0, "°"),
            "pelvis_roll_rom" to RefRange(6.0, 11.0, "°"),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricRow("hip_rom_left", metrics.hip_rom_left, ref = refs["hip_rom_left"].takeIf { showReferences })
        MetricRow("hip_rom_right", metrics.hip_rom_right, ref = refs["hip_rom_right"].takeIf { showReferences })
        MetricRow("knee_rom_left", metrics.knee_rom_left, ref = refs["knee_rom_left"].takeIf { showReferences })
        MetricRow("knee_rom_right", metrics.knee_rom_right, ref = refs["knee_rom_right"].takeIf { showReferences })
        MetricRow("cadence_est", metrics.cadence_est, ref = refs["cadence_est"].takeIf { showReferences })
        MetricRow("symmetry_index", metrics.symmetry_index, ref = refs["symmetry_index"].takeIf { showReferences })
        MetricRow("pelvis_pitch_rom", metrics.pelvis_pitch_rom, ref = refs["pelvis_pitch_rom"].takeIf { showReferences })
        MetricRow("pelvis_roll_rom", metrics.pelvis_roll_rom, ref = refs["pelvis_roll_rom"].takeIf { showReferences })

        // даты — без refs
        MetricRow("created_at", formatIsoTs(metrics.created_at) ?: "—", ref = null)
        MetricRow("commented_at", formatIsoTs(metrics.commented_at) ?: "No comment yet", ref = null)
    }
}

private data class RefRange(
    val min: Double? = null,
    val max: Double? = null,
    val unit: String = ""
) {
    fun text(): String {
        val a = min
        val b = max
        return when {
            a != null && b != null -> "Ref: ${fmt(a)}–${fmt(b)}$unit"
            a != null -> "Ref: ≥${fmt(a)}$unit"
            b != null -> "Ref: ≤${fmt(b)}$unit"
            else -> ""
        }
    }

    private fun fmt(x: Double): String {
        val r = x.toInt().toDouble()
        return if (abs(x - r) < 1e-9) x.toInt().toString() else String.format("%.1f", x)
    }
}

private enum class MetricFlag { OK, LOW, HIGH, NA }

private fun flag(value: Double?, ref: RefRange?): MetricFlag {
    if (value == null || ref == null) return MetricFlag.NA
    ref.min?.let { if (value < it) return MetricFlag.LOW }
    ref.max?.let { if (value > it) return MetricFlag.HIGH }
    return MetricFlag.OK
}

private val DISPLAY_TS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())

private fun formatIsoTs(iso: String?): String? {
    if (iso.isNullOrBlank()) return null

    val instant = runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: return iso // если вдруг прилетело что-то нестандартное — покажем как есть

    return DISPLAY_TS.format(instant)
}


@Composable
private fun MetricRow(
    label: String,
    value: Any?,
    ref: RefRange?
) {
    val number = (value as? Number)?.toDouble()
    val f = flag(number, ref)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)

        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            val valueText = when (value) {
                null -> "—"
                is String -> value.takeIf { it.isNotBlank() } ?: "—"
                is Number -> {
                    val d = value.toDouble()
                    val r = d.toInt().toDouble()
                    if (abs(d - r) < 1e-9) d.toInt().toString() else String.format("%.2f", d)
                }
                else -> value.toString()
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(valueText, style = MaterialTheme.typography.bodyMedium)

                if (ref != null && f != MetricFlag.NA) {
                    val chipLabel = when (f) {
                        MetricFlag.OK -> "OK"
                        MetricFlag.LOW -> "LOW"
                        MetricFlag.HIGH -> "HIGH"
                        MetricFlag.NA -> ""
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text(chipLabel) }
                    )
                }
            }

            val refText = ref?.text().orEmpty()
            if (refText.isNotBlank()) {
                Text(
                    refText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
