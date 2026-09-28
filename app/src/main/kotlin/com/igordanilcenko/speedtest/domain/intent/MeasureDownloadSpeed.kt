package com.igordanilcenko.speedtest.domain.intent

import com.igordanilcenko.speedtest.domain.DownloadSpeedService
import com.igordanilcenko.speedtest.domain.model.DownloadUpdate
import com.igordanilcenko.speedtest.domain.model.Node
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.seconds

class MeasureDownloadSpeed(private val service: DownloadSpeedService) {
    operator fun invoke(server: Node) = flow {
        var finished = false
        service.measure(server, duration).collect { update ->
            check(!finished) { "Download emitted an update after completion" }
            finished = update is DownloadUpdate.Finished
            emit(update)
        }
        check(finished) { "Download ended without a result" }
    }

    companion object {
        val duration = 15.seconds
    }
}
