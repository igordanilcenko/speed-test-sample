package com.igordanilcenko.speedtest.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igordanilcenko.speedtest.domain.DirectoryException
import com.igordanilcenko.speedtest.domain.LocationException
import com.igordanilcenko.speedtest.domain.LocationFailure
import com.igordanilcenko.speedtest.domain.intent.FindNearestNodes
import com.igordanilcenko.speedtest.domain.intent.NodeDiscoveryUpdate
import com.igordanilcenko.speedtest.domain.intent.SelectLowestPingServer
import com.igordanilcenko.speedtest.domain.intent.MeasureDownloadSpeed
import com.igordanilcenko.speedtest.domain.model.DownloadUpdate
import com.igordanilcenko.speedtest.domain.model.NearbyNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class SpeedTestViewModel(
    private val findNearestNodes: FindNearestNodes,
    private val selectLowestPingServer: SelectLowestPingServer,
    private val measureDownloadSpeed: MeasureDownloadSpeed,
    private val log: (String) -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeedTestUiState())
    val state = mutableState.asStateFlow()
    private var testJob: Job? = null

    fun onIntent(intent: SpeedTestIntent) {
        when (intent) {
            is SpeedTestIntent.Start -> start()
            is SpeedTestIntent.Stop, SpeedTestIntent.ScreenLeft -> {
                log("[Run] Reset requested: $intent")
                reset()
            }

            is SpeedTestIntent.LocationAccessChanged -> {
                if (intent.permission != state.value.locationPermission ||
                    intent.locationEnabled != state.value.locationEnabled
                ) {
                    log("[Location] Access changed: permission=${intent.permission}, enabled=${intent.locationEnabled}")
                    reset()
                    mutableState.value = state.value.copy(
                        locationPermission = intent.permission,
                        locationEnabled = intent.locationEnabled,
                    )
                }
            }
        }
    }

    private fun reset() {
        testJob?.cancel()
        testJob = null
        mutableState.value = SpeedTestUiState(
            locationPermission = state.value.locationPermission,
            locationEnabled = state.value.locationEnabled,
        )
    }

    private fun start() {
        if (!state.value.canStart || state.value.locationPermission != LocationPermission.Granted) {
            log(
                "[Run] Start blocked: permission=${state.value.locationPermission}, " +
                        "locationEnabled=${state.value.locationEnabled}, phase=${state.value.phase}"
            )
            return
        }
        log("[Run] Starting server discovery")
        reset()
        mutableState.value = state.value.copy(phase = TestPhase.Locating)
        testJob = viewModelScope.launch {
            try {
                findNearestNodes().collect { update ->
                    currentCoroutineContext().ensureActive()
                    mutableState.value = when (update) {
                        is NodeDiscoveryUpdate.Locating -> {
                            log("[Discovery] Obtaining user location")
                            state.value.copy(phase = TestPhase.Locating)
                        }

                        is NodeDiscoveryUpdate.Loading -> {
                            log("[Discovery] Location received; loading server directory")
                            state.value.copy(phase = TestPhase.FindingNodes)
                        }

                        is NodeDiscoveryUpdate.Ready -> {
                            log(formatNearbyNodes(update.nodes))
                            log("[Ping] Measuring ICMP RTT for ${update.nodes.size} candidates")
                            mutableState.value = state.value.copy(phase = TestPhase.Pinging, nodes = update.nodes)
                            val selected = selectLowestPingServer(update.nodes)
                            currentCoroutineContext().ensureActive()
                            log(
                                if (selected == null) "[Ping] Failed: no candidate returned a valid ICMP measurement"
                                else "[Ping] Selected lowest RTT: ${selected.node.host}:${selected.node.port}, " +
                                        "${String.format(Locale.US, "%.3f", selected.pingMs)} ms"
                            )
                            state.value.copy(
                                phase = if (selected == null) TestPhase.Error else TestPhase.ServerReady,
                                selectedServer = selected,
                                pingFailed = selected == null,
                            )
                        }
                    }
                }
                val selected = state.value.selectedServer ?: return@launch
                mutableState.value = state.value.copy(phase = TestPhase.Measuring)
                log("[Download] Starting 15-second download test for ${selected.node.host}")
                measureDownloadSpeed(selected.node).collect { update ->
                    currentCoroutineContext().ensureActive()
                    mutableState.value = state.value.copy(
                        phase = if (update is DownloadUpdate.Finished) TestPhase.Finished else TestPhase.Measuring,
                        download = update.measurement,
                        isDemo = update.isDemo,
                    )
                    if (update is DownloadUpdate.Finished) {
                        log("[Download] Finished: average=${update.measurement.averageMbps} Mbps, demo=${update.isDemo}")
                    }
                }
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) log("[Run] Cancelled")
                currentCoroutineContext().ensureActive()
                log(
                    "[Run] Failed during ${state.value.phase}: ${error.javaClass.simpleName}" +
                            when (error) {
                                is LocationException -> " (${error.failure})"
                                is DirectoryException -> " (${error.failure})"
                                else -> ""
                            }
                )
                val locationFailure = (error as? LocationException)?.failure
                mutableState.value = state.value.copy(
                    phase = TestPhase.Error,
                    failure = (error as? DirectoryException)?.failure,
                    locationFailure = locationFailure,
                    locationPermission = if (locationFailure == LocationFailure.PermissionDenied)
                        LocationPermission.Denied else state.value.locationPermission,
                    locationEnabled = if (locationFailure == LocationFailure.Disabled) false else state.value.locationEnabled,
                )
            }
        }
    }
}

internal fun formatNearbyNodes(nodes: List<NearbyNode>): String = buildString {
    append("[Discovery] Nearest servers (${nodes.size}), sorted by distance")
    nodes.sortedBy { it.distanceKm }.forEachIndexed { index, nearby ->
        val name = nearby.node.name.replace('\n', ' ').replace('\r', ' ').take(120)
        val host = nearby.node.host.replace('\n', ' ').replace('\r', ' ').take(253)
        append("\n  ${index + 1}. ")
        if (index == 0) append("[NEAREST] ")
        append("$name | $host:${nearby.node.port} | ")
        append(String.format(Locale.US, "%.2f km", nearby.distanceKm))
    }
}
