package com.thruhiker.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Top-level destinations.
 *
 * Nav 3 keys are plain serializable objects rather than strings and route
 * patterns, so arguments are type-checked at compile time.
 */

@Serializable
data object FlyoverRoute : NavKey

@Serializable
data object PlanRoute : NavKey

@Serializable
data object RecordRoute : NavKey

@Serializable
data object JournalRoute : NavKey
