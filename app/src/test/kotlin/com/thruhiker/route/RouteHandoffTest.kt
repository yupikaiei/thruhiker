package com.thruhiker.route

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteHandoffTest {

  private val track = Track.of(
    listOf(
      TrackPoint(LatLng(46.0, 7.0)),
      TrackPoint(LatLng(46.01, 7.0)),
    ),
  )

  @Test
  fun `a route is handed over and consumed exactly once`() {
    RouteHandoff.offer(track, "Col des Fours")

    assertEquals(track, RouteHandoff.take())
    assertEquals("Col des Fours", RouteHandoff.takeName())
    assertNull(RouteHandoff.take())
    assertNull(RouteHandoff.takeName())
  }

  @Test
  fun `nothing waiting means nothing taken`() {
    RouteHandoff.take()
    RouteHandoff.takeName()

    assertNull(RouteHandoff.take())
    assertNull(RouteHandoff.takeName())
  }

  @Test
  fun `a route with no name is handed over unnamed`() {
    RouteHandoff.offer(track, null)

    assertEquals(track, RouteHandoff.take())
    assertNull(RouteHandoff.takeName())
  }
}
