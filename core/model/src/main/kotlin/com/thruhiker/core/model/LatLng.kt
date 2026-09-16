package com.thruhiker.core.model

/**
 * A geographic coordinate in WGS84 decimal degrees.
 *
 * Elevation deliberately lives on [TrackPoint] rather than here, so that
 * horizontal geometry and vertical profile can be reasoned about separately.
 */
data class LatLng(
  val latitude: Double,
  val longitude: Double,
) {
  /** True when both components are inside the valid WGS84 degree ranges. */
  val isValid: Boolean
    get() = latitude.isFinite() &&
      longitude.isFinite() &&
      latitude in -90.0..90.0 &&
      longitude in -180.0..180.0
}
