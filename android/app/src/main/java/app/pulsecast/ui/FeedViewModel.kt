package app.pulsecast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pulsecast.BuildConfig
import app.pulsecast.ads.NativeAdPool
import app.pulsecast.data.Card
import app.pulsecast.data.FeedRepository
import app.pulsecast.data.Habits
import app.pulsecast.data.Ranking
import app.pulsecast.stats.Stats
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String) { FOR_YOU("For you"), LATEST("Latest"), TOP("Top"), SAVED("Saved") }

sealed interface Row {
    val key: String
    data class Post(val card: Card) : Row { override val key = card.id }
    data class Ad(val slot: Int) : Row { override val key = "ad-$slot" }
    data class CaughtUp(val newCount: Int) : Row { override val key = "caught-up" }
}

data class FeedState(
    val cards: List<Card> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val tab: Tab = Tab.FOR_YOU,
    val topic: String? = null,
    val saved: Set<String> = emptySet(),
    val lastVisitNewest: Instant = Instant.EPOCH,
    val adsVersion: Int = 0,
    val briefing: Boolean = true,
    val breaking: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val query: String = "",
    val searching: Boolean = false,
    /** When the news engine last published the feed. */
    val generatedAt: Instant? = null,
)

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FeedRepository(app, BuildConfig.FEED_URL)
    private val habits = Habits(app)
    val ads = NativeAdPool(app) { _state.update { it.copy(adsVersion = it.adsVersion + 1) } }

    private val _state = MutableStateFlow(
        FeedState(
            saved = habits.saved,
            lastVisitNewest = habits.lastVisitNewest,
            briefing = habits.briefingEnabled,
            breaking = habits.breakingEnabled,
            themeMode = runCatching { ThemeMode.valueOf(habits.themeMode) }.getOrDefault(ThemeMode.SYSTEM),
        ),
    )
    val state: StateFlow<FeedState> = _state

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { repo.cached() }
            if (cached != null) _state.update { it.copy(cards = cached.cards, loaded = true, generatedAt = cached.generatedAt) }
            refresh()
        }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            try {
                val feed = withContext(Dispatchers.IO) { repo.refresh() }
                _state.update { it.copy(cards = feed.cards, loaded = true, refreshing = false, generatedAt = feed.generatedAt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Stats.problem(e)
                _state.update {
                    it.copy(
                        loaded = true,
                        refreshing = false,
                        error = if (it.cards.isEmpty()) "Couldn't load the feed. Pull down to try again." else "Offline: showing saved stories.",
                    )
                }
            }
        }
    }

    fun adsReady() = ads.start()

    fun setTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        Stats.event("tab_view", "tab" to tab.name.lowercase())
    }

    fun setTopic(topic: String?) {
        _state.update { it.copy(topic = topic) }
        if (topic != null) Stats.event("topic_filter", "topic" to topic)
    }

    fun setSearching(on: Boolean) = _state.update { it.copy(searching = on, query = if (on) it.query else "") }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun searched() {
        val q = _state.value.query.trim()
        if (q.length >= 2) Stats.event("search", "length" to q.length)
    }

    fun opened(card: Card) {
        habits.noteInterest(card.topic, 1.0)
        Stats.event("card_open", "topic" to card.topic, "type" to card.type, "source" to card.source.name)
    }

    fun shared(card: Card) {
        habits.noteInterest(card.topic, 2.0)
        Stats.event("card_share", "topic" to card.topic)
    }

    fun readSource(card: Card) = Stats.event("read_source", "source" to card.source.name)

    fun toggleSave(card: Card) {
        val saved = habits.toggleSave(card)
        _state.update { it.copy(saved = habits.saved) }
        if (saved) Stats.event("card_save", "topic" to card.topic)
    }

    private var deepest = 0

    /** How far down the feed the reader got this visit. */
    fun seen(index: Int) {
        if (index > deepest) deepest = index
    }

    fun setBriefing(on: Boolean) {
        habits.briefingEnabled = on
        _state.update { it.copy(briefing = on) }
    }

    fun setThemeMode(mode: ThemeMode) {
        habits.themeMode = mode.name
        _state.update { it.copy(themeMode = mode) }
    }

    fun setBreaking(on: Boolean) {
        habits.breakingEnabled = on
        _state.update { it.copy(breaking = on) }
    }

    /** Called when the app is closed: next visit shows a "caught up" marker here. */
    fun markVisited() {
        if (deepest > 0) Stats.event("session_depth", "items_seen" to deepest + 1)
        deepest = 0
        val newest = _state.value.cards.maxOfOrNull { it.publishedAt } ?: return
        habits.lastVisitNewest = newest
    }

    fun topics(): List<String> = _state.value.cards.groupingBy { it.topic }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    fun rows(s: FeedState): List<Row> {
        var cards = s.cards
        val words = s.query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (s.searching && words.isNotEmpty()) {
            // Search covers every story, newest first, whatever tab is open.
            cards = Ranking.latest(cards.filter { c ->
                val text = "${c.headline} ${c.summary} ${c.whyItMatters} ${c.keyPoints.joinToString(" ")} ${c.source.name} ${c.topic}".lowercase()
                words.all { it in text }
            })
            val out = ArrayList<Row>()
            cards.forEachIndexed { i, card ->
                out.add(Row.Post(card))
                if ((i + 1) % AD_EVERY == 0) out.add(Row.Ad(i / AD_EVERY))
            }
            return out
        }
        if (s.topic != null) cards = cards.filter { it.topic == s.topic }
        cards = when (s.tab) {
            Tab.FOR_YOU -> Ranking.forYou(cards, habits.interests())
            Tab.LATEST -> Ranking.latest(cards)
            Tab.TOP -> Ranking.top(cards)
            Tab.SAVED -> Ranking.latest(cards.filter { it.id in s.saved })
        }
        val out = ArrayList<Row>()
        val showCaughtUp = s.tab == Tab.LATEST && s.lastVisitNewest != Instant.EPOCH
        val newCount = if (showCaughtUp) cards.count { it.publishedAt.isAfter(s.lastVisitNewest) } else 0
        var caughtUpPlaced = false
        var slot = 0
        cards.forEachIndexed { i, card ->
            if (showCaughtUp && !caughtUpPlaced && newCount > 0 && !card.publishedAt.isAfter(s.lastVisitNewest)) {
                out.add(Row.CaughtUp(newCount))
                caughtUpPlaced = true
            }
            out.add(Row.Post(card))
            // A sponsored post after every 6 stories, never in Saved.
            if (s.tab != Tab.SAVED && (i + 1) % AD_EVERY == 0) out.add(Row.Ad(slot++))
        }
        return out
    }

    override fun onCleared() {
        ads.destroy()
    }

    companion object {
        const val AD_EVERY = 6
    }
}
