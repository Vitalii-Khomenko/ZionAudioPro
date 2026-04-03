package com.aiproject.musicplayer

import java.net.URL

internal object DlnaProtocol {

    fun buildBrowseEnvelope(objectId: String): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <ObjectID>${xmlEscape(objectId)}</ObjectID>
      <BrowseFlag>BrowseDirectChildren</BrowseFlag>
      <Filter>dc:title,res,res@duration,upnp:class</Filter>
      <StartingIndex>0</StartingIndex>
      <RequestedCount>500</RequestedCount>
      <SortCriteria/>
    </u:Browse>
  </s:Body>
</s:Envelope>"""

    fun resolveControlUrl(location: String, xml: String): String? {
        val cdIdx = xml.indexOf("ContentDirectory").takeIf { it >= 0 } ?: return null
        val start = xml.indexOf("<controlURL>", cdIdx).takeIf { it >= 0 } ?: return null
        val end = xml.indexOf("</controlURL>", start).takeIf { it >= 0 } ?: return null
        val path = xml.substring(start + "<controlURL>".length, end).trim()
        return try {
            URL(URL(location), path).toString()
        } catch (_: Exception) {
            null
        }
    }

    fun parseBrowse(soapXml: String): List<DlnaTrack> {
        val resultStart = soapXml.indexOf("<Result>").takeIf { it >= 0 } ?: return emptyList()
        val resultEnd = soapXml.indexOf("</Result>", resultStart).takeIf { it >= 0 } ?: return emptyList()
        val didl = soapXml.substring(resultStart + "<Result>".length, resultEnd)
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

        val tracks = mutableListOf<DlnaTrack>()
        var pos = 0
        while (true) {
            val itemStart = didl.indexOf("<item", pos).takeIf { it >= 0 } ?: break
            val itemEnd = didl.indexOf("</item>", itemStart).takeIf { it >= 0 } ?: break
            val item = didl.substring(itemStart, itemEnd + "</item>".length)
            pos = itemEnd + "</item>".length

            val title = xmlTag(item, "dc:title") ?: xmlTag(item, "title") ?: continue
            val url = firstHttpResUrl(item) ?: continue
            tracks += DlnaTrack(title = title, url = url, durationMs = parseDurationMs(item))
        }
        return tracks
    }

    private fun xmlTag(xml: String, tag: String): String? {
        val start = xml.indexOf("<$tag>").takeIf { it >= 0 } ?: return null
        val end = xml.indexOf("</$tag>", start).takeIf { it >= 0 } ?: return null
        return xml.substring(start + tag.length + 2, end).trim()
    }

    private fun firstHttpResUrl(item: String): String? {
        var resSearch = 0
        while (true) {
            val resOpen = item.indexOf("<res", resSearch).takeIf { it >= 0 } ?: return null
            val tagEnd = item.indexOf('>', resOpen).takeIf { it >= 0 } ?: return null
            val resClose = item.indexOf("</res>", tagEnd).takeIf { it >= 0 } ?: return null
            val candidate = item.substring(tagEnd + 1, resClose).trim()
            if (candidate.startsWith("http", ignoreCase = true)) {
                return candidate
            }
            resSearch = resClose + "</res>".length
        }
    }

    private fun parseDurationMs(item: String): Long {
        val match = Regex("duration=\"(\\d+):(\\d+):(\\d+)(?:\\.(\\d{1,3}))?\"").find(item) ?: return 0L
        val (hours, minutes, seconds, millisRaw) = match.destructured
        val millis = when {
            millisRaw.isEmpty() -> 0L
            millisRaw.length == 1 -> millisRaw.toLong() * 100L
            millisRaw.length == 2 -> millisRaw.toLong() * 10L
            else -> millisRaw.take(3).toLong()
        }
        return ((hours.toLong() * 3600L) + (minutes.toLong() * 60L) + seconds.toLong()) * 1000L + millis
    }

    private fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
