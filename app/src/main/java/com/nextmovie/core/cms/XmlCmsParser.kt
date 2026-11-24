package com.nextmovie.core.cms

import com.nextmovie.core.model.*
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** CMS XML details carry episodes inside dl/dd, not JSON vod_play_url fields. */
object XmlCmsParser {
    fun details(xml: String): List<VodDetail> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        }
        val doc = builder.parse(ByteArrayInputStream(xml.toByteArray()))
        val videos = doc.getElementsByTagName("video")
        return (0 until videos.length).mapNotNull { index ->
            val element = videos.item(index) as? Element ?: return@mapNotNull null
            fun tag(vararg names: String): String = names.firstNotNullOfOrNull { name ->
                element.getElementsByTagName(name).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
            }.orEmpty()
            val name = tag("vod_name", "name")
            if (name.isBlank()) return@mapNotNull null
            val vod = Vod(id = tag("vod_id", "id").ifBlank { name }, name = name,
                pic = tag("vod_pic", "pic"), remark = tag("vod_remarks", "note"),
                year = tag("vod_year", "year"), area = tag("vod_area", "area"),
                typeName = tag("vod_class", "type"), actor = tag("vod_actor", "actor"),
                director = tag("vod_director", "director"), content = tag("vod_content", "des"))
            val dd = element.getElementsByTagName("dd")
            val lines = (0 until dd.length).mapNotNull { i ->
                val entry = dd.item(i) as? Element ?: return@mapNotNull null
                val episodes = entry.textContent.trim().split('#').mapNotNull { raw ->
                    if (raw.isBlank()) return@mapNotNull null
                    val parts = raw.split('$', limit = 2)
                    val url = parts.last().trim()
                    if (url.isBlank()) null else Episode(if (parts.size == 2) parts.first().ifBlank { "正片" } else "正片", url)
                }
                if (episodes.isEmpty()) null else PlayLine(entry.getAttribute("flag").ifBlank { "线路${i + 1}" }, episodes)
            }
            VodDetail(vod, lines)
        }
    }
}
