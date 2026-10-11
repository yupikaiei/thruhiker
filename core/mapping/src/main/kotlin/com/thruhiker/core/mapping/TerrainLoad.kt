package com.thruhiker.core.mapping

/**
 * How much 3D-terrain building one frame is allowed to schedule.
 *
 * Not a style property: it changes how the loading work is spread across frames and never the
 * image that ends up on screen, so choosing is a question of where a hitch is least
 * objectionable rather than of what the map looks like. Terrain drapes are built when tiles
 * arrive, and a moving camera makes tiles arrive constantly — which is why a screen that
 * animates wants the tighter budget and a screen you pan by hand does not.
 *
 * The names are the SDK's own enum constant names, looked up reflectively because the app also
 * builds against the published MapLibre SDK, which has no terrain at all. Renaming one here
 * turns the tuning into a silent no-op, which `TerrainLoadBudgetTest` is there to catch.
 */
enum class TerrainLoad(internal val sdkName: String) {
  /** No budget: everything builds the frame it arrives. The SDK default, and the sharpest. */
  QUALITY("QUALITY"),

  /** 32 tile builds and 16 drape re-renders per frame. A middle ground, and the default here. */
  BALANCED("BALANCED"),

  /**
   * 8 tile builds and 4 drape re-renders per frame: the smoothest the SDK offers, at the cost
   * of the most visible fill-in.
   *
   * This is what the flyover asks for. Its camera never stops moving, so terrain arrives in a
   * fresh burst every second, and a frame that stalls to accept one reads as a stutter rather
   * than as detail — detail that lands two frames later is invisible at a kilometre a second.
   */
  PERFORMANCE("PERFORMANCE"),
}
