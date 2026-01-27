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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.margo.app_iot.data.SessionStore
import com.margo.app_iot.network.ApiClient
import com.margo.app_iot.network.AuthRepository
import com.margo.app_iot.network.toUserMessage
import kotlinx.coroutines.launch

@Composable
fun DoctorHome(
    api: ApiClient,
    auth: AuthRepository,
    session: SessionStore,
    onLogout: () -> Unit
) {
    val doctorId by session.usernameFlow.collectAsState(initial = "")

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
                    auth = auth,
                    doctorId = doctorId,
                    onSelectPatient = { selectedPatient = it }
                )
            } else {
                DoctorPatientDetails(
                    api = api,
                    auth = auth,
                    doctorId = doctorId,
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
    auth: AuthRepository,
    doctorId: String,
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
        if (doctorId.isBlank()) return
        loading = true
        error = null
        scope.launch {
            val res = auth.call { token ->
                api.getPatientsByDoctorId(doctorId = doctorId, accessToken = token)
            }
            loading = false
            if (res.isSuccess) {
                patients = res.getOrNull()?.patients ?: emptyList()
            } else {
                error = res.exceptionOrNull()?.toUserMessage() ?: "Failed to load patients"
            }
        }
    }

    LaunchedEffect(doctorId) {
        if (doctorId.isNotBlank()) refresh()
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
                    Card(modifier = Modifier.fillMaxWidth()) {
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
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

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
                            val res = auth.call { token ->
                                api.addPatientToDoctor(
                                    doctorId = doctorId,
                                    patientId = patientId,
                                    accessToken = token
                                )
                            }
                            if (res.isSuccess) {
                                showAddDialog = false
                                newPatientId = ""
                                refresh()
                            } else {
                                error = res.exceptionOrNull()?.toUserMessage() ?: "Failed to add patient"
                            }
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

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
                            val res = auth.call { token ->
                                api.deletePatientFromDoctor(
                                    doctorId = doctorId,
                                    patientId = toDelete,
                                    accessToken = token
                                )
                            }
                            deleting = false
                            if (res.isSuccess) {
                                confirmDeletePatient = null
                                refresh()
                            } else {
                                deleteError = res.exceptionOrNull()?.toUserMessage() ?: "Failed to delete patient"
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
    auth: AuthRepository,
    doctorId: String,
    patientId: String,
    onBack: () -> Unit
) {
    com.margo.app_iot.ui.shared.ExperimentsScreen(
        api = api,
        auth = auth,
        ownerUserId = patientId,
        doctorIdLabel = doctorId,
        editableComment = true,
        onSaveComment = { expId, newComment ->
            auth.call { token ->
                api.setExperimentComment(
                    experimentId = expId,
                    comment = newComment,
                    accessToken = token
                ).map { Unit }
            }
        },
        title = "Experiments: $patientId",
        onBack = onBack
    )
}
