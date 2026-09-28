package com.igordanilcenko.speedtest.domain

import com.igordanilcenko.speedtest.domain.model.PingResult

fun interface PingService {
    suspend fun ping(host: String): PingResult
}
