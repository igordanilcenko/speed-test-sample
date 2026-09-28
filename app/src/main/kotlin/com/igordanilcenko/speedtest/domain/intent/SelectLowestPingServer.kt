package com.igordanilcenko.speedtest.domain.intent

import com.igordanilcenko.speedtest.domain.PingService
import com.igordanilcenko.speedtest.domain.model.NearbyNode
import com.igordanilcenko.speedtest.domain.model.PingResult
import com.igordanilcenko.speedtest.domain.model.SelectedServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

class SelectLowestPingServer(private val pingService: PingService) {
    suspend operator fun invoke(nodes: List<NearbyNode>, log: (String) -> Unit = {}): SelectedServer? = coroutineScope {
        val results = nodes.distinctBy { it.node.id }.sortedBy { it.distanceKm }.take(5).map { nearby ->
            async {
                nearby.node to pingService.ping(nearby.node.host)
            }
        }.awaitAll()
        log(buildString {
            append("[Ping] ICMP results (${results.size})")
            results.forEachIndexed { index, (node, result) ->
                val host = node.host.replace('\n', ' ').replace('\r', ' ').take(253)
                val latency = when (result) {
                    is PingResult.Success -> if (result.latencyMs.isFinite() && result.latencyMs >= 0)
                        String.format(Locale.US, "%.3f ms", result.latencyMs) else "invalid RTT"

                    PingResult.NoReply -> "no reply"
                    PingResult.Unavailable -> "ICMP unavailable"
                }
                append("\n  ${index + 1}. $host:${node.port} | $latency")
            }
        })
        results.mapNotNull { (node, result) ->
            if (result is PingResult.Success && result.latencyMs.isFinite() && result.latencyMs >= 0)
                SelectedServer(node, result.latencyMs) else null
        }.minByOrNull { it.pingMs }
    }
}
