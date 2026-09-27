package com.igordanilcenko.speedtest.presentation

import com.igordanilcenko.speedtest.domain.model.SelectedServer
import com.igordanilcenko.speedtest.domain.TestFailure
import com.igordanilcenko.speedtest.domain.LocationFailure

enum class TestPhase { Idle, Locating, FindingNodes, Pinging, Measuring, Finished, Error }
enum class LocationPermission { Unknown, NotRequested, Granted, Denied }

data class SpeedTestUiState(
    val phase: TestPhase = TestPhase.Idle,
    val selectedServer: SelectedServer? = null,
    val speedMbps: Double? = null,
    val elapsedMillis: Long = 0,
    val failure: TestFailure? = null,
    val locationPermission: LocationPermission = LocationPermission.Unknown,
    val locationEnabled: Boolean = true,
    val locationFailure: LocationFailure? = null,
) {
    val canStart: Boolean
        get() = locationPermission != LocationPermission.Unknown &&
            locationPermission != LocationPermission.Denied && locationEnabled && !isRunning

    val isRunning: Boolean
        get() = phase == TestPhase.Locating || phase == TestPhase.FindingNodes ||
            phase == TestPhase.Pinging || phase == TestPhase.Measuring
}
