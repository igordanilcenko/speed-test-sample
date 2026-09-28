package com.igordanilcenko.speedtest.domain

import com.igordanilcenko.speedtest.domain.model.NearbyNode
import com.igordanilcenko.speedtest.domain.model.PingResult
import com.igordanilcenko.speedtest.domain.model.SelectedServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class SelectLowestPingServer(private val pingService: PingService) {
    suspend operator fun invoke(nodes: List<NearbyNode>): SelectedServer? = coroutineScope {
        nodes.distinctBy { it.node.id }.sortedBy { it.distanceKm }.take(5).map { nearby ->
            async {
                val result = pingService.ping(nearby.node.host)
                if (result is PingResult.Success && result.latencyMs.isFinite() && result.latencyMs >= 0) {
                    SelectedServer(nearby.node, result.latencyMs)
                } else null
            }
        }.awaitAll().filterNotNull().minByOrNull { it.pingMs }
    }
}
