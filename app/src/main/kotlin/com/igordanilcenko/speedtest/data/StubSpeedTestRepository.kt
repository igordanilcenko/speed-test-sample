package com.igordanilcenko.speedtest.data

import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement
import com.igordanilcenko.speedtest.domain.SpeedTestRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class StubSpeedTestRepository : SpeedTestRepository {
    override suspend fun getNearestNodes(): List<Node> {
        delay(500)
        return listOf(12, 24, 38, 56, 79).mapIndexed { index, distance ->
            Node("demo-$index", "Demo ${index + 1}", distance)
        }
    }

    override suspend fun ping(node: Node): Double? {
        delay(600)
        return mapOf("demo-0" to 35.0, "demo-1" to 22.0, "demo-2" to 12.0,
            "demo-3" to 48.0, "demo-4" to 28.0)[node.id]
    }

    override fun measureSpeed(server: Node, durationMillis: Long): Flow<SpeedMeasurement> = flow {
        var elapsed = 0L
        var bytes = 0L
        val speeds = listOf(64.0, 80.0, 96.0, 88.0, 104.0)
        var index = 0
        while (elapsed < durationMillis) {
            val interval = minOf(500L, durationMillis - elapsed)
            delay(interval)
            val speed = speeds[index++ % speeds.size]
            elapsed += interval
            bytes += (speed * 1_000 * interval / 8).toLong()
            emit(SpeedMeasurement(speed, bytes, elapsed))
        }
    }
}
