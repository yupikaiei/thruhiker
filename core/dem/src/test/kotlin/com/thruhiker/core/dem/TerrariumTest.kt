package com.thruhiker.core.dem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrariumTest {

  @Test
  fun `the encoding is signed around an offset of 32768`() {
    assertEquals(0.0, Terrarium.elevationMeters(128, 0, 0), 1e-9)
    assertEquals(256.0, Terrarium.elevationMeters(129, 0, 0), 1e-9)
    assertEquals(-1.0, Terrarium.elevationMeters(127, 255, 0), 1e-9)
    assertEquals(12.5, Terrarium.elevationMeters(128, 12, 128), 1e-9)
  }

  @Test
  fun `everest round-trips through the channels`() {
    // 8848.86 m: the red channel is the high byte, green the middle, blue the fraction.
    val meters = 8848.86
    val encoded = meters + 32768.0
    val red = (encoded / 256.0).toInt()
    val green = (encoded - red * 256.0).toInt()
    val blue = ((encoded - red * 256.0 - green) * 256.0).toInt()

    assertEquals(meters, Terrarium.elevationMeters(red, green, blue), 0.01)
  }

  @Test
  fun `packed argb is decoded in channel order`() {
    // 0xFF 80 0A 80 -> red 128, green 10, blue 128.
    val packed = (0xFF shl 24) or (128 shl 16) or (10 shl 8) or 128
    assertEquals(Terrarium.elevationMeters(128, 10, 128), Terrarium.elevationMeters(packed), 1e-9)
  }

  @Test
  fun `a hole in the dataset is not ground`() {
    assertFalse(Terrarium.isPlausible(Terrarium.NO_DATA_METERS))
    assertFalse(Terrarium.isPlausible(-2_000.0))
    assertFalse(Terrarium.isPlausible(Double.NaN))
    assertFalse(Terrarium.isPlausible(99_999.0))
  }

  @Test
  fun `real ground, including the lowest land on earth, is plausible`() {
    assertTrue(Terrarium.isPlausible(0.0))
    assertTrue(Terrarium.isPlausible(4_808.0))
    assertTrue(Terrarium.isPlausible(-430.0)) // The Dead Sea shore.
  }
}
