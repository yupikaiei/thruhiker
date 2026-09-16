package com.thruhiker.core.gpx

import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint

/** A point of interest carried alongside a track, such as a water source or a summit. */
data class GpxWaypoint(
  val name: String?,
  val point: TrackPoint,
)

/**
 * The useful contents of a GPX file.
 *
 * GPX can describe tracks, routes and waypoints at once, and real trail files use
 * all three: a route for the plan, a track for the recorded walk, and waypoints
 * for the water sources. Routes are folded into [track] because everything
 * downstream, distances, elevation, the flyover, treats them identically.
 */
data class GpxDocument(
  val name: String? = null,
  val description: String? = null,
  val track: Track = Track.Empty,
  val waypoints: List<GpxWaypoint> = emptyList(),
) {
  val isEmpty: Boolean get() = track.isEmpty && waypoints.isEmpty()
}

/** Thrown when a file is not usable GPX. Carries the reason, never a stack trace alone. */
class GpxParseException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
