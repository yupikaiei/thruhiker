package com.thruhiker.core.mapping

import android.content.Context
import android.graphics.BitmapFactory
import com.thruhiker.core.dem.DemTileSource
import com.thruhiker.core.dem.SlippyTile
import com.thruhiker.core.dem.TileCoordinate
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads elevation tiles over HTTP, off a disk cache.
 *
 * The same Terrarium tiles the map draws hillshade from, so a route planned on a map that is
 * already showing relief costs no new data to give elevation to. That is deliberate: it keeps
 * the app's promise of working from what it has, and it means the profile is computed from the
 * same ground the user is looking at.
 *
 * Three tiers, cheapest first:
 *
 * - **Memory.** A planning pass asks for the same tile hundreds of times — an interpolated
 *   sample per route point against a tile that covers kilometres. Without this the disk would
 *   be re-read, and the PNG re-decoded, for every one of those points.
 * - **Disk.** Written once and reused forever after, which is what makes a route planned at
 *   home still have a profile in a valley with no signal.
 * - **Network.** Only reached when both of the above miss.
 *
 * Every failure — offline, HTTP error, a truncated or non-PNG body, an unexpected size — is
 * reported as null rather than thrown. A route with no elevation is a usable route; a crash on
 * the way to one is not.
 */
class TerrariumTileSource(
  context: Context,
  private val baseUrl: String = MapStyleFactory.TERRAIN_TILES_URL,
  /**
   * Asked before each tile. A planning pass can touch dozens of tiles and each one is a
   * blocking request, and a superseded pass cannot be interrupted out of one — so this is what
   * stops it queueing a minute of work behind the pass that matters.
   *
   * The caller identifies its own pass rather than asking "is anything still wanted": a newer
   * pass is what makes an older one stale, and it makes it stale immediately, rather than at
   * whatever suspension point its cancellation is finally noticed.
   */
  private val isActive: () -> Boolean = { true },
  /**
   * Where tiles are kept between runs. Assumes the configured provider: two sources pointed at
   * different servers need different directories, or one will serve the other's ground.
   */
  private val cacheDirectory: File = File(context.cacheDir, CACHE_DIRECTORY),
) : DemTileSource {

  override fun tile(tile: TileCoordinate): IntArray? {
    if (!isActive()) return null

    val key = "$baseUrl/${tile.zoom}/${tile.x}/${tile.y}"
    MemoryCache[key]?.let { return it }

    val pixels = fromDisk(tile) ?: fromNetwork(tile) ?: return null
    MemoryCache[key] = pixels
    return pixels
  }

  private fun fromDisk(tile: TileCoordinate): IntArray? {
    val file = cacheFile(tile)
    if (!file.isFile) return null
    // A file that will not decode is worse than no file: it would be re-read and re-failed on
    // every point of the route, so it is cleared out and the network gets a chance instead.
    return decode(file.readBytes()) ?: run { file.delete(); null }
  }

  private fun fromNetwork(tile: TileCoordinate): IntArray? {
    val bytes = try {
      fetch(urlFor(tile))
    } catch (_: IOException) {
      return null
    } ?: return null

    val pixels = decode(bytes) ?: return null
    runCatching {
      cacheDirectory.mkdirs()
      cacheFile(tile).writeBytes(bytes)
    }
    return pixels
  }

  private fun fetch(url: String): ByteArray? {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = CONNECT_TIMEOUT_MILLIS
      readTimeout = READ_TIMEOUT_MILLIS
      requestMethod = "GET"
      setRequestProperty("User-Agent", USER_AGENT)
    }

    return try {
      if (connection.responseCode !in 200..299) null
      else connection.inputStream.use { it.readBytes() }
    } finally {
      connection.disconnect()
    }
  }

  private fun decode(bytes: ByteArray): IntArray? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return try {
      if (bitmap.width != SlippyTile.TILE_SIZE || bitmap.height != SlippyTile.TILE_SIZE) {
        return null
      }
      IntArray(SlippyTile.TILE_SIZE * SlippyTile.TILE_SIZE).also { pixels ->
        bitmap.getPixels(
          pixels,
          0,
          SlippyTile.TILE_SIZE,
          0,
          0,
          SlippyTile.TILE_SIZE,
          SlippyTile.TILE_SIZE,
        )
      }
    } finally {
      bitmap.recycle()
    }
  }

  private fun cacheFile(tile: TileCoordinate): File =
    File(cacheDirectory, "${tile.zoom}_${tile.x}_${tile.y}.png")

  private fun urlFor(tile: TileCoordinate): String =
    baseUrl
      .replace("{z}", tile.zoom.toString())
      .replace("{x}", tile.x.toString())
      .replace("{y}", tile.y.toString())

  companion object {
    private const val CACHE_DIRECTORY = "terrarium"
    private const val CONNECT_TIMEOUT_MILLIS = 8_000
    private const val READ_TIMEOUT_MILLIS = 10_000
    private const val USER_AGENT = "ThruHiker/0.1 (offline-first thru-hike planner)"
  }
}

/**
 * Decoded tiles, shared by every source in the process.
 *
 * Source instances are per planning pass — each needs its own staleness test — but the tiles
 * they read are not: a plan is revised over and over as waypoints are moved, and without a
 * cache that outlives the pass every revision would re-read and re-decode the same PNGs.
 *
 * Access-ordered, so the tiles a pass is walking through stay resident and the ones it has
 * moved past are evicted. Every entry is a quarter of a megabyte, which is why it is capped.
 */
private object MemoryCache {

  private const val MAXIMUM_TILES = 64

  private val tiles = object : LinkedHashMap<String, IntArray>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, IntArray>): Boolean =
      size > MAXIMUM_TILES
  }

  operator fun get(key: String): IntArray? = synchronized(tiles) { tiles[key] }

  operator fun set(key: String, pixels: IntArray) {
    synchronized(tiles) { tiles[key] = pixels }
  }
}
