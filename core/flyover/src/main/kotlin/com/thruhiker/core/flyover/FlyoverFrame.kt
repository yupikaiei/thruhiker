package com.thruhiker.core.flyover

import com.thruhiker.core.model.CameraOptions
import com.thruhiker.core.model.LatLng

/** Which part of the flight a frame belongs to. */
enum class FlyoverPhase {
  /** Establishing shot over the trailhead, settling into the travelling camera. */
  Intro,

  /** Flying the route. */
  Travel,

  /** Pulling back from the finish. */
  Outro,
}

/**
 * One frame of the flyover, at 60 frames a second.
 *
 * [revealedFraction] is what the map should draw: on a full recording it runs 0 to
 * 1 across the travel phase and stays at 0 and 1 through the intro and outro, so
 * the route draws itself as the camera flies it.
 */
data class FlyoverFrame(
  val phase: FlyoverPhase,
  val camera: CameraOptions,
  /** Position in the whole timeline, 0 to 1. Used for progress indicators. */
  val progress: Double,
  val revealedFraction: Double,
  val distanceAlongTrackMeters: Double,
  val elevationMeters: Double?,
  val position: LatLng,
)
