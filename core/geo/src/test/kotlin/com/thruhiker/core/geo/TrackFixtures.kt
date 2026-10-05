package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint

/**
 * Builds a straight run of [legs] legs, each [spacingMeters] long, starting at [start] and
 * heading along [bearingDegrees], climbing steadily at [gradePercent].
 *
 * Distances are produced by the geodesic forward formula rather than by nudging degrees, so a
 * fixture's length is known to the metre and tests can assert on real numbers instead of on
 * tolerances wide enough to hide a bug.
 */
internal fun straightRun(
  legs: Int,
  spacingMeters: Double = 100.0,
  gradePercent: Double = 0.0,
  start: LatLng = LatLng(46.0, 7.0),
  bearingDegrees: Double = 90.0,
  timed: Boolean = false,
  startTimeMillis: Long = 1_700_000_000_000L,
): List<TrackPoint> {
  require(legs >= 0) { "Legs must not be negative: $legs" }

  val points = ArrayList<TrackPoint>(legs + 1)
  var position = start
  var distance = 0.0

  points.add(
    TrackPoint(
      position = position,
      elevationMeters = elevationAt(distance, gradePercent),
      timeMillis = if (timed) startTimeMillis else null,
    ),
  )

  for (leg in 1..legs) {
    position = Geodesic.destination(position, bearingDegrees, spacingMeters)
    distance += Geodesic.distanceMeters(points.last().position, position)
    points.add(
      TrackPoint(
        position = position,
        elevationMeters = elevationAt(distance, gradePercent),
        timeMillis = if (timed) startTimeMillis + leg * 60_000L else null,
      ),
    )
  }

  return points
}

/** Shorthand for a single-segment track built from [points]. */
internal fun trackOf(points: List<TrackPoint>): Track =
  if (points.isEmpty()) Track.Empty else Track.of(points)

private fun elevationAt(distanceMeters: Double, gradePercent: Double): Double? =
  if (gradePercent == 0.0 && distanceMeters == 0.0) 0.0 else distanceMeters * gradePercent / 100.0
