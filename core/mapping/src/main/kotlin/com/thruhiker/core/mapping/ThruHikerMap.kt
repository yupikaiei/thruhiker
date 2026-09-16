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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/**
 * A MapLibre map rendering real 3D terrain.
 *
 * The style is fetched and decorated off the main thread, so a flat vector
 * basemap appears first and terrain snaps in a moment later. On a long trail that
 * ordering matters: it means a hiker standing on a pass with two bars of signal
 * still gets a usable map while the DEM tiles are still arriving.
 *
 * Terrain is a style-level rather than imperative feature in MapLibre, so there is
 * no "enable 3D" call: [MapStyleFactory] injects the DEM source and the `terrain`
 * property, and the renderer does the rest.
 */
@Composable
fun ThruHikerMap(
  initialCamera: CameraOptions,
  modifier: Modifier = Modifier,
  styleUrl: String = MapStyleFactory.OPEN_FREE_MAP_STYLE_URL,
  terrainExaggeration: Double = MapStyleFactory.DEFAULT_EXAGGERATION,
  onMapReady: (MapController) -> Unit = {},
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  val mapView = remember(context) {
    MapLibre.getInstance(context)
    MapView(context).apply { onCreate(null) }
  }

  var controller by remember { mutableStateOf<MapController?>(null) }
  var styleJson by remember { mutableStateOf<String?>(null) }
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

  LaunchedEffect(styleUrl, terrainExaggeration) {
    styleJson = withContext(Dispatchers.IO) {
      runCatching {
        MapStyleFactory.withTerrain(
          baseStyleJson = MapStyleLoader.fetch(styleUrl),
          exaggeration = terrainExaggeration,
        )
      }.getOrNull()
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

  LaunchedEffect(controller, styleJson) {
    val readyController = controller ?: return@LaunchedEffect
    val json = styleJson ?: return@LaunchedEffect
    readyController.applyStyle(json)
    readyController.moveCamera(initialCamera)
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
