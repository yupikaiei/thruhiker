package com.thruhiker.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Top-level destinations.
 *
 * Nav 3 keys are plain serializable objects rather than strings and route
 * patterns, so arguments are type-checked at compile time.
 */

/** The planner: draw, measure, colour and split a route. The app's front door. */
@Serializable
data object PlanRoute : NavKey

/** The kept routes, which open in the flyover. */
@Serializable
data object RoutesRoute : NavKey

/** The 3D flight over a route. */
@Serializable
data object FlyoverRoute : NavKey

/** On-trail recording. Not built yet. */
@Serializable
data object RecordRoute : NavKey
