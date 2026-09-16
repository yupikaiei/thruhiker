package com.thruhiker.core.geo

import com.thruhiker.core.model.Track

/** Cumulative ascent and descent, in metres. */
data class ElevationGainLoss(
  val gainMeters: Double,
  val lossMeters: Double,
) {
  companion object {
    val Zero = ElevationGainLoss(0.0, 0.0)
  }
}

/**
 * Computes cumulative ascent and descent from a raw elevation series.
 *
 * Naive summation of every positive delta massively overstates gain on real
 * data, because barometric and DEM noise oscillates by a metre or two between
 * samples. The industry-standard fix, used here, is a hysteresis threshold: a
 * provisional extremum is only committed once the profile has moved further than
 * [DEFAULT_THRESHOLD_METERS] away from the last committed reference.
 *
 * The trade-off is a small systematic under-count on gentle, finely sampled
 * climbs. That is the correct bias for planning: over-reporting gain would make
 * every day's time estimate pessimistic.
 */
object ElevationCalculator {

  /** 3 m ~ 10 ft, the usual threshold for consumer GPS hardware. */
  const val DEFAULT_THRESHOLD_METERS = 3.0

  fun gainLoss(
    elevations: Iterable<Double?>,
    thresholdMeters: Double = DEFAULT_THRESHOLD_METERS,
  ): ElevationGainLoss {
    require(thresholdMeters >= 0.0) { "Threshold must not be negative: $thresholdMeters" }

    var reference: Double? = null
    var gain = 0.0
    var loss = 0.0

    for (elevation in elevations) {
      if (elevation == null || !elevation.isFinite()) continue

      val current = reference
      if (current == null) {
        reference = elevation
        continue
      }

      val delta = elevation - current
      if (delta > thresholdMeters) {
        gain += delta
        reference = elevation
      } else if (delta < -thresholdMeters) {
        loss += -delta
        reference = elevation
      }
      // Within the threshold band: treat as noise and hold the reference.
    }

    return ElevationGainLoss(gain, loss)
  }

  /**
   * Gain and loss summed across segments.
   *
   * The hysteresis reference is deliberately reset at every segment boundary: the
   * terrain crossed during a pause is unknown, so counting the elevation
   * difference across the gap as ascent would be inventing metres the hiker never
   * climbed.
   */
  fun gainLoss(track: Track, thresholdMeters: Double = DEFAULT_THRESHOLD_METERS): ElevationGainLoss {
    var gain = 0.0
    var loss = 0.0

    for (segment in track.segments) {
      val segmentResult = gainLoss(segment.elevations(), thresholdMeters)
      gain += segmentResult.gainMeters
      loss += segmentResult.lossMeters
    }

    return ElevationGainLoss(gain, loss)
  }

  /** Lowest recorded elevation, or null when the track carries no elevation data. */
  fun minimum(elevations: Iterable<Double?>): Double? =
    elevations.filterNotNull().filter { it.isFinite() }.minOrNull()

  /** Highest recorded elevation, or null when the track carries no elevation data. */
  fun maximum(elevations: Iterable<Double?>): Double? =
    elevations.filterNotNull().filter { it.isFinite() }.maxOrNull()
}
