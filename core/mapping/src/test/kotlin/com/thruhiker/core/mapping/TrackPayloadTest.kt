package com.thruhiker.core.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the route source is handed, and what the route layers are told to do with it.
 *
 * MapLibre does not clear a GeoJSON source when it is given a geometry with no coordinates,
 * and — measured on the device — it does not clear it when it is given an empty
 * FeatureCollection either: the geometry from the previous update stays on the map and keeps
 * being drawn. An empty reveal is therefore two things: well-formed empty data for the next
 * update to build on, and hidden route layers, which is the part that actually stops the
 * route being drawn.
 */
class TrackPayloadTest {

  private val emptyReveal =
    """{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[]}}"""

  private val partialReveal =
    """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[6.8,45.9],[6.9,46.0]]}}"""

  @Test
  fun `an empty reveal becomes a clearing update`() {
    assertEquals(NO_TRACK, trackPayloadFor(emptyReveal))
  }

  @Test
  fun `the clearing update is a feature collection with no features`() {
    assertTrue(NO_TRACK.contains("\"FeatureCollection\""))
    assertTrue(NO_TRACK.contains("\"features\":[]"))
  }

  @Test
  fun `a real reveal is passed through untouched`() {
    assertEquals(partialReveal, trackPayloadFor(partialReveal))
  }
}
