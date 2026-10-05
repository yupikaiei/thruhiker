package com.thruhiker.ui.components

import com.thruhiker.core.geo.GradientBand
import com.thruhiker.core.geo.TrailDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val METRIC = DistanceUnit.KILOMETERS
private val IMPERIAL = DistanceUnit.MILES

class FormattingTest {

  @Test
  fun `short distances stay in metres`() {
    assertEquals("999 m", formatDistance(999.0, METRIC))
  }

  @Test
  fun `long distances become kilometres`() {
    assertEquals("1.5 km", formatDistance(1_500.0, METRIC))
    assertEquals("42.2 km", formatDistance(42_195.0, METRIC))
  }

  @Test
  fun `an unknown number is a dash and never a zero`() {
    assertEquals("—", formatDistance(null, METRIC))
    assertEquals("—", formatDistance(Double.NaN, METRIC))
    assertEquals("—", formatElevation(null, METRIC))
    assertEquals("—", formatElevation(null, IMPERIAL))
    assertEquals("—", formatDuration(null))
    assertEquals("—", formatGradient(null))
  }

  @Test
  fun `elevation is rounded and grouped`() {
    assertEquals("1,235 m", formatElevation(1_234.6, METRIC))
  }

  @Test
  fun `imperial distances become miles and feet`() {
    assertEquals("3.11 mi", formatDistance(5_000.0, IMPERIAL))
    assertEquals("26.22 mi", formatDistance(42_195.0, IMPERIAL))
    assertEquals("328 ft", formatDistance(100.0, IMPERIAL))
  }

  /**
   * A tenth of a mile is where miles stop being readable. Below it, a walker wants feet —
   * "0.06 mi to the water" is not a distance anyone can picture.
   */
  @Test
  fun `imperial switches to feet below a tenth of a mile`() {
    assertEquals("492 ft", formatDistance(150.0, IMPERIAL))
    assertEquals("0.12 mi", formatDistance(200.0, IMPERIAL))
  }

  @Test
  fun `imperial elevations are in feet`() {
    assertEquals("3,281 ft", formatElevation(1_000.0, IMPERIAL))
    assertEquals("14,291 ft", formatElevation(4_355.76, IMPERIAL))
  }

  @Test
  fun `durations read as hours and minutes`() {
    assertEquals("0m", formatDuration(0.0))
    assertEquals("40m", formatDuration(2_400.0))
    assertEquals("1h 30m", formatDuration(5_400.0))
    assertEquals("2h 5m", formatDuration(7_500.0))
  }

  @Test
  fun `a clock time is rendered for the reader's locale`() {
    val text = formatClockTime(1_700_000_000_000L)
    assertTrue("was '$text'", text.isNotBlank())
    assertTrue("was '$text'", text.any { it.isDigit() })
  }

  @Test
  fun `a split length is a kilometre or a mile and nothing between`() {
    assertEquals(1_000.0, DistanceUnit.KILOMETERS.splitLengthMeters, 1e-9)
    assertEquals(1_609.344, DistanceUnit.MILES.splitLengthMeters, 1e-9)
  }

  /** A climb and a descent must never render the same, which is why the sign is forced. */
  @Test
  fun `gradients always carry a sign`() {
    assertEquals("+8%", formatGradient(8.0))
    assertEquals("-12%", formatGradient(-12.0))
    assertEquals("+0%", formatGradient(0.0))
  }

  @Test
  fun `bands and difficulties use their human labels`() {
    assertEquals("Flat", formatBand(GradientBand.FLAT))
    assertEquals("Steep climb", formatBand(GradientBand.STEEP_CLIMB))
    assertEquals("Moderate", formatDifficulty(TrailDifficulty.MODERATE))
  }

  @Test
  fun `a date is rendered and names its year`() {
    val text = formatDate(1_700_000_000_000L)
    assertTrue("was '$text'", text.isNotBlank())
    assertTrue("was '$text'", text.contains("2023") || text.contains("23"))
  }
}
