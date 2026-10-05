package com.thruhiker.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAnalysisTest {

  @Test
  fun `difficulty scales with distance and climb`() {
    assertEquals(TrailDifficulty.EASY, RouteAnalysis.difficultyFor(5_000.0, 100.0))
    assertEquals(TrailDifficulty.MODERATE, RouteAnalysis.difficultyFor(12_000.0, 300.0))
    assertEquals(TrailDifficulty.HARD, RouteAnalysis.difficultyFor(25_000.0, 900.0))
    assertEquals(TrailDifficulty.SEVERE, RouteAnalysis.difficultyFor(42_000.0, 2_000.0))
    assertEquals(TrailDifficulty.EXTREME, RouteAnalysis.difficultyFor(80_000.0, 5_000.0))
  }

  @Test
  fun `a flat hundred kilometres is still an extreme day`() {
    assertEquals(TrailDifficulty.EXTREME, RouteAnalysis.difficultyFor(100_000.0, 0.0))
  }

  @Test
  fun `analysis reports distance climb and walking time`() {
    val track = trackOf(straightRun(legs = 50, spacingMeters = 100.0, gradePercent = 6.0))

    val analysis = RouteAnalysis.of(track)

    assertEquals(5_000.0, analysis.stats.distanceMeters, 1.0)
    assertEquals(300.0, analysis.stats.elevationGainMeters, 1.0)
    assertTrue(analysis.walkingSeconds > 0.0)
    assertEquals(analysis.walkingSeconds / 3600.0, analysis.walkingHours, 1e-12)
  }

  @Test
  fun `a steady climb is entirely in one band`() {
    val track = trackOf(straightRun(legs = 30, spacingMeters = 100.0, gradePercent = 8.0))

    val analysis = RouteAnalysis.of(track)

    assertEquals(8.0, analysis.steepestClimbPercent!!, 0.01)
    assertNull(analysis.steepestDescentPercent)
    assertEquals(GradientBand.CLIMB, analysis.dominantBand)
    assertEquals(1.0, analysis.gradientDistribution.getValue(GradientBand.CLIMB), 0.001)
  }

  @Test
  fun `net gradient is the climb minus the descent over the ground covered`() {
    val track = trackOf(straightRun(legs = 20, spacingMeters = 100.0, gradePercent = 10.0))

    val analysis = RouteAnalysis.of(track)

    assertEquals(10.0, analysis.averageGradientPercent!!, 0.05)
  }

  @Test
  fun `a route with no elevation reports unknown rather than flat`() {
    val points = straightRun(legs = 20, spacingMeters = 100.0, gradePercent = 0.0)
      .map { it.copy(elevationMeters = null) }

    val analysis = RouteAnalysis.of(trackOf(points))

    assertNull(analysis.averageGradientPercent)
    assertNull(analysis.steepestClimbPercent)
    assertNull(analysis.steepestDescentPercent)
    assertEquals(GradientBand.UNKNOWN, analysis.dominantBand)
    assertEquals(1.0, analysis.gradientDistribution.getValue(GradientBand.UNKNOWN), 1e-9)
    assertTrue(analysis.walkingSeconds > 0.0)
  }

  @Test
  fun `gradient extremes ignore legs too short to be real`() {
    // A ten-metre leg with a four-metre blip reads as 40% and must be ignored; the long leg
    // that follows is flat, so there is no honest climb to report at all.
    val noisy = listOf(
      com.thruhiker.core.model.TrackPoint(com.thruhiker.core.model.LatLng(46.0, 7.0), 1000.0),
      com.thruhiker.core.model.TrackPoint(com.thruhiker.core.model.LatLng(46.000_09, 7.0), 1004.0),
      com.thruhiker.core.model.TrackPoint(com.thruhiker.core.model.LatLng(46.001_09, 7.0), 1004.0),
    )

    val analysis = RouteAnalysis.of(trackOf(noisy), RouteAnalysisOptions(minimumLegMeters = 20.0))

    assertNull(analysis.steepestClimbPercent)
  }
}
