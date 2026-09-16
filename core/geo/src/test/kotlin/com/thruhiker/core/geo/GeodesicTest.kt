package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeodesicTest {

  private val alps = LatLng(46.5, 7.9)

  @Test
  fun `coincident points report zero distance and bearing`() {
    val result = Geodesic.inverse(alps, alps)
    assertEquals(0.0, result.distanceMeters, 0.0)
    assertEquals(0.0, result.initialBearingDegrees, 0.0)
  }

  /**
   * One degree of longitude on the equator is `a * pi / 180` by definition of the
   * semi-major axis, so this pins the implementation to WGS84 rather than to a
   * spherical approximation.
   */
  @Test
  fun `one degree of longitude on the equator matches the WGS84 equatorial degree`() {
    val distance = Geodesic.distanceMeters(LatLng(0.0, 0.0), LatLng(0.0, 1.0))
    val expected = Geodesic.SEMI_MAJOR_AXIS_METERS * Math.PI / 180.0
    assertEquals(expected, distance, 0.01)
    assertEquals(111_319.4908, distance, 0.01)
  }

  /** The WGS84 meridian quadrant is a published constant: 10 001 965.7293 m. */
  @Test
  fun `equator to north pole equals the published meridian quadrant`() {
    val distance = Geodesic.distanceMeters(LatLng(0.0, 0.0), LatLng(90.0, 0.0))
    assertEquals(10_001_965.7293, distance, 0.5)
  }

  @Test
  fun `distance is symmetric`() {
    val a = LatLng(51.5, -0.12)
    val b = LatLng(-33.87, 151.21)
    assertEquals(
      Geodesic.distanceMeters(a, b),
      Geodesic.distanceMeters(b, a),
      1e-6,
    )
  }

  /**
   * Reversing a path reverses both bearings.
   *
   * This compares initial-with-final, not initial-with-initial. The naive claim
   * `initial(a,b) + 180 == initial(b,a)` is simply false on an ellipsoid: a
   * geodesic's departure and arrival bearings differ unless the path happens to
   * be symmetric about a meridian.
   */
  @Test
  fun `reversing a path reverses both bearings`() {
    val a = LatLng(46.5, 7.9)
    val b = LatLng(45.9, 6.87)

    assertEquals(
      Geodesic.normalizeDegrees(Geodesic.finalBearingDegrees(a, b) + 180.0),
      Geodesic.initialBearingDegrees(b, a),
      1e-9,
    )
    assertEquals(
      Geodesic.normalizeDegrees(Geodesic.initialBearingDegrees(a, b) + 180.0),
      Geodesic.finalBearingDegrees(b, a),
      1e-9,
    )
  }

  /**
   * Round-tripping through the direct formula validates the inverse and direct
   * solutions against each other without depending on remembered constants.
   *
   * Tolerances are looser for the one-metre legs than for the 250 km ones on
   * purpose: recovering a one-metre displacement from coordinates 6.4 million
   * metres from the origin costs about seven significant digits to cancellation,
   * so a strictly relative tolerance would be measuring floating-point noise
   * rather than the algorithm.
   */
  @Test
  fun `destination round trips through inverse`() {
    val bearings = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
    val distances = listOf(1.0, 1_000.0, 25_000.0, 250_000.0)

    for (bearing in bearings) {
      for (distance in distances) {
        val projected = Geodesic.destination(alps, bearing, distance)
        val measured = Geodesic.distanceMeters(alps, projected)

        assertEquals(
          "distance round trip failed for bearing=$bearing distance=$distance",
          distance,
          measured,
          distance * 1e-8 + 1e-6,
        )
        assertEquals(
          "bearing round trip failed for bearing=$bearing distance=$distance",
          bearing,
          Geodesic.initialBearingDegrees(alps, projected),
          1e-6,
        )
      }
    }
  }

  @Test
  fun `longitude difference across the antimeridian is short`() {
    val distance = Geodesic.distanceMeters(LatLng(0.0, 179.9), LatLng(0.0, -179.9))
    val expected = Geodesic.SEMI_MAJOR_AXIS_METERS * Math.toRadians(0.2)
    assertEquals(expected, distance, 1.0)
    assertTrue("crossing the antimeridian must not become a near-global trip", distance < 23_000.0)
  }

  @Test
  fun `path over the pole equals twice the distance to the pole`() {
    val toPole = Geodesic.distanceMeters(LatLng(89.0, 0.0), LatLng(90.0, 0.0))
    val across = Geodesic.distanceMeters(LatLng(89.0, 0.0), LatLng(89.0, 180.0))
    assertEquals(2 * toPole, across, 1.0)
  }

  @Test
  fun `near antipodal pairs still produce a plausible half circumference`() {
    val distance = Geodesic.distanceMeters(LatLng(0.0, 0.0), LatLng(0.5, 179.5))
    assertEquals(20_003_931.0, distance, 100_000.0)
  }

  @Test
  fun `interpolate returns the endpoints at the extremes`() {
    val a = LatLng(46.0, 7.0)
    val b = LatLng(47.0, 8.0)
    assertEquals(a, Geodesic.interpolate(a, b, 0.0))
    assertEquals(b, Geodesic.interpolate(a, b, 1.0))
  }

  @Test
  fun `interpolate finds the midpoint along the equator`() {
    val midpoint = Geodesic.interpolate(LatLng(0.0, 0.0), LatLng(0.0, 2.0), 0.5)
    assertEquals(0.0, midpoint.latitude, 1e-9)
    assertEquals(1.0, midpoint.longitude, 1e-9)
  }

  @Test
  fun `interpolate clamps fractions outside the unit interval`() {
    val a = LatLng(46.0, 7.0)
    val b = LatLng(47.0, 8.0)
    assertEquals(a, Geodesic.interpolate(a, b, -3.0))
    assertEquals(b, Geodesic.interpolate(a, b, 4.0))
  }

  @Test
  fun `normalize helpers wrap values into range`() {
    assertEquals(10.0, Geodesic.normalizeDegrees(370.0), 0.0)
    assertEquals(350.0, Geodesic.normalizeDegrees(-10.0), 0.0)
    assertEquals(-170.0, Geodesic.normalizeLongitude(190.0), 0.0)
    assertEquals(170.0, Geodesic.normalizeLongitude(-190.0), 0.0)
    assertEquals(-10.0, Geodesic.normalizeLongitudeDelta(350.0), 0.0)
    assertEquals(10.0, Geodesic.normalizeLongitudeDelta(-350.0), 0.0)
  }

  @Test
  fun `cumulative distances start at zero and accumulate`() {
    val points = listOf(
      LatLng(46.0, 7.0),
      LatLng(46.001, 7.0),
      LatLng(46.002, 7.0),
    )
    val cumulative = Geodesic.cumulativeDistancesMeters(points)
    assertEquals(3, cumulative.size)
    assertEquals(0.0, cumulative[0], 0.0)

    val firstLeg = Geodesic.distanceMeters(points[0], points[1])
    val secondLeg = Geodesic.distanceMeters(points[1], points[2])
    assertEquals(firstLeg, cumulative[1], 1e-6)
    assertEquals(firstLeg + secondLeg, cumulative[2], 1e-6)
    assertEquals(cumulative[2], Geodesic.pathLengthMeters(points), 1e-6)
  }

  @Test
  fun `invalid coordinates are rejected`() {
    val invalid = listOf(
      LatLng(91.0, 0.0),
      LatLng(-91.0, 0.0),
      LatLng(0.0, 181.0),
      LatLng(Double.NaN, 0.0),
    )
    for (coordinate in invalid) {
      assertTrue("$coordinate should be invalid", !coordinate.isValid)
      assertThrows(IllegalArgumentException::class.java) {
        Geodesic.inverse(coordinate, alps)
      }
    }
  }
}
