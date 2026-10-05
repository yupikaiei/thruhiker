package com.thruhiker.core.geo

import com.thruhiker.core.model.Track

/**
 * A coarse, planning-grade difficulty rating.
 *
 * Deliberately effort-based rather than technical. A route's difficulty for someone
 * planning a day out is dominated by how much climbing and distance it packs in; whether the
 * rock needs hands is a different question that no amount of GPS data answers.
 */
enum class TrailDifficulty(val label: String) {
  EASY("Easy"),
  MODERATE("Moderate"),
  HARD("Hard"),
  SEVERE("Severe"),
  EXTREME("Extreme"),
}

/** Tunables for [RouteAnalysis]. */
data class RouteAnalysisOptions(
  /**
   * Legs shorter than this are ignored when hunting for the steepest gradient.
   *
   * A ten-metre leg with a barometric blip of four metres reads as 40%, and there is no
   * shortage of those in any real recording. Requiring a leg long enough for the gradient to
   * mean something is what keeps the headline number believable.
   */
  val minimumLegMeters: Double = 20.0,
  val sampleCount: Int = ElevationProfile.DEFAULT_SAMPLE_COUNT,
)

/**
 * Everything a planner wants to know about a route in one object.
 *
 * This is the analysis half of the app: distance and climb for the plan, gradient
 * distribution for the map and the profile colours, and a difficulty rating for the
 * one-line summary.
 */
data class RouteAnalysis(
  val stats: TrackStats,
  /** Tobler walking time for the whole route, in seconds. */
  val walkingSeconds: Double,
  /** Net climb per metre of ground, in percent. Null when the route has no elevation. */
  val averageGradientPercent: Double?,
  /** Steepest sustained climb, in percent. Null when no leg is long enough to judge. */
  val steepestClimbPercent: Double?,
  /** Steepest sustained descent, in percent (negative). Null when no leg qualifies. */
  val steepestDescentPercent: Double?,
  /** Fraction of the route's length in each gradient band, by distance. */
  val gradientDistribution: Map<GradientBand, Double>,
  val difficulty: TrailDifficulty,
) {
  /** The band with the largest share of the route, or null when there is no elevation. */
  val dominantBand: GradientBand?
    get() = gradientDistribution.maxByOrNull { it.value }?.key

  /** Walking time in hours, for display. */
  val walkingHours: Double get() = walkingSeconds / 3600.0

  companion object {
    fun of(track: Track, options: RouteAnalysisOptions = RouteAnalysisOptions()): RouteAnalysis {
      val stats = TrackStatsCalculator.compute(track)
      val profile = ElevationProfile.of(track, options.sampleCount)
      val extremes = steepestGradients(track, options.minimumLegMeters)

      val distance = stats.distanceMeters
      val averageGradient = if (distance > 0.0 && profile.hasElevation) {
        val net = stats.elevationGainMeters - stats.elevationLossMeters
        net / distance * 100.0
      } else {
        null
      }

      return RouteAnalysis(
        stats = stats,
        walkingSeconds = ToblerEstimator.hikingSeconds(track),
        averageGradientPercent = averageGradient,
        steepestClimbPercent = extremes.climb,
        steepestDescentPercent = extremes.descent,
        gradientDistribution = bandFractions(profile.samples),
        difficulty = difficultyFor(distance, stats.elevationGainMeters),
      )
    }

    /**
     * Difficulty from distance and climb.
     *
     * The score is kilometres plus hundreds of metres of ascent, which is the time-honoured
     * way walkers compare two routes: 10 km and 500 m of climb costs about the same as 15 km
     * and nothing. The cut-offs are deliberately generous at the top: a route that scores
     * "severe" is a big day, and one that scores "extreme" is a race.
     */
    fun difficultyFor(distanceMeters: Double, gainMeters: Double): TrailDifficulty {
      val effort = distanceMeters / 1000.0 + gainMeters / 100.0
      return when {
        effort < EASY_CEILING -> TrailDifficulty.EASY
        effort < MODERATE_CEILING -> TrailDifficulty.MODERATE
        effort < HARD_CEILING -> TrailDifficulty.HARD
        effort < SEVERE_CEILING -> TrailDifficulty.SEVERE
        else -> TrailDifficulty.EXTREME
      }
    }

    /**
     * Share of the route's length in each gradient band.
     *
     * Weighted by distance rather than by sample count so that a section where the GNSS
     * logged twice as often does not count twice as much.
     */
    fun bandFractions(samples: List<ElevationSample>): Map<GradientBand, Double> {
      if (samples.size < 2) return emptyMap()

      val totals = LinkedHashMap<GradientBand, Double>()
      var total = 0.0

      for (index in 1 until samples.size) {
        val weight = samples[index].distanceMeters - samples[index - 1].distanceMeters
        if (weight <= 0.0) continue
        total += weight
        val band = samples[index].band
        totals[band] = (totals[band] ?: 0.0) + weight
      }

      if (total <= 0.0) return emptyMap()
      return totals.mapValues { it.value / total }
    }

    private fun steepestGradients(track: Track, minimumLegMeters: Double): Extremes {
      var climb: Double? = null
      var descent: Double? = null

      for (segment in track.segments) {
        val points = segment.points
        for (index in 1 until points.size) {
          val previous = points[index - 1]
          val current = points[index]

          val previousElevation = previous.elevationMeters ?: continue
          val currentElevation = current.elevationMeters ?: continue

          val distance = Geodesic.distanceMeters(previous.position, current.position)
          if (distance < minimumLegMeters) continue

          val grade = (currentElevation - previousElevation) / distance * 100.0
          if (grade > 0.0 && (climb == null || grade > climb)) climb = grade
          if (grade < 0.0 && (descent == null || grade < descent)) descent = grade
        }
      }

      return Extremes(climb, descent)
    }

    private const val EASY_CEILING = 10.0
    private const val MODERATE_CEILING = 20.0
    private const val HARD_CEILING = 40.0
    private const val SEVERE_CEILING = 70.0
  }

  private data class Extremes(val climb: Double?, val descent: Double?)
}
