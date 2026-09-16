package com.thruhiker.core.model

/**
 * An ordered set of samples describing a route, either recorded on trail or
 * planned ahead of time.
 */
data class Track(
  val points: List<TrackPoint>,
) {
  val isEmpty: Boolean get() = points.isEmpty()
  val size: Int get() = points.size

  val positions: List<LatLng> get() = points.map { it.position }

  fun elevations(): List<Double?> = points.map { it.elevationMeters }

  companion object {
    val Empty = Track(emptyList())
  }
}
