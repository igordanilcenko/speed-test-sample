package com.igordanilcenko.speedtest.domain

import com.igordanilcenko.speedtest.domain.model.SelectedServer
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

const val TEST_DURATION_MILLIS = 15_000L

sealed interface SpeedTestUpdate {
    data object Locating : SpeedTestUpdate
    data object FindingNodes : SpeedTestUpdate
    data object Pinging : SpeedTestUpdate
    data class Measuring(val server: SelectedServer, val sample: SpeedMeasurement? = null) : SpeedTestUpdate
    data class Finished(val server: SelectedServer, val sample: SpeedMeasurement) : SpeedTestUpdate
}

enum class TestFailure { NoServers, NoPingReplies, NoMeasurements }
class SpeedTestException(val failure: TestFailure) : Exception(failure.name)

class RunSpeedTest(
    private val repository: SpeedTestRepository,
    private val locationRepository: LocationRepository,
) {
    operator fun invoke(): Flow<SpeedTestUpdate> = flow {
        emit(SpeedTestUpdate.Locating)
        val coordinates = locationRepository.getCurrentCoordinates()
        emit(SpeedTestUpdate.FindingNodes)
        val nodes = withTimeout(10_000) { repository.getNearestNodes(coordinates).take(5) }
        if (nodes.isEmpty()) throw SpeedTestException(TestFailure.NoServers)
        emit(SpeedTestUpdate.Pinging)
        val server = coroutineScope {
            nodes.map { node ->
                async {
                    val ping = try {
                        withTimeoutOrNull(2_000) { repository.ping(node) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    ping
                        ?.takeIf { it.isFinite() && it >= 0 }
                        ?.let { SelectedServer(node, it) }
                }
            }.awaitAll().filterNotNull().minByOrNull { it.pingMs }
        } ?: throw SpeedTestException(TestFailure.NoPingReplies)

        emit(SpeedTestUpdate.Measuring(server))
        var lastSample: SpeedMeasurement? = null
        withTimeout(TEST_DURATION_MILLIS + 5_000) {
            repository.measureSpeed(server.node, TEST_DURATION_MILLIS).collect { sample ->
                lastSample = sample
                emit(SpeedTestUpdate.Measuring(server, sample))
            }
        }
        val result = lastSample?.takeIf { it.elapsedMillis >= TEST_DURATION_MILLIS }
            ?: throw SpeedTestException(TestFailure.NoMeasurements)
        emit(SpeedTestUpdate.Finished(server, result))
    }
}
