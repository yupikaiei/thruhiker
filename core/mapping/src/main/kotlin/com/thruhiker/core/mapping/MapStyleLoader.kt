package com.thruhiker.core.mapping

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a remote style document.
 *
 * Deliberately uses [HttpURLConnection] rather than pulling in an HTTP client:
 * this is one blocking request at startup, and MapLibre already brings its own
 * networking for tiles. Adding OkHttp here would mean two HTTP stacks in the app.
 *
 * Callers are responsible for being off the main thread.
 */
internal object MapStyleLoader {

  private const val CONNECT_TIMEOUT_MILLIS = 15_000
  private const val READ_TIMEOUT_MILLIS = 15_000

  @Throws(IOException::class)
  fun fetch(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = CONNECT_TIMEOUT_MILLIS
      readTimeout = READ_TIMEOUT_MILLIS
      requestMethod = "GET"
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", USER_AGENT)
    }

    return try {
      val code = connection.responseCode
      if (code !in 200..299) {
        throw IOException("Style request failed with HTTP $code for $url")
      }
      connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
      connection.disconnect()
    }
  }

  private const val USER_AGENT = "ThruHiker/0.1 (offline-first thru-hike planner)"
}
