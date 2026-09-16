package com.thruhiker.core.gpx

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads GPX 1.0 and 1.1.
 *
 * Two deliberate choices:
 *
 * 1. **Structure is read by local element name, ignoring namespaces.** GPX 1.0 has
 *    no namespace and GPX 1.1 does, and files in the wild mix prefixes freely.
 *    Matching on the local name accepts both without a namespace map.
 * 2. **DTD processing and entity expansion are disabled.** GPX files are
 *    downloaded from strangers on the internet, and an XML parser that resolves
 *    external entities on request is how XML external entity attacks work.
 */
object GpxParser {

  fun parse(xml: String): GpxDocument = parse(xml.byteInputStream(Charsets.UTF_8))

  fun parse(stream: InputStream): GpxDocument {
    val document = try {
      newDocumentBuilder().parse(stream)
    } catch (exception: GpxParseException) {
      throw exception
    } catch (exception: Exception) {
      throw GpxParseException("Could not read GPX XML: ${exception.message}", exception)
    }

    val root = document.documentElement
      ?: throw GpxParseException("GPX document has no root element")

    if (!root.localNameOrNodeName().equals("gpx", ignoreCase = true)) {
      throw GpxParseException(
        "Expected a <gpx> root element but found <${root.localNameOrNodeName()}>",
      )
    }

    val waypoints = root.childrenNamed("wpt").mapNotNull { element ->
      val point = element.toTrackPoint() ?: return@mapNotNull null
      GpxWaypoint(name = element.textOf("name"), point = point)
    }

    // Routes are plans, not recordings, but every consumer downstream wants
    // geometry, so they are folded in as extra segments.
    val routeSegments = root.childrenNamed("rte").mapNotNull { route ->
      val points = route.childrenNamed("rtept").mapNotNull { it.toTrackPoint() }
      if (points.isEmpty()) null else TrackSegment(points)
    }

    val trackSegments = ArrayList<TrackSegment>()
    var trackName: String? = null

    for (trackElement in root.childrenNamed("trk")) {
      if (trackName == null) trackName = trackElement.textOf("name")

      for (segmentElement in trackElement.childrenNamed("trkseg")) {
        val points = segmentElement.childrenNamed("trkpt").mapNotNull { it.toTrackPoint() }
        if (points.isNotEmpty()) trackSegments.add(TrackSegment(points))
      }

      // Some exporters put <trkpt> straight under <trk>, which the schema forbids.
      // Keep the data rather than discarding it on a technicality.
      val loose = trackElement.childrenNamed("trkpt").mapNotNull { it.toTrackPoint() }
      if (loose.isNotEmpty()) trackSegments.add(TrackSegment(loose))
    }

    trackSegments.addAll(routeSegments)

    val metadata = root.firstChildNamed("metadata")
    val name = metadata?.textOf("name")
      ?: trackName
      ?: root.textOf("name")
    val description = metadata?.textOf("desc") ?: root.textOf("desc")

    return GpxDocument(
      name = name,
      description = description,
      track = Track(trackSegments),
      waypoints = waypoints,
    )
  }

  private fun newDocumentBuilder(): DocumentBuilder {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true

    // Hardening. Features are set defensively because not every parser
    // implementation supports every one of them.
    factory.setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setFeatureIfSupported(
      "http://apache.org/xml/features/nonvalidating/load-external-dtd",
      false,
    )
    runCatching { factory.isXIncludeAware = false }
    runCatching { factory.isExpandEntityReferences = false }

    return factory.newDocumentBuilder()
  }
}

private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
  runCatching { setFeature(name, value) }
}

private fun Node.localNameOrNodeName(): String = localName ?: nodeName

private fun Element.childElements(): List<Element> {
  val nodes = childNodes
  val elements = ArrayList<Element>(nodes.length)
  for (index in 0 until nodes.length) {
    val node = nodes.item(index)
    if (node.nodeType == Node.ELEMENT_NODE) elements.add(node as Element)
  }
  return elements
}

private fun Element.childrenNamed(localName: String): List<Element> =
  childElements().filter { it.localNameOrNodeName().equals(localName, ignoreCase = true) }

private fun Element.firstChildNamed(localName: String): Element? =
  childElements().firstOrNull { it.localNameOrNodeName().equals(localName, ignoreCase = true) }

private fun Element.textOf(localName: String): String? =
  firstChildNamed(localName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Reads a `<trkpt>`, `<rtept>` or `<wpt>`.
 *
 * Returns null for a point with no usable coordinates rather than throwing: one
 * malformed sample in a 100,000-point file should not cost the whole file.
 */
private fun Element.toTrackPoint(): TrackPoint? {
  val latitude = getAttribute("lat").toDoubleOrNull() ?: return null
  val longitude = getAttribute("lon").toDoubleOrNull() ?: return null

  val position = LatLng(latitude, longitude)
  if (!position.isValid) return null

  return TrackPoint(
    position = position,
    elevationMeters = textOf("ele")?.toDoubleOrNull(),
    timeMillis = GpxTime.parseMillis(textOf("time")),
  )
}
