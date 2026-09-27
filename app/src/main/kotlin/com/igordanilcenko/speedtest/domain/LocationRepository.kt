package com.igordanilcenko.speedtest.domain

import com.igordanilcenko.speedtest.domain.model.Coordinates

interface LocationRepository {
    suspend fun getCurrentCoordinates(): Coordinates
}

enum class LocationFailure { PermissionDenied, Disabled, Unavailable }
class LocationException(val failure: LocationFailure) : Exception(failure.name)
