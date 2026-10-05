package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationProfileTest {

  @Test
  fun `an empty track has an empty profile`() {
    assertEquals(ElevationProfile.Empty, ElevationProfile.of(Track.Empty))
  }

  @Test
  fun `the profile is always resampled to the requested size`() {
    val track = trackOf(straightRun(legs = 40, spacingMeters = 100.0, gradePercent = 5.0))

    val profile = ElevationProfile.of(track, sampleCount = 32)

    assertEquals(32, profile.samples.size)
    assertEquals(0.0, profile.samples.first().distanceMeters, 1e-9)
    assertEquals(profile.totalDistanceMeters, profile.samples.last().distanceMeters, 1e-6)
  }

  @Test
  fun `gain loss and extremes come from the track`() {
    val track = trackOf(straightRun(legs = 10, spacingMeters = 100.0, gradePercent = 10.0))

    val profile = ElevationProfile.of(track)

    assertEquals(100.0, profile.gainMeters, 0.5)
    assertEquals(0.0, profile.lossMeters, 0.5)
    assertEquals(0.0, profile.minimumElevationMeters!!, 0.5)
    assertEquals(100.0, profile.maximumElevationMeters!!, 0.5)
    assertTrue(profile.hasElevation)
  }

  @Test
  fun `gradient is positive on the way up and negative on the way down`() {
    val up = trackOf(straightRun(legs = 20, spacingMeters = 100.0, gradePercent = 8.0))
    val down = trackOf(straightRun(legs = 20, spacingMeters = 100.0, gradePercent = -8.0))

    val upProfile = ElevationProfile.of(up)
    val downProfile = ElevationProfile.of(down)

    assertTrue(upProfile.samples.drop(1).all { (it.gradientPercent ?: 0.0) > 0.0 })
    assertTrue(downProfile.samples.drop(1).all { (it.gradientPercent ?: 0.0) < 0.0 })
  }

  @Test
  fun `about eight percent reads back as about eight percent`() {
    val track = trackOf(straightRun(legs = 20, spacingMeters = 100.0, gradePercent = 8.0))

    val profile = ElevationProfile.of(track)

    val middle = profile.samples[profile.samples.size / 2].gradientPercent!!
    assertEquals(8.0, middle, 0.05)
  }

  @Test
  fun `the first sample has no gradient because nothing precedes it`() {
    val track = trackOf(straightRun(legs = 5, spacingMeters = 100.0, gradePercent = 5.0))
    assertNull(ElevationProfile.of(track).samples.first().gradientPercent)
  }

  @Test
  fun `a track with no elevation reports none rather than zero`() {
    val points = listOf(
      TrackPoint(LatLng(46.0, 7.0)),
      TrackPoint(LatLng(46.001, 7.0)),
      TrackPoint(LatLng(46.002, 7.0)),
    )

    val profile = ElevationProfile.of(trackOf(points))

    assertFalse(profile.hasElevation)
    assertNull(profile.minimumElevationMeters)
    assertNull(profile.maximumElevationMeters)
    assertTrue(profile.samples.all { it.elevationMeters == null })
    assertTrue(profile.samples.all { it.gradientPercent == null })
    assertTrue(profile.samples.all { it.band == GradientBand.UNKNOWN })
  }

  @Test
  fun `a single point produces one column and no distance`() {
    val profile = ElevationProfile.of(trackOf(listOf(TrackPoint(LatLng(46.0, 7.0), 1200.0))))

    assertEquals(1, profile.samples.size)
    assertEquals(0.0, profile.totalDistanceMeters, 0.0)
    assertEquals(1200.0, profile.samples.first().elevationMeters!!, 0.0)
  }

  @Test
  fun `band fractions cover the whole route exactly once`() {
    val track = trackOf(straightRun(legs = 30, spacingMeters = 100.0, gradePercent = 5.0))

    val fractions = ElevationProfile.of(track).bandFractions()

    assertEquals(1.0, fractions.values.sum(), 1e-9)
    assertTrue(fractions.containsKey(GradientBand.GENTLE_CLIMB))
  }

  @Test
  fun `a recording gap becomes a step rather than a slope`() {
    val up = straightRun(legs = 5, spacingMeters = 100.0, gradePercent = 0.0, start = LatLng(46.0, 7.0))
      .mapIndexed { index, point -> point.copy(elevationMeters = 1000.0 + index) }
    // A second segment starting exactly where the first ended, but 200 m higher.
    val down = straightRun(legs = 5, spacingMeters = 100.0, gradePercent = 0.0, start = up.last().position)
      .mapIndexed { index, point -> point.copy(elevationMeters = 1200.0 - index) }

    val track = Track(
      listOf(
        com.thruhiker.core.model.TrackSegment(up),
        com.thruhiker.core.model.TrackSegment(down),
      ),
    )

    val profile = ElevationProfile.of(track, sampleCount = 11)

    // The gap sits at the same distance as the end of the first segment, so the resampled
    // column at that distance carries no gradient rather than a fabricated 100% slope.
    assertTrue(profile.samples.any { it.gradientPercent == null })
  }
}
