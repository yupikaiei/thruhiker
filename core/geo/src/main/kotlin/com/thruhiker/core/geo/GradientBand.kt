package com.thruhiker.core.geo

/**
 * A colour-coded band of gradient, in the sense every trail-running route planner uses.
 *
 * A trail is not described by its average gradient — a 6% average can be a fire road or a
 * staircase — so the useful summary is how much of the route sits in each band, and the
 * useful map is one whose line is painted by band. The colours below are the familiar
 * blue-to-red ramp: blue downhill, grey flat, red uphill.
 *
 * The boundaries are deliberately the ones a runner reads at a glance rather than round
 * numbers: 3%, 7% and 15% cap and foot the ranges people actually talk about ("rolling",
 * "climby", "steep").
 *
 * [UNKNOWN] exists because a planned route, or a GPX recorded without a barometer, has
 * horizontal geometry and no vertical profile at all. Painting that grey is honest; calling
 * it flat would be a lie that makes a Himalayan pass look like a towpath.
 */
enum class GradientBand(
  val label: String,
  val colorHex: String,
  /** Exclusive upper bound of the band, in percent. [UNKNOWN] carries no bound. */
  val upperBoundPercent: Double,
) {
  STEEP_DESCENT("Steep descent", "#2C7BB6", -15.0),
  DESCENT("Descent", "#6FAEDC", -7.0),
  GENTLE_DESCENT("Gentle descent", "#B7D6EA", -3.0),
  FLAT("Flat", "#C2C7CC", 3.0),
  GENTLE_CLIMB("Gentle climb", "#F6C177", 7.0),
  CLIMB("Climb", "#EF8534", 15.0),
  STEEP_CLIMB("Steep climb", "#D7191C", Double.POSITIVE_INFINITY),
  UNKNOWN("No elevation data", "#9AA0A6", Double.NaN),
  ;

  companion object {
    /** Steepest descent first, so a linear scan finds the first band the grade falls under. */
    private val ORDERED = listOf(
      STEEP_DESCENT,
      DESCENT,
      GENTLE_DESCENT,
      FLAT,
      GENTLE_CLIMB,
      CLIMB,
      STEEP_CLIMB,
    )

    /** The band a gradient falls in, or [UNKNOWN] when the grade is not known. */
    fun of(gradePercent: Double?): GradientBand {
      if (gradePercent == null || !gradePercent.isFinite()) return UNKNOWN
      for (band in ORDERED) {
        if (gradePercent < band.upperBoundPercent) return band
      }
      return STEEP_CLIMB
    }
  }
}
