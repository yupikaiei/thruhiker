package com.thruhiker.core.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the difference between the two ways MapLibre can be handed a style.
 *
 * `MapLibreMap.setStyle(String)` is the *URL* overload — internally it builds a
 * `Style.Builder` with `fromUri` — so a style document passed there is fetched as
 * if it were an address. That fetch fails, no style is installed, and the map
 * renders nothing at all. It is a silent failure: the style factory's own tests
 * keep passing, and the app has no error to show, so the only symptom is a blank
 * map with no tiles.
 */
class MapControllerStyleTest {

  private val styleJson = """{"version":8,"sources":{},"layers":[]}"""

  @Test
  fun `a style document is carried as json rather than as a url`() {
    val builder = styleBuilderFor(styleJson)

    assertEquals(styleJson, builder.json)
    assertTrue(
      "a style document must not be sent down the URL path",
      builder.uri.isNullOrEmpty(),
    )
  }
}
