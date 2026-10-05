package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackOperationsTest {

  @Test
  fun `reversing flips the geometry`() {
    val points = straightRun(legs = 5, spacingMeters = 100.0)
    val track = trackOf(points)

    val reversed = TrackOperations.reverse(track)

    assertEquals(points.first().position, reversed.points.last().position)
    assertEquals(points.last().position, reversed.points.first().position)
    assertEquals(points.size, reversed.points.size)
  }

  @Test
  fun `reversing keeps distance but mirrors timestamps`() {
    val points = straightRun(legs = 4, spacingMeters = 100.0, timed = true)
    val track = trackOf(points)

    val reversed = TrackOperations.reverse(track)

    val originalStats = TrackStatsCalculator.compute(track)
    val reversedStats = TrackStatsCalculator.compute(reversed)

    assertEquals(originalStats.distanceMeters, reversedStats.distanceMeters, 1e-6)

    val times = reversed.points.map { it.timeMillis }
    assertTrue(times.all { it != null })
    assertEquals(points.first().timeMillis, times.first())
    assertEquals(points.last().timeMillis, times.last())
    for (index in 1 until times.size) {
      assertTrue(times[index]!! > times[index - 1]!!)
    }
  }

  @Test
  fun `reversing an untimed track just reverses it`() {
    val points = straightRun(legs = 3, spacingMeters = 100.0)
    val reversed = TrackOperations.reverse(trackOf(points))

    assertEquals(points.reversed().map { it.position }, reversed.points.map { it.position })
  }

  @Test
  fun `merging keeps segments apart rather than inventing a link`() {
    val first = trackOf(straightRun(legs = 3, spacingMeters = 100.0))
    val second = trackOf(straightRun(legs = 2, spacingMeters = 100.0, start = LatLng(47.0, 8.0)))

    val merged = TrackOperations.merge(listOf(first, second))

    assertEquals(2, merged.segments.size)
    assertEquals(7, merged.points.size)
    // The distance is the sum of the two files, with nothing counted for the jump between them.
    assertEquals(
      TrackStatsCalculator.compute(first).distanceMeters +
        TrackStatsCalculator.compute(second).distanceMeters,
      TrackStatsCalculator.compute(merged).distanceMeters,
      1e-6,
    )
  }

  @Test
  fun `densifying respects the spacing and keeps the endpoints`() {
    val points = listOf(LatLng(46.0, 7.0), LatLng(46.0, 7.05))

    val dense = TrackOperations.densifyPositions(points, maximumSpacingMeters = 100.0)

    assertEquals(points.first(), dense.first())
    assertEquals(points.last(), dense.last())
    assertTrue(dense.size > 30)

    for (index in 1 until dense.size) {
      val leg = Geodesic.distanceMeters(dense[index - 1], dense[index])
      assertTrue("leg $index was $leg m", leg <= 100.0 + 1e-6)
    }

    assertEquals(
      Geodesic.pathLengthMeters(points),
      Geodesic.pathLengthMeters(dense),
      1e-6,
    )
  }

  @Test
  fun `densifying leaves short legs alone`() {
    val points = listOf(LatLng(46.0, 7.0), LatLng(46.000_1, 7.0))
    val dense = TrackOperations.densifyPositions(points, maximumSpacingMeters = 100.0)
    assertEquals(points, dense)
  }

  @Test
  fun `densifying interpolates elevation along the leg`() {
    val waypoints = listOf(
      TrackPoint(LatLng(46.0, 7.0), elevationMeters = 1000.0),
      TrackPoint(LatLng(46.0, 7.02), elevationMeters = 1200.0),
    )

    val dense = TrackOperations.densify(waypoints, maximumSpacingMeters = 100.0)

    assertTrue(dense.size > 10)
    assertEquals(1000.0, dense.first().elevationMeters!!, 1e-9)
    assertEquals(1200.0, dense.last().elevationMeters!!, 1e-9)
    // Monotone, and every sample between the two ends.
    assertTrue(dense.all { it.elevationMeters!! in 1000.0..1200.0 })
    for (index in 1 until dense.size) {
      assertTrue(dense[index].elevationMeters!! >= dense[index - 1].elevationMeters!!)
    }

    val stats = TrackStatsCalculator.compute(Track.of(dense))
    assertEquals(200.0, stats.elevationGainMeters, 1.0)
  }

  @Test
  fun `densifying invents no elevation when only one end has any`() {
    val waypoints = listOf(
      TrackPoint(LatLng(46.0, 7.0), elevationMeters = 1000.0),
      TrackPoint(LatLng(46.0, 7.02)),
    )

    val dense = TrackOperations.densify(waypoints, maximumSpacingMeters = 100.0)

    // The first waypoint keeps the height it was given; nothing after it invents one.
    assertEquals(1000.0, dense.first().elevationMeters!!, 1e-9)
    assertTrue(dense.drop(1).all { it.elevationMeters == null })
  }

  @Test
  fun `waypoints become a measurable track with no elevation`() {
    val waypoints = listOf(LatLng(46.0, 7.0), LatLng(46.01, 7.0), LatLng(46.01, 7.02))

    val track = TrackOperations.fromPositions(waypoints)

    assertTrue(track.points.size > 3)
    assertTrue(track.points.all { it.elevationMeters == null })
    assertEquals(2.66, TrackStatsCalculator.compute(track).distanceMeters / 1000.0, 0.1)
  }

  @Test
  fun `a closed loop returns to its first waypoint`() {
    val waypoints = listOf(LatLng(46.0, 7.0), LatLng(46.01, 7.01), LatLng(46.0, 7.02))

    val track = TrackOperations.closedLoop(waypoints)

    assertEquals(waypoints.first(), track.points.first().position)
    assertEquals(waypoints.first(), track.points.last().position)
  }

  @Test
  fun `merging nothing is an empty track`() {
    assertEquals(0, TrackOperations.merge(emptyList()).size)
    assertEquals(1, TrackOperations.merge(listOf(trackOf(straightRun(legs = 2)))).segments.size)
  }

  @Test
  fun `an empty segment is dropped when merging`() {
    val withEmpty = listOf(
      com.thruhiker.core.model.Track(listOf(TrackSegment.Empty)),
      trackOf(straightRun(legs = 2)),
    )

    assertNotEquals(0, TrackOperations.merge(withEmpty).segments.size)
    assertEquals(1, TrackOperations.merge(withEmpty).segments.size)
  }

  @Test
  fun `densify rejects a non-positive spacing`() {
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      TrackOperations.densifyPositions(listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0)), 0.0)
    }
  }
}
