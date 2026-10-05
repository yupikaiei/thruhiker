package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** Shape knobs for [LoopGenerator]. */
data class LoopOptions(
  /** Corners in the loop's control polygon. Fewer is rounder, more is more lobed. */
  val controlPoints: Int = 9,
  /** How far each corner's radius may stray from the ideal circle, as a fraction. */
  val irregularity: Double = 0.28,
  /** Where the first corner sits, so successive loops do not all point the same way. */
  val rotationDegrees: Double = 37.0,
  /** Fixes the shape, so the same request always produces the same loop. */
  val seed: Long = 1L,
  val samplesPerSpan: Int = 10,
  val maximumSpacingMeters: Double = TrackOperations.DEFAULT_SPACING_METERS,
)

/**
 * Draws a closed loop of a requested length around a start point.
 *
 * This is the "generate a loop" half of a route planner, with one honest limitation: it does
 * not follow trails. Real trail-following needs a routing graph over the OpenStreetMap
 * network, which is a server and a downloaded graph, and neither exists in an offline-first
 * app with no backend. What this produces is a shape — a scenic starting point the user can
 * drag waypoints around, or a base for a hand-drawn route — and it is labelled as such
 * wherever it is offered.
 *
 * The loop is a closed Catmull-Rom spline through a ring of control points at a jittered
 * radius from the start. A circle of circumference `D` has radius `D / 2π`, which is where the
 * first guess comes from; because the spline through a lobed ring is not exactly that circle,
 * the radius is then rescaled by the ratio of the requested length to the measured one, a few
 * times, until the loop is the length that was asked for.
 */
object LoopGenerator {

  /** Regeneration passes needed for the length to settle. Three is ample; four is free. */
  private const val REFINEMENTS = 4

  fun generate(
    start: LatLng,
    targetDistanceMeters: Double,
    options: LoopOptions = LoopOptions(),
  ): Track {
    require(start.isValid) { "Invalid start: $start" }
    require(targetDistanceMeters > 0.0) {
      "Target distance must be positive: $targetDistanceMeters"
    }
    require(options.controlPoints >= 3) {
      "A loop needs at least three control points: ${options.controlPoints}"
    }
    require(options.irregularity in 0.0..0.9) {
      "Irregularity must be within 0.0..0.9: ${options.irregularity}"
    }
    require(options.samplesPerSpan >= 2) {
      "Each span needs at least two samples: ${options.samplesPerSpan}"
    }

    var scale = 1.0
    repeat(REFINEMENTS - 1) {
      val length = Geodesic.pathLengthMeters(loopPositions(start, targetDistanceMeters, scale, options))
      if (length > 0.0) scale *= targetDistanceMeters / length
    }

    val positions = loopPositions(start, targetDistanceMeters, scale, options)
    return Track.of(positions.map { TrackPoint(it) })
  }

  private fun loopPositions(
    start: LatLng,
    targetDistanceMeters: Double,
    scale: Double,
    options: LoopOptions,
  ): List<LatLng> {
    val control = controlRing(start, targetDistanceMeters, scale, options)
    val spline = catmullRomClosed(control, options.samplesPerSpan, start.longitude)
    return TrackOperations.densifyPositions(spline, options.maximumSpacingMeters)
  }

  /** A ring of points around [start], each at a jittered radius from it. */
  private fun controlRing(
    start: LatLng,
    targetDistanceMeters: Double,
    scale: Double,
    options: LoopOptions,
  ): List<LatLng> {
    val baseRadius = targetDistanceMeters / (2.0 * PI) * scale
    val count = options.controlPoints

    return (0 until count).map { index ->
      val bearing = options.rotationDegrees + 360.0 * index / count
      val jitter = pseudoRandom(options.seed, index) * 2.0 * options.irregularity - options.irregularity
      val radius = baseRadius * (1.0 + jitter)
      Geodesic.destination(start, bearing, radius)
    }
  }

  /**
   * Samples a closed Catmull-Rom spline through [control].
   *
   * Longitudes are unwrapped relative to [referenceLongitude] first and rewrapped afterwards,
   * so a loop drawn across the antimeridian does not draw a line all the way around the world
   * on the way back.
   */
  private fun catmullRomClosed(
    control: List<LatLng>,
    samplesPerSpan: Int,
    referenceLongitude: Double,
  ): List<LatLng> {
    val count = control.size
    val unwrapped = control.map { point ->
      LatLng(
        latitude = point.latitude,
        longitude = referenceLongitude +
          Geodesic.normalizeLongitudeDelta(point.longitude - referenceLongitude),
      )
    }

    val result = ArrayList<LatLng>(count * samplesPerSpan + 1)

    for (span in 0 until count) {
      val p0 = unwrapped[(span - 1 + count) % count]
      val p1 = unwrapped[span]
      val p2 = unwrapped[(span + 1) % count]
      val p3 = unwrapped[(span + 2) % count]

      for (sample in 0 until samplesPerSpan) {
        val t = sample.toDouble() / samplesPerSpan
        result.add(
          LatLng(
            latitude = spline(p0.latitude, p1.latitude, p2.latitude, p3.latitude, t),
            longitude = Geodesic.normalizeLongitude(
              spline(p0.longitude, p1.longitude, p2.longitude, p3.longitude, t),
            ),
          ),
        )
      }
    }

    // Close the ring on the exact starting point rather than on the spline's approximation.
    result.add(unwrapped.first().let { LatLng(it.latitude, Geodesic.normalizeLongitude(it.longitude)) })
    return result
  }

  /** Catmull-Rom interpolation of one coordinate, with the usual 0.5 tension. */
  private fun spline(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5 * (
      2.0 * p1 +
        (-p0 + p2) * t +
        (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
        (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
      )
  }

  /**
   * A deterministic value in `[0, 1)` from a seed and a corner index.
   *
   * Deliberately not [kotlin.random.Random]: the point is that the same seed produces the
   * same loop on every device, every version and every run, which is what lets the shape be
   * tested at all.
   */
  private fun pseudoRandom(seed: Long, index: Int): Double {
    val value = sin(seed * 12.9898 + index * 78.233) * 43758.5453
    return value - floor(value)
  }
}
