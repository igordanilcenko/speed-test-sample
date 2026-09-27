package com.igordanilcenko.speedtest.data

import retrofit2.http.GET

interface ServerDirectoryApi {
    @GET("api/v2/servers")
    suspend fun getServers(): List<ServerDto?>
}

data class ServerDto(
    val url: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
    val country: String? = null,
)
