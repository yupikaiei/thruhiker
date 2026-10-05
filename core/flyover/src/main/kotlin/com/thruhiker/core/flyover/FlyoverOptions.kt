package com.thruhiker.core.flyover

/**
 * Knobs for the cinematic camera.
 *
 * The defaults are tuned for a day hike: a few seconds to settle in, a pace fast
 * enough to hold attention, and a camera pitched far enough over to read relief
 * without losing the horizon.
 */
data class FlyoverOptions(
  /** Establishing shot before the walk begins. */
  val introMillis: Long = 3_000L,

  /** Pull-back at the finish. */
  val outroMillis: Long = 3_500L,

  /** Screen time per kilometre of route, before clamping. */
  val secondsPerKilometre: Double = 0.8,

  /**
   * Travel duration is clamped rather than proportional to length.
   *
   * Without a floor, a 2 km stroll is over before the viewer has focused. Without
   * a ceiling, the Pacific Crest Trail takes two hours to fly.
   */
  val minimumTravelMillis: Long = 6_000L,
  val maximumTravelMillis: Long = 70_000L,

  val travelTilt: Double = 60.0,
  val introTilt: Double = 38.0,
  val outroTilt: Double = 30.0,

  val overviewZoom: Double = 11.0,
  val travelZoom: Double = 13.5,
  val outroZoom: Double = 10.5,

  /**
   * Yaw offset applied while travelling.
   *
   * Looking exactly along the direction of travel flattens the terrain, because
   * the ground ahead is foreshortened into nothing. A slight offset turns the
   * route into a diagonal across the frame and makes the relief legible.
   */
  val bearingOffsetDegrees: Double = 20.0,

  /**
   * How far ahead of the walker the camera aims, along the route.
   *
   * Zero, deliberately. The camera's target is the point MapLibre puts in the middle of
   * the screen, and the reveal draws the route *behind* the walker, so any look-ahead
   * pushes the drawn end of the line further down the frame: at the travelling tilt the
   * ground behind the target is magnified, and the 350 m this used to be measured as a
   * quarter of the screen height. The walker ended up near the bottom edge, under the
   * info panel, with the route trailing off screen — which is exactly what "the path is
   * not centred" looks like from the outside.
   *
   * Aiming at the walker centres the moving end of the route and lets the ground ahead
   * fill the frame above it, because the camera still looks forward: it is the tilt that
   * decides where it looks, and this only decides which point is in the middle.
   */
  val lookAheadMeters: Double = 0.0,

  /**
   * How far either side of the walker the camera's heading is eased over.
   *
   * Necessary because a raw leg bearing jitters wherever the track bends or GPS noise
   * creeps in, and a jittering bearing reads as a camera having a fit. Easing the heading
   * along the route rather than across it also means a hairpin becomes a pan spread over
   * this distance instead of a spin through most of the compass in a few frames, so the
   * window wants to be generous: it costs nothing but a little lag on a turn.
   */
  val bearingWindowMeters: Double = 800.0,

  /** Sweep the intro camera turns through before settling behind the walker. */
  val introSweepDegrees: Double = 70.0,
) {
  init {
    require(introMillis >= 0L) { "Intro must not be negative: $introMillis" }
    require(outroMillis >= 0L) { "Outro must not be negative: $outroMillis" }
    require(minimumTravelMillis > 0L) { "Minimum travel must be positive: $minimumTravelMillis" }
    require(maximumTravelMillis >= minimumTravelMillis) {
      "Maximum travel ($maximumTravelMillis) must be at least the minimum ($minimumTravelMillis)"
    }
    require(secondsPerKilometre >= 0.0) { "Pace must not be negative: $secondsPerKilometre" }
    require(lookAheadMeters >= 0.0) { "Look-ahead must not be negative: $lookAheadMeters" }
    require(bearingWindowMeters >= 0.0) { "Bearing window must not be negative: $bearingWindowMeters" }
  }

  /** Travel duration for a route of a given length, clamped. */
  fun travelMillisFor(totalDistanceMeters: Double): Long {
    val kilometres = totalDistanceMeters / 1000.0
    val requested = kilometres * secondsPerKilometre * 1000.0
    return requested.toLong().coerceIn(minimumTravelMillis, maximumTravelMillis)
  }
}
