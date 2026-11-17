package app.pulsecast.data

import android.content.Context
import app.pulsecast.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Where the engine publishes: the folder that holds feed.json. */
val SITE_BASE: String = BuildConfig.FEED_URL.substringBeforeLast('/')

/**
 * Downloads a JSON file from the engine's site with ETag caching, keeping a
 * copy for offline use. [maxAgeMs] skips the network if the copy is fresher.
 */
class CachedJson(context: Context, private val name: String) {
    private val file = File(context.filesDir, "cache-$name")
    private val prefs = context.getSharedPreferences("cache-etags", Context.MODE_PRIVATE)

    fun cached(): String? = if (file.exists()) file.readText() else null

    @Throws(IOException::class)
    fun get(url: String, maxAgeMs: Long = 0): String {
        if (maxAgeMs > 0 && file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs) return file.readText()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            prefs.getString(name, null)?.takeIf { file.exists() }?.let { conn.setRequestProperty("If-None-Match", it) }
            return when (val code = conn.responseCode) {
                304 -> file.readText().also { file.setLastModified(System.currentTimeMillis()) }
                200 -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also {
                    JSONObject(it) // validate before replacing the copy
                    file.writeText(it)
                    prefs.edit().putString(name, conn.getHeaderField("ETag")).apply()
                }
                404 -> throw IOException("Not published yet")
                else -> throw IOException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}

// ---------- Weekly deep-dive ----------

data class IssueSection(val heading: String, val body: String, val cardIds: List<String>)
data class IssueCard(val id: String, val headline: String, val source: Link, val image: String?)
data class Issue(
    val weekOf: String,
    val publishedAt: Instant,
    val title: String,
    val intro: String,
    val sections: List<IssueSection>,
    val takeaway: String,
    val cards: List<IssueCard>,
)

class WeeklyRepository(context: Context) {
    private val json = CachedJson(context, "weekly.json")

    fun cached(): List<Issue> = json.cached()?.let { runCatching { parse(it) }.getOrNull() } ?: emptyList()

    @Throws(IOException::class)
    fun refresh(): List<Issue> = parse(json.get("$SITE_BASE/weekly.json"))

    private fun parse(text: String): List<Issue> {
        val arr = JSONObject(text).optJSONArray("issues") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                Issue(
                    weekOf = o.getString("weekOf"),
                    publishedAt = Instant.parse(o.getString("publishedAt")),
                    title = o.getString("title"),
                    intro = o.optString("intro"),
                    sections = o.optJSONArray("sections").objects().map { s ->
                        IssueSection(s.optString("heading"), s.optString("body"), s.optJSONArray("cardIds").strings())
                    },
                    takeaway = o.optString("takeaway"),
                    cards = o.optJSONArray("cards").objects().mapNotNull { c ->
                        val src = c.optJSONObject("source") ?: return@mapNotNull null
                        IssueCard(c.optString("id"), c.optString("headline"), Link(src.optString("name"), src.optString("url")), c.optString("image").takeIf { it.startsWith("https://") })
                    },
                )
            }.getOrNull()
        }
    }
}

// ---------- Full archive ----------

/** Every card Prompt has published, one file per month. Downloaded on the first Pro search. */
class ArchiveRepository(private val context: Context) {
    private val index = CachedJson(context, "archive-index.json")
    private val loaded = HashMap<String, List<Card>>()

    @Synchronized
    @Throws(IOException::class)
    fun all(): List<Card> {
        val idx = JSONObject(index.get("$SITE_BASE/archive/index.json", maxAgeMs = 30 * 60_000))
        val months = idx.optJSONArray("months").objects().map { it.optString("month") }.filter { Regex("\\d{4}-\\d{2}").matches(it) }
        val current = months.firstOrNull()
        val out = ArrayList<Card>()
        for (m in months) {
            val cards = loaded[m] ?: run {
                val file = CachedJson(context, "archive-$m.json")
                // Past months don't change; the latest month is re-checked every half hour.
                val text = try {
                    file.get("$SITE_BASE/archive/$m.json", maxAgeMs = if (m == current) 30 * 60_000 else Long.MAX_VALUE)
                } catch (e: IOException) {
                    file.cached() ?: throw e
                }
                FeedParser.parse(text).cards.also { if (m != current) loaded[m] = it }
            }
            out.addAll(cards)
        }
        return out
    }
}

// ---------- Ask about this story ----------

data class Turn(val q: String, val a: String)

object AskClient {
    val configured: Boolean get() = BuildConfig.ASK_URL.startsWith("https://")

    /** Returns the answer, or throws with a message that can be shown to the reader. */
    @Throws(IOException::class)
    fun ask(card: Card, question: String, history: List<Turn>, installId: String): String {
        val body = JSONObject()
            .put("url", card.source.url)
            .put("month", card.publishedAt.toString().take(7))
            .put("question", question)
            .put("installId", installId)
            .put("history", JSONArray(history.takeLast(4).map { JSONObject().put("q", it.q).put("a", it.a) }))
        val conn = URL(BuildConfig.ASK_URL.trimEnd('/') + "/ask").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-prompt-key", BuildConfig.ASK_KEY)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val o = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299) return o?.optString("answer")?.takeIf { it.isNotBlank() } ?: throw IOException("Couldn't get an answer. Try again.")
            throw IOException(o?.optString("error")?.takeIf { it.isNotBlank() } ?: "Couldn't get an answer. Try again.")
        } finally {
            conn.disconnect()
        }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this!!.optJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = (0 until (this?.length() ?: 0)).mapNotNull { this!!.optString(it).takeIf(String::isNotBlank) }
