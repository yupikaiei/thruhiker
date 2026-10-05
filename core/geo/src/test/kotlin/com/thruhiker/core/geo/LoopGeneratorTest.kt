package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class LoopGeneratorTest {

  private val start = LatLng(46.0, 7.0)

  @Test
  fun `the loop closes on the point it started from`() {
    val track = LoopGenerator.generate(start, 8_000.0)

    val gap = Geodesic.distanceMeters(track.points.first().position, track.points.last().position)
    assertEquals(0.0, gap, 1.0)
  }

  @Test
  fun `the loop is the length that was asked for`() {
    for (target in listOf(3_000.0, 8_000.0, 15_000.0, 30_000.0)) {
      val track = LoopGenerator.generate(start, target)
      val length = TrackStatsCalculator.compute(track).distanceMeters
      assertEquals("target $target", target, length, target * 0.05)
    }
  }

  @Test
  fun `the loop stays around the start rather than wandering off`() {
    val target = 10_000.0
    val track = LoopGenerator.generate(start, target)
    val baseRadius = target / (2.0 * PI)

    for (point in track.points) {
      val distance = Geodesic.distanceMeters(start, point.position)
      assertTrue("$distance m from the start", distance <= baseRadius * 2.0)
    }
  }

  @Test
  fun `the same request produces the same loop every time`() {
    val first = LoopGenerator.generate(start, 9_000.0)
    val second = LoopGenerator.generate(start, 9_000.0)

    assertEquals(first.points.map { it.position }, second.points.map { it.position })
  }

  @Test
  fun `a different seed produces a different shape`() {
    val first = LoopGenerator.generate(start, 9_000.0, LoopOptions(seed = 1L))
    val second = LoopGenerator.generate(start, 9_000.0, LoopOptions(seed = 7L))

    assertNotEquals(first.points.map { it.position }, second.points.map { it.position })
    assertEquals(
      9_000.0,
      TrackStatsCalculator.compute(second).distanceMeters,
      450.0,
    )
  }

  @Test
  fun `a generated loop has geometry but no elevation`() {
    val track = LoopGenerator.generate(start, 6_000.0)

    assertTrue(track.points.size > 20)
    assertTrue(track.points.all { it.elevationMeters == null })
    assertEquals(GradientBand.UNKNOWN, RouteAnalysis.of(track).dominantBand)
  }

  @Test
  fun `a degenerate request is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      LoopGenerator.generate(start, 0.0)
    }
    assertThrows(IllegalArgumentException::class.java) {
      LoopGenerator.generate(start, 5_000.0, LoopOptions(controlPoints = 2))
    }
    assertThrows(IllegalArgumentException::class.java) {
      LoopGenerator.generate(start, 5_000.0, LoopOptions(irregularity = 1.5))
    }
  }
}
