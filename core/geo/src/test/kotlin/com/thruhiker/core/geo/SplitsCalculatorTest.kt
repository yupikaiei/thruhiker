package com.thruhiker.core.geo

import com.thruhiker.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitsCalculatorTest {

  @Test
  fun `an empty track has no splits`() {
    val splits = SplitsCalculator.compute(Track.Empty)
    assertTrue(splits.splits.isEmpty())
    assertEquals(0.0, splits.totalDistanceMeters, 0.0)
    assertEquals(0.0, splits.totalSeconds, 0.0)
  }

  @Test
  fun `a three and a half kilometre route is three full splits and a partial`() {
    val track = trackOf(straightRun(legs = 35, spacingMeters = 100.0, gradePercent = 0.0))

    val splits = SplitsCalculator.compute(track)

    assertEquals(4, splits.splits.size)
    assertFalse(splits.splits[0].isPartial)
    assertFalse(splits.splits[1].isPartial)
    assertFalse(splits.splits[2].isPartial)
    assertTrue(splits.splits[3].isPartial)

    assertEquals(1_000.0, splits.splits[0].distanceMeters, 0.5)
    assertEquals(1_000.0, splits.splits[1].distanceMeters, 0.5)
    assertEquals(1_000.0, splits.splits[2].distanceMeters, 0.5)
    assertEquals(500.0, splits.splits[3].distanceMeters, 0.5)
  }

  @Test
  fun `split indexes are one-based and start distances follow`() {
    val track = trackOf(straightRun(legs = 25, spacingMeters = 100.0, gradePercent = 0.0))

    val splits = SplitsCalculator.compute(track).splits

    assertEquals(1, splits[0].index)
    assertEquals(0.0, splits[0].startDistanceMeters, 0.0)
    assertEquals(2, splits[1].index)
    assertEquals(1_000.0, splits[1].startDistanceMeters, 0.5)
    assertEquals(2_000.0, splits[2].startDistanceMeters, 0.5)
  }

  @Test
  fun `the splits add up to the route totals`() {
    val track = trackOf(straightRun(legs = 50, spacingMeters = 100.0, gradePercent = 10.0))
    val stats = TrackStatsCalculator.compute(track)

    val splits = SplitsCalculator.compute(track)

    assertEquals(stats.distanceMeters, splits.totalDistanceMeters, 1.0)
    assertEquals(
      stats.elevationGainMeters,
      splits.splits.sumOf { it.gainMeters },
      1.0,
    )
    assertEquals(
      ToblerEstimator.hikingSeconds(track),
      splits.totalSeconds,
      1.0,
    )
  }

  @Test
  fun `cumulative time increases and the last split holds the total`() {
    val track = trackOf(straightRun(legs = 32, spacingMeters = 100.0, gradePercent = 5.0))

    val splits = SplitsCalculator.compute(track)

    var previous = 0.0
    for (split in splits.splits) {
      assertTrue("cumulative time went backwards", split.cumulativeSeconds > previous)
      previous = split.cumulativeSeconds
    }
    assertEquals(splits.totalSeconds, splits.splits.last().cumulativeSeconds, 1e-9)
  }

  @Test
  fun `a steady climb gives every full split the same gradient and gain`() {
    val track = trackOf(straightRun(legs = 50, spacingMeters = 100.0, gradePercent = 10.0))

    val splits = SplitsCalculator.compute(track).splits.filter { !it.isPartial }

    assertEquals(5, splits.size)
    for ((index, split) in splits.withIndex()) {
      assertEquals(100.0, split.gainMeters, 1.0)
      assertEquals(0.0, split.lossMeters, 0.05)
      assertEquals(10.0, split.averageGradientPercent!!, 0.05)
      assertEquals(index * 100.0, split.startElevationMeters!!, 1.0)
    }
  }

  @Test
  fun `a route shorter than one split is a single partial`() {
    val track = trackOf(straightRun(legs = 6, spacingMeters = 100.0, gradePercent = 0.0))

    val splits = SplitsCalculator.compute(track)

    assertEquals(1, splits.splits.size)
    assertTrue(splits.splits.single().isPartial)
    assertEquals(600.0, splits.splits.single().distanceMeters, 0.5)
  }

  @Test
  fun `mile splits are available for the imperial`() {
    val track = trackOf(straightRun(legs = 40, spacingMeters = 100.0, gradePercent = 0.0))

    val splits = SplitsCalculator.compute(track, splitLengthMeters = SplitsCalculator.MILE)

    assertEquals(4_000.0, splits.totalDistanceMeters, 1.0)
    assertEquals(3, splits.splits.size)
    assertEquals(SplitsCalculator.MILE, splits.splits[0].distanceMeters, 0.5)
  }

  @Test
  fun `the fastest split is the quickest full one per metre`() {
    // Flat first kilometre, then a steep climb: the flat split must win.
    val flat = straightRun(legs = 10, spacingMeters = 100.0, gradePercent = 0.0)
    val climbStart = flat.last().position
    val climb = straightRun(
      legs = 10,
      spacingMeters = 100.0,
      gradePercent = 20.0,
      start = climbStart,
    ).mapIndexed { index, point ->
      point.copy(elevationMeters = (flat.last().elevationMeters ?: 0.0) + index * 20.0)
    }

    val track = trackOf(flat + climb.drop(1))
    val splits = SplitsCalculator.compute(track)

    assertEquals(1, splits.fastestSplit!!.index)
  }

  @Test
  fun `splits without elevation still have times`() {
    val points = straightRun(legs = 25, spacingMeters = 100.0, gradePercent = 0.0)
      .map { it.copy(elevationMeters = null) }

    val splits = SplitsCalculator.compute(trackOf(points))

    assertEquals(3, splits.splits.size)
    assertTrue(splits.splits.all { it.averageGradientPercent == null })
    assertTrue(splits.splits.all { it.seconds > 0.0 })
  }
}
