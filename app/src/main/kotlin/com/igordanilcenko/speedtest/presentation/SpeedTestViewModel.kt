package com.igordanilcenko.speedtest.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igordanilcenko.speedtest.domain.RunSpeedTest
import com.igordanilcenko.speedtest.domain.SpeedTestException
import com.igordanilcenko.speedtest.domain.SpeedTestUpdate
import com.igordanilcenko.speedtest.domain.LocationException
import com.igordanilcenko.speedtest.domain.LocationFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SpeedTestViewModel(private val runSpeedTest: RunSpeedTest) : ViewModel() {
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
                runSpeedTest().collect { update ->
                    currentCoroutineContext().ensureActive()
                    mutableState.value = when (update) {
                        SpeedTestUpdate.Locating -> state.value.copy(phase = TestPhase.Locating)
                        SpeedTestUpdate.FindingNodes -> state.value.copy(phase = TestPhase.FindingNodes)
                        SpeedTestUpdate.Pinging -> state.value.copy(phase = TestPhase.Pinging)
                        is SpeedTestUpdate.Measuring -> state.value.copy(
                            phase = TestPhase.Measuring,
                            selectedServer = update.server,
                            speedMbps = update.sample?.currentMbps,
                            elapsedMillis = update.sample?.elapsedMillis ?: 0,
                        )
                        is SpeedTestUpdate.Finished -> state.value.copy(
                            phase = TestPhase.Finished,
                            selectedServer = update.server,
                            speedMbps = update.sample.averageMbps,
                            elapsedMillis = update.sample.elapsedMillis,
                        )
                    }
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val locationFailure = (error as? LocationException)?.failure
                mutableState.value = state.value.copy(
                    phase = TestPhase.Error,
                    failure = (error as? SpeedTestException)?.failure,
                    locationFailure = locationFailure,
                    locationPermission = if (locationFailure == LocationFailure.PermissionDenied)
                        LocationPermission.Denied else state.value.locationPermission,
                    locationEnabled = if (locationFailure == LocationFailure.Disabled) false else state.value.locationEnabled,
                )
            }
        }
    }
}
