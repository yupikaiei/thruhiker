package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStatsCalculatorTest {

  private fun point(
    latitude: Double,
    longitude: Double,
    elevation: Double? = null,
    timeMillis: Long? = null,
  ) = TrackPoint(
    position = LatLng(latitude, longitude),
    elevationMeters = elevation,
    timeMillis = timeMillis,
  )

  @Test
  fun `an empty track yields empty stats`() {
    assertEquals(TrackStats.Empty, TrackStatsCalculator.compute(Track.Empty))
  }

  @Test
  fun `a single point has no distance and no timing`() {
    val stats = TrackStatsCalculator.compute(
      Track.of(listOf(point(46.0, 7.0, elevation = 100.0))),
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
      point(0.0, 0.0, elevation = 0.0),
      point(0.0, 0.01, elevation = 0.0),
      point(0.0, 0.03, elevation = 0.0),
    )

    val stats = TrackStatsCalculator.compute(Track.of(points))

    assertEquals(Geodesic.pathLengthMeters(points.map { it.position }), stats.distanceMeters, 1e-6)
    // 0.03 degrees of longitude on the equator.
    assertEquals(3_339.58, stats.distanceMeters, 1.0)
  }

  @Test
  fun `elevation gain loss and extremes are reported`() {
    val points = listOf(
      point(46.0, 7.0, elevation = 1000.0),
      point(46.001, 7.0, elevation = 1300.0),
      point(46.002, 7.0, elevation = 1100.0),
    )

    val stats = TrackStatsCalculator.compute(Track.of(points))

    assertEquals(300.0, stats.elevationGainMeters, 1e-9)
    assertEquals(200.0, stats.elevationLossMeters, 1e-9)
    assertEquals(1000.0, stats.minimumElevationMeters!!, 1e-9)
    assertEquals(1300.0, stats.maximumElevationMeters!!, 1e-9)
  }

  @Test
  fun `timestamps drive elapsed and average speed`() {
    val points = listOf(
      point(0.0, 0.0, timeMillis = 1_000L),
      point(0.0, 0.01, timeMillis = 61_000L),
    )

    val stats = TrackStatsCalculator.compute(Track.of(points))

    assertEquals(1_000L, stats.startTimeMillis)
    assertEquals(61_000L, stats.endTimeMillis)
    assertEquals(60_000L, stats.elapsedMillis)
    assertEquals(60_000L, stats.movingMillis)
    assertEquals(stats.distanceMeters / 60.0, stats.averageSpeedMetersPerSecond!!, 1e-9)
  }

  @Test
  fun `a long pause is excluded from moving time`() {
    val points = listOf(
      point(46.0, 7.0, timeMillis = 0L),
      point(46.001, 7.0, timeMillis = 60_000L), // 111 m in 60 s: walking
      point(46.002, 7.0, timeMillis = 660_000L), // 10 min gap: lunch
    )

    val stats = TrackStatsCalculator.compute(Track.of(points))

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
      point(46.0, 7.0, timeMillis = 0L),
      point(46.000045, 7.0, timeMillis = 60_000L), // ~5 m in a minute
    )

    val stats = TrackStatsCalculator.compute(Track.of(points))

    assertEquals(0L, stats.movingMillis)
    assertNull(stats.movingSpeedMetersPerSecond)
    assertTrue(stats.distanceMeters > 0.0)
  }

  @Test
  fun `a track without timestamps reports null timing`() {
    val points = listOf(
      point(46.0, 7.0, elevation = 10.0),
      point(46.001, 7.0, elevation = 20.0),
    )

    val stats = TrackStatsCalculator.compute(Track.of(points))

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
      point(46.0, 7.0, elevation = 0.0),
      point(46.001, 7.0, elevation = 1.0),
      point(46.002, 7.0, elevation = 2.0),
    )

    val defaultStats = TrackStatsCalculator.compute(Track.of(points))
    assertEquals(0.0, defaultStats.elevationGainMeters, 1e-9)

    val sensitive = TrackStatsCalculator.compute(
      Track.of(points),
      TrackStatsOptions(elevationThresholdMeters = 0.0),
    )
    assertEquals(2.0, sensitive.elevationGainMeters, 1e-9)
  }

  /**
   * The regression test for the reason the model has segments at all.
   *
   * Two recorded legs 1,100 km apart must total the distance walked, not the
   * distance between them.
   */
  @Test
  fun `distance never spans a segment boundary`() {
    val firstLeg = listOf(
      point(0.0, 0.0, elevation = 0.0),
      point(0.0, 0.01, elevation = 0.0),
      point(0.0, 0.02, elevation = 0.0),
    )
    val secondLeg = listOf(
      point(10.0, 0.0, elevation = 0.0),
      point(10.0, 0.01, elevation = 0.0),
    )
    val track = Track(listOf(TrackSegment(firstLeg), TrackSegment(secondLeg)))

    val expected = Geodesic.pathLengthMeters(firstLeg.map { it.position }) +
      Geodesic.pathLengthMeters(secondLeg.map { it.position })

    val stats = TrackStatsCalculator.compute(track)

    assertEquals(expected, stats.distanceMeters, 1e-6)
    assertTrue(
      "the 1,100 km gap between segments must not be counted",
      stats.distanceMeters < 4_000.0,
    )
    assertEquals(5, stats.pointCount)
  }

  @Test
  fun `moving time is not accrued across a segment boundary`() {
    val track = Track(
      listOf(
        TrackSegment(
          listOf(
            point(0.0, 0.0, timeMillis = 0L),
            point(0.0, 0.01, timeMillis = 60_000L),
          ),
        ),
        TrackSegment(
          listOf(
            point(10.0, 0.0, timeMillis = 3_600_000L),
            point(10.0, 0.01, timeMillis = 3_660_000L),
          ),
        ),
      ),
    )

    val stats = TrackStatsCalculator.compute(track)

    // One minute of walking inside each segment; the 59 minutes between them is a break.
    assertEquals(120_000L, stats.movingMillis)
    assertEquals(3_660_000L, stats.elapsedMillis)
  }
}
