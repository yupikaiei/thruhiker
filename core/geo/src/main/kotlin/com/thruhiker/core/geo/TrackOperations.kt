package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import kotlin.math.ceil

/**
 * Edits a planner applies to a route: reverse it, merge several files into one, or turn a
 * handful of tapped waypoints into a track that can be measured and flown.
 *
 * All of these are geometry operations on immutable values, so a planner screen can hold a
 * route, show the edit, and undo by keeping the previous value.
 */
object TrackOperations {

  /**
   * Spacing used when a route has to be turned into points.
   *
   * Point spacing is a trade-off between fidelity and size: 25 m is about the resolution of a
   * phone screen at trail zoom, and it keeps a 40 km planned route to a few thousand points
   * rather than the tens of thousands a 1 Hz recording of the same walk would produce.
   */
  const val DEFAULT_SPACING_METERS = 25.0

  /**
   * Reverses a track, keeping timestamps chronological.
   *
   * Naively reversing the points leaves a track whose timestamps run backwards, which every
   * downstream calculation — elapsed time, moving time, the flyover's own no-op — reads as
   * nonsense. When every point is timed, each timestamp is mirrored across the recording's
   * start and end, so the reversed route still starts where the original started and ends
   * where it ended. Points without times are simply reversed.
   */
  fun reverse(track: Track): Track = Track(
    track.segments.reversed().map { segment -> TrackSegment(reversePoints(segment.points)) },
  )

  /**
   * Concatenates tracks into one route.
   *
   * The segments are kept as segments rather than fused into one run. Two files that join up
   * will look continuous either way, and two that do not must not be drawn as if they did:
   * the gap is exactly the information a hiker merging a trip's daily files needs to see.
   */
  fun merge(tracks: List<Track>): Track =
    Track(tracks.flatMap { it.segments }.filter { !it.isEmpty })

  /**
   * Inserts samples so that no two consecutive ones are further apart than
   * [maximumSpacingMeters], interpolating positions on the geodesic and elevations linearly.
   *
   * A route built from tapped waypoints is a handful of straight legs, and a straight leg
   * between two taps can be twenty kilometres long. Measuring that as one leg is fine;
   * drawing it, colouring it by gradient and flying a camera along it are not, because there
   * is nowhere for the detail to live. Densifying gives all three something to work with.
   *
   * Elevation rides along because it is the entire vertical profile of a route, and a route
   * imported from a GPX file has one worth keeping. A leg with elevation at one end and not
   * the other produces samples with none, rather than inventing a slope.
   *
   * Timestamps are deliberately not interpolated: an invented time is worse than an absent
   * one, and nothing downstream needs a time on a route that has never been walked.
   */
  fun densify(
    points: List<TrackPoint>,
    maximumSpacingMeters: Double = DEFAULT_SPACING_METERS,
  ): List<TrackPoint> {
    require(maximumSpacingMeters > 0.0) { "Spacing must be positive: $maximumSpacingMeters" }
    if (points.size < 2) return points

    val result = ArrayList<TrackPoint>(points.size * 2)
    result.add(points.first())

    for (index in 1 until points.size) {
      val start = points[index - 1]
      val end = points[index]
      val legLength = Geodesic.distanceMeters(start.position, end.position)

      // A repeated tap, or two samples snapped to the same place: nothing to interpolate.
      if (legLength <= 0.0) continue

      val steps = ceil(legLength / maximumSpacingMeters).toInt().coerceAtLeast(1)
      for (step in 1..steps) {
        val fraction = step.toDouble() / steps
        result.add(
          TrackPoint(
            position = Geodesic.interpolate(start.position, end.position, fraction),
            elevationMeters = interpolate(start.elevationMeters, end.elevationMeters, fraction),
          ),
        )
      }
    }

    return result
  }

  /** Positions only, for callers with no vertical profile to carry. */
  fun densifyPositions(
    points: List<LatLng>,
    maximumSpacingMeters: Double = DEFAULT_SPACING_METERS,
  ): List<LatLng> =
    densify(points.map { TrackPoint(it) }, maximumSpacingMeters).map { it.position }

  /** Turns waypoints into a metric track, carrying whatever elevation they have. */
  fun fromWaypoints(
    waypoints: List<TrackPoint>,
    maximumSpacingMeters: Double = DEFAULT_SPACING_METERS,
  ): Track = Track.of(densify(waypoints, maximumSpacingMeters))

  /** Convenience for planners whose waypoints are bare coordinates. */
  fun fromPositions(
    waypoints: List<LatLng>,
    maximumSpacingMeters: Double = DEFAULT_SPACING_METERS,
  ): Track = fromWaypoints(waypoints.map { TrackPoint(it) }, maximumSpacingMeters)

  /** Turns waypoints into a closed loop, returning to the first one. */
  fun closedLoop(
    waypoints: List<LatLng>,
    maximumSpacingMeters: Double = DEFAULT_SPACING_METERS,
  ): Track {
    if (waypoints.size < 2) return fromPositions(waypoints, maximumSpacingMeters)
    return fromPositions(waypoints + waypoints.first(), maximumSpacingMeters)
  }

  private fun interpolate(start: Double?, end: Double?, fraction: Double): Double? =
    if (start != null && end != null) start + (end - start) * fraction else null

  private fun reversePoints(points: List<TrackPoint>): List<TrackPoint> {
    if (points.size < 2) return points.reversed()

    if (points.any { it.timeMillis == null }) return points.reversed()

    val first = points.first().timeMillis ?: return points.reversed()
    val last = points.last().timeMillis ?: return points.reversed()

    return points.reversed().map { point ->
      val time = point.timeMillis ?: return@map point
      point.copy(timeMillis = first + (last - time))
    }
  }
}
