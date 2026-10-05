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

  const val GRADIENT_SOURCE_ID = "thruhiker-gradient"
  const val GRADIENT_LAYER_ID = "thruhiker-gradient-line"
  const val GRADIENT_CASING_LAYER_ID = "thruhiker-gradient-casing"

  const val WAYPOINT_SOURCE_ID = "thruhiker-waypoints"
  const val WAYPOINT_LAYER_ID = "thruhiker-waypoint-circle"
  const val WAYPOINT_LABEL_LAYER_ID = "thruhiker-waypoint-label"

  /** Every layer this factory injects, which it has to be able to find again to re-stack. */
  private val INJECTED_LAYER_IDS = listOf(
    HILLSHADE_LAYER_ID,
    TRACK_CASING_LAYER_ID,
    TRACK_LAYER_ID,
    GRADIENT_CASING_LAYER_ID,
    GRADIENT_LAYER_ID,
    WAYPOINT_LAYER_ID,
    WAYPOINT_LABEL_LAYER_ID,
  )

  /**
   * Route layers, in draw order, inserted between the basemap geometry and its labels.
   *
   * The gradient route goes after the flat one so that a planner showing a coloured route
   * covers the plain line underneath, and the plain line is still there for a flyover that
   * has no gradient data to draw.
   */
  private val ROUTE_BLOCK_ORDER = listOf(
    TRACK_CASING_LAYER_ID,
    TRACK_LAYER_ID,
    GRADIENT_CASING_LAYER_ID,
    GRADIENT_LAYER_ID,
    WAYPOINT_LAYER_ID,
  )

  /** Layers drawn above everything, including the basemap's own place names. */
  private val TOP_BLOCK_ORDER = listOf(WAYPOINT_LABEL_LAYER_ID)

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
   * Waypoint green.
   *
   * Deliberately outside the blue-to-red gradient ramp, so a marker never reads as part of
   * the route it is sitting on.
   */
  private const val WAYPOINT_COLOR = "#0B6E4F"
  private const val SYMBOL_LAYER_TYPE = "symbol"

  /**
   * The font the basemap's glyph server actually publishes.
   *
   * MapLibre's default `text-font` is "Open Sans Regular, Arial Unicode MS Regular", and the
   * OpenFreeMap glyph endpoint serves only Noto Sans. A symbol layer asking for a font the
   * server does not have renders no text at all, which for a numbered waypoint looks exactly
   * like a bug in the labels.
   *
   * Exactly one name, deliberately. `text-font` is a *font stack*, and MapLibre joins the
   * names with commas to build the request path, so asking for two fonts asks the server for a
   * stack called "Noto Sans Regular,Noto Sans Bold" — which it does not publish, and which
   * comes back as a 404 that silently costs every label on the map. Running the app is what
   * found that; the live style uses single-name stacks everywhere, and so does this.
   */
  private fun labelFont(): JSONArray = JSONArray().put("Noto Sans Regular")

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

    // Under the first symbol so the shading sits below icons and text.
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

  /**
   * Adds the gradient-coloured route.
   *
   * The source data is a collection of short two-point features rather than one line,
   * because the colour changes along the route and a line layer can only paint by a value it
   * reads off each feature. Band boundaries are therefore decided in Kotlin, where they are
   * tested, and not in a style expression, where they are not.
   */
  fun withGradientLine(baseStyleJson: String, gradientGeoJson: String): String {
    val style = JSONObject(baseStyleJson)
    putGeoJsonSource(style, GRADIENT_SOURCE_ID, gradientGeoJson)

    return insertLayers(
      style,
      listOf(gradientCasingLayer(), gradientLineLayer()),
    ).toString()
  }

  /** Adds planner waypoints, or an imported file's points of interest, as numbered pins. */
  fun withWaypoints(baseStyleJson: String, waypointsGeoJson: String): String {
    val style = JSONObject(baseStyleJson)
    putGeoJsonSource(style, WAYPOINT_SOURCE_ID, waypointsGeoJson)

    return insertLayers(
      style,
      listOf(waypointCircleLayer(), waypointLabelLayer()),
    ).toString()
  }

  private fun putGeoJsonSource(style: JSONObject, id: String, geoJson: String) {
    val sources = style.optJSONObject("sources")
      ?: JSONObject().also { style.put("sources", it) }
    sources.put(id, JSONObject().put("type", "geojson").put("data", JSONObject(geoJson)))
  }

  private fun terrainSource(): JSONObject = JSONObject()
    .put("type", "raster-dem")
    .put("tiles", JSONArray().put(TERRAIN_TILES_URL))
    .put("encoding", "terrarium")
    .put("tileSize", 256)
    .put("maxzoom", MAX_TERRAIN_ZOOM)
    .put("attribution", TERRAIN_ATTRIBUTION)

  /**
   * Places the injected layers where the style specification expects them.
   *
   * The hillshade goes below the basemap geometry so buildings, bridges and boundaries
   * draw over it: it describes the terrain under them, not them. The route goes above all
   * of that geometry, because a trail line hidden under a boundary or an extruded building
   * is the whole problem, but still below the label symbols, because a line drawn over text
   * hurts legibility exactly where legibility matters most.
   *
   * Layers this factory injected earlier are pulled out and re-stacked first, so the
   * function is safe to call repeatedly and produces the same style whichever of
   * [withTerrain] and [withTrackLine] ran first.
   */
  private fun insertLayers(style: JSONObject, layers: List<JSONObject>): JSONObject {
    val injected = LinkedHashMap<String, JSONObject>()
    val basemap = JSONArray()
    val original = style.optJSONArray("layers") ?: JSONArray()
    for (index in 0 until original.length()) {
      val layer = original.getJSONObject(index)
      val id = layer.optString("id")
      if (id in INJECTED_LAYER_IDS) injected[id] = layer else basemap.put(layer)
    }
    layers.forEach { injected[it.getString("id")] = it }

    val hillshadeAt = firstSymbolIndex(basemap)
    val routeAt = labelBlockIndex(basemap)

    val result = JSONArray()
    for (index in 0..basemap.length()) {
      if (index == hillshadeAt) injected[HILLSHADE_LAYER_ID]?.let { result.put(it) }
      if (index == routeAt) {
        for (id in ROUTE_BLOCK_ORDER) {
          injected[id]?.let { result.put(it) }
        }
      }
      if (index < basemap.length()) result.put(basemap.getJSONObject(index))
    }

    // Waypoint captions are the one thing that must sit above the basemap's own labels,
    // because a numbered point the user just placed is more interesting than the name of the
    // village it landed in.
    for (id in TOP_BLOCK_ORDER) {
      injected[id]?.let { result.put(it) }
    }

    style.put("layers", result)
    return style
  }

  /** Index of the first symbol layer, or the end of the array when there are none. */
  private fun firstSymbolIndex(layers: JSONArray): Int {
    for (index in 0 until layers.length()) {
      if (layers.getJSONObject(index).optString("type") == SYMBOL_LAYER_TYPE) return index
    }
    return layers.length()
  }

  /**
   * Index of the first symbol layer with no geometry layer after it.
   *
   * "Below the first symbol layer" is the usual rule for a route layer and it is wrong for
   * the style this app actually renders. Liberty lists a road-arrow *symbol* above its
   * bridges, buildings and boundaries, so inserting there hides the route under a boundary
   * line or an extruded building. Skipping to the first symbol that has no geometry after it
   * keeps the route above all of them and still under the place names, which is the intent.
   */
  private fun labelBlockIndex(layers: JSONArray): Int {
    var lastGeometry = -1
    for (index in 0 until layers.length()) {
      if (layers.getJSONObject(index).optString("type") != SYMBOL_LAYER_TYPE) lastGeometry = index
    }
    for (index in lastGeometry + 1 until layers.length()) {
      if (layers.getJSONObject(index).optString("type") == SYMBOL_LAYER_TYPE) return index
    }
    return layers.length()
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

  private fun gradientCasingLayer(): JSONObject = JSONObject()
    .put("id", GRADIENT_CASING_LAYER_ID)
    .put("type", "line")
    .put("source", GRADIENT_SOURCE_ID)
    .put("layout", lineLayout())
    .put(
      "paint",
      JSONObject()
        .put("line-color", TRACK_CASING_COLOR)
        .put("line-opacity", 0.55)
        .put("line-width", zoomInterpolated(4.5, 11.0)),
    )

  /** Paints each leg with the colour its own feature carries. */
  private fun gradientLineLayer(): JSONObject = JSONObject()
    .put("id", GRADIENT_LAYER_ID)
    .put("type", "line")
    .put("source", GRADIENT_SOURCE_ID)
    .put("layout", lineLayout())
    .put(
      "paint",
      JSONObject()
        .put("line-color", JSONArray().put("get").put("color"))
        .put("line-width", zoomInterpolated(3.0, 8.0)),
    )

  private fun waypointCircleLayer(): JSONObject = JSONObject()
    .put("id", WAYPOINT_LAYER_ID)
    .put("type", "circle")
    .put("source", WAYPOINT_SOURCE_ID)
    .put(
      "paint",
      JSONObject()
        .put("circle-radius", zoomInterpolated(3.5, 7.0))
        .put("circle-color", WAYPOINT_COLOR)
        .put("circle-stroke-color", "#FFFFFF")
        .put("circle-stroke-width", 2.0),
    )

  private fun waypointLabelLayer(): JSONObject = JSONObject()
    .put("id", WAYPOINT_LABEL_LAYER_ID)
    .put("type", "symbol")
    .put("source", WAYPOINT_SOURCE_ID)
    .put(
      "layout",
      JSONObject()
        .put("text-field", JSONArray().put("get").put("label"))
        .put("text-font", labelFont())
        .put("text-size", zoomInterpolated(10.0, 13.0))
        .put("text-allow-overlap", true)
        .put("text-offset", JSONArray().put(0).put(1.3)),
    )
    .put(
      "paint",
      JSONObject()
        .put("text-color", "#12140F")
        .put("text-halo-color", "#FFFFFF")
        .put("text-halo-width", 1.5),
    )

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
