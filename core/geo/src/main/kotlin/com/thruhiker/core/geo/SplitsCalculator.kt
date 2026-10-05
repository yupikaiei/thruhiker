package com.thruhiker.core.geo

import com.thruhiker.core.model.Track
import kotlin.math.min

/**
 * One segment of a route cut at a fixed distance interval — a "split", in the language
 * every race and every route planner uses.
 *
 * [seconds] is a Tobler estimate from the elevation actually crossed in this interval, not a
 * flat-speed guess, so the splits of a climb and the splits of the descent that follows it
 * are reported in the proportions a walker will feel them.
 */
data class Split(
  /** 1-based, matching "km 1, km 2, …" rather than an array index. */
  val index: Int,
  val startDistanceMeters: Double,
  val endDistanceMeters: Double,
  val distanceMeters: Double,
  val gainMeters: Double,
  val lossMeters: Double,
  val startElevationMeters: Double?,
  val endElevationMeters: Double?,
  /** Net gradient across the split, in percent. Null without elevation data at both ends. */
  val averageGradientPercent: Double?,
  val seconds: Double,
  val cumulativeSeconds: Double,
  /** True for the final split of a route that does not end on a whole interval. */
  val isPartial: Boolean,
) {
  val elevationDeltaMeters: Double?
    get() {
      val start = startElevationMeters ?: return null
      val end = endElevationMeters ?: return null
      return end - start
    }
}

/** A whole route cut into equal-distance [splits]. */
data class Splits(
  val splits: List<Split>,
  val splitLengthMeters: Double,
  val totalDistanceMeters: Double,
  val totalSeconds: Double,
) {
  /** Fastest full-length split, which is the one people compare. Null when none qualifies. */
  val fastestSplit: Split?
    get() = splits
      .filter { !it.isPartial && it.distanceMeters > 0.0 && it.seconds > 0.0 }
      .minByOrNull { it.seconds / it.distanceMeters }

  companion object {
    val Empty = Splits(emptyList(), SplitsCalculator.KILOMETER, 0.0, 0.0)
  }
}

/**
 * Cuts a track into equal-distance splits.
 *
 * The awkward part is that split boundaries almost never land on a track point, so a leg of
 * the route frequently straddles two splits. Both distance and elevation are distributed
 * across the straddled splits in proportion to the ground each one takes, which is what
 * keeps the per-split numbers summing to the whole-route numbers: an app whose splits
 * disagree with its totals is an app nobody trusts.
 *
 * Elevation gain and loss keep the same hysteresis the rest of the app uses, and a committed
 * change is attributed to the split where it happened. Resetting the reference at every
 * boundary — the obvious implementation — silently deletes a few metres of climb per
 * kilometre on a long ascent, which on an alpine pass is hundreds of metres.
 */
object SplitsCalculator {

  /** Splits are asked for in these two lengths and nothing else, in practice. */
  const val KILOMETER = 1000.0
  const val MILE = 1_609.344

  /**
   * Splits shorter than this are dropped.
   *
   * A route of 10,000.4 m would otherwise end with a 40 cm split whose gain is noise. Real
   * partial splits — the last 600 m of a 21.6 km half marathon — are far longer than this.
   */
  private const val MINIMUM_SPLIT_METERS = 1.0

  private const val DISTANCE_EPSILON = 1e-6

  fun compute(
    track: Track,
    splitLengthMeters: Double = KILOMETER,
    elevationThresholdMeters: Double = ElevationCalculator.DEFAULT_THRESHOLD_METERS,
  ): Splits {
    require(splitLengthMeters > 0.0) { "Split length must be positive: $splitLengthMeters" }

    val accumulators = ArrayList<Accumulator>()
    var cursor = 0.0
    var splitIndex = 0
    var referenceElevation: Double? = null

    for (segment in track.segments) {
      val points = segment.points
      if (points.isEmpty()) continue

      // The reference resets at a gap: the climb across unrecorded ground is unknown.
      referenceElevation = points.first().elevationMeters

      for (index in 1 until points.size) {
        val start = points[index - 1]
        val end = points[index]

        val legLength = Geodesic.distanceMeters(start.position, end.position)
        if (legLength <= 0.0) continue

        val startElevation = start.elevationMeters
        val endElevation = end.elevationMeters

        val committed = commitElevation(referenceElevation, endElevation, elevationThresholdMeters)
        referenceElevation = committed.reference
        var gain = committed.gain
        var loss = committed.loss

        var consumed = 0.0
        var remaining = legLength

        while (remaining > DISTANCE_EPSILON) {
          val splitEnd = (splitIndex + 1) * splitLengthMeters
          val room = splitEnd - cursor
          if (room <= DISTANCE_EPSILON) {
            splitIndex++
            continue
          }

          val take = min(room, remaining)
          val fraction = take / legLength

          val accumulator = accumulator(accumulators, splitIndex)
          if (accumulator.distanceMeters == 0.0) {
            accumulator.startElevationMeters = elevationAt(startElevation, endElevation, consumed / legLength)
          }

          accumulator.distanceMeters += take
          accumulator.gainMeters += gain * fraction
          accumulator.lossMeters += loss * fraction

          val deltaElevation = if (startElevation != null && endElevation != null) {
            (endElevation - startElevation) * fraction
          } else {
            0.0
          }
          accumulator.seconds += ToblerEstimator.hikingSeconds(take, deltaElevation)

          consumed += take
          remaining -= take
          cursor += take

          accumulator.endElevationMeters =
            elevationAt(startElevation, endElevation, consumed / legLength)

          gain = 0.0
          loss = 0.0

          if (cursor >= splitEnd - DISTANCE_EPSILON) splitIndex++
        }
      }
    }

    var cumulative = 0.0
    val splits = ArrayList<Split>(accumulators.size)

    for ((index, accumulator) in accumulators.withIndex()) {
      if (accumulator.distanceMeters < MINIMUM_SPLIT_METERS) continue

      cumulative += accumulator.seconds
      val start = index * splitLengthMeters

      splits.add(
        Split(
          index = index + 1,
          startDistanceMeters = start,
          endDistanceMeters = start + accumulator.distanceMeters,
          distanceMeters = accumulator.distanceMeters,
          gainMeters = accumulator.gainMeters,
          lossMeters = accumulator.lossMeters,
          startElevationMeters = accumulator.startElevationMeters,
          endElevationMeters = accumulator.endElevationMeters,
          averageGradientPercent = netGradient(accumulator),
          seconds = accumulator.seconds,
          cumulativeSeconds = cumulative,
          isPartial = accumulator.distanceMeters < splitLengthMeters - DISTANCE_EPSILON,
        ),
      )
    }

    return Splits(
      splits = splits,
      splitLengthMeters = splitLengthMeters,
      totalDistanceMeters = splits.sumOf { it.distanceMeters },
      totalSeconds = cumulative,
    )
  }

  private fun accumulator(accumulators: MutableList<Accumulator>, index: Int): Accumulator =
    accumulators.getOrElse(index) { Accumulator().also { accumulators.add(it) } }

  private fun elevationAt(start: Double?, end: Double?, fraction: Double): Double? =
    if (start != null && end != null) start + (end - start) * fraction else null

  private fun netGradient(accumulator: Accumulator): Double? {
    val start = accumulator.startElevationMeters ?: return null
    val end = accumulator.endElevationMeters ?: return null
    if (accumulator.distanceMeters <= 0.0) return null
    return (end - start) / accumulator.distanceMeters * 100.0
  }

  /** Applies the shared hysteresis and reports what, if anything, was committed. */
  private fun commitElevation(
    reference: Double?,
    elevation: Double?,
    thresholdMeters: Double,
  ): Committed {
    if (elevation == null) return Committed(null, 0.0, 0.0)
    val current = reference ?: return Committed(elevation, 0.0, 0.0)

    val delta = elevation - current
    return when {
      delta > thresholdMeters -> Committed(elevation, delta, 0.0)
      delta < -thresholdMeters -> Committed(elevation, 0.0, -delta)
      else -> Committed(current, 0.0, 0.0)
    }
  }

  private data class Committed(
    val reference: Double?,
    val gain: Double,
    val loss: Double,
  )

  private class Accumulator {
    var distanceMeters: Double = 0.0
    var gainMeters: Double = 0.0
    var lossMeters: Double = 0.0
    var startElevationMeters: Double? = null
    var endElevationMeters: Double? = null
    var seconds: Double = 0.0
  }
}
