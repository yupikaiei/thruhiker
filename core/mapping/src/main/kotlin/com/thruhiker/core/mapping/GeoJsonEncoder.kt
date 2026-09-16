package com.thruhiker.core.mapping

import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/**
 * Converts a track into GeoJSON for the map, optionally drawing only part of it.
 *
 * The partial form is what makes the flyover's route draw itself as the camera
 * flies: at any moment the map holds the geometry walked so far, cut mid-leg at
 * exactly the right distance.
 *
 * Coordinates are rounded to six decimal places, about 11 cm. That is finer than a
 * phone screen can show, and it roughly halves the size of a hundred-thousand-point
 * document, which matters because this string is rebuilt many times per flight.
 *
 * GeoJSON orders coordinates longitude-first. Every other coordinate in this
 * codebase is latitude-first, because that is how they are spoken aloud, so this is
 * the one place a transposition bug can hide without failing to compile.
 */
object GeoJsonEncoder {

  private const val COORDINATE_DECIMALS = 6
  private const val COORDINATE_SCALE = 1_000_000.0

  fun encode(track: Track, revealedFraction: Double = 1.0): String {
    val fraction = revealedFraction.coerceIn(0.0, 1.0)
    val lines = if (fraction >= 1.0) {
      completeLines(track)
    } else {
      revealedLines(track, fraction)
    }

    val geometry = when (lines.size) {
      0 -> JSONObject()
        .put("type", "MultiLineString")
        .put("coordinates", JSONArray())

      1 -> JSONObject()
        .put("type", "LineString")
        .put("coordinates", lines.first())

      // A multi-segment track stays visually broken at the gaps, for the same reason
      // it stays numerically broken in core:geo.
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

  private fun completeLines(track: Track): List<JSONArray> =
    track.segments.mapNotNull { segment ->
      if (segment.isEmpty) null else positionsArray(segment.points.map { it.position })
    }

  /**
   * Geometry covering the first [fraction] of the track's length.
   *
   * Segments are consumed whole until the budget runs out; the segment containing
   * the cut-off is emitted with a final coordinate interpolated at exactly the
   * remaining distance. Gaps consume budget and emit nothing, so the reveal pauses
   * at a gap rather than drawing a line across it.
   */
  private fun revealedLines(track: Track, fraction: Double): List<JSONArray> {
    val lengths = track.segments.map { segment ->
      Geodesic.pathLengthMeters(segment.points.map { it.position })
    }
    val total = lengths.sum()
    if (total <= 0.0) return emptyList()

    var budget = total * fraction
    val lines = ArrayList<JSONArray>()

    for ((index, segment) in track.segments.withIndex()) {
      if (segment.isEmpty || budget <= 0.0) continue

      val segmentLength = lengths[index]
      if (segmentLength <= budget) {
        lines.add(positionsArray(segment.points.map { it.position }))
        budget -= segmentLength
        continue
      }

      partialLine(segment.points.map { it.position }, budget)?.let(lines::add)
      budget = 0.0
    }

    return lines
  }

  /** The line covering the first [budgetMeters] of a single run of positions. */
  private fun partialLine(positions: List<LatLng>, budgetMeters: Double): JSONArray? {
    if (positions.size < 2) return null

    val line = JSONArray().apply { put(coordinate(positions.first())) }
    if (budgetMeters <= 0.0) return null

    var travelled = 0.0

    for (index in 1 until positions.size) {
      val legStart = positions[index - 1]
      val legEnd = positions[index]
      val legLength = Geodesic.distanceMeters(legStart, legEnd)
      if (legLength <= 0.0) continue

      if (travelled + legLength <= budgetMeters) {
        line.put(coordinate(legEnd))
        travelled += legLength
      } else {
        val cut = Geodesic.interpolate(legStart, legEnd, (budgetMeters - travelled) / legLength)
        line.put(coordinate(cut))
        break
      }
    }

    // A single coordinate is not a line, and MapLibre will not render one.
    return if (line.length() >= 2) line else null
  }

  private fun positionsArray(positions: List<LatLng>): JSONArray = JSONArray().apply {
    for (position in positions) put(coordinate(position))
  }

  /** Longitude first. See the class comment. */
  private fun coordinate(position: LatLng): JSONArray = JSONArray()
    .put(round(position.longitude * COORDINATE_SCALE) / COORDINATE_SCALE)
    .put(round(position.latitude * COORDINATE_SCALE) / COORDINATE_SCALE)
}
