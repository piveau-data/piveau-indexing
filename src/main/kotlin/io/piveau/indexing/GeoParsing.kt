package io.piveau.indexing

import io.vertx.core.json.JsonObject
import org.apache.jena.rdf.model.Literal
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.ParseException
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.geojson.GeoJsonWriter
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

fun parseGeoLiteral(value: Literal): JsonObject = when (value.datatypeURI) {
    "https://www.iana.org/assignments/media-types/application/vnd.geo+json" -> JsonObject(value.string)
    "http://www.openlinksw.com/schemas/virtrdf#Geometry" -> try {
        val geometry = WKTReader().read(value.string)
        JsonObject(GeoJsonWriter().apply {
            setEncodeCRS(false)
        }.write(geometry))
    } catch (e: ParseException) {
        // ??? what to do here
        // we should add at least a log message
        JsonObject()
    }
    "http://www.opengis.net/ont/geosparql#gmlLiteral" -> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val document = builder.parse(InputSource(StringReader(value.string)))
        val xPath = XPathFactory.newInstance().newXPath()
        val node1 = (xPath.evaluate(
            "/Envelope/upperCorner/text()",
            document,
            XPathConstants.NODE
        ) as Node).nodeValue
        val node2 = (xPath.evaluate(
            "/Envelope/lowerCorner/text()",
            document,
            XPathConstants.NODE
        ) as Node).nodeValue
        val node1Splitted =
            node1.trim().split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val node2Splitted =
            node2.trim().split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        /**
         * Latitude - Second Parameter - Y
         * Longitude - First Parameter - X
         */
        if (node1Splitted.size > 1 && node2Splitted.size > 1) {
            val envelope = Envelope(
                Coordinate(node2Splitted[1].toDouble(), node2Splitted[0].toDouble()),
                Coordinate(node1Splitted[1].toDouble(), node1Splitted[0].toDouble())
            )
            val geometry = GeometryFactory().toGeometry(envelope)
            JsonObject(GeoJsonWriter().apply {
                setEncodeCRS(false)
            }.write(geometry))
        } else {
            JsonObject()
        }
    }
    else -> JsonObject()
}
