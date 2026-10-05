package com.thruhiker.core.dem

import com.thruhiker.core.model.LatLng
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** One tile in the web-mercator pyramid that every raster tile server uses. */
data class TileCoordinate(val zoom: Int, val x: Int, val y: Int)

/** A position inside a tile, in pixels, each in `[0, tileSize)`. */
data class TilePixel(val x: Double, val y: Double)

/**
 * Latitude/longitude to web-mercator tile and pixel, and back.
 *
 * A DEM tile is addressed by the same scheme as any other map tile, so knowing which tile a
 * point falls in — and where inside it — is the whole of looking an elevation up.
 *
 * Mercator cannot represent the poles; it runs off to infinity, so latitudes are clamped to
 * the standard bound. Nothing a hiker walks on is near it.
 *
 * This is kept pure and free of any HTTP or image decoding so that it can be tested against
 * known tile numbers on the JVM, which is where the mistakes in this kind of arithmetic hide.
 */
object SlippyTile {

  const val TILE_SIZE = 256

  /** The latitude where mercator runs off to infinity, and the usual cut-off. */
  const val MAXIMUM_LATITUDE = 85.051_128_779_806_59

  /** Tiles along one edge at [zoom]. */
  fun tilesPerAxis(zoom: Int): Int {
    require(zoom in 0..MAXIMUM_ZOOM) { "Zoom out of range: $zoom" }
    return 1 shl zoom
  }

  /** The tile a position falls in at [zoom]. */
  fun tileFor(position: LatLng, zoom: Int): TileCoordinate {
    require(position.isValid) { "Invalid position: $position" }
    val (x, y) = scaledCoordinates(position, zoom)
    return TileCoordinate(zoom, floor(x).toInt(), floor(y).toInt())
  }

  /** Where inside its tile a position falls, in pixels. */
  fun pixelWithin(position: LatLng, tile: TileCoordinate): TilePixel {
    require(position.isValid) { "Invalid position: $position" }
    val (x, y) = scaledCoordinates(position, tile.zoom)
    return TilePixel(
      x = (x - tile.x) * TILE_SIZE,
      y = (y - tile.y) * TILE_SIZE,
    )
  }

  /** The position at a pixel inside a tile. The inverse of [tileFor] plus [pixelWithin]. */
  fun positionFor(tile: TileCoordinate, pixel: TilePixel): LatLng {
    val axis = tilesPerAxis(tile.zoom).toDouble()
    val x = (tile.x + pixel.x / TILE_SIZE) / axis
    val y = (tile.y + pixel.y / TILE_SIZE) / axis

    val longitude = x * 360.0 - 180.0
    val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))
    return LatLng(latitude, longitude)
  }

  /** Every distinct tile a set of positions touches at [zoom]. */
  fun tilesCovering(positions: List<LatLng>, zoom: Int): Set<TileCoordinate> {
    val tiles = LinkedHashSet<TileCoordinate>()
    for (position in positions) {
      if (position.isValid) tiles.add(tileFor(position, zoom))
    }
    return tiles
  }

  /**
   * Fractional tile coordinates: the integer part is the tile, the fraction the position
   * inside it scaled to `[0, 1)`.
   */
  private fun scaledCoordinates(position: LatLng, zoom: Int): Pair<Double, Double> {
    val axis = tilesPerAxis(zoom).toDouble()

    val x = (position.longitude + 180.0) / 360.0 * axis

    val latitude = Math.toRadians(position.latitude.coerceIn(-MAXIMUM_LATITUDE, MAXIMUM_LATITUDE))
    val y = (1.0 - ln(tan(latitude) + 1.0 / cos(latitude)) / PI) / 2.0 * axis

    return x to y
  }

  /** Tiles beyond this are not served by any of the DEM sources this app can reach. */
  const val MAXIMUM_ZOOM = 22
}
