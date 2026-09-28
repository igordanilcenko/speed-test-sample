package com.igordanilcenko.speedtest.data

import com.igordanilcenko.speedtest.domain.DownloadSpeedService
import com.igordanilcenko.speedtest.domain.intent.MeasureDownloadSpeed
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.DownloadUpdate
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DemoDownloadSpeedServiceTest {
    private val server = Node("test", "Test", "does-not-exist.invalid", 80, Coordinates(0.0, 0.0))

    @Test fun `demo emits progress every half second and finishes at fifteen seconds`() = runTest {
        val updates = MeasureDownloadSpeed(DemoDownloadSpeedService())(server).toList()
        assertEquals(15_000, testScheduler.currentTime)
        assertEquals(30, updates.filterIsInstance<DownloadUpdate.Progress>().size)
        assertTrue(updates.all { it.isDemo })
        assertEquals((0L..29).map { it * 500 }, updates.dropLast(1).map { it.measurement.elapsedMillis })
        val finished = updates.last() as DownloadUpdate.Finished
        assertEquals(15_000, finished.measurement.elapsedMillis)
        val totalBytes = (0L..29).sumOf { step -> ((48.0 + (step * 7 % 12) * 4) * 62_500).toLong() }
        assertEquals(totalBytes, finished.measurement.totalBytes)
        assertEquals(totalBytes * 8.0 / 15 / 1_000_000, finished.measurement.averageMbps, 0.00001)
    }

    @Test fun `use case forwards selected server and fixed duration`() = runTest {
        val service = DownloadSpeedService { selected, duration ->
            assertSame(server, selected)
            assertEquals(15.seconds, duration)
            flowOf(DownloadUpdate.Finished(SpeedMeasurement(1.0, 1_875_000, 15_000)))
        }
        assertFalse(MeasureDownloadSpeed(service)(server).toList().single().isDemo)
    }

    @Test fun `empty stream does not report successful measurement`() = runTest {
        try {
            MeasureDownloadSpeed(DownloadSpeedService { _, _ -> emptyFlow() })(server).toList()
            fail("Expected missing result failure")
        } catch (_: IllegalStateException) {
        }
    }
}
