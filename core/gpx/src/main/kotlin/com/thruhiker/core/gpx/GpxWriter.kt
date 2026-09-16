package com.thruhiker.core.gpx

import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import java.util.Locale

/**
 * Writes GPX 1.1, the format every trail app and GPS unit can read.
 *
 * Coordinates are written to seven decimal places, roughly one centimetre, which
 * is finer than consumer GNSS and therefore lossless in practice. Elevation gets
 * two decimals, because that is the resolution a barometric altimeter can
 * actually justify.
 */
object GpxWriter {

  const val CREATOR = "ThruHiker"
  const val VERSION = "1.1"

  private const val NAMESPACE = "http://www.topografix.com/GPX/1/1"
  private const val SCHEMA =
    "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"

  private const val INDENT = "  "

  fun write(document: GpxDocument): String {
    val out = StringBuilder()
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    out.append("<gpx version=\"").append(VERSION)
      .append("\" creator=\"").append(CREATOR)
      .append("\" xmlns=\"").append(NAMESPACE)
      .append("\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance")
      .append("\" xsi:schemaLocation=\"").append(SCHEMA)
      .append("\">\n")

    if (document.name != null || document.description != null) {
      out.append(INDENT).append("<metadata>\n")
      document.name?.let { out.append(INDENT.repeat(2)).element("name", it) }
      document.description?.let { out.append(INDENT.repeat(2)).element("desc", it) }
      out.append(INDENT).append("</metadata>\n")
    }

    for (waypoint in document.waypoints) {
      out.append(INDENT)
        .append("<wpt")
        .append(coordinateAttributes(waypoint.point))
        .append(">\n")
      waypoint.name?.let { out.append(INDENT.repeat(2)).element("name", it) }
      out.append(INDENT.repeat(2)).elevation(waypoint.point)
      out.append(INDENT).append("</wpt>\n")
    }

    if (!document.track.isEmpty) {
      out.append(INDENT).append("<trk>\n")
      out.append(INDENT.repeat(2)).element("name", document.name ?: "ThruHiker track")

      for (segment in document.track.segments) {
        if (segment.isEmpty) continue
        out.append(INDENT.repeat(2)).append("<trkseg>\n")
        for (point in segment.points) {
          out.append(INDENT.repeat(3))
            .append("<trkpt")
            .append(coordinateAttributes(point))
            .append(">\n")
          out.append(INDENT.repeat(4)).elevation(point)
          point.timeMillis?.let { out.append(INDENT.repeat(4)).element("time", GpxTime.format(it)) }
          out.append(INDENT.repeat(3)).append("</trkpt>\n")
        }
        out.append(INDENT.repeat(2)).append("</trkseg>\n")
      }

      out.append(INDENT).append("</trk>\n")
    }

    out.append("</gpx>\n")
    return out.toString()
  }

  fun write(track: Track, name: String? = null): String =
    write(GpxDocument(name = name, track = track))

  private fun coordinateAttributes(point: TrackPoint): String =
    " lat=\"" + formatCoordinate(point.position.latitude) +
      "\" lon=\"" + formatCoordinate(point.position.longitude) + "\""

  private fun StringBuilder.elevation(point: TrackPoint): StringBuilder {
    val elevation = point.elevationMeters ?: return this
    return element("ele", String.format(Locale.US, "%.2f", elevation))
  }

  private fun StringBuilder.element(name: String, value: String): StringBuilder =
    append('<').append(name).append('>')
      .append(escape(value))
      .append("</").append(name).append(">\n")

  private fun formatCoordinate(value: Double): String =
    String.format(Locale.US, "%.7f", value)

  private fun escape(value: String): String = buildString(value.length) {
    for (character in value) {
      when (character) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(character)
      }
    }
  }
}
