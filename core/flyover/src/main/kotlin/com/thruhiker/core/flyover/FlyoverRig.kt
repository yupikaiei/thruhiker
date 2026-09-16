package com.thruhiker.core.flyover

import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.geo.TrackSampler
import com.thruhiker.core.model.CameraOptions
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import kotlin.math.pow

/**
 * Directs the camera for a cinematic flight over a track.
 *
 * The whole thing is a pure function of elapsed time: [frameAt] takes milliseconds
 * and returns a camera. That is what makes it testable without a map, and what will
 * make video export possible, because the export path can ask for frame 1,247
 * without having played frames 1 to 1,246.
 *
 * The flight has three parts. An intro settles from a wide establishing shot onto
 * the trailhead, turned off-axis so the first sweep reveals the terrain. Travel
 * flies the route at a constant ground speed with the camera looking a little ahead
 * of the walker, offset in bearing so the route runs diagonally across the frame
 * instead of dead ahead into the distance. The outro pulls up and flattens out over
 * the finish.
 */
class FlyoverRig private constructor(
  private val sampler: TrackSampler,
  private val options: FlyoverOptions,
  val travelMillis: Long,
) {

  val durationMillis: Long get() = options.introMillis + travelMillis + options.outroMillis

  /** The frame at [elapsedMillis] from the start, clamped to the flight's extent. */
  fun frameAt(elapsedMillis: Long): FlyoverFrame {
    val elapsed = elapsedMillis.coerceIn(0L, durationMillis)
    val progress = if (durationMillis > 0L) elapsed.toDouble() / durationMillis else 1.0
    val travelStart = options.introMillis
    val travelEnd = travelStart + travelMillis

    return when {
      elapsed < travelStart -> introFrame(elapsed, progress)
      elapsed < travelEnd -> travelFrame(elapsed - travelStart, progress)
      else -> outroFrame(elapsed - travelEnd, progress)
    }
  }

  private fun introFrame(elapsed: Long, progress: Double): FlyoverFrame {
    val raw = if (options.introMillis > 0L) elapsed.toDouble() / options.introMillis else 1.0
    // Ease-out: move off the wide shot quickly, then settle, which reads as a
    // camera operator rather than a linear slide.
    val eased = easeOutCubic(raw)

    val trailhead = sampler.sampleAt(0.0)
    val settled = travelState(0.0)
    val opening = CameraState(
      target = trailhead.position,
      zoom = options.overviewZoom,
      tilt = options.introTilt,
      // Start turned off the direction of travel and sweep onto it.
      bearing = Geodesic.normalizeDegrees(settled.bearing - options.introSweepDegrees),
    )

    return FlyoverFrame(
      phase = FlyoverPhase.Intro,
      camera = lerpState(opening, settled, eased).toCameraOptions(),
      progress = progress,
      revealedFraction = 0.0,
      distanceAlongTrackMeters = 0.0,
      elevationMeters = trailhead.elevationMeters,
      position = trailhead.position,
    )
  }

  private fun travelFrame(elapsed: Long, progress: Double): FlyoverFrame {
    val raw = if (travelMillis > 0L) elapsed.toDouble() / travelMillis else 1.0
    val clamped = raw.coerceIn(0.0, 1.0)
    val distance = clamped * sampler.totalDistanceMeters
    val here = sampler.sampleAt(distance)

    return FlyoverFrame(
      phase = FlyoverPhase.Travel,
      camera = travelState(distance).toCameraOptions(),
      progress = progress,
      revealedFraction = clamped,
      distanceAlongTrackMeters = distance,
      elevationMeters = here.elevationMeters,
      position = here.position,
    )
  }

  private fun outroFrame(elapsed: Long, progress: Double): FlyoverFrame {
    val raw = if (options.outroMillis > 0L) elapsed.toDouble() / options.outroMillis else 1.0
    val eased = easeInOutCubic(raw)

    val finish = sampler.sampleAt(sampler.totalDistanceMeters)
    val travelling = travelState(sampler.totalDistanceMeters)
    val settling = CameraState(
      target = finish.position,
      zoom = options.outroZoom,
      tilt = options.outroTilt,
      // Hold the arrival bearing: rotating during the pull-back draws attention to
      // the rotation rather than to the country the walker just crossed.
      bearing = travelling.bearing,
    )

    return FlyoverFrame(
      phase = FlyoverPhase.Outro,
      camera = lerpState(travelling, settling, eased).toCameraOptions(),
      progress = progress,
      revealedFraction = 1.0,
      distanceAlongTrackMeters = sampler.totalDistanceMeters,
      elevationMeters = finish.elevationMeters,
      position = finish.position,
    )
  }

  /** The travelling camera at a given distance along the route. */
  private fun travelState(distance: Double): CameraState {
    val ahead = sampler.sampleAt(distance + options.lookAheadMeters)
    val behind = sampler.sampleAt(distance - options.bearingWindowMeters)
    val forward = sampler.sampleAt(distance + options.bearingWindowMeters)

    // Fall back to the local leg bearing near a route's ends, or on degenerate
    // geometry where the window collapses to a single point.
    val direction = if (Geodesic.distanceMeters(behind.position, forward.position) > 1.0) {
      Geodesic.initialBearingDegrees(behind.position, forward.position)
    } else {
      sampler.sampleAt(distance).bearingDegrees
    }

    return CameraState(
      target = ahead.position,
      zoom = options.travelZoom,
      tilt = options.travelTilt,
      bearing = Geodesic.normalizeDegrees(direction + options.bearingOffsetDegrees),
    )
  }

  companion object {
    /** Null when there is nothing to fly over, which is how callers detect an empty route. */
    fun of(track: Track, options: FlyoverOptions = FlyoverOptions()): FlyoverRig? {
      val sampler = TrackSampler.of(track) ?: return null
      return FlyoverRig(sampler, options, options.travelMillisFor(sampler.totalDistanceMeters))
    }
  }
}

/** A camera position mid-interpolation. */
private data class CameraState(
  val target: LatLng,
  val zoom: Double,
  val tilt: Double,
  val bearing: Double,
) {
  fun toCameraOptions(): CameraOptions = CameraOptions(
    target = target,
    zoom = zoom,
    tilt = tilt,
    bearing = bearing,
  )
}

private fun lerpState(from: CameraState, to: CameraState, fraction: Double): CameraState {
  val t = fraction.coerceIn(0.0, 1.0)
  return CameraState(
    target = Geodesic.interpolate(from.target, to.target, t),
    zoom = lerp(from.zoom, to.zoom, t),
    tilt = lerp(from.tilt, to.tilt, t),
    bearing = lerpAngle(from.bearing, to.bearing, t),
  )
}

private fun lerp(from: Double, to: Double, fraction: Double): Double =
  from + (to - from) * fraction

/**
 * Rotates from one bearing to another the short way round.
 *
 * 350 to 10 degrees is 20 degrees of rotation and not 340, and getting that wrong
 * spins the camera most of the way round the compass between two nearly adjacent
 * headings.
 */
internal fun lerpAngle(from: Double, to: Double, fraction: Double): Double =
  Geodesic.normalizeDegrees(from + shortestDeltaDegrees(from, to) * fraction.coerceIn(0.0, 1.0))

/** Signed difference [to] - [from] wrapped into (-180, 180]. */
internal fun shortestDeltaDegrees(from: Double, to: Double): Double {
  var delta = (to - from) % 360.0
  if (delta > 180.0) delta -= 360.0
  if (delta <= -180.0) delta += 360.0
  return delta
}

private fun easeOutCubic(x: Double): Double {
  val c = x.coerceIn(0.0, 1.0)
  return 1.0 - (1.0 - c).pow(3)
}

private fun easeInOutCubic(x: Double): Double {
  val c = x.coerceIn(0.0, 1.0)
  return if (c < 0.5) {
    4.0 * c * c * c
  } else {
    1.0 - (-2.0 * c + 2.0).pow(3) / 2.0
  }
}
