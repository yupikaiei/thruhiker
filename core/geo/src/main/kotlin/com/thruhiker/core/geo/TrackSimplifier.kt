package com.thruhiker.core.geo

import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * Douglas-Peucker simplification that respects both the horizontal shape and the
 * vertical profile of a track.
 *
 * A plain 2D Douglas-Peucker run over latitude/longitude will happily delete the
 * apex of a switchback climb, because the apex is horizontally close to the line
 * joining its neighbours. The result is a track whose map shape looks right but
 * whose elevation profile is a lie, which breaks climb detection, day splitting
 * and time estimates.
 *
 * A sample is therefore kept when it deviates beyond *either* tolerance. The two
 * tolerances are normalised into a single score so that subdivision always
 * targets the worst offender of either kind.
 */
object TrackSimplifier {

  /** 5 m is roughly the noise floor of consumer GNSS, so finer detail is not real. */
  const val DEFAULT_HORIZONTAL_TOLERANCE_METERS = 5.0

  /** Matches the elevation hysteresis threshold used for gain/loss. */
  const val DEFAULT_VERTICAL_TOLERANCE_METERS = 3.0

  fun simplify(
    points: List<TrackPoint>,
    horizontalToleranceMeters: Double = DEFAULT_HORIZONTAL_TOLERANCE_METERS,
    verticalToleranceMeters: Double = DEFAULT_VERTICAL_TOLERANCE_METERS,
  ): List<TrackPoint> {
    require(horizontalToleranceMeters >= 0.0) {
      "Horizontal tolerance must not be negative: $horizontalToleranceMeters"
    }
    require(verticalToleranceMeters >= 0.0) {
      "Vertical tolerance must not be negative: $verticalToleranceMeters"
    }

    if (points.size <= 2) return points

    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true

    // Explicit stack rather than recursion: a 100k-point day hike would otherwise
    // risk exhausting the call stack.
    val pending = ArrayDeque<Int>()
    pending.addLast(0)
    pending.addLast(points.size - 1)

    while (pending.isNotEmpty()) {
      val end = pending.removeLast()
      val start = pending.removeLast()
      if (end - start < 2) continue

      var worstIndex = -1
      var worstScore = 1.0 // Only samples exceeding their tolerance are retained.

      for (index in start + 1 until end) {
        val score = normalizedDeviation(
          start = points[start],
          end = points[end],
          point = points[index],
          horizontalToleranceMeters = horizontalToleranceMeters,
          verticalToleranceMeters = verticalToleranceMeters,
        )
        if (score > worstScore) {
          worstScore = score
          worstIndex = index
        }
      }

      if (worstIndex != -1) {
        keep[worstIndex] = true
        pending.addLast(start)
        pending.addLast(worstIndex)
        pending.addLast(worstIndex)
        pending.addLast(end)
      }
    }

    return points.filterIndexed { index, _ -> keep[index] }
  }

  fun simplify(
    track: Track,
    horizontalToleranceMeters: Double = DEFAULT_HORIZONTAL_TOLERANCE_METERS,
    verticalToleranceMeters: Double = DEFAULT_VERTICAL_TOLERANCE_METERS,
  ): Track = Track(
    simplify(track.points, horizontalToleranceMeters, verticalToleranceMeters),
  )

  private fun normalizedDeviation(
    start: TrackPoint,
    end: TrackPoint,
    point: TrackPoint,
    horizontalToleranceMeters: Double,
    verticalToleranceMeters: Double,
  ): Double {
    val deviation = LocalMetric.deviationFromSegment(start.position, end.position, point.position)

    var score = deviationRatio(deviation.perpendicularMeters, horizontalToleranceMeters)

    val startElevation = start.elevationMeters
    val endElevation = end.elevationMeters
    val pointElevation = point.elevationMeters

    if (startElevation != null && endElevation != null && pointElevation != null) {
      val interpolated = startElevation +
        (endElevation - startElevation) * deviation.alongSegmentFraction
      score = max(
        score,
        deviationRatio(abs(pointElevation - interpolated), verticalToleranceMeters),
      )
    }

    return score
  }

  /**
   * Deviation expressed as a multiple of its tolerance.
   *
   * A tolerance of zero means "do not simplify": any non-zero deviation then
   * scores as infinite so the sample is always retained.
   */
  private fun deviationRatio(deviation: Double, toleranceMeters: Double): Double = when {
    toleranceMeters > 0.0 -> deviation / toleranceMeters
    deviation > 0.0 -> Double.MAX_VALUE
    else -> 0.0
  }
}
