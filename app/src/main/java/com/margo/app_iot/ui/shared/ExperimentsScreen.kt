@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.margo.app_iot.ui.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.margo.app_iot.network.ApiClient
import kotlinx.coroutines.launch

/**
 * Общий экран “эксперименты пользователя”:
 * - Загружает список experimentIds по userId
 * - По клику раскрывает карточку и грузит info по experimentId
 * - В режиме doctor: дает редактировать comment и сохранять через onSaveComment
 *
 * Реальные сетевые вызовы:
 * - api.getExperimentsByUserId(userId, accessToken)
 * - api.getExperimentInfo(experimentId, accessToken)
 *
 * Сохранение comment специально через callback, чтобы:
 * - у врача дергать PATCH /experiments/{id}/comment
 * - у пациента вообще не показывать Save
 */
@Composable
fun ExperimentsScreen(
    api: ApiClient,
    accessToken: String,
    ownerUserId: String,
    doctorIdLabel: String? = null, // подпись “doctor X” (может быть null)
    editableComment: Boolean,
    onSaveComment: (suspend (experimentId: String, comment: String) -> Result<Unit>)? = null,
    title: String = "Experiments",
    onBack: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()

    var experimentIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingList by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }

    var expandedExpId by rememberSaveable { mutableStateOf<String?>(null) }

    val infoCache = remember { mutableStateMapOf<String, ApiClient.ExperimentInfoResponse>() }
    var loadingInfoId by remember { mutableStateOf<String?>(null) }
    var infoError by remember { mutableStateOf<String?>(null) }

    var saveError by remember { mutableStateOf<String?>(null) }
    var savingExpId by remember { mutableStateOf<String?>(null) }

    fun refreshExperiments() {
        if (ownerUserId.isBlank() || accessToken.isBlank()) return
        loadingList = true
        listError = null
        scope.launch {
            val res = api.getExperimentsByUserId(userId = ownerUserId, accessToken = accessToken)
            loadingList = false
            if (res.isSuccess) {
                experimentIds = res.getOrNull()?.experimentIds ?: emptyList()
            } else {
                listError = res.exceptionOrNull()?.message ?: "Failed to load experiments"
            }
        }
    }

    fun loadInfo(expId: String, force: Boolean = false) {
        if (accessToken.isBlank()) return
        if (!force && infoCache.containsKey(expId)) return

        loadingInfoId = expId
        infoError = null
        scope.launch {
            val res = api.getExperimentInfo(experimentId = expId, accessToken = accessToken)
            loadingInfoId = null

            if (res.isSuccess) {
                val info = res.getOrNull()
                if (info != null) infoCache[expId] = info
            } else {
                infoError = res.exceptionOrNull()?.message ?: "Failed to load experiment info"
            }
        }
    }

    fun toggle(expId: String) {
        expandedExpId = if (expandedExpId == expId) null else expId
        if (expandedExpId == expId) {
            loadInfo(expId)
        } else {
            infoError = null
            saveError = null
        }
    }

    suspend fun saveComment(expId: String, comment: String) {
        val saver = onSaveComment ?: return
        savingExpId = expId
        saveError = null

        val res = saver(expId, comment)

        savingExpId = null
        if (res.isSuccess) {
            // после успешного save — перезагрузим info, чтобы подтянуть updated comment/commented_at
            loadInfo(expId, force = true)
        } else {
            saveError = res.exceptionOrNull()?.message ?: "Failed to save comment"
        }
    }

    LaunchedEffect(ownerUserId, accessToken) {
        if (ownerUserId.isNotBlank() && accessToken.isNotBlank()) {
            refreshExperiments()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        // header
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text("Back") }
                }
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { refreshExperiments() }, enabled = !loadingList) {
                    if (loadingList) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Refresh list")
                }

                OutlinedButton(
                    onClick = {
                        val expId = expandedExpId
                        if (expId != null) loadInfo(expId, force = true)
                    },
                    enabled = expandedExpId != null && loadingInfoId == null
                ) {
                    Text("Refresh info")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (accessToken.isBlank()) {
            Text("No access token. Please login again.", color = MaterialTheme.colorScheme.error)
            return@Column
        }

        if (listError != null) {
            Text(listError!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(10.dp))
        }

        if (experimentIds.isEmpty() && !loadingList) {
            Text("No experiments yet.")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(experimentIds) { expId ->
                val expanded = expandedExpId == expId
                val info = infoCache[expId]
                val isLoadingThis = loadingInfoId == expId
                val isSavingThis = savingExpId == expId

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { toggle(expId) }
                ) {
                    Column(Modifier.padding(12.dp)) {

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(expId, style = MaterialTheme.typography.titleMedium)

                            // TODO: show experiment start time here (right top / bottom) when backend provides it
                        }

                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            Divider()
                            Spacer(Modifier.height(10.dp))

                            when {
                                isLoadingThis && info == null -> {
                                    Row {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text("Loading info…")
                                    }
                                }

                                infoError != null && info == null -> {
                                    Text(infoError!!, color = MaterialTheme.colorScheme.error)
                                }

                                info != null -> {
                                    if (!editableComment) {
                                        ExperimentDetailsReadOnlyCard(
                                            doctorId = doctorIdLabel,
                                            comment = info.comment,
                                            metrics = info.metrics,
                                            onRefresh = { loadInfo(expId, force = true) }
                                        )
                                    } else {
                                        ExperimentDetailsEditorCard(
                                            doctorId = doctorIdLabel,
                                            initialComment = info.comment,
                                            metrics = info.metrics,
                                            onSave = { newText ->
                                                if (onSaveComment != null && !isSavingThis) {
                                                    scope.launch { saveComment(expId, newText) }
                                                }
                                            },
                                            onRefresh = { loadInfo(expId, force = true) }
                                        )

                                        if (isSavingThis) {
                                            Spacer(Modifier.height(8.dp))
                                            Row {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Text("Saving…")
                                            }
                                        }
                                    }

                                    if (infoError != null) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(infoError!!, color = MaterialTheme.colorScheme.error)
                                    }
                                    if (saveError != null) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(saveError!!, color = MaterialTheme.colorScheme.error)
                                    }
                                }

                                else -> Text("No info loaded yet.")
                            }
                        }
                    }
                }
            }
        }
    }
}
