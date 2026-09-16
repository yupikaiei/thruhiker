package com.thruhiker.core.mapping

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a flat vector basemap style into one that renders real 3D terrain.
 *
 * MapLibre exposes terrain through the style specification rather than an
 * imperative API: a `raster-dem` source plus a root-level `terrain` property, and
 * relief shading comes from a `hillshade` layer bound to the same source. So the
 * cheapest correct implementation is to take the authoritative basemap style and
 * inject three things, rather than hand-writing a style and hoping the
 * source-layer names in it are right.
 *
 * Working purely on JSON strings also keeps this unit-testable on the JVM: no
 * emulator, no rendering, no network.
 */
object MapStyleFactory {

  /** OpenFreeMap serves OpenMapTiles-schema vector tiles with no key and no quota. */
  const val OPEN_FREE_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

  /**
   * AWS Open Data terrain tiles: global, free, no key, Terrarium-encoded.
   *
   * Terrarium encoding packs elevation into the RGB channels, which is why the
   * `encoding` value below is not optional: get it wrong and the Himalaya end up
   * below sea level.
   */
  const val TERRAIN_TILES_URL =
    "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

  const val TERRAIN_SOURCE_ID = "thruhiker-terrain"
  const val HILLSHADE_LAYER_ID = "thruhiker-hillshade"

  /** The Terrarium dataset carries no elevation data beyond this zoom. */
  const val MAX_TERRAIN_ZOOM = 15

  /**
   * Vertical exaggeration. Real-world relief is subtle from a camera five
   * kilometres up, so a little exaggeration is what makes terrain read as
   * mountainous rather than as a painted flat surface.
   */
  const val DEFAULT_EXAGGERATION = 1.35

  private const val TERRAIN_ATTRIBUTION = "Elevation: AWS Terrain Tiles / Mapzen"

  /**
   * Returns [baseStyleJson] with a terrain source, a hillshade layer and a sky
   * layer added. Idempotent: running it twice produces the same style.
   */
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

    style.put("layers", layersWithHillshade(style.optJSONArray("layers")))
    style.put("sky", skyLayer())

    return style.toString()
  }

  private fun terrainSource(): JSONObject = JSONObject()
    .put("type", "raster-dem")
    .put("tiles", JSONArray().put(TERRAIN_TILES_URL))
    .put("encoding", "terrarium")
    .put("tileSize", 256)
    .put("maxzoom", MAX_TERRAIN_ZOOM)
    .put("attribution", TERRAIN_ATTRIBUTION)

  /**
   * Places the hillshade immediately below the first symbol layer.
   *
   * Shading draped over roads and contours is fine; shading draped over place
   * names is not, because it hurts legibility exactly where legibility matters
   * most. Any previously injected hillshade is dropped first so the function is
   * safe to call repeatedly.
   */
  private fun layersWithHillshade(original: JSONArray?): JSONArray {
    val result = JSONArray()
    var inserted = false

    for (index in 0 until (original?.length() ?: 0)) {
      val layer = original!!.getJSONObject(index)
      if (layer.optString("id") == HILLSHADE_LAYER_ID) continue

      if (!inserted && layer.optString("type") == "symbol") {
        result.put(hillshadeLayer())
        inserted = true
      }
      result.put(layer)
    }

    if (!inserted) result.put(hillshadeLayer())
    return result
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

  /**
   * A sky layer gives the horizon a gradient, which is most of what makes a
   * tilted 3D view read as a landscape rather than as a tilted flat map.
   */
  private fun skyLayer(): JSONObject = JSONObject()
    .put("sky-color", "#199BEA")
    .put("horizon-color", "#FFFFFF")
    .put("fog-color", "#F0F8FF")
    .put("sky-horizon-blend", 0.6)
    .put("horizon-fog-blend", 0.5)
    .put("fog-ground-blend", 0.5)
}
