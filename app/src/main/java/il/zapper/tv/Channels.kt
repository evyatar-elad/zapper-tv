package il.zapper.tv

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Source(
    val type: String,          // "hls" = כתובת קבועה, "keshet" = כתובת עם כרטיס מ-mako
    val url: String? = null,
    val referer: String? = null,
    val path: String? = null,  // keshet: הנתיב בשרת של mako
    val cdn: String? = null,
    val host: String? = null,
)

data class Channel(val number: Int, val name: String, val sources: List<Source>) {
    val label get() = "$number  $name"
}

object Lineup {
    // הרשימה העדכנית מהריפו. אם אין רשת או שהריפו פרטי: הרשימה השמורה, ואז זו שבתוך האפליקציה.
    const val REMOTE_URL = "https://raw.githubusercontent.com/evyatar-elad/zapper-tv/main/lineup/channels.json"
    private const val CACHE = "channels.json"

    fun parse(text: String): List<Channel> {
        val arr = JSONObject(text).getJSONArray("channels")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val src = c.getJSONArray("sources")
            Channel(
                number = c.getInt("number"),
                name = c.getString("name"),
                sources = (0 until src.length()).map { j ->
                    val s = src.getJSONObject(j)
                    Source(
                        type = s.optString("type", "hls"),
                        url = s.optString("url").ifEmpty { null },
                        referer = s.optString("referer").ifEmpty { null },
                        path = s.optString("path").ifEmpty { null },
                        cdn = s.optString("cdn").ifEmpty { null },
                        host = s.optString("host").ifEmpty { null },
                    )
                },
            )
        }.filter { it.sources.isNotEmpty() }.sortedBy { it.number }
    }

    /** טעינה מקומית ומהירה: עותק שמור מהרשת, ואם אין - הרשימה שבתוך האפליקציה. */
    fun loadLocal(ctx: Context): List<Channel> {
        val cache = File(ctx.filesDir, CACHE)
        if (cache.exists()) {
            runCatching { parse(cache.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return parse(ctx.assets.open(CACHE).bufferedReader().use { it.readText() })
    }

    /** מוריד את הרשימה מהריפו ושומר. מחזיר null אם נכשל. רץ ברקע. */
    fun fetchRemote(ctx: Context): List<Channel>? = runCatching {
        val text = Http.get(REMOTE_URL)
        val list = parse(text)
        if (list.isEmpty()) return null
        File(ctx.filesDir, CACHE).writeText(text)
        list
    }.getOrNull()
}

object Http {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", UA)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw StreamException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

class StreamException(msg: String) : Exception(msg)
