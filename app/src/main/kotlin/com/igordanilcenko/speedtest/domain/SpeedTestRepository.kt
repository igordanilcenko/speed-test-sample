package com.igordanilcenko.speedtest.domain

import kotlinx.coroutines.flow.Flow
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement

interface SpeedTestRepository {
    suspend fun getNearestNodes(coordinates: Coordinates): List<Node>
    suspend fun ping(node: Node): Double?
    fun measureSpeed(server: Node, durationMillis: Long): Flow<SpeedMeasurement>
}
