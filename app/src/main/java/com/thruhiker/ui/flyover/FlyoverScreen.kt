package com.thruhiker.ui.flyover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.thruhiker.core.designsystem.theme.ThruHikerTheme
import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.model.LatLng
import java.util.Locale

/**
 * Placeholder for the 3D cinematic screen.
 *
 * The MapLibre terrain view replaces this in the next increment. What is already
 * real is the geodesic engine underneath it, so the screen reports a live figure
 * computed by `core:geo` rather than a hard-coded string: the straight-line
 * distance between the two termini of the Appalachian Trail, next to the trail's
 * actual walking length.
 */
@Composable
fun FlyoverScreen(modifier: Modifier = Modifier) {
  val springerMountain = LatLng(34.6268, -84.1943)
  val mountKatahdin = LatLng(45.9044, -68.9216)

  val straightLineMeters = Geodesic.distanceMeters(springerMountain, mountKatahdin)
  val bearing = Geodesic.initialBearingDegrees(springerMountain, mountKatahdin)

  // Published Appalachian Trail length, used as a sanity reference: the whole
  // point of the flyover is that the trail is 60% longer than the straight line.
  val trailLengthMeters = 3_530_000.0
  val sinuosity = trailLengthMeters / straightLineMeters

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = "3D flyover",
      style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      text = "MapLibre terrain, the camera rig and the trail-reveal layer arrive " +
        "in the next increment.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(32.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(20.dp)) {
        Text(
          text = "core:geo is live",
          style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        StatRow("Springer Mtn → Katahdin", formatKilometers(straightLineMeters))
        StatRow("Initial bearing", formatDegrees(bearing))
        StatRow("Real AT length", formatKilometers(trailLengthMeters))
        StatRow("Trail-to-flight ratio", formatRatio(sinuosity))
      }
    }
  }
}

@Composable
private fun StatRow(label: String, value: String) {
  Column(Modifier.padding(vertical = 4.dp)) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = value, style = MaterialTheme.typography.titleLarge)
  }
}

private fun formatKilometers(meters: Double): String =
  String.format(Locale.US, "%,.0f km", meters / 1000.0)

private fun formatDegrees(degrees: Double): String =
  String.format(Locale.US, "%.1f°", degrees)

private fun formatRatio(ratio: Double): String =
  String.format(Locale.US, "%.2f×", ratio)

@Preview(showBackground = true)
@Composable
private fun FlyoverScreenPreview() {
  ThruHikerTheme { FlyoverScreen() }
}
