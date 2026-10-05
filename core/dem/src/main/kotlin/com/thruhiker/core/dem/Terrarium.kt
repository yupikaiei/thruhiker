package com.thruhiker.core.dem

/**
 * Decoding for the Terrarium elevation tiles this app already renders as hillshade.
 *
 * Terrarium packs a height into the three channels of a PNG pixel — the red channel is the
 * high byte, green the middle, blue the fraction. Reading it back is arithmetic, which is the
 * whole reason the format is worth using: no decoder, no metadata, no server.
 *
 * The offset of 32768 is what makes the encoding signed, so the same tiles cover the Dead Sea
 * and Everest.
 */
object Terrarium {

  /** The value a tile uses where it has no data at all, including most open ocean. */
  const val NO_DATA_METERS = -32768.0

  /**
   * Heights below this are treated as "no data" rather than as ground.
   *
   * The deepest land on Earth is about -430 m, so anything far below that is a hole in the
   * dataset. Passing it on as an elevation would put a genuine -32,768 m into an elevation
   * profile and produce an ascent figure in the hundreds of thousands of metres.
   */
  const val MINIMUM_PLAUSIBLE_METERS = -500.0

  /** The height, in metres, encoded by one pixel's channels. */
  fun elevationMeters(red: Int, green: Int, blue: Int): Double =
    (red * 256.0 + green + blue / 256.0) - 32768.0

  /** The height encoded by a packed ARGB pixel. */
  fun elevationMeters(argb: Int): Double = elevationMeters(
    red = (argb shr 16) and 0xFF,
    green = (argb shr 8) and 0xFF,
    blue = argb and 0xFF,
  )

  /** True when a decoded height is real ground rather than a hole in the dataset. */
  fun isPlausible(meters: Double): Boolean =
    meters.isFinite() && meters > MINIMUM_PLAUSIBLE_METERS && meters < MAXIMUM_PLAUSIBLE_METERS

  /** Well above Everest, so anything past it is also a decoding mistake. */
  private const val MAXIMUM_PLAUSIBLE_METERS = 9_000.0
}
