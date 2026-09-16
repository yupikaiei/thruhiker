package com.thruhiker.core.model

/**
 * An ordered set of segments describing a route, either recorded on trail or
 * planned ahead of time.
 *
 * Everything that walks the track must walk it segment by segment. [points] is a
 * convenience for display and bulk inspection only; it deliberately erases the
 * gaps, so it must not be used for distance, speed or elevation maths.
 */
data class Track(
  val segments: List<TrackSegment>,
) {
  /** Every point, with segment boundaries erased. Not for measurement. */
  val points: List<TrackPoint> get() = segments.flatMap { it.points }

  val positions: List<LatLng> get() = points.map { it.position }

  val isEmpty: Boolean get() = segments.all { it.isEmpty }

  val size: Int get() = segments.sumOf { it.size }

  fun elevations(): List<Double?> = points.map { it.elevationMeters }

  companion object {
    val Empty = Track(emptyList())

    /** Wraps a single run of points, which is what most imports and recordings produce. */
    fun of(points: List<TrackPoint>): Track =
      if (points.isEmpty()) Empty else Track(listOf(TrackSegment(points)))
  }
}
