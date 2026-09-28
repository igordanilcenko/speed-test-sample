package com.igordanilcenko.speedtest.presentation

import com.igordanilcenko.speedtest.domain.DirectoryFailure
import com.igordanilcenko.speedtest.domain.DownloadFailure
import com.igordanilcenko.speedtest.domain.LocationFailure
import com.igordanilcenko.speedtest.domain.model.SelectedServer
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement

enum class TestPhase { Idle, Locating, FindingNodes, Pinging, ServerReady, Measuring, Finished, Error }
enum class LocationPermission { Unknown, NotRequested, Granted, Denied }

data class SpeedTestUiState(
    val phase: TestPhase = TestPhase.Idle,
    val download: SpeedMeasurement? = null,
    val selectedServer: SelectedServer? = null,
    val pingFailed: Boolean = false,
    val failure: DirectoryFailure? = null,
    val downloadFailure: DownloadFailure? = null,
    val locationPermission: LocationPermission = LocationPermission.Unknown,
    val locationEnabled: Boolean = true,
    val locationFailure: LocationFailure? = null,
) {
    val canStart: Boolean
        get() = locationPermission != LocationPermission.Unknown &&
                locationPermission != LocationPermission.Denied && locationEnabled && !isRunning

    val isRunning: Boolean
        get() = phase == TestPhase.Locating || phase == TestPhase.FindingNodes || phase == TestPhase.Pinging || phase == TestPhase.Measuring
}
