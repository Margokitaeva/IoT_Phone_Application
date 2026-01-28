package com.margo.app_iot.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.margo.app_iot.data.SessionStore
import com.margo.app_iot.network.ApiClient
import kotlinx.coroutines.launch
import com.margo.app_iot.network.toUserMessage

@Composable
fun AuthRoot(
    api: ApiClient,
    session: SessionStore,
    onAuthed: () -> Unit
) {
    var mode by remember { mutableStateOf(AuthMode.Login) }
    if (mode == AuthMode.Login) {
        LoginScreen(api, session, onAuthed, onGoSignUp = { mode = AuthMode.SignUp })
    } else {
        SignUpScreen(api, session, onAuthed, onGoLogin = { mode = AuthMode.Login })
    }
}

private enum class AuthMode { Login, SignUp }

@Composable
private fun LoginScreen(
    api: ApiClient,
    session: SessionStore,
    onAuthed: () -> Unit,
    onGoSignUp: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
//    var role by remember { mutableStateOf("patient") } // patient/doctor
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
        Text("Login", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(14.dp))

//        RolePicker(role = role, onRoleChange = { role = it })

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            password, { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = {
                error = null
                loading = true
                scope.launch {
                    val u = username.trim()
                    val res = api.login(userId = u, password = password)
                    loading = false

                    if (res.isSuccess) {
                        val payload = res.getOrNull()!!
//                        session.setLoggedIn(payload.user.userId, payload.user.role)
//                        session.setTokens(payload.accessToken, payload.refreshToken)
                        session.setSession(payload.user.userId, payload.user.role, payload.accessToken, payload.refreshToken)
                        session.setDeviceId("") // deviceId через BLE handshake
                        onAuthed()
                    } else {
                        val e = res.exceptionOrNull()
                        error = e?.toUserMessage() ?: "Login failed."
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "Loading..." else "Log in")
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onGoSignUp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign up")
        }
    }
}

@Composable
private fun SignUpScreen(
    api: ApiClient,
    session: SessionStore,
    onAuthed: () -> Unit,
    onGoLogin: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("patient") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
        Text("Sign up", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(14.dp))

        RolePicker(role = role, onRoleChange = { role = it })

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            password, { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            password2, { password2 = it },
            label = { Text("Repeat password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = {
                error = null
                if (password != password2) {
                    error = "Passwords do not match"
                    return@Button
                }
                loading = true
                scope.launch {
                    // registration flow: register -> login -> open next
                    val reg = api.register(username.trim(), password, role)
                    if (reg.isFailure) {
                        loading = false
                        error = reg.exceptionOrNull()?.toUserMessage() ?: "Register failed."
                        return@launch
                    }

                    // login right after register
                    val log = api.login(userId = username.trim(), password = password)
                    loading = false
                    if (log.isSuccess) {
                        val payload = log.getOrNull()!!
                        session.setLoggedIn(payload.user.userId, payload.user.role)
                        session.setTokens(payload.accessToken, payload.refreshToken)
                        session.setDeviceId("")
                        onAuthed()
                    }
                    else {
                        error = log.exceptionOrNull()?.toUserMessage() ?: "Login failed."
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "Loading..." else "Create account")
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(onClick = onGoLogin, modifier = Modifier.fillMaxWidth()) {
            Text("Back to login")
        }
    }
}

@Composable
private fun RolePicker(role: String, onRoleChange: (String) -> Unit) {
    Column {
        Text("Role", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = role == "patient",
                onClick = { onRoleChange("patient") },
                label = { Text("Patient") }
            )
            FilterChip(
                selected = role == "doctor",
                onClick = { onRoleChange("doctor") },
                label = { Text("Doctor") }
            )
        }
    }
}
