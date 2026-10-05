package com.thruhiker.core.mapping

import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.geo.GradientBand
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
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

  /**
   * Leg length used when the route is coloured by gradient.
   *
   * The gradient of a leg is only as good as the ground it spans: a five-metre leg with a
   * one-metre DEM wobble reads as 20%, and a hundred-thousand-point recording would upload a
   * hundred thousand two-point features to draw one line. Thirty metres is the compromise
   * every route planner lands on — fine enough to show a switchback, coarse enough that the
   * number means something.
   */
  const val DEFAULT_GRADIENT_SPACING_METERS = 30.0

  /** The update MapLibre clears a source with: an empty but well-formed collection. */
  const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

  /** A point the map should mark, optionally with a caption. */
  data class WaypointMarker(
    val position: LatLng,
    val label: String? = null,
  )

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

  /**
   * Encodes the route as one two-point feature per leg, each carrying its gradient band's
   * colour.
   *
   * A single line cannot be painted by a value that changes along it — `line-gradient` needs
   * a progress expression, not a band — so the route is expressed as many short features and
   * the style reads the colour off each one. That is also what allows a gap in the recording
   * to break the line, because a new segment simply starts a new run of features.
   */
  fun encodeGradient(
    track: Track,
    minimumSpacingMeters: Double = DEFAULT_GRADIENT_SPACING_METERS,
  ): String {
    require(minimumSpacingMeters > 0.0) { "Spacing must be positive: $minimumSpacingMeters" }

    val features = JSONArray()

    for (segment in track.segments) {
      val points = decimate(segment.points, minimumSpacingMeters)
      for (index in 1 until points.size) {
        val start = points[index - 1]
        val end = points[index]
        features.put(gradientFeature(start, end, minimumSpacingMeters))
      }
    }

    return collection(features)
  }

  /** Encodes markers as numbered points, with a caption when they have one. */
  fun encodeWaypoints(markers: List<WaypointMarker>): String {
    val features = JSONArray()

    for ((index, marker) in markers.withIndex()) {
      val number = index + 1
      val properties = JSONObject()
        .put("index", number)
        // Always present, so the style's label layer can read it without a fallback
        // expression: a planner tap with no name shows its position in the route instead.
        .put("label", marker.label ?: number.toString())

      features.put(
        JSONObject()
          .put("type", "Feature")
          .put("properties", properties)
          .put(
            "geometry",
            JSONObject()
              .put("type", "Point")
              .put("coordinates", coordinate(marker.position)),
          ),
      )
    }

    return collection(features)
  }

  /**
   * Convenience for callers with no captions to give.
   *
   * The JVM name is spelled out because both overloads erase to `List`, which would
   * otherwise be a clash at the bytecode level even though Kotlin can tell them apart.
   */
  @JvmName("encodeWaypointPositions")
  fun encodeWaypoints(positions: List<LatLng>): String =
    encodeWaypoints(positions.map { WaypointMarker(it) })

  /**
   * One leg, coloured by its band.
   *
   * A leg shorter than half the display spacing is skipped rather than coloured: it exists
   * only because the track ended mid-interval, and its gradient is computed over too little
   * ground to be worth a colour that the user will read as real.
   */
  private fun gradientFeature(
    start: TrackPoint,
    end: TrackPoint,
    minimumSpacingMeters: Double,
  ): JSONObject {
    val distance = Geodesic.distanceMeters(start.position, end.position)
    val startElevation = start.elevationMeters
    val endElevation = end.elevationMeters

    val grade = if (
      startElevation != null &&
      endElevation != null &&
      distance >= minimumSpacingMeters / 2.0
    ) {
      (endElevation - startElevation) / distance * 100.0
    } else {
      null
    }

    val band = GradientBand.of(grade)

    return JSONObject()
      .put("type", "Feature")
      .put(
        "properties",
        JSONObject()
          .put("band", band.name)
          .put("color", band.colorHex),
      )
      .put(
        "geometry",
        JSONObject()
          .put("type", "LineString")
          .put("coordinates", JSONArray().put(coordinate(start.position)).put(coordinate(end.position))),
      )
  }

  /**
   * Thins a run of points to at most one every [minimumSpacingMeters], keeping both ends.
   *
   * The last point is always kept, so the drawn route reaches the destination even when the
   * final leg is shorter than the spacing.
   */
  private fun decimate(points: List<TrackPoint>, minimumSpacingMeters: Double): List<TrackPoint> {
    if (points.size < 2) return points

    val result = ArrayList<TrackPoint>()
    result.add(points.first())
    var accumulated = 0.0

    for (index in 1 until points.size) {
      accumulated += Geodesic.distanceMeters(points[index - 1].position, points[index].position)
      if (index == points.size - 1 || accumulated >= minimumSpacingMeters) {
        result.add(points[index])
        accumulated = 0.0
      }
    }

    return result
  }

  private fun collection(features: JSONArray): String = JSONObject()
    .put("type", "FeatureCollection")
    .put("features", features)
    .toString()

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
