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

Phase 1, the route planner. What works today:

- Multi-module Gradle build with a shared version catalog, verified on AGP 9 / Kotlin 2.3 / JDK 21.
- `core:model` and `core:geo`: domain types, WGS84 geodesic maths, distance-based track sampling,
  gradient bands, elevation profiles, splits, route analysis and loop generation.
- `core:dem`: Terrarium elevation tiles decoded, sampled bilinearly and filled into a drawn route,
  so a line tapped onto a map gets a real vertical profile. Pure JVM; the platform fetch is a seam.
- `core:gpx`: reads GPX 1.0 and 1.1, and writes GPX 1.1.
- `core:flyover`: the cinematic camera, as a pure function of elapsed time.
- `core:mapping`: MapLibre rendering real 3D terrain with hillshade, sky and a route that draws
  itself, wrapped in a Compose map surface — plus a gradient-coloured route and numbered markers.
- `core:designsystem`: Material 3 theme (pine, granite, sunrise) with Material You support.
- `app`: Compose shell with Navigation 3, a four-destination bottom bar, a **planner** that builds,
  measures, colours and splits a route, a **route library** that keeps them, and the Flyover.

**325 unit tests, all passing.** Two end-to-end paths work today:

- **Plan**: tap waypoints on the map, import a GPX (one file or several, joined), or generate a loop
  → measure it → read its gradient-coloured elevation profile and its per-kilometre or per-mile
  splits, with optional arrival times → save it, export it, or hand it to the flyover.
- **Fly**: pick a GPX file → parse it → measure it → plan a flight over it → fly it in 3D while the
  route draws itself behind the camera, with play, pause, replay and scrubbing.

**It has now been run, on an x86_64 emulator.** That first run was worth the wait: the app
crashed on launch, because every published MapLibre artifact but one is a Vulkan-only build and
no emulator image exposes a Vulkan device — see "Running on an emulator" below. With that
fixed, what was confirmed on screen is the planner end to end: tapping the map drops
waypoints with correct coordinates, the route draws over real terrain, distance, climb,
difficulty and Tobler time come out right, the profile and split panels render, the route
library works, the planner's hand-off opens the flyover, and the flyover flies.

**A tapped route now gets its elevation from the ground.** The planner's default view is centred on
Mont Blanc, so the most convenient place to tap is the summit: a three-tap route there reads 6.6 km
with 1,996 m of ascent and arrives at 4,755 m, coloured steep-climb for the climb and steep-descent
for the descent. That number was checked rather than trusted — an independent sampler, run in Python
against the same tiles the app had downloaded, measured 6.65 km and 1,995 m, and got 2,781 / 4,755 /
3,245 m at the three tapped points. Its naive per-leg gain of 2,010 m against the app's 1,995 m after
hysteresis also shows the DEM's noise is not inflating the climb. The DEM reports 4,755 m at the
summit against a true 4,808 m: it smooths a peak, which is what a 30 m grid does.

What was *not* confirmed is 3D terrain, because the emulator build necessarily uses the flat,
published SDK, and a one-off camera change: on this GPU stack `map.cameraPosition = x` updates
the camera the SDK reports without producing a frame, so the planner's "Fit" only moves the
map when the camera is driven the way the flyover drives it (see `MapController.animateCamera`).
A physical device is still the only place 3D terrain and the terrain-specific quirks can be
judged, and `samples/chamonix-test-loop.gpx` is still the one import that would confirm them.

Not yet built: video export, recording, and the *thru-hike* planning engine — itineraries, day
splitting, resupply and permits. The route planner above is the geometry half of that. See
"Roadmap" below.

## Module map

| Module | Type | Purpose |
| --- | --- | --- |
| `app` | Android app | Shell, navigation, planner, route library, flyover |
| `core:model` | Kotlin JVM | Domain types (`LatLng`, `TrackPoint`, `Track`). No platform dependencies |
| `core:geo` | Kotlin JVM | Geodesic maths, elevation, profiles, gradient bands, splits, route analysis, loop generation |
| `core:dem` | Kotlin JVM | Terrarium tile decoding, slippy-map tile maths, bilinear height sampling, elevation filling |
| `core:gpx` | Kotlin JVM | GPX parsing and writing |
| `core:mapping` | Android library | MapLibre terrain styles, GeoJSON encoding, camera framing, Compose map surface |
| `core:flyover` | Kotlin JVM | Cinematic camera: timeline, keyframes, easing |
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
- **`GradientBand`** — the blue-to-red ramp every trail planner paints with, in seven bands plus
  `UNKNOWN` for a route whose vertical profile nobody recorded.
- **`ElevationProfile`** — the track resampled onto an even distance axis, with the gradient of every
  column and a flag on the columns that straddle a recording gap.
- **`SplitsCalculator`** — the route cut at fixed distance intervals, with Tobler time, climb and
  gradient per split, summing to the whole-route totals.
- **`RouteAnalysis`** — distance, climb, net gradient, steepest sustained climb and descent, the
  share of the route in each band, and an effort-based difficulty rating.
- **`TrackOperations`** — reverse (with timestamps mirrored so they stay chronological), merge, and
  densify with elevation interpolation.
- **`LoopGenerator`** — a closed Catmull-Rom loop of a requested length around a point, rescaled
  until it measures what was asked for.

### Why tracks have segments

A `Track` is a list of `TrackSegment`s, not a flat list of points, because GPS recording is not
continuous. A hiker pauses for lunch, loses signal in a canyon, or switches the phone off
overnight. Treating those gaps as ordinary consecutive samples invents a straight-line jump across
however far they walked in between, which corrupts distance and speed at the same time. Elevation
gain across a gap is unknown travel rather than climb, so the hysteresis reference resets at every
segment boundary, and the map draws the gaps instead of spanning them.

`Track.points` still exists for display, but it deliberately erases the boundaries and must not be
used for measurement.

### How the planner works

The planner is a screen and a set of pure functions, and the split between them is the design.
Everything a route can be measured for — its length, its climb, its profile, its splits, its
difficulty — is a function of a `Track` in `core:geo`, tested on the JVM in milliseconds. The screen
owns only what a map is needed for: the waypoints the user taps, and the geometry handed to
MapLibre.

Waypoints are `TrackPoint`s, not bare coordinates, because a route's elevation has to survive the
round trip through the editor. Tapping the map adds a point with no elevation; importing a recording
adds points that have one. Densifying a leg interpolates elevation linearly when both ends have it
and produces nothing when they do not, so a planned route is honestly profile-less while an imported
one keeps the profile its barometer recorded.

**A route with no elevation goes and gets some.** Without it, everything vertical is switched off —
no gradient colours, no profile, no climb, no difficulty — and a drawn route is exactly the case that
has none, because heights cannot be derived from a shape. So the planner asks the ground. `core:dem`
reads the same Terrarium tiles the map is already fetching to draw hillshade, samples them bilinearly
at every route point, and rebuilds the route's statistics from what it finds. Because it is the same
data the map is showing, the profile cannot disagree with the relief on screen.

Three details decide whether that is a feature or a nuisance:

- **A recorded height always wins.** Only points with no elevation are filled, so importing a GPX
  that carries its own barometric profile never has it overwritten by a global model with tens of
  metres of error in steep ground.
- **The work is bounded, and the route degrades rather than refusing.** `TrackElevationFiller`
  chooses the finest zoom whose tiles fit under a cap of 64, so a route too long for trail resolution
  is sampled coarser rather than not at all, and one too long even for the coarsest zoom is left
  alone instead of fetching a thousand tiles.
- **Sampling is bilinear, not nearest-pixel.** At the chosen zoom a pixel is about thirteen metres of
  ground, so a route densified to 25 m would otherwise step between whole pixels — and a staircase,
  differentiated into a gradient, is a row of spikes rather than a profile.

The fetch is behind a one-method `DemTileSource` seam, which is what keeps the arithmetic testable on
the JVM with hand-built tiles. The Android implementation adds a decoded-tile memory cache and a disk
cache under the app's cache directory, so a route planned at home still has a profile in a valley with
no signal. A planning pass reads the same tile hundreds of times, and the pass is cancelled by
*staleness* — a newer pass takes a generation number — rather than by coroutine cancellation, because
a blocked `HttpURLConnection` read cannot be interrupted out of.

A route with no elevation at all draws as a plain line rather than a grey one. Supplying an empty
gradient collection lets the ordinary route line show through, which is the honest picture: the shape
is known, the steepness is not.

**Gradients are coloured per leg, not per route.** A line layer can only paint by a value it reads
off each feature, so `GeoJsonEncoder.encodeGradient` emits one short two-point feature per leg
carrying its band's colour, and the style reads that colour back. Legs are cut at 30 m, the
compromise every planner lands on: fine enough to show a switchback, coarse enough that a leg's
gradient means something. Along the way this keeps the band boundaries in tested Kotlin rather than
in a style expression.

A route with no elevation at all draws as a plain line rather than a grey one. Supplying an empty
gradient collection lets the ordinary route line show through, which is the honest picture: the shape
is known, the steepness is not.

**The profile is sampled by distance.** Track points are not evenly spaced, so plotting the raw array
squashes the interesting half of a route into a few pixels and stretches the rest. `ElevationProfile`
resamples onto an even distance axis and flags the columns either side of a recording gap, which
carry no gradient: the slope of ground nobody measured is not a number, and the chart draws that
stretch grey instead of inventing a slope across it.

**Splits share the legs that straddle them.** A kilometre boundary almost never lands on a track
point, so a leg frequently spans two splits. Distance and elevation change are both shared between
them in proportion to the ground each takes, and gain and loss keep the same hysteresis the rest of
the app uses, attributed to the split where it was committed. Resetting the hysteresis reference at
every boundary — the obvious implementation — silently deletes a few metres of climb per kilometre
on a long ascent, which on an alpine pass is hundreds of metres. `SplitsCalculatorTest` pins the
invariant that the splits sum to the whole-route numbers.

**The loop generator draws a shape, not a route.** Following real trails needs a routing graph over
OpenStreetMap, which is a server and a download, and neither exists in an offline-first app with no
backend. `LoopGenerator` produces a closed Catmull-Rom spline through a jittered ring of control
points at a radius derived from the requested length, then rescales that ring until the measured
loop is the length that was asked for. It is labelled an estimate wherever it is offered; what it is
good for is a starting shape the user drags into place.

**The route library is the app's only persistence.** Each route is a GPX file in the app's files
directory with a small JSON index beside it holding the summaries, so the list screen parses
kilobytes rather than a hundred tracks. Keeping routes as GPX means the library is also a directory
of files any other tool can open.

### How the flyover works

`FlyoverRig.frameAt(elapsedMillis)` is a pure function from a millisecond offset to a camera. That
one decision is what makes the rest tractable: the camera maths is testable without a map, and video
export becomes possible later because an exporter can ask for frame 1,247 directly instead of
playing frames 1 to 1,246.

The flight has three parts. An **intro** settles from a wide establishing shot onto the trailhead,
turned off-axis so the first sweep reveals the terrain. **Travel** flies the route at a constant
ground speed with the camera looking a fixed distance *ahead* of the walker, offset in bearing so
the route runs diagonally across the frame rather than dead ahead into the distance, where
foreshortening would flatten it into nothing. The **outro** pulls up and flattens out over the
finish.

Camera position comes from `TrackSampler`, which walks the track by *distance* rather than by point
index. Track points are not evenly spaced, so stepping through the array would make the camera
lurch through dense sections and sprint through sparse ones, at a different speed on every
recording. Travel duration is clamped rather than proportional: without a floor a two-kilometre
stroll is over before you have focused, and without a ceiling the Pacific Crest Trail takes two
hours to fly.

There are two distance axes, and the camera uses whichever one keeps it honest. Walking distance
never crosses a gap, so the reveal stops at one and the mileage readout does not jump over ground
nobody covered. Flight distance counts the gap as ground still to be crossed, interpolating
straight across it, so the camera crosses the unrecorded stretch at the same ground speed as
everything else instead of teleporting nine hundred metres between two frames. The rig reads the
first axis for what it draws and reports, and the second for where it looks and how fast it moves.

Camera heading is eased *along* the route rather than measured *across* it. A heading taken from the
chord between two points either side of the walker is fine in the open and fails at a hairpin: the
two ends of a wide window sit on opposite sides of the fold, a hundred metres apart, and the camera
spins through most of the compass in a handful of frames. The rig samples the walker's own heading
along the route, unwraps it so that crossing north is a small step rather than a jump of 350
degrees, and only then averages it over `bearingWindowMeters`. A turn becomes a pan however tightly
the route doubles back, and the jitter of a one-second recording averages away with it.

The route drawing itself is `GeoJsonEncoder.encode(track, revealedFraction)`, which cuts the
geometry mid-leg at exactly the right distance. The reveal granularity is a bandwidth decision, not
a visual one — every step re-encodes and re-uploads the geometry so far, so a hundred-thousand-point
route revealed in three hundred steps would move gigabytes of JSON.

## Downloading a build

Two things are published, on purpose.

**The newest build from `main`**, replaced on every push:
<https://github.com/yupikaiei/thruhiker/releases/tag/latest>

The asset names deliberately do not contain the commit, so this link is permanent and always serves
the current build — good enough to bookmark on a phone:

```
https://github.com/yupikaiei/thruhiker/releases/download/latest/thruhiker-arm64-v8a.apk
```

**Frozen versions**, from a `v*` tag: <https://github.com/yupikaiei/thruhiker/releases/latest>

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Rolling builds are marked as prereleases so they do not take the "Latest" badge away from a real
tagged version, which is why the two URLs above are different pages.

Install `thruhiker-arm64-v8a.apk` on any modern phone. It is the only asset: the vendored terrain
SDK is arm64-only, so a 32-bit device has no renderer to install. Android will ask you to allow
installs from an unknown source and Play Protect may warn, both of which are expected — "Signing"
below explains why these builds are signed the way they are, and why that matters if you download
more than one.

Actions artifacts are uploaded per run as well, but they need a signed-in GitHub session and expire
after 30 days, which is the reason the releases exist.

## Signing

An APK has to be signed to be installed at all — `adb install` refuses an unsigned one with
`INSTALL_PARSE_FAILED_NO_CERTIFICATES`. The release variant therefore falls back to the debug
keystore when no keystore is configured, so `assembleRelease` produces something you can put on a
phone immediately, with no key to invent or look after first.

That fallback is right locally and wrong in CI, and the difference is worth knowing before it bites.
A GitHub runner is a fresh machine on every run, so the debug keystore is generated on the spot and
every published build carries a different signature. Android then refuses to install a new download
over the previous one and says nothing about why. Two builds this repository published, three weeks
apart, were signed with these certificates:

| Release | SHA-256 of the signing certificate |
| --- | --- |
| `latest`, 2026-10-10 | `b8a6098885aea4176a4193d2dfede4f2a75a41710fadff2decc382238168b7d9` |
| `v0.1.0`, 2026-09-16 | `b5ec98b637b618d4c4ae64a0eb7e04634a7b3efee75d73919c14812ae3df492c` |

Giving CI a real keystore collapses those into one signature, which is what makes "download the new
build and install it" actually work. Create one and keep it somewhere you will not lose it:

```bash
keytool -genkeypair -v -keystore thruhiker.jks -alias thruhiker \
  -keyalg RSA -keysize 4096 -validity 10000

base64 -i thruhiker.jks | pbcopy   # macOS
base64 -w0 thruhiker.jks > thruhiker.jks.b64   # Linux
```

Then add four repository secrets, under Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `THRUHIKER_KEYSTORE_BASE64` | the base64 blob above |
| `THRUHIKER_KEYSTORE_PASSWORD` | the store password you chose |
| `THRUHIKER_KEY_ALIAS` | `thruhiker` |
| `THRUHIKER_KEY_PASSWORD` | the key password you chose |

The workflow decodes the keystore into the runner's temp directory and exports `THRUHIKER_KEYSTORE`
and the rest; `app/build.gradle.kts` reads those and creates a `release` signing config, which the
release variant then prefers. Nothing is required for a local build, and nothing changes until the
secrets exist — the workflow warns when they do not. The same four variables work locally:

```bash
THRUHIKER_KEYSTORE=thruhiker.jks \
THRUHIKER_KEYSTORE_PASSWORD="$PASS" \
THRUHIKER_KEY_ALIAS=thruhiker \
THRUHIKER_KEY_PASSWORD="$PASS" \
  ./gradlew assembleRelease
```

`*.keystore` and `*.jks` are gitignored. Back the keystore up outside the repository: lose it and
you cannot update an installed copy, because Android will not accept a build signed with a
different key as an upgrade.

## Building

Requires JDK 17+ (JDK 21 recommended) and an Android SDK with platform 36.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

./gradlew test              # unit tests across all modules
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # release APK, signed — see "Signing"
./gradlew lint              # Android lint
```

Builds ship a vendored MapLibre SDK with 3D terrain — see "Terrain" below.

The APKs land in `app/build/outputs/apk/{debug,release}/`, split per ABI because MapLibre ships a
~13 MB native library for each one. A universal APK would be about 60 MB, so the splits stay on.
Note there is no `app-debug.apk` — the split is not a variant of a base name, it replaces it:

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk   # phone
```

With the vendored terrain SDK in place, arm64-v8a is the only split built, because that SDK carries
no other native library and the others would produce an APK with no renderer at all. The other ABIs
appear only when the build opts out of terrain, which is what an x86_64 emulator needs — see
"Running on an emulator" below.

If `ANDROID_HOME` is unset, point the build at your SDK with `sdk.dir` in `local.properties` (not
committed).

### Running on an emulator

The vendored 3D-terrain SDK carries an arm64-v8a native library and nothing else, so it cannot
run on an x86_64 emulator. Opt out of it:

```bash
./gradlew installDebug -Pthruhiker.vendoredTerrain=false
```

That builds the three usual ABI splits against the published SDK instead, which is the same
fallback the build takes on a fresh clone. The map is flat and hillshaded — no 3D terrain.

Two things about that path are worth knowing, both found by actually running it:

**The published `android-sdk` artifact is Vulkan-only.** `RenderingEngine.getDefaultRenderingEngine`
returns `VULKAN` unconditionally and `setCurrentType(OPENGL)` throws
`UnsupportedOperationException`. That is fine on a phone with a Vulkan driver and fatal
everywhere else: the render thread dies with `No Vulkan compatible GPU found` and the activity is
killed. No emulator image will do, either — the guest advertises `android.hardware.vulkan.level`
even when `cmd gpu vkjson` reports zero devices, so the map gets far enough to crash. The app
therefore depends on `android-sdk-opengl`: the same SDK, the same version, the same classes, with
the OpenGL ES renderer.

**A camera move on its own does not always produce a frame.** `moveCamera` pushes the camera to
the native map with a single `jumpTo`, and on this GPU stack the frame that comes back is the
previous one — `cameraPosition` reports the new camera while the screen still shows the old one.
Touch gestures render, and so does the flyover, which re-issues its camera every frame; a
one-off "frame this route" does not. `MapController.animateCamera` exists for that reason, and
`MapLibreMap.setPadding` was observed to break subsequent camera changes outright. Whether any of
this reproduces on real hardware is unknown; it is exactly the class of thing a device test
settles.

### Terrain

MapLibre Native's **released** SDK has no 3D terrain: upstream is still building it, on the
`feature/terrain-3d` branch. MapLibre ignores the root-level `terrain` property in the meantime —
the style parser only looks up the keys it knows, it does not reject unknown ones — so against the
published SDK the map is necessarily a flat, hillshaded plane.

`MapStyleFactory` emits the terrain the style specification describes anyway, because that half of
the feature is portable:

- a Terrarium-encoded `raster-dem` source,
- a root-level `terrain` property pointing at it, with an exaggeration,
- a `hillshade` layer on the same source, sitting below the label layers, and
- a `sky` layer.

That document is all MapLibre GL JS, Mapbox, and MapLibre Native's terrain branch need, and it is
unit-tested as a JSON transformation rather than by looking at pixels.

3D comes from a vendored copy of that branch in `third_party/maplibre-android/`: the branch's
Android sources plus its CI-built arm64-v8a native library, stripped. It is committed on purpose, so
a fresh clone and CI both build terrain without needing an NDK, a token, or a network fetch, and
`settings.gradle.kts` includes it as `:maplibre-terrain`. `core:mapping` depends on it instead of
the published artifact — the classes are the same, so no app code changes either way. Delete the
directory and the build falls back to the published, flat SDK.

Terrain loading is budgeted to `TerrainLoadMode.BALANCED`. The SDK's default builds every newly
revealed tile and drape in the frame it arrives, which stalls that frame; a flyover is the worst
case there is, because the camera never stops moving and there is a fresh burst of terrain every
second. The budget renders the same final image a frame or two later, which is invisible at a
kilometre a second. The setting exists only in the terrain branch, so it is applied reflectively and
a missing method is a no-op — `TerrainLoadBudgetTest` pins the lookup to whichever SDK is on the
classpath.

To refresh it when upstream moves:

```bash
scripts/build-maplibre-terrain.sh   # re-fetches the sources and native library, reassembles
./gradlew :app:assembleDebug
```

Worth knowing before shipping it:

- It tracks an unreleased upstream branch (draft PR maplibre/maplibre-native#4190), so it carries
  pre-merge risk; only the OpenGL renderer and only `arm64-v8a` are vendored, so the APK is
  arm64-only and the other ABI splits are dropped while the module is present.
- The library is ~11 MB installed, which is roughly what the APK grows by.
- MapLibre Native is BSD 2-Clause: `third_party/maplibre-android/LICENSE.md`.

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
| 1 | Route planner: waypoints, gradient colouring, elevation profile, splits, GPX import/export, route library, terrain-derived elevation for drawn routes ✅ · trail pack and offline elevation profiles outstanding |
| 2 | 3D cinematic recap: camera rig, trail reveal, in-app playback ✅ · MP4/GIF and shareable video outstanding |
| 3 | Recording: foreground service, adaptive sampling, barometric elevation |
| 4 | Offline corridor maps and OSM routing for alternates and bailouts |
| 5 | Thru-hike planning engine: itinerary, day splitting, resupply, nutrition, permits |
| 6 | Cinematic preview of the next day's section |
| 7 | On-trail execution: alerts, town cards, check-in timer, Health Connect, Wear OS |
| 8 | Battery and frame profiling, signed release APK |

## Trying it

The Plan tab is the fastest way in: tap three or four points on the map and you have a route with a
distance, a gradient-coloured line, an elevation profile, a difficulty rating and a split table. The
heights come from the terrain, so a drawn route is measured, not merely measured off. On the first
tap of a new shape the climb and difficulty read `—` rather than a number, because while the terrain
is being read they are unknown rather than zero.

Splits can be cut in kilometres or miles and, with a start time set, report the clock time each one
is reached instead of the elapsed time — the difference between reviewing a route and standing at a
trailhead working out whether you will reach the hut before dark.

Import takes one GPX file or several at once, which is how a long trail published as a file per stage
becomes one route. `samples/chamonix-test-loop.gpx` from the Plan tab is the one to try: the imported
elevations survive into the profile, the map and the splits. It is synthetic test data with a
deliberate recording gap in it; `samples/README.md` explains what should happen. The same file can be
imported from the Flyover tab, which keeps the gap as a gap and draws the file's waypoints as markers.