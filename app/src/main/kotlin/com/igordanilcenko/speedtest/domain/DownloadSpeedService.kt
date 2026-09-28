package com.igordanilcenko.speedtest.domain

import com.igordanilcenko.speedtest.domain.model.DownloadUpdate
import com.igordanilcenko.speedtest.domain.model.Node
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

fun interface DownloadSpeedService {
    fun measure(server: Node, duration: Duration): Flow<DownloadUpdate>
}
