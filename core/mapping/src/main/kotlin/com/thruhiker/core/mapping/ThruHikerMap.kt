package com.thruhiker.core.mapping

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thruhiker.core.model.CameraOptions
import com.thruhiker.core.model.LatLng
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
  gradientGeoJson: String? = null,
  waypointsGeoJson: String? = null,
  onMapClick: ((LatLng) -> Unit)? = null,
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

  // Terrain and the route layers belong to the style, so they are set once. Note what is
  // *not* in it: any route geometry. The route is source data and is pushed separately,
  // because a style is applied asynchronously — it can land well after a seek, and a style
  // that carries the route therefore draws the whole route again on top of the reveal the
  // flyover has just set. Keeping the style's route source empty makes the worst case of a
  // missed update "nothing is drawn", which is the state the flight starts in anyway.
  LaunchedEffect(controller, baseStyleJson, terrainExaggeration) {
    val readyController = controller ?: return@LaunchedEffect
    val base = baseStyleJson ?: return@LaunchedEffect

    // Layers are added for every route-bearing feature up front, with empty sources. The
    // data is pushed separately, for the same reason the track geometry is: a style is
    // applied asynchronously, so a style that carried the data could land after the user has
    // already changed it and repaint something stale.
    val styled = MapStyleFactory.withTerrainAndTrack(
      baseStyleJson = base,
      trackGeoJson = NO_TRACK,
      exaggeration = terrainExaggeration,
    )
    val withGradient = MapStyleFactory.withGradientLine(styled, GeoJsonEncoder.EMPTY_COLLECTION)
    val withWaypoints = MapStyleFactory.withWaypoints(withGradient, GeoJsonEncoder.EMPTY_COLLECTION)

    readyController.applyStyle(withWaypoints)
  }

  // The drawn route, reasserted whenever it changes. Safe to call before the style has
  // loaded: the update waits for the style rather than being dropped by it.
  LaunchedEffect(controller, baseStyleJson, trackGeoJson) {
    val readyController = controller ?: return@LaunchedEffect
    if (baseStyleJson == null) return@LaunchedEffect
    readyController.updateTrack(trackGeoJson ?: NO_TRACK)
  }

  LaunchedEffect(controller, baseStyleJson, gradientGeoJson) {
    val readyController = controller ?: return@LaunchedEffect
    if (baseStyleJson == null) return@LaunchedEffect
    readyController.updateGradientRoute(gradientGeoJson ?: GeoJsonEncoder.EMPTY_COLLECTION)
  }

  LaunchedEffect(controller, baseStyleJson, waypointsGeoJson) {
    val readyController = controller ?: return@LaunchedEffect
    if (baseStyleJson == null) return@LaunchedEffect
    readyController.updateWaypoints(waypointsGeoJson ?: GeoJsonEncoder.EMPTY_COLLECTION)
  }

  // Framing follows the imported route. The flyover moves the camera itself after this, so
  // this only has to place the establishing shot — and it places it with an animated move,
  // because a plain jump does not repaint on the SDK this app builds against. See
  // [MapController.animateCamera].
  //
  // Declared *after* the data effects, and that ordering is load-bearing: a route and a new
  // camera usually arrive in the same recomposition — that is exactly what "generate a loop
  // and frame it" does — and a source update cancels a camera transition that is still
  // running. Moving the camera last lets the new geometry land first, so the animation is
  // not thrown away half way to its destination.
  LaunchedEffect(controller, baseStyleJson, camera) {
    val readyController = controller ?: return@LaunchedEffect
    if (baseStyleJson == null) return@LaunchedEffect
    readyController.animateCamera(camera)
  }

  // Registered once and always calling the newest handler. Keying the effect on the callback
  // would tear down and reinstall the listener on every recomposition, because a lambda
  // written inline at the call site is a new object each time.
  val currentOnMapClick by rememberUpdatedState(onMapClick)
  LaunchedEffect(controller) {
    val readyController = controller ?: return@LaunchedEffect
    readyController.setOnMapClickListener { position -> currentOnMapClick?.invoke(position) }
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
