package com.thruhiker.core.mapping

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleFactoryPlanningTest {

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

  private fun styled(): JSONObject = JSONObject(
    MapStyleFactory.withWaypoints(
      MapStyleFactory.withGradientLine(baseStyle, GeoJsonEncoder.EMPTY_COLLECTION),
      GeoJsonEncoder.EMPTY_COLLECTION,
    ),
  )

  @Test
  fun `the gradient route is added as a geojson source`() {
    val style = JSONObject(MapStyleFactory.withGradientLine(baseStyle, GeoJsonEncoder.EMPTY_COLLECTION))

    val source = style.getJSONObject("sources").getJSONObject(MapStyleFactory.GRADIENT_SOURCE_ID)

    assertEquals("geojson", source.getString("type"))
  }

  @Test
  fun `the gradient route is a casing plus a coloured line`() {
    val style = JSONObject(MapStyleFactory.withGradientLine(baseStyle, GeoJsonEncoder.EMPTY_COLLECTION))

    val casing = layer(style, MapStyleFactory.GRADIENT_CASING_LAYER_ID)
    val line = layer(style, MapStyleFactory.GRADIENT_LAYER_ID)

    assertEquals("line", casing.getString("type"))
    assertEquals("line", line.getString("type"))
    assertEquals(MapStyleFactory.GRADIENT_SOURCE_ID, line.getString("source"))
    assertTrue(
      casing.getJSONObject("paint").getJSONArray("line-width").getDouble(4) >
        line.getJSONObject("paint").getJSONArray("line-width").getDouble(4),
    )
  }

  /** The whole point of the layer: the colour comes off each leg's own feature. */
  @Test
  fun `the gradient line reads its colour from the feature`() {
    val colour = layer(styled(), MapStyleFactory.GRADIENT_LAYER_ID)
      .getJSONObject("paint")
      .getJSONArray("line-color")

    assertEquals("get", colour.getString(0))
    assertEquals("color", colour.getString(1))
  }

  @Test
  fun `the coloured route renders above the plain one`() {
    val style = JSONObject(
      MapStyleFactory.withGradientLine(
        MapStyleFactory.withTrackLine(baseStyle, GeoJsonEncoder.EMPTY_COLLECTION),
        GeoJsonEncoder.EMPTY_COLLECTION,
      ),
    )
    val ids = style.layerIds()

    assertTrue(
      ids.indexOf(MapStyleFactory.TRACK_LAYER_ID) < ids.indexOf(MapStyleFactory.GRADIENT_LAYER_ID),
    )
  }

  @Test
  fun `waypoints are a circle layer with a caption above everything`() {
    val style = styled()

    val circle = layer(style, MapStyleFactory.WAYPOINT_LAYER_ID)
    assertEquals("circle", circle.getString("type"))
    assertEquals(MapStyleFactory.WAYPOINT_SOURCE_ID, circle.getString("source"))

    val ids = style.layerIds()
    assertEquals(MapStyleFactory.WAYPOINT_LABEL_LAYER_ID, ids.last())
  }

  /**
   * One name, not a stack.
   *
   * MapLibre builds the glyph request path by comma-joining the font stack, so a two-name
   * stack asks the font server for a font called "Noto Sans Regular,Noto Sans Bold". The
   * OpenFreeMap endpoint does not publish that, answers 404, and every label on the map
   * silently disappears — a failure that is invisible in a style document and obvious the
   * moment the app is run.
   */
  @Test
  fun `the caption font is a single published name`() {
    val fonts = layer(styled(), MapStyleFactory.WAYPOINT_LABEL_LAYER_ID)
      .getJSONObject("layout")
      .getJSONArray("text-font")

    assertEquals(1, fonts.length())
    assertEquals("Noto Sans Regular", fonts.getString(0))
  }

  @Test
  fun `the route renders above the basemap and below the labels`() {
    val ids = styled().layerIds()

    assertTrue(ids.indexOf("road") < ids.indexOf(MapStyleFactory.GRADIENT_LAYER_ID))
    assertTrue(ids.indexOf(MapStyleFactory.GRADIENT_LAYER_ID) < ids.indexOf("place-labels"))
    assertTrue(ids.indexOf(MapStyleFactory.WAYPOINT_LAYER_ID) < ids.indexOf("place-labels"))
  }

  @Test
  fun `applying the planning layers twice does not duplicate them`() {
    val once = styled().layerIds()
    val twice = JSONObject(
      MapStyleFactory.withWaypoints(
        MapStyleFactory.withGradientLine(
          MapStyleFactory.withWaypoints(
            MapStyleFactory.withGradientLine(baseStyle, GeoJsonEncoder.EMPTY_COLLECTION),
            GeoJsonEncoder.EMPTY_COLLECTION,
          ),
          GeoJsonEncoder.EMPTY_COLLECTION,
        ),
        GeoJsonEncoder.EMPTY_COLLECTION,
      ),
    ).layerIds()

    assertEquals(once, twice)
  }

  @Test
  fun `the layers survive being stacked with terrain`() {
    val style = JSONObject(
      MapStyleFactory.withWaypoints(
        MapStyleFactory.withGradientLine(
          MapStyleFactory.withTerrainAndTrack(baseStyle, GeoJsonEncoder.EMPTY_COLLECTION),
          GeoJsonEncoder.EMPTY_COLLECTION,
        ),
        GeoJsonEncoder.EMPTY_COLLECTION,
      ),
    )

    val ids = style.layerIds()
    assertTrue(ids.contains(MapStyleFactory.HILLSHADE_LAYER_ID))
    assertTrue(ids.contains(MapStyleFactory.TRACK_LAYER_ID))
    assertTrue(ids.contains(MapStyleFactory.GRADIENT_LAYER_ID))
    assertTrue(ids.contains(MapStyleFactory.WAYPOINT_LAYER_ID))
    assertEquals(1, ids.count { it == MapStyleFactory.TRACK_LAYER_ID })
  }
}
