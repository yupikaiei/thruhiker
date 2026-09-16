package com.thruhiker.core.model

/**
 * A continuous run of samples with no gaps.
 *
 * Segments exist because GPS recording does not: a hiker pauses for lunch, loses
 * signal in a canyon, or switches the phone off overnight. Treating those gaps as
 * ordinary consecutive points invents a straight-line jump of however far they
 * walked in between, which corrupts distance, speed and elevation gain at the
 * same time.
 */
data class TrackSegment(
  val points: List<TrackPoint>,
) {
  val isEmpty: Boolean get() = points.isEmpty()
  val size: Int get() = points.size

  val positions: List<LatLng> get() = points.map { it.position }

  fun elevations(): List<Double?> = points.map { it.elevationMeters }

  companion object {
    val Empty = TrackSegment(emptyList())
  }
}
