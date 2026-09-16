package com.thruhiker.core.mapping

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleFactoryTest {

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

  private fun styleOf(
    base: String = baseStyle,
    exaggeration: Double = MapStyleFactory.DEFAULT_EXAGGERATION,
  ): JSONObject = JSONObject(MapStyleFactory.withTerrain(base, exaggeration))

  private fun JSONObject.layerIds(): List<String> {
    val layers = getJSONArray("layers")
    return (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
  }

  /**
   * Terrarium is one of several DEM encodings. Declaring the wrong one does not
   * fail loudly; it renders mountains as ocean, so it is worth pinning.
   */
  @Test
  fun `terrain source is a terrarium raster dem`() {
    val terrain = styleOf().getJSONObject("sources").getJSONObject(MapStyleFactory.TERRAIN_SOURCE_ID)

    assertEquals("raster-dem", terrain.getString("type"))
    assertEquals("terrarium", terrain.getString("encoding"))
    assertEquals(MapStyleFactory.MAX_TERRAIN_ZOOM, terrain.getInt("maxzoom"))
    assertEquals(
      MapStyleFactory.TERRAIN_TILES_URL,
      terrain.getJSONArray("tiles").getString(0),
    )
    assertNotNull(terrain.getString("attribution"))
  }

  @Test
  fun `terrain property points at the terrain source`() {
    val style = styleOf()
    val terrain = style.getJSONObject("terrain")

    assertEquals(MapStyleFactory.TERRAIN_SOURCE_ID, terrain.getString("source"))
    assertEquals(MapStyleFactory.DEFAULT_EXAGGERATION, terrain.getDouble("exaggeration"), 1e-9)
  }

  @Test
  fun `exaggeration is configurable`() {
    val style = styleOf(exaggeration = 2.5)
    assertEquals(2.5, style.getJSONObject("terrain").getDouble("exaggeration"), 1e-9)
  }

  @Test
  fun `hillshade sits above the map layers and below the labels`() {
    val ids = styleOf().layerIds()

    val hillshade = ids.indexOf(MapStyleFactory.HILLSHADE_LAYER_ID)
    assertTrue("hillshade layer should exist", hillshade >= 0)
    assertTrue("roads should render below shading", ids.indexOf("road") < hillshade)
    assertTrue(
      "place labels must stay above shading so they remain legible",
      ids.indexOf("place-labels") > hillshade,
    )
  }

  @Test
  fun `hillshade is bound to the terrain source`() {
    val style = styleOf()
    val layers = style.getJSONArray("layers")
    val hillshade = (0 until layers.length())
      .map { layers.getJSONObject(it) }
      .first { it.getString("id") == MapStyleFactory.HILLSHADE_LAYER_ID }

    assertEquals("hillshade", hillshade.getString("type"))
    assertEquals(MapStyleFactory.TERRAIN_SOURCE_ID, hillshade.getString("source"))
    assertTrue(hillshade.getJSONObject("paint").has("hillshade-exaggeration"))
  }

  @Test
  fun `a sky layer is added`() {
    val sky = styleOf().getJSONObject("sky")
    assertTrue(sky.has("sky-color"))
    assertTrue(sky.has("horizon-color"))
    assertTrue(sky.has("fog-color"))
  }

  @Test
  fun `existing sources and layers are preserved`() {
    val style = styleOf()
    val sources = style.getJSONObject("sources")

    assertTrue("the basemap source must survive", sources.has("openmaptiles"))
    assertEquals(2, sources.length())

    val ids = style.layerIds()
    assertEquals(5, ids.size)
    assertEquals(listOf("background", "water", "road"), ids.take(3))
    assertTrue(ids.contains("place-labels"))
  }

  /** The function is called on every style reload, so it must not accumulate layers. */
  @Test
  fun `applying terrain twice is idempotent`() {
    val once = MapStyleFactory.withTerrain(baseStyle)
    val twice = MapStyleFactory.withTerrain(once)

    assertEquals(once, twice)

    val ids = JSONObject(twice).layerIds()
    assertEquals(
      1,
      ids.count { it == MapStyleFactory.HILLSHADE_LAYER_ID },
    )
  }

  @Test
  fun `a style with no sources or layers still gains terrain`() {
    val style = styleOf(base = """{"version": 8}""")

    assertTrue(style.getJSONObject("sources").has(MapStyleFactory.TERRAIN_SOURCE_ID))
    assertEquals(listOf(MapStyleFactory.HILLSHADE_LAYER_ID), style.layerIds())
    assertTrue(style.has("terrain"))
  }

  @Test
  fun `the open free map style url is keyless https`() {
    assertTrue(MapStyleFactory.OPEN_FREE_MAP_STYLE_URL.startsWith("https://"))
    assertTrue(MapStyleFactory.TERRAIN_TILES_URL.startsWith("https://"))
  }
}
