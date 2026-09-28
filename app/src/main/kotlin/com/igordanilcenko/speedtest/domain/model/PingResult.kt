package com.igordanilcenko.speedtest.domain.model

sealed interface PingResult {
    data class Success(val latencyMs: Double) : PingResult
    data object NoReply : PingResult
    data object Unavailable : PingResult
}
