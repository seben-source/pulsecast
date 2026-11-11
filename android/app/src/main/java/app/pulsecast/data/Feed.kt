package app.pulsecast.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class Link(val name: String, val url: String)

data class Card(
    val id: String,
    /** news, release, research or tool */
    val type: String,
    val topic: String,
    val headline: String,
    val summary: String,
    val whyItMatters: String,
    val keyPoints: List<String>,
    val context: String,
    val whatNext: String,
    val source: Link,
    val alsoCoveredBy: List<Link>,
    val discussionUrl: String?,
    val publishedAt: Instant,
    val heat: Int,
)

data class Feed(val generatedAt: Instant, val cards: List<Card>)

object FeedParser {
    fun parse(text: String): Feed {
        val root = JSONObject(text)
        val arr = root.optJSONArray("cards") ?: JSONArray()
        val cards = ArrayList<Card>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { cards.add(card(o)) }
        }
        return Feed(instant(root.optString("generatedAt")) ?: Instant.EPOCH, cards)
    }

    private fun card(o: JSONObject): Card {
        val src = o.getJSONObject("source")
        return Card(
            id = o.getString("id"),
            type = o.optString("type", "news"),
            topic = o.optString("topic", "Business"),
            headline = o.getString("headline"),
            summary = o.getString("summary"),
            whyItMatters = o.optString("whyItMatters"),
            keyPoints = strings(o.optJSONArray("keyPoints")),
            context = o.optString("context"),
            whatNext = o.optString("whatNext"),
            source = Link(src.optString("name"), src.getString("url")),
            alsoCoveredBy = links(o.optJSONArray("alsoCoveredBy")),
            discussionUrl = o.optString("discussionUrl").takeIf { it.startsWith("http") },
            publishedAt = instant(o.optString("publishedAt")) ?: Instant.now(),
            heat = o.optInt("heat", 30),
        )
    }

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optString(it).takeIf(String::isNotBlank) }

    private fun links(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        a!!.optJSONObject(i)?.let { Link(it.optString("name"), it.optString("url")) }?.takeIf { it.url.startsWith("http") }
    }

    private fun instant(s: String?): Instant? = try { if (s.isNullOrBlank()) null else Instant.parse(s) } catch (_: Exception) { null }
}

/** Downloads the feed (with caching) and keeps a copy for offline use. */
class FeedRepository(context: Context, private val url: String) {
    private val cacheFile = File(context.filesDir, "feed.json")
    private val prefs = context.getSharedPreferences("feed", Context.MODE_PRIVATE)

    fun cached(): Feed? = try {
        if (cacheFile.exists()) FeedParser.parse(cacheFile.readText()) else null
    } catch (_: Exception) {
        null
    }

    /** Fetches the latest feed. Returns the cached one if nothing changed. */
    @Throws(IOException::class)
    fun refresh(): Feed {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            prefs.getString("etag", null)?.takeIf { cacheFile.exists() }?.let { conn.setRequestProperty("If-None-Match", it) }
            when (val code = conn.responseCode) {
                304 -> return cached() ?: throw IOException("Cache missing")
                200 -> {
                    val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val feed = FeedParser.parse(text) // validate before replacing the cache
                    cacheFile.writeText(text)
                    prefs.edit().putString("etag", conn.getHeaderField("ETag")).apply()
                    return feed
                }
                else -> throw IOException("Feed returned HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
