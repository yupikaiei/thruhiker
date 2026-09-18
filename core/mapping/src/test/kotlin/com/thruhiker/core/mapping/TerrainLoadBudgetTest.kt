package com.thruhiker.core.mapping

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.android.maps.MapLibreMap

/**
 * The app is built against two SDKs: the published one, which has no 3D terrain, and the
 * vendored terrain build, which does. The load budget is applied reflectively for that
 * reason, so the lookup is pinned here to whatever SDK is actually on the classpath. Without
 * it, renaming the setting upstream would quietly turn the tuning into a no-op.
 */
class TerrainLoadBudgetTest {

  @Test
  fun `the terrain load budget matches the sdk on the classpath`() {
    val modeClass = runCatching { Class.forName(TERRAIN_LOAD_MODE_CLASS) }.getOrNull()
    val setter = modeClass?.let { type ->
      runCatching { MapLibreMap::class.java.getMethod(TERRAIN_LOAD_MODE_SETTER, type) }.getOrNull()
    }

    if (modeClass == null) {
      // Published SDK: no terrain to tune, and the lookup has to stay a no-op.
      assertNull(setter)
      return
    }

    assertNotNull("the terrain SDK must expose $TERRAIN_LOAD_MODE_SETTER", setter)
    assertNotNull(
      "the terrain SDK must have a $TERRAIN_LOAD_MODE_BALANCED mode",
      modeClass.enumConstants?.firstOrNull { constant ->
        (constant as Enum<*>).name == TERRAIN_LOAD_MODE_BALANCED
      },
    )
  }
}
