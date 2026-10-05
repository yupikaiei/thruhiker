package com.thruhiker.ui.flyover

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where pressing play begins a flight.
 *
 * The caller applies the frame at this offset before playback starts, which is what resets
 * the drawn route. A first play must reset it: the map is still showing the imported
 * whole-route preview while the intro deliberately reveals nothing, so without the reset
 * the full route sits on screen for the whole intro and then snaps away when travel begins.
 */
class FlyoverPlaybackStartTest {

  private val duration = 16_000L

  @Test
  fun `a first play starts at the top`() {
    assertEquals(
      0L,
      playbackStartMillis(finished = false, flightMillis = 0L, durationMillis = duration),
    )
  }

  @Test
  fun `a paused flight resumes where it stopped`() {
    assertEquals(
      7_250L,
      playbackStartMillis(finished = false, flightMillis = 7_250L, durationMillis = duration),
    )
  }

  @Test
  fun `a finished flight restarts from the top`() {
    assertEquals(
      0L,
      playbackStartMillis(finished = true, flightMillis = 7_250L, durationMillis = duration),
    )
  }

  @Test
  fun `a flight left at the end restarts from the top`() {
    assertEquals(
      0L,
      playbackStartMillis(finished = false, flightMillis = duration, durationMillis = duration),
    )
  }
}
