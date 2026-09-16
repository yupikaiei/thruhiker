package com.thruhiker.ui.flyover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.thruhiker.R
import com.thruhiker.core.geo.ToblerEstimator
import com.thruhiker.core.geo.TrackStats
import com.thruhiker.core.geo.TrackStatsCalculator
import com.thruhiker.core.gpx.GpxParser
import com.thruhiker.core.mapping.CameraOptions
import com.thruhiker.core.mapping.GeoJsonEncoder
import com.thruhiker.core.mapping.ThruHikerMap
import com.thruhiker.core.mapping.TrackFraming
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Mont Blanc massif: the reference view when no route is loaded.
 *
 * It is a good test case because it has everything at once: a deep valley, a
 * glaciated summit and a 4,800 m relief range within one screen at this zoom, so
 * terrain that looks right here will look right anywhere.
 */
private val MONT_BLANC = LatLng(45.8326, 6.8652)

private val DEFAULT_CAMERA = CameraOptions(
  target = MONT_BLANC,
  zoom = 12.0,
  tilt = CameraOptions.CINEMATIC_TILT,
  bearing = 20.0,
)

/** A route the user imported, with everything derived from it computed once, off the main thread. */
private data class ImportedRoute(
  val name: String?,
  val track: Track,
  val geoJson: String,
  val stats: TrackStats,
  val walkingSeconds: Double,
  val camera: CameraOptions,
)

/**
 * The 3D cinematic screen.
 *
 * Today: import a GPX file, see it drawn over real terrain, and see what the
 * maths in `core:geo` makes of it. The camera rig, the progressive reveal and
 * video export land next.
 */
@Composable
fun FlyoverScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var route by remember { mutableStateOf<ImportedRoute?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val unreadable = stringResource(R.string.route_error_unreadable)
  val noTrack = stringResource(R.string.route_error_no_track)

  val picker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult

    scope.launch {
      // Parsing, statistics, the walking-time estimate and the GeoJSON encoding all
      // happen off the main thread: a 100,000-point trail guide is a real file, not
      // a toy, and every one of those steps walks the whole track.
      val outcome: Result<ImportedRoute> = withContext(Dispatchers.IO) {
        runCatching {
          val document = context.contentResolver.openInputStream(uri)?.use { stream ->
            GpxParser.parse(stream)
          } ?: throw IllegalStateException(unreadable)

          // A waypoint-only file is valid GPX and useless here.
          require(!document.track.isEmpty) { noTrack }

          val track = document.track
          ImportedRoute(
            name = document.name,
            track = track,
            geoJson = GeoJsonEncoder.encode(track),
            stats = TrackStatsCalculator.compute(track),
            walkingSeconds = ToblerEstimator.hikingSeconds(track),
            camera = TrackFraming.cameraFor(track) ?: DEFAULT_CAMERA,
          )
        }
      }

      outcome
        .onSuccess { imported ->
          route = imported
          errorMessage = null
        }
        .onFailure { failure ->
          errorMessage = failure.message ?: unreadable
        }
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    ThruHikerMap(
      camera = route?.camera ?: DEFAULT_CAMERA,
      trackGeoJson = route?.geoJson,
      modifier = Modifier.fillMaxSize(),
    )

    RouteCard(
      name = route?.name,
      stats = route?.stats,
      walkingSeconds = route?.walkingSeconds,
      errorMessage = errorMessage,
      onImportClick = { picker.launch(GPX_MIME_TYPES) },
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(12.dp),
    )
  }
}

/**
 * GPX has no registered MIME type that every file provider agrees on, so the picker
 * is opened to everything and the contents are validated on read instead.
 */
private val GPX_MIME_TYPES = arrayOf("*/*")

@Composable
private fun RouteCard(
  name: String?,
  stats: TrackStats?,
  walkingSeconds: Double?,
  errorMessage: String?,
  onImportClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ),
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = name ?: stringResource(R.string.route_none_title),
          style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onImportClick) {
          Text(
            stringResource(
              if (stats == null) R.string.import_gpx else R.string.import_gpx_another,
            ),
          )
        }
      }

      if (errorMessage != null) {
        Text(
          text = errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      if (stats == null) {
        if (errorMessage == null) {
          Text(
            text = stringResource(R.string.route_none_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
          Statistic(stringResource(R.string.stats_distance), formatDistance(stats.distanceMeters))
          Statistic(stringResource(R.string.stats_ascent), formatElevation(stats.elevationGainMeters))
          Statistic(stringResource(R.string.stats_walking_time), formatDuration(walkingSeconds))
        }
        Text(
          text = stringResource(R.string.stats_walking_time_hint),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun Statistic(label: String, value: String) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = value, style = MaterialTheme.typography.titleMedium)
  }
}

private fun formatDistance(meters: Double): String =
  String.format(Locale.US, "%,.1f km", meters / 1000.0)

private fun formatElevation(meters: Double): String =
  String.format(Locale.US, "%,.0f m", meters)

private fun formatDuration(seconds: Double?): String {
  if (seconds == null || !seconds.isFinite()) return "—"
  val totalMinutes = (seconds / 60.0).toLong()
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
