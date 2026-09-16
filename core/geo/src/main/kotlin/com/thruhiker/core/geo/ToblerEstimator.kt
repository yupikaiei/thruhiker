package com.thruhiker.core.geo

import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import kotlin.math.abs
import kotlin.math.exp

/**
 * Walking-time estimates based on Tobler's hiking function.
 *
 * Tobler's function is an empirical fit to human walking speed against grade and
 * is the basis for most serious route-time estimates. Its useful property for
 * thru-hike planning is that it is not monotonic in the way a naive "flat speed
 * plus penalty" model is: the fastest walking happens on a *slight descent*
 * (~ -5% grade), and speed falls off asymmetrically either side of that.
 *
 * This matters on a 4,000 km trail. Using flat-speed arithmetic to model the
 * Sierra or the Whites produces day lengths that are wrong by several hours,
 * and a wrong day length cascades straight into a wrong resupply plan.
 */
object ToblerEstimator {

  /** Speed on the optimal grade, matching Tobler's fitted constant. */
  const val MAX_SPEED_KMH = 6.0

  /** Floor applied to degenerate grades so vertical segments can't produce infinite time. */
  const val MIN_SPEED_KMH = 0.05

  private const val GRADE_OFFSET = 0.05
  private const val DECAY = 3.5
  private const val METERS_PER_SECOND_PER_KMH = 1000.0 / 3600.0

  /**
   * Walking speed in km/h for a [grade] expressed as rise over run
   * (positive is uphill, e.g. `0.1` is a 10% climb).
   */
  fun speedKmh(grade: Double): Double {
    require(grade.isFinite()) { "Grade must be finite: $grade" }
    val speed = MAX_SPEED_KMH * exp(-DECAY * abs(grade + GRADE_OFFSET))
    return speed.coerceAtLeast(MIN_SPEED_KMH)
  }

  /** Walking speed in metres per second for a [grade] expressed as rise over run. */
  fun speedMetersPerSecond(grade: Double): Double = speedKmh(grade) * METERS_PER_SECOND_PER_KMH

  /**
   * Time in seconds to cover a segment of [horizontalDistanceMeters] with a
   * vertical change of [elevationDeltaMeters].
   */
  fun hikingSeconds(horizontalDistanceMeters: Double, elevationDeltaMeters: Double): Double {
    if (horizontalDistanceMeters <= 0.0) return 0.0
    val grade = elevationDeltaMeters / horizontalDistanceMeters
    return horizontalDistanceMeters / speedMetersPerSecond(grade)
  }

  /**
   * Total walking time in seconds for an ordered track, summing the estimate for
   * each consecutive pair. Segments without elevation data are treated as flat.
   */
  fun hikingSeconds(points: List<TrackPoint>): Double {
    var total = 0.0
    for (index in 1 until points.size) {
      val previous = points[index - 1]
      val current = points[index]
      val distance = Geodesic.distanceMeters(previous.position, current.position)
      val previousElevation = previous.elevationMeters
      val currentElevation = current.elevationMeters
      val delta = if (previousElevation != null && currentElevation != null) {
        currentElevation - previousElevation
      } else {
        0.0
      }
      total += hikingSeconds(distance, delta)
    }
    return total
  }

  /** Walking time for a whole track, summed segment by segment. */
  fun hikingSeconds(track: Track): Double =
    track.segments.sumOf { hikingSeconds(it.points) }
}
