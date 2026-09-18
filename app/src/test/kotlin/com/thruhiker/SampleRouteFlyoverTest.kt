package com.thruhiker

import com.thruhiker.core.flyover.FlyoverOptions
import com.thruhiker.core.flyover.FlyoverRig
import com.thruhiker.core.gpx.GpxParser
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample route is the one the README tells people to open, and it contains what a real
 * day out contains: a hairpin, and a break in the recording. It is measured frame by frame
 * here because a camera fault over ground like that is a visible glitch, and without a
 * device the frame to frame numbers are the only way to see one.
 *
 * A camera that moves a fixed distance per second should never move much more than that
 * between two frames, whatever the route does underneath it.
 */
class SampleRouteFlyoverTest {

  private val frameMillis = 1000L / 60L

  private fun sampleTrack(): Track {
    val file = File("../samples/chamonix-test-loop.gpx")
    check(file.exists()) { "sample route missing at ${file.absolutePath}" }
    return file.inputStream().use { GpxParser.parse(it) }.track
  }

  private fun sampleRig(): FlyoverRig =
    FlyoverRig.of(sampleTrack()) ?: error("no rig for the sample route")

  @Test
  fun `the sample route flies without the camera jumping`() {
    val rig = sampleRig()

    var previous = rig.frameAt(0L)
    var worstTarget = 0.0
    var worstBearing = 0.0
    var elapsed = frameMillis
    while (elapsed <= rig.durationMillis) {
      val frame = rig.frameAt(elapsed)
      worstTarget = maxOf(worstTarget, distanceMeters(previous.camera.target, frame.camera.target))
      worstBearing = maxOf(worstBearing, abs(turnDegrees(previous.camera.bearing, frame.camera.bearing)))
      previous = frame
      elapsed += frameMillis
    }

    // Roughly twenty metres a frame at the configured pace. The route's break in recording
    // is nine hundred metres: covering that between two frames is a teleport, and a heading
    // taken across the hairpin instead of along the route swings through most of the compass.
    assertTrue("camera target jumped $worstTarget m in a frame", worstTarget < 30.0)
    assertTrue("bearing whipped $worstBearing deg in a frame", worstBearing < 4.0)
  }

  @Test
  fun `the reveal keeps up with the camera and stops at the gap`() {
    val rig = sampleRig()
    val options = FlyoverOptions()

    var previousReveal = -1.0
    var previousWalked = Double.NaN
    var heldFrames = 0
    var elapsed = options.introMillis
    while (elapsed < options.introMillis + rig.travelMillis) {
      val frame = rig.frameAt(elapsed)
      assertTrue(
        "reveal went backwards at $elapsed ms: ${frame.revealedFraction}",
        frame.revealedFraction >= previousReveal - 1e-9,
      )
      if (!previousWalked.isNaN() && abs(frame.distanceAlongTrackMeters - previousWalked) < 1e-9) {
        heldFrames++
      }
      previousReveal = frame.revealedFraction
      previousWalked = frame.distanceAlongTrackMeters
      elapsed += frameMillis
    }

    assertEquals(0.0, rig.frameAt(0L).revealedFraction, 1e-9)
    assertEquals(1.0, rig.frameAt(rig.durationMillis).revealedFraction, 1e-9)
    assertTrue(
      "the mileage should stand still while the camera flies the gap, held for $heldFrames frames",
      heldFrames > 10,
    )
  }

  private fun turnDegrees(from: Double, to: Double): Double {
    var delta = (to - from) % 360.0
    if (delta > 180.0) delta -= 360.0
    if (delta <= -180.0) delta += 360.0
    return delta
  }

  private fun distanceMeters(from: LatLng, to: LatLng): Double {
    val earthRadius = 6_371_008.8
    val fromLat = Math.toRadians(from.latitude)
    val toLat = Math.toRadians(to.latitude)
    val deltaLat = toLat - fromLat
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(deltaLat / 2).let { it * it } +
      cos(fromLat) * cos(toLat) * sin(deltaLon / 2).let { it * it }
    return 2 * earthRadius * asin(min(1.0, sqrt(a)))
  }
}
