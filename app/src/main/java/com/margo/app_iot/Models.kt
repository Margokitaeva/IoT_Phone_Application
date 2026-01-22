package com.margo.app_iot

data class QuaternionSample(
    val q0: Float,
    val q1: Float,
    val q2: Float,
    val q3: Float
)

data class ExperimentDataResponse(
    val experimentId: String,
    val deviceID: String,
    val timestamp: Long,
    val mpuProcessedData: List<List<Float>>
)