package com.igordanilcenko.speedtest.presentation

import com.igordanilcenko.speedtest.domain.model.NearbyNode
import com.igordanilcenko.speedtest.domain.model.SelectedServer
import com.igordanilcenko.speedtest.domain.DirectoryFailure
import com.igordanilcenko.speedtest.domain.LocationFailure

enum class TestPhase { Idle, Locating, FindingNodes, Pinging, ServerReady, Error }
enum class LocationPermission { Unknown, NotRequested, Granted, Denied }

data class SpeedTestUiState(
    val phase: TestPhase = TestPhase.Idle,
    val nodes: List<NearbyNode> = emptyList(),
    val selectedServer: SelectedServer? = null,
    val pingFailed: Boolean = false,
    val failure: DirectoryFailure? = null,
    val locationPermission: LocationPermission = LocationPermission.Unknown,
    val locationEnabled: Boolean = true,
    val locationFailure: LocationFailure? = null,
) {
    val canStart: Boolean
        get() = locationPermission != LocationPermission.Unknown &&
            locationPermission != LocationPermission.Denied && locationEnabled && !isRunning

    val isRunning: Boolean
        get() = phase == TestPhase.Locating || phase == TestPhase.FindingNodes || phase == TestPhase.Pinging
}
