package com.thruhiker.core.gpx

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * GPX timestamps in ISO 8601.
 *
 * In the wild these come in several shapes: with a `Z`, with a numeric offset,
 * with or without fractional seconds, and, from some older exporters, with no
 * zone at all. Rather than reject the last of those, missing zones are read as
 * UTC, which is what a device recording without a zone almost always means.
 */
internal object GpxTime {

  fun parseMillis(text: String?): Long? {
    val value = text?.trim().orEmpty()
    if (value.isEmpty()) return null

    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
      .recoverCatching { Instant.parse(value).toEpochMilli() }
      .recoverCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }
      .getOrNull()
  }

  fun format(millis: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
}
