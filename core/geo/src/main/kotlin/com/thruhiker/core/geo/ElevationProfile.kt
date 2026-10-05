package com.thruhiker.core.geo

import com.thruhiker.core.model.Track

/**
 * One column of an elevation profile: how far along the route, how high, and how steep the
 * ground was on the way in.
 *
 * [gradientPercent] describes the leg that *arrives* at this sample, not the leg that
 * leaves it, which is why the first sample has none: nothing precedes it. It is null across a
 * recording gap, because the slope of ground nobody measured is not a number.
 */
data class ElevationSample(
  val distanceMeters: Double,
  val elevationMeters: Double?,
  val gradientPercent: Double?,
  /** True when this column sits immediately after a recording gap. */
  val isAcrossGap: Boolean = false,
) {
  /** The colour band this sample's incoming leg belongs to. */
  val band: GradientBand get() = GradientBand.of(gradientPercent)
}

/**
 * A distance-sampled elevation series, ready to be drawn.
 *
 * The profile is sampled by *distance*, not by point index. Track points are not evenly
 * spaced — a GPS logs every second on a climb and every ten on a road — so plotting the raw
 * array squashes the interesting half of the route into a few pixels and stretches the rest.
 * Resampling onto an even distance axis also makes the output a fixed size, which is what a
 * chart wants, whatever the length of the route.
 *
 * A recording gap contributes no distance, so the profile stays a single continuous curve —
 * but the columns either side of it are flagged with [ElevationSample.isAcrossGap] and carry
 * no gradient, so a chart can draw that stretch faintly instead of presenting a confident
 * slope across ground the recording skipped.
 */
data class ElevationProfile(
  val samples: List<ElevationSample>,
  val totalDistanceMeters: Double,
  val minimumElevationMeters: Double?,
  val maximumElevationMeters: Double?,
  val gainMeters: Double,
  val lossMeters: Double,
) {
  /** True when at least one sample carries a height, so a chart has something to draw. */
  val hasElevation: Boolean get() = samples.any { it.elevationMeters != null }

  /** The elevation range to scale a chart to, or null when there is nothing to scale. */
  val elevationRangeMeters: ClosedFloatingPointRange<Double>?
    get() {
      val low = minimumElevationMeters ?: return null
      val high = maximumElevationMeters ?: return null
      return low..high
    }
  /** True when the track was recorded in more than one piece. */
  val hasGaps: Boolean get() = samples.any { it.isAcrossGap }
  /** Fraction of the route's length in the given band, by distance. */
  fun bandFractions(): Map<GradientBand, Double> = RouteAnalysis.bandFractions(samples)

  companion object {
    /**
     * Columns per profile.
     *
     * Two hundred and forty is roughly one per two pixels on a phone at typical chart
     * widths, so the curve looks smooth without the file-size or drawing cost of the raw
     * track. A chart wider than that is unusual enough not to be worth a bigger default.
     */
    const val DEFAULT_SAMPLE_COUNT = 240

    val Empty = ElevationProfile(
      samples = emptyList(),
      totalDistanceMeters = 0.0,
      minimumElevationMeters = null,
      maximumElevationMeters = null,
      gainMeters = 0.0,
      lossMeters = 0.0,
    )

    /** Builds an evenly distance-sampled profile for [track]. */
    fun of(track: Track, sampleCount: Int = DEFAULT_SAMPLE_COUNT): ElevationProfile {
      require(sampleCount >= 2) { "A profile needs at least two samples: $sampleCount" }

      val knots = distanceKnots(track)
      if (knots.isEmpty()) return Empty

      val totalDistance = knots.last().distanceMeters
      val samples = if (totalDistance <= 0.0) {
        listOf(ElevationSample(0.0, knots.first().elevationMeters, null))
      } else {
        resample(knots, totalDistance, sampleCount)
      }

      val gainLoss = ElevationCalculator.gainLoss(track)

      return ElevationProfile(
        samples = samples,
        totalDistanceMeters = totalDistance,
        minimumElevationMeters = ElevationCalculator.minimum(track.elevations()),
        maximumElevationMeters = ElevationCalculator.maximum(track.elevations()),
        gainMeters = gainLoss.gainMeters,
        lossMeters = gainLoss.lossMeters,
      )
    }

    /**
     * A knot at every point that advances the distance, tagged with its segment.
     *
     * Points that do not advance the distance — the first point of every segment after the
     * first, which sits exactly where the previous segment ended — are skipped, keeping the
     * knot distances strictly increasing so that interpolation between them is well defined.
     * The segment index is what survives the collapse, and it is what lets a later column
     * notice that it straddles a gap.
     */
    private fun distanceKnots(track: Track): List<Knot> {
      val knots = ArrayList<Knot>()
      var distance = 0.0

      for ((segmentIndex, segment) in track.segments.withIndex()) {
        val points = segment.points
        for (index in points.indices) {
          if (index > 0) {
            distance += Geodesic.distanceMeters(points[index - 1].position, points[index].position)
          }
          if (knots.isNotEmpty() && distance <= knots.last().distanceMeters) continue
          knots.add(Knot(distance, points[index].elevationMeters, segmentIndex))
        }
      }

      return knots
    }

    private fun resample(
      knots: List<Knot>,
      totalDistance: Double,
      sampleCount: Int,
    ): List<ElevationSample> {
      val samples = ArrayList<ElevationSample>(sampleCount)
      var knotIndex = 0
      var previousKnotIndex = 0
      val lastIndex = knots.size - 1

      for (column in 0 until sampleCount) {
        val target = totalDistance * column / (sampleCount - 1)

        // Forward only: the targets increase and so do the knots, so this is a single pass.
        while (knotIndex < lastIndex && knots[knotIndex + 1].distanceMeters <= target) {
          knotIndex++
        }

        val acrossGap = column > 0 &&
          knots[previousKnotIndex].segmentIndex != knots[knotIndex].segmentIndex

        samples.add(
          ElevationSample(
            distanceMeters = target,
            elevationMeters = elevationAt(knots, knotIndex, target),
            gradientPercent = null,
            isAcrossGap = acrossGap,
          ),
        )

        previousKnotIndex = knotIndex
      }

      return withGradients(samples)
    }

    private fun elevationAt(knots: List<Knot>, index: Int, target: Double): Double? {
      val current = knots[index]
      val next = knots.getOrNull(index + 1) ?: return current.elevationMeters

      val currentElevation = current.elevationMeters
      val nextElevation = next.elevationMeters
      if (currentElevation == null || nextElevation == null) return currentElevation

      val span = next.distanceMeters - current.distanceMeters
      if (span <= 0.0) return currentElevation

      val fraction = ((target - current.distanceMeters) / span).coerceIn(0.0, 1.0)
      return currentElevation + (nextElevation - currentElevation) * fraction
    }

    /** Fills in the gradient of each sample from the leg that arrives at it. */
    private fun withGradients(samples: List<ElevationSample>): List<ElevationSample> {
      if (samples.size < 2) return samples

      val result = ArrayList<ElevationSample>(samples.size)
      result.add(samples.first())

      for (index in 1 until samples.size) {
        val previous = samples[index - 1]
        val current = samples[index]

        val previousElevation = previous.elevationMeters
        val currentElevation = current.elevationMeters
        val run = current.distanceMeters - previous.distanceMeters

        val gradient = if (
          !current.isAcrossGap &&
          previousElevation != null &&
          currentElevation != null &&
          run > 0.0
        ) {
          (currentElevation - previousElevation) / run * 100.0
        } else {
          null
        }

        result.add(current.copy(gradientPercent = gradient))
      }

      return result
    }
  }
}

/** A distance/elevation pair from the original track, used as an interpolation anchor. */
private class Knot(
  val distanceMeters: Double,
  val elevationMeters: Double?,
  /** Which track segment this knot came from; a change means the column straddles a gap. */
  val segmentIndex: Int,
)
