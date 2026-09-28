package com.igordanilcenko.speedtest.domain

import com.igordanilcenko.speedtest.domain.intent.SelectLowestPingServer
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.NearbyNode
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.PingResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectLowestPingServerTest {
    private fun node(id: Int) = NearbyNode(
        Node("$id", "Server $id", "host$id.test", 8080, Coordinates(0.0, 0.0)), id.toDouble(),
    )

    @Test
    fun `only five nearest distinct nodes are pinged concurrently`() = runTest {
        val hosts = mutableListOf<String>()
        val select = SelectLowestPingServer { host ->
            hosts += host
            delay(100)
            PingResult.Success(20.0 - host.removePrefix("host").substringBefore('.').toInt())
        }
        val selected = select((9 downTo 0).map(::node) + node(0))
        assertEquals((0..4).map { "host$it.test" }, hosts)
        assertEquals("4", selected?.node?.id)
        assertEquals(16.0, selected!!.pingMs, 0.0)
        assertEquals(100, testScheduler.currentTime)
    }

    @Test
    fun `unreachable and invalid measurements are excluded`() = runTest {
        val results = listOf(
            PingResult.NoReply, PingResult.Unavailable,
            PingResult.Success(Double.NaN), PingResult.Success(-1.0), PingResult.Success(0.1)
        )
        val select = SelectLowestPingServer { host ->
            results[host.removePrefix("host").substringBefore('.').toInt()]
        }
        val logs = mutableListOf<String>()
        assertEquals("4", select((0..4).map(::node), logs::add)?.node?.id)
        val resultsLog = logs.single()
        assertTrue(resultsLog.contains("host0.test:8080 | no reply"))
        assertTrue(resultsLog.contains("host1.test:8080 | ICMP unavailable"))
        assertTrue(resultsLog.contains("host2.test:8080 | invalid RTT"))
        assertTrue(resultsLog.contains("host3.test:8080 | invalid RTT"))
        assertTrue(resultsLog.contains("host4.test:8080 | 0.100 ms"))
    }

    @Test
    fun `no replies or no candidates produce no selected server`() = runTest {
        val select = SelectLowestPingServer { PingResult.NoReply }
        assertNull(select((0..2).map(::node)))
        assertNull(select(emptyList()))
    }

    @Test
    fun `equal rtt prefers closer candidate`() = runTest {
        val select = SelectLowestPingServer { PingResult.Success(5.0) }
        assertEquals("0", select(listOf(node(1), node(0)))?.node?.id)
    }
}
