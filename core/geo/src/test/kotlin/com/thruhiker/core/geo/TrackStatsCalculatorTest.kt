package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStatsCalculatorTest {

  @Test
  fun `an empty track yields empty stats`() {
    assertEquals(TrackStats.Empty, TrackStatsCalculator.compute(Track.Empty))
  }

  @Test
  fun `a single point has no distance and no timing`() {
    val stats = TrackStatsCalculator.compute(
      Track(listOf(TrackPoint(LatLng(46.0, 7.0), elevationMeters = 100.0))),
    )
    assertEquals(1, stats.pointCount)
    assertEquals(0.0, stats.distanceMeters, 0.0)
    assertEquals(100.0, stats.minimumElevationMeters!!, 0.0)
    assertNull(stats.elapsedMillis)
    assertNull(stats.movingMillis)
  }

  @Test
  fun `distance sums the geodesic legs`() {
    val points = listOf(
      TrackPoint(LatLng(0.0, 0.0), elevationMeters = 0.0),
      TrackPoint(LatLng(0.0, 0.01), elevationMeters = 0.0),
      TrackPoint(LatLng(0.0, 0.03), elevationMeters = 0.0),
    )
    val stats = TrackStatsCalculator.compute(Track(points))

    assertEquals(Geodesic.pathLengthMeters(points.map { it.position }), stats.distanceMeters, 1e-6)
    // 0.03 degrees of longitude on the equator.
    assertEquals(3_339.58, stats.distanceMeters, 1.0)
  }

  @Test
  fun `elevation gain loss and extremes are reported`() {
    val points = listOf(
      TrackPoint(LatLng(46.0, 7.0), elevationMeters = 1000.0),
      TrackPoint(LatLng(46.001, 7.0), elevationMeters = 1300.0),
      TrackPoint(LatLng(46.002, 7.0), elevationMeters = 1100.0),
    )
    val stats = TrackStatsCalculator.compute(Track(points))

    assertEquals(300.0, stats.elevationGainMeters, 1e-9)
    assertEquals(200.0, stats.elevationLossMeters, 1e-9)
    assertEquals(1000.0, stats.minimumElevationMeters!!, 1e-9)
    assertEquals(1300.0, stats.maximumElevationMeters!!, 1e-9)
  }

  @Test
  fun `timestamps drive elapsed and average speed`() {
    val points = listOf(
      TrackPoint(LatLng(0.0, 0.0), timeMillis = 1_000L),
      TrackPoint(LatLng(0.0, 0.01), timeMillis = 61_000L),
    )
    val stats = TrackStatsCalculator.compute(Track(points))

    assertEquals(1_000L, stats.startTimeMillis)
    assertEquals(61_000L, stats.endTimeMillis)
    assertEquals(60_000L, stats.elapsedMillis)
    assertEquals(60_000L, stats.movingMillis)
    assertEquals(stats.distanceMeters / 60.0, stats.averageSpeedMetersPerSecond!!, 1e-9)
  }

  @Test
  fun `a long pause is excluded from moving time`() {
    val points = listOf(
      TrackPoint(LatLng(46.0, 7.0), timeMillis = 0L),
      TrackPoint(LatLng(46.001, 7.0), timeMillis = 60_000L), // 111 m in 60 s: walking
      TrackPoint(LatLng(46.002, 7.0), timeMillis = 660_000L), // 10 min gap: lunch
    )
    val stats = TrackStatsCalculator.compute(Track(points))

    assertEquals(660_000L, stats.elapsedMillis)
    assertEquals(60_000L, stats.movingMillis)
    assertTrue(
      "moving speed must exceed average speed when breaks are excluded",
      stats.movingSpeedMetersPerSecond!! > stats.averageSpeedMetersPerSecond!!,
    )
  }

  @Test
  fun `standing still accrues distance but not moving time`() {
    val points = listOf(
      TrackPoint(LatLng(46.0, 7.0), timeMillis = 0L),
      TrackPoint(LatLng(46.000045, 7.0), timeMillis = 60_000L), // ~5 m in a minute
    )
    val stats = TrackStatsCalculator.compute(Track(points))

    assertEquals(0L, stats.movingMillis)
    assertNull(stats.movingSpeedMetersPerSecond)
    assertTrue(stats.distanceMeters > 0.0)
  }

  @Test
  fun `a track without timestamps reports null timing`() {
    val points = listOf(
      TrackPoint(LatLng(46.0, 7.0), elevationMeters = 10.0),
      TrackPoint(LatLng(46.001, 7.0), elevationMeters = 20.0),
    )
    val stats = TrackStatsCalculator.compute(Track(points))

    assertNull(stats.startTimeMillis)
    assertNull(stats.endTimeMillis)
    assertNull(stats.elapsedMillis)
    assertNull(stats.movingMillis)
    assertNull(stats.averageSpeedMetersPerSecond)
    assertNull(stats.movingSpeedMetersPerSecond)
  }

  @Test
  fun `custom elevation threshold is honoured`() {
    val points = listOf(
      TrackPoint(LatLng(46.0, 7.0), elevationMeters = 0.0),
      TrackPoint(LatLng(46.001, 7.0), elevationMeters = 1.0),
      TrackPoint(LatLng(46.002, 7.0), elevationMeters = 2.0),
    )

    val defaultStats = TrackStatsCalculator.compute(Track(points))
    assertEquals(0.0, defaultStats.elevationGainMeters, 1e-9)

    val sensitive = TrackStatsCalculator.compute(
      Track(points),
      TrackStatsOptions(elevationThresholdMeters = 0.0),
    )
    assertEquals(2.0, sensitive.elevationGainMeters, 1e-9)
  }
}
