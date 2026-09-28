package com.igordanilcenko.speedtest.domain

enum class DownloadFailure {
    TokenRequest, InvalidHello, Unauthorized, Connection, TruncatedResponse, Timeout, InvalidResponse
}

class DownloadException(val failure: DownloadFailure) : Exception(failure.name)
