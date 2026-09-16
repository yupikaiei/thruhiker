package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Fast planar approximation of the ellipsoid, valid over the small extents that
 * simplification, snapping and screen-space geometry operate on.
 *
 * Uses the standard WGS84 local scale factors, which are accurate to a few parts
 * per million at mid latitudes and far cheaper than a geodesic solve.
 */
object LocalMetric {

  /** Metres per degree of latitude at the given latitude. */
  fun metersPerDegreeLatitude(atLatitudeDegrees: Double): Double {
    val phi = Math.toRadians(atLatitudeDegrees)
    return 111_132.92 - 559.82 * cos(2 * phi) + 1.175 * cos(4 * phi) - 0.0023 * cos(6 * phi)
  }

  /** Metres per degree of longitude at the given latitude. */
  fun metersPerDegreeLongitude(atLatitudeDegrees: Double): Double {
    val phi = Math.toRadians(atLatitudeDegrees)
    return 111_412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
  }

  /** Planar distance between two nearby points, in metres. */
  fun distanceMeters(from: LatLng, to: LatLng): Double {
    val latitude = (from.latitude + to.latitude) / 2
    val dy = (to.latitude - from.latitude) * metersPerDegreeLatitude(latitude)
    val dx = Geodesic.normalizeLongitudeDelta(to.longitude - from.longitude) *
      metersPerDegreeLongitude(latitude)
    return hypot(dx, dy)
  }

  /**
   * Deviation of [point] from the planar segment [start] -> [end].
   *
   * [perpendicularMeters] is the shortest distance to the segment ([start], >[end]
   * are handled by clamping the projection, so this is never a distance to the
   * infinite line). [alongSegmentFraction] is where the projection falls, clamped
   * to `[0, 1]`, which callers use to interpolate elevation across the segment.
   */
  data class Deviation(
    val perpendicularMeters: Double,
    val alongSegmentFraction: Double,
  )

  fun deviationFromSegment(start: LatLng, end: LatLng, point: LatLng): Deviation {
    val latitude = (start.latitude + end.latitude + point.latitude) / 3
    val scaleLat = metersPerDegreeLatitude(latitude)
    val scaleLon = metersPerDegreeLongitude(latitude)

    val ax = 0.0
    val ay = 0.0
    val bx = Geodesic.normalizeLongitudeDelta(end.longitude - start.longitude) * scaleLon
    val by = (end.latitude - start.latitude) * scaleLat
    val px = Geodesic.normalizeLongitudeDelta(point.longitude - start.longitude) * scaleLon
    val py = (point.latitude - start.latitude) * scaleLat

    val segmentLengthSquared = bx * bx + by * by
    if (segmentLengthSquared == 0.0) {
      return Deviation(sqrt(px * px + py * py), 0.0)
    }

    val rawFraction = (px * bx + py * by) / segmentLengthSquared
    val fraction = rawFraction.coerceIn(0.0, 1.0)

    val closestX = bx * fraction
    val closestY = by * fraction
    val perpendicular = hypot(px - closestX, py - closestY)

    return Deviation(perpendicular, fraction)
  }
}
