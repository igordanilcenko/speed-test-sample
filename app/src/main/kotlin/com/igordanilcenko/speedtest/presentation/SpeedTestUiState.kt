package com.igordanilcenko.speedtest.presentation

import com.igordanilcenko.speedtest.domain.model.SelectedServer
import com.igordanilcenko.speedtest.domain.TestFailure

enum class TestPhase { Idle, FindingNodes, Pinging, Measuring, Finished, Error }

data class SpeedTestUiState(
    val phase: TestPhase = TestPhase.Idle,
    val selectedServer: SelectedServer? = null,
    val speedMbps: Double? = null,
    val elapsedMillis: Long = 0,
    val failure: TestFailure? = null,
) {
    val isRunning: Boolean
        get() = phase == TestPhase.FindingNodes || phase == TestPhase.Pinging || phase == TestPhase.Measuring
}
