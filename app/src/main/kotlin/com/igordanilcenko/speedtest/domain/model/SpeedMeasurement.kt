package com.igordanilcenko.speedtest.domain.model

data class SpeedMeasurement(
    val currentMbps: Double,
    val totalBytes: Long,
    val elapsedMillis: Long,
) {
    val averageMbps: Double
        get() = if (elapsedMillis > 0) totalBytes * 8.0 / elapsedMillis / 1_000 else 0.0
}