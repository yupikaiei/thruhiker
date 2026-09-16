package com.thruhiker.core.gpx

import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export then import is the loop a hiker actually lives in: record on the phone,
 * export to a GPS watch or a friend, import it back later. If it loses a metre or
 * a segment on the way through, that is a bug that shows up months into a trail.
 */
class GpxRoundTripTest {

  private val track = Track(
    listOf(
      TrackSegment(
        listOf(
          TrackPoint(
            position = LatLng(46.1234567, 7.7654321),
            elevationMeters = 1234.56,
            timeMillis = 1_774_000_000_000L,
          ),
          TrackPoint(
            position = LatLng(46.1234568, 7.7654322),
            elevationMeters = 1300.0,
            timeMillis = 1_774_000_060_000L,
          ),
        ),
      ),
      TrackSegment(
        listOf(
          TrackPoint(
            position = LatLng(46.2, 7.8),
            elevationMeters = 1000.0,
            timeMillis = 1_774_003_600_000L,
          ),
        ),
      ),
    ),
  )

  @Test
  fun `a written track reads back with the same shape`() {
    val document = GpxDocument(
      name = "Test & <trail>",
      description = "Round \"trip\"",
      track = track,
    )

    val reparsed = GpxParser.parse(GpxWriter.write(document))

    assertEquals("Test & <trail>", reparsed.name)
    assertEquals("Round \"trip\"", reparsed.description)
    assertEquals(2, reparsed.track.segments.size)
    assertEquals(2, reparsed.track.segments[0].size)
    assertEquals(1, reparsed.track.segments[1].size)
    assertEquals(3, reparsed.track.size)
  }

  @Test
  fun `coordinates survive to the centimetre`() {
    val reparsed = GpxParser.parse(GpxWriter.write(track))
    val original = track.points[0].position
    val restored = reparsed.track.points[0].position

    assertTrue(
      "coordinate moved ${Geodesic.distanceMeters(original, restored)} m",
      Geodesic.distanceMeters(original, restored) < 0.02,
    )
  }

  @Test
  fun `elevations and times survive exactly`() {
    val reparsed = GpxParser.parse(GpxWriter.write(track))

    for ((original, restored) in track.points.zip(reparsed.track.points)) {
      assertNotNull(restored.elevationMeters)
      assertEquals(original.elevationMeters!!, restored.elevationMeters!!, 0.005)
      assertEquals(original.timeMillis, restored.timeMillis)
    }
  }

  @Test
  fun `waypoints survive the round trip`() {
    val document = GpxDocument(
      name = "With waypoints",
      track = track,
      waypoints = listOf(
        GpxWaypoint("Water cache", TrackPoint(LatLng(32.6, -116.4), elevationMeters = 1100.0)),
      ),
    )

    val reparsed = GpxParser.parse(GpxWriter.write(document))

    assertEquals(1, reparsed.waypoints.size)
    assertEquals("Water cache", reparsed.waypoints[0].name)
    assertEquals(32.6, reparsed.waypoints[0].point.position.latitude, 1e-7)
  }

  @Test
  fun `an empty track still produces a document that parses`() {
    val reparsed = GpxParser.parse(GpxWriter.write(Track.Empty, name = "Empty"))

    assertTrue(reparsed.track.isEmpty)
    assertEquals("Empty", reparsed.name)
  }

  @Test
  fun `the header is a namespaced gpx 1_1 document`() {
    val xml = GpxWriter.write(track, name = "Header")

    assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
    assertTrue(xml.contains("version=\"1.1\""))
    assertTrue(xml.contains("creator=\"${GpxWriter.CREATOR}\""))
    assertTrue(xml.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
    assertTrue(xml.trimEnd().endsWith("</gpx>"))
  }

  /** A name with markup in it must not be able to break the file it is written into. */
  @Test
  fun `xml metacharacters are escaped`() {
    val xml = GpxWriter.write(track, name = "Mile 100 <water> & \"shade\"")

    assertTrue(xml.contains("&lt;water&gt;"))
    assertTrue(xml.contains("&amp;"))
    assertTrue(xml.contains("&quot;"))
    assertTrue(!xml.contains("<water>"))
  }
}
