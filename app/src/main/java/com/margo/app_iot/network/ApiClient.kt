package com.margo.app_iot.network

import com.margo.app_iot.ExperimentDataResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiClient(
    private val baseUrl: String
) {
    // TODO: если нужно — добавь логирование/интерсептор
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // -------- AUTH --------

    data class LoginResponse(val username: String, val role: String)

    suspend fun login(username: String, password: String): Result<LoginResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("Username", username)
                    .put("Password", password)
                    .toString()

                val req = Request.Builder()
                    .url("$baseUrl/auth/login")
                    .post(body.toRequestBody(jsonType))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} $text")

                    val json = JSONObject(text)
                    LoginResponse(
                        username = json.optString("Username", username),
                        role = json.getString("Role")
                    )
                }
            }
        }

    suspend fun register(username: String, password: String, role: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("Username", username)
                .put("Password", password)
                .put("Role", role) // patient/doctor
                .toString()

            postNoBody("$baseUrl/auth/register", body, okCodes = setOf(200), err400 = "Bad request")
        }

    // -------- PATIENT --------

    suspend fun patientAddDevice(username: String, deviceId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("Username", username)
                    .put("DeviceID", deviceId)
                    .toString()

                val req = Request.Builder()
                    .url("$baseUrl/patient/addDevice")
                    .post(body.toRequestBody(jsonType))
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return@use

                    if (resp.code == 400) {
                        throw IllegalStateException("DEVICE_ALREADY_EXISTS")
                    }

                    throw IllegalStateException("HTTP ${resp.code} ${resp.body?.string().orEmpty()}")
                }
            }.map { Unit }
        }

    suspend fun patientGetDeviceId(username: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/getDevice/$username")
                    .get()
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} $text")

                    val json = JSONObject(text)
                    json.getString("deviceID")
                }
            }
        }

    suspend fun patientDeleteDevice(patientName: String, deviceId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("DeviceID", deviceId)
                    .put("patientName", patientName)
                    .toString()

                val req = Request.Builder()
                    .url("$baseUrl/patient/deleteDevice")
                    .delete(body.toRequestBody(jsonType))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} $text")

                    // сервер: { "data": "..." }
                    val json = JSONObject(text)
                    json.optString("data", text)
                }
            }
        }


//    suspend fun patientAddDevice(username: String, deviceId: String): Result<Unit> =
//        withContext(Dispatchers.IO) {
//            val body = JSONObject()
//                .put("Username", username)
//                .put("DeviceID", deviceId)
//                .toString()
//
//            postNoBody("$baseUrl/patient/addDevice", body, okCodes = setOf(200), err400 = "Bad request")
//        }

    suspend fun patientGetComment(username: String): Result<String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/patient/getComment/$username")
                .get()
                .build()

            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val text = resp.body?.string().orEmpty()
                    val json = JSONObject(text)
                    json.optString("comment", "")
                }
            }
        }

    suspend fun patientGetData(username: String): Result<PatientDataResponse> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/patient/getData/$username")
                .get()
                .build()

            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val text = resp.body?.string().orEmpty()
                    val json = JSONObject(text)

                    val ts = json.optLong("timestamp", 0L)
                    val arr = json.optJSONArray("mpuProcessedData") ?: JSONArray()

                    // mpuProcessedData: [ [4 floats], [4 floats], ... ]
                    val batches = mutableListOf<List<Float>>()
                    for (i in 0 until arr.length()) {
                        val inner = arr.getJSONArray(i)
                        val one = mutableListOf<Float>()
                        for (j in 0 until inner.length()) {
                            one.add(inner.getDouble(j).toFloat())
                        }
                        batches.add(one)
                    }

                    PatientDataResponse(timestamp = ts, mpuProcessedData = batches)
                }
            }
        }

    suspend fun patientGetExperiments(username: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/patient/getExperiments/$username")
                    .get()
                    .build()

                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) error("HTTP ${resp.code}")

                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val arr = json.optJSONArray("experiments") ?: JSONArray()

                buildList {
                    for (i in 0 until arr.length()) add(arr.getString(i))
                }
            }
        }

    suspend fun patientGetExperimentData(username: String, experimentId: String): Result<ExperimentDataResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/patient/getExperimentData/$username/$experimentId")
                    .get()
                    .build()

                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) error("HTTP ${resp.code}")

                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)

                val deviceId = json.optString("deviceID")
                val timestamp = json.optLong("timestamp", 0L)

                val mpuArr = json.optJSONArray("mpuProcessedData") ?: JSONArray()
                val mpuProcessedData = buildList {
                    for (i in 0 until mpuArr.length()) {
                        val row = mpuArr.getJSONArray(i)
                        add(
                            listOf(
                                row.getDouble(0).toFloat(),
                                row.getDouble(1).toFloat(),
                                row.getDouble(2).toFloat(),
                                row.getDouble(3).toFloat()
                            )
                        )
                    }
                }

                ExperimentDataResponse(
                    experimentId = experimentId,
                    deviceID = deviceId,
                    timestamp = timestamp,
                    mpuProcessedData = mpuProcessedData
                )
            }
        }




    // -------- DOCTOR --------

    suspend fun doctorGetPatients(username: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/doctor/getPatients/$username")
                .get()
                .build()

            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val text = resp.body?.string().orEmpty()
                    val json = JSONObject(text)
                    val arr = json.optJSONArray("patients") ?: JSONArray()
                    (0 until arr.length()).map { arr.getString(it) }
                }
            }
        }

    suspend fun doctorAddPatient(doctorName: String, patientName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("doctorName", doctorName)
                .put("patientName", patientName)
                .toString()

            postNoBody("$baseUrl/doctor/addPatient", body, okCodes = setOf(200), err400 = "Bad request")
        }

    suspend fun doctorSetComment(patientName: String, comment: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("patientName", patientName)
                .put("comment", comment)
                .toString()

            postNoBody("$baseUrl/doctor/setComment", body, okCodes = setOf(200), err400 = "Bad request")
        }

    // -------- helper --------

    private fun postNoBody(
        url: String,
        jsonBody: String,
        okCodes: Set<Int>,
        err400: String? = null,
        err401: String? = null
    ): Result<Unit> = runCatching {
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonType))
            .build()

        http.newCall(req).execute().use { resp ->
            if (resp.code in okCodes) return@use
            if (resp.code == 400 && err400 != null) error(err400)
            if (resp.code == 401 && err401 != null) error(err401)
            val body = resp.body?.string().orEmpty()
            error("HTTP ${resp.code} $body")
        }
    }
}
