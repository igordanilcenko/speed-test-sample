package com.igordanilcenko.speedtest.domain.model

sealed interface DownloadUpdate {
    val measurement: SpeedMeasurement

    data class Progress(
        override val measurement: SpeedMeasurement,
    ) : DownloadUpdate

    data class Finished(
        override val measurement: SpeedMeasurement,
    ) : DownloadUpdate
}
