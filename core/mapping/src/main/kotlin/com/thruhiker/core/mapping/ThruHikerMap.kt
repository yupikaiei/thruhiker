package com.thruhiker.core.mapping

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thruhiker.core.model.CameraOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/**
 * A MapLibre map rendering real 3D terrain, optionally with a route drawn over it.
 *
 * The basemap style is fetched once and then decorated locally, so importing a
 * route does not mean hitting the network again. Terrain is a style-level rather
 * than imperative feature in MapLibre, so there is no "enable 3D" call:
 * [MapStyleFactory] injects the DEM source, the `terrain` property and the track
 * layers, and the renderer does the rest.
 *
 * Rebuilding the whole style to change the route is heavier than mutating a
 * source, but it happens only when the user imports a file, and it keeps the
 * route on the same code path as terrain, which is fully unit-tested.
 */
@Composable
fun ThruHikerMap(
  camera: CameraOptions,
  modifier: Modifier = Modifier,
  styleUrl: String = MapStyleFactory.OPEN_FREE_MAP_STYLE_URL,
  terrainExaggeration: Double = MapStyleFactory.DEFAULT_EXAGGERATION,
  trackGeoJson: String? = null,
  onMapReady: (MapController) -> Unit = {},
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  val mapView = remember(context) {
    MapLibre.getInstance(context)
    MapView(context).apply { onCreate(null) }
  }

  var controller by remember { mutableStateOf<MapController?>(null) }
  var baseStyleJson by remember(styleUrl) { mutableStateOf<String?>(null) }
  val requestState = remember { MapRequestState() }

  DisposableEffect(lifecycleOwner, mapView) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> mapView.onStart()
        Lifecycle.Event.ON_RESUME -> mapView.onResume()
        Lifecycle.Event.ON_PAUSE -> mapView.onPause()
        Lifecycle.Event.ON_STOP -> mapView.onStop()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      // Destroying here only. Handling ON_DESTROY in the observer as well would
      // tear the map down twice.
      mapView.onDestroy()
    }
  }

  LaunchedEffect(styleUrl) {
    baseStyleJson = withContext(Dispatchers.IO) {
      runCatching { MapStyleLoader.fetch(styleUrl) }.getOrNull()
    }
  }

  AndroidView(
    modifier = modifier,
    factory = { mapView },
    update = { view ->
      if (!requestState.requested) {
        requestState.requested = true
        view.getMapAsync { map ->
          val newController = MapController(map)
          controller = newController
          onMapReady(newController)
        }
      }
    },
  )

  // Style and camera are applied together: MapLibre preserves the camera across a
  // style change, so setting both here keeps framing deterministic instead of
  // racing two effects against each other.
  LaunchedEffect(controller, baseStyleJson, terrainExaggeration, trackGeoJson, camera) {
    val readyController = controller ?: return@LaunchedEffect
    val base = baseStyleJson ?: return@LaunchedEffect

    readyController.applyStyle(
      MapStyleFactory.withTerrainAndTrack(
        baseStyleJson = base,
        trackGeoJson = trackGeoJson,
        exaggeration = terrainExaggeration,
      ),
    )
    readyController.moveCamera(camera)
  }
}

/**
 * Plain guard flag.
 *
 * This deliberately avoids snapshot state: [AndroidView]'s `update` block runs
 * during layout, and writing snapshot state from there is not allowed.
 */
private class MapRequestState {
  var requested = false
}
