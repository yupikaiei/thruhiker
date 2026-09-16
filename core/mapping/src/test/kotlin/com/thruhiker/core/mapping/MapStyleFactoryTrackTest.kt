package com.thruhiker.core.mapping

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleFactoryTrackTest {

  private val baseStyle = """
    {
      "version": 8,
      "sources": {
        "openmaptiles": { "type": "vector", "url": "https://example.invalid/tiles.json" }
      },
      "layers": [
        { "id": "background", "type": "background" },
        { "id": "water", "type": "fill" },
        { "id": "road", "type": "line" },
        { "id": "place-labels", "type": "symbol" }
      ]
    }
  """.trimIndent()

  private val trackGeoJson = """
    {"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[7.9,46.5],[8.0,46.6]]}}
  """.trimIndent()

  private fun JSONObject.layerIds(): List<String> {
    val layers = getJSONArray("layers")
    return (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
  }

  private fun layer(style: JSONObject, id: String): JSONObject {
    val layers = style.getJSONArray("layers")
    return (0 until layers.length())
      .map { layers.getJSONObject(it) }
      .first { it.getString("id") == id }
  }

  @Test
  fun `the track is added as a geojson source`() {
    val style = JSONObject(MapStyleFactory.withTrackLine(baseStyle, trackGeoJson))

    val source = style.getJSONObject("sources").getJSONObject(MapStyleFactory.TRACK_SOURCE_ID)

    assertEquals("geojson", source.getString("type"))
    assertEquals(
      "LineString",
      source.getJSONObject("data").getJSONObject("geometry").getString("type"),
    )
  }

  @Test
  fun `the route is drawn as a casing plus an inner line`() {
    val style = JSONObject(MapStyleFactory.withTrackLine(baseStyle, trackGeoJson))

    val casing = layer(style, MapStyleFactory.TRACK_CASING_LAYER_ID)
    val line = layer(style, MapStyleFactory.TRACK_LAYER_ID)

    assertEquals("line", casing.getString("type"))
    assertEquals("line", line.getString("type"))
    assertEquals(MapStyleFactory.TRACK_SOURCE_ID, line.getString("source"))
    assertEquals(
      MapStyleFactory.DEFAULT_TRACK_COLOR,
      line.getJSONObject("paint").getString("line-color"),
    )
  }

  @Test
  fun `the casing renders beneath the bright line`() {
    val ids = JSONObject(MapStyleFactory.withTrackLine(baseStyle, trackGeoJson)).layerIds()

    assertTrue(
      ids.indexOf(MapStyleFactory.TRACK_CASING_LAYER_ID) <
        ids.indexOf(MapStyleFactory.TRACK_LAYER_ID),
    )
  }

  /** The reason the casing exists: legibility over snow, forest and rock alike. */
  @Test
  fun `the casing is wider than the line`() {
    val style = JSONObject(MapStyleFactory.withTrackLine(baseStyle, trackGeoJson))

    val casingWidth = layer(style, MapStyleFactory.TRACK_CASING_LAYER_ID)
      .getJSONObject("paint")
      .getJSONArray("line-width")
    val lineWidth = layer(style, MapStyleFactory.TRACK_LAYER_ID)
      .getJSONObject("paint")
      .getJSONArray("line-width")

    // ["interpolate", ["linear"], ["zoom"], 6, w, 14, w]
    assertEquals(3.0, casingWidth.getDouble(4), 1e-9)
    assertEquals(1.5, lineWidth.getDouble(4), 1e-9)
  }

  @Test
  fun `line width grows with zoom`() {
    val width = layer(JSONObject(MapStyleFactory.withTrackLine(baseStyle, trackGeoJson)), MapStyleFactory.TRACK_LAYER_ID)
      .getJSONObject("paint")
      .getJSONArray("line-width")

    val atOverview = width.getDouble(4)
    val atTrail = width.getDouble(6)
    assertTrue("a route line should thicken as you zoom in", atTrail > atOverview)
  }

  @Test
  fun `the route renders above the basemap but below labels`() {
    val ids = JSONObject(MapStyleFactory.withTrackLine(baseStyle, trackGeoJson)).layerIds()

    val road = ids.indexOf("road")
    val casing = ids.indexOf(MapStyleFactory.TRACK_CASING_LAYER_ID)
    val line = ids.indexOf(MapStyleFactory.TRACK_LAYER_ID)
    val labels = ids.indexOf("place-labels")

    assertTrue(road < casing)
    assertTrue(line < labels)
  }

  @Test
  fun `the route renders above the hillshade`() {
    val style = JSONObject(
      MapStyleFactory.withTerrainAndTrack(baseStyle, trackGeoJson),
    )
    val ids = style.layerIds()

    val hillshade = ids.indexOf(MapStyleFactory.HILLSHADE_LAYER_ID)
    val casing = ids.indexOf(MapStyleFactory.TRACK_CASING_LAYER_ID)
    val labels = ids.indexOf("place-labels")

    assertTrue("shading must not cover the route", hillshade < casing)
    assertTrue(labels > casing)
  }

  @Test
  fun `terrain and track can be applied in either order with the same result`() {
    val terrainFirst = JSONObject(MapStyleFactory.withTerrainAndTrack(baseStyle, trackGeoJson))
    val trackFirst = JSONObject(
      MapStyleFactory.withTrackLine(
        MapStyleFactory.withTerrain(baseStyle),
        trackGeoJson,
      ),
    )

    // Compared structurally rather than as raw strings: org.json serialises from an
    // unordered map, so identical documents need not be byte-identical.
    assertEquals(terrainFirst.layerIds(), trackFirst.layerIds())
    assertEquals(
      terrainFirst.getJSONObject("sources").keys().asSequence().sorted().toList(),
      trackFirst.getJSONObject("sources").keys().asSequence().sorted().toList(),
    )
    assertEquals(
      terrainFirst.getJSONObject("terrain").getString("source"),
      trackFirst.getJSONObject("terrain").getString("source"),
    )
  }

  @Test
  fun `rebuilding the style does not duplicate layers`() {
    val once = MapStyleFactory.withTerrainAndTrack(baseStyle, trackGeoJson)
    val twice = MapStyleFactory.withTerrainAndTrack(once, trackGeoJson)

    val ids = JSONObject(twice).layerIds()
    assertEquals(1, ids.count { it == MapStyleFactory.TRACK_LAYER_ID })
    assertEquals(1, ids.count { it == MapStyleFactory.TRACK_CASING_LAYER_ID })
    assertEquals(1, ids.count { it == MapStyleFactory.HILLSHADE_LAYER_ID })
  }

  @Test
  fun `a custom track colour is honoured`() {
    val style = JSONObject(
      MapStyleFactory.withTrackLine(baseStyle, trackGeoJson, color = "#00E5FF"),
    )

    assertEquals(
      "#00E5FF",
      layer(style, MapStyleFactory.TRACK_LAYER_ID).getJSONObject("paint").getString("line-color"),
    )
  }

  @Test
  fun `omitting the track leaves a terrain only style`() {
    val style = JSONObject(MapStyleFactory.withTerrainAndTrack(baseStyle, trackGeoJson = null))

    assertTrue(!style.getJSONObject("sources").has(MapStyleFactory.TRACK_SOURCE_ID))
    assertTrue(!style.layerIds().contains(MapStyleFactory.TRACK_LAYER_ID))
    assertTrue(style.layerIds().contains(MapStyleFactory.HILLSHADE_LAYER_ID))
  }

  @Test
  fun `basemap layers and sources survive`() {
    val style = JSONObject(MapStyleFactory.withTerrainAndTrack(baseStyle, trackGeoJson))

    assertTrue(style.getJSONObject("sources").has("openmaptiles"))
    assertTrue(style.layerIds().contains("water"))
    assertTrue(style.layerIds().contains("road"))
  }
}
