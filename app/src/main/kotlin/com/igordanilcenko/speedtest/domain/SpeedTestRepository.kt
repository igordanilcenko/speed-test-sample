package com.igordanilcenko.speedtest.domain

import kotlinx.coroutines.flow.Flow

interface SpeedTestRepository {
    suspend fun getNearestNodes(): List<Node>
    suspend fun ping(node: Node): Double?
    fun measureSpeed(server: Node, durationMillis: Long): Flow<SpeedMeasurement>
}
