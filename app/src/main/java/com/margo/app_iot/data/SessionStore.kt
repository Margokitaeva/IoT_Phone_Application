package com.margo.app_iot.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore("session_store")

class SessionStore(private val context: Context) {

    private object Keys {
        val LOGGED_IN = booleanPreferencesKey("logged_in")
        val USERNAME = stringPreferencesKey("username")
        val ROLE = stringPreferencesKey("role") // patient/doctor
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val loggedInFlow: Flow<Boolean> = context.sessionDataStore.data.map { it[Keys.LOGGED_IN] ?: false }
    val usernameFlow: Flow<String> = context.sessionDataStore.data.map { it[Keys.USERNAME] ?: "" }
    val roleFlow: Flow<String> = context.sessionDataStore.data.map { it[Keys.ROLE] ?: "" }
    val deviceIdFlow: Flow<String> = context.sessionDataStore.data.map { it[Keys.DEVICE_ID] ?: "" }

    suspend fun setLoggedIn(username: String, role: String) {
        context.sessionDataStore.edit {
            it[Keys.LOGGED_IN] = true
            it[Keys.USERNAME] = username
            it[Keys.ROLE] = role
        }
    }

    suspend fun setDeviceId(deviceId: String) {
        context.sessionDataStore.edit { it[Keys.DEVICE_ID] = deviceId }
    }

    suspend fun clearDeviceId() {
        context.sessionDataStore.edit { it.remove(Keys.DEVICE_ID) }
    }

    suspend fun logout() {
        context.sessionDataStore.edit {
            it[Keys.LOGGED_IN] = false
            it.remove(Keys.USERNAME)
            it.remove(Keys.ROLE)
            // deviceId можно оставить или чистить — я очищу:
            it.remove(Keys.DEVICE_ID)
        }
    }
}
