package com.thruhiker.core.dem

/**
 * Somewhere DEM tiles can be read from.
 *
 * Deliberately not an HTTP client. Fetching, decoding a PNG and caching it are platform
 * concerns with platform APIs, and keeping them behind this one method is what lets the
 * arithmetic — which is where the bugs are — be tested on the JVM with hand-built tiles.
 *
 * Implementations are called many times for the same tile while a route is walked, and are
 * expected to make the repeat cheap.
 */
interface DemTileSource {

  /**
   * The pixels of [tile] in ARGB, row-major, [SlippyTile.TILE_SIZE] squared, or null when the
   * tile cannot be read — no network, no cache, no such tile.
   *
   * Implementations are called off the main thread.
   */
  fun tile(tile: TileCoordinate): IntArray?
}
