package com.igordanilcenko.speedtest.domain.intent

import com.igordanilcenko.speedtest.domain.DirectoryException
import com.igordanilcenko.speedtest.domain.DirectoryFailure
import com.igordanilcenko.speedtest.domain.LocationRepository
import com.igordanilcenko.speedtest.domain.ServerDirectoryRepository
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.NearbyNode
import com.igordanilcenko.speedtest.domain.model.Node
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

sealed interface NodeDiscoveryUpdate {
    data object Locating : NodeDiscoveryUpdate
    data object Loading : NodeDiscoveryUpdate
    data class Ready(val nodes: List<NearbyNode>) : NodeDiscoveryUpdate
}

class FindNearestNodes(
    private val repository: ServerDirectoryRepository,
    private val locationRepository: LocationRepository,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    operator fun invoke(): Flow<NodeDiscoveryUpdate> = flow {
        emit(NodeDiscoveryUpdate.Locating)
        val coordinates = locationRepository.getCurrentCoordinates()
        emit(NodeDiscoveryUpdate.Loading)
        val nodes = withTimeout(20_000) { repository.getNodes() }
        val nearest = withContext(computationDispatcher) { selectNearestNodes(coordinates, nodes) }
        if (nearest.isEmpty()) throw DirectoryException(DirectoryFailure.NoServers)
        emit(NodeDiscoveryUpdate.Ready(nearest))
    }
}

internal fun selectNearestNodes(origin: Coordinates, nodes: List<Node>): List<NearbyNode> =
    nodes.distinctBy { it.id }
        .map { NearbyNode(it, distanceKm(origin, it.coordinates)) }
        .sortedWith(compareBy<NearbyNode> { it.distanceKm }.thenBy { it.node.id })
        .take(5)

internal fun distanceKm(from: Coordinates, to: Coordinates): Double {
    val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
    val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
    val a = sin(latitudeDelta / 2).let { it * it } +
            cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) *
            sin(longitudeDelta / 2).let { it * it }
    return 2 * 6371.0088 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
