package com.thruhiker.core.mapping

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/**
 * Converts a track into GeoJSON for the map.
 *
 * Coordinates are rounded to six decimal places, about 11 cm. That is finer than
 * a phone screen can show, and it roughly halves the size of a 100,000-point
 * document, which matters because this string is parsed and uploaded to the GPU
 * on every style load.
 *
 * GeoJSON orders coordinates longitude-first. Every other coordinate in this
 * codebase is latitude-first, because that is how they are spoken aloud, so this
 * is the one place a transposition bug can hide without failing to compile.
 */
object GeoJsonEncoder {

  private const val COORDINATE_DECIMALS = 6
  private const val COORDINATE_SCALE = 1_000_000.0

  fun encode(track: Track): String {
    val lines = track.segments.mapNotNull { segment ->
      if (segment.isEmpty) null else positionArray(segment.points.map { it.position })
    }

    val geometry = when (lines.size) {
      0 -> JSONObject()
        .put("type", "MultiLineString")
        .put("coordinates", JSONArray())

      1 -> JSONObject()
        .put("type", "LineString")
        .put("coordinates", lines.first())

      // A multi-segment track must stay visually broken at the gaps, for the same
      // reason it stays numerically broken in core:geo.
      else -> JSONObject()
        .put("type", "MultiLineString")
        .put("coordinates", JSONArray().apply { lines.forEach { put(it) } })
    }

    return JSONObject()
      .put("type", "Feature")
      .put("properties", JSONObject())
      .put("geometry", geometry)
      .toString()
  }

  private fun positionArray(positions: List<LatLng>): JSONArray =
    JSONArray().apply {
      for (position in positions) {
        put(
          JSONArray()
            .put(round(position.longitude * COORDINATE_SCALE) / COORDINATE_SCALE)
            .put(round(position.latitude * COORDINATE_SCALE) / COORDINATE_SCALE),
        )
      }
    }
}
