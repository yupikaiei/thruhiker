package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Geodesic maths on the WGS84 ellipsoid.
 *
 * The primary algorithm is Vincenty's inverse formula, which converges to
 * sub-millimetre accuracy for all but near-antipodal point pairs. Those
 * degenerate pairs are detected via non-convergence and fall back to a
 * spherical (haversine) solution, so callers never observe a failed computation.
 *
 * This is a thru-hiker's distance: over a 4,000 km trail the difference between
 * ellipsoidal and spherical distance is tens of kilometres, which is the
 * difference between a resupply plan that works and one that does not.
 */
object Geodesic {

  const val SEMI_MAJOR_AXIS_METERS = 6_378_137.0
  const val FLATTENING = 1.0 / 298.257223563
  const val SEMI_MINOR_AXIS_METERS = 6_356_752.314245179
  const val MEAN_RADIUS_METERS = 6_371_008.8

  /**
   * Convergence is judged relative to the magnitude of the angle being solved,
   * with a tiny absolute floor so near-zero angles still terminate.
   *
   * This matters at short range. An absolute tolerance of 1e-12 rad on a one
   * metre leg is a *relative* error of about 6e-6, which becomes centimetre-scale
   * drift once it is applied across thousands of GPS samples. Relative
   * convergence keeps the error flat over nine orders of magnitude of distance.
   */
  private const val RELATIVE_CONVERGENCE = 1e-13
  private const val ABSOLUTE_CONVERGENCE = 1e-15
  private const val MAX_ITERATIONS = 200

  /** Inverse geodesic solution. Bearings are degrees clockwise from true north. */
  data class InverseResult(
    val distanceMeters: Double,
    val initialBearingDegrees: Double,
    val finalBearingDegrees: Double,
  )

  /** Ellipsoidal distance between two points, in metres. */
  fun distanceMeters(from: LatLng, to: LatLng): Double = inverse(from, to).distanceMeters

  /** Forward azimuth at [from], in degrees clockwise from true north. */
  fun initialBearingDegrees(from: LatLng, to: LatLng): Double =
    inverse(from, to).initialBearingDegrees

  /** Back azimuth arriving at [to], in degrees clockwise from true north. */
  fun finalBearingDegrees(from: LatLng, to: LatLng): Double =
    inverse(from, to).finalBearingDegrees

  fun inverse(from: LatLng, to: LatLng): InverseResult {
    require(from.isValid) { "Invalid origin: $from" }
    require(to.isValid) { "Invalid destination: $to" }

    if (from.latitude == to.latitude && from.longitude == to.longitude) {
      return InverseResult(0.0, 0.0, 0.0)
    }

    val a = SEMI_MAJOR_AXIS_METERS
    val b = SEMI_MINOR_AXIS_METERS
    val f = FLATTENING

    val deltaLambda = Math.toRadians(normalizeLongitudeDelta(to.longitude - from.longitude))
    val u1 = atan((1 - f) * tan(Math.toRadians(from.latitude)))
    val u2 = atan((1 - f) * tan(Math.toRadians(to.latitude)))

    val sinU1 = sin(u1)
    val cosU1 = cos(u1)
    val sinU2 = sin(u2)
    val cosU2 = cos(u2)

    var lambda = deltaLambda
    var iteration = 0
    var converged = false
    var sigma = 0.0
    var sinSigma = 0.0
    var cosSigma = 0.0
    var cosSqAlpha = 0.0
    var cos2SigmaM = 0.0

    while (!converged && iteration < MAX_ITERATIONS) {
      val sinLambda = sin(lambda)
      val cosLambda = cos(lambda)

      val term1 = cosU2 * sinLambda
      val term2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
      sinSigma = sqrt(term1 * term1 + term2 * term2)

      // Coincident points on the equator-inclusive degenerate case.
      if (sinSigma == 0.0) return InverseResult(0.0, 0.0, 0.0)

      cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
      sigma = atan2(sinSigma, cosSigma)

      val sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
      cosSqAlpha = 1 - sinAlpha * sinAlpha
      cos2SigmaM = if (cosSqAlpha == 0.0) {
        0.0 // Equatorial line: cos²α is zero and this term is defined as zero.
      } else {
        cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha
      }

      val c = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha))
      val previousLambda = lambda
      lambda = deltaLambda + (1 - c) * f * sinAlpha *
        (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))

      converged = hasConverged(previousLambda, lambda)
      iteration++
    }

    if (!converged) return sphericalInverse(from, to)

    val uSq = cosSqAlpha * (a * a - b * b) / (b * b)
    val bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
    val bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))

    val deltaSigma = bigB * sinSigma * (
      cos2SigmaM + bigB / 4 * (
        cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
          bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) *
          (-3 + 4 * cos2SigmaM * cos2SigmaM)
        )
      )

    val sinLambda = sin(lambda)
    val cosLambda = cos(lambda)

    val initialBearing = atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
    val finalBearing = atan2(cosU1 * sinLambda, -sinU1 * cosU2 + cosU1 * sinU2 * cosLambda)

    return InverseResult(
      distanceMeters = b * bigA * (sigma - deltaSigma),
      initialBearingDegrees = normalizeDegrees(Math.toDegrees(initialBearing)),
      finalBearingDegrees = normalizeDegrees(Math.toDegrees(finalBearing)),
    )
  }

  /**
   * Projects [distanceMeters] from [from] along [initialBearingDegrees] using
   * Vincenty's direct formula.
   */
  fun destination(from: LatLng, initialBearingDegrees: Double, distanceMeters: Double): LatLng {
    require(from.isValid) { "Invalid origin: $from" }
    require(distanceMeters >= 0.0) { "Distance must not be negative: $distanceMeters" }
    if (distanceMeters == 0.0) return from

    val a = SEMI_MAJOR_AXIS_METERS
    val b = SEMI_MINOR_AXIS_METERS
    val f = FLATTENING

    val alpha1 = Math.toRadians(normalizeDegrees(initialBearingDegrees))
    val sinAlpha1 = sin(alpha1)
    val cosAlpha1 = cos(alpha1)

    val tanU1 = (1 - f) * tan(Math.toRadians(from.latitude))
    val cosU1 = 1 / sqrt(1 + tanU1 * tanU1)
    val sinU1 = tanU1 * cosU1
    val sigma1 = atan2(tanU1, cosAlpha1)

    val sinAlpha = cosU1 * sinAlpha1
    val cosSqAlpha = 1 - sinAlpha * sinAlpha
    val uSq = cosSqAlpha * (a * a - b * b) / (b * b)
    val bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
    val bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))

    var sigma = distanceMeters / (b * bigA)
    var iteration = 0
    var deltaSigma: Double
    do {
      val cos2SigmaM = cos(2 * sigma1 + sigma)
      val sinSigma = sin(sigma)
      val cosSigma = cos(sigma)
      deltaSigma = bigB * sinSigma * (
        cos2SigmaM + bigB / 4 * (
          cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
            bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) *
            (-3 + 4 * cos2SigmaM * cos2SigmaM)
          )
        )
      val previousSigma = sigma
      sigma = distanceMeters / (b * bigA) + deltaSigma
      iteration++
      if (iteration > MAX_ITERATIONS) break
      if (hasConverged(previousSigma, sigma)) break
    } while (true)

    val cos2SigmaM = cos(2 * sigma1 + sigma)
    val sinSigma = sin(sigma)
    val cosSigma = cos(sigma)

    val tmp = sinU1 * sinSigma - cosU1 * cosSigma * cosAlpha1
    val latitude = atan2(
      sinU1 * cosSigma + cosU1 * sinSigma * cosAlpha1,
      (1 - f) * sqrt(sinAlpha * sinAlpha + tmp * tmp),
    )

    val lambda = atan2(sinSigma * sinAlpha1, cosU1 * cosSigma - sinU1 * sinSigma * cosAlpha1)
    val c = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha))
    val l = lambda - (1 - c) * f * sinAlpha *
      (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))

    return LatLng(
      latitude = Math.toDegrees(latitude),
      longitude = normalizeLongitude(from.longitude + Math.toDegrees(l)),
    )
  }

  /**
   * Position a given fraction of the way from [from] to [to].
   *
   * Interpolates on the sphere, which is exact enough for camera moves and
   * animation and cannot fail the way an iterative ellipsoidal solve can.
   */
  fun interpolate(from: LatLng, to: LatLng, fraction: Double): LatLng {
    require(from.isValid && to.isValid) { "Invalid endpoints: $from -> $to" }
    val t = fraction.coerceIn(0.0, 1.0)
    if (t == 0.0) return from
    if (t == 1.0) return to

    val lat1 = Math.toRadians(from.latitude)
    val lon1 = Math.toRadians(from.longitude)
    val lat2 = Math.toRadians(to.latitude)
    val lon2 = Math.toRadians(to.longitude)

    val x1 = cos(lat1) * cos(lon1)
    val y1 = cos(lat1) * sin(lon1)
    val z1 = sin(lat1)
    val x2 = cos(lat2) * cos(lon2)
    val y2 = cos(lat2) * sin(lon2)
    val z2 = sin(lat2)

    val dot = (x1 * x2 + y1 * y2 + z1 * z2).coerceIn(-1.0, 1.0)
    val omega = acos(dot)
    if (omega < 1e-12) return from

    val sinOmega = sin(omega)
    val scale1 = sin((1 - t) * omega) / sinOmega
    val scale2 = sin(t * omega) / sinOmega

    val x = x1 * scale1 + x2 * scale2
    val y = y1 * scale1 + y2 * scale2
    val z = z1 * scale1 + z2 * scale2

    return LatLng(
      latitude = Math.toDegrees(atan2(z, sqrt(x * x + y * y))),
      longitude = Math.toDegrees(atan2(y, x)),
    )
  }

  /**
   * Running distance at each point, where element `i` is the distance from
   * point 0 to point `i`. The returned array always has the same size as
   * [points], with a leading zero.
   */
  fun cumulativeDistancesMeters(points: List<LatLng>): DoubleArray {
    val distances = DoubleArray(points.size)
    for (i in 1 until points.size) {
      distances[i] = distances[i - 1] + distanceMeters(points[i - 1], points[i])
    }
    return distances
  }

  /** Total path length through an ordered list of points, in metres. */
  fun pathLengthMeters(points: List<LatLng>): Double {
    var total = 0.0
    for (i in 1 until points.size) {
      total += distanceMeters(points[i - 1], points[i])
    }
    return total
  }

  /** Wraps degrees into `[0, 360)`. */
  fun normalizeDegrees(degrees: Double): Double {
    val wrapped = degrees % 360.0
    return if (wrapped < 0) wrapped + 360.0 else wrapped
  }

  /** Wraps longitude into `(-180, 180]`. */
  fun normalizeLongitude(longitude: Double): Double {
    var value = longitude
    while (value <= -180.0) value += 360.0
    while (value > 180.0) value -= 360.0
    return value
  }

  /** Smallest signed difference between two longitudes, accounting for the antimeridian. */
  fun normalizeLongitudeDelta(delta: Double): Double {
    var value = delta
    while (value > 180.0) value -= 360.0
    while (value < -180.0) value += 360.0
    return value
  }

  /** True when [current] has stopped moving meaningfully relative to its own magnitude. */
  private fun hasConverged(previous: Double, current: Double): Boolean {
    val delta = abs(current - previous)
    return delta <= ABSOLUTE_CONVERGENCE + RELATIVE_CONVERGENCE * abs(current)
  }

  private fun sphericalInverse(from: LatLng, to: LatLng): InverseResult {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLat = lat2 - lat1
    val deltaLon = Math.toRadians(normalizeLongitudeDelta(to.longitude - from.longitude))

    val sinHalfLat = sin(deltaLat / 2)
    val sinHalfLon = sin(deltaLon / 2)
    val h = sinHalfLat * sinHalfLat + cos(lat1) * cos(lat2) * sinHalfLon * sinHalfLon
    val distance = 2 * MEAN_RADIUS_METERS * asin(min(1.0, sqrt(h)))

    val y = sin(deltaLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
    val bearing = normalizeDegrees(Math.toDegrees(atan2(y, x)))

    return InverseResult(distance, bearing, bearing)
  }
}
