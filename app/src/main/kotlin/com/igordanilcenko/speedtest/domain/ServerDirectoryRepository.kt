package com.igordanilcenko.speedtest.domain

import com.igordanilcenko.speedtest.domain.model.Node

interface ServerDirectoryRepository {
    suspend fun getNodes(): List<Node>
}

enum class DirectoryFailure { NoServers, Unavailable, InvalidResponse }
class DirectoryException(val failure: DirectoryFailure) : Exception(failure.name)
