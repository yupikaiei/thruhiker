package com.thruhiker.core.dem

import com.thruhiker.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemSamplerTest {

  private val tile = TileCoordinate(zoom = 13, x = 4252, y = 2916)

  /** The position at a pixel inside [tile], which is how these tests address the grid. */
  private fun at(x: Double, y: Double) = SlippyTile.positionFor(tile, TilePixel(x, y))

  @Test
  fun `a flat surface reads back flat`() {
    val sampler = DemSampler(FixedDem(flatTile(1_240.0)), zoom = 13)

    assertEquals(1_240.0, sampler.elevationAt(at(10.5, 20.5))!!, 0.01)
    assertEquals(1_240.0, sampler.elevationAt(at(200.5, 30.5))!!, 0.01)
  }

  @Test
  fun `a pixel centre reads back that pixel exactly`() {
    val pixels = flatTile(1_000.0, mapOf((1 to 0) to 2_000.0, (2 to 0) to 3_000.0))
    val sampler = DemSampler(FixedDem(pixels), zoom = 13)

    assertEquals(1_000.0, sampler.elevationAt(at(0.5, 0.5))!!, 0.01)
    assertEquals(2_000.0, sampler.elevationAt(at(1.5, 0.5))!!, 0.01)
    assertEquals(3_000.0, sampler.elevationAt(at(2.5, 0.5))!!, 0.01)
  }

  /**
   * The reason for interpolating at all: a sample on the boundary between two pixels is
   * halfway between them rather than snapped to one of them. Nearest-pixel sampling here
   * would produce a stepped profile, and a gradient taken from a stepped profile is a row of
   * spikes.
   */
  @Test
  fun `a sample between two pixels is between their heights`() {
    val pixels = flatTile(1_000.0, mapOf((1 to 0) to 2_000.0))
    val sampler = DemSampler(FixedDem(pixels), zoom = 13)

    assertEquals(1_500.0, sampler.elevationAt(at(1.0, 0.5))!!, 0.01)
    assertEquals(1_250.0, sampler.elevationAt(at(0.75, 0.5))!!, 0.01)
  }

  @Test
  fun `interpolation works across rows as well as columns`() {
    val pixels = flatTile(1_000.0, mapOf((0 to 1) to 2_000.0))
    val sampler = DemSampler(FixedDem(pixels), zoom = 13)

    assertEquals(1_500.0, sampler.elevationAt(at(0.5, 1.0))!!, 0.01)
  }

  @Test
  fun `an unavailable tile is unknown rather than zero`() {
    val sampler = DemSampler(FixedDem(null), zoom = 13)

    assertNull(sampler.elevationAt(at(10.5, 10.5)))
  }

  @Test
  fun `a hole in the data is unknown rather than a hole in the earth`() {
    val sampler = DemSampler(FixedDem(flatTile(Terrarium.NO_DATA_METERS)), zoom = 13)

    assertNull(sampler.elevationAt(at(10.5, 10.5)))
  }

  @Test
  fun `tile edges stay well defined`() {
    val pixels = flatTile(1_000.0, mapOf((0 to 0) to 1_500.0))
    val sampler = DemSampler(FixedDem(pixels), zoom = 13)

    // The very corner interpolates against itself rather than reading outside the array.
    assertEquals(1_500.0, sampler.elevationAt(at(0.5, 0.5))!!, 0.01)
    assertEquals(1_500.0, sampler.elevationAt(at(0.0, 0.0))!!, 0.01)
  }

  @Test
  fun `a synthetic surface is recovered from real tile geometry`() {
    // A ramp climbing 15 m per 0.001 degrees of latitude, encoded into tiles the way the real
    // source would, then read back at points that fall in different tiles. Heights stay inside
    // the plausible range, so this fails on interpolation error and not on the sanity check.
    val source = SyntheticDem { position -> 1_500.0 + (position.latitude - 45.5) * 15_000.0 }
    val sampler = DemSampler(source, zoom = 13)

    for (latitude in listOf(45.4, 45.5, 45.6)) {
      val expected = 1_500.0 + (latitude - 45.5) * 15_000.0
      val sampled = sampler.elevationAt(LatLng(latitude, 6.8652))!!
      assertEquals("at $latitude", expected, sampled, 0.5)
    }

    assertTrue(source.tilesServed > 0)
  }
}
