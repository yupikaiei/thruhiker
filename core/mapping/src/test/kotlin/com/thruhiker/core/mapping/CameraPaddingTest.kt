package com.thruhiker.core.mapping

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The camera padding that keeps the framed point in the middle of the uncovered map.
 *
 * The order is the whole point of the test. MapLibre takes these four values as left, top,
 * right, bottom while everything else in the SDK that deals with screen insets — and every
 * developer's first guess — is top, right, bottom, left. Getting it wrong produces a
 * mis-framed map rather than an error, and on this screen the two ends are a panel at the
 * bottom and nothing at all at the top, so the mistake is a route drawn under the panel.
 */
class CameraPaddingTest {

  @Test
  fun `padding is given in left, top, right, bottom order`() {
    assertArrayEquals(
      doubleArrayOf(1.0, 2.0, 3.0, 4.0),
      cameraPadding(left = 1, top = 2, right = 3, bottom = 4),
      0.0,
    )
  }

  @Test
  fun `an uncovered map asks for no padding`() {
    assertNull(cameraPadding(left = 0, top = 0, right = 0, bottom = 0))
  }

  @Test
  fun `a negative inset is treated as nothing covered`() {
    assertNull(cameraPadding(left = -40, top = -1, right = 0, bottom = 0))
  }

  @Test
  fun `the info panel pads only the bottom`() {
    assertArrayEquals(
      doubleArrayOf(0.0, 0.0, 0.0, 660.0),
      cameraPadding(left = 0, top = 0, right = 0, bottom = 660),
      0.0,
    )
  }
}
