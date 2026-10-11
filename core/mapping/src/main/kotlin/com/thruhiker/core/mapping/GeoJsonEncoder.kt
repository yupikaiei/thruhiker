package com.thruhiker.core.mapping

import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.geo.GradientBand
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/** Six decimal places, about 11 cm: finer than a phone screen shows. */
private const val COORDINATE_SCALE = 1_000_000.0

/**
 * Writes one `[lon,lat]` pair, rounded.
 *
 * Deliberately not `String.format`, which is locale-dependent and would write a decimal comma
 * wherever the phone is set to one; `StringBuilder.append(Double)` is locale-independent and
 * several times faster besides. It is the whole per-coordinate cost of a reveal, so it is the
 * one place in this file where the choice matters more than the readability.
 */
private fun appendCoordinate(builder: StringBuilder, position: LatLng) {
  builder.append('[')
  builder.append(round(position.longitude * COORDINATE_SCALE) / COORDINATE_SCALE)
  builder.append(',')
  builder.append(round(position.latitude * COORDINATE_SCALE) / COORDINATE_SCALE)
  builder.append(']')
}

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

  /**
   * A route prepared for repeated partial encoding.
   *
   * The flyover re-encodes the revealed part of its route several times a second, and encoding
   * it from a [Track] re-derives what cannot have changed: the length of every leg, the total,
   * and a JSON tree for the whole revealed geometry. Measured on a 10,000-point recording, one
   * half-revealed encode cost 21 ms — inside the frame callback that drives the camera, so every
   * reveal step dropped a frame or two, several times a second, for the length of the flight.
   *
   * Prepared once, a reveal costs one pass over the coordinates it draws: no geodesics, no JSON
   * tree, no boxing. A segment that is already fully revealed is appended as the text it had
   * anyway. What is left is proportional to what is *newly* revealed rather than to the route.
   *
   * This is a second implementation of [encode]'s reveal rules rather than a wrapper around it,
   * which is the only way to check a fast one against a readable one. `GeoJsonRevealTest`
   * compares the two fraction by fraction, on a track with a gap in the middle.
   */
  class Reveal private constructor(
    private val segments: List<Prepared>,
    private val totalMeters: Double,
    private val spacingMeters: Double,
  ) {

    private class Prepared(
      val points: List<LatLng>,
      /** Metres from the start of the segment to each point. The first is always zero. */
      val cumulative: DoubleArray,
      /** The whole segment's coordinate text, so a revealed segment is a copy. */
      val text: String,
    ) {
      val lengthMeters: Double get() = cumulative[cumulative.size - 1]
    }

    /** The geometry covering the first [revealedFraction] of the route's length. */
    fun encode(revealedFraction: Double): String {
      val budget = totalMeters * revealedFraction.coerceIn(0.0, 1.0)
      val lines = if (budget <= 0.0) emptyList() else linesWithin(budget)

      val builder = StringBuilder(HEAD.length + 64)
      builder.append(HEAD)
      when (lines.size) {
        // The empty case has to look exactly like this: MapController decides whether the
        // route is drawn or hidden by looking for `"coordinates":[]` in the document.
        0 -> builder.append(NO_COORDINATES_GEOMETRY)
        1 -> builder.append(LINE_START).append(lines[0]).append("]}")
        else -> {
          builder.append(MULTI_LINE_START)
          for ((index, line) in lines.withIndex()) {
            if (index > 0) builder.append(',')
            builder.append('[').append(line).append(']')
          }
          builder.append("]}")
        }
      }
      return builder.append('}').toString()
    }

    private fun linesWithin(budget: Double): List<String> {
      val lines = ArrayList<String>(segments.size)
      var remaining = budget

      for (segment in segments) {
        if (segment.lengthMeters <= remaining) {
          lines.add(segment.text)
          remaining -= segment.lengthMeters
        } else {
          partialLine(segment, remaining)?.let(lines::add)
          return lines
        }
      }
      return lines
    }

    /** The first [budget] metres of one segment, or null when that is not a line. */
    private fun partialLine(segment: Prepared, budget: Double): String? {
      if (segment.points.size < 2) return null
      if (budget <= 0.0) return null

      val builder = StringBuilder(512)
      appendCoordinate(builder, segment.points.first())
      var coordinates = 1

      // Metres since the last point actually written. Points closer together than the spacing
      // are dropped, which is what bounds the size of this document by the route's length
      // rather than by how densely it was recorded. At a spacing of zero nothing is dropped, so
      // this walk emits exactly the points the reference implementation does.
      var sinceDrawn = 0.0

      for (index in 1 until segment.points.size) {
        val travelled = segment.cumulative[index - 1]
        val legLength = segment.cumulative[index] - travelled
        // A repeated point is not a leg: it draws nothing and there is nothing to cut.
        if (legLength <= 0.0) continue

        if (segment.cumulative[index] <= budget) {
          sinceDrawn += legLength
          if (sinceDrawn < spacingMeters) continue
          builder.append(',')
          appendCoordinate(builder, segment.points[index])
          coordinates++
          sinceDrawn = 0.0
          continue
        }

        // The end of the drawn line is interpolated on the exact leg the route reaches the
        // budget inside, so the line ends where the walker is rather than at the last point
        // that survived thinning.
        val cut = Geodesic.interpolate(
          segment.points[index - 1],
          segment.points[index],
          (budget - travelled) / legLength,
        )
        builder.append(',')
        appendCoordinate(builder, cut)
        coordinates++
        break
      }

      return if (coordinates >= 2) builder.toString() else null
    }

    companion object {
      private const val HEAD = """{"type":"Feature","properties":{},"geometry":"""
      private const val LINE_START = """{"type":"LineString","coordinates":["""
      private const val MULTI_LINE_START = """{"type":"MultiLineString","coordinates":["""
      private const val NO_COORDINATES_GEOMETRY =
        """{"type":"MultiLineString","coordinates":[]}"""

      /**
       * Measures the whole route, renders each segment's coordinates, once.
       *
       * [minimumSpacingMeters] thins the *drawn* points and nothing else: the distances are
       * measured on every point, so the reveal still ends exactly where the route reaches the
       * revealed distance, and only the shape between the ends is simplified. It exists because
       * this document is re-uploaded through the whole flight, so its size is the flight's cost:
       * at a spacing of zero a ten-thousand-point recording uploads ten thousand coordinates,
       * and at twenty metres it uploads one per twenty metres of route, whatever the recording
       * did. At the zoom a flyover travels at, twenty metres is a couple of pixels.
       */
      fun of(track: Track, minimumSpacingMeters: Double = 0.0): Reveal {
        val prepared = track.segments.mapNotNull { segment ->
          if (segment.isEmpty) return@mapNotNull null

          val points = segment.points.map { it.position }
          val cumulative = DoubleArray(points.size)
          for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] +
              Geodesic.distanceMeters(points[index - 1], points[index])
          }

          val text = StringBuilder(points.size * 22)
          appendCoordinate(text, points.first())
          var sinceDrawn = 0.0
          for (index in 1 until points.size) {
            sinceDrawn += cumulative[index] - cumulative[index - 1]
            // The last point is always drawn, so a thinned segment still reaches its own end
            // rather than stopping at the last point that survived thinning.
            if (index < points.size - 1 && sinceDrawn < minimumSpacingMeters) continue
            text.append(',')
            appendCoordinate(text, points[index])
            sinceDrawn = 0.0
          }

          Prepared(points, cumulative, text.toString())
        }

        return Reveal(prepared, prepared.sumOf { it.lengthMeters }, minimumSpacingMeters)
      }
    }
  }

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
