package com.thruhiker.core.mapping

import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Picks a camera that frames a whole route.
 *
 * Without this, importing the PCT while the camera sits on Mont Blanc shows the
 * user an empty map and reads as a failure, even though everything worked.
 *
 * The zoom is derived from the web-Mercator geometry rather than a lookup table:
 * at zoom `z` the world is `256 * 2^z` pixels wide, so a span of `d` kilometres
 * occupies `256 * 2^z * d / 40075` pixels. Solving for the zoom that fits the span
 * across a phone-width viewport gives the expression below.
 */
object TrackFraming {

  /**
   * Pixels-per-world-width budget. Chosen so a route fills most of a phone screen
   * with a little margin, rather than touching the edges exactly.
   */
  private const val FIT_CONSTANT_KM = 60_000.0

  private const val MIN_ZOOM = 3.0
  private const val MAX_ZOOM = 15.0

  fun cameraFor(
    track: Track,
    tilt: Double = CameraOptions.CINEMATIC_TILT,
  ): CameraOptions? {
    val positions = track.positions
    if (positions.isEmpty()) return null

    val center = centroid(positions)
    val diagonalKm = diagonalKilometers(positions, center)

    return CameraOptions(
      target = center,
      zoom = zoomFor(diagonalKm),
      tilt = tilt,
      bearing = 0.0,
    )
  }

  /**
   * Mean position computed on the sphere.
   *
   * Averaging latitudes and longitudes directly is wrong across the antimeridian:
   * a track either side of 180 degrees would centre near Greenwich. Averaging unit
   * vectors has no such seam.
   */
  fun centroid(positions: List<LatLng>): LatLng {
    require(positions.isNotEmpty()) { "Cannot find a centroid of no positions" }

    var x = 0.0
    var y = 0.0
    var z = 0.0

    for (position in positions) {
      val latitude = Math.toRadians(position.latitude)
      val longitude = Math.toRadians(position.longitude)
      x += cos(latitude) * cos(longitude)
      y += cos(latitude) * sin(longitude)
      z += sin(latitude)
    }

    return LatLng(
      latitude = Math.toDegrees(atan2(z, hypot(x, y))),
      longitude = Math.toDegrees(atan2(y, x)),
    )
  }

  /**
   * Twice the farthest distance from the centre, so that a linear trail's diagonal
   * approximates its length rather than its width.
   */
  fun diagonalKilometers(positions: List<LatLng>, center: LatLng = centroid(positions)): Double {
    var farthest = 0.0
    for (position in positions) {
      val distance = Geodesic.distanceMeters(center, position)
      if (distance > farthest) farthest = distance
    }
    return 2.0 * farthest / 1000.0
  }

  /** Zoom that fits a span of [diagonalKilometers] across a phone-width viewport. */
  fun zoomFor(diagonalKilometers: Double): Double {
    if (diagonalKilometers <= 0.0) return MAX_ZOOM
    val zoom = ln(FIT_CONSTANT_KM / diagonalKilometers) / ln(2.0)
    return zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
  }
}
