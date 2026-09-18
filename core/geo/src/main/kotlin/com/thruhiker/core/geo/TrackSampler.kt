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
 *
 * There are two distance axes. [totalDistanceMeters] / [sampleAt] measure what was
 * walked, and stay put across a gap. [totalFlightDistanceMeters] / [sampleAtFlight]
 * count the unrecorded stretch between two segments as ground the camera still has to
 * cover, so a flyover crosses it in a straight line instead of teleporting. Anything
 * that talks about the walk (mileage, revealed fraction, statistics) belongs on the
 * first axis; anything that moves a camera belongs on the second.
 */
class TrackSampler private constructor(
  private val segments: List<List<TrackPoint>>,
  private val cumulativeBySegment: List<DoubleArray>,
  private val segmentStartDistances: DoubleArray,
  /** Straight-line distance of the unrecorded stretch joining segment [i-1]'s end to segment [i]'s start. */
  private val gapBeforeSegment: DoubleArray,
  /** Distance along the flight axis at which each segment starts, gaps included. */
  private val flightStartDistances: DoubleArray,
  val totalDistanceMeters: Double,
  val totalFlightDistanceMeters: Double,
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

  /**
   * The point [distanceMeters] along the flight axis, clamped to the route's extent.
   *
   * Inside a segment this is [sampleAt] plus the gaps that came before it. Across an
   * unrecorded stretch it walks the straight line between where one recording stopped
   * and the next began, so a camera moving a fixed distance per second keeps moving at
   * a fixed speed through the gap rather than jumping it in a single frame.
   *
   * The unrecorded stretch is not part of the walk, so its samples keep reporting the
   * walking distance and segment reached at the end of the previous recording, and its
   * elevation is interpolated between the two recorded ends. Nothing is drawn there:
   * this axis exists to move a camera, not to describe the route.
   */
  fun sampleAtFlight(distanceMeters: Double): TrackSample {
    val distance = distanceMeters.coerceIn(0.0, totalFlightDistanceMeters)
    val segmentIndex = flightSegmentIndexFor(distance)

    if (distance < flightStartDistances[segmentIndex]) {
      val previous = segments[segmentIndex - 1].last()
      val next = segments[segmentIndex].first()
      val gap = gapBeforeSegment[segmentIndex]
      val fraction = if (gap > 0.0) {
        ((distance - (flightStartDistances[segmentIndex] - gap)) / gap).coerceIn(0.0, 1.0)
      } else {
        0.0
      }
      return TrackSample(
        position = Geodesic.interpolate(previous.position, next.position, fraction),
        elevationMeters = interpolateElevation(
          previous.elevationMeters,
          next.elevationMeters,
          fraction,
        ),
        distanceMeters = segmentStartDistances[segmentIndex],
        segmentIndex = segmentIndex - 1,
        bearingDegrees = Geodesic.initialBearingDegrees(previous.position, next.position),
      )
    }

    // Inside a segment the two axes differ only by the gaps already crossed.
    val walked = segmentStartDistances[segmentIndex] +
      (distance - flightStartDistances[segmentIndex])
    return sampleAt(walked)
  }

  /**
   * How far the walker had got at [flightDistanceMeters]. Holds at the end of the last
   * recording while the camera crosses the unrecorded stretch to the next one.
   */
  fun walkedDistanceAtFlight(flightDistanceMeters: Double): Double {
    val distance = flightDistanceMeters.coerceIn(0.0, totalFlightDistanceMeters)
    val segmentIndex = flightSegmentIndexFor(distance)
    if (distance < flightStartDistances[segmentIndex]) return segmentStartDistances[segmentIndex]
    return (segmentStartDistances[segmentIndex] + (distance - flightStartDistances[segmentIndex]))
      .coerceAtMost(totalDistanceMeters)
  }

  /**
   * The segment [distance] belongs to on the flight axis, counting the unrecorded
   * stretch *before* a segment as part of that segment. Rounding down instead would
   * leave the gap unclaimed, and a distance inside it would fall through to the
   * walking axis and jump the gap in a single frame.
   */
  private fun flightSegmentIndexFor(distance: Double): Int {
    for (index in 0 until segments.size - 1) {
      if (distance < flightStartDistances[index] + cumulativeBySegment[index].last()) return index
    }
    return segments.size - 1
  }

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

      // An unrecorded stretch is a straight line between the two recordings either side
      // of it: that is the best guess available about ground nobody logged.
      val gapBeforeSegment = DoubleArray(segments.size)
      for (index in 1 until segments.size) {
        gapBeforeSegment[index] = Geodesic.distanceMeters(
          segments[index - 1].last().position,
          segments[index].first().position,
        )
      }

      val flightStartDistances = DoubleArray(segments.size)
      for (index in 1 until segments.size) {
        flightStartDistances[index] = flightStartDistances[index - 1] +
          cumulativeBySegment[index - 1].last() +
          gapBeforeSegment[index]
      }
      val totalFlight = flightStartDistances.last() + cumulativeBySegment.last().last()

      return TrackSampler(
        segments = segments,
        cumulativeBySegment = cumulativeBySegment,
        segmentStartDistances = segmentStartDistances,
        gapBeforeSegment = gapBeforeSegment,
        flightStartDistances = flightStartDistances,
        totalDistanceMeters = total,
        totalFlightDistanceMeters = totalFlight,
      )
    }
  }
}
