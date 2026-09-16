package com.thruhiker.core.mapping

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a flat vector basemap style into the style this app renders.
 *
 * MapLibre exposes most of what this app needs through the style specification
 * rather than an imperative API: terrain is a source plus a root property,
 * relief shading is a layer, and the route is a GeoJSON source plus layers. So the
 * implementation here takes the authoritative basemap style and injects the
 * pieces that matter, rather than hand-writing a style and hoping the
 * source-layer names in it are right.
 *
 * Working on JSON strings also keeps all of this unit-testable on the JVM: no
 * emulator, no rendering, no network.
 */
object MapStyleFactory {

  /** OpenFreeMap serves OpenMapTiles-schema vector tiles with no key and no quota. */
  const val OPEN_FREE_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

  /**
   * AWS Open Data terrain tiles: global, free, no key, Terrarium-encoded.
   *
   * Terrarium packs elevation into the RGB channels, so the `encoding` value below
   * is not optional. Get it wrong and the Himalaya render below sea level.
   */
  const val TERRAIN_TILES_URL =
    "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

  const val TERRAIN_SOURCE_ID = "thruhiker-terrain"
  const val HILLSHADE_LAYER_ID = "thruhiker-hillshade"

  const val TRACK_SOURCE_ID = "thruhiker-track"
  const val TRACK_LAYER_ID = "thruhiker-track-line"
  const val TRACK_CASING_LAYER_ID = "thruhiker-track-casing"

  /** The Terrarium dataset carries no elevation data beyond this zoom. */
  const val MAX_TERRAIN_ZOOM = 15

  /**
   * Vertical exaggeration. Real relief is subtle from five kilometres up, so a
   * little exaggeration is what makes terrain read as mountainous rather than as a
   * painted flat surface.
   */
  const val DEFAULT_EXAGGERATION = 1.35

  /** Blaze orange: the colour every trail marker in the world is painted. */
  const val DEFAULT_TRACK_COLOR = "#FF5A00"

  private const val TERRAIN_ATTRIBUTION = "Elevation: AWS Terrain Tiles / Mapzen"
  private const val TRACK_CASING_COLOR = "#12140F"

  /**
   * Returns [baseStyleJson] with a terrain source, hillshade, sky and track layers.
   *
   * Idempotent: applying it twice produces the same style, which matters because
   * the style is rebuilt whenever the imported route changes.
   */
  fun withTerrainAndTrack(
    baseStyleJson: String,
    trackGeoJson: String? = null,
    exaggeration: Double = DEFAULT_EXAGGERATION,
    trackColor: String = DEFAULT_TRACK_COLOR,
  ): String {
    val withTerrain = withTerrain(baseStyleJson, exaggeration)
    return if (trackGeoJson == null) withTerrain else withTrackLine(withTerrain, trackGeoJson, trackColor)
  }

  /** Adds real 3D terrain: a DEM source, the terrain property, hillshade and sky. */
  fun withTerrain(
    baseStyleJson: String,
    exaggeration: Double = DEFAULT_EXAGGERATION,
  ): String {
    val style = JSONObject(baseStyleJson)

    val sources = style.optJSONObject("sources")
      ?: JSONObject().also { style.put("sources", it) }
    sources.put(TERRAIN_SOURCE_ID, terrainSource())

    style.put(
      "terrain",
      JSONObject()
        .put("source", TERRAIN_SOURCE_ID)
        .put("exaggeration", exaggeration),
    )

    style.put("sky", skyLayer())

    // Inserted before the labels so that shading sits under the text.
    return insertLayers(style, listOf(hillshadeLayer())).toString()
  }

  /**
   * Draws the route as a casing plus a bright inner line.
   *
   * The casing is the same trick road maps use: a dark, slightly wider stroke
   * underneath keeps the route legible over both snow and dark forest, which no
   * single-colour line manages across a 4,000 km trail.
   */
  fun withTrackLine(
    baseStyleJson: String,
    trackGeoJson: String,
    color: String = DEFAULT_TRACK_COLOR,
  ): String {
    val style = JSONObject(baseStyleJson)

    val sources = style.optJSONObject("sources")
      ?: JSONObject().also { style.put("sources", it) }
    sources.put(
      TRACK_SOURCE_ID,
      JSONObject()
        .put("type", "geojson")
        .put("data", JSONObject(trackGeoJson)),
    )

    // Casing first so the bright line renders on top of it.
    return insertLayers(style, listOf(trackCasingLayer(), trackLineLayer(color))).toString()
  }

  private fun terrainSource(): JSONObject = JSONObject()
    .put("type", "raster-dem")
    .put("tiles", JSONArray().put(TERRAIN_TILES_URL))
    .put("encoding", "terrarium")
    .put("tileSize", 256)
    .put("maxzoom", MAX_TERRAIN_ZOOM)
    .put("attribution", TERRAIN_ATTRIBUTION)

  /**
   * Inserts [layers] immediately below the first symbol layer.
   *
   * Anything draped over the basemap belongs above the roads and water it is
   * describing, but below place names, because shading or a route line drawn over
   * text hurts legibility exactly where legibility matters most. Layers whose ids
   * already exist are dropped first, so the function is safe to call repeatedly.
   */
  private fun insertLayers(style: JSONObject, layers: List<JSONObject>): JSONObject {
    val incomingIds = layers.map { it.getString("id") }.toSet()
    val original = style.optJSONArray("layers") ?: JSONArray()
    val result = JSONArray()
    var inserted = false

    for (index in 0 until original.length()) {
      val layer = original.getJSONObject(index)
      if (layer.optString("id") in incomingIds) continue

      if (!inserted && layer.optString("type") == "symbol") {
        layers.forEach { result.put(it) }
        inserted = true
      }
      result.put(layer)
    }

    if (!inserted) layers.forEach { result.put(it) }

    style.put("layers", result)
    return style
  }

  private fun hillshadeLayer(): JSONObject = JSONObject()
    .put("id", HILLSHADE_LAYER_ID)
    .put("type", "hillshade")
    .put("source", TERRAIN_SOURCE_ID)
    .put("layout", JSONObject().put("visibility", "visible"))
    .put(
      "paint",
      JSONObject()
        .put("hillshade-shadow-color", "#473B24")
        .put("hillshade-highlight-color", "#FFFFFF")
        .put("hillshade-accent-color", "#3F4A2E")
        .put("hillshade-exaggeration", 0.4),
    )

  private fun trackCasingLayer(): JSONObject = JSONObject()
    .put("id", TRACK_CASING_LAYER_ID)
    .put("type", "line")
    .put("source", TRACK_SOURCE_ID)
    .put("layout", lineLayout())
    .put(
      "paint",
      JSONObject()
        .put("line-color", TRACK_CASING_COLOR)
        .put("line-opacity", 0.55)
        // Widths grow with zoom so the route reads at both trail and overview scale.
        .put("line-width", zoomInterpolated(3.0, 9.0)),
    )

  private fun trackLineLayer(color: String): JSONObject = JSONObject()
    .put("id", TRACK_LAYER_ID)
    .put("type", "line")
    .put("source", TRACK_SOURCE_ID)
    .put("layout", lineLayout())
    .put(
      "paint",
      JSONObject()
        .put("line-color", color)
        .put("line-width", zoomInterpolated(1.5, 6.0)),
    )

  private fun lineLayout(): JSONObject = JSONObject()
    .put("line-cap", "round")
    .put("line-join", "round")

  private fun zoomInterpolated(atOverview: Double, atTrail: Double): JSONArray = JSONArray()
    .put("interpolate")
    .put(JSONArray().put("linear"))
    .put(JSONArray().put("zoom"))
    .put(OVERVIEW_ZOOM)
    .put(atOverview)
    .put(TRAIL_ZOOM)
    .put(atTrail)

  /**
   * A sky layer gives the horizon a gradient, which is most of what makes a tilted
   * 3D view read as a landscape rather than as a tilted flat map.
   */
  private fun skyLayer(): JSONObject = JSONObject()
    .put("sky-color", "#199BEA")
    .put("horizon-color", "#FFFFFF")
    .put("fog-color", "#F0F8FF")
    .put("sky-horizon-blend", 0.6)
    .put("horizon-fog-blend", 0.5)
    .put("fog-ground-blend", 0.5)

  private const val OVERVIEW_ZOOM = 6
  private const val TRAIL_ZOOM = 14
}
