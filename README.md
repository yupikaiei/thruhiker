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
- `core:model` and `core:geo`: pure-Kotlin domain types and WGS84 geodesic maths, with 59 unit tests.
- `core:designsystem`: Material 3 theme (pine, granite, sunrise) with Material You support.
- `app`: Compose shell with Navigation 3 and a four-destination bottom bar.

Not yet built: the MapLibre terrain view, recording, the planning engine, and video export.
See "Roadmap" below.

## Module map

| Module | Type | Purpose |
| --- | --- | --- |
| `app` | Android app | Shell, navigation, DI wiring |
| `core:model` | Kotlin JVM | Domain types (`LatLng`, `TrackPoint`, `Track`). No platform dependencies |
| `core:geo` | Kotlin JVM | Geodesic maths, elevation, simplification, hiking-time estimation |
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

## Building

Requires JDK 17+ (JDK 21 recommended) and an Android SDK with platform 36.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

./gradlew test              # unit tests across all modules
./gradlew assembleDebug     # debug APK
./gradlew lint              # Android lint
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

If `ANDROID_HOME` is not set, Gradle reads `sdk.dir` from `local.properties` (not committed).

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
| 1 | Trail pack, GPX/FIT import-export, offline elevation profiles |
| 2 | 3D cinematic recap: camera rig, trail reveal, MP4/GIF export |
| 3 | Recording: foreground service, adaptive sampling, barometric elevation |
| 4 | Offline corridor maps and OSM routing for alternates and bailouts |
| 5 | Planning engine: itinerary, day splitting, resupply, nutrition, permits |
| 6 | Cinematic preview of the next day's section |
| 7 | On-trail execution: alerts, town cards, check-in timer, Health Connect, Wear OS |
| 8 | Battery and frame profiling, signed release APK |