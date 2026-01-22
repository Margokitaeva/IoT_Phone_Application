@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.margo.app_iot.ui.doctor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.margo.app_iot.data.SessionStore
import com.margo.app_iot.network.ApiClient
import kotlinx.coroutines.launch

@Composable
fun DoctorHome(
    api: ApiClient,
    session: SessionStore,
    onLogout: () -> Unit
) {
    val username by session.usernameFlow.collectAsState(initial = "")
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
                    doctorName = username,
                    onSelectPatient = { selectedPatient = it }
                )
            } else {
                DoctorPatientDetails(
                    api = api,
                    patientName = selectedPatient!!,
                    onBack = { selectedPatient = null }
                )
            }
        }
    }
}

@Composable
private fun DoctorPatientsList(
    api: ApiClient,
    doctorName: String,
    onSelectPatient: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var patients by remember { mutableStateOf(listOf<String>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var newPatient by remember { mutableStateOf("") }

    fun refresh() {
        if (doctorName.isBlank()) return
        loading = true
        error = null
        scope.launch {
            val res = api.doctorGetPatients(doctorName)
            loading = false
            if (res.isSuccess) patients = res.getOrNull().orEmpty()
            else error = res.exceptionOrNull()?.message ?: "Failed"
        }
    }


    LaunchedEffect(doctorName) {
        if (doctorName.isBlank()) {
            return@LaunchedEffect
        }
        refresh()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add patient")
            }
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().padding(16.dp)) {

            if (doctorName.isBlank()) {
                Text("Loading...")
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

            Spacer(Modifier.height(10.dp))

            LazyColumn(Modifier.fillMaxSize()) {
                items(patients) { p ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { onSelectPatient(p) }
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(p, style = MaterialTheme.typography.titleMedium)
                            Text("Tap to open", style = MaterialTheme.typography.bodySmall)
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
                    value = newPatient,
                    onValueChange = { newPatient = it },
                    label = { Text("Patient username") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // TODO: можно показать прогресс, обработать ошибки красивее
                            val doctor = doctorName.trim()
                            val patient = newPatient.trim()
                            if (doctor.isBlank() || patient.isBlank()) {
                                error = "Doctor or patient name is empty"
                                return@launch
                            }
                            val res = api.doctorAddPatient(doctorName = doctorName, patientName = newPatient.trim())
                            if (res.isSuccess) {
                                showAddDialog = false
                                newPatient = ""
                                refresh()
                            } else {
                                // оставим диалог открытым, ошибка отобразится ниже
                                error = res.exceptionOrNull()?.message ?: "Failed to add"
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
}

@Composable
private fun DoctorPatientDetails(
    api: ApiClient,
    patientName: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var comment by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editMode by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    fun load() {
        loading = true
        error = null
        scope.launch {
            // используем patient/getComment — отдельного doctor/getComment нет
            val res = api.patientGetComment(patientName)
            loading = false
            if (res.isSuccess) {
                comment = res.getOrNull().orEmpty()
                draft = comment
            } else {
                error = res.exceptionOrNull()?.message ?: "Failed"
            }
        }
    }

    LaunchedEffect(patientName) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(patientName) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    if (!editMode) {
                        IconButton(onClick = { editMode = true; draft = comment }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                    } else {
                        IconButton(onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                val res = api.doctorSetComment(patientName = patientName, comment = draft)
                                loading = false
                                if (res.isSuccess) {
                                    editMode = false
                                    comment = draft
                                } else {
                                    error = res.exceptionOrNull()?.message ?: "Failed to save"
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Comment", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))

            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)

            Spacer(Modifier.height(10.dp))

            if (!editMode) {
                OutlinedTextField(
                    value = comment,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
                )
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Edit comment") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text("Tap Save (top-right) to apply changes.", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(18.dp))
            Text("Patient history (TODO)", style = MaterialTheme.typography.titleMedium)
            Text("Placeholder: we will add UI when you decide the format.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
