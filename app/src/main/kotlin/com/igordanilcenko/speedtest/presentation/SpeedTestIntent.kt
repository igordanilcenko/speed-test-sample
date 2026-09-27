package com.igordanilcenko.speedtest.presentation

sealed interface SpeedTestIntent {
    data object Start : SpeedTestIntent
    data object Stop : SpeedTestIntent
    data object ScreenLeft : SpeedTestIntent
    data class LocationAccessChanged(
        val permission: LocationPermission,
        val locationEnabled: Boolean,
    ) : SpeedTestIntent
}
