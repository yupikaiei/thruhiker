package com.thruhiker.core.dem

import com.thruhiker.core.model.LatLng

/** Packs a height the way a Terrarium tile would. */
internal fun terrariumPixel(meters: Double): Int {
  val value = meters + 32768.0
  val red = (value / 256.0).toInt()
  val green = (value - red * 256.0).toInt()
  val blue = ((value - red * 256.0 - green) * 256.0).toInt().coerceIn(0, 255)
  return (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue
}

/** A tile source that answers with a surface described by a function of position. */
internal class SyntheticDem(
  private val heightAt: (LatLng) -> Double,
) : DemTileSource {

  var tilesServed = 0
    private set

  override fun tile(tile: TileCoordinate): IntArray? {
    tilesServed++
    val pixels = IntArray(SlippyTile.TILE_SIZE * SlippyTile.TILE_SIZE)
    for (y in 0 until SlippyTile.TILE_SIZE) {
      for (x in 0 until SlippyTile.TILE_SIZE) {
        val position = SlippyTile.positionFor(
          tile,
          TilePixel(x + 0.5, y + 0.5),
        )
        pixels[y * SlippyTile.TILE_SIZE + x] = terrariumPixel(heightAt(position))
      }
    }
    return pixels
  }
}

/** A tile source that always answers with the same array, or with nothing. */
internal class FixedDem(private val pixels: IntArray?) : DemTileSource {
  var tilesServed = 0
    private set

  override fun tile(tile: TileCoordinate): IntArray? {
    tilesServed++
    return pixels
  }
}

/** A synthetic surface reachable only where [available] says so — a phone with no signal. */
internal class PartialDem(
  private val heightAt: (LatLng) -> Double,
  private val available: (TileCoordinate) -> Boolean,
) : DemTileSource {

  private val delegate = SyntheticDem(heightAt)

  override fun tile(tile: TileCoordinate): IntArray? =
    if (available(tile)) delegate.tile(tile) else null
}

/** A tile of 256² pixels, all at [baseMeters], with individual pixels overridden. */
internal fun flatTile(baseMeters: Double, overrides: Map<Pair<Int, Int>, Double> = emptyMap()): IntArray {
  val pixels = IntArray(SlippyTile.TILE_SIZE * SlippyTile.TILE_SIZE) { terrariumPixel(baseMeters) }
  for ((coordinate, meters) in overrides) {
    val (x, y) = coordinate
    pixels[y * SlippyTile.TILE_SIZE + x] = terrariumPixel(meters)
  }
  return pixels
}

/** A straight run of points across [spanDegrees] of longitude, to exercise the tile budget. */
internal fun spreadPositions(count: Int, spanDegrees: Double, latitude: Double = 46.0): List<LatLng> =
  (0 until count).map { index ->
    LatLng(latitude, -spanDegrees / 2 + spanDegrees * index / (count - 1).coerceAtLeast(1))
  }
