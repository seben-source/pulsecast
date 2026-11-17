package app.pulsecast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pulsecast.BuildConfig
import app.pulsecast.ads.NativeAdPool
import app.pulsecast.data.ArchiveRepository
import app.pulsecast.data.AskClient
import app.pulsecast.data.Billing
import app.pulsecast.data.Card
import app.pulsecast.data.FeedRepository
import app.pulsecast.data.Follows
import app.pulsecast.data.Habits
import app.pulsecast.data.Issue
import app.pulsecast.data.ProStore
import app.pulsecast.data.Ranking
import app.pulsecast.data.Turn
import app.pulsecast.data.WeeklyRepository
import app.pulsecast.stats.Stats
import app.pulsecast.work.FollowAlertWorker
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String) { FOR_YOU("For you"), LATEST("Latest"), TOP("Top"), FOLLOWING("Following"), SAVED("Saved") }

sealed interface Row {
    val key: String
    data class Post(val card: Card) : Row { override val key = card.id }
    data class Ad(val slot: Int) : Row { override val key = "ad-$slot" }
    data class CaughtUp(val newCount: Int) : Row { override val key = "caught-up" }
    /** This week's deep-dive, at the top of For you. */
    data class Weekly(val issue: Issue) : Row { override val key = "weekly-${issue.weekOf}" }
    /** Under search results: older stories (Pro) or the Pro upsell. */
    data class ArchiveNote(val text: String, val upsell: Boolean) : Row { override val key = "archive-note" }
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
    // Prompt Pro
    val pro: Boolean = false,
    val field: String? = null,
    val follows: Set<String> = emptySet(),
    val followAlerts: Boolean = true,
    val issues: List<Issue> = emptyList(),
    /** Older stories matching the search, from the archive (Pro), newest first. */
    val archiveHits: List<Card> = emptyList(),
    val archiveStatus: String? = null,
    val debugUnlock: Boolean = false,
    /** Bumped when Google Play reports something, so the Pro screen redraws. */
    val billingVersion: Int = 0,
)

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FeedRepository(app, BuildConfig.FEED_URL)
    private val habits = Habits(app)
    private val proStore = ProStore(app)
    private val weekly = WeeklyRepository(app)
    private val archive = ArchiveRepository(app)
    val ads = NativeAdPool(app) { _state.update { it.copy(adsVersion = it.adsVersion + 1) } }
    val billing = Billing(app, proStore) {
        _state.update { it.copy(billingVersion = it.billingVersion + 1) }
        proChanged()
    }

    private val _state = MutableStateFlow(
        FeedState(
            saved = habits.saved,
            lastVisitNewest = habits.lastVisitNewest,
            briefing = habits.briefingEnabled,
            breaking = habits.breakingEnabled,
            themeMode = runCatching { ThemeMode.valueOf(habits.themeMode) }.getOrDefault(ThemeMode.SYSTEM),
            pro = proStore.isPro,
            field = proStore.field,
            follows = proStore.follows,
            followAlerts = proStore.followAlerts,
            debugUnlock = proStore.debugUnlock,
        ),
    )
    val state: StateFlow<FeedState> = _state

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { repo.cached() }
            // Only fill from the cache if a network refresh hasn't already landed.
            if (cached != null) _state.update { if (it.cards.isNotEmpty()) it else it.copy(cards = cached.cards, loaded = true, generatedAt = cached.generatedAt) }
            val issues = withContext(Dispatchers.IO) { weekly.cached() }
            _state.update { it.copy(issues = issues) }
            refresh()
        }
        billing.start()
        proChanged()
    }

    /** Re-reads Pro and keeps the follow-alert checks scheduled only while they're needed. */
    private fun proChanged() {
        val pro = proStore.isPro
        if (pro != _state.value.pro) Stats.event(if (pro) "pro_on" else "pro_off")
        _state.update { it.copy(pro = pro) }
        FollowAlertWorker.sync(getApplication(), pro && proStore.followAlerts && proStore.follows.isNotEmpty())
        if (!pro && _state.value.tab == Tab.FOLLOWING) _state.update { it.copy(tab = Tab.FOR_YOU) }
    }

    private var lastRefreshAt = 0L

    /** Called whenever the app comes back to the foreground. */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshAt > 2 * 60_000) refresh()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        lastRefreshAt = System.currentTimeMillis()
        _state.update { it.copy(refreshing = true, error = null) }
        billing.refreshPurchases()
        viewModelScope.launch {
            // The weekly deep-dive is small; fetched alongside the feed. Failures are quiet.
            runCatching { withContext(Dispatchers.IO) { weekly.refresh() } }.onSuccess { issues -> _state.update { it.copy(issues = issues) } }
        }
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

    /** Pro readers never load ads (they'd only waste data). */
    fun adsReady() {
        if (!proStore.isPro) ads.start()
    }

    fun setTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        Stats.event("tab_view", "tab" to tab.name.lowercase())
    }

    fun setTopic(topic: String?) {
        _state.update { it.copy(topic = topic) }
        if (topic != null) Stats.event("topic_filter", "topic" to topic)
    }

    fun setSearching(on: Boolean) {
        _state.update { it.copy(searching = on, query = if (on) it.query else "", archiveHits = if (on) it.archiveHits else emptyList(), archiveStatus = null) }
    }

    private var archiveJob: Job? = null

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        archiveJob?.cancel()
        if (!_state.value.pro) return
        val words = words(q)
        if (words.isEmpty() || q.trim().length < 2) {
            _state.update { it.copy(archiveHits = emptyList(), archiveStatus = null) }
            return
        }
        // Pro: also search every older story, once typing pauses.
        archiveJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(archiveStatus = "Searching older stories…") }
            try {
                val all = withContext(Dispatchers.IO) { archive.all() }
                val inFeed = _state.value.cards.mapTo(HashSet()) { it.id }
                val hits = Ranking.latest(all.filter { it.id !in inFeed && matchesWords(it, words) }).take(200)
                _state.update { it.copy(archiveHits = hits, archiveStatus = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(archiveHits = emptyList(), archiveStatus = "Couldn't search older stories. Check your connection.") }
            }
        }
    }

    private fun words(q: String) = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun matchesWords(c: Card, words: List<String>): Boolean {
        val text = "${c.headline} ${c.summary} ${c.whyItMatters} ${c.keyPoints.joinToString(" ")} ${c.source.name} ${c.topic}".lowercase()
        return words.all { it in text }
    }
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
        val words = words(s.query)
        if (s.searching && words.isNotEmpty()) {
            // Search covers every story, newest first, whatever tab is open.
            cards = Ranking.latest(cards.filter { matchesWords(it, words) })
            val out = ArrayList<Row>()
            cards.forEachIndexed { i, card ->
                out.add(Row.Post(card))
                if (!s.pro && (i + 1) % AD_EVERY == 0) out.add(Row.Ad(i / AD_EVERY))
            }
            if (s.query.trim().length >= 2) {
                if (s.pro) {
                    if (s.archiveHits.isNotEmpty()) {
                        out.add(Row.ArchiveNote("OLDER STORIES · ${s.archiveHits.size}", upsell = false))
                        s.archiveHits.forEach { out.add(Row.Post(it)) }
                    } else if (s.archiveStatus != null) {
                        out.add(Row.ArchiveNote(s.archiveStatus, upsell = false))
                    }
                } else {
                    out.add(Row.ArchiveNote("Search every story Prompt has ever covered, not just the last 10 days.", upsell = true))
                }
            }
            return out
        }
        if (s.topic != null) cards = cards.filter { it.topic == s.topic }
        cards = when (s.tab) {
            Tab.FOR_YOU -> Ranking.forYou(cards, habits.interests())
            Tab.LATEST -> Ranking.latest(cards)
            Tab.TOP -> Ranking.top(cards)
            Tab.FOLLOWING -> Ranking.latest(cards.filter { Follows.matchesAny(it, s.follows) })
            Tab.SAVED -> Ranking.latest(cards.filter { it.id in s.saved })
        }
        val out = ArrayList<Row>()
        latestIssue(s)?.let { if (s.tab == Tab.FOR_YOU && s.topic == null) out.add(Row.Weekly(it)) }
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
            // Pro: no ads anywhere.
            if (!s.pro && s.tab != Tab.SAVED && (i + 1) % AD_EVERY == 0) out.add(Row.Ad(slot++))
        }
        return out
    }

    /** The newest weekly issue, if it's from the last 7 days. */
    fun latestIssue(s: FeedState): Issue? =
        s.issues.firstOrNull()?.takeIf { Duration.between(it.publishedAt, Instant.now()).toDays() < 7 }

    fun visibleTabs(s: FeedState): List<Tab> = Tab.entries.filter { it != Tab.FOLLOWING || (s.pro && s.follows.isNotEmpty()) }

    // ---------- Prompt Pro ----------

    fun setDebugUnlock(on: Boolean) {
        proStore.debugUnlock = on
        _state.update { it.copy(debugUnlock = on) }
        proChanged()
    }

    fun setField(field: String?) {
        proStore.field = field
        _state.update { it.copy(field = proStore.field) }
        field?.let { Stats.event("pro_field", "field" to it) }
    }

    fun addFollow(raw: String) {
        val term = raw.trim().replace(Regex("\\s+"), " ").take(40)
        if (term.length < 2 || _state.value.follows.any { it.equals(term, ignoreCase = true) }) return
        // Stories already in the feed don't alert; only ones that arrive from now on.
        proStore.notifiedIds = proStore.notifiedIds + _state.value.cards.filter { Follows.matches(it, term) }.map { it.id }
        proStore.follows = proStore.follows + term
        _state.update { it.copy(follows = proStore.follows) }
        Stats.event("pro_follow")
        proChanged()
    }

    fun removeFollow(term: String) {
        proStore.follows = proStore.follows - term
        _state.update { it.copy(follows = proStore.follows) }
        proChanged()
    }

    fun setFollowAlerts(on: Boolean) {
        proStore.followAlerts = on
        _state.update { it.copy(followAlerts = on) }
        proChanged()
    }

    /** Asks the Ask server about a card. Throws with a message fit to show. */
    suspend fun ask(card: Card, question: String, history: List<Turn>): String {
        Stats.event("pro_ask")
        return withContext(Dispatchers.IO) { AskClient.ask(card, question, history, proStore.installId) }
    }

    fun subscribed() = proStore.subscribed

    fun proInterest(plan: String) = Stats.event("pro_interest", "plan" to plan)

    override fun onCleared() {
        ads.destroy()
        billing.end()
    }

    companion object {
        const val AD_EVERY = 6
    }
}
