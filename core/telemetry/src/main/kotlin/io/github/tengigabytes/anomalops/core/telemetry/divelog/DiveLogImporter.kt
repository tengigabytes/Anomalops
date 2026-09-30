// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.divelog

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/**
 * FR-44 / ADR-0008 `TelemetryImporter`: reads the dives of a UDDF or Subsurface XML export (requirements
 * section 8: other formats go through Subsurface first). The format is told by the root element.
 */
object DiveLogImporter {
    fun read(input: InputStream): List<DiveProfile> =
        parse(input).delegate?.dives ?: throw DiveLogFormatException("empty log")

    private fun parse(input: InputStream): RootDispatch {
        val handler = RootDispatch()
        try {
            parser().parse(input, handler)
        } catch (e: SAXException) {
            throw e.exception as? DiveLogFormatException ?: DiveLogFormatException("not a readable XML log", e)
        } catch (e: IOException) {
            throw DiveLogFormatException("log could not be read", e)
        }
        return handler
    }

    private fun parser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        // A log comes from outside the app: no DTDs or external entities.
        // UNVERIFIED(G1): which of these features Android's parser accepts; unsupported ones are skipped.
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, value) ->
            try {
                setFeature(feature, value)
            } catch (_: ParserConfigurationException) {
                // Not supported by this parser.
            } catch (_: SAXException) {
                // Not recognised by this parser.
            }
        }
    }.newSAXParser()

    /** Picks the reader on the root element and passes every event on to it. */
    private class RootDispatch : DefaultHandler() {
        var delegate: LogHandler? = null

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val handler = delegate ?: when (localName?.ifEmpty { null } ?: qName) {
                "uddf" -> UddfHandler()
                "divelog", "dives" -> SubsurfaceHandler()
                else -> throw DiveLogFormatException("root element '$qName' is neither UDDF nor Subsurface")
            }.also { delegate = it }
            handler.startElement(uri, localName, qName, attributes)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            delegate?.endElement(uri, localName, qName)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            delegate?.characters(ch, start, length)
        }
    }
}
