package com.igordanilcenko.speedtest.presentation

sealed interface SpeedTestIntent {
    data object Start : SpeedTestIntent
    data object Stop : SpeedTestIntent
    data object ScreenLeft : SpeedTestIntent
}
