package com.thruhiker.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.thruhiker.core.geo.SplitsCalculator

/**
 * Which system the app works in.
 *
 * Not a display detail. People plan in the units they navigate in, and a route planner that
 * makes someone convert a distance in their head is one they stop opening. Which is why this
 * is one setting for the whole app rather than a parameter on one screen: a distance in
 * kilometres next to an ascent in feet is worse than either system on its own.
 */
enum class DistanceUnit {
  KILOMETERS,
  MILES;

  /** The interval splits are cut at. A "split" is a kilometre or a mile and nothing between. */
  val splitLengthMeters: Double
    get() = when (this) {
      KILOMETERS -> SplitsCalculator.KILOMETER
      MILES -> SplitsCalculator.MILE
    }

  /** The one-word name of the distance unit, for a toggle. */
  val distanceLabel: String
    get() = when (this) {
      KILOMETERS -> "km"
      MILES -> "mi"
    }
}

/**
 * The current units, and a way to change them, for every screen at once.
 *
 * Held as a composition local rather than threaded through every call so that a screen added
 * later cannot quietly show kilometres while the rest of the app shows miles.
 */
data class DistanceUnits(
  val unit: DistanceUnit,
  val select: (DistanceUnit) -> Unit,
)

/**
 * Defaults to metric when there is no provider — a preview, or a test rendering one widget —
 * because the app's own data, and the terrain it is sampled from, are metric.
 */
val LocalDistanceUnits = compositionLocalOf { DistanceUnits(DistanceUnit.KILOMETERS) {} }
