package com.igordanilcenko.speedtest.domain.intent

import com.igordanilcenko.speedtest.domain.PingService
import com.igordanilcenko.speedtest.domain.model.NearbyNode
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.PingResult
import com.igordanilcenko.speedtest.domain.model.SelectedServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class SelectLowestPingServer(private val pingService: PingService) {
    suspend operator fun invoke(
        nodes: List<NearbyNode>,
        onResults: (List<Pair<Node, PingResult>>) -> Unit = {},
    ): SelectedServer? = coroutineScope {
        val results = nodes.distinctBy { it.node.id }.sortedBy { it.distanceKm }.take(5).map { nearby ->
            async {
                nearby.node to pingService.ping(nearby.node.host)
            }
        }.awaitAll()
        onResults(results)
        results.mapNotNull { (node, result) ->
            if (result is PingResult.Success && result.latencyMs.isFinite() && result.latencyMs >= 0)
                SelectedServer(node, result.latencyMs) else null
        }.minByOrNull { it.pingMs }
    }
}
