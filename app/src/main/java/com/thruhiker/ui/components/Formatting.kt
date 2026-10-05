package com.thruhiker.ui.components

import com.thruhiker.core.geo.GradientBand
import com.thruhiker.core.geo.TrailDifficulty
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * One place where numbers become the strings a hiker reads.
 *
 * These were private to the flyover screen until the planner needed the same distances and
 * the same esimated times. Two screens that disagree about how far a route is are worse than
 * one screen that formats badly, so they live here now.
 *
 * Every one of them takes the unit explicitly rather than reading it from the environment:
 * a value shown without a unit is a value somebody will read in the wrong one.
 */

/** Miles are the only extra length unit the app needs; everything is metres underneath. */
private const val METERS_PER_MILE = 1_609.344

private const val FEET_PER_METER = 3.280_839_895_013_123

/**
 * A tenth of a mile. Below this, miles read as a meaningless fraction, so feet take over —
 * which is what a walker expects for "how far to the next water".
 */
private const val FEET_THRESHOLD_METERS = METERS_PER_MILE / 10.0

/**
 * Distance, in the unit the reader uses.
 *
 * Metric stays in metres under a kilometre, because "0.4 km" is not how anyone says four
 * hundred metres.
 */
fun formatDistance(meters: Double?, unit: DistanceUnit): String {
  if (meters == null || !meters.isFinite()) return "—"
  return when (unit) {
    DistanceUnit.KILOMETERS ->
      if (meters < 1_000.0) {
        String.format(Locale.US, "%,.0f m", meters)
      } else {
        String.format(Locale.US, "%,.1f km", meters / 1_000.0)
      }

    DistanceUnit.MILES ->
      if (meters < FEET_THRESHOLD_METERS) {
        String.format(Locale.US, "%,.0f ft", meters * FEET_PER_METER)
      } else {
        String.format(Locale.US, "%,.2f mi", meters / METERS_PER_MILE)
      }
  }
}

/** Elevation, in metres or feet. Null is unknown, never zero. */
fun formatElevation(meters: Double?, unit: DistanceUnit): String = when {
  meters == null || !meters.isFinite() -> "—"
  unit == DistanceUnit.MILES -> String.format(Locale.US, "%,.0f ft", meters * FEET_PER_METER)
  else -> String.format(Locale.US, "%,.0f m", meters)
}

/** A duration as `3h 40m`, or `40m` under an hour. */
fun formatDuration(seconds: Double?): String {
  if (seconds == null || !seconds.isFinite()) return "—"
  val totalMinutes = (seconds / 60.0).toLong()
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/**
 * A wall-clock time, in whatever the device's locale uses.
 *
 * Deliberately locale-driven rather than a fixed `HH:mm`: half the point of showing a clock
 * time is that it matches the watch on the reader's wrist.
 */
fun formatClockTime(millis: Long): String =
  DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** A gradient as a signed percentage, so a climb and a descent never look alike. */
fun formatGradient(percent: Double?): String =
  if (percent == null || !percent.isFinite()) "—" else String.format(Locale.US, "%+.0f%%", percent)

/** The word a reader expects for a band, rather than its enum name. */
fun formatBand(band: GradientBand): String = band.label

fun formatDifficulty(difficulty: TrailDifficulty): String = difficulty.label

/** A short, locale-aware date for a library entry. */
fun formatDate(millis: Long): String =
  DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
