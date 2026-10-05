package com.thruhiker.core.dem

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackElevationFillerTest {

  /** A hill climbing 20 m per 0.001 degrees of latitude — steep, but ordinary ground. */
  private val ramp: (LatLng) -> Double = { position -> 1_200.0 + (position.latitude - 45.8) * 20_000.0 }

  private fun drawn(count: Int, fromLatitude: Double = 45.8, step: Double = 0.0005): Track =
    Track.of(
      (0 until count).map { index ->
        TrackPoint(LatLng(fromLatitude + step * index, 6.8652))
      },
    )

  @Test
  fun `a drawn route gains the heights of the ground it crosses`() {
    val track = drawn(count = 12)
    val result = TrackElevationFiller(SyntheticDem(ramp)).fill(track)

    assertNotNull(result)
    val filled = result!!.track.points
    assertEquals(12, result.pointsFilled)
    assertEquals(12, filled.size)
    assertTrue(filled.all { it.elevationMeters != null })

    // The surface was recovered, not invented.
    filled.forEachIndexed { index, point ->
      val expected = ramp(point.position)
      assertEquals("point $index", expected, point.elevationMeters!!, 1.0)
    }
  }

  @Test
  fun `the filled profile actually climbs`() {
    // The whole point of filling a drawn route: without variation there is no gradient to
    // colour and nothing for the profile to draw.
    val filled = TrackElevationFiller(SyntheticDem(ramp)).fill(drawn(count = 20))!!.track.points

    val first = filled.first().elevationMeters!!
    val last = filled.last().elevationMeters!!
    assertTrue("expected a climb, got $first -> $last", last > first + 10.0)
  }

  /**
   * A recording's own elevation is barometric or from a surveyed track file. The DEM is a
   * global model with tens of metres of error in steep ground, so it must never be allowed to
   * overwrite what the device measured.
   */
  @Test
  fun `a recorded elevation is never overwritten`() {
    val track = Track.of(
      listOf(
        TrackPoint(LatLng(45.80, 6.8652), elevationMeters = 1_111.0),
        TrackPoint(LatLng(45.8005, 6.8652)),
        TrackPoint(LatLng(45.8010, 6.8652), elevationMeters = 2_222.0),
      ),
    )

    val result = TrackElevationFiller(SyntheticDem(ramp)).fill(track)

    assertNotNull(result)
    assertEquals(1, result!!.pointsFilled)
    val points = result.track.points
    assertEquals(1_111.0, points[0].elevationMeters!!, 1e-9)
    assertEquals(2_222.0, points[2].elevationMeters!!, 1e-9)
    assertNotNull(points[1].elevationMeters)
  }

  @Test
  fun `a route that already has elevations is not touched`() {
    val track = Track.of(
      listOf(
        TrackPoint(LatLng(45.80, 6.8652), elevationMeters = 1_000.0),
        TrackPoint(LatLng(45.81, 6.8652), elevationMeters = 1_100.0),
      ),
    )

    val source = SyntheticDem(ramp)
    assertNull(TrackElevationFiller(source).fill(track))
    assertEquals("no tile should have been fetched", 0, source.tilesServed)
  }

  @Test
  fun `terrain that cannot be read leaves the route as it was`() {
    val track = drawn(count = 5)

    assertNull(TrackElevationFiller(FixedDem(null)).fill(track))
  }

  @Test
  fun `a short route is sampled at trail resolution`() {
    val result = TrackElevationFiller(SyntheticDem(ramp)).fill(drawn(count = 10))

    assertEquals(DemSampler.DEFAULT_ZOOM, result!!.zoom)
    assertTrue(result.tiles <= TrackElevationFiller.DEFAULT_MAXIMUM_TILES)
  }

  @Test
  fun `a long route is sampled at a coarser zoom rather than refused`() {
    // 800 km of longitudes pushed through a fixed default budget.
    val track = Track.of(spreadPositions(count = 200, spanDegrees = 8.0).map { TrackPoint(it) })
    val filler = TrackElevationFiller(SyntheticDem { 1_500.0 }, maximumTiles = 8)

    val result = filler.fill(track)

    assertNotNull(result)
    assertTrue("expected a coarser zoom, got ${result!!.zoom}", result.zoom < DemSampler.DEFAULT_ZOOM)
    assertTrue(result.zoom >= TrackElevationFiller.MINIMUM_ZOOM)
    assertTrue(result.tiles <= 8)
    assertEquals(200, result.pointsFilled)
  }

  @Test
  fun `a route too large for even the coarsest zoom is left alone`() {
    val track = Track.of(spreadPositions(count = 200, spanDegrees = 8.0).map { TrackPoint(it) })
    val source = SyntheticDem { 1_500.0 }

    assertNull(TrackElevationFiller(source, maximumTiles = 2).fill(track))
    assertEquals("nothing should have been fetched", 0, source.tilesServed)
  }

  @Test
  fun `segments survive the fill`() {
    val track = Track(
      listOf(
        TrackSegment((0..3).map { TrackPoint(LatLng(45.80 + it * 0.001, 6.8652)) }),
        TrackSegment((0..6).map { TrackPoint(LatLng(45.85 + it * 0.001, 6.8652)) }),
      ),
    )

    val result = TrackElevationFiller(SyntheticDem(ramp)).fill(track)!!

    assertEquals(2, result.track.segments.size)
    assertEquals(listOf(4, 7), result.track.segments.map { it.points.size })
    assertTrue(result.track.points.all { it.elevationMeters != null })
  }

  /**
   * Offline, a route over a stretch of terrain with no cached tiles should still come back
   * with whatever could be read — half a profile is still a profile.
   */
  @Test
  fun `terrain that is only partly available still fills what it can`() {
    val track = Track.of(spreadPositions(count = 9, spanDegrees = 0.09).map { TrackPoint(it) })
    val covered = SlippyTile.tilesCovering(track.positions, DemSampler.DEFAULT_ZOOM)
    assertTrue("expected the route to span several tiles", covered.size >= 2)
    val missing = covered.first()

    val result = TrackElevationFiller(
      PartialDem(heightAt = { 1_400.0 }, available = { it != missing }),
    ).fill(track)

    assertNotNull(result)
    assertTrue(result!!.pointsFilled in 1 until 9)
    assertTrue(result.track.points.any { it.elevationMeters == null })
  }

  @Test
  fun `the reported tile count matches the coverage`() {
    val track = drawn(count = 30)
    val result = TrackElevationFiller(SyntheticDem(ramp)).fill(track)!!

    assertEquals(
      SlippyTile.tilesCovering(track.positions, result.zoom).size,
      result.tiles,
    )
  }
}
