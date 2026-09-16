package com.thruhiker.core.geo

import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import com.thruhiker.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ElevationCalculatorTest {

  private val threshold = ElevationCalculator.DEFAULT_THRESHOLD_METERS

  @Test
  fun `empty input yields zero gain and loss`() {
    val result = ElevationCalculator.gainLoss(emptyList())
    assertEquals(0.0, result.gainMeters, 0.0)
    assertEquals(0.0, result.lossMeters, 0.0)
  }

  @Test
  fun `a single sample yields zero gain and loss`() {
    val result = ElevationCalculator.gainLoss(listOf(1234.0))
    assertEquals(ElevationGainLoss.Zero, result)
  }

  @Test
  fun `samples without elevation are ignored entirely`() {
    val result = ElevationCalculator.gainLoss(listOf(null, null, null))
    assertEquals(ElevationGainLoss.Zero, result)
  }

  @Test
  fun `a flat profile yields zero gain and loss`() {
    val result = ElevationCalculator.gainLoss(List(50) { 1500.0 })
    assertEquals(ElevationGainLoss.Zero, result)
  }

  /** Sub-threshold oscillation is GPS noise, not terrain. */
  @Test
  fun `noise inside the threshold band is discarded`() {
    val result = ElevationCalculator.gainLoss(
      listOf(100.0, 100.5, 99.6, 100.4, 99.9, 100.0),
    )
    assertEquals(0.0, result.gainMeters, 0.0)
    assertEquals(0.0, result.lossMeters, 0.0)
  }

  @Test
  fun `a single large climb is counted in full`() {
    val result = ElevationCalculator.gainLoss(listOf(1000.0, 1900.0))
    assertEquals(900.0, result.gainMeters, 1e-9)
    assertEquals(0.0, result.lossMeters, 1e-9)
  }

  /**
   * Documents the deliberate under-count: a one-metre staircase with a 3 m
   * threshold commits a new reference only every third step, so 10 m of real
   * ascent reports as 8 m.
   */
  @Test
  fun `stairs under-count slightly by design`() {
    val result = ElevationCalculator.gainLoss((0..10).map { it.toDouble() }, threshold)
    assertEquals(8.0, result.gainMeters, 1e-9)
    assertEquals(0.0, result.lossMeters, 1e-9)
  }

  @Test
  fun `climb then descent reports both directions`() {
    val result = ElevationCalculator.gainLoss(listOf(0.0, 50.0, 0.0))
    assertEquals(50.0, result.gainMeters, 1e-9)
    assertEquals(50.0, result.lossMeters, 1e-9)
  }

  @Test
  fun `null samples inside a climb are skipped without breaking accumulation`() {
    val result = ElevationCalculator.gainLoss(listOf(0.0, null, 50.0, null, 120.0))
    assertEquals(120.0, result.gainMeters, 1e-9)
    assertEquals(0.0, result.lossMeters, 1e-9)
  }

  @Test
  fun `non finite samples are treated as missing`() {
    val result = ElevationCalculator.gainLoss(
      listOf(0.0, Double.NaN, 50.0, Double.POSITIVE_INFINITY, 90.0),
    )
    assertEquals(90.0, result.gainMeters, 1e-9)
  }

  @Test
  fun `a zero threshold counts every movement`() {
    val result = ElevationCalculator.gainLoss(listOf(0.0, 1.0, 2.0), thresholdMeters = 0.0)
    assertEquals(2.0, result.gainMeters, 1e-9)
  }

  @Test
  fun `a negative threshold is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      ElevationCalculator.gainLoss(listOf(1.0, 2.0), thresholdMeters = -1.0)
    }
  }

  @Test
  fun `minimum and maximum ignore missing values`() {
    val elevations = listOf(1200.0, null, 950.0, 1830.0, Double.NaN)
    assertEquals(950.0, ElevationCalculator.minimum(elevations)!!, 1e-9)
    assertEquals(1830.0, ElevationCalculator.maximum(elevations)!!, 1e-9)
  }

  @Test
  fun `minimum and maximum are null when no elevation is present`() {
    assertNull(ElevationCalculator.minimum(listOf(null, null)))
    assertNull(ElevationCalculator.maximum(emptyList()))
  }

  @Test
  fun `track overload reads elevations from the track`() {
    val track = Track.of(
      listOf(
        TrackPoint(LatLng(46.0, 7.0), elevationMeters = 0.0),
        TrackPoint(LatLng(46.001, 7.0), elevationMeters = 300.0),
      ),
    )
    val result = ElevationCalculator.gainLoss(track)
    assertEquals(300.0, result.gainMeters, 1e-9)
    assertEquals(0.0, result.lossMeters, 1e-9)
  }

  /**
   * Ascent between segments is unknown travel, not climb.
   *
   * A pause recorded at the foot of a hill, resumed at the top, must not report
   * the height of that hill as ascent. This is the elevation half of the reason
   * segments exist.
   */
  @Test
  fun `elevation change across a segment boundary is not counted`() {
    val track = Track(
      listOf(
        TrackSegment(
          listOf(
            TrackPoint(LatLng(46.0, 7.0), elevationMeters = 0.0),
            TrackPoint(LatLng(46.001, 7.0), elevationMeters = 100.0),
          ),
        ),
        TrackSegment(
          listOf(
            TrackPoint(LatLng(46.100, 7.0), elevationMeters = 900.0),
            TrackPoint(LatLng(46.101, 7.0), elevationMeters = 1000.0),
          ),
        ),
      ),
    )

    val result = ElevationCalculator.gainLoss(track)

    // 100 m inside each segment; the 800 m of unknown ground between them ignored.
    assertEquals(200.0, result.gainMeters, 1e-9)
    assertEquals(0.0, result.lossMeters, 1e-9)
  }
}
