package com.thruhiker.core.mapping

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonEncoderTest {

  private fun point(latitude: Double, longitude: Double) =
    TrackPoint(LatLng(latitude, longitude))

  /**
   * The single most likely silent bug in this file.
   *
   * GeoJSON is longitude-first; every other coordinate in this codebase is
   * latitude-first. Swapping them does not fail to compile, does not fail to
   * render, and puts the Appalachian Trail in the Pacific Ocean.
   */
  @Test
  fun `coordinates are longitude first`() {
    val track = Track.of(listOf(point(46.5, 7.9), point(46.6, 8.0)))

    val geometry = JSONObject(GeoJsonEncoder.encode(track)).getJSONObject("geometry")
    val first = geometry.getJSONArray("coordinates").getJSONArray(0)

    assertEquals(7.9, first.getDouble(0), 1e-9)
    assertEquals(46.5, first.getDouble(1), 1e-9)
  }

  @Test
  fun `a single segment becomes a line string`() {
    val track = Track.of(listOf(point(1.0, 2.0), point(3.0, 4.0)))

    val geometry = JSONObject(GeoJsonEncoder.encode(track)).getJSONObject("geometry")

    assertEquals("LineString", geometry.getString("type"))
    assertEquals(2, geometry.getJSONArray("coordinates").length())
  }

  /** Gaps stay visible on the map, for the same reason they stay visible in the maths. */
  @Test
  fun `multiple segments become a multi line string`() {
    val track = Track(
      listOf(
        TrackSegment(listOf(point(1.0, 2.0), point(3.0, 4.0))),
        TrackSegment(listOf(point(50.0, 60.0), point(51.0, 61.0), point(52.0, 62.0))),
      ),
    )

    val geometry = JSONObject(GeoJsonEncoder.encode(track)).getJSONObject("geometry")
    val lines = geometry.getJSONArray("coordinates")

    assertEquals("MultiLineString", geometry.getString("type"))
    assertEquals(2, lines.length())
    assertEquals(2, lines.getJSONArray(0).length())
    assertEquals(3, lines.getJSONArray(1).length())
  }

  @Test
  fun `an empty track encodes to an empty multi line string`() {
    val geometry = JSONObject(GeoJsonEncoder.encode(Track.Empty)).getJSONObject("geometry")

    assertEquals("MultiLineString", geometry.getString("type"))
    assertEquals(0, geometry.getJSONArray("coordinates").length())
  }

  @Test
  fun `empty segments are dropped rather than emitted as empty lines`() {
    val track = Track(
      listOf(
        TrackSegment(emptyList()),
        TrackSegment(listOf(point(1.0, 2.0), point(3.0, 4.0))),
      ),
    )

    val geometry = JSONObject(GeoJsonEncoder.encode(track)).getJSONObject("geometry")

    // One real line, so it degrades to the simpler geometry type.
    assertEquals("LineString", geometry.getString("type"))
  }

  @Test
  fun `coordinates are rounded to about eleven centimetres`() {
    val track = Track.of(listOf(point(46.123456789, 7.987654321)))

    val coordinate = JSONObject(GeoJsonEncoder.encode(track))
      .getJSONObject("geometry")
      .getJSONArray("coordinates")
      .getJSONArray(0)

    assertEquals(7.987654, coordinate.getDouble(0), 1e-9)
    assertEquals(46.123457, coordinate.getDouble(1), 1e-9)
  }

  @Test
  fun `the feature wrapper is well formed`() {
    val root = JSONObject(GeoJsonEncoder.encode(Track.of(listOf(point(1.0, 2.0)))))

    assertEquals("Feature", root.getString("type"))
    assertTrue(root.has("geometry"))
    assertTrue(root.has("properties"))
  }
}
