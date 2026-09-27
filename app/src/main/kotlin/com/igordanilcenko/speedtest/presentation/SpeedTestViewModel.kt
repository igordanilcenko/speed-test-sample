package com.igordanilcenko.speedtest.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igordanilcenko.speedtest.domain.RunSpeedTest
import com.igordanilcenko.speedtest.domain.SpeedTestException
import com.igordanilcenko.speedtest.domain.SpeedTestUpdate
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
                testJob?.cancel()
                testJob = null
                mutableState.value = SpeedTestUiState()
            }
        }
    }

    private fun start() {
        if (state.value.isRunning) return
        mutableState.value = SpeedTestUiState(phase = TestPhase.FindingNodes)
        testJob = viewModelScope.launch {
            try {
                runSpeedTest().collect { update ->
                    currentCoroutineContext().ensureActive()
                    mutableState.value = when (update) {
                        SpeedTestUpdate.FindingNodes -> SpeedTestUiState(phase = TestPhase.FindingNodes)
                        SpeedTestUpdate.Pinging -> SpeedTestUiState(phase = TestPhase.Pinging)
                        is SpeedTestUpdate.Measuring -> SpeedTestUiState(
                            phase = TestPhase.Measuring,
                            selectedServer = update.server,
                            speedMbps = update.sample?.currentMbps,
                            elapsedMillis = update.sample?.elapsedMillis ?: 0,
                        )
                        is SpeedTestUpdate.Finished -> SpeedTestUiState(
                            phase = TestPhase.Finished,
                            selectedServer = update.server,
                            speedMbps = update.sample.averageMbps,
                            elapsedMillis = update.sample.elapsedMillis,
                        )
                    }
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = SpeedTestUiState(
                    phase = TestPhase.Error,
                    failure = (error as? SpeedTestException)?.failure,
                )
            }
        }
    }
}
