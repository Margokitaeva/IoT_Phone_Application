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

    data class AuthedUser(val userId: String, val role: String)

    data class LoginResponse(
        val accessToken: String,
        val refreshToken: String,
        val user: AuthedUser
    )

    data class RefreshResponse(
        val accessToken: String,
        val refreshToken: String
    )

    data class ExperimentInfo(
        val experimentID: String,

        val hipRomLeft: Double?,
        val hipRomRight: Double?,
        val kneeRomLeft: Double?,
        val kneeRomRight: Double?,

        val cadenceEst: Double?,
        val symmetryIndex: Double?,

        val pelvisPitchRom: Double?,
        val pelvisRollRom: Double?
    )

    data class PatResult(
        val patientId: String?,
        val updatedAt: String?,

        val hipRomLeft: Double?,
        val hipRomRight: Double?,
        val kneeRomLeft: Double?,
        val kneeRomRight: Double?,

        val cadenceEst: Double?,
        val symmetryIndex: Double?,

        val pelvisPitchRom: Double?,
        val pelvisRollRom: Double?
    )

    data class UserDevicePair(
        val userId: String,
        val deviceId: String,
        val pairedAt: String?
    )


    class ApiHttpException(val code: Int, val body: String) : RuntimeException("HTTP $code $body")


    suspend fun login(userId: String, password: String): Result<LoginResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("userId", userId)
                    .put("password", password)
                    .toString()

                val req = Request.Builder()
                    .url("$baseUrl/api/v1/auth/login")
                    .post(body.toRequestBody(jsonType))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} $text")

                    val json = JSONObject(text)
                    val userJson = json.getJSONObject("user")

                    LoginResponse(
                        accessToken = json.getString("accessToken"),
                        refreshToken = json.getString("refreshToken"),
                        user = AuthedUser(
                            userId = userJson.getString("userId"),
                            role = userJson.getString("role")
                        )
                    )
                }
            }
        }


    data class RegisterResponse(val userId: String, val role: String)

    suspend fun register(userId: String, password: String, role: String): Result<RegisterResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("userId", userId)
                    .put("password", password)
                    .put("role", role)
                    .toString()

                val req = Request.Builder()
                    .url("$baseUrl/api/v1/auth/register")
                    .post(body.toRequestBody(jsonType))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} $text")

                    // 201
                    val json = JSONObject(text)
                    RegisterResponse(
                        userId = json.getString("userId"),
                        role = json.getString("role")
                    )
                }
            }
        }

    suspend fun refresh(refreshToken: String): Result<RefreshResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("refreshToken", refreshToken)
                    .toString()

                val req = Request.Builder()
                    .url("$baseUrl/api/v1/auth/refresh")
                    .post(body.toRequestBody(jsonType))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} $text")

                    val json = JSONObject(text)
                    RefreshResponse(
                        accessToken = json.getString("accessToken"),
                        refreshToken = json.getString("refreshToken")
                    )
                }
            }
        }

    suspend fun logout(accessToken: String, refreshToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("refreshToken", refreshToken)
                    .toString()

                val req = Request.Builder()
                    .url("$baseUrl/api/v1/auth/logout")
                    .post(body.toRequestBody(jsonType))
                    .apply {
                        if (accessToken.isNotBlank()) header("Authorization", "Bearer $accessToken")
                    }
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (resp.code == 204) return@use
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} $text")
                }
            }.map { Unit }
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

    suspend fun patientGetExperimentsInfo(username: String): Result<List<ExperimentInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/patient/getExperimentsInfo/$username")
                    .get()
                    .build()

                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} $body")

                    val json = JSONObject(body)
                    val arr = json.optJSONArray("experiments") ?: JSONArray()

                    buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)

                            add(
                                ExperimentInfo(
                                    experimentID = o.optString("experimentID"),

                                    hipRomLeft = o.optDoubleOrNull("hip_rom_left"),
                                    hipRomRight = o.optDoubleOrNull("hip_rom_right"),
                                    kneeRomLeft = o.optDoubleOrNull("knee_rom_left"),
                                    kneeRomRight = o.optDoubleOrNull("knee_rom_right"),

                                    cadenceEst = o.optDoubleOrNull("cadence_est"),
                                    symmetryIndex = o.optDoubleOrNull("symmetry_index"),

                                    pelvisPitchRom = o.optDoubleOrNull("pelvis_pitch_rom"),
                                    pelvisRollRom = o.optDoubleOrNull("pelvis_roll_rom")
                                )
                            )
                        }
                    }
                }
            }
        }

    suspend fun patientGetPatResult(username: String, experimentId: String): Result<PatResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/getPatResult/$username/$experimentId")
                    .get()
                    .build()

                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} $body")

                    val json = JSONObject(body)

                    PatResult(
                        patientId = json.optString("patientId").ifBlank { null },
                        updatedAt = json.optString("updatedAt").ifBlank { null },

                        hipRomLeft = json.optDoubleOrNull("hipRomLeft"),
                        hipRomRight = json.optDoubleOrNull("hipRomRight"),
                        kneeRomLeft = json.optDoubleOrNull("kneeRomLeft"),
                        kneeRomRight = json.optDoubleOrNull("kneeRomRight"),

                        cadenceEst = json.optDoubleOrNull("cadenceEst"),
                        symmetryIndex = json.optDoubleOrNull("symmetryIndex"),

                        pelvisPitchRom = json.optDoubleOrNull("pelvisPitchRom"),
                        pelvisRollRom = json.optDoubleOrNull("pelvisRollRom")
                    )
                }
            }
        }


    // helper: чтобы нормально обрабатывать null/отсутствие поля
    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        return if (has(key) && !isNull(key)) optDouble(key) else null
    }


    // -------- PAIRING --------
    suspend fun getDeviceByUserId(userId: String, accessToken: String): Result<UserDevicePair?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/users/$userId/device")
                    .get()
                    .apply {
                        if (accessToken.isNotBlank()) {
                            header("Authorization", "Bearer $accessToken")
                        }
                    }
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()

                    // 404 = нормальная ветка "Device not paired"
                    if (resp.code == 404) return@use null

                    // 403 и прочие — как ошибка
                    if (!resp.isSuccessful) throw ApiHttpException(resp.code, text)

                    val json = JSONObject(text)
                    UserDevicePair(
                        userId = json.getString("userId"),
                        deviceId = json.getString("deviceId"),
                        pairedAt = json.optString("pairedAt").ifBlank { null }
                    )
                }
            }
        }


    suspend fun putDeviceByUserId(userId: String, deviceId: String, accessToken: String): Result<UserDevicePair> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("deviceId", deviceId)
                    .toString()

                val req = Request.Builder()
                    .url("$baseUrl/api/v1/users/$userId/device")
                    .put(body.toRequestBody(jsonType))
                    .apply {
                        header("Content-Type", "application/json")
                        if (accessToken.isNotBlank()) {
                            header("Authorization", "Bearer $accessToken")
                        }
                    }
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiHttpException(resp.code, text)

                    val json = JSONObject(text)
                    UserDevicePair(
                        userId = json.getString("userId"),
                        deviceId = json.getString("deviceId"),
                        pairedAt = json.optString("pairedAt").ifBlank { null }
                    )
                }
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
