package com.thruhiker.core.geo

/** Aggregate statistics for a track. Nullable fields are unknown, never zero. */
data class TrackStats(
  val pointCount: Int,
  val distanceMeters: Double,
  val elevationGainMeters: Double,
  val elevationLossMeters: Double,
  val minimumElevationMeters: Double?,
  val maximumElevationMeters: Double?,
  val startTimeMillis: Long?,
  val endTimeMillis: Long?,
  /** Wall-clock time from first to last timestamp, including breaks. */
  val elapsedMillis: Long?,
  /** Time actually spent walking; excludes stops and data gaps. */
  val movingMillis: Long?,
  val averageSpeedMetersPerSecond: Double?,
  val movingSpeedMetersPerSecond: Double?,
) {
  companion object {
    val Empty = TrackStats(
      pointCount = 0,
      distanceMeters = 0.0,
      elevationGainMeters = 0.0,
      elevationLossMeters = 0.0,
      minimumElevationMeters = null,
      maximumElevationMeters = null,
      startTimeMillis = null,
      endTimeMillis = null,
      elapsedMillis = null,
      movingMillis = null,
      averageSpeedMetersPerSecond = null,
      movingSpeedMetersPerSecond = null,
    )
  }
}

/** Tunables for [TrackStatsCalculator]. Defaults target handheld GPS on foot. */
data class TrackStatsOptions(
  val elevationThresholdMeters: Double = ElevationCalculator.DEFAULT_THRESHOLD_METERS,
  /**
   * Below this speed a segment counts as stationary. 0.3 m/s is about 1 km/h,
   * which is slower than any real walking pace but above GPS jitter.
   */
  val movingSpeedThresholdMetersPerSecond: Double = 0.3,
  /**
   * Timestamps further apart than this are treated as a gap (paused recording,
   * lost signal, phone off overnight) and excluded from moving time rather than
   * divided by the full interval.
   */
  val maximumGapMillis: Long = 60_000L,
)
