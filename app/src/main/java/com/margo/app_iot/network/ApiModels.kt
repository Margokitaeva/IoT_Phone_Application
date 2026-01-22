package com.margo.app_iot.network
data class PatientDataResponse(
    val timestamp: Long,
    val mpuProcessedData: List<List<Float>> // [ [4 floats], [4 floats], ... ]
)
