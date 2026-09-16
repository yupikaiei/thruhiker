package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint

/** A point reached by walking a given distance along a track. */
data class TrackSample(
  val position: LatLng,
  val elevationMeters: Double?,
  /** Distance from the start of the track, which is not the same as the requested distance when clamped. */
  val distanceMeters: Double,
  val segmentIndex: Int,
  /** Bearing of the leg this sample sits on, degrees clockwise from true north. */
  val bearingDegrees: Double,
)

/**
 * Walks a track by distance rather than by point index.
 *
 * This is the primitive the flyover is built on: an animation moves a camera a
 * fixed distance per second, but track points are not evenly spaced, so stepping
 * through the point array would make the camera lurch on dense sections, sprint on
 * sparse ones, and move at a different speed on every recording.
 *
 * Gaps are honoured. Distance never crosses a segment boundary, which is what lets
 * the reveal animation stop at a gap instead of drawing a line across it.
 */
class TrackSampler private constructor(
  private val segments: List<List<TrackPoint>>,
  private val cumulativeBySegment: List<DoubleArray>,
  private val segmentStartDistances: DoubleArray,
  val totalDistanceMeters: Double,
) {

  val segmentCount: Int get() = segments.size

  /**
   * The point [distanceMeters] along the track, clamped to the track's extent.
   *
   * Position and bearing are interpolated on the geodesic between the two samples
   * either side; elevation is interpolated linearly, which is exact enough between
   * samples a second apart and avoids inventing a curve the data does not support.
   */
  fun sampleAt(distanceMeters: Double): TrackSample {
    val distance = distanceMeters.coerceIn(0.0, totalDistanceMeters)
    val segmentIndex = segmentIndexFor(distance)
    val points = segments[segmentIndex]
    val cumulative = cumulativeBySegment[segmentIndex]
    val localDistance = distance - segmentStartDistances[segmentIndex]

    // A segment of one point, or one whose points are all identical, has no leg to
    // interpolate along.
    if (points.size == 1 || cumulative.last() <= 0.0) {
      return TrackSample(
        position = points.first().position,
        elevationMeters = points.first().elevationMeters,
        distanceMeters = distance,
        segmentIndex = segmentIndex,
        bearingDegrees = 0.0,
      )
    }

    val legIndex = legIndexFor(cumulative, localDistance)
    val start = points[legIndex]
    val end = points[legIndex + 1]
    val legStart = cumulative[legIndex]
    val legLength = cumulative[legIndex + 1] - legStart
    val fraction = if (legLength > 0.0) {
      ((localDistance - legStart) / legLength).coerceIn(0.0, 1.0)
    } else {
      0.0
    }

    return TrackSample(
      position = Geodesic.interpolate(start.position, end.position, fraction),
      elevationMeters = interpolateElevation(start.elevationMeters, end.elevationMeters, fraction),
      distanceMeters = distance,
      segmentIndex = segmentIndex,
      bearingDegrees = Geodesic.initialBearingDegrees(start.position, end.position),
    )
  }

  /**
   * The point [fraction] of the way along the track, where 0 is the start and 1 the end.
   * Note this is fraction of *distance*, not of point count.
   */
  fun sampleAtFraction(fraction: Double): TrackSample =
    sampleAt(fraction.coerceIn(0.0, 1.0) * totalDistanceMeters)

  private fun segmentIndexFor(distance: Double): Int {
    var index = 0
    for (candidate in 1 until segments.size) {
      if (segmentStartDistances[candidate] <= distance) index = candidate else break
    }
    return index
  }

  /** Last leg whose start is at or before [localDistance], skipping zero-length legs. */
  private fun legIndexFor(cumulative: DoubleArray, localDistance: Double): Int {
    var index = 0
    for (candidate in 1 until cumulative.size - 1) {
      if (cumulative[candidate] <= localDistance) index = candidate else break
    }
    return index
  }

  private fun interpolateElevation(start: Double?, end: Double?, fraction: Double): Double? = when {
    start != null && end != null -> start + (end - start) * fraction
    start != null -> start
    else -> end
  }

  companion object {
    /** Null when the track has no geometry to walk, which is how callers detect "nothing to play". */
    fun of(track: Track): TrackSampler? {
      val segments = track.segments.map { it.points }.filter { it.isNotEmpty() }
      if (segments.isEmpty()) return null

      val cumulativeBySegment = segments.map { points ->
        val cumulative = DoubleArray(points.size)
        for (index in 1 until points.size) {
          cumulative[index] = cumulative[index - 1] +
            Geodesic.distanceMeters(points[index - 1].position, points[index].position)
        }
        cumulative
      }

      val segmentStartDistances = DoubleArray(segments.size)
      for (index in 1 until segments.size) {
        segmentStartDistances[index] =
          segmentStartDistances[index - 1] + cumulativeBySegment[index - 1].last()
      }

      val total = segmentStartDistances.last() + cumulativeBySegment.last().last()

      return TrackSampler(segments, cumulativeBySegment, segmentStartDistances, total)
    }
  }
}
