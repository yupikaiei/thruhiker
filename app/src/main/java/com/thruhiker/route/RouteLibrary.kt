package com.thruhiker.route

import android.content.Context
import com.thruhiker.core.geo.RouteAnalysis
import com.thruhiker.core.geo.TrailDifficulty
import com.thruhiker.core.geo.TrackStatsCalculator
import com.thruhiker.core.gpx.GpxParser
import com.thruhiker.core.gpx.GpxWriter
import com.thruhiker.core.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A route the user has kept, with everything the list screen needs to describe it.
 *
 * The geometry is not in here. Summaries live in one small JSON index that is cheap to parse
 * on every screen entry, while each route's points live in its own GPX file and are read only
 * when the route is opened. A hundred saved thru-hikes would otherwise mean parsing a hundred
 * tracks to draw a list.
 */
data class SavedRoute(
  val id: String,
  val name: String,
  val createdAtMillis: Long,
  val distanceMeters: Double,
  val elevationGainMeters: Double,
  val elevationLossMeters: Double,
  val pointCount: Int,
  val difficulty: TrailDifficulty,
  val walkingSeconds: Double,
)

/**
 * The device's route library: the app's only persistence.
 *
 * Routes are stored as GPX, which is the app's native currency, so the library doubles as a
 * directory of files any other tool can read. An index beside them carries the summaries.
 *
 * The index is parsed with the platform's [JSONObject] rather than a serialization library, on
 * purpose: the app otherwise has no serialization runtime on its classpath, and the shape
 * being written here is a flat array of scalars that does not justify adding one.
 *
 * Every method does file I/O and must be called off the main thread.
 */
class RouteLibrary(context: Context) {

  private val directory = File(context.filesDir, DIRECTORY_NAME)
  private val indexFile = File(directory, INDEX_FILE_NAME)

  /**
   * Every saved route, newest first.
   *
   * A corrupt or unreadable index yields an empty library rather than throwing. The list
   * screen has nothing useful to say about a bad index, and a crash there would take the
   * planner with it.
   */
  fun list(): List<SavedRoute> {
    if (!indexFile.isFile) return emptyList()
    return runCatching { decodeIndex(indexFile.readText()) }
      .getOrDefault(emptyList())
      .sortedByDescending { it.createdAtMillis }
  }

  /** The track of one saved route, or null when its file has gone missing. */
  fun load(id: String): Track? {
    val file = routeFile(id)
    if (!file.isFile) return null
    return runCatching { GpxParser.parse(file.inputStream()).track }.getOrNull()
  }

  /** The GPX text of one saved route, ready to be written wherever the user asked. */
  fun gpx(id: String): String? {
    val file = routeFile(id)
    if (!file.isFile) return null
    return runCatching { file.readText() }.getOrNull()
  }

  /**
   * Stores [track] under [name] and returns its summary.
   *
   * @throws IOException when the file cannot be written, so the caller can tell the user their
   *   route was not kept instead of pretending it was.
   */
  @Throws(IOException::class)
  fun save(track: Track, name: String): SavedRoute {
    require(!track.isEmpty) { "Refusing to save an empty route" }

    ensureDirectory()
    val stats = TrackStatsCalculator.compute(track)
    val summary = SavedRoute(
      id = UUID.randomUUID().toString(),
      name = name.trim().ifBlank { DEFAULT_NAME },
      createdAtMillis = System.currentTimeMillis(),
      distanceMeters = stats.distanceMeters,
      elevationGainMeters = stats.elevationGainMeters,
      elevationLossMeters = stats.elevationLossMeters,
      pointCount = stats.pointCount,
      difficulty = RouteAnalysis.difficultyFor(stats.distanceMeters, stats.elevationGainMeters),
      walkingSeconds = RouteAnalysis.of(track).walkingSeconds,
    )

    routeFile(summary.id).writeText(GpxWriter.write(track, summary.name))
    writeIndex(listOf(summary) + list())
    return summary
  }

  /** Removes a route and its file. Deleting something already gone is a no-op. */
  fun delete(id: String) {
    routeFile(id).delete()
    writeIndex(list().filterNot { it.id == id })
  }

  private fun routeFile(id: String): File = File(directory, "$id.gpx")

  @Throws(IOException::class)
  private fun ensureDirectory() {
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw IOException("Could not create the route library at ${directory.path}")
    }
  }

  private fun writeIndex(routes: List<SavedRoute>) {
    ensureDirectory()
    indexFile.writeText(encodeIndex(routes))
  }

  private fun encodeIndex(routes: List<SavedRoute>): String {
    val array = JSONArray()
    for (route in routes) {
      array.put(
        JSONObject()
          .put(FIELD_ID, route.id)
          .put(FIELD_NAME, route.name)
          .put(FIELD_CREATED, route.createdAtMillis)
          .put(FIELD_DISTANCE, route.distanceMeters)
          .put(FIELD_GAIN, route.elevationGainMeters)
          .put(FIELD_LOSS, route.elevationLossMeters)
          .put(FIELD_POINTS, route.pointCount)
          .put(FIELD_DIFFICULTY, route.difficulty.name)
          .put(FIELD_WALKING_SECONDS, route.walkingSeconds),
      )
    }
    return array.toString()
  }

  private fun decodeIndex(text: String): List<SavedRoute> {
    val array = JSONArray(text)
    return (0 until array.length()).map { index -> array.getJSONObject(index).toSavedRoute() }
  }

  private fun JSONObject.toSavedRoute(): SavedRoute = SavedRoute(
    id = getString(FIELD_ID),
    name = getString(FIELD_NAME),
    createdAtMillis = getLong(FIELD_CREATED),
    distanceMeters = getDouble(FIELD_DISTANCE),
    elevationGainMeters = getDouble(FIELD_GAIN),
    elevationLossMeters = getDouble(FIELD_LOSS),
    pointCount = getInt(FIELD_POINTS),
    // A difficulty name this build does not know is not worth losing the route over.
    difficulty = runCatching { TrailDifficulty.valueOf(getString(FIELD_DIFFICULTY)) }
      .getOrDefault(TrailDifficulty.MODERATE),
    walkingSeconds = optDouble(FIELD_WALKING_SECONDS, 0.0),
  )

  private companion object {
    const val DIRECTORY_NAME = "routes"
    const val INDEX_FILE_NAME = "index.json"
    const val DEFAULT_NAME = "Planned route"

    const val FIELD_ID = "id"
    const val FIELD_NAME = "name"
    const val FIELD_CREATED = "createdAtMillis"
    const val FIELD_DISTANCE = "distanceMeters"
    const val FIELD_GAIN = "elevationGainMeters"
    const val FIELD_LOSS = "elevationLossMeters"
    const val FIELD_POINTS = "pointCount"
    const val FIELD_DIFFICULTY = "difficulty"
    const val FIELD_WALKING_SECONDS = "walkingSeconds"
  }
}
