package com.aiproject.musicplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

data class DlnaServer(
    val friendlyName: String,
    val location: String,
    val controlUrl: String
)

data class DlnaTrack(
    val title: String,
    val url: String,
    val durationMs: Long = 0L
)

object DlnaDiscovery {

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /**
     * Send SSDP M-SEARCH and collect UPnP MediaServer responses.
     * Blocks for [timeoutSec] seconds on the IO dispatcher.
     */
    suspend fun discoverServers(timeoutSec: Int = 4): List<DlnaServer> =
        withContext(Dispatchers.IO) {
            val msearch = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"

            val locations = linkedSetOf<String>()
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = timeoutSec * 1000
                    val addr = InetAddress.getByName(SSDP_ADDR)
                    val bytes = msearch.toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(bytes, bytes.size, addr, SSDP_PORT))

                    val buf = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + timeoutSec * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                                .lineSequence()
                                .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                                ?.substringAfter(':')?.trim()
                                ?.also { locations += it }
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            locations.mapNotNull { loc ->
                try {
                    val xml = fetchText(loc)
                    val name = xmlTag(xml, "friendlyName") ?: "Unknown Server"
                    val ctrl = resolveControlUrl(loc, xml) ?: return@mapNotNull null
                    DlnaServer(name, loc, ctrl)
                } catch (_: Exception) {
                    null
                }
            }
        }

    /**
     * Browse a ContentDirectory container (default: root "0").
     * Returns audio items found in this container (up to 500).
     */
    suspend fun browse(server: DlnaServer, objectId: String = "0"): List<DlnaTrack> =
        withContext(Dispatchers.IO) {
            val soap = DlnaProtocol.buildBrowseEnvelope(objectId)
            try {
                val conn = (URL(server.controlUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                    setRequestProperty(
                        "SOAPAction",
                        "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
                    )
                    connectTimeout = 5_000
                    readTimeout = 15_000
                    doOutput = true
                }
                try {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(soap) }
                    DlnaProtocol.parseBrowse(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
        }
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Find the ContentDirectory:1 controlURL in device description XML
     * and resolve it against the [location] base URL.
     */
    private fun resolveControlUrl(location: String, xml: String): String? {
        return DlnaProtocol.resolveControlUrl(location, xml)
    }

    private fun xmlTag(xml: String, tag: String): String? {
        val start = xml.indexOf("<$tag>").takeIf { it >= 0 } ?: return null
        val end = xml.indexOf("</$tag>", start).takeIf { it >= 0 } ?: return null
        return xml.substring(start + tag.length + 2, end).trim()
    }
}
