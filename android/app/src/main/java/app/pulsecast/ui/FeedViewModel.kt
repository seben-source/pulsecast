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
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String) { FOR_YOU("For you"), LATEST("Latest"), SAVED("Saved") }

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
        ),
    )
    val state: StateFlow<FeedState> = _state

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { repo.cached() }
            if (cached != null) _state.update { it.copy(cards = cached.cards, loaded = true) }
            refresh()
        }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            try {
                val feed = withContext(Dispatchers.IO) { repo.refresh() }
                _state.update { it.copy(cards = feed.cards, loaded = true, refreshing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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

    fun setTab(tab: Tab) = _state.update { it.copy(tab = tab) }
    fun setTopic(topic: String?) = _state.update { it.copy(topic = topic) }

    fun opened(card: Card) = habits.noteInterest(card.topic, 1.0)
    fun shared(card: Card) = habits.noteInterest(card.topic, 2.0)

    fun toggleSave(card: Card) {
        habits.toggleSave(card)
        _state.update { it.copy(saved = habits.saved) }
    }

    fun setBriefing(on: Boolean) {
        habits.briefingEnabled = on
        _state.update { it.copy(briefing = on) }
    }

    fun setBreaking(on: Boolean) {
        habits.breakingEnabled = on
        _state.update { it.copy(breaking = on) }
    }

    /** Called when the app is closed: next visit shows a "caught up" marker here. */
    fun markVisited() {
        val newest = _state.value.cards.maxOfOrNull { it.publishedAt } ?: return
        habits.lastVisitNewest = newest
    }

    fun topics(): List<String> = _state.value.cards.groupingBy { it.topic }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    fun rows(s: FeedState): List<Row> {
        var cards = s.cards
        if (s.topic != null) cards = cards.filter { it.topic == s.topic }
        cards = when (s.tab) {
            Tab.FOR_YOU -> Ranking.forYou(cards, habits.interests())
            Tab.LATEST -> Ranking.latest(cards)
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
