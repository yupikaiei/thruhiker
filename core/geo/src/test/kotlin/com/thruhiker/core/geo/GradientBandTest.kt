package com.thruhiker.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientBandTest {

  @Test
  fun `band boundaries are inclusive at the bottom and exclusive at the top`() {
    assertEquals(GradientBand.STEEP_DESCENT, GradientBand.of(-15.1))
    assertEquals(GradientBand.DESCENT, GradientBand.of(-15.0))
    assertEquals(GradientBand.DESCENT, GradientBand.of(-7.1))
    assertEquals(GradientBand.GENTLE_DESCENT, GradientBand.of(-7.0))
    assertEquals(GradientBand.FLAT, GradientBand.of(-3.0))
    assertEquals(GradientBand.FLAT, GradientBand.of(2.99))
    assertEquals(GradientBand.GENTLE_CLIMB, GradientBand.of(3.0))
    assertEquals(GradientBand.CLIMB, GradientBand.of(7.0))
    assertEquals(GradientBand.STEEP_CLIMB, GradientBand.of(15.0))
    assertEquals(GradientBand.STEEP_CLIMB, GradientBand.of(400.0))
  }

  @Test
  fun `flat is the neutral band around zero`() {
    assertEquals(GradientBand.FLAT, GradientBand.of(0.0))
    assertEquals(GradientBand.FLAT, GradientBand.of(-0.5))
  }

  @Test
  fun `a missing gradient is unknown rather than flat`() {
    assertEquals(GradientBand.UNKNOWN, GradientBand.of(null))
    assertEquals(GradientBand.UNKNOWN, GradientBand.of(Double.NaN))
    assertEquals(GradientBand.UNKNOWN, GradientBand.of(Double.POSITIVE_INFINITY))
  }

  @Test
  fun `every band but unknown carries a distinct colour`() {
    val coloured = GradientBand.entries.filter { it != GradientBand.UNKNOWN }
    val colors = coloured.map { it.colorHex }
    assertEquals(coloured.size, colors.toSet().size)
    assertTrue(colors.all { it.matches(Regex("#[0-9A-Fa-f]{6}")) })
  }
}
