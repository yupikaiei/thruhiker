package com.thruhiker.core.dem

import com.thruhiker.core.model.LatLng
import kotlin.math.floor

/**
 * Reads a height off a DEM tile grid at any position.
 *
 * Heights are interpolated bilinearly between the four pixels around the point rather than
 * taken from the nearest one. At the zoom this app samples, a pixel is about thirteen metres
 * of ground; a route densified to twenty-five metre legs would therefore step between whole
 * pixels and come out as a staircase, and a staircase differentiated into a gradient is a row
 * of spikes. Smoothing with the neighbouring pixels is what makes the profile, and the
 * gradient colours drawn from it, look like terrain instead of like noise.
 */
class DemSampler(
  private val source: DemTileSource,
  val zoom: Int = DEFAULT_ZOOM,
) {

  /**
   * The height at [position], or null when the tile is unavailable or holds no data.
   *
   * A missing tile is a normal outcome, not an error: the app is allowed to be offline, and
   * positions over open ocean genuinely have no elevation.
   */
  fun elevationAt(position: LatLng): Double? {
    if (!position.isValid) return null

    val tile = SlippyTile.tileFor(position, zoom)
    val pixels = source.tile(tile) ?: return null
    if (pixels.size != SlippyTile.TILE_SIZE * SlippyTile.TILE_SIZE) return null

    return sampleBilinear(pixels, SlippyTile.pixelWithin(position, tile))
  }

  private fun sampleBilinear(pixels: IntArray, pixel: TilePixel): Double? {
    // Tile pixels are sampled at their centres, so the origin is half a pixel in.
    val fractionalX = pixel.x - 0.5
    val fractionalY = pixel.y - 0.5

    val x0 = floor(fractionalX).toInt()
    val y0 = floor(fractionalY).toInt()
    val mixX = fractionalX - x0
    val mixY = fractionalY - y0

    val topLeft = elevation(pixels, x0, y0) ?: return null
    val topRight = elevation(pixels, x0 + 1, y0) ?: return null
    val bottomLeft = elevation(pixels, x0, y0 + 1) ?: return null
    val bottomRight = elevation(pixels, x0 + 1, y0 + 1) ?: return null

    val top = topLeft + (topRight - topLeft) * mixX
    val bottom = bottomLeft + (bottomRight - bottomLeft) * mixX
    return top + (bottom - top) * mixY
  }

  /** One pixel's height, clamping at the tile edge so a border sample stays well defined. */
  private fun elevation(pixels: IntArray, x: Int, y: Int): Double? {
    val size = SlippyTile.TILE_SIZE
    val clampedX = x.coerceIn(0, size - 1)
    val clampedY = y.coerceIn(0, size - 1)
    val meters = Terrarium.elevationMeters(pixels[clampedY * size + clampedX])
    return if (Terrarium.isPlausible(meters)) meters else null
  }

  companion object {
    /**
     * Thirteen gives roughly thirteen metres per pixel at the latitudes this app is used at —
     * comfortably finer than the twenty-five metre legs the planner densifies to, and coarse
     * enough that a long route only spans a handful of tiles.
     */
    const val DEFAULT_ZOOM = 13
  }
}
