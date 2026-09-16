# ThruHiker

An offline-first Android app for planning and executing thru-hikes anywhere in the world,
with a cinematic 3D map as its centrepiece.

A thru-hike is a three-to-six month logistics problem disguised as a hike. ThruHiker owns
the whole arc:

**PLAN** (itinerary, resupply, permits) → **PREVIEW** (fly tomorrow's section in 3D) →
**EXECUTE** (offline recording and on-trail heads-ups) → **RELIVE** (3D cinematic recap,
exported as shareable video).

## Offline first, by requirement rather than preference

Five to seven days without a signal is the *normal* case on a long trail, not the edge case.
There is no backend, no account and no sync in scope: the device is the system of record, and
everything from the elevation profile to the itinerary is computed locally.

## Status

Phase 0, foundations. What works today:

- Multi-module Gradle build with a shared version catalog, verified on AGP 9 / Kotlin 2.3 / JDK 21.
- `core:model` and `core:geo`: pure-Kotlin domain types and WGS84 geodesic maths.
- `core:gpx`: reads GPX 1.0 and 1.1, and writes GPX 1.1.
- `core:mapping`: MapLibre rendering real 3D terrain with hillshade, sky and a route line, wrapped
  in a Compose map surface.
- `core:designsystem`: Material 3 theme (pine, granite, sunrise) with Material You support.
- `app`: Compose shell with Navigation 3, a four-destination bottom bar, and a Flyover tab that
  imports a GPX file, draws it over terrain, and reports what the maths makes of it.

**127 unit tests, all passing.** The end-to-end path that works today is: pick a GPX file →
parse → compute distance, ascent, elapsed and moving time → estimate walking time with Tobler →
encode as GeoJSON → draw over 3D terrain with the camera framed on the route.

**Nothing has run on a device yet.** This container has no emulator and cannot reach a USB-attached
phone, so the map is verified to compile and to produce a correct style document, not to render on
a screen. That is still the top outstanding risk, and it is the reason the sample file exists: it
takes one import to find out.

Not yet built: recording, the flyover camera rig and video export, and the planning engine. See
"Roadmap" below.

## Module map

| Module | Type | Purpose |
| --- | --- | --- |
| `app` | Android app | Shell, navigation, DI wiring |
| `core:model` | Kotlin JVM | Domain types (`LatLng`, `TrackPoint`, `Track`). No platform dependencies |
| `core:geo` | Kotlin JVM | Geodesic maths, elevation, simplification, hiking-time estimation |
| `core:gpx` | Kotlin JVM | GPX parsing and writing |
| `core:mapping` | Android library | MapLibre terrain styles, GeoJSON encoding, camera framing, Compose map surface |
| `core:designsystem` | Android library | Theme and shared UI primitives |

Pure Kotlin JVM modules are deliberate: the hard part of this app is arithmetic, and arithmetic
that runs on the JVM runs its tests in milliseconds instead of needing an emulator.

### What `core:geo` does

- **`Geodesic`** — Vincenty's inverse and direct formulae on the WGS84 ellipsoid, with a
  spherical fallback for near-antipodal pairs where Vincenty does not converge. Over a 4,000 km
  trail the difference between ellipsoidal and spherical distance is tens of kilometres, which
  is the difference between a resupply plan that works and one that does not.
- **`ElevationCalculator`** — cumulative ascent and descent with hysteresis, because summing raw
  positive deltas turns two metres of barometric noise into thousands of metres of phantom climb.
- **`TrackSimplifier`** — Douglas-Peucker that respects the vertical profile. A plain 2D run
  deletes switchback apexes, which breaks climb detection and day splitting.
- **`ToblerEstimator`** — walking time from grade. Tobler's function peaks on a slight descent,
  so time estimates are asymmetric uphill and downhill rather than a flat speed plus penalty.
- **`TrackStatsCalculator`** — distance, gain, loss, elapsed time and moving time in one pass.

### Why tracks have segments

A `Track` is a list of `TrackSegment`s, not a flat list of points, because GPS recording is not
continuous. A hiker pauses for lunch, loses signal in a canyon, or switches the phone off
overnight. Treating those gaps as ordinary consecutive samples invents a straight-line jump across
however far they walked in between, which corrupts distance and speed at the same time. Elevation
gain across a gap is unknown travel rather than climb, so the hysteresis reference resets at every
segment boundary, and the map draws the gaps instead of spanning them.

`Track.points` still exists for display, but it deliberately erases the boundaries and must not be
used for measurement.

## Building

Requires JDK 17+ (JDK 21 recommended) and an Android SDK with platform 36.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

./gradlew test              # unit tests across all modules
./gradlew assembleDebug     # debug APK
./gradlew lint              # Android lint
```

The debug APK lands in `app/build/outputs/apk/debug/`, split per ABI because MapLibre ships a
~13 MB native library for each one. Install `app-arm64-v8a-debug.apk` on any modern phone; the
x86_64 build is for emulators. A universal APK would be about 60 MB, so splits stay on.

If `ANDROID_HOME` is not set, Gradle reads `sdk.dir` from `local.properties` (not committed).

### Terrain

3D terrain has no imperative "enable" call. `MapStyleFactory` fetches the basemap style and injects
a Terrarium-encoded `raster-dem` source, a root-level `terrain` property, a `hillshade` layer placed
below the label layers, and a sky layer. That is the whole feature, which is why it is unit-tested
as a JSON transformation rather than by looking at pixels.

## Free and open stack

No API keys, no per-MAU billing:

- **Basemap** — OpenFreeMap vector tiles
- **Terrain** — AWS Open Data elevation tiles (Terrarium-encoded DEM)
- **Imagery** — NASA GIBS, EOX Sentinel-2 cloudless
- **Weather** — Open-Meteo
- **Trail geometry** — OpenStreetMap, NPS and USFS
- **Rendering** — MapLibre

## Roadmap

| Phase | Deliverable |
| --- | --- |
| 0 | Foundations, build, CI, geo core ✅ |
| 1 | GPX import/export ✅ · trail pack and offline elevation profiles outstanding |
| 2 | 3D cinematic recap: camera rig, trail reveal, MP4/GIF export |
| 3 | Recording: foreground service, adaptive sampling, barometric elevation |
| 4 | Offline corridor maps and OSM routing for alternates and bailouts |
| 5 | Planning engine: itinerary, day splitting, resupply, nutrition, permits |
| 6 | Cinematic preview of the next day's section |
| 7 | On-trail execution: alerts, town cards, check-in timer, Health Connect, Wear OS |
| 8 | Battery and frame profiling, signed release APK |

## Trying it

Import `samples/chamonix-test-loop.gpx` from the Flyover tab. It is synthetic test data with a
deliberate recording gap in it; `samples/README.md` explains what should happen.