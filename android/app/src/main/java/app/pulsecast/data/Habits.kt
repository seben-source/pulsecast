package app.pulsecast.data

import android.content.Context
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.min
import org.json.JSONObject

/**
 * What this reader has saved, already seen, and tends to read.
 * Stays on the phone. Used to rank "For you" and to show "You're caught up".
 */
class Habits(context: Context) {
    private val prefs = context.getSharedPreferences("habits", Context.MODE_PRIVATE)

    var saved: Set<String>
        get() = prefs.getStringSet("saved", emptySet())!!.toSet()
        private set(v) = prefs.edit().putStringSet("saved", v).apply()

    fun toggleSave(card: Card): Boolean {
        val now = card.id !in saved
        saved = if (now) saved + card.id else saved - card.id
        if (now) noteInterest(card.topic, 3.0)
        return now
    }

    /** Topic -> interest score, built from opens, saves and shares. */
    fun interests(): Map<String, Double> {
        val o = JSONObject(prefs.getString("interests", "{}")!!)
        return o.keys().asSequence().associateWith { o.optDouble(it, 0.0) }
    }

    fun noteInterest(topic: String, weight: Double) {
        val o = JSONObject(prefs.getString("interests", "{}")!!)
        // Older interest fades a little each time, so the feed follows what you read lately.
        val keys = o.keys().asSequence().toList()
        for (k in keys) o.put(k, o.optDouble(k) * 0.97)
        o.put(topic, o.optDouble(topic, 0.0) + weight)
        prefs.edit().putString("interests", o.toString()).apply()
    }

    /** Newest publish time the reader has scrolled past, for "new since last visit". */
    var lastVisitNewest: Instant
        get() = Instant.ofEpochMilli(prefs.getLong("lastNewest", 0))
        set(v) = prefs.edit().putLong("lastNewest", v.toEpochMilli()).apply()

    var briefingEnabled: Boolean
        get() = prefs.getBoolean("briefing", true)
        set(v) = prefs.edit().putBoolean("briefing", v).apply()

    var breakingEnabled: Boolean
        get() = prefs.getBoolean("breaking", true)
        set(v) = prefs.edit().putBoolean("breaking", v).apply()

    var lastBreakingId: String?
        get() = prefs.getString("lastBreaking", null)
        set(v) = prefs.edit().putString("lastBreaking", v).apply()

    var lastBriefingDay: String?
        get() = prefs.getString("lastBriefingDay", null)
        set(v) = prefs.edit().putString("lastBriefingDay", v).apply()
}

object Ranking {
    /** "For you": how hot a story is, fading with age, nudged towards topics you read. */
    fun forYou(cards: List<Card>, interests: Map<String, Double>, now: Instant = Instant.now()): List<Card> {
        val top = interests.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return cards.sortedByDescending { c ->
            val ageHours = Duration.between(c.publishedAt, now).toMinutes().coerceAtLeast(0) / 60.0
            val freshness = exp(-ageHours / 30.0)
            val affinity = 1.0 + 0.35 * min(1.0, (interests[c.topic] ?: 0.0) / top)
            (c.heat + 10) * freshness * affinity
        }
    }

    fun latest(cards: List<Card>) = cards.sortedByDescending { it.publishedAt }
}
