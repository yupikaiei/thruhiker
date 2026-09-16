package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class ToblerEstimatorTest {

  @Test
  fun `speed peaks on a slight descent`() {
    assertEquals(
      ToblerEstimator.MAX_SPEED_KMH,
      ToblerEstimator.speedKmh(-0.05),
      1e-12,
    )
  }

  @Test
  fun `flat ground speed matches the fitted constant`() {
    val expected = ToblerEstimator.MAX_SPEED_KMH * exp(-0.175)
    assertEquals(5.0367, expected, 1e-3)
    assertEquals(expected, ToblerEstimator.speedKmh(0.0), 1e-12)
  }

  @Test
  fun `speed falls away on both sides of the optimum`() {
    val optimal = ToblerEstimator.speedKmh(-0.05)
    val flat = ToblerEstimator.speedKmh(0.0)
    val gentleClimb = ToblerEstimator.speedKmh(0.1)
    val steepClimb = ToblerEstimator.speedKmh(0.3)
    val steepDescent = ToblerEstimator.speedKmh(-0.4)

    assertTrue(optimal > flat)
    assertTrue(flat > gentleClimb)
    assertTrue(gentleClimb > steepClimb)
    assertTrue(flat > steepDescent)
  }

  /** A 5 km flat walk at ~5.04 km/h is the textbook Tobler result: just under an hour. */
  @Test
  fun `five flat kilometres takes just under an hour`() {
    val seconds = ToblerEstimator.hikingSeconds(horizontalDistanceMeters = 5_000.0, elevationDeltaMeters = 0.0)
    assertTrue("expected ~3574 s but was $seconds", seconds in 3_560.0..3_590.0)
  }

  @Test
  fun `climbing takes longer than walking flat`() {
    val flat = ToblerEstimator.hikingSeconds(5_000.0, 0.0)
    val climbing = ToblerEstimator.hikingSeconds(5_000.0, 500.0)
    val descending = ToblerEstimator.hikingSeconds(5_000.0, -250.0)
    assertTrue(climbing > flat)
    assertTrue(descending < flat)
  }

  @Test
  fun `zero distance takes no time`() {
    assertEquals(0.0, ToblerEstimator.hikingSeconds(0.0, 100.0), 0.0)
    assertEquals(0.0, ToblerEstimator.hikingSeconds(-5.0, 100.0), 0.0)
  }

  @Test
  fun `vertical grades stay finite because speed is floored`() {
    val seconds = ToblerEstimator.hikingSeconds(10.0, 10_000.0)
    assertTrue("time must be finite but was $seconds", seconds.isFinite())
    assertTrue(seconds > 0.0)
  }

  @Test
  fun `non finite grades are rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      ToblerEstimator.speedKmh(Double.NaN)
    }
  }

  @Test
  fun `track estimate sums per segment and uses elevation`() {
    val flatPoints = listOf(
      TrackPoint(LatLng(46.0, 7.0), elevationMeters = 1000.0),
      TrackPoint(LatLng(46.009, 7.0), elevationMeters = 1000.0),
    )
    val climbingPoints = listOf(
      TrackPoint(LatLng(46.0, 7.0), elevationMeters = 1000.0),
      TrackPoint(LatLng(46.009, 7.0), elevationMeters = 1600.0),
    )

    val flat = ToblerEstimator.hikingSeconds(flatPoints)
    val climbing = ToblerEstimator.hikingSeconds(climbingPoints)

    assertTrue(flat > 0.0)
    assertTrue("climbing the same distance must take longer", climbing > flat)

    val singleSegment = ToblerEstimator.hikingSeconds(
      Geodesic.distanceMeters(flatPoints[0].position, flatPoints[1].position),
      0.0,
    )
    assertEquals(singleSegment, flat, 1e-6)
  }

  @Test
  fun `track estimate treats missing elevation as flat`() {
    val points = listOf(
      TrackPoint(LatLng(46.0, 7.0)),
      TrackPoint(LatLng(46.009, 7.0), elevationMeters = 1600.0),
    )
    val distance = Geodesic.distanceMeters(points[0].position, points[1].position)
    assertEquals(ToblerEstimator.hikingSeconds(distance, 0.0), ToblerEstimator.hikingSeconds(points), 1e-6)
    assertTrue(ToblerEstimator.hikingSeconds(points) > 0.0)
  }
}
