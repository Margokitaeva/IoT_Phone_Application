@file:OptIn(ExperimentalMaterial3Api::class)

package com.margo.app_iot.ui.doctor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.margo.app_iot.data.SessionStore
import com.margo.app_iot.network.ApiClient
import com.margo.app_iot.ui.shared.ExperimentDetailsEditorCard
import kotlinx.coroutines.launch

@Composable
fun DoctorHome(
    api: ApiClient,
    session: SessionStore,
    onLogout: () -> Unit
) {
    val doctorId by session.usernameFlow.collectAsState(initial = "")
    val accessToken by session.accessTokenFlow.collectAsState(initial = "")

    var selectedPatient by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Doctor") },
                actions = { TextButton(onClick = onLogout) { Text("Logout") } }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (selectedPatient == null) {
                DoctorPatientsList(
                    api = api,
                    doctorId = doctorId,
                    accessToken = accessToken,
                    onSelectPatient = { selectedPatient = it }
                )
            } else {
                DoctorPatientDetails(
                    api = api,
                    doctorId = doctorId,
                    accessToken = accessToken,
                    patientId = selectedPatient!!,
                    onBack = { selectedPatient = null }
                )
            }
        }
    }
}

@Composable
private fun DoctorPatientsList(
    api: ApiClient,
    doctorId: String,
    accessToken: String,
    onSelectPatient: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    var patients by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var newPatientId by remember { mutableStateOf("") }

    var confirmDeletePatient by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }

    fun refresh() {
        if (doctorId.isBlank() || accessToken.isBlank()) return
        loading = true
        error = null
        scope.launch {
            val res = api.getPatientsByDoctorId(doctorId = doctorId, accessToken = accessToken)
            loading = false
            if (res.isSuccess) {
                patients = res.getOrNull()?.patients ?: emptyList()
            } else {
                error = res.exceptionOrNull()?.message ?: "Failed to load patients"
            }
        }
    }

    LaunchedEffect(doctorId, accessToken) {
        if (doctorId.isNotBlank() && accessToken.isNotBlank()) refresh()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add patient")
            }
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (accessToken.isBlank()) {
                Text("No access token. Please login again.", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Patients", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { refresh() }) { Text(if (loading) "..." else "Refresh") }
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }

            if (deleteError != null) {
                Spacer(Modifier.height(10.dp))
                Text(deleteError!!, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(10.dp))

            if (patients.isEmpty() && !loading) {
                Text("No patients yet.")
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(patients) { p ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSelectPatient(p) }
                            ) {
                                Text(p, style = MaterialTheme.typography.titleMedium)
                                Text("Tap to open experiments", style = MaterialTheme.typography.bodySmall)
                            }

                            TextButton(
                                onClick = { confirmDeletePatient = p },
                                enabled = !deleting
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    // Add patient dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add patient") },
            text = {
                OutlinedTextField(
                    value = newPatientId,
                    onValueChange = { newPatientId = it },
                    label = { Text("PatientId") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val patientId = newPatientId.trim()
                        if (patientId.isBlank()) {
                            error = "PatientId is empty"
                            return@TextButton
                        }
                        scope.launch {
                            error = null
                            val res = api.addPatientToDoctor(
                                doctorId = doctorId,
                                patientId = patientId,
                                accessToken = accessToken
                            )
                            if (res.isSuccess) {
                                showAddDialog = false
                                newPatientId = ""
                                refresh()
                            } else {
                                error = res.exceptionOrNull()?.message ?: "Failed to add patient"
                            }
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Confirm delete dialog
    val toDelete = confirmDeletePatient
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDeletePatient = null },
            title = { Text("Remove patient") },
            text = { Text("Are you sure you want to remove patient \"$toDelete\" from your list?") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        deleteError = null
                        scope.launch {
                            val res = api.deletePatientFromDoctor(
                                doctorId = doctorId,
                                patientId = toDelete,
                                accessToken = accessToken
                            )
                            deleting = false
                            if (res.isSuccess) {
                                confirmDeletePatient = null
                                refresh()
                            } else {
                                deleteError = res.exceptionOrNull()?.message ?: "Failed to delete patient"
                            }
                        }
                    }
                ) { Text(if (deleting) "Deleting..." else "Delete") }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { confirmDeletePatient = null }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DoctorPatientDetails(
    api: ApiClient,
    doctorId: String,
    accessToken: String,
    patientId: String,
    onBack: () -> Unit
) {
    com.margo.app_iot.ui.shared.ExperimentsScreen(
        api = api,
        accessToken = accessToken,
        ownerUserId = patientId,
        doctorIdLabel = doctorId,
        editableComment = true,
        onSaveComment = { expId, newComment ->
            // ApiClient.setExperimentComment возвращает Result<SetCommentResponse>,
            // а ExperimentsScreen ожидает Result<Unit> -> мапаем.
            api.setExperimentComment(
                experimentId = expId,
                comment = newComment,
                accessToken = accessToken
            ).map { Unit }
        },
        title = "Experiments: $patientId",
        onBack = onBack
    )
}
