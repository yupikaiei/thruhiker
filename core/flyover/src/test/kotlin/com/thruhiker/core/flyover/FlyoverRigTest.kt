package com.thruhiker.core.flyover

import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.geo.TrackSampler
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FlyoverRigTest {

  private val options = FlyoverOptions()

  private fun point(latitude: Double, longitude: Double, elevation: Double? = null) =
    TrackPoint(LatLng(latitude, longitude), elevationMeters = elevation)

  /** A due-north line, so bearings and latitudes are predictable. */
  private fun northTrack(count: Int, spacingMeters: Double): Track {
    val degreesPerMetre = 1.0 / 111_132.0
    return Track.of(
      (0 until count).map { index ->
        point(46.0 + index * spacingMeters * degreesPerMetre, 7.0, 1000.0 + index * 5.0)
      },
    )
  }

  private fun rig(track: Track, configure: FlyoverOptions = options): FlyoverRig =
    FlyoverRig.of(track, configure)!!

  @Test
  fun `an empty track has no rig`() {
    assertNull(FlyoverRig.of(Track.Empty))
  }

  @Test
  fun `travel duration is clamped at both ends`() {
    val short = rig(northTrack(count = 3, spacingMeters = 500.0))
    assertEquals(options.minimumTravelMillis, short.travelMillis)

    // 400 km at 0.8 s/km would be 320 s, well past the ceiling.
    val long = rig(northTrack(count = 5, spacingMeters = 100_000.0))
    assertEquals(options.maximumTravelMillis, long.travelMillis)
  }

  @Test
  fun `duration is the sum of the three phases`() {
    val rig = rig(northTrack(count = 10, spacingMeters = 1_000.0))
    assertEquals(
      options.introMillis + rig.travelMillis + options.outroMillis,
      rig.durationMillis,
    )
  }

  @Test
  fun `the flight opens on the trailhead with nothing revealed`() {
    val track = northTrack(count = 10, spacingMeters = 1_000.0)
    val rig = rig(track)
    val frame = rig.frameAt(0L)

    assertEquals(FlyoverPhase.Intro, frame.phase)
    assertEquals(0.0, frame.revealedFraction, 0.0)
    assertEquals(0.0, frame.distanceAlongTrackMeters, 0.0)
    assertEquals(46.0, frame.position.latitude, 1e-9)
    assertEquals(1000.0, frame.elevationMeters!!, 1e-6)
  }

  @Test
  fun `the flight closes on the finish with everything revealed`() {
    val track = northTrack(count = 10, spacingMeters = 1_000.0)
    val rig = rig(track)
    val frame = rig.frameAt(rig.durationMillis)
    val sampler = TrackSampler.of(track)!!

    assertEquals(FlyoverPhase.Outro, frame.phase)
    assertEquals(1.0, frame.revealedFraction, 1e-9)
    assertEquals(sampler.totalDistanceMeters, frame.distanceAlongTrackMeters, 1e-6)
  }

  @Test
  fun `elapsed time outside the flight is clamped`() {
    val rig = rig(northTrack(count = 10, spacingMeters = 1_000.0))

    assertEquals(rig.frameAt(0L), rig.frameAt(-60_000L))
    assertEquals(rig.frameAt(rig.durationMillis), rig.frameAt(rig.durationMillis + 60_000L))
  }

  @Test
  fun `the reveal never goes backwards`() {
    val rig = rig(northTrack(count = 40, spacingMeters = 500.0))

    var previous = -1.0
    var elapsed = 0L
    while (elapsed <= rig.durationMillis) {
      val fraction = rig.frameAt(elapsed).revealedFraction
      assertTrue("reveal went backwards at $elapsed ms", fraction >= previous - 1e-9)
      previous = fraction
      elapsed += 50L
    }

    assertEquals(1.0, previous, 1e-9)
  }

  @Test
  fun `distance along the route never exceeds its length`() {
    val track = northTrack(count = 40, spacingMeters = 500.0)
    val rig = rig(track)
    val total = TrackSampler.of(track)!!.totalDistanceMeters

    var elapsed = 0L
    while (elapsed <= rig.durationMillis) {
      assertTrue(rig.frameAt(elapsed).distanceAlongTrackMeters <= total + 1e-6)
      elapsed += 100L
    }
  }

  @Test
  fun `progress runs from zero to one`() {
    val rig = rig(northTrack(count = 10, spacingMeters = 1_000.0))

    assertEquals(0.0, rig.frameAt(0L).progress, 1e-9)
    assertEquals(1.0, rig.frameAt(rig.durationMillis).progress, 1e-9)
    assertTrue(rig.frameAt(rig.durationMillis / 2).progress in 0.4..0.6)
  }

  @Test
  fun `the phases occur in order`() {
    val rig = rig(northTrack(count = 20, spacingMeters = 1_000.0))

    assertEquals(FlyoverPhase.Intro, rig.frameAt(0L).phase)
    assertEquals(FlyoverPhase.Intro, rig.frameAt(options.introMillis - 1).phase)
    assertEquals(FlyoverPhase.Travel, rig.frameAt(options.introMillis).phase)
    assertEquals(
      FlyoverPhase.Travel,
      rig.frameAt(options.introMillis + rig.travelMillis - 1).phase,
    )
    assertEquals(FlyoverPhase.Outro, rig.frameAt(options.introMillis + rig.travelMillis).phase)
    assertEquals(FlyoverPhase.Outro, rig.frameAt(rig.durationMillis).phase)
  }

  /**
   * The camera looks ahead of the walker rather than at them, which is what makes
   * the shot show what is coming instead of what has already gone past.
   */
  @Test
  fun `the travelling camera looks a fixed distance ahead`() {
    val track = northTrack(count = 40, spacingMeters = 500.0)
    val rig = rig(track)
    val sampler = TrackSampler.of(track)!!
    val frame = rig.frameAt(options.introMillis + rig.travelMillis / 2)

    val here = sampler.sampleAt(frame.distanceAlongTrackMeters)
    val ahead = Geodesic.distanceMeters(here.position, frame.camera.target)

    assertEquals(options.lookAheadMeters, ahead, 5.0)
  }

  @Test
  fun `the travelling camera is offset from the direction of travel`() {
    val rig = rig(northTrack(count = 40, spacingMeters = 500.0))
    val frame = rig.frameAt(options.introMillis + rig.travelMillis / 2)

    // Heading due north, plus the yaw offset.
    assertEquals(options.bearingOffsetDegrees, frame.camera.bearing, 1.0)
  }

  @Test
  fun `travel zoom and tilt are the configured ones`() {
    val rig = rig(northTrack(count = 40, spacingMeters = 500.0))
    val frame = rig.frameAt(options.introMillis + rig.travelMillis / 2)

    assertEquals(options.travelZoom, frame.camera.zoom, 1e-9)
    assertEquals(options.travelTilt, frame.camera.tilt, 1e-9)
  }

  @Test
  fun `the intro sweeps in from wide and the outro pulls back out`() {
    val rig = rig(northTrack(count = 40, spacingMeters = 500.0))

    val opening = rig.frameAt(0L)
    val travelling = rig.frameAt(options.introMillis + rig.travelMillis / 2)
    val closing = rig.frameAt(rig.durationMillis)

    assertTrue("intro should start wider than it finishes", opening.camera.zoom < travelling.camera.zoom)
    assertTrue("outro should pull back out", closing.camera.zoom < travelling.camera.zoom)
    assertTrue("outro should flatten the pitch", closing.camera.tilt < travelling.camera.tilt)
  }

  @Test
  fun `the camera target advances along the route through travel`() {
    val rig = rig(northTrack(count = 40, spacingMeters = 500.0))

    var previousLatitude = -90.0
    var elapsed = options.introMillis
    while (elapsed < options.introMillis + rig.travelMillis) {
      val latitude = rig.frameAt(elapsed).camera.target.latitude
      assertTrue("camera target went backwards at $elapsed ms", latitude >= previousLatitude - 1e-9)
      previousLatitude = latitude
      elapsed += 100L
    }
  }

  @Test
  fun `tilt stays within sane bounds throughout`() {
    val rig = rig(northTrack(count = 40, spacingMeters = 500.0))

    var elapsed = 0L
    while (elapsed <= rig.durationMillis) {
      val tilt = rig.frameAt(elapsed).camera.tilt
      assertTrue("tilt out of range at $elapsed ms: $tilt", tilt in 0.0..85.0)
      elapsed += 50L
    }
  }

  @Test
  fun `bearing stays normalised throughout`() {
    val rig = rig(northTrack(count = 40, spacingMeters = 500.0))

    var elapsed = 0L
    while (elapsed <= rig.durationMillis) {
      val bearing = rig.frameAt(elapsed).camera.bearing
      assertTrue("bearing out of range at $elapsed ms: $bearing", bearing >= 0.0 && bearing < 360.0)
      elapsed += 50L
    }
  }

  @Test
  fun `a single point route produces a still flight rather than failing`() {
    val rig = rig(Track.of(listOf(point(46.0, 7.0, 1000.0))))

    assertEquals(options.minimumTravelMillis, rig.travelMillis)
    val frame = rig.frameAt(rig.durationMillis / 2)
    assertEquals(46.0, frame.position.latitude, 1e-9)
    assertEquals(46.0, frame.camera.target.latitude, 1e-9)
  }

  @Test
  fun `an invalid configuration is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      FlyoverOptions(minimumTravelMillis = 0L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      FlyoverOptions(minimumTravelMillis = 10_000L, maximumTravelMillis = 5_000L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      FlyoverOptions(lookAheadMeters = -1.0)
    }
  }

  @Test
  fun `angle interpolation takes the short way round`() {
    // 350 to 10 is twenty degrees forward, through north. Going the other way
    // round the compass would be three hundred and forty, and would spin the
    // camera the long way between two almost adjacent headings.
    assertEquals(350.0, lerpAngle(350.0, 10.0, 0.0), 1e-9)
    assertEquals(355.0, lerpAngle(350.0, 10.0, 0.25), 1e-9)
    assertEquals(0.0, lerpAngle(350.0, 10.0, 0.5), 1e-9)
    assertEquals(5.0, lerpAngle(350.0, 10.0, 0.75), 1e-9)
    assertEquals(10.0, lerpAngle(350.0, 10.0, 1.0), 1e-9)
  }

  @Test
  fun `shortest angle delta is signed and bounded`() {
    assertEquals(20.0, shortestDeltaDegrees(350.0, 10.0), 1e-9)
    assertEquals(-20.0, shortestDeltaDegrees(10.0, 350.0), 1e-9)
    assertEquals(180.0, shortestDeltaDegrees(0.0, 180.0), 1e-9)
    assertEquals(90.0, shortestDeltaDegrees(0.0, 450.0), 1e-9)
  }
}
