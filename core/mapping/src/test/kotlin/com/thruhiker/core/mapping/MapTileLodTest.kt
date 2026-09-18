package com.thruhiker.core.mapping

import com.thruhiker.core.model.CameraOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Guards the tile level-of-detail setting.
 *
 * MapLibre reduces the tile LOD away from the camera viewpoint once the map is pitched
 * past `tileLodPitchThreshold`, and the SDK default for that threshold is 60 degrees.
 * [CameraOptions.CINEMATIC_TILT] is 60 degrees as well, so a map that leaves the default
 * in place renders most of the screen from coarser tiles: the basemap goes soft and the
 * hillshade loses the relief it exists to show. These tests keep the threshold at the
 * value the MapLibre API documents as "LOD calculation is never performed" and keep it
 * above every pitch the camera can reach.
 */
class MapTileLodTest {

  @Test
  fun `tile lod reduction is disabled rather than made conditional`() {
    assertEquals(PI, TILE_LOD_PITCH_THRESHOLD_RADIANS, 1e-9)
  }

  @Test
  fun `the cinematic tilt stays well below the lod threshold`() {
    assertTrue(
      "the camera tilts to ${CameraOptions.CINEMATIC_TILT} degrees, so a " +
        "${Math.toDegrees(TILE_LOD_PITCH_THRESHOLD_RADIANS)} degree LOD threshold would " +
        "coarsen most of the screen",
      CameraOptions.CINEMATIC_TILT < Math.toDegrees(TILE_LOD_PITCH_THRESHOLD_RADIANS),
    )
  }
}
