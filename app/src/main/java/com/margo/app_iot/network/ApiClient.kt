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

    suspend fun deleteDeviceByUserId(userId: String, accessToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/users/$userId/device")
                    .delete()
                    .apply { header("Authorization", "Bearer $accessToken") }
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (resp.code == 204) return@use
                    val text = resp.body?.string().orEmpty()
                    throw ApiHttpException(resp.code, text)
                }
            }.map { Unit }
        }



    // -------- PATIENT --------

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

    data class UserExperimentsResponse(
        val userId: String,
        val experimentIds: List<String>
    )

    suspend fun getExperimentsByUserId(userId: String, accessToken: String): Result<UserExperimentsResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/users/$userId/experiments")
                    .get()
                    .apply { header("Authorization", "Bearer $accessToken") }
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiHttpException(resp.code, text)

                    val json = JSONObject(text)
                    val arr = json.optJSONArray("experimentIds") ?: JSONArray()
                    val ids = buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }

                    UserExperimentsResponse(
                        userId = json.getString("userId"),
                        experimentIds = ids
                    )
                }
            }
        }

    data class ExperimentMetrics(
        val hip_rom_left: Double?,
        val hip_rom_right: Double?,
        val knee_rom_left: Double?,
        val knee_rom_right: Double?,
        val cadence_est: Double?,
        val symmetry_index: Double?,
        val pelvis_pitch_rom: Double?,
        val pelvis_roll_rom: Double?,
        val created_at: String?,
        val commented_at: String?
    )

    data class ExperimentInfoResponse(
        val experimentId: String,
        val userId: String,
        val comment: String?,
        val metrics: ExperimentMetrics?
    )

    suspend fun getExperimentInfo(experimentId: String, accessToken: String): Result<ExperimentInfoResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/experiments/$experimentId/info")
                    .get()
                    .apply { header("Authorization", "Bearer $accessToken") }
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiHttpException(resp.code, text)

                    val json = JSONObject(text)
                    val metricsObj = json.optJSONObject("metrics")

                    val metrics = metricsObj?.let {
                        ExperimentMetrics(
                            hip_rom_left = it.optDoubleOrNull("hip_rom_left"),
                            hip_rom_right = it.optDoubleOrNull("hip_rom_right"),
                            knee_rom_left = it.optDoubleOrNull("knee_rom_left"),
                            knee_rom_right = it.optDoubleOrNull("knee_rom_right"),
                            cadence_est = it.optDoubleOrNull("cadence_est"),
                            symmetry_index = it.optDoubleOrNull("symmetry_index"),
                            pelvis_pitch_rom = it.optDoubleOrNull("pelvis_pitch_rom"),
                            pelvis_roll_rom = it.optDoubleOrNull("pelvis_roll_rom"),
                            created_at = it.optString("created_at").ifBlank { null },
                            commented_at = it.optString("commented_at").ifBlank { null }
                        )
                    }

                    ExperimentInfoResponse(
                        experimentId = json.getString("experimentId"),
                        userId = json.getString("userId"),
                        comment = json.optString("comment").ifBlank { null },
                        metrics = metrics
                    )
                }
            }
        }

    data class ExperimentDataItem(
        val ts_ms: Long,
        val mpuProcessedData: List<List<Float>>
    )

    data class ExperimentDataApiResponse(
        val experimentId: String,
        val items: List<ExperimentDataItem>
    )

    suspend fun getExperimentData(experimentId: String, accessToken: String): Result<ExperimentDataApiResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/experiments/$experimentId/data")
                    .get()
                    .apply { header("Authorization", "Bearer $accessToken") }
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiHttpException(resp.code, text)

                    val json = JSONObject(text)
                    val itemsArr = json.optJSONArray("items") ?: JSONArray()

                    val items = buildList {
                        for (i in 0 until itemsArr.length()) {
                            val it = itemsArr.getJSONObject(i)
                            val ts = it.optLong("ts_ms", 0L)

                            val mpuArr = it.optJSONArray("mpuProcessedData") ?: JSONArray()
                            val mpuProcessedData = buildList {
                                for (s in 0 until mpuArr.length()) {
                                    val row = mpuArr.getJSONArray(s)
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

                            add(ExperimentDataItem(ts_ms = ts, mpuProcessedData = mpuProcessedData))
                        }
                    }

                    ExperimentDataApiResponse(
                        experimentId = json.getString("experimentId"),
                        items = items
                    )
                }
            }
        }

    // helper: чтобы нормально обрабатывать null/отсутствие поля
    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        return if (has(key) && !isNull(key)) optDouble(key) else null
    }

    data class PatientDoctorResponse(val patientId: String, val doctorId: String)

    suspend fun getDoctorIdByPatientId(patientId: String, accessToken: String): Result<PatientDoctorResponse?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/patients/$patientId/doctor")
                    .get()
                    .apply { header("Authorization", "Bearer $accessToken") }
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.code == 404) return@use null
                    if (!resp.isSuccessful) throw ApiHttpException(resp.code, text)

                    val json = JSONObject(text)
                    PatientDoctorResponse(
                        patientId = json.getString("patientId"),
                        doctorId = json.getString("doctorId")
                    )
                }
            }
        }



    // -------- DOCTOR --------
    data class DoctorPatientsResponse(val doctorId: String, val patients: List<String>)

    suspend fun getPatientsByDoctorId(doctorId: String, accessToken: String): Result<DoctorPatientsResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/doctors/$doctorId/patients")
                    .get()
                    .apply { header("Authorization", "Bearer $accessToken") }
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiHttpException(resp.code, text)

                    val json = JSONObject(text)
                    val arr = json.optJSONArray("patients") ?: JSONArray()
                    val pts = buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }

                    DoctorPatientsResponse(
                        doctorId = json.getString("doctorId"),
                        patients = pts
                    )
                }
            }
        }

    data class AddPatientResponse(val doctorId: String, val patientId: String)

    suspend fun addPatientToDoctor(doctorId: String, patientId: String, accessToken: String): Result<AddPatientResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("patientId", patientId).toString()

                val req = Request.Builder()
                    .url("$baseUrl/api/v1/doctors/$doctorId/patients")
                    .post(body.toRequestBody(jsonType))
                    .apply {
                        header("Content-Type", "application/json")
                        header("Authorization", "Bearer $accessToken")
                    }
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!(resp.code == 201 || resp.isSuccessful)) throw ApiHttpException(resp.code, text)

                    val json = JSONObject(text)
                    AddPatientResponse(
                        doctorId = json.getString("doctorId"),
                        patientId = json.getString("patientId")
                    )
                }
            }
        }

    suspend fun deletePatientFromDoctor(doctorId: String, patientId: String, accessToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/doctors/$doctorId/patients/$patientId")
                    .delete()
                    .apply { header("Authorization", "Bearer $accessToken") }
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (resp.code == 204) return@use
                    val text = resp.body?.string().orEmpty()
                    throw ApiHttpException(resp.code, text)
                }
            }.map { Unit }
        }


    data class SetCommentResponse(val experimentId: String, val comment: String?)

    suspend fun setExperimentComment(experimentId: String, comment: String, accessToken: String): Result<SetCommentResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("comment", comment).toString()

                val req = Request.Builder()
                    .url("$baseUrl/api/v1/experiments/$experimentId/comment")
                    .patch(body.toRequestBody(jsonType))
                    .apply {
                        header("Content-Type", "application/json")
                        header("Authorization", "Bearer $accessToken")
                    }
                    .build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiHttpException(resp.code, text)

                    val json = JSONObject(text)
                    SetCommentResponse(
                        experimentId = json.getString("experimentId"),
                        comment = json.optString("comment").ifBlank { null }
                    )
                }
            }
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
