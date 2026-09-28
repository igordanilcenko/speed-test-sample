package com.igordanilcenko.speedtest.domain.model

data class SpeedMeasurement(
    val currentMbps: Double,
    val totalBytes: Long,
    val elapsedMillis: Long,
    val elapsedNanos: Long = elapsedMillis * 1_000_000,
) {
    val averageMbps: Double
        get() = calculateMbps(totalBytes, elapsedNanos)
}

internal fun calculateMbps(bytes: Long, elapsedNanos: Long): Double {
    require(bytes >= 0 && elapsedNanos >= 0)
    return if (elapsedNanos == 0L) 0.0 else bytes * 8_000.0 / elapsedNanos
}
