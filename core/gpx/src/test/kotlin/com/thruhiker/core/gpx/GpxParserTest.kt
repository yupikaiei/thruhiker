package com.thruhiker.core.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxParserTest {

  private val gpx11 = """
    <?xml version="1.0" encoding="UTF-8"?>
    <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
      <metadata>
        <name>PCT Section A</name>
        <desc>Southern California</desc>
      </metadata>
      <wpt lat="32.6" lon="-116.4">
        <name>Water cache</name>
        <ele>1100.0</ele>
      </wpt>
      <trk>
        <name>Day 1</name>
        <trkseg>
          <trkpt lat="32.5" lon="-116.5"><ele>1000.0</ele><time>2026-04-01T06:00:00Z</time></trkpt>
          <trkpt lat="32.51" lon="-116.51"><ele>1010.0</ele><time>2026-04-01T06:30:00Z</time></trkpt>
        </trkseg>
        <trkseg>
          <trkpt lat="32.8" lon="-116.8"><ele>1500.0</ele><time>2026-04-01T12:00:00Z</time></trkpt>
          <trkpt lat="32.81" lon="-116.81"><ele>1520.0</ele><time>2026-04-01T12:30:00Z</time></trkpt>
        </trkseg>
      </trk>
    </gpx>
  """.trimIndent()

  @Test
  fun `metadata name and description are read`() {
    val document = GpxParser.parse(gpx11)
    assertEquals("PCT Section A", document.name)
    assertEquals("Southern California", document.description)
  }

  @Test
  fun `segments are kept apart`() {
    val document = GpxParser.parse(gpx11)

    assertEquals(2, document.track.segments.size)
    assertEquals(2, document.track.segments[0].size)
    assertEquals(2, document.track.segments[1].size)
    assertEquals(4, document.track.size)

    // The gap between the segments is 30 km; if the parser collapsed them the two
    // ends would be treated as neighbours.
    assertEquals(32.51, document.track.segments[0].points.last().position.latitude, 1e-9)
    assertEquals(32.8, document.track.segments[1].points.first().position.latitude, 1e-9)
  }

  @Test
  fun `elevation and time are read from track points`() {
    val document = GpxParser.parse(gpx11)
    val point = document.track.segments[0].points[0]

    assertEquals(1000.0, point.elevationMeters!!, 1e-9)
    assertNotNull(point.timeMillis)
  }

  @Test
  fun `timestamps keep their separation`() {
    val document = GpxParser.parse(gpx11)
    val first = document.track.segments[0].points[0].timeMillis!!
    val second = document.track.segments[0].points[1].timeMillis!!

    assertEquals(30 * 60 * 1000L, second - first)
  }

  @Test
  fun `waypoints are read with their names`() {
    val document = GpxParser.parse(gpx11)

    assertEquals(1, document.waypoints.size)
    assertEquals("Water cache", document.waypoints[0].name)
    assertEquals(1100.0, document.waypoints[0].point.elevationMeters!!, 1e-9)
    assertEquals(32.6, document.waypoints[0].point.position.latitude, 1e-9)
  }

  /** GPX 1.0 has no namespace at all, and files in the wild have no version discipline. */
  @Test
  fun `gpx 1_0 without a namespace parses`() {
    val xml = """
      <?xml version="1.0"?>
      <gpx version="1.0" creator="test">
        <trk><name>Old school</name><trkseg>
          <trkpt lat="1.0" lon="2.0"><ele>50.0</ele></trkpt>
        </trkseg></trk>
      </gpx>
    """.trimIndent()

    val document = GpxParser.parse(xml)

    assertEquals(1, document.track.segments.size)
    assertEquals(1.0, document.track.points[0].position.latitude, 1e-9)
    assertEquals(50.0, document.track.points[0].elevationMeters!!, 1e-9)
  }

  @Test
  fun `a prefixed namespace is matched by local name`() {
    val xml = """
      <?xml version="1.0"?>
      <g:gpx xmlns:g="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
        <g:trk><g:trkseg>
          <g:trkpt lat="3.0" lon="4.0"><g:ele>75.0</g:ele></g:trkpt>
        </g:trkseg></g:trk>
      </g:gpx>
    """.trimIndent()

    val document = GpxParser.parse(xml)

    assertEquals(1, document.track.size)
    assertEquals(3.0, document.track.points[0].position.latitude, 1e-9)
    assertEquals(75.0, document.track.points[0].elevationMeters!!, 1e-9)
  }

  /** A planned route and a recorded track are the same thing to everything downstream. */
  @Test
  fun `routes are folded in as segments`() {
    val xml = """
      <?xml version="1.0"?>
      <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <rte>
          <name>Planned</name>
          <rtept lat="5.0" lon="6.0"/>
          <rtept lat="5.1" lon="6.1"/>
        </rte>
      </gpx>
    """.trimIndent()

    val document = GpxParser.parse(xml)

    assertEquals(1, document.track.segments.size)
    assertEquals(2, document.track.size)
  }

  /** The schema forbids it, exporters emit it anyway. */
  @Test
  fun `trkpt directly under trk is still read`() {
    val xml = """
      <?xml version="1.0"?>
      <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <trk>
          <trkpt lat="7.0" lon="8.0"/>
          <trkpt lat="7.1" lon="8.1"/>
        </trk>
      </gpx>
    """.trimIndent()

    val document = GpxParser.parse(xml)

    assertEquals(1, document.track.segments.size)
    assertEquals(2, document.track.size)
  }

  /** One bad sample in a 100,000-point file must not cost the whole file. */
  @Test
  fun `unusable points are skipped rather than fatal`() {
    val xml = """
      <?xml version="1.0"?>
      <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <trk><trkseg>
          <trkpt lat="9.0" lon="10.0"/>
          <trkpt lon="10.1"/>
          <trkpt lat="9.2"/>
          <trkpt lat="91.0" lon="10.2"/>
          <trkpt lat="9.3" lon="10.3"/>
        </trkseg></trk>
      </gpx>
    """.trimIndent()

    val document = GpxParser.parse(xml)

    assertEquals(2, document.track.size)
  }

  @Test
  fun `a missing elevation or time is null rather than zero`() {
    val xml = """
      <?xml version="1.0"?>
      <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <trk><trkseg><trkpt lat="1.0" lon="1.0"/></trkseg></trk>
      </gpx>
    """.trimIndent()

    val point = GpxParser.parse(xml).track.points[0]

    assertNull(point.elevationMeters)
    assertNull(point.timeMillis)
  }

  @Test
  fun `a timestamp without a zone is read as utc`() {
    val withZone = """
      <?xml version="1.0"?>
      <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <trk><trkseg><trkpt lat="1.0" lon="1.0"><time>2026-04-01T08:00:00+02:00</time></trkpt></trkseg></trk>
      </gpx>
    """.trimIndent()

    val withoutZone = """
      <?xml version="1.0"?>
      <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <trk><trkseg><trkpt lat="1.0" lon="1.0"><time>2026-04-01T06:00:00</time></trkpt></trkseg></trk>
      </gpx>
    """.trimIndent()

    val zoned = GpxParser.parse(withZone).track.points[0].timeMillis
    val naive = GpxParser.parse(withoutZone).track.points[0].timeMillis

    assertNotNull(zoned)
    assertEquals(zoned, naive)
  }

  @Test
  fun `an unparseable timestamp is null rather than throwing`() {
    val xml = """
      <?xml version="1.0"?>
      <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <trk><trkseg><trkpt lat="1.0" lon="1.0"><time>last Tuesday</time></trkpt></trkseg></trk>
      </gpx>
    """.trimIndent()

    assertNull(GpxParser.parse(xml).track.points[0].timeMillis)
  }

  @Test
  fun `a non gpx root element is rejected`() {
    val xml = """<?xml version="1.0"?><kml><Document/></kml>"""

    val exception = assertThrows(GpxParseException::class.java) { GpxParser.parse(xml) }
    assertTrue(exception.message!!.contains("kml"))
  }

  @Test
  fun `malformed xml is rejected with a useful message`() {
    val exception = assertThrows(GpxParseException::class.java) {
      GpxParser.parse("<gpx><trk></gpx>")
    }
    assertTrue(exception.message!!.contains("Could not read GPX XML"))
  }

  /**
   * GPX arrives from strangers on the internet. A parser that expands entities on
   * request will happily read `/etc/passwd` for whoever asked.
   */
  @Test
  fun `a doctype is refused rather than expanded`() {
    val xml = """
      <?xml version="1.0"?>
      <!DOCTYPE gpx [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
      <gpx version="1.1" creator="test"><trk><name>&xxe;</name></trk></gpx>
    """.trimIndent()

    assertThrows(GpxParseException::class.java) { GpxParser.parse(xml) }
  }

  @Test
  fun `an empty document parses to an empty track`() {
    val xml = """
      <?xml version="1.0"?>
      <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1"></gpx>
    """.trimIndent()

    val document = GpxParser.parse(xml)

    assertTrue(document.track.isEmpty)
    assertTrue(document.isEmpty)
  }
}
