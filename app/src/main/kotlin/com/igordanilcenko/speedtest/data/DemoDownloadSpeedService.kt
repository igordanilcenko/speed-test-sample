package com.igordanilcenko.speedtest.data

import com.igordanilcenko.speedtest.domain.DownloadSpeedService
import com.igordanilcenko.speedtest.domain.model.DownloadUpdate
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

class DemoDownloadSpeedService : DownloadSpeedService {
    override fun measure(server: Node, duration: Duration): Flow<DownloadUpdate> = flow {
        require(duration.isFinite() && duration.inWholeMilliseconds > 0)
        var elapsed = 0L
        var bytes = 0L
        var measurement = SpeedMeasurement(0.0, 0, 0)
        emit(DownloadUpdate.Progress(measurement, isDemo = true))
        while (elapsed < duration.inWholeMilliseconds) {
            val interval = minOf(500L, duration.inWholeMilliseconds - elapsed)
            delay(interval)
            val mbps = 48.0 + ((elapsed / 500 * 7) % 12) * 4
            bytes += (mbps * 1_000 * interval / 8).toLong()
            elapsed += interval
            measurement = SpeedMeasurement(mbps, bytes, elapsed)
            if (elapsed < duration.inWholeMilliseconds) {
                emit(DownloadUpdate.Progress(measurement, isDemo = true))
            }
        }
        emit(DownloadUpdate.Finished(measurement, isDemo = true))
    }
}
