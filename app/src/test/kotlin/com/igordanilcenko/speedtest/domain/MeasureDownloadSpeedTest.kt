package com.igordanilcenko.speedtest.domain

import com.igordanilcenko.speedtest.domain.intent.MeasureDownloadSpeed
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.DownloadUpdate
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class MeasureDownloadSpeedTest {
    private val server = Node("test", "Test", "test.invalid", 80, Coordinates(0.0, 0.0))

    @Test
    fun `forwards selected server duration and result`() = runTest {
        val result = DownloadUpdate.Finished(SpeedMeasurement(1.0, 1_875_000, 15_000))
        val service = DownloadSpeedService { selected, duration ->
            assertSame(server, selected)
            assertEquals(15.seconds, duration)
            flowOf(result)
        }
        assertEquals(result, MeasureDownloadSpeed(service)(server).toList().single())
    }

    @Test
    fun `empty stream does not report success`() = runTest {
        try {
            MeasureDownloadSpeed(DownloadSpeedService { _, _ -> emptyFlow() })(server).toList()
            fail("Expected missing result failure")
        } catch (_: IllegalStateException) {
        }
    }
}
