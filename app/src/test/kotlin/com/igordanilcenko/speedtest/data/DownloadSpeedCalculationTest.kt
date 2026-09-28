package com.igordanilcenko.speedtest.data

import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement
import com.igordanilcenko.speedtest.domain.model.calculateMbps
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSpeedCalculationTest {
    @Test
    fun `bytes and nanoseconds convert to decimal Mbps`() {
        assertEquals(100.0, calculateMbps(6_250_000, 500_000_000), 0.000001)
    }

    @Test
    fun `average uses all bytes and total elapsed time`() {
        assertEquals(100.0, SpeedMeasurement(200.0, 187_500_000, 15_000).averageMbps, 0.000001)
    }

    @Test
    fun `current speed uses byte and time deltas`() {
        val previous = DownloadSample(6_250_000, 500_000_000)
        val current = DownloadSample(18_750_000, 1_000_000_000).measurement(previous)
        assertEquals(200.0, current.currentMbps, 0.000001)
        assertEquals(150.0, current.averageMbps, 0.000001)
    }

    @Test
    fun `sub millisecond precision is retained`() {
        val measurement = DownloadSample(625, 50_000).measurement(DownloadSample(0, 0))
        assertEquals(0, measurement.elapsedMillis)
        assertEquals(100.0, measurement.averageMbps, 0.000001)
    }

    @Test
    fun `initial empty sample has zero speed`() {
        assertEquals(0.0, calculateMbps(0, 0), 0.0)
    }
}
