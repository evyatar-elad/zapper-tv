package il.zapper.tv

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** הופך מקור ברשימה לכתובת שאפשר לנגן + כותרות. רץ ברקע (keshet פונה לרשת). */
object Resolver {
    data class Resolved(val url: String, val headers: Map<String, String>)

    fun resolve(s: Source): Resolved = when (s.type) {
        "keshet" -> keshet(s)
        else -> Resolved(
            s.url ?: throw StreamException("חסרה כתובת"),
            s.referer?.let { mapOf("Referer" to it) } ?: emptyMap(),
        )
    }

    // קשת: כמו הנגן באתר mako. מבקשים "כרטיס" לנתיב השידור החי (שידור חינמי, בלי התחברות),
    // ומצרפים אותו לכתובת. אם mako מבקשת התחברות - עוצרים, לא עוקפים.
    private fun keshet(s: Source): Resolved {
        val path = s.path ?: throw StreamException("חסר נתיב")
        val host = s.host ?: "https://mako-streaming.akamaized.net"
        val cdn = s.cdn ?: "AKAMAI"
        val q = "https://mass.mako.co.il/ClicksStatistics/entitlementsServicesV2.jsp?et=ngt&lp=" +
            URLEncoder.encode(path, "UTF-8") + "&rv=" + cdn
        val body = Http.get(q).trim().trimStart('﻿')
        if (body.startsWith("<")) throw StreamException("mako החזירה דף במקום תשובה")
        val j = JSONObject(body)
        when (val case = j.optString("caseId")) {
            "1" -> Unit
            "4" -> throw StreamException("קשת דורשת התחברות")
            else -> throw StreamException("קשת סירבה ($case)")
        }
        val ticket = j.optJSONArray("tickets")?.optJSONObject(0)?.optString("ticket").orEmpty()
        if (ticket.isEmpty()) throw StreamException("אין כרטיס")
        val sep = if ('?' in path) "&" else "?"
        return Resolved(host + path + sep + URLDecoder.decode(ticket, "UTF-8"), emptyMap())
    }
}
