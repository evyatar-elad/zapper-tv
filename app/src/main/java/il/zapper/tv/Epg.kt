package il.zapper.tv

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class Prog(val start: Long, val end: Long, val name: String, val desc: String) {
    fun isNow(now: Long) = now in start until end
}

/**
 * לוח שידורים: הקובץ הפתוח של פישנזון (המקור של Idan Plus ושל "מסך").
 * zip עם epg.json: {"12": [{"start": unix, "end": unix, "name": "...", "description": "..."}], ...}
 * מתעדכן פעם ביום, כ-5 ימים קדימה. גיבוי: השרת של "מסך" (אותו קובץ).
 */
object Epg {
    private val URLS = listOf(
        "https://raw.githubusercontent.com/fishenzon-epg/EPG/main/epg.json.zip",
        "https://masach-lineup.yev-gavrikov.workers.dev/v1/epg/il.zip",
    )
    private const val CACHE = "epg.zip"
    private const val MAX_AGE_MS = 6 * 3600_000L

    @Volatile private var data: Map<String, List<Prog>> = emptyMap()
    @Volatile private var loadedAt = 0L

    fun programs(key: String?): List<Prog> = key?.let { data[it] } ?: emptyList()

    fun now(key: String?, t: Long = System.currentTimeMillis() / 1000): Prog? =
        programs(key).firstOrNull { it.isNow(t) }

    /** טעינה מהעותק השמור, והורדה מחדש אם ישן. רץ ברקע. keys = רק הערוצים שלנו. */
    fun refresh(ctx: Context, keys: Set<String>) {
        val cache = File(ctx.filesDir, CACHE)
        if (data.isEmpty() && cache.exists()) runCatching { data = parse(cache.readBytes(), keys) }
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        if (fresh && data.isNotEmpty() && System.currentTimeMillis() - loadedAt < MAX_AGE_MS) return
        if (!fresh) {
            for (u in URLS) {
                val bytes = runCatching { download(u) }.getOrNull() ?: continue
                val parsed = runCatching { parse(bytes, keys) }.getOrNull() ?: continue
                if (parsed.isEmpty()) continue
                cache.writeBytes(bytes)
                data = parsed
                break
            }
        }
        loadedAt = System.currentTimeMillis()
    }

    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "zapper-tv")
        try {
            if (c.responseCode !in 200..299) throw StreamException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(zip: ByteArray, keys: Set<String>): Map<String, List<Prog>> {
        val json = ZipInputStream(zip.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null && !e.name.endsWith(".json")) e = z.nextEntry
            if (e == null) throw StreamException("אין json")
            z.readBytes().toString(Charsets.UTF_8)
        }
        val root = JSONObject(json)
        val out = HashMap<String, List<Prog>>()
        for (k in keys) {
            val arr = root.optJSONArray(k) ?: continue
            out[k] = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Prog(
                    start = o.optLong("start"),
                    end = o.optLong("end"),
                    name = o.optString("name").trim(),
                    desc = clean(o.optString("description")),
                )
            }.filter { it.end > it.start }.sortedBy { it.start }
        }
        return out
    }

    // התיאורים מסתיימים ב"(C) פישנזון" - מוצג פעם אחת בתחתית הלוח במקום בכל תכנית
    private fun clean(s: String) = s.replace("(C) פישנזון", "").replace(Regex("\\s+"), " ").trim()
}
