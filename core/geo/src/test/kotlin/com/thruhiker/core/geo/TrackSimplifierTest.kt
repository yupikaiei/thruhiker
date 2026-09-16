package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class TrackSimplifierTest {

  @Test
  fun `tracks of two points or fewer are returned unchanged`() {
    val empty = emptyList<TrackPoint>()
    assertEquals(empty, TrackSimplifier.simplify(empty))

    val single = listOf(point(46.0, 7.0, 100.0))
    assertEquals(single, TrackSimplifier.simplify(single))

    val pair = listOf(point(46.0, 7.0, 100.0), point(46.001, 7.0, 120.0))
    assertEquals(pair, TrackSimplifier.simplify(pair))
  }

  @Test
  fun `collinear samples collapse to the endpoints`() {
    val points = (0..4).map { point(46.0 + it * 0.001, 7.0, 1000.0) }
    val simplified = TrackSimplifier.simplify(points)
    assertEquals(2, simplified.size)
    assertEquals(points.first(), simplified.first())
    assertEquals(points.last(), simplified.last())
  }

  @Test
  fun `a horizontal detour is preserved`() {
    val points = listOf(
      point(46.0, 7.0, 1000.0),
      point(46.001, 7.0045, 1000.0), // ~111 m off the straight line
      point(46.0, 7.009, 1000.0),
    )
    assertEquals(3, TrackSimplifier.simplify(points).size)
  }

  /**
   * The reason this implementation exists: a 3,000 ft switchback apex is
   * horizontally close to the line joining its neighbours, so a purely
   * two-dimensional simplification would delete the hardest part of the day.
   */
  @Test
  fun `an elevation spike is preserved even when horizontally collinear`() {
    val points = listOf(
      point(46.0, 7.0, 1000.0),
      point(46.001, 7.0, 1200.0),
      point(46.002, 7.0, 1000.0),
    )
    val simplified = TrackSimplifier.simplify(points)
    assertEquals(3, simplified.size)
    assertEquals(1200.0, simplified[1].elevationMeters!!, 0.0)
  }

  @Test
  fun `an elevation ramp within tolerance is dropped`() {
    val points = listOf(
      point(46.0, 7.0, 1000.0),
      point(46.001, 7.0, 1001.0),
      point(46.002, 7.0, 1002.0),
    )
    assertEquals(2, TrackSimplifier.simplify(points).size)
  }

  @Test
  fun `looser tolerances never keep more samples`() {
    val points = (0..200).map { index ->
      point(
        latitude = 46.0 + index * 0.0002,
        longitude = 7.0 + 0.0002 * sin(index / 3.0),
        elevation = 1000.0 + 20 * sin(index / 7.0),
      )
    }

    val tight = TrackSimplifier.simplify(points, 1.0, 1.0)
    val medium = TrackSimplifier.simplify(points, 10.0, 5.0)
    val loose = TrackSimplifier.simplify(points, 500.0, 500.0)

    assertTrue(tight.size <= points.size)
    assertTrue(medium.size <= tight.size)
    assertTrue(loose.size <= medium.size)
    assertEquals(2, loose.size)
  }

  @Test
  fun `endpoints always survive`() {
    val points = (0..50).map {
      point(46.0 + it * 0.0005, 7.0 + it * 0.0003, 500.0 + it)
    }
    val simplified = TrackSimplifier.simplify(points, 20.0, 20.0)
    assertEquals(points.first(), simplified.first())
    assertEquals(points.last(), simplified.last())
  }

  @Test
  fun `zero tolerances retain every deviating sample`() {
    val points = listOf(
      point(46.0, 7.0, 1000.0),
      point(46.0005, 7.0, 1005.0),
      point(46.001, 7.0, 1000.0),
    )
    assertEquals(3, TrackSimplifier.simplify(points, 0.0, 0.0).size)
  }

  @Test
  fun `simplifying a track preserves point order`() {
    val points = (0..30).map { point(46.0 + it * 0.001, 7.0, 1000.0 + (it % 5) * 10.0) }
    val simplified = TrackSimplifier.simplify(points, 5.0, 3.0)
    val originalIndices = simplified.map { points.indexOf(it) }
    assertEquals(originalIndices.sorted(), originalIndices)
  }

  @Test
  fun `track overload simplifies the underlying points`() {
    val track = Track.of((0..4).map { point(46.0 + it * 0.001, 7.0, 1000.0) })
    assertEquals(2, TrackSimplifier.simplify(track).size)
  }

  /**
   * Two collinear segments must remain two segments.
   *
   * Merging them would draw a line across ground the hiker never walked, and the
   * flyover's progressive reveal would then cheerfully travel along it.
   */
  @Test
  fun `segments are simplified independently and never merged`() {
    val first = listOf(
      point(46.000, 7.0, 1000.0),
      point(46.001, 7.0, 1000.0),
      point(46.002, 7.0, 1000.0),
    )
    val second = listOf(
      point(47.000, 7.0, 1000.0),
      point(47.001, 7.0, 1000.0),
      point(47.002, 7.0, 1000.0),
    )

    val simplified = TrackSimplifier.simplify(
      Track(listOf(TrackSegment(first), TrackSegment(second))),
    )

    assertEquals(2, simplified.segments.size)
    assertEquals(2, simplified.segments[0].size)
    assertEquals(2, simplified.segments[1].size)
    assertEquals(4, simplified.size)
  }

  private fun point(latitude: Double, longitude: Double, elevation: Double?): TrackPoint =
    TrackPoint(LatLng(latitude, longitude), elevationMeters = elevation)
}
