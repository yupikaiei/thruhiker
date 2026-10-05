package com.thruhiker.core.dem

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackSegment

/**
 * What one elevation fill achieved, or null when there was nothing to do.
 *
 * [zoom] and [tiles] are reported because they are the honest measure of how good the profile
 * is: a route long enough to need a coarser zoom has a blunter profile, and the caller is
 * entitled to say so rather than presenting it as measured ground.
 */
data class ElevationFill(
  val track: Track,
  val zoom: Int,
  val tiles: Int,
  val pointsFilled: Int,
)

/**
 * Gives a hand-drawn route the vertical profile it does not have.
 *
 * A route tapped onto the map is a line with no heights on it, which leaves the app's whole
 * vertical half — the gradient colours on the map, the elevation profile, the climb, the
 * difficulty rating, the effort behind each split — switched off. The heights are not
 * discoverable from the geometry, so they have to be looked up, and the tiles the map is
 * already using to draw hillshade are exactly the right place to look them up.
 *
 * Two rules keep this from being a nuisance:
 *
 * - **A recorded height always wins.** Only points with no elevation are filled, so importing a
 *   GPX that carries its own barometric profile never has that profile overwritten by a DEM.
 * - **The work is bounded.** The finest zoom whose tiles fit under a cap is chosen, and a route
 *   too long even for the coarsest is left alone rather than allowed to fetch a thousand tiles.
 *
 * Implementations of [DemTileSource] are expected to cache, so a route planned once is
 * re-planned without touching the network — which is what makes this survive a flaky signal
 * on the hill it is being used on.
 */
class TrackElevationFiller(
  private val source: DemTileSource,
  private val maximumZoom: Int = DemSampler.DEFAULT_ZOOM,
  private val minimumZoom: Int = MINIMUM_ZOOM,
  private val maximumTiles: Int = DEFAULT_MAXIMUM_TILES,
) {

  /** Fills every point that has no elevation, or returns null when it cannot or need not. */
  fun fill(track: Track): ElevationFill? {
    val missing = track.segments.flatMap { segment ->
      segment.points.filter { it.elevationMeters == null }.map { it.position }
    }
    if (missing.isEmpty()) return null

    val zoom = zoomFor(missing) ?: return null
    val sampler = DemSampler(source, zoom)
    val tiles = SlippyTile.tilesCovering(missing, zoom).size

    var pointsFilled = 0
    val segments = track.segments.map { segment ->
      TrackSegment(
        segment.points.map { point ->
          if (point.elevationMeters != null) {
            point
          } else {
            val elevation = sampler.elevationAt(point.position)
            if (elevation == null) {
              point
            } else {
              pointsFilled++
              point.copy(elevationMeters = elevation)
            }
          }
        },
      )
    }

    if (pointsFilled == 0) return null
    return ElevationFill(Track(segments), zoom, tiles, pointsFilled)
  }

  /**
   * The finest zoom whose coverage fits the tile budget, or null when none does.
   *
   * Stepping down rather than giving up matters for a long route: at half the zoom a tile
   * covers four times the ground, so a hundred-kilometre line that cannot be served at trail
   * resolution still gets a profile — a coarser one, and the caller is told which.
   */
  private fun zoomFor(positions: List<LatLng>): Int? {
    for (zoom in maximumZoom downTo minimumZoom) {
      if (SlippyTile.tilesCovering(positions, zoom).size <= maximumTiles) return zoom
    }
    return null
  }

  companion object {
    /**
     * The coarsest zoom worth sampling at.
     *
     * Below this a pixel is more than a few hundred metres of ground and the resulting profile
     * stops describing the walk — it describes the mountain range.
     */
    const val MINIMUM_ZOOM = 8

    /** Tiles a single fill may fetch. Enough for a long day out, few enough to be polite. */
    const val DEFAULT_MAXIMUM_TILES = 64
  }
}
