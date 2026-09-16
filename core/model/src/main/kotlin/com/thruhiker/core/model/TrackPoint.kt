package com.thruhiker.core.model

/**
 * A single sample along a recorded or planned route.
 *
 * Every field except [position] is nullable because real GPS traces are patchy:
 * elevation is frequently absent on first fix, and imported GPX files often
 * omit timestamps or accuracy.
 */
data class TrackPoint(
  val position: LatLng,
  val elevationMeters: Double? = null,
  val timeMillis: Long? = null,
  val horizontalAccuracyMeters: Double? = null,
) {
  val hasElevation: Boolean get() = elevationMeters != null
}
