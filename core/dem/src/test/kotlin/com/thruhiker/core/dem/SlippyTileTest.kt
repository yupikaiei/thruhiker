package com.thruhiker.core.dem

import com.thruhiker.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlippyTileTest {

  @Test
  fun `the origin is the centre of the world at every zoom`() {
    for (zoom in 1..14) {
      val half = SlippyTile.tilesPerAxis(zoom) / 2
      assertEquals(
        TileCoordinate(zoom, half, half),
        SlippyTile.tileFor(LatLng(0.0, 0.0), zoom),
      )
    }
  }

  @Test
  fun `tiles count along one edge doubles per zoom`() {
    assertEquals(1, SlippyTile.tilesPerAxis(0))
    assertEquals(256, SlippyTile.tilesPerAxis(8))
    assertEquals(8192, SlippyTile.tilesPerAxis(13))
  }

  @Test
  fun `north and west are lower tile numbers than south and east`() {
    val origin = SlippyTile.tileFor(LatLng(0.0, 0.0), 10)
    val north = SlippyTile.tileFor(LatLng(1.0, 0.0), 10)
    val south = SlippyTile.tileFor(LatLng(-1.0, 0.0), 10)
    val west = SlippyTile.tileFor(LatLng(0.0, -1.0), 10)
    val east = SlippyTile.tileFor(LatLng(0.0, 1.0), 10)

    assertTrue(north.y < origin.y)
    assertTrue(south.y > origin.y)
    assertTrue(west.x < origin.x)
    assertTrue(east.x > origin.x)
  }

  /**
   * The property that matters: a position, pushed through the forward maths and pulled back
   * out of the tile arithmetic, has to come back where it started. An off-by-one in the
   * layout, a swapped axis or a wrong origin all survive a hand-picked tile number and all
   * fail this.
   */
  @Test
  fun `tile and pixel round-trip back to the same position`() {
    val positions = listOf(
      LatLng(0.0, 0.0),
      LatLng(45.8326, 6.8652),
      LatLng(-33.8688, 151.2093),
      LatLng(64.1466, -21.9426),
      LatLng(-45.0312, 168.6626),
    )

    for (zoom in 8..15) {
      for (position in positions) {
        val tile = SlippyTile.tileFor(position, zoom)
        val pixel = SlippyTile.pixelWithin(position, tile)
        val restored = SlippyTile.positionFor(tile, pixel)

        assertEquals("latitude at z$zoom", position.latitude, restored.latitude, 1e-6)
        assertEquals("longitude at z$zoom", position.longitude, restored.longitude, 1e-6)
      }
    }
  }

  @Test
  fun `a pixel stays inside its own tile`() {
    val tile = SlippyTile.tileFor(LatLng(45.9, 6.9), 12)

    val pixel = SlippyTile.pixelWithin(LatLng(45.9, 6.9), tile)

    assertTrue(pixel.x in 0.0..<SlippyTile.TILE_SIZE.toDouble())
    assertTrue(pixel.y in 0.0..<SlippyTile.TILE_SIZE.toDouble())
  }

  @Test
  fun `coverage deduplicates and ignores invalid positions`() {
    val tiles = SlippyTile.tilesCovering(
      listOf(
        LatLng(45.8326, 6.8652),
        LatLng(45.8327, 6.8653),
        LatLng(45.90, 7.10),
        LatLng(Double.NaN, 7.0),
      ),
      zoom = 13,
    )

    assertEquals(2, tiles.size)
    assertTrue(tiles.all { it.zoom == 13 })
  }

  @Test
  fun `the poles are clamped rather than running off to infinity`() {
    val north = SlippyTile.tileFor(LatLng(89.9, 0.0), 8)
    val limit = SlippyTile.tileFor(LatLng(SlippyTile.MAXIMUM_LATITUDE, 0.0), 8)

    assertEquals(limit.y, north.y)
    assertTrue(north.y >= 0)
  }
}
