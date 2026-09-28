package com.igordanilcenko.speedtest.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igordanilcenko.speedtest.domain.FindNearestNodes
import com.igordanilcenko.speedtest.domain.SelectLowestPingServer
import com.igordanilcenko.speedtest.domain.DirectoryException
import com.igordanilcenko.speedtest.domain.NodeDiscoveryUpdate
import com.igordanilcenko.speedtest.domain.LocationException
import com.igordanilcenko.speedtest.domain.LocationFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SpeedTestViewModel(
    private val findNearestNodes: FindNearestNodes,
    private val selectLowestPingServer: SelectLowestPingServer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeedTestUiState())
    val state = mutableState.asStateFlow()
    private var testJob: Job? = null

    fun onIntent(intent: SpeedTestIntent) {
        when (intent) {
            SpeedTestIntent.Start -> start()
            SpeedTestIntent.Stop, SpeedTestIntent.ScreenLeft -> {
                reset()
            }
            is SpeedTestIntent.LocationAccessChanged -> {
                if (intent.permission != state.value.locationPermission ||
                    intent.locationEnabled != state.value.locationEnabled) {
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
        if (!state.value.canStart || state.value.locationPermission != LocationPermission.Granted) return
        reset()
        mutableState.value = state.value.copy(phase = TestPhase.Locating)
        testJob = viewModelScope.launch {
            try {
                findNearestNodes().collect { update ->
                    currentCoroutineContext().ensureActive()
                    mutableState.value = when (update) {
                        NodeDiscoveryUpdate.Locating -> state.value.copy(phase = TestPhase.Locating)
                        NodeDiscoveryUpdate.Loading -> state.value.copy(phase = TestPhase.FindingNodes)
                        is NodeDiscoveryUpdate.Ready -> {
                            mutableState.value = state.value.copy(phase = TestPhase.Pinging, nodes = update.nodes)
                            val selected = selectLowestPingServer(update.nodes)
                            currentCoroutineContext().ensureActive()
                            state.value.copy(
                                phase = if (selected == null) TestPhase.Error else TestPhase.ServerReady,
                                selectedServer = selected,
                                pingFailed = selected == null,
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
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
