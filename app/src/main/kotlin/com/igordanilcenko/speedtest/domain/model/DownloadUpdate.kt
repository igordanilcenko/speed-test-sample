package com.igordanilcenko.speedtest.domain.model

sealed interface DownloadUpdate {
    val measurement: SpeedMeasurement
    val isDemo: Boolean

    data class Progress(
        override val measurement: SpeedMeasurement,
        override val isDemo: Boolean = false,
    ) : DownloadUpdate

    data class Finished(
        override val measurement: SpeedMeasurement,
        override val isDemo: Boolean = false,
    ) : DownloadUpdate
}
