package com.thruhiker.ui.flyover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.thruhiker.core.designsystem.theme.ThruHikerTheme
import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.mapping.CameraOptions
import com.thruhiker.core.mapping.ThruHikerMap
import com.thruhiker.core.model.LatLng
import java.util.Locale

/**
 * Mont Blanc massif: the reference view for terrain work.
 *
 * It is a good test case because it has everything at once: a deep valley, a
 * glaciated summit and a 4,800 m relief range within one screen at this zoom, so
 * terrain that looks right here will look right anywhere.
 */
private val MONT_BLANC = LatLng(45.8326, 6.8652)

/**
 * The 3D cinematic screen.
 *
 * Today this renders live terrain at the cinematic camera tilt. The camera rig,
 * the progressive trail reveal and video export land in the flyover phase.
 */
@Composable
fun FlyoverScreen(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize()) {
    ThruHikerMap(
      initialCamera = CameraOptions(
        target = MONT_BLANC,
        zoom = 12.0,
        tilt = CameraOptions.CINEMATIC_TILT,
        bearing = 20.0,
      ),
      modifier = Modifier.fillMaxSize(),
    )

    FlyoverOverlay(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(12.dp),
    )
  }
}

/** Live figures from `core:geo`, plus the attribution the tile sources require. */
@Composable
private fun FlyoverOverlay(modifier: Modifier = Modifier) {
  // A straight line between the two termini of the Appalachian Trail, next to the
  // trail's real walking length. The ratio is the whole argument for a flyover:
  // the trail is nothing like the line you would draw on a map.
  val springerMountain = LatLng(34.6268, -84.1943)
  val mountKatahdin = LatLng(45.9044, -68.9216)
  val trailLengthMeters = 3_530_000.0

  val straightLineMeters = Geodesic.distanceMeters(springerMountain, mountKatahdin)

  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ),
  ) {
    Column(Modifier.padding(16.dp)) {
      Text(
        text = "Terrain live",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "Springer Mtn → Katahdin ${formatKilometers(straightLineMeters)} · " +
          "trail ${formatRatio(trailLengthMeters / straightLineMeters)} the flight line",
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = "Tiles: OpenFreeMap · Elevation: AWS Terrain Tiles / Mapzen",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun formatKilometers(meters: Double): String =
  String.format(Locale.US, "%,.0f km", meters / 1000.0)

private fun formatRatio(ratio: Double): String =
  String.format(Locale.US, "%.2f×", ratio)

@Preview(showBackground = true)
@Composable
private fun FlyoverOverlayPreview() {
  ThruHikerTheme {
    Box(Modifier.fillMaxSize()) { FlyoverOverlay() }
  }
}
