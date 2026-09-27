package com.igordanilcenko.speedtest.domain.model

data class Node(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val coordinates: Coordinates,
)
